package com.pineapple.sageos2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pineapple.sage.SageVoiceService
import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.runtime.SageRuntimeHost
import com.pineapple.sageos2.runtime.SageRuntimeListener

/**
 * The owner-facing Sage home.
 *
 * This screen deliberately knows almost nothing about engineering internals. It is conversation,
 * presence, and a way to talk. The legacy cockpit remains available behind Advanced for the rare
 * times the owner actually wants diagnostics, Core revisioning, scopes, or capability truth.
 */
class SageHomeActivity : Activity() {
    private lateinit var host: SageRuntimeHost
    private lateinit var presence: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var activity: TextView
    private lateinit var input: EditText
    private var brainProgress: BrainProgress? = null
    private val main = Handler(Looper.getMainLooper())
    private var pulseFrame = 0

    private val pulse = object : Runnable {
        override fun run() {
            if (activity.visibility != View.VISIBLE) return
            pulseFrame = (pulseFrame + 1) % 4
            renderPresence(schedulePulse = false)
            if (activity.visibility == View.VISIBLE) main.postDelayed(this, 550L)
        }
    }

    private val listener = object : SageRuntimeListener {
        override fun onStateChanged(snapshot: SageRuntimeSnapshot) = runOnUiThread {
            if (snapshot.state != SageRuntimeState.THINKING_DEEP) brainProgress = null
            renderAll()
        }

        override fun onBrainProgress(progress: BrainProgress) = runOnUiThread {
            if (progress.turnId == host.snapshot().activeTurnId) brainProgress = progress
            renderAll()
        }

        override fun onTextResponse(turnId: Long, text: String) = runOnUiThread {
            brainProgress = null
            renderAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        host = SageRuntimeHost.get(this)
        buildUi()
        requestRuntimePermissionsIfNeeded()
        host.start()
        renderAll()
    }

    override fun onStart() {
        super.onStart()
        host.addListener(listener)
        renderAll()
    }

    override fun onStop() {
        main.removeCallbacks(pulse)
        host.removeListener(listener)
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        header.addView(TextView(this).apply {
            text = "S"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_SAGE, 26)
        }, LinearLayout.LayoutParams(dp(50), dp(50)))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        identity.addView(TextView(this).apply {
            text = "Sage"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        })
        presence = TextView(this).apply {
            text = "Here with you"
            textSize = 14f
            setTextColor(COLOR_MUTED)
        }
        identity.addView(presence)
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(Button(this).apply {
            text = "Advanced"
            contentDescription = "Open Sage advanced controls"
            isAllCaps = false
            textSize = 13f
            setTextColor(COLOR_MUTED)
            background = rounded(COLOR_SURFACE, 18, COLOR_BORDER)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { startActivity(Intent(this@SageHomeActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))

        root.addView(header)

        scroll = ScrollView(this).apply { isFillViewport = true }
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(10), 0, dp(12))
        }
        scroll.addView(conversation)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        activity = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_SAGE_DARK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(COLOR_ACTIVITY, 18)
            visibility = View.GONE
        }
        root.addView(activity, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(8)) })

        input = EditText(this).apply {
            hint = "Message Sage"
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_TEXT)
            textSize = 17f
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(Color.WHITE, 20, COLOR_BORDER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitMessage()
                    true
                } else false
            }
        }
        root.addView(input)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        controls.addView(Button(this).apply {
            text = "Talk"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            background = rounded(COLOR_SURFACE, 20, COLOR_BORDER)
            setOnClickListener {
                host.pushToTalk()
                renderAll()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        controls.addView(Button(this).apply {
            text = "Send"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(COLOR_SAGE, 20)
            setOnClickListener { submitMessage() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)

        setContentView(root)
    }

    private fun submitMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText("")
        host.submitText(message)
        renderAll()
    }

    private fun renderAll() {
        renderConversation()
        renderPresence()
    }

    private fun renderConversation() {
        conversation.removeAllViews()
        val entries = host.recentConversation(60)
        if (entries.isEmpty()) {
            conversation.addView(TextView(this).apply {
                text = "I'm here. What are we doing?"
                textSize = 18f
                setTextColor(COLOR_TEXT)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(COLOR_SAGE_BUBBLE, 20)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                setMargins(0, dp(8), dp(48), dp(8))
            })
        } else {
            entries.forEach { entry ->
                val owner = entry.speaker == ConversationSpeaker.OWNER
                conversation.addView(TextView(this).apply {
                    text = entry.text
                    textSize = 17f
                    setTextColor(COLOR_TEXT)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = rounded(if (owner) COLOR_OWNER_BUBBLE else COLOR_SAGE_BUBBLE, 20)
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (owner) Gravity.END else Gravity.START
                    if (owner) setMargins(dp(48), dp(6), 0, dp(6))
                    else setMargins(0, dp(6), dp(48), dp(6))
                })
            }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderPresence(schedulePulse: Boolean = true) {
        val snapshot = host.snapshot()
        val stage = brainProgress?.stage ?: host.brainStatus().progressStage
        val ui = SageChatPresentation.present(snapshot, stage)
        presence.text = ui.presence

        val message = listOfNotNull(ui.activity, ui.waitingMessage).joinToString(" · ")
        if (message.isBlank()) {
            activity.visibility = View.GONE
            main.removeCallbacks(pulse)
            return
        }

        activity.visibility = View.VISIBLE
        val animated = if (ui.activity != null) ui.activity + ".".repeat(pulseFrame) else message
        activity.text = if (ui.waitingMessage != null && ui.activity != null) "$animated · ${ui.waitingMessage}" else animated
        if (schedulePulse && ui.activity != null) {
            main.removeCallbacks(pulse)
            main.postDelayed(pulse, 550L)
        }
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching { startForegroundService(Intent(this, SageVoiceService::class.java)) }
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_RUNTIME = 221
        private val COLOR_BACKGROUND = Color.rgb(247, 244, 238)
        private val COLOR_SURFACE = Color.rgb(255, 253, 249)
        private val COLOR_BORDER = Color.rgb(220, 215, 205)
        private val COLOR_TEXT = Color.rgb(42, 40, 36)
        private val COLOR_MUTED = Color.rgb(105, 101, 94)
        private val COLOR_SAGE = Color.rgb(104, 139, 111)
        private val COLOR_SAGE_DARK = Color.rgb(70, 104, 77)
        private val COLOR_SAGE_BUBBLE = Color.rgb(234, 241, 233)
        private val COLOR_OWNER_BUBBLE = Color.rgb(238, 230, 216)
        private val COLOR_ACTIVITY = Color.rgb(240, 244, 237)
    }
}
