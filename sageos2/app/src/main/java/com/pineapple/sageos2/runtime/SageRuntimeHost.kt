package com.pineapple.sageos2.runtime

import android.content.Context
import android.os.Build
import com.pineapple.sageos2.action.AndroidDeviceController
import com.pineapple.sageos2.action.AndroidFastActionEngine
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.brain.BrainRouterEngine
import com.pineapple.sageos2.brain.LocalNativeBrainEngine
import com.pineapple.sageos2.capability.AndroidCapabilityBroker
import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilityStatus
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.continuity.TaskRecoveryManager
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.core.SageTurnCoordinator
import com.pineapple.sageos2.diagnostics.DiagnosticReportRenderer
import com.pineapple.sageos2.diagnostics.DiagnosticReportSnapshot
import com.pineapple.sageos2.diagnostics.DiagnosticTaskSummary
import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore
import com.pineapple.sageos2.forge.ForgeClient
import com.pineapple.sageos2.forge.ForgeStore
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.SharedPreferencesConversationHistoryStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.migration.LegacyPersonalityContinuityMigration
import com.pineapple.sageos2.migration.LegacySageMigration
import com.pineapple.sageos2.mode.SharedPreferencesSageModeController
import com.pineapple.sageos2.root.SocketRootBrokerClient
import com.pineapple.sageos2.speech.AndroidSpeechPort
import com.pineapple.sageos2.speech.RemoteWakeWordEngine
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import com.pineapple.sageos2.workflow.SharedPreferencesChickenTonightScopeStore
import com.pineapple.sageos2.workflow.WorkflowRegistryEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

interface SageRuntimeListener {
    fun onStateChanged(snapshot: SageRuntimeSnapshot) = Unit
    fun onTextResponse(turnId: Long, text: String) = Unit
}

