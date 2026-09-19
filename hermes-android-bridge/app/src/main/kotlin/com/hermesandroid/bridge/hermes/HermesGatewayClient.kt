package com.hermesandroid.bridge.hermes

import android.content.Context
import android.util.Base64
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.net.URLEncoder


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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var state: State = State.Disconnected
        private set
    @Volatile var listener: Listener? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile var lastStoredSessionId: String? = null
        private set
    @Volatile private var shouldReconnect = false
    @Volatile private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    @Volatile private var lastInboundMs = 0L
    @Volatile private var heartbeatJob: Job? = null

    val gatewayUrl: String? get() = prefs.getString(KEY_URL, null)
    val gatewayToken: String? get() = prefs.getString(KEY_TOKEN, null)

    fun configure(url: String, token: String?) {
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_TOKEN, token?.trim()).apply()
    }

    fun connect(resetBackoff: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        disconnect()
        shouldReconnect = true
        if (resetBackoff) reconnectAttempt = 0
        val raw = gatewayUrl?.trim().orEmpty()
        if (raw.isBlank()) return emitError("Gateway URL is empty")
        state = State.Connecting
        listener?.onStateChanged(state)

        val socketUrl = buildSocketUrl(raw)
        val token = gatewayToken?.trim().orEmpty()
        val authenticatedUrl = if (
            token.isNotBlank() &&
            !socketUrl.contains("token=") &&
            !socketUrl.contains("ticket=") &&
            !socketUrl.contains("internal=")
        ) {
            val separator = if (socketUrl.contains("?")) "&" else "?"
            socketUrl + separator + "token=" + URLEncoder.encode(token, "UTF-8")
        } else {
            socketUrl
        }

        val request = Request.Builder()
            .url(authenticatedUrl)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastInboundMs = System.currentTimeMillis()
                state = State.Connected
                listener?.onStateChanged(state)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = System.currentTimeMillis()
                handleFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                socket = null
                emitError("Gateway WebSocket failed: ${t.message ?: "unknown error"}")
                failPending("Gateway failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                socket = null
                state = State.Disconnected
                listener?.onStateChanged(state)
                failPending("Gateway closed: $code $reason")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
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
        val result = frame.getAsJsonObject("result") ?: throw IllegalStateException("Gateway did not return session result")
        lastStoredSessionId = result.get("stored_session_id")?.asString
        return result.get("session_id")?.asString
            ?: throw IllegalStateException("Gateway did not return session_id")
    }

    data class ResumeInfo(val runtimeId: String, val openRequests: JsonArray)

    suspend fun resumeSessionInfo(storedId: String): ResumeInfo {
        val frame = request("session.resume", JsonObject().apply { addProperty("session_id", storedId) })
        val result = frame.getAsJsonObject("result") ?: throw IllegalStateException("Gateway did not return resume result")
        val runtimeId = result.get("session_id")?.asString
            ?: throw IllegalStateException("Gateway did not return runtime session_id")
        val openRequests = result.getAsJsonArray("open_requests") ?: JsonArray()
        lastStoredSessionId = result.get("stored_session_id")?.asString ?: storedId
        return ResumeInfo(runtimeId, openRequests)
    }

    suspend fun attachImageBytes(sessionId: String, filename: String, bytes: ByteArray): JsonObject {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val frame = request("image.attach_bytes", JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("filename", filename)
            addProperty("content_base64", encoded)
        })
        return frame.getAsJsonObject("result") ?: JsonObject()
    }

    suspend fun usage(sessionId: String): JsonObject {
        val frame = request("session.usage", JsonObject().apply { addProperty("session_id", sessionId) })
        return frame.getAsJsonObject("result") ?: JsonObject()
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
        if (frame.get("method")?.asString == "event") {
            val eventParams = frame.getAsJsonObject("params") ?: JsonObject()
            val eventType = eventParams.get("type")?.asString ?: return
            val payload = eventParams.getAsJsonObject("payload") ?: JsonObject()
            if (eventType == "gateway.ready") {
                if (payload.get("heartbeat")?.asBoolean == true) {
                    startHeartbeat()
                }
                scope.launch {
                    runCatching {
                        request(
                            "client.capabilities",
                            JsonObject().apply { addProperty("server_requests", true) },
                            10_000L
                        )
                    }
                }
            }
            listener?.onEvent(eventType, payload)
            return
        }
        val method = frame.get("method")?.asString ?: return
        val params = frame.getAsJsonObject("params") ?: JsonObject()
        if (id != null) listener?.onServerRequest(id, method, params)
        else listener?.onEvent(method, params)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && state == State.Connected) {
                delay(15_000L)
                if (!isActive || state != State.Connected) break
                try {
                    request("ping", JsonObject(), 10_000L)
                    lastInboundMs = System.currentTimeMillis()
                } catch (error: Exception) {
                    emitError("Gateway heartbeat failed: ${error.message ?: "timeout"}")
                    socket?.cancel()
                    break
                }
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectJob?.isActive == true) return
        val delayMs = (1000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) connect(resetBackoff = false)
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun buildSocketUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        url = when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "ws://" + url
        }
        return if (url.contains("/api/ws")) url else url + "/api/ws"
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
