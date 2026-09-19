package com.hermesandroid.bridge.hermes

import android.content.Context
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HermesGatewayClient private constructor(context: Context) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Connected : State
        data class Error(val message: String) : State
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onEvent(method: String, params: JsonObject)
        fun onServerRequest(id: JsonElement, method: String, params: JsonObject)
        fun onError(message: String)
    }

    companion object {
        private const val PREFS = "hermes_native_prefs"
        private const val KEY_URL = "gateway_url"
        private const val KEY_TOKEN = "gateway_token"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        @Volatile private var instance: HermesGatewayClient? = null

        fun get(context: Context): HermesGatewayClient =
            instance ?: synchronized(this) {
                instance ?: HermesGatewayClient(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    @Volatile var state: State = State.Disconnected
        private set
    @Volatile var listener: Listener? = null
    @Volatile private var socket: WebSocket? = null

    val gatewayUrl: String? get() = prefs.getString(KEY_URL, null)
    val gatewayToken: String? get() = prefs.getString(KEY_TOKEN, null)

    fun configure(url: String, token: String?) {
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_TOKEN, token?.trim()).apply()
    }

    fun connect() {
        val raw = gatewayUrl?.trim().orEmpty()
        if (raw.isBlank()) return emitError("Gateway URL is empty")
        disconnect()
        state = State.Connecting
        listener?.onStateChanged(state)

        val request = Request.Builder()
            .url(normalizeWebSocketUrl(raw))
            .apply {
                gatewayToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state = State.Connected
                listener?.onStateChanged(state)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                emitError("Gateway WebSocket failed: ${t.message ?: "unknown error"}")
                failPending("Gateway failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = State.Disconnected
                listener?.onStateChanged(state)
                failPending("Gateway closed: $code $reason")
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "client disconnect")
        socket = null
        state = State.Disconnected
        listener?.onStateChanged(state)
        failPending("Gateway disconnected")
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject {
        val ws = socket ?: throw IllegalStateException("Gateway is not connected")
        if (state != State.Connected) throw IllegalStateException("Gateway is not connected")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        if (!ws.send(frame.toString())) {
            pending.remove(id)
            throw IllegalStateException("Gateway rejected request")
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }.also {
                it.get("error")?.let { error -> throw IllegalStateException(error.toString()) }
            }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun createSession(): String {
        val frame = request("session.create")
        return frame.getAsJsonObject("result")?.get("session_id")?.asString
            ?: throw IllegalStateException("Gateway did not return session_id")
    }

    suspend fun sendPrompt(sessionId: String, text: String) {
        request("prompt.submit", JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("text", text)
        })
    }

    suspend fun interrupt(sessionId: String) {
        request("session.interrupt", JsonObject().apply { addProperty("session_id", sessionId) })
    }

    suspend fun steer(sessionId: String, text: String) {
        request("session.steer", JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("text", text)
        })
    }

    suspend fun activate(sessionId: String) {
        request("session.activate", JsonObject().apply { addProperty("session_id", sessionId) })
    }

    suspend fun history(sessionId: String): JsonArray {
        val frame = request("session.history", JsonObject().apply { addProperty("session_id", sessionId) })
        return frame.getAsJsonObject("result")?.getAsJsonArray("messages") ?: JsonArray()
    }

    suspend fun sessions(): JsonArray {
        val frame = request("session.list")
        return frame.getAsJsonObject("result")?.getAsJsonArray("sessions") ?: JsonArray()
    }

    fun respond(id: JsonElement, result: JsonObject? = null, error: JsonObject? = null) {
        socket?.send(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            when {
                error != null -> add("error", error)
                result != null -> add("result", result)
                else -> add("result", JsonObject())
            }
        }.toString())
    }

    private fun handleFrame(text: String) {
        val frame = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse {
            emitError("Invalid gateway JSON frame")
            return
        }
        val id = frame.get("id")
        if (id != null && pending.containsKey(id.asString)) {
            pending[id.asString]?.complete(frame)
            return
        }
        val method = frame.get("method")?.asString ?: return
        val params = frame.getAsJsonObject("params") ?: JsonObject()
        if (id != null) listener?.onServerRequest(id, method, params)
        else listener?.onEvent(method, params)
    }

    private fun normalizeWebSocketUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        url = when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "ws://$url"
        }
        return if (url.endsWith("/api/ws")) url else "$url/api/ws"
    }

    private fun failPending(message: String) {
        pending.values.forEach { it.complete(JsonObject().apply {
            add("error", JsonObject().apply { addProperty("message", message) })
        }) }
        pending.clear()
    }

    private fun emitError(message: String) {
        state = State.Error(message)
        listener?.onStateChanged(state)
        listener?.onError(message)
    }
}
