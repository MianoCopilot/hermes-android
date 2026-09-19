package com.hermesandroid.bridge.hermes

import android.content.Context
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
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
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_DEADLINE_MS = 45_000L
        @Volatile private var instance: HermesGatewayClient? = null

        fun get(context: Context): HermesGatewayClient =
            instance ?: synchronized(this) {
                instance ?: HermesGatewayClient(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val respondedRequests = ConcurrentHashMap.newKeySet<String>()
    private val heartbeatPings = ConcurrentHashMap.newKeySet<String>()

    @Volatile var state: State = State.Disconnected
        private set
    @Volatile var listener: Listener? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile var lastStoredSessionId: String? = null
        private set
    @Volatile private var shouldReconnect = false
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var lastLivenessMs = 0L
    private var reconnectAttempt = 0

    val gatewayUrl: String? get() = prefs.getString(KEY_URL, null)
    val gatewayToken: String? get() = prefs.getString(KEY_TOKEN, null)

    fun configure(url: String, token: String?) {
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_TOKEN, token?.trim()).apply()
    }

    fun connect(resetBackoff: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        shouldReconnect = true
        if (resetBackoff) reconnectAttempt = 0
        stopHeartbeat()
        closeSocketOnly()

        val raw = gatewayUrl?.trim().orEmpty()
        if (raw.isBlank()) {
            emitError("Gateway URL is empty")
            return
        }

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

        val request = Request.Builder().url(authenticatedUrl).build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                lastLivenessMs = System.currentTimeMillis()
                state = State.Connected
                listener?.onStateChanged(state)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                handleFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket && socket != null) return
                stopHeartbeat()
                socket = null
                emitError("Gateway WebSocket failed: " + (t.message ?: "unknown error"))
                failPending("Gateway failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket && socket != null) return
                stopHeartbeat()
                socket = null
                state = State.Disconnected
                listener?.onStateChanged(state)
                failPending("Gateway closed: " + code + " " + reason)
                scheduleReconnect()
            }
        })
        socket = newSocket
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        closeSocketOnly()
        respondedRequests.clear()
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
            withTimeout(timeoutMs) { deferred.await() }.also { response ->
                response.get("error")?.let { error ->
                    val message = if (error.isJsonObject) error.asJsonObject.get("message")?.asString else null
                    throw IllegalStateException(message ?: error.toString())
                }
            }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun createSession(model: String? = null, provider: String? = null, reasoningEffort: String? = null): String {
        val params = JsonObject().apply {
            model?.takeIf { it.isNotBlank() }?.let { addProperty("model", it) }
            provider?.takeIf { it.isNotBlank() }?.let { addProperty("provider", it) }
            reasoningEffort?.takeIf { it.isNotBlank() && !it.equals("inherit", true) }?.let { addProperty("reasoning_effort", it) }
        }
        val result = request("session.create", params).getAsJsonObject("result")
            ?: throw IllegalStateException("Gateway did not return session result")
        lastStoredSessionId = result.get("stored_session_id")?.asString
        return result.get("session_id")?.asString ?: throw IllegalStateException("Gateway did not return session_id")
    }

    suspend fun modelOptions(): JsonArray {
        val result = request("model.options").getAsJsonObject("result") ?: return JsonArray()
        return result.getAsJsonArray("providers") ?: JsonArray()
    }

    data class ResumeInfo(val runtimeId: String, val openRequests: JsonArray)

    suspend fun resumeSessionInfo(storedId: String): ResumeInfo {
        val result = request("session.resume", JsonObject().apply { addProperty("session_id", storedId) })
            .getAsJsonObject("result") ?: throw IllegalStateException("Gateway did not return resume result")
        lastStoredSessionId = result.get("stored_session_id")?.asString ?: storedId
        val runtimeId = result.get("session_id")?.asString ?: throw IllegalStateException("Gateway did not return runtime session_id")
        val openRequests = result.getAsJsonArray("open_requests") ?: JsonArray()
        return ResumeInfo(runtimeId, openRequests)
    }

    suspend fun attachImageBytes(sessionId: String, filename: String, bytes: ByteArray): JsonObject {
        val result = request("image.attach_bytes", JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("filename", filename)
            addProperty("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }).getAsJsonObject("result")
        return result ?: JsonObject()
    }

    suspend fun usage(sessionId: String): JsonObject =
        request("session.usage", JsonObject().apply { addProperty("session_id", sessionId) })
            .getAsJsonObject("result") ?: JsonObject()

    suspend fun voiceTts(text: String): JsonObject =
        request("voice.tts", JsonObject().apply { addProperty("text", text) }, 60_000L)
            .getAsJsonObject("result") ?: JsonObject()

    suspend fun voiceToggle(action: String): JsonObject =
        request("voice.toggle", JsonObject().apply { addProperty("action", action) }, 20_000L)
            .getAsJsonObject("result") ?: JsonObject()

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

    suspend fun history(sessionId: String): JsonArray =
        request("session.history", JsonObject().apply { addProperty("session_id", sessionId) })
            .getAsJsonObject("result")?.getAsJsonArray("messages") ?: JsonArray()

    suspend fun sessions(): JsonArray =
        request("session.list", JsonObject().apply { addProperty("limit", 50) })
            .getAsJsonObject("result")?.getAsJsonArray("sessions") ?: JsonArray()

    fun respond(id: JsonElement, result: JsonObject? = null, error: JsonObject? = null) {
        val key = id.toString()
        if (!respondedRequests.add(key)) return
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            when {
                error != null -> add("error", error)
                result != null -> add("result", result)
                else -> add("result", JsonObject())
            }
        }.toString()
        val sent = socket?.send(payload) ?: false
        if (!sent) respondedRequests.remove(key)
    }

    private fun handleFrame(text: String) {
        val frame = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse {
            emitError("Invalid gateway JSON frame")
            return
        }
        val id = frame.get("id")
        if (id != null && heartbeatPings.remove(id.asString)) {
            lastLivenessMs = System.currentTimeMillis()
            return
        }
        if (id != null && pending.containsKey(id.asString)) {
            pending[id.asString]?.complete(frame)
            return
        }
        if (frame.get("method")?.asString == "event") {
            val eventParams = frame.getAsJsonObject("params") ?: JsonObject()
            val eventType = eventParams.get("type")?.asString ?: return
            val payload = eventParams.getAsJsonObject("payload") ?: JsonObject()
            if (eventType == "gateway.ready") {
                if (payload.get("heartbeat")?.asBoolean == true) startHeartbeat()
                scope.launch {
                    runCatching {
                        request("client.capabilities", JsonObject().apply { addProperty("server_requests", true) }, 10_000L)
                    }
                }
            }
            listener?.onEvent(eventType, payload)
            return
        }
        val method = frame.get("method")?.asString ?: return
        val params = frame.getAsJsonObject("params") ?: JsonObject()
        if (id != null) listener?.onServerRequest(id, method, params) else listener?.onEvent(method, params)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && state == State.Connected) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || state != State.Connected) break
                val ws = socket ?: break
                val pingId = "heartbeat-" + UUID.randomUUID().toString()
                if (!ws.send(JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", pingId)
                    addProperty("method", "ping")
                    add("params", JsonObject())
                }.toString())) {
                    emitError("Gateway heartbeat send failed")
                    ws.cancel()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatPings.clear()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectJob?.isActive == true) return
        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            if (shouldReconnect) connect(resetBackoff = false)
        }
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

    private fun closeSocketOnly() {
        try { socket?.close(1000, "client reconnect") } catch (_: Exception) { }
        socket = null
    }

    private fun failPending(message: String) {
        pending.values.forEach { deferred ->
            deferred.complete(JsonObject().apply {
                add("error", JsonObject().apply { addProperty("message", message) })
            })
        }
        pending.clear()
    }

    private fun emitError(message: String) {
        state = State.Error(message)
        listener?.onStateChanged(state)
        listener?.onError(message)
    }
}