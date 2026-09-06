package com.pineapple.sageos2.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class SherpaWakeWordEngine(
    context: Context,
    private val keywordScore: Float = 1.5f,
    private val keywordThreshold: Float = 0.25f
) : WakeWordEngine {
    private val appContext = context.applicationContext
    private val session = AtomicLong(0L)
    private val lock = Any()
    @Volatile private var profiles: List<WakeProfile> = SharedPreferencesWakeProfileStore.defaults()
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var lastProblem: String? = null
    @Volatile private var running = false

    override fun configure(profiles: List<WakeProfile>) {
        this.profiles = profiles.filter { it.enabled }
    }

    override fun health(): WakeWordHealth {
        val compiled = compiledEntries()
        return when {
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                WakeWordHealth(false, ENGINE, "microphone permission is not granted")
            compiled.isEmpty() -> WakeWordHealth(false, ENGINE, "no wake phrases have compiled BPE tokens")
            else -> runCatching { ensureSpotter() }
                .fold(
                    onSuccess = { WakeWordHealth(true, ENGINE, "offline KWS ready (${compiled.size} phrase${if (compiled.size == 1) "" else "s"})") },
                    onFailure = { WakeWordHealth(false, ENGINE, it.message ?: "offline KWS initialization failed") }
                )
        }
    }

    override fun start(generation: Long, onWake: (WakeHit) -> Unit) {
        stop()
        val entries = compiledEntries()
        require(entries.isNotEmpty()) { "no compiled wake phrases are configured" }
        require(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "microphone permission is not granted"
        }
        val kws = ensureSpotter()
        val keywordSpec = entries.joinToString("/") { entry ->
            "${entry.tokens} :$keywordScore #$keywordThreshold @${entry.profile.id}"
        }
        val stream = kws.createStream(keywordSpec)
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        require(minBytes > 0) { "AudioRecord minimum buffer is invalid: $minBytes" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            FORMAT,
            maxOf(minBytes * 2, SAMPLES_PER_CHUNK * 2)
        )
        require(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            stream.release()
            "wake AudioRecord failed to initialize"
        }
        val currentSession = session.incrementAndGet()
        synchronized(lock) {
            running = true
            audioRecord = record
        }
        record.startRecording()
        val thread = Thread({ process(currentSession, generation, record, stream, kws, entries, onWake) }, "sage-wake-kws").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
    }

    private fun process(
        currentSession: Long,
        generation: Long,
        record: AudioRecord,
        stream: OnlineStream,
        kws: KeywordSpotter,
        entries: List<CompiledWake>,
        onWake: (WakeHit) -> Unit
    ) {
        val shorts = ShortArray(SAMPLES_PER_CHUNK)
        try {
            while (running && session.get() == currentSession) {
                val count = record.read(shorts, 0, shorts.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { shorts[it] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (running && session.get() == currentSession && kws.isReady(stream)) {
                    kws.decode(stream)
                    val detected = kws.getResult(stream).keyword.trim()
                    if (detected.isBlank()) continue
                    kws.reset(stream)
                    val profile = resolveProfile(detected, entries) ?: continue
                    running = false
                    onWake(WakeHit(generation, profile.id, profile.modeId, profile.acknowledgement))
                    break
                }
            }
        } catch (t: Throwable) {
            lastProblem = t.message ?: t::class.simpleName
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { stream.release() }
            synchronized(lock) {
                if (audioRecord === record) audioRecord = null
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    override fun stop() {
        running = false
        session.incrementAndGet()
        val record = synchronized(lock) { audioRecord.also { audioRecord = null } }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        worker = null
    }

    override fun close() {
        stop()
        synchronized(lock) {
            runCatching { spotter?.release() }
            spotter = null
        }
    }

    private fun ensureSpotter(): KeywordSpotter = synchronized(lock) {
        spotter?.let { return@synchronized it }
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.int8.onnx",
                    decoder = "$MODEL_DIR/decoder.onnx",
                    joiner = "$MODEL_DIR/joiner.int8.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2"
            ),
            maxActivePaths = 4,
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = keywordScore,
            keywordsThreshold = keywordThreshold,
            numTrailingBlanks = 2
        )
        KeywordSpotter(assetManager = appContext.assets, config = config).also {
            spotter = it
            lastProblem = null
        }
    }

    private fun compiledEntries(): List<CompiledWake> = buildList {
        profiles.forEach { profile ->
            profile.phrases.forEach { phrase ->
                profile.compiledTokensFor(phrase)?.takeIf { it.isNotBlank() }?.let { tokens ->
                    add(CompiledWake(profile, WakeProfile.normalizePhrase(phrase), tokens))
                }
            }
        }
    }

    private fun resolveProfile(detected: String, entries: List<CompiledWake>): WakeProfile? {
        val normalized = detected.lowercase(Locale.US).trim().replace(Regex("\\s+"), " ")
        return entries.firstOrNull { it.profile.id.lowercase(Locale.US) == normalized }?.profile
            ?: entries.firstOrNull { it.phrase == normalized }?.profile
    }

    private data class CompiledWake(val profile: WakeProfile, val phrase: String, val tokens: String)

    companion object {
        private const val ENGINE = "sherpa-onnx-kws-1.13.7"
        private const val MODEL_DIR = "sherpa-kws"
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLES_PER_CHUNK = 1_600
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