class SageRuntimeHost private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<SageRuntimeListener>()

    val traces = SharedPreferencesTraceStore(appContext)
    val tasks = SharedPreferencesTaskContinuityStore(appContext)
    val recovery = TaskRecoveryManager(tasks)
    val core = SharedPreferencesSageCoreStore(appContext)
    val memory = SharedPreferencesTwinMemoryStore(appContext)
    val history = SharedPreferencesConversationHistoryStore(appContext)
    val ownerApps = SharedPreferencesOwnerAppRegistry(appContext)
    val wakeProfiles = SharedPreferencesWakeProfileStore(appContext)
    val modes = SharedPreferencesSageModeController(appContext)
    val forgeStore = ForgeStore(appContext)
    val forge = ForgeClient(appContext, forgeStore)
    val rootBroker = SocketRootBrokerClient()
    val capabilities = AndroidCapabilityBroker(appContext, rootBroker, forge, forgeStore)
    val chickenTonightScope = SharedPreferencesChickenTonightScopeStore(appContext)

    private val legacyMigrationReport = LegacySageMigration(
        appContext,
        core = core,
        memory = memory,
        ownerApps = ownerApps,
        wakeProfiles = wakeProfiles,
        tasks = tasks
    ).runIfNeeded()

    private val legacyPersonalityMigrationReport = LegacyPersonalityContinuityMigration(
        appContext,
        memory = memory
    ).runIfNeeded()

    private val persistentObserver = PersistentRuntimeObserver(traces)
    private val observer = object : RuntimeObserver {
        override fun onDiagnostic(message: String) = persistentObserver.onDiagnostic(message)
        override fun onTypedInputQueued(depth: Int) = persistentObserver.onTypedInputQueued(depth)
        override fun onTypedInputRejected(reason: String) = persistentObserver.onTypedInputRejected(reason)
        override fun onUnhandledFailure(message: String, cause: Throwable?) = persistentObserver.onUnhandledFailure(message, cause)
        override fun onStateChanged(snapshot: SageRuntimeSnapshot) {
            persistentObserver.onStateChanged(snapshot)
            listeners.forEach { it.onStateChanged(snapshot) }
        }
        override fun onTextResponse(turnId: Long, text: String) {
            persistentObserver.onTextResponse(turnId, text)
            listeners.forEach { it.onTextResponse(turnId, text) }
        }
    }

    private val localModel = File(File(appContext.filesDir, "brain"), "sage-brain.gguf")
    private val localBrain = LocalNativeBrainEngine(localModel.absolutePath)
    private val brain = BrainRouterEngine(listOf(localBrain))
    private val wakeEngine = RemoteWakeWordEngine(appContext)
    private val speech = AndroidSpeechPort(appContext, wakeEngine, wakeProfiles)
    private val controller = AndroidDeviceController(appContext, ownerApps, diagnosticReportProvider = { diagnosticReport() })
    private val fastActions = AndroidFastActionEngine(controller)
    private val workflows = WorkflowRegistryEngine(tasks, traces, chickenTonightScope)

    val runtime = SageRuntime(
        coordinator = SageTurnCoordinator(),
        speech = speech,
        brain = brain,
        fastActions = fastActions,
        workflows = workflows,
        scheduler = AndroidRuntimeScheduler(),
        observer = observer,
        sageCore = core,
        twinMemory = memory,
        conversationHistory = history,
        ownerApps = ownerApps,
        modes = modes,
        capabilities = capabilities,
        taskContinuity = tasks
    )

    fun start() {
        if (started.compareAndSet(false, true)) {
            traces.record("legacy_migration", legacyMigrationReport.summary())
            traces.record("legacy_personality_migration", legacyPersonalityMigrationReport.summary())
            val recovered = recovery.recoverInterruptedRuntimeTasks()
            val states = capabilities.snapshot().states
            traces.record(
                "host",
                "Sage runtime starting; recoveredTurns=${recovered.size}; " +
                    "root=${states[Capability.SAGEOS_ROOT_BROKER] == CapabilityStatus.ACTIVE}; " +
                    "forge=${states[Capability.FORGE] == CapabilityStatus.ACTIVE}; " +
                    "deviceOwner=${states[Capability.DEVICE_OWNER] == CapabilityStatus.ACTIVE}; " +
                    "accessibility=${states[Capability.ACCESSIBILITY] == CapabilityStatus.ACTIVE}; " +
                    "chickenScope=${chickenTonightScope.current()?.isUsable() == true}"
            )
            recovered.forEach { task ->
                traces.record("recovery", "recoverable task=${task.taskId} next=${task.nextStep}")
            }
            runtime.start()
        }
    }

    fun submitText(text: String) { start(); runtime.submit(SageEvent.TextSubmitted(text)) }
    fun pushToTalk() { start(); runtime.submit(SageEvent.PushToTalkRequested) }
    fun snapshot(): SageRuntimeSnapshot = runtime.snapshot()
    fun brainStatus() = brain.health()
    fun wakeStatus() = wakeEngine.health()
    fun capabilityStatus() = capabilities.snapshot()
    fun recoverableTasks() = tasks.active()
    fun recentConversation(limit: Int = 40): List<ConversationEntry> = history.recent(limit).entries

    fun diagnosticReport(traceLimit: Int = 100): String {
        val now = System.currentTimeMillis()
        val runtimeSnapshot = snapshot()
        val brainHealth = brainStatus()
        val wakeHealth = wakeStatus()
        val capabilityMap = capabilityStatus().states.mapKeys { it.key.name }.mapValues { it.value.name }
        val mode = modes.current()
        val apps = ownerApps.snapshot()
        val scope = chickenTonightScope.current()
        val scopeStatus = when {
            scope == null -> "NOT_CONFIGURED"
            scope.isExpired(now) -> "EXPIRED"
            scope.isUsable(now) -> "READY"
            else -> "INCOMPLETE"
        }
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val appVersion = buildString {
            append(packageInfo?.versionName ?: "unknown")
            val versionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
                else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toLong()
                }
            }
            versionCode?.let { append(" (").append(it).append(')') }
        }
        return DiagnosticReportRenderer.render(
            DiagnosticReportSnapshot(
                createdAtMs = now,
                appVersion = appVersion,
                packageName = appContext.packageName,
                device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                runtimeState = runtimeSnapshot.state.name,
                listeningMode = runtimeSnapshot.listeningMode.name,
                brainReady = brainHealth.ready,
                brainDetail = brainHealth.detail,
                brainLastLatencyMs = brainHealth.lastLatencyMs,
                wakeReady = wakeHealth.ready,
                wakeEngine = wakeHealth.engine,
                wakeDetail = wakeHealth.detail,
                capabilities = capabilityMap,
                sageCoreRevision = core.current().revision,
                profileId = mode.profileId,
                modeId = mode.modeId,
                ownerAppsRevision = apps.revision,
                ownerAppsCount = apps.apps.size,
                recoverableTasks = recoverableTasks().map { task ->
                    DiagnosticTaskSummary(task.taskId, task.title, task.state.name, task.nextStep)
                },
                chickenTonightScopeStatus = scopeStatus,
                traces = traces.recent(traceLimit.coerceIn(0, 200))
            )
        )
    }

    fun addListener(listener: SageRuntimeListener) { listeners += listener }
    fun removeListener(listener: SageRuntimeListener) { listeners -= listener }

    companion object {
        @Volatile private var instance: SageRuntimeHost? = null
        fun get(context: Context): SageRuntimeHost = instance ?: synchronized(this) {
            instance ?: SageRuntimeHost(context).also { instance = it }
        }
    }
}
