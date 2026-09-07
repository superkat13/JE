package com.pineapple.sageos2.brain

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.pineapple.sageos2.runtime.SageRuntimeHost
import java.util.Locale

class BrainModelImportActivity : Activity() {
    private lateinit var store: BrainModelStore
    private lateinit var status: TextView
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var choose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrainModelStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Sage Brain model"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Choose a local .gguf model. Sage copies it into private app storage as sage-brain.gguf. The original file is left unchanged."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(6), 0, dp(6)) }
        progressText = TextView(this).apply { textSize = 14f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        choose = Button(this).apply {
            text = "Choose GGUF model"
            setOnClickListener { chooseModel() }
        }
        root.addView(status)
        root.addView(progressText)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
        root.addView(choose)
        root.addView(Button(this).apply {
            text = "Back to Sage"
            setOnClickListener { finish() }
        })
        setContentView(root)
        renderCurrent()
    }

    @Suppress("DEPRECATION")
    private fun chooseModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Deprecated in Android API; retained to avoid adding an Activity Result dependency to SageOS core")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        choose.isEnabled = false
        progress.progress = 0
        progressText.text = "Preparing import…"
        status.text = "Importing model. Keep Sage open until this finishes."

        Thread({
            runCatching {
                store.import(uri) { copied, total ->
                    runOnUiThread {
                        if (total != null && total > 0L) {
                            progress.progress = ((copied.toDouble() / total.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
                            progressText.text = "${humanBytes(copied)} / ${humanBytes(total)}"
                        } else {
                            progressText.text = humanBytes(copied)
                        }
                    }
                }
            }.onSuccess { result ->
                val health = SageRuntimeHost.get(this).brainStatus()
                runOnUiThread {
                    choose.isEnabled = true
                    progress.progress = 1000
                    progressText.text = "${humanBytes(result.metadata.sizeBytes)} • SHA-256 ${result.metadata.sha256.take(12)}…"
                    status.text = if (health.ready) {
                        "Brain READY • ${result.metadata.sourceName}"
                    } else {
                        "Model imported, but Brain is not ready: ${health.detail}. If a previous invalid model already failed to load, fully close and reopen Sage before retrying."
                    }
                    Toast.makeText(this, "Sage Brain model imported", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    choose.isEnabled = true
                    progress.progress = 0
                    progressText.text = ""
                    status.text = "Import failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }, "sage-model-import").start()
    }

    private fun renderCurrent() {
        val metadata = store.metadata()
        status.text = when {
            store.isProvisioned() && metadata != null -> "Installed: ${metadata.sourceName} • ${humanBytes(metadata.sizeBytes)}"
            store.isProvisioned() -> "A GGUF model is installed in Sage private storage."
            else -> "No Sage Brain model is installed yet."
        }
        progressText.text = metadata?.sha256?.let { "SHA-256 ${it.take(12)}…" }.orEmpty()
    }

    private fun humanBytes(bytes: Long): String {
        val value = bytes.toDouble()
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_MODEL = 230
    }
}
