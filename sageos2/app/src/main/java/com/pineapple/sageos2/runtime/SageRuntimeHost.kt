package com.pineapple.sageos2.runtime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.pineapple.sage.SageSherpaRecognitionService
import com.pineapple.sage.SageSpeechBackendState
import com.pineapple.sageos2.action.AndroidDeviceController
import com.pineapple.sageos2.action.AndroidFastActionEngine
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.brain.BrainRouterEngine
import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.brain.BrainModelStore
import com.pineapple.sageos2.brain.LocalNativeBrainEngine
import com.pineapple.sageos2.capability.AndroidCapabilityBroker
import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilityStatus
import com.pineapple.sageos2.continuity.OwnerContinuityImporter
import com.pineapple.sageos2.continuity.OwnerContinuityImportResult
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskRecoveryManager
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.core.SageTurnCoordinator
import com.pineapple.sageos2.diagnostics.DiagnosticReportRenderer
import com.pineapple.sageos2.diagnostics.DiagnosticReportSnapshot
import com.pineapple.sageos2.diagnostics.DiagnosticTaskSummary
import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore
import com.pineapple.sageos2.forge.ForgeClient
import com.pineapple.sageos2.forge.ForgeStore
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.learning.SharedPreferencesLearnedPhraseStore
import com.pineapple.sageos2.localapi.SageLocalApiServer
import com.pineapple.sageos2.localapi.SageRuntimePromptGateway
import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.SharedPreferencesConversationHistoryStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.maintenance.SelfCareManager
import com.pineapple.sageos2.maintenance.SelfCareSnapshot
import com.pineapple.sageos2.migration.LegacyPersonalityContinuityMigration
import com.pineapple.sageos2.migration.LegacySageMigration
import com.pineapple.sageos2.mode.SharedPreferencesSageModeController
import com.pineapple.sageos2.personal.SagePersonalCommandEngine
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
    fun onBrainProgress(progress: BrainProgress) = Unit
    fun onTextResponse(turnId: Long, text: String) = Unit
    fun onTypedInputRejected(reason: String) = Unit
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
    val learnedPhrases = SharedPreferencesLearnedPhraseStore(appContext)
    val wakeProfiles = SharedPreferencesWakeProfileStore(appContext)
    val modes = SharedPreferencesSageModeController(appContext)
    val forgeStore = ForgeStore(appContext)
    val forge = ForgeClient(appContext, forgeStore)
    val rootBroker = SocketRootBrokerClient()
    val chickenTonightScope = SharedPreferencesChickenTonightScopeStore(appContext)
    private val ownerContinuityImporter = OwnerContinuityImporter(appContext, core, memory)

    @Volatile private var legacyMigrationReport = runLegacyMigration()
    @Volatile private var legacyPersonalityMigrationReport = runLegacyPersonalityMigration()

    private fun runLegacyMigration() = LegacySageMigration(
        appContext,
        core = core,
        memory = memory,
        ownerApps = ownerApps,
        wakeProfiles = wakeProfiles,
        tasks = tasks
    ).runIfNeeded()

    private fun runLegacyPersonalityMigration() = LegacyPersonalityContinuityMigration(
        appContext,
        memory = memory
    ).runIfNeeded()

    private val persistentObserver = PersistentRuntimeObserver(traces)
    private val observer = object : RuntimeObserver {
        override fun onDiagnostic(message: String) = persistentObserver.onDiagnostic(message)
        override fun onTypedInputQueued(depth: Int) = persistentObserver.onTypedInputQueued(depth)
        override fun onTypedInputRejected(reason: String) {
            persistentObserver.onTypedInputRejected(reason)
            listeners.forEach { it.onTypedInputRejected(reason) }
        }
        override fun onUnhandledFailure(message: String, cause: Throwable?) = persistentObserver.onUnhandledFailure(message, cause)
        override fun onStateChanged(snapshot: SageRuntimeSnapshot) {
            persistentObserver.onStateChanged(snapshot)
            listeners.forEach { it.onStateChanged(snapshot) }
        }
        override fun onBrainProgress(progress: BrainProgress) {
            persistentObserver.onBrainProgress(progress)
            listeners.forEach { it.onBrainProgress(progress) }
        }
        override fun onTextResponse(turnId: Long, text: String) {
            persistentObserver.onTextResponse(turnId, text)
            listeners.forEach { it.onTextResponse(turnId, text) }
        }
    }

    private val brainModelStore = BrainModelStore(appContext)
    private val localModel = brainModelStore.modelFile()
    private val localBrain = LocalNativeBrainEngine(localModel.absolutePath)
    private val brain = BrainRouterEngine(listOf(localBrain))
    private val wakeEngine = RemoteWakeWordEngine(appContext) { detail ->
        traces.record("wake_recovery", detail.take(800))
    }
    private val speech = AndroidSpeechPort(appContext, wakeEngine, wakeProfiles)
    private val controller = AndroidDeviceController(appContext, ownerApps, diagnosticReportProvider = { diagnosticReport() })
    val capabilities = AndroidCapabilityBroker(appContext, rootBroker, forge, forgeStore, deviceController = controller)
    private val fastActions = AndroidFastActionEngine(controller)
    private val workflows = WorkflowRegistryEngine(tasks, traces, chickenTonightScope)
    private val personalCommands = SagePersonalCommandEngine(memory, learnedPhrases, modes)
    private val selfCare = SelfCareManager(tasks)
    private val localApi by lazy {
        SageLocalApiServer(
            gateway = SageRuntimePromptGateway(this),
            logger = { detail -> traces.record("local_api", detail.take(800)) }
        )
    }
    private val selfCareHandler = Handler(Looper.getMainLooper())
    private val selfCareRunnable = object : Runnable {
        override fun run() {
            if (!started.get()) return
            runCatching { runSelfCareCheck() }
                .onFailure { traces.record("self_care", "check failed: ${it.message ?: it::class.java.simpleName}") }
            selfCareHandler.postDelayed(this, SELF_CARE_INTERVAL_MS)
        }
    }

    val runtime = SageRuntime(
        coordinator = SageTurnCoordinator(
            com.pineapple.sageos2.core.SageCommandRouter(
                personal = personalCommands,
                ownerApps = ownerApps
            )
        ),
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
            val autoResumeCandidate = recovery.autoResumeCandidate()
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
            SageSherpaRecognitionService.prewarm(appContext)
            runtime.start()
            localApi.start()
            scheduleRecoveredResume(autoResumeCandidate)
            selfCareHandler.removeCallbacks(selfCareRunnable)
            selfCareHandler.postDelayed(selfCareRunnable, SELF_CARE_INITIAL_DELAY_MS)
        }
    }

    private fun scheduleRecoveredResume(candidate: TaskCheckpoint?) {
        if (candidate == null) return
        selfCareHandler.postDelayed({
            if (!started.get()) return@postDelayed
            if (runtime.snapshot().state != SageRuntimeState.IDLE_WAKE) {
                traces.record("recovery", "auto-resume deferred because Sage is busy; task=${candidate.taskId}")
                return@postDelayed
            }
            val marked = recovery.markAutoResumeAttempted(candidate.taskId) ?: return@postDelayed
            traces.record(
                "recovery",
                "auto-resuming task=${marked.taskId} depth=${marked.metadata["recoveryDepth"] ?: "1"}"
            )
            runtime.resumeRecoveredTask(marked)
        }, RECOVERY_RESUME_DELAY_MS)
    }

    private fun runSelfCareCheck() {
        legacyMigrationReport = runLegacyMigration()
        legacyPersonalityMigrationReport = runLegacyPersonalityMigration()
        val brainHealth = brainStatus()
        val wakeHealth = wakeStatus()
        val findings = selfCare.reconcile(
            SelfCareSnapshot(
                brainReady = brainHealth.ready,
                brainDetail = brainHealth.detail,
                wakeReady = wakeHealth.ready,
                wakeDetail = wakeHealth.detail,
                coreRevision = core.current().revision,
                legacyCorePresent = legacyMigrationReport.legacyCorePresent,
                migrationErrors = legacyMigrationReport.errors
            )
        )
        traces.record(
            "self_care",
            if (findings.isEmpty()) "healthy; no unresolved self-care findings"
            else "findings=${findings.joinToString(",") { it.code }}" +
                if (!wakeHealth.ready) "; wake=${wakeHealth.detail.take(500)}" else ""
        )
    }

    fun submitText(text: String) { start(); runtime.submit(SageEvent.TextSubmitted(text)) }

    /**
     * Submit a local-API text turn only when it can begin immediately.
     *
     * Keeping the readiness check and submit under the runtime monitor prevents a localhost
     * request from being queued behind another owner turn and then receiving the wrong response.
     */
    fun submitTextIfReady(text: String): Boolean {
        start()
        synchronized(runtime) {
            return when (runtime.snapshot().state) {
                SageRuntimeState.IDLE_WAKE, SageRuntimeState.FOLLOW_UP_LISTENING -> {
                    runtime.submit(SageEvent.TextSubmitted(text))
                    true
                }
                else -> false
            }
        }
    }

    fun pushToTalk() { start(); runtime.submit(SageEvent.PushToTalkRequested) }
    fun snapshot(): SageRuntimeSnapshot = runtime.snapshot()
    fun brainStatus() = brain.health()
    fun wakeStatus() = wakeEngine.health()
    fun capabilityStatus() = capabilities.snapshot()
    fun recoverableTasks() = tasks.active()
    fun recentConversation(limit: Int = 40): List<ConversationEntry> = history.recent(limit).entries

    fun importOwnerContinuity(raw: String): OwnerContinuityImportResult {
        val result = ownerContinuityImporter.import(raw)
        traces.record(
            "owner_continuity",
            "import source=${result.source.take(120)} already=${result.alreadyImported} coreRevision=${result.coreRevision} memories=${result.memoriesApplied}"
        )
        runCatching { runSelfCareCheck() }
            .onFailure { traces.record("self_care", "post-import check failed: ${it.message ?: it::class.java.simpleName}") }
        return result
    }

    fun forgetLearnedPhrase(phrase: String): Boolean {
        val normalized = SharedPreferencesLearnedPhraseStore.normalize(phrase)
        val removed = learnedPhrases.remove(phrase)
        memory.snapshot().records.filter { record ->
            if (!record.active) return@filter false
            val keyMatch = record.key.equals("When I say $normalized", ignoreCase = true)
            val rememberedPhrase = Regex("(?i)^when\\s+i\\s+say\\s+(.+?)\\s*,\\s+i\\s+mean\\s+.+$")
                .matchEntire(record.value.trim())
                ?.groupValues
                ?.get(1)
                ?.let { SharedPreferencesLearnedPhraseStore.normalize(it) }
            keyMatch || rememberedPhrase == normalized
        }.forEach { memory.forget(it.id) }
        return removed
    }

    fun diagnosticReport(traceLimit: Int = 100): String {
        val now = System.currentTimeMillis()
        val runtimeSnapshot = snapshot()
        val brainHealth = brainStatus()
        val wakeHealth = wakeStatus()
        val commandSpeechReady = SageSpeechBackendState.sherpaReady(appContext)
        val androidSpeechFallback = SpeechRecognizer.isRecognitionAvailable(appContext)
        val commandSpeechDetail = buildString {
            append(SageSpeechBackendState.readinessDetail(appContext))
            append("; ").append(SageSherpaRecognitionService.runtimeDetail())
            if (!commandSpeechReady) append("; Android fallback=").append(androidSpeechFallback)
        }
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
                commandSpeechReady = commandSpeechReady,
                commandSpeechDetail = commandSpeechDetail,
                capabilities = capabilityMap,
                sageCoreRevision = core.current().revision,
                profileId = mode.profileId,
                modeId = mode.modeId,
                ownerAppsRevision = apps.revision,
                ownerAppsCount = apps.apps.size,
                continuityMigration = legacyMigrationReport.summary(),
                recoverableTasks = recoverableTasks().map { task ->
                    DiagnosticTaskSummary(task.taskId, task.title, task.state.name, task.nextStep)
                },
                chickenTonightScopeStatus = scopeStatus,
                traces = traces.recent(traceLimit.coerceIn(0, 200)),
                brainEvidence = buildMap {
                    brainHealth.telemetry?.let { telemetry ->
                        telemetry.nativeStage?.let { put("native stage", it) }
                        telemetry.cachedPromptTokens?.let { put("cached prompt tokens", it.toString()) }
                        telemetry.promptTokens?.let { put("prompt tokens", it.toString()) }
                        telemetry.generatedTokens?.let { put("generated tokens", it.toString()) }
                        telemetry.promptPrefillMs?.let { put("prompt prefill", "$it ms") }
                        telemetry.firstTokenMs?.let { put("first token", "$it ms") }
                        telemetry.generationMs?.let { put("generation", "$it ms") }
                        telemetry.promptTokensPerSecond?.let { put("prompt speed", "$it tokens/s") }
                    }
                    val inspected = brainModelStore.inspectionMetadata()
                    val imported = brainModelStore.metadata()
                    if (localModel.isFile) put("model file bytes", localModel.length().toString())
                    (inspected?.sha256 ?: imported?.sha256)?.takeIf { it.isNotBlank() }?.let {
                        put("model sha256", it)
                    }
                    inspected?.architecture?.takeIf { it.isNotBlank() }?.let { put("model architecture", it) }
                    inspected?.embeddedName?.takeIf { it.isNotBlank() }?.let { put("model embedded name", it) }
                    inspected?.fileType?.takeIf { it.isNotBlank() }?.let { put("model file type", it) }
                    inspected?.quantizationVersion?.takeIf { it.isNotBlank() }?.let { put("model quantization version", it) }
                    inspected?.parameterCount?.takeIf { it >= 0L }?.let { put("model parameters", it.toString()) }
                    inspected?.inspectedAtMs?.takeIf { it > 0L }?.let { put("model inspected at", it.toString()) }
                    if (localModel.isFile && inspected == null && imported == null) {
                        put("model identity", "installed; digest/metadata not yet inspected")
                    }
                }
            )
        )
    }

    fun addListener(listener: SageRuntimeListener) { listeners += listener }
    fun removeListener(listener: SageRuntimeListener) { listeners -= listener }

    companion object {
        private const val RECOVERY_RESUME_DELAY_MS = 1_200L
        private const val SELF_CARE_INITIAL_DELAY_MS = 8_000L
        private const val SELF_CARE_INTERVAL_MS = 5L * 60L * 1000L
        @Volatile private var instance: SageRuntimeHost? = null
        fun get(context: Context): SageRuntimeHost = instance ?: synchronized(this) {
            instance ?: SageRuntimeHost(context).also { instance = it }
        }
    }
}
