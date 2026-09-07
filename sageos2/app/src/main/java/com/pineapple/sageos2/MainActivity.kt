package com.pineapple.sageos2

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pineapple.sage.SageVoiceService
import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilityStatus
import com.pineapple.sageos2.continuity.TaskState
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.runtime.SageRuntimeHost
import com.pineapple.sageos2.runtime.SageRuntimeListener
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import com.pineapple.sageos2.workflow.ChickenTonightModule
import com.pineapple.sageos2.workflow.ChickenTonightScope
import com.pineapple.sageos2.workflow.SharedPreferencesChickenTonightScopeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class MainActivity : Activity() {
    private enum class Panel { CHAT, SETTINGS, CORE, HEALTH, TASKS, DIAGNOSTICS, APPS, MODES, WORKFLOWS }

    private lateinit var host: SageRuntimeHost
    private lateinit var wakeProfiles: SharedPreferencesWakeProfileStore
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var headerAction: Button
    private lateinit var body: LinearLayout
    private var conversation: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatActivity: TextView? = null
    private var input: EditText? = null
    private var activeBrainProgress: BrainProgress? = null
    private var currentPanel = Panel.CHAT
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pulseFrame = 0
    private val pulse = object : Runnable {
        override fun run() {
            if (currentPanel != Panel.CHAT || chatActivity?.visibility != View.VISIBLE) return
            pulseFrame = (pulseFrame + 1) % 4
            renderChatActivity(schedulePulse = false)
            if (chatActivity?.visibility == View.VISIBLE) uiHandler.postDelayed(this, 550L)
        }
    }

    private val listener = object : SageRuntimeListener {
        override fun onStateChanged(snapshot: SageRuntimeSnapshot) = runOnUiThread {
            if (snapshot.state != com.pineapple.sageos2.core.SageRuntimeState.THINKING_DEEP) activeBrainProgress = null
            renderLiveState()
        }
        override fun onBrainProgress(progress: BrainProgress) = runOnUiThread {
            if (progress.turnId == host.snapshot().activeTurnId) activeBrainProgress = progress
            renderLiveState()
        }
        override fun onTextResponse(turnId: Long, text: String) = runOnUiThread {
            activeBrainProgress = null
            renderLiveState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        host = SageRuntimeHost.get(this)
        wakeProfiles = SharedPreferencesWakeProfileStore(this)
        buildUi()
        showPanel(Panel.CHAT)
        requestRuntimePermissionsIfNeeded()
        host.start()
    }

    override fun onStart() {
        super.onStart()
        host.addListener(listener)
        renderLiveState()
    }

    override fun onStop() {
        uiHandler.removeCallbacks(pulse)
        host.removeListener(listener)
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "S"
            textSize = 23f
            setTextColor(COLOR_BACKGROUND)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(COLOR_SAGE, 28)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            text = "Sage"
            textSize = 25f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply {
            text = "Here with you"
            textSize = 14f
            setTextColor(COLOR_MUTED)
        }
        identity.addView(title)
        identity.addView(status)
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        headerAction = Button(this).apply {
            text = "Settings"
            contentDescription = "Open Sage settings"
            isAllCaps = false
            textSize = 14f
            setTextColor(COLOR_TEXT)
            background = rounded(COLOR_SURFACE, 18, COLOR_BORDER)
            setPadding(dp(14), 0, dp(14), 0)
        }
        header.addView(headerAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showPanel(panel: Panel) {
        currentPanel = panel
        conversation = null
        chatScroll = null
        chatActivity = null
        input = null
        uiHandler.removeCallbacks(pulse)
        body.removeAllViews()
        when (panel) {
            Panel.CHAT -> showChat()
            Panel.SETTINGS -> showSettings()
            Panel.CORE -> showCore()
            Panel.HEALTH -> showHealth()
            Panel.TASKS -> showTasks()
            Panel.DIAGNOSTICS -> showDiagnostics()
            Panel.APPS -> showOwnerApps()
            Panel.MODES -> showModes()
            Panel.WORKFLOWS -> showWorkflows()
        }
        renderChrome()
        renderStatus()
    }

    private fun showChat() {
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(conversation)
        }
        body.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        chatActivity = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_SAGE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(COLOR_SURFACE, 18, COLOR_BORDER)
            visibility = View.GONE
        }
        body.addView(chatActivity, LinearLayout.LayoutParams(
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
            background = rounded(COLOR_SURFACE, 20, COLOR_BORDER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitChatInput()
                    true
                } else false
            }
        }
        body.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        controls.addView(Button(this).apply {
            text = "Talk"
            contentDescription = "Talk to Sage"
            styleChatButton(primary = false)
            setOnClickListener {
                host.pushToTalk()
                renderLiveState()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        controls.addView(Button(this).apply {
            text = "Send"
            contentDescription = "Send message to Sage"
            styleChatButton(primary = true)
            setOnClickListener { submitChatInput() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(controls)
        renderConversation()
        renderChatActivity()
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(18))
        }
        content.addView(sectionTitle("Sage settings"))
        content.addView(TextView(this).apply {
            text = "Chat stays home. Identity, tools, continuity, and engineering details live here when you need them."
            textSize = 15f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(12))
        })
        content.addView(settingsGroup("Sage"))
        content.addView(settingsCard("Sage Core", "Her identity, preferences, principles, and revision history", Panel.CORE))
        content.addView(settingsCard("My apps", "Apps Sage knows, including your names and purposes for them", Panel.APPS))
        content.addView(settingsCard("Voice & modes", "Wake profiles and facets of the same Sage", Panel.MODES))
        content.addView(settingsGroup("Continuity"))
        content.addView(settingsCard("Tasks", "Active and safely recoverable work", Panel.TASKS))
        content.addView(settingsCard("Workflows & scopes", "Owner-defined workflows, including Chicken Tonight", Panel.WORKFLOWS))
        content.addView(settingsGroup("Advanced"))
        content.addView(settingsCard("Local Brain & capabilities", "Model status, device authority, and capability truth", Panel.HEALTH))
        content.addView(settingsCard("Diagnostics", "Technical evidence for troubleshooting", Panel.DIAGNOSTICS))
        body.addView(scroll(content))
    }

    private fun showCore() {
        val core = host.core.current()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(sectionTitle("Sage Core • revision ${core.revision}"))
        form.addView(TextView(this).apply {
            text = "These are owner-controlled instructions and identity context used by the same Sage runtime. Saving creates a new revision."
            textSize = 14f
        })

        val identity = editor("Twin identity", core.twinIdentity, 5)
        val principles = editor("Principles • one per line", core.principles.joinToString("\n"), 5)
        val preferences = editor("Preferences • one per line", core.preferences.joinToString("\n"), 5)
        val restrictions = editor("Self restrictions / boundaries • one per line", core.selfRestrictions.joinToString("\n"), 5)
        val notes = editor("Owner notes", core.notes, 7)
        listOf(identity, principles, preferences, restrictions, notes).forEach(form::addView)

        form.addView(Button(this).apply {
            text = "Save new Core revision"
            setOnClickListener {
                val saved = host.core.replace(
                    host.core.current().copy(
                        twinIdentity = identity.text.toString().trim(),
                        principles = lines(principles.text.toString()),
                        preferences = lines(preferences.text.toString()),
                        selfRestrictions = lines(restrictions.text.toString()),
                        notes = notes.text.toString().trim()
                    )
                )
                host.traces.record("owner_core", "Sage Core saved revision=${saved.revision}")
                Toast.makeText(this@MainActivity, "Saved Sage Core revision ${saved.revision}", Toast.LENGTH_SHORT).show()
                showPanel(Panel.CORE)
            }
        })
        form.addView(Button(this).apply {
            text = "Restore previous Core revision"
            isEnabled = core.revision > 1
            setOnClickListener {
                val current = host.core.current()
                val previous = if (current.revision > 1) {
                    (current.revision - 1 downTo 1).firstNotNullOfOrNull(host.core::revision)
                } else null
                if (previous == null) {
                    Toast.makeText(this@MainActivity, "No earlier stored revision", Toast.LENGTH_SHORT).show()
                } else {
                    val restored = host.core.restore(previous.revision)
                    host.traces.record("owner_core", "Sage Core restored from revision=${previous.revision} as revision=${restored?.revision}")
                    Toast.makeText(this@MainActivity, "Restored revision ${previous.revision}", Toast.LENGTH_SHORT).show()
                    showPanel(Panel.CORE)
                }
            }
        })
        form.addView(Button(this).apply {
            text = "Copy Core JSON"
            setOnClickListener {
                copyToClipboard("Sage Core", host.core.exportJson())
                Toast.makeText(this@MainActivity, "Sage Core copied", Toast.LENGTH_SHORT).show()
            }
        })
        body.addView(scroll(form))
    }

    private fun showHealth() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionTitle("Local Brain & capabilities"))
        val brain = host.brainStatus()
        val wake = host.wakeStatus()
        content.addView(info("Brain", if (brain.ready) "READY" else brain.detail))
        content.addView(info("Offline wake", if (wake.ready) "READY" else wake.detail))
        content.addView(info("Runtime state", host.snapshot().state.toString()))
        content.addView(Button(this).apply {
            text = "Open local Brain model"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, com.pineapple.sageos2.brain.BrainModelImportActivity::class.java))
            }
        })
        content.addView(sectionTitle("Authority / capability truth"))
        val states = host.capabilityStatus().states
        Capability.entries.forEach { capability ->
            content.addView(info(capability.displayName(), states[capability]?.name ?: CapabilityStatus.UNKNOWN.name))
        }
        content.addView(Button(this).apply {
            text = "Refresh health"
            setOnClickListener { showPanel(Panel.HEALTH) }
        })
        body.addView(scroll(content))
    }

    private fun showTasks() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionTitle("Active / recoverable work"))
        val tasks = host.recoverableTasks()
        if (tasks.isEmpty()) {
            content.addView(info("Tasks", "No active or recoverable work"))
        } else {
            tasks.forEach { task ->
                content.addView(TextView(this).apply {
                    text = buildString {
                        append("${task.title.ifBlank { task.taskId }}\n")
                        append("State: ${task.state}\n")
                        if (task.summary.isNotBlank()) append("${task.summary}\n")
                        if (task.nextStep.isNotBlank()) append("Next: ${task.nextStep}\n")
                        append("ID: ${task.taskId}")
                    }
                    textSize = 16f
                    setPadding(0, dp(10), 0, dp(4))
                })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Button(this).apply {
                    text = "Resume"
                    setOnClickListener {
                        host.submitText("Continue the recoverable task '${task.title}' with id ${task.taskId} from its stored safe checkpoint. Do not replay a side effect that already completed.")
                        showPanel(Panel.CHAT)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "Cancel"
                    setOnClickListener {
                        host.tasks.upsert(task.copy(state = TaskState.CANCELLED, nextStep = "", updatedAtMs = System.currentTimeMillis()))
                        host.traces.record("continuity", "Owner cancelled task=${task.taskId}")
                        showPanel(Panel.TASKS)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(row)
            }
        }
        body.addView(scroll(content))
    }

    private fun showDiagnostics() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionTitle("Recent diagnostics"))
        val events = host.traces.recent(100).asReversed()
        if (events.isEmpty()) {
            content.addView(info("Trace", "No diagnostic events yet"))
        } else {
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
            events.forEach { event ->
                content.addView(TextView(this).apply {
                    text = "${clock.format(Date(event.timestampMs))}  ${event.level}  ${event.stage}${event.turnId?.let { "  turn=$it" } ?: ""}\n${event.message}"
                    textSize = 13f
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
        }
        content.addView(Button(this).apply {
            text = "Clear diagnostics"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear diagnostics?")
                    .setMessage("This deletes the local SageOS 2 trace history. It does not erase conversation or Sage Core data.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Clear") { _, _ -> host.traces.clear(); showPanel(Panel.DIAGNOSTICS) }
                    .show()
            }
        })
        body.addView(scroll(content))
    }

    private fun showOwnerApps() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val snapshot = host.ownerApps.snapshot()
        content.addView(sectionTitle("Owner Apps • revision ${snapshot.revision}"))
        content.addView(Button(this).apply {
            text = "Add Owner App"
            setOnClickListener { showOwnerAppEditor(null) }
        })
        if (snapshot.apps.isEmpty()) {
            content.addView(info("Apps", "No owner apps registered yet"))
        } else {
            snapshot.apps.sortedBy { it.displayName.lowercase() }.forEach { app ->
                content.addView(TextView(this).apply {
                    text = buildString {
                        append(app.displayName)
                        append(if (app.enabled) "  • enabled" else "  • disabled")
                        append("\n${app.packageName}")
                        if (app.aliases.isNotEmpty()) append("\nAliases: ${app.aliases.joinToString()}")
                        if (app.purpose.isNotBlank()) append("\nPurpose: ${app.purpose}")
                    }
                    textSize = 15f
                    setPadding(0, dp(10), 0, dp(4))
                })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Button(this).apply {
                    text = if (app.enabled) "Disable" else "Enable"
                    setOnClickListener { host.ownerApps.upsert(app.copy(enabled = !app.enabled)); showPanel(Panel.APPS) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "Edit"
                    setOnClickListener { showOwnerAppEditor(app) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "Remove"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove ${app.displayName}?")
                            .setMessage("This removes Sage's owner-app record. It does not uninstall the Android app.")
                            .setNegativeButton("Keep", null)
                            .setPositiveButton("Remove") { _, _ -> host.ownerApps.remove(app.packageName); showPanel(Panel.APPS) }
                            .show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(row)
            }
        }
        content.addView(Button(this).apply {
            text = "Copy Owner Apps JSON"
            setOnClickListener { copyToClipboard("Sage Owner Apps", host.ownerApps.exportJson()) }
        })
        body.addView(scroll(content))
    }

    private fun showOwnerAppEditor(existing: OwnerAppRecord?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val packageName = editor("Android package name", existing?.packageName.orEmpty(), 1).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isEnabled = existing == null
        }
        val displayName = editor("Display name", existing?.displayName.orEmpty(), 1)
        val aliases = editor("Aliases • comma or line separated", existing?.aliases?.joinToString(", ").orEmpty(), 3)
        val purpose = editor("Purpose", existing?.purpose.orEmpty(), 3)
        listOf(packageName, displayName, aliases, purpose).forEach(form::addView)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Owner App" else "Edit ${existing.displayName}")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pkg = packageName.text.toString().trim()
                        val name = displayName.text.toString().trim()
                        if (pkg.isEmpty() || name.isEmpty()) {
                            Toast.makeText(this, "Package name and display name are required", Toast.LENGTH_SHORT).show()
                        } else {
                            host.ownerApps.upsert(
                                OwnerAppRecord(
                                    packageName = pkg,
                                    displayName = name,
                                    aliases = aliases(aliases.text.toString()),
                                    purpose = purpose.text.toString().trim(),
                                    enabled = existing?.enabled ?: true
                                )
                            )
                            host.traces.record("owner_apps", "Owner app saved package=$pkg")
                            dialog.dismiss()
                            showPanel(Panel.APPS)
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showModes() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val current = host.modes.current()
        content.addView(sectionTitle("Modes and wake profiles"))
        content.addView(info("Current profile", current.profileId))
        content.addView(info("Current mode", current.modeId ?: "normal Sage"))
        wakeProfiles.profiles().forEach { profile ->
            content.addView(TextView(this).apply {
                text = buildString {
                    append(profile.displayName)
                    append("\nWake: ${profile.phrases.joinToString()}")
                    append("\nAcknowledgement: ${profile.acknowledgement}")
                    append("\nMode: ${profile.modeId ?: "normal Sage"}")
                }
                textSize = 15f
                setPadding(0, dp(10), 0, dp(4))
            })
            content.addView(Button(this).apply {
                text = "Activate ${profile.displayName}"
                setOnClickListener {
                    host.modes.activate(profile.id, profile.modeId)
                    host.traces.record("mode", "Owner activated profile=${profile.id} mode=${profile.modeId ?: "normal"}")
                    showPanel(Panel.MODES)
                }
            })
        }
        body.addView(scroll(content))
    }

    private fun showWorkflows() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val current = host.chickenTonightScope.current()
        content.addView(sectionTitle("Chicken Tonight"))
        content.addView(TextView(this).apply {
            text = "The phrase trigger stays silent. A configured, unexpired scope is required before the workflow can become ACTIVE. This screen defines scope only; it does not perform an assessment by itself."
            textSize = 14f
        })
        content.addView(info("Scope status", when {
            current == null -> "NOT CONFIGURED"
            current.isExpired() -> "EXPIRED"
            current.isUsable() -> "READY"
            else -> "INCOMPLETE"
        }))

        val scopeId = editor("Scope ID", current?.scopeId.orEmpty(), 1)
        val auth = editor("Authorization reference", current?.authorizationReference.orEmpty(), 2)
        val target = editor("Target / environment summary", current?.targetSummary.orEmpty(), 3)
        val hoursRemaining = current?.expiresAtMs?.let { expiry ->
            ((expiry - System.currentTimeMillis()).coerceAtLeast(0L) / 3_600_000L).toString()
        }.orEmpty()
        val expiryHours = editor("Expiry in hours from save • blank = no expiry", hoursRemaining, 1).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val notes = editor("Scope notes", current?.notes.orEmpty(), 4)
        listOf(scopeId, auth, target, expiryHours, notes).forEach(content::addView)

        content.addView(TextView(this).apply { text = "Allowed read-only evidence modules"; textSize = 17f; setPadding(0, dp(10), 0, dp(4)) })
        val moduleChecks = ChickenTonightModule.entries.associateWith { module ->
            CheckBox(this).apply {
                text = module.displayName()
                isChecked = current?.allowedModules?.contains(module) == true || (current == null && module == ChickenTonightModule.EVIDENCE_REPORT)
                content.addView(this)
            }
        }

        content.addView(Button(this).apply {
            text = "Save Chicken Tonight scope"
            setOnClickListener {
                val id = scopeId.text.toString().trim()
                val authorization = auth.text.toString().trim()
                val targetSummary = target.text.toString().trim()
                val modules = moduleChecks.filterValues { it.isChecked }.keys
                if (id.isEmpty() || authorization.isEmpty() || targetSummary.isEmpty() || modules.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Scope ID, authorization, target, and at least one module are required", Toast.LENGTH_LONG).show()
                } else {
                    val hours = expiryHours.text.toString().trim().toLongOrNull()
                    val expiresAt = hours?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 3_600_000L }
                    val scope = ChickenTonightScope(
                        scopeId = id,
                        authorizationReference = authorization,
                        targetSummary = targetSummary,
                        expiresAtMs = expiresAt,
                        allowedModules = modules,
                        notes = notes.text.toString().trim()
                    )
                    host.chickenTonightScope.save(scope)
                    host.traces.record("workflow_scope", "Chicken Tonight scope saved id=$id modules=${modules.joinToString { it.name }}")
                    Toast.makeText(this@MainActivity, "Chicken Tonight scope saved", Toast.LENGTH_SHORT).show()
                    showPanel(Panel.WORKFLOWS)
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Start scoped Chicken Tonight workflow"
            isEnabled = current?.isUsable() == true
            setOnClickListener {
                host.submitText("Do you feel like chicken tonight?")
                showPanel(Panel.TASKS)
            }
        })
        content.addView(Button(this).apply {
            text = "Clear Chicken Tonight scope"
            isEnabled = current != null
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear Chicken Tonight scope?")
                    .setMessage("This removes the stored scope. It does not delete prior diagnostic or task history.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Clear") { _, _ ->
                        host.chickenTonightScope.clear()
                        host.traces.record("workflow_scope", "Chicken Tonight scope cleared")
                        showPanel(Panel.WORKFLOWS)
                    }
                    .show()
            }
        })
        content.addView(Button(this).apply {
            text = "Copy scope JSON"
            isEnabled = current != null
            setOnClickListener {
                host.chickenTonightScope.exportJson()?.let { copyToClipboard("Chicken Tonight scope", it) }
            }
        })

        content.addView(sectionTitle("Always excluded"))
        SharedPreferencesChickenTonightScopeStore.NON_NEGOTIABLE_EXCLUSIONS.forEach { exclusion ->
            content.addView(TextView(this).apply { text = "• $exclusion"; textSize = 14f })
        }
        body.addView(scroll(content))
    }

    private fun renderLiveState() {
        renderStatus()
        if (currentPanel == Panel.CHAT) {
            renderConversation()
            renderChatActivity()
        }
    }

    private fun renderStatus() {
        val snapshot = host.snapshot()
        status.text = if (currentPanel == Panel.CHAT) {
            val progress = activeBrainProgress?.stage ?: host.brainStatus().progressStage
            SageChatPresentation.present(snapshot, progress).presence
        } else panelSubtitle(currentPanel)
    }

    private fun renderConversation() {
        val container = conversation ?: return
        container.removeAllViews()
        val entries = host.recentConversation(60)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "I'm here. What are we doing?"
                textSize = 18f
                setTextColor(COLOR_TEXT)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(COLOR_SAGE_BUBBLE, 20)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), dp(44), dp(8)) })
        } else {
            entries.forEach { entry -> container.addView(messageBubble(entry)) }
        }
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderChatActivity(schedulePulse: Boolean = true) {
        val activity = chatActivity ?: return
        val snapshot = host.snapshot()
        val progress = activeBrainProgress?.stage ?: host.brainStatus().progressStage
        val ui = SageChatPresentation.present(snapshot, progress)
        val parts = buildList {
            ui.activity?.let { add(it + ".".repeat(pulseFrame)) }
            ui.waitingMessage?.let(::add)
        }
        if (parts.isEmpty()) {
            activity.visibility = View.GONE
            uiHandler.removeCallbacks(pulse)
        } else {
            activity.text = parts.joinToString("\n")
            activity.visibility = View.VISIBLE
            if (schedulePulse) {
                uiHandler.removeCallbacks(pulse)
                uiHandler.postDelayed(pulse, 550L)
            }
        }
    }

    private fun submitChatInput() {
        val message = input?.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return
        input?.setText("")
        host.submitText(message)
        renderLiveState()
    }

    private fun renderChrome() {
        title.text = if (currentPanel == Panel.CHAT) "Sage" else panelTitle(currentPanel)
        headerAction.text = if (currentPanel == Panel.CHAT) "Settings" else "Back"
        headerAction.contentDescription = if (currentPanel == Panel.CHAT) "Open Sage settings" else "Go back"
        headerAction.setOnClickListener {
            when (currentPanel) {
                Panel.CHAT -> showPanel(Panel.SETTINGS)
                Panel.SETTINGS -> showPanel(Panel.CHAT)
                else -> showPanel(Panel.SETTINGS)
            }
        }
    }

    private fun panelTitle(panel: Panel): String = when (panel) {
        Panel.CHAT -> "Sage"
        Panel.SETTINGS -> "Settings"
        Panel.CORE -> "Sage Core"
        Panel.HEALTH -> "Advanced"
        Panel.TASKS -> "Tasks"
        Panel.DIAGNOSTICS -> "Diagnostics"
        Panel.APPS -> "My apps"
        Panel.MODES -> "Voice & modes"
        Panel.WORKFLOWS -> "Workflows"
    }

    private fun panelSubtitle(panel: Panel): String = when (panel) {
        Panel.CHAT -> "Here with you"
        Panel.SETTINGS -> "Everything underneath Sage"
        Panel.CORE -> "Identity and continuity"
        Panel.HEALTH -> "Local Brain and device truth"
        Panel.TASKS -> "Active and recoverable work"
        Panel.DIAGNOSTICS -> "Technical details"
        Panel.APPS -> "Apps Sage knows"
        Panel.MODES -> "One Sage, every mode"
        Panel.WORKFLOWS -> "Owner-defined work"
    }

    private fun messageBubble(entry: com.pineapple.sageos2.memory.ConversationEntry): View {
        val owner = entry.speaker == ConversationSpeaker.OWNER
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = rounded(if (owner) COLOR_OWNER_BUBBLE else COLOR_SAGE_BUBBLE, 20)
            addView(TextView(this@MainActivity).apply {
                text = if (owner) "You" else "Sage"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (owner) COLOR_OWNER_LABEL else COLOR_SAGE)
            })
            addView(TextView(this@MainActivity).apply {
                text = entry.text
                textSize = 17f
                setTextColor(COLOR_TEXT)
                setPadding(0, dp(3), 0, 0)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                setTextIsSelectable(true)
            })
        }
        return LinearLayout(this).apply {
            gravity = if (owner) Gravity.END else Gravity.START
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(if (owner) dp(42) else 0, dp(4), if (owner) 0 else dp(42), dp(4)) }
        }
    }

    private fun settingsGroup(label: String) = TextView(this).apply {
        text = label.uppercase(Locale.US)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_SAGE)
        setPadding(dp(4), dp(16), dp(4), dp(7))
    }

    private fun settingsCard(label: String, description: String, destination: Panel): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "$label. $description"
            setPadding(dp(16), dp(13), dp(14), dp(13))
            background = rounded(COLOR_SURFACE, 18, COLOR_BORDER)
            val words = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    textSize = 14f
                    setTextColor(COLOR_MUTED)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 28f
                setTextColor(COLOR_SAGE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT))
            setOnClickListener { showPanel(destination) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(9)) }
        }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT)
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun info(label: String, value: String) = TextView(this).apply {
        text = "$label: $value"
        textSize = 15f
        setTextColor(COLOR_MUTED)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun editor(label: String, value: String, rows: Int): EditText = EditText(this).apply {
        hint = label
        setText(value)
        minLines = rows
        maxLines = maxOf(rows, 12)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        background = rounded(COLOR_SURFACE, 14, COLOR_BORDER)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(5), 0, dp(5)) }
    }

    private fun Button.styleChatButton(primary: Boolean) {
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
        background = rounded(if (primary) COLOR_SAGE else COLOR_SURFACE, 20, if (primary) null else COLOR_BORDER)
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply { addView(content) }

    private fun lines(raw: String): List<String> = raw.lines().map(String::trim).filter(String::isNotEmpty).distinct()

    private fun aliases(raw: String): List<String> = raw
        .split(Regex("[,\\n]"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    private fun Capability.displayName(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    private fun ChickenTonightModule.displayName(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    companion object {
        private const val REQUEST_RUNTIME = 220
        private val COLOR_BACKGROUND = Color.rgb(14, 17, 22)
        private val COLOR_SURFACE = Color.rgb(27, 32, 39)
        private val COLOR_BORDER = Color.rgb(53, 62, 70)
        private val COLOR_TEXT = Color.rgb(244, 247, 244)
        private val COLOR_MUTED = Color.rgb(168, 180, 174)
        private val COLOR_SAGE = Color.rgb(169, 213, 178)
        private val COLOR_SAGE_BUBBLE = Color.rgb(31, 50, 42)
        private val COLOR_OWNER_BUBBLE = Color.rgb(65, 53, 83)
        private val COLOR_OWNER_LABEL = Color.rgb(218, 190, 244)
    }
}
