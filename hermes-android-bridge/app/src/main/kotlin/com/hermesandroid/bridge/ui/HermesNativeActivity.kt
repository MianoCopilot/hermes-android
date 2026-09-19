package com.hermesandroid.bridge.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import android.util.Base64
import com.google.gson.*
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.hermes.HermesGatewayClient
import com.hermesandroid.bridge.hermes.TermuxGatewayStarter
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
    private lateinit var steerInput: EditText
    private lateinit var btnSteer: Button
    private lateinit var modelButton: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway by lazy { HermesGatewayClient.get(applicationContext) }
    private var sessionId: String? = null
    private var currentAssistant: TextView? = null
    private var currentReasoning: TextView? = null
    private val assistantBuffer = StringBuilder()
    private var ttsEnabled = true
    private var selectedModel: String? = null
    private var selectedProvider: String? = null
    private var selectedReasoning: String = "inherit"
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var activeDialog: AlertDialog? = null
    private var activeRequestId: JsonElement? = null
    private var lastToolLine: TextView? = null
    private val prefs by lazy { getSharedPreferences("hermes_native_session", MODE_PRIVATE) }
    private val IMAGE_PICK_REQUEST = 5101
    private val FILE_PICK_REQUEST = 5102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hermes_native)
        applySystemBarInsets(findViewById(R.id.hermesRoot))
        status = findViewById(R.id.tvGatewayStatus)
        messages = findViewById(R.id.messageList)
        input = findViewById(R.id.etPrompt)
        send = findViewById(R.id.btnSendPrompt)
        stop = findViewById(R.id.btnStopTurn)
        url = findViewById(R.id.etGatewayUrl)
        token = findViewById(R.id.etGatewayToken)
        voice = findViewById(R.id.btnVoice)
        ttsButton = findViewById(R.id.btnTts)
        steerInput = findViewById(R.id.etSteer)
        btnSteer = findViewById(R.id.btnSteer)
        modelButton = findViewById(R.id.btnModel)
        selectedModel = prefs.getString("model", null)
        selectedProvider = prefs.getString("provider", null)
        selectedReasoning = prefs.getString("reasoning_effort", "inherit") ?: "inherit"

        gateway.listener = this
        url.setText(gateway.gatewayUrl ?: "ws://127.0.0.1:9119")
        token.setText(gateway.gatewayToken ?: "")
        updateUi()
        if (prefs.getString("stored_session_id", null) != null) appendBubble("system", "Saved session available • tap RESUME")

        findViewById<Button>(R.id.btnDeviceBridge).setOnClickListener {
            startActivity(android.content.Intent(this, com.hermesandroid.bridge.MainActivity::class.java))
        }

        findViewById<Button>(R.id.btnStartLocalHermes).setOnClickListener {
            url.setText("ws://127.0.0.1:9119")
            val localToken = generateLocalGatewayToken()
            token.setText(localToken)
            if (!TermuxGatewayStarter.startLocalGateway(this, localToken)) {
                toast("Could not start Ubuntu Hermes backend. Enable Termux external-command permission.")
                return@setOnClickListener
            }
            gateway.configure(url.text.toString(), localToken)
            gateway.connect()
            toast("Starting Hermes Agent in Ubuntu on 127.0.0.1:9119...")
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
        findViewById<Button>(R.id.btnImage).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.btnFile).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { showUsage() }
        btnSteer.setOnClickListener { submitSteer() }
        voice.setOnClickListener { startVoiceInput() }
        ttsButton.setOnClickListener { ttsEnabled = !ttsEnabled; ttsButton.text = if (ttsEnabled) "TTS ON" else "TTS OFF" }
        modelButton.setOnClickListener { showModelPicker() }
        initSpeech()
        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) textToSpeech?.language = Locale.getDefault()
        }
        updateModelButton()
    }

    private fun applySystemBarInsets(root: android.view.View) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun pickFile() {
        if (sessionId == null) {
            toast("Create or resume a Hermes session first")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, FILE_PICK_REQUEST) }
            .onFailure { error -> toast("Could not open file picker: " + error.message) }
    }

    private fun pickImage() {
        if (sessionId == null) {
            toast("Create or resume a Hermes session first")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(intent, IMAGE_PICK_REQUEST) }
            .onFailure { error -> toast("Could not open image picker: " + error.message) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val selectedUri = data?.data ?: return
        when (requestCode) {
            IMAGE_PICK_REQUEST -> attachImage(selectedUri)
            FILE_PICK_REQUEST -> attachFile(selectedUri)
        }
    }

    private fun attachFile(uri: Uri) {
        val sid = sessionId ?: return
        scope.launch {
            runCatching {
                val filename = queryDisplayName(uri) ?: "attachment"
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = readAttachmentBytes(uri, 20 * 1024 * 1024)
                val result = if (mimeType.equals("application/pdf", true) || filename.endsWith(".pdf", true)) {
                    gateway.attachPdfBytes(sid, filename, bytes)
                } else {
                    gateway.attachFileBytes(sid, filename, mimeType, bytes)
                }
                runOnUiThread {
                    val label = result.get("text")?.asString
                        ?: result.get("message")?.asString
                        ?: result.get("ref_text")?.asString
                        ?: "Attached " + filename
                    appendBubble("system", "📎 " + label)
                }
            }.onFailure { error -> toast(error.message ?: "File attach failed") }
        }
    }

    private fun readAttachmentBytes(uri: Uri, maxBytes: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not read file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) out.write(buffer, 0, read)
                if (out.size() > maxBytes) throw IllegalStateException("File is larger than " + (maxBytes / (1024 * 1024)) + " MB")
            }
            return out.toByteArray()
        }
    }

    private fun attachImage(uri: Uri) {
        val sid = sessionId ?: return
        scope.launch {
            runCatching {
                val filename = queryDisplayName(uri) ?: "image.jpg"
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) out.write(buffer, 0, read)
                        if (out.size() > 12 * 1024 * 1024) {
                            throw IllegalStateException("Image is larger than 12 MB")
                        }
                    }
                    out.toByteArray()
                } ?: throw IllegalStateException("Could not read image")
                val result = gateway.attachImageBytes(sid, filename, bytes)
                runOnUiThread {
                    val label = result.get("text")?.asString ?: result.get("message")?.asString ?: "Image attached"
                    appendBubble("system", "🖼 " + label)
                }
            }.onFailure { error -> toast(error.message ?: "Image attach failed") }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun showModelPicker() {
        scope.launch {
            runCatching {
                val providers = gateway.modelOptions()
                val items = mutableListOf<Pair<String, Pair<String, String>>>()
                providers.forEach { providerJson ->
                    if (!providerJson.isJsonObject) return@forEach
                    val provider = providerJson.asJsonObject
                    val slug = provider.get("slug")?.asString.orEmpty()
                    val providerName = provider.get("name")?.asString?.takeIf { it.isNotBlank() } ?: slug
                    provider.getAsJsonArray("models")?.forEach { model ->
                        val modelName = model.asString
                        if (modelName.isNotBlank()) {
                            items += (providerName + " / " + modelName) to (slug to modelName)
                        }
                    }
                }
                val visible = items.distinctBy { it.second }.take(80)
                val labels = visible.map { it.first }.toTypedArray()
                runOnUiThread {
                    val choices = arrayOf("INHERIT", "LOW", "MEDIUM", "HIGH")
                    AlertDialog.Builder(this@HermesNativeActivity)
                        .setTitle("Hermes model")
                        .setSingleChoiceItems(
                            labels,
                            visible.indexOfFirst { it.second.second == selectedModel && it.second.first == selectedProvider }.coerceAtLeast(-1)
                        ) { dialog, which ->
                            selectedProvider = visible[which].second.first
                            selectedModel = visible[which].second.second
                            dialog.dismiss()
                            showReasoningPicker()
                        }
                        .setNeutralButton("INHERIT") { _, _ ->
                            selectedProvider = null
                            selectedModel = null
                            selectedReasoning = "inherit"
                            persistModelSelection()
                            updateModelButton()
                        }
                        .show()
                }
            }.onFailure { error -> toast(error.message ?: "Could not load models") }
        }
    }

    private fun showReasoningPicker() {
        val options = arrayOf("inherit", "low", "medium", "high")
        val checked = options.indexOf(selectedReasoning).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Reasoning effort")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                selectedReasoning = options[which]
                persistModelSelection()
                updateModelButton()
                dialog.dismiss()
            }
            .show()
    }

    private fun persistModelSelection() {
        prefs.edit()
            .putString("model", selectedModel)
            .putString("provider", selectedProvider)
            .putString("reasoning_effort", selectedReasoning)
            .apply()
    }

    private fun updateModelButton() {
        modelButton.text = when {
            selectedModel.isNullOrBlank() -> "MODEL"
            else -> selectedModel!!.take(12)
        }
    }

    private fun showUsage() {
        val sid = sessionId ?: run {
            toast("No active Hermes session")
            return
        }
        scope.launch {
            runCatching {
                val usage = gateway.usage(sid)
                val lines = usage.entrySet().map { entry -> entry.key + ": " + entry.value.toString() }
                runOnUiThread {
                    AlertDialog.Builder(this@HermesNativeActivity)
                        .setTitle("Session usage")
                        .setMessage(lines.joinToString("\n").ifBlank { "No usage data" })
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.onFailure { error -> toast(error.message ?: "Could not read usage") }
        }
    }

    private fun newSession() {
        scope.launch {
            runCatching {
                sessionId = gateway.createSession(selectedModel, selectedProvider, selectedReasoning)
                gateway.lastStoredSessionId?.let { prefs.edit().putString("stored_session_id", it).apply() }
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
                val resume = gateway.resumeSessionInfo(storedId)
                sessionId = resume.runtimeId
                prefs.edit().putString("stored_session_id", gateway.lastStoredSessionId ?: storedId).apply()
                val history = gateway.history(sessionId!!)
                resume.openRequests.forEach { request ->
                    if (request.isJsonObject) {
                        val requestObject = request.asJsonObject
                        val requestId = requestObject.get("id") ?: return@forEach
                        val requestMethod = requestObject.get("method")?.asString ?: return@forEach
                        val requestParams = requestObject.getAsJsonObject("params") ?: JsonObject()
                        onServerRequest(requestId, requestMethod, requestParams)
                    }
                }
                messages.removeAllViews()
                renderHistory(history)
            }.onFailure { toast(it.message ?: "Could not resume session") }
        }
    }

    private fun submitSteer() {
        val sid = sessionId ?: run {
            toast("Create or resume a Hermes session first")
            return
        }
        val text = steerInput.text.toString().trim()
        if (text.isBlank()) return
        steerInput.text?.clear()
        scope.launch {
            runCatching { gateway.steer(sid, text) }
                .onSuccess { toast("Steering instruction queued") }
                .onFailure { toast(it.message ?: "Steer failed") }
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        input.text?.clear()
        appendBubble("user", text)
        scope.launch {
            runCatching {
                val sid = sessionId ?: gateway.createSession(selectedModel, selectedProvider, selectedReasoning).also {
                    sessionId = it
                    gateway.lastStoredSessionId?.let { stored -> prefs.edit().putString("stored_session_id", stored).apply() }
                }
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

    private fun generateLocalGatewayToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun renderHistory(history: JsonArray) {
        history.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val o = item.asJsonObject
            val role = o.get("role")?.asString ?: "assistant"
            val text = o.get("text")?.asString
                ?: o.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?: ""
            val displayKind = o.get("display_kind")?.asString.orEmpty()
            val name = o.get("name")?.asString.orEmpty()
            val rendered = when {
                displayKind == "tool" && name.isNotBlank() -> "⚙ $name" + if (text.isNotBlank()) " • $text" else ""
                text.isNotBlank() -> text
                else -> ""
            }
            if (rendered.isNotBlank()) appendBubble(role.ifBlank { "assistant" }, rendered)
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
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                voice.text = "MIC"
                if (spoken.isNotBlank()) {
                    input.setText(spoken)
                    input.setSelection(input.text.length)
                    if (gateway.state == HermesGatewayClient.State.Connected) submit()
                }
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
                "message.delta" -> {
                    val text = params.get("text")?.asString.orEmpty()
                    assistantBuffer.append(text)
                    currentAssistant?.text = assistantBuffer.toString()
                }
                "reasoning.delta", "thinking.delta" -> {
                    val text = params.get("text")?.asString.orEmpty()
                    if (currentReasoning == null) currentReasoning = appendBubble("reasoning", "")
                    currentReasoning?.append(text)
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
                    val finalText = params.get("text")?.asString.orEmpty()
                    if (assistantBuffer.isBlank() && finalText.isNotBlank()) {
                        assistantBuffer.append(finalText)
                        currentAssistant?.text = assistantBuffer.toString()
                    }
                    currentAssistant = null
                    currentReasoning = null
                    if (ttsEnabled && assistantBuffer.isNotBlank()) {
                        val spoken = assistantBuffer.toString()
                        scope.launch {
                            val backendSpoke = runCatching { gateway.voiceTts(spoken) }.isSuccess
                            if (!backendSpoke) {
                                textToSpeech?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "hermes-response")
                            }
                        }
                    }
                }
                "gateway.ready" -> {
                    updateUi()
                    val saved = prefs.getString("stored_session_id", null)
                    if (sessionId == null && !saved.isNullOrBlank()) {
                        scope.launch { resumeSession(saved) }
                    }
                }
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
        val command = p.get("command")?.asString.orEmpty()
        val description = p.get("description")?.asString.orEmpty()
        val choices = p.getAsJsonArray("choices")
            ?.asList()
            ?.map { item -> item.asString }
            ?.filter { value -> value.isNotBlank() }
            ?.ifEmpty { listOf("once", "deny") }
            ?: listOf("once", "deny")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hermes approval")
            .setMessage(
                when {
                    description.isBlank() -> command
                    command.isBlank() -> description
                    else -> description + "\n\n" + command
                }
            )
            .setItems(
                choices.map { value -> value.uppercase(Locale.getDefault()) }.toTypedArray()
            ) { _, which ->
                gateway.respond(id, JsonObject().apply {
                    addProperty("choice", choices[which])
                    addProperty("all", false)
                })
                activeRequestId = null
                activeDialog = null
            }
            .setNegativeButton("CANCEL") { _, _ ->
                gateway.respond(id, JsonObject())
                activeRequestId = null
                activeDialog = null
            }
            .create()

        dialog.setOnCancelListener {
            gateway.respond(id, JsonObject())
            activeRequestId = null
            activeDialog = null
        }
        dialog.show()
        return dialog
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
