package com.pineapple.sageos2

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "SageOS 2.0"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Starting Sage safely…"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(14))
        })
        status = TextView(this).apply { textSize = 15f }
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
        status.text = "Opening the Sage runtime…"
        runCatching { SageRuntimeHost.get(this) }
            .onSuccess {
                StartupCrashRecorder.clear(applicationContext)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .onFailure { error -> showStartupFailure("Runtime construction failed", error) }
    }

    private fun showPreviousCrash(report: String) {
        status.text = "SageOS caught the previous crash instead of hiding it.\n\n$report"
        actions.removeAllViews()
        actions.addView(Button(this).apply {
            text = "Retry Sage"
            setOnClickListener {
                StartupCrashRecorder.clear(applicationContext)
                initializeRuntime()
            }
        })
        actions.addView(Button(this).apply {
            text = "Copy crash report"
            setOnClickListener { copy("SageOS startup crash", report) }
        })
    }

    private fun showStartupFailure(label: String, error: Throwable) {
        val report = StartupCrashRecorder.format(label, error)
        StartupCrashRecorder.save(applicationContext, report)
        status.text = report
        actions.removeAllViews()
        actions.addView(Button(this).apply {
            text = "Retry Sage"
            setOnClickListener { initializeRuntime() }
        })
        actions.addView(Button(this).apply {
            text = "Copy startup report"
            setOnClickListener { copy("SageOS startup report", report) }
        })
    }

    private fun copy(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
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
