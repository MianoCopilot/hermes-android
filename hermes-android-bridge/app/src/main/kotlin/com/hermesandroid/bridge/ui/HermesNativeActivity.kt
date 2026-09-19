package com.hermesandroid.bridge.ui

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import java.util.Locale
import com.google.gson.*
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.hermes.HermesGatewayClient
import kotlinx.coroutines.*

class HermesNativeActivity : Activity(), HermesGatewayClient.Listener {
    private lateinit var status: TextView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var voice: Button
    private lateinit var ttsButton: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway by lazy { HermesGatewayClient.get(applicationContext) }
    private var sessionId: String? = null
    private var currentAssistant: TextView? = null
    private val assistantBuffer = StringBuilder()
    private var ttsEnabled = true
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var activeDialog: AlertDialog? = null
    private var lastToolLine: TextView? = null
    private val prefs by lazy { getSharedPreferences("hermes_native_session", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hermes_native)
        status = findViewById(R.id.tvGatewayStatus)
        messages = findViewById(R.id.messageList)
        input = findViewById(R.id.etPrompt)
        send = findViewById(R.id.btnSendPrompt)
        stop = findViewById(R.id.btnStopTurn)
        url = findViewById(R.id.etGatewayUrl)
        token = findViewById(R.id.etGatewayToken)
        voice = findViewById(R.id.btnVoice)
        ttsButton = findViewById(R.id.btnTts)

        gateway.listener = this
        url.setText(gateway.gatewayUrl ?: "")
        token.setText(gateway.gatewayToken ?: "")
        updateUi()
        if (prefs.getString("stored_session_id", null) != null) appendBubble("system", "Saved session available • tap RESUME")

        findViewById<Button>(R.id.btnDeviceBridge).setOnClickListener {
            startActivity(android.content.Intent(this, com.hermesandroid.bridge.MainActivity::class.java))
        }

        findViewById<Button>(R.id.btnGatewayConnect).setOnClickListener {
            gateway.configure(url.text.toString(), token.text.toString().takeIf { it.isNotBlank() })
            gateway.connect()
        }
        send.setOnClickListener { submit() }
        stop.setOnClickListener {
            sessionId?.let { sid -> scope.launch { runCatching { gateway.interrupt(sid) } } }
        }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener { newSession() }
        findViewById<Button>(R.id.btnResumeSession).setOnClickListener { showSessions() }
        voice.setOnClickListener { startVoiceInput() }
        ttsButton.setOnClickListener { ttsEnabled = !ttsEnabled; ttsButton.text = if (ttsEnabled) "TTS ON" else "TTS OFF" }
        initSpeech()
        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) textToSpeech?.language = Locale.getDefault()
        }
    }

    private fun newSession() {
        scope.launch {
            runCatching {
                sessionId = gateway.createSession()
                prefs.edit().remove("stored_session_id").apply()
                messages.removeAllViews()
                appendBubble("system", "New Hermes session")
            }.onFailure { toast(it.message ?: "Could not create session") }
        }
    }

    private fun showSessions() {
        scope.launch {
            runCatching {
                val sessions = gateway.sessions()
                val rows = sessions.asList()
                if (rows.isEmpty()) {
                    toast("No saved Hermes sessions")
                    return@runCatching
                }
                val labels = rows.map { row ->
                    val o = row.asJsonObject
                    val title = o.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "Untitled"
                    val preview = o.get("preview")?.asString?.replace("\\n", " ")?.take(70) ?: ""
                    "$title${if (preview.isNotBlank()) " • $preview" else ""}"
                }.toTypedArray()
                runOnUiThread {
                    AlertDialog.Builder(this@HermesNativeActivity)
                        .setTitle("Resume Hermes session")
                        .setItems(labels) { _, which ->
                            val storedId = rows[which].asJsonObject.get("id")?.asString
                            if (!storedId.isNullOrBlank()) resumeSession(storedId)
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                }
            }.onFailure { toast(it.message ?: "Could not load sessions") }
        }
    }

    private fun resumeSession(storedId: String) {
        scope.launch {
            runCatching {
                sessionId = gateway.resumeSession(storedId)
                prefs.edit().putString("stored_session_id", storedId).apply()
                val history = gateway.history(sessionId!!)
                messages.removeAllViews()
                renderHistory(history)
            }.onFailure { toast(it.message ?: "Could not resume session") }
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        input.text?.clear()
        appendBubble("user", text)
        scope.launch {
            runCatching {
                val sid = sessionId ?: gateway.createSession().also { sessionId = it }
                assistantBuffer.setLength(0)
                currentAssistant = appendBubble("assistant", "")
                gateway.sendPrompt(sid, text)
            }.onFailure { toast(it.message ?: "Prompt failed") }
        }
    }

    private fun appendBubble(role: String, initial: String): TextView {
        return TextView(this).apply {
            text = initial
            setPadding(18, 14, 18, 14)
            textSize = 16f
            gravity = Gravity.START
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(
                when (role) {
                    "user" -> 0xFF223322.toInt()
                    "assistant" -> 0xFF202020.toInt()
                    "approval" -> 0xFF3A2A16.toInt()
                    else -> 0xFF171717.toInt()
                }
            )
            messages.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 6
                bottomMargin = 6
            })
        }
    }

    private fun renderHistory(history: JsonArray) {
        history.forEach { item ->
            val o = item.asJsonObject
            val role = o.get("role")?.asString ?: "assistant"
            val text = when {
                o.get("content")?.isJsonPrimitive == true -> o.get("content").asString
                o.get("text")?.isJsonPrimitive == true -> o.get("text").asString
                else -> ""
            }
            if (text.isNotBlank()) appendBubble(role, text)
        }
    }

    private fun initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voice.isEnabled = false
            voice.text = "MIC N/A"
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voice.text = "LISTENING..." }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voice.text = "MIC" }
            override fun onError(error: Int) { voice.text = "MIC"; toast("Speech recognition error: $error") }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) { input.setText(text); input.setSelection(input.text.length) }
                voice.text = "MIC"
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4001)
            return
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure { toast("Could not start microphone: ${it.message}") }
    }

    private fun updateUi() {
        status.text = when (val s = gateway.state) {
            HermesGatewayClient.State.Disconnected -> "● Disconnected"
            HermesGatewayClient.State.Connecting -> "◌ Connecting..."
            HermesGatewayClient.State.Connected -> "● Connected"
            is HermesGatewayClient.State.Error -> "✕ " + s.message.take(48)
        }
        send.isEnabled = gateway.state == HermesGatewayClient.State.Connected
        stop.isEnabled = sessionId != null && gateway.state == HermesGatewayClient.State.Connected
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onStateChanged(state: HermesGatewayClient.State) = runOnUiThread { updateUi() }

    override fun onEvent(method: String, params: JsonObject) {
        runOnUiThread {
            when (method) {
                "message.delta", "reasoning.delta", "thinking.delta" -> {
                    val text = params.get("text")?.asString
                        ?: params.getAsJsonObject("payload")?.get("text")?.asString
                        ?: ""
                    assistantBuffer.append(text)
                    currentAssistant?.text = assistantBuffer.toString()
                }
                "tool.start" -> {
                    val name = params.get("name")?.asString ?: "tool"
                    val preview = params.get("preview")?.asString ?: params.get("args_text")?.asString.orEmpty()
                    val suffix = if (preview.isNotBlank()) " • " + preview else ""
                    lastToolLine = appendBubble("system", "⚙ " + name + suffix)
                }
                "tool.complete" -> {
                    val name = params.get("name")?.asString ?: "tool"
                    val summary = params.get("summary")?.asString ?: params.get("result_text")?.asString.orEmpty()
                    val suffix = if (summary.isNotBlank()) " • " + summary else ""
                    lastToolLine?.text = "✓ " + name + suffix
                    lastToolLine = null
                }
                "status.update", "notification.show" -> {
                    val text = params.get("text")?.asString.orEmpty()
                    if (text.isNotBlank()) appendBubble("system", text)
                }
                "reasoning.available" -> {
                    val text = params.get("text")?.asString.orEmpty()
                    if (text.isNotBlank()) appendBubble("system", "🧠 reasoning available: " + text.take(400))
                }
                "voice.transcript" -> {
                    val text = params.get("text")?.asString.orEmpty()
                    if (text.isNotBlank()) input.setText(text)
                }
                "request.cancel" -> {
                    activeDialog?.dismiss()
                    activeDialog = null
                }
                "message.complete" -> {
                    currentAssistant = null
                    if (ttsEnabled && assistantBuffer.isNotBlank()) textToSpeech?.speak(assistantBuffer.toString(), TextToSpeech.QUEUE_FLUSH, null, "hermes-response")
                }
                "gateway.ready" -> updateUi()
            }
        }
    }

    override fun onServerRequest(id: JsonElement, method: String, params: JsonObject) {
        runOnUiThread {
            activeDialog?.dismiss()
            activeDialog = when (method) {
                "approval" -> showApprovalDialog(id, params)
                "clarify" -> showClarifyDialog(id, params)
                "sudo", "secret", "vault.unlock_prompt", "vault.code", "terminal.read", "preview.read", "window.read", "preview.act", "tour" -> showValueDialog(id, method, params)
                else -> { toast("Hermes request: $method"); gateway.respond(id, error = JsonObject().apply { addProperty("code", -32601); addProperty("message", "Unsupported mobile request: $method") }); null }
            }
        }
    }

    private fun showApprovalDialog(id: JsonElement, p: JsonObject): AlertDialog {
        val command = p.get("command")?.asString ?: ""
        val description = p.get("description")?.asString ?: "Approval required"
        val choices = p.getAsJsonArray("choices")?.map { it.asString }?.ifEmpty { listOf("once", "deny") } ?: listOf("once", "deny")
        val names = choices.map { it.uppercase(Locale.getDefault()) }.toTypedArray()
        return AlertDialog.Builder(this).setTitle("Hermes approval")
            .setMessage((if (description.isBlank()) "" else description + "\\n\\n") + command)
            .setItems(names) { _, which ->
                val choice = choices[which]
                gateway.respond(id, JsonObject().apply { addProperty("choice", choice); addProperty("all", false) })
            }
            .setOnCancelListener { gateway.respond(id, JsonObject()) }
            .create().also { it.show() }
    }

    private fun showClarifyDialog(id: JsonElement, p: JsonObject): AlertDialog {
        val questions = p.getAsJsonArray("questions")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val fields = linkedMapOf<String, EditText>()
        if (questions != null && questions.size() > 0) {
            questions.forEach { q ->
                val qo = q.asJsonObject
                val qid = qo.get("qid")?.asString ?: return@forEach
                val label = TextView(this).apply { text = qo.get("question")?.asString ?: qid; setPadding(0, 12, 0, 6) }
                val field = EditText(this).apply { hint = "Answer" }
                box.addView(label); box.addView(field); fields[qid] = field
            }
        } else {
            box.addView(TextView(this).apply { text = p.get("question")?.asString ?: "Hermes needs an answer."; setPadding(0, 12, 0, 8) })
            fields["__single__"] = EditText(this)
        }
        val d = AlertDialog.Builder(this).setTitle("Hermes asks").setView(box)
            .setPositiveButton("SEND") { _, _ ->
                if (fields.containsKey("__single__")) gateway.respond(id, JsonObject().apply { addProperty("answer", fields["__single__"]?.text?.toString().orEmpty()) })
                else gateway.respond(id, JsonObject().apply { add("answers", JsonObject().also { a -> fields.forEach { (k,v) -> a.addProperty(k, v.text.toString()) } }) })
            }
            .setNegativeButton("CANCEL") { _, _ -> gateway.respond(id, JsonObject()) }
            .create()
        d.setOnCancelListener { gateway.respond(id, JsonObject()) }
        d.show()
        return d
    }

    private fun showValueDialog(id: JsonElement, method: String, p: JsonObject): AlertDialog {
        val field = EditText(this).apply {
            hint = when (method) { "sudo" -> "Password"; "secret" -> p.get("prompt")?.asString ?: "Secret"; else -> "Value" }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val d = AlertDialog.Builder(this).setTitle("Hermes: $method").setView(field)
            .setPositiveButton("SEND") { _, _ -> gateway.respond(id, JsonObject().apply { addProperty("value", field.text.toString()) }) }
            .setNegativeButton("CANCEL") { _, _ -> gateway.respond(id, JsonObject().apply { addProperty("value", "") }) }
            .create()
        d.setOnCancelListener { gateway.respond(id, JsonObject().apply { addProperty("value", "") }) }
        d.show()
        return d
    }


        runOnUiThread {
            appendBubble("approval", "Hermes request: $method")
            if (method.contains("approval", ignoreCase = true)) {
                toast("Hermes is waiting for approval")
            }
        }
    }

    override fun onError(message: String) = runOnUiThread { toast(message) }

    override fun onDestroy() {
        activeDialog?.dismiss()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        gateway.listener = null
        scope.cancel()
        super.onDestroy()
    }
}
