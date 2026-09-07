package com.pineapple.sageos2

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pineapple.sageos2.runtime.SageRuntimeHost
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash-safe front door for SageOS 2.
 *
 * The owner cockpit must never vanish just because one runtime subsystem cannot initialize on a
 * particular Android build. This activity renders first, then constructs the runtime. It also
 * records an uncaught main-process crash so the next launch can show exact evidence instead of
 * repeating a half-second close loop.
 */
class SageLaunchActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private var currentMessage: String = ""
    private var technicalDetails: String? = null
    private var detailsVisible = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupCrashRecorder.install(applicationContext)
        buildUi()

        val previous = StartupCrashRecorder.lastCrash(applicationContext)
        if (previous != null) showPreviousCrash(previous)
        else main.post { initializeRuntime() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(44), dp(24), dp(24))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        root.addView(TextView(this).apply {
            text = "S"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_BACKGROUND)
            gravity = Gravity.CENTER
            background = rounded(COLOR_SAGE, 40)
        }, LinearLayout.LayoutParams(dp(78), dp(78)))
        root.addView(TextView(this).apply {
            text = "Sage"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Right here with you"
            textSize = 16f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(22))
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(actions)
        setContentView(root)
    }

    private fun initializeRuntime() {
        actions.removeAllViews()
        technicalDetails = null
        detailsVisible = false
        showMessage("I'm getting ready…")
        runCatching { SageRuntimeHost.get(this) }
            .onSuccess {
                StartupCrashRecorder.clear(applicationContext)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .onFailure { error -> showStartupFailure("Runtime construction failed", error) }
    }

    private fun showPreviousCrash(report: String) {
        technicalDetails = report
        detailsVisible = false
        showMessage("I couldn't stay open last time, but your data is still here. Try me again, or open the technical details if we need them.")
        actions.removeAllViews()
        actions.addView(Button(this).apply {
            text = "Try again"
            styleButton(primary = true)
            setOnClickListener {
                StartupCrashRecorder.clear(applicationContext)
                initializeRuntime()
            }
        })
        actions.addView(Button(this).apply {
            text = "Show technical details"
            styleButton(primary = false)
            setOnClickListener {
                detailsVisible = !detailsVisible
                text = if (detailsVisible) "Hide technical details" else "Show technical details"
                renderMessage()
            }
        })
        actions.addView(Button(this).apply {
            text = "Copy technical details"
            styleButton(primary = false)
            setOnClickListener { copy("SageOS startup crash", report) }
        })
    }

    private fun showStartupFailure(label: String, error: Throwable) {
        val report = StartupCrashRecorder.format(label, error)
        StartupCrashRecorder.save(applicationContext, report)
        technicalDetails = report
        detailsVisible = false
        showMessage("I couldn't finish opening, but I kept the details and your Sage data is untouched.")
        actions.removeAllViews()
        actions.addView(Button(this).apply {
            text = "Try again"
            styleButton(primary = true)
            setOnClickListener { initializeRuntime() }
        })
        actions.addView(Button(this).apply {
            text = "Show technical details"
            styleButton(primary = false)
            setOnClickListener {
                detailsVisible = !detailsVisible
                text = if (detailsVisible) "Hide technical details" else "Show technical details"
                renderMessage()
            }
        })
        actions.addView(Button(this).apply {
            text = "Copy technical details"
            styleButton(primary = false)
            setOnClickListener { copy("SageOS startup report", report) }
        })
    }

    private fun showMessage(message: String) {
        currentMessage = message
        renderMessage()
    }

    private fun renderMessage() {
        status.text = buildString {
            append(currentMessage)
            if (detailsVisible) technicalDetails?.let { append("\n\nTechnical details\n\n").append(it) }
        }
    }

    private fun Button.styleButton(primary: Boolean) {
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
        background = rounded(if (primary) COLOR_SAGE else COLOR_SURFACE, 18, if (primary) null else COLOR_BORDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { setMargins(0, dp(7), 0, 0) }
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun copy(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_BACKGROUND = Color.rgb(14, 17, 22)
        private val COLOR_SURFACE = Color.rgb(27, 32, 39)
        private val COLOR_BORDER = Color.rgb(53, 62, 70)
        private val COLOR_TEXT = Color.rgb(244, 247, 244)
        private val COLOR_MUTED = Color.rgb(168, 180, 174)
        private val COLOR_SAGE = Color.rgb(169, 213, 178)
    }
}

private object StartupCrashRecorder {
    private const val PREFS = "sageos2_startup_guard"
    private const val KEY_LAST_CRASH = "last_crash"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { save(appContext, format("Uncaught process crash on ${thread.name}", error)) }
                previous?.uncaughtException(thread, error)
            }
            installed = true
        }
    }

    fun lastCrash(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_CRASH, null)
        ?.takeIf { it.isNotBlank() }

    fun save(context: Context, report: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, report.take(24_000))
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_CRASH)
            .apply()
    }

    fun format(label: String, error: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return buildString {
            append("SageOS startup report\n")
            append("Time: ").append(stamp).append('\n')
            append("Stage: ").append(label).append('\n')
            append("Error: ").append(error.javaClass.name).append(": ").append(error.message.orEmpty()).append("\n\n")
            append(writer.toString())
        }
    }
}
