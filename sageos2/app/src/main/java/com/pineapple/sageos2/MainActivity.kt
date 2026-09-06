package com.pineapple.sageos2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pineapple.sage.SageVoiceService
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.runtime.SageRuntimeHost
import com.pineapple.sageos2.runtime.SageRuntimeListener

open class MainActivity : Activity() {
    private lateinit var host: SageRuntimeHost
    private lateinit var status: TextView
    private lateinit var conversation: TextView
    private lateinit var input: EditText

    private val listener = object : SageRuntimeListener {
        override fun onStateChanged(snapshot: SageRuntimeSnapshot) = runOnUiThread { render() }
        override fun onTextResponse(turnId: Long, text: String) = runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = SageRuntimeHost.get(this)
        buildUi()
        requestRuntimePermissionsIfNeeded()
        host.start()
        render()
    }

    override fun onStart() {
        super.onStart()
        host.addListener(listener)
        render()
    }

    override fun onStop() {
        host.removeListener(listener)
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        root.addView(TextView(this).apply { text = "SageOS 2.0"; textSize = 28f })
        root.addView(TextView(this).apply { text = "Virtual twin runtime"; textSize = 18f })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 12, 0, 12) }
        root.addView(status)

        conversation = TextView(this).apply { textSize = 18f; setPadding(8, 8, 8, 8) }
        val scroll = ScrollView(this).apply { addView(conversation) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this).apply {
            hint = "Message Sage"
            textSize = 18f
            maxLines = 4
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) { input.setText(""); host.submitText(text); render() }
            }
        }
        val talk = Button(this).apply {
            text = "Push to talk"
            setOnClickListener { host.pushToTalk(); render() }
        }
        controls.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(talk, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        setContentView(root)
    }

    private fun render() {
        val snapshot = host.snapshot()
        val brain = host.brainStatus()
        val wake = host.wakeStatus()
        status.text = buildString {
            append("State: ${snapshot.state}   Input: ${snapshot.listeningMode}\n")
            append("Brain: ${if (brain.ready) "ready" else brain.detail}\n")
            append("Wake: ${if (wake.ready) "ready" else "push-to-talk until KWS is packaged"}")
        }
        conversation.text = host.recentConversation(40).joinToString("\n\n") { entry ->
            val who = if (entry.speaker == ConversationSpeaker.OWNER) "You" else "Sage"
            "$who: ${entry.text}"
        }.ifBlank { "Sage is ready for text chat." }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startRuntimeService() else requestPermissions(needed.toTypedArray(), REQUEST_RUNTIME)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME) startRuntimeService()
    }

    private fun startRuntimeService() {
        runCatching { startForegroundService(Intent(this, SageVoiceService::class.java)) }
    }

    companion object { private const val REQUEST_RUNTIME = 220 }
}
