package com.hermesandroid.bridge.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway by lazy { HermesGatewayClient.get(applicationContext) }
    private var sessionId: String? = null
    private var currentAssistant: TextView? = null
    private val assistantBuffer = StringBuilder()

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

        gateway.listener = this
        url.setText(gateway.gatewayUrl ?: "")
        token.setText(gateway.gatewayToken ?: "")
        updateUi()

        findViewById<Button>(R.id.btnGatewayConnect).setOnClickListener {
            gateway.configure(url.text.toString(), token.text.toString().takeIf { it.isNotBlank() })
            gateway.connect()
        }
        send.setOnClickListener { submit() }
        stop.setOnClickListener {
            sessionId?.let { sid -> scope.launch { runCatching { gateway.interrupt(sid) } } }
        }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener { newSession() }
    }

    private fun newSession() {
        scope.launch {
            runCatching {
                sessionId = gateway.createSession()
                messages.removeAllViews()
                appendBubble("system", "New Hermes session")
            }.onFailure { toast(it.message ?: "Could not create session") }
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
                "message.complete" -> currentAssistant = null
                "gateway.ready" -> updateUi()
            }
        }
    }

    override fun onServerRequest(id: JsonElement, method: String, params: JsonObject) {
        runOnUiThread {
            appendBubble("approval", "Hermes request: $method")
            if (method.contains("approval", ignoreCase = true)) {
                toast("Hermes is waiting for approval")
            }
        }
    }

    override fun onError(message: String) = runOnUiThread { toast(message) }

    override fun onDestroy() {
        gateway.listener = null
        scope.cancel()
        super.onDestroy()
    }
}
