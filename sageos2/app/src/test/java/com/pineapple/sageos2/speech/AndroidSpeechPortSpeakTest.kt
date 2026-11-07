package com.pineapple.sageos2.speech

import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidSpeechPortSpeakTest {
    private var port: AndroidSpeechPort? = null

    @After fun cleanup() {
        port?.shutdown()
        idle()
    }

    @Test fun longReplyIsQueuedInFullAndCompletesOnlyAfterTheLastUtterance() {
        var completed = false
        port = AndroidSpeechPort(
            RuntimeEnvironment.getApplication(),
            object : WakeWordEngine {
                override fun configure(profiles: List<WakeProfile>) = Unit
                override fun start(generation: Long, onWake: (WakeHit) -> Unit) = Unit
                override fun stop() = Unit
                override fun health() = WakeWordHealth(false, "test", "test")
            },
            object : WakeProfileProvider {
                override fun profiles(): List<WakeProfile> = emptyList()
            }
        )
        idle()
        port!!.onInit(TextToSpeech.SUCCESS)
        idle()

        val text = buildString {
            repeat(100) { append("Sage keeps the complete answer intact across every spoken sentence. ") }
        }
        port!!.speak(41L, text) { completed = true }
        idle()

        val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()) {
            "the real port must create the shadow TTS engine"
        }
        val shadow = Shadow.extract<ShadowTextToSpeech>(tts)
        val expectedChunks = SpeechTextChunker.chunk(
            text,
            TextToSpeech.getMaxSpeechInputLength().coerceAtMost(SpeechTextChunker.DEFAULT_MAX_CHARS)
        )
        assertTrue(expectedChunks.size > 1)
        assertEquals(expectedChunks, shadow.spokenTextList)
        assertEquals(text, shadow.spokenTextList.joinToString(""))
        assertEquals(TextToSpeech.QUEUE_ADD, shadow.queueMode)
        assertTrue("completion must follow the final chunk, not the first", completed)
    }

    @Test fun transientAcknowledgementNeverFlushesAnActiveReply() {
        port = AndroidSpeechPort(
            RuntimeEnvironment.getApplication(),
            object : WakeWordEngine {
                override fun configure(profiles: List<WakeProfile>) = Unit
                override fun start(generation: Long, onWake: (WakeHit) -> Unit) = Unit
                override fun stop() = Unit
                override fun health() = WakeWordHealth(false, "test", "test")
            },
            object : WakeProfileProvider {
                override fun profiles(): List<WakeProfile> = emptyList()
            }
        )
        idle()
        port!!.onInit(TextToSpeech.SUCCESS)
        idle()

        // ShadowTextToSpeech completes speech synchronously, so hold the
        // in-progress flag to simulate a still-speaking native TTS engine.
        val busy = AndroidSpeechPort::class.java.getDeclaredField("speechActive").apply {
            isAccessible = true
        }
        busy.setBoolean(port!!, true)
        var normalCompleted = false
        port!!.speakTransient("Acknowledged.")
        port!!.speak(93L, "Do not lose this sentence.") { normalCompleted = true }
        idle()

        val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val shadow = Shadow.extract<ShadowTextToSpeech>(tts)
        assertTrue("the active utterance cannot be flushed by another TTS request",
            shadow.spokenTextList.isEmpty())
        assertTrue("the newer reply must remain queued, not reported as spoken",
            !normalCompleted)

        busy.setBoolean(port!!, false)
        val drain = AndroidSpeechPort::class.java.getDeclaredMethod("drainDeferredSpeech").apply {
            isAccessible = true
        }
        drain.invoke(port!!)
        idle()

        assertEquals(listOf("Acknowledged.", "Do not lose this sentence."),
            shadow.spokenTextList)
        assertTrue("the deferred normal reply must receive completion", normalCompleted)
    }

    @Test fun wakeDoesNotRestartDuringQueuedSpeechOrRestartTwiceAfterIt() {
        var wakeStarts = 0
        port = AndroidSpeechPort(
            RuntimeEnvironment.getApplication(),
            object : WakeWordEngine {
                override fun configure(profiles: List<WakeProfile>) = Unit
                override fun start(generation: Long, onWake: (WakeHit) -> Unit) {
                    wakeStarts += 1
                }
                override fun stop() = Unit
                override fun health() = WakeWordHealth(false, "test", "test")
            },
            object : WakeProfileProvider {
                override fun profiles(): List<WakeProfile> = emptyList()
            }
        )
        idle()
        port!!.onInit(TextToSpeech.SUCCESS)
        idle()

        val busy = AndroidSpeechPort::class.java.getDeclaredField("speechActive").apply {
            isAccessible = true
        }
        busy.setBoolean(port!!, true)
        port!!.setListening(SageListeningMode.WAKE_ONLY, 15L, 0L)
        port!!.speakTransient("Acknowledged.")
        idle()
        assertEquals("microphone cannot restart during an active utterance", 0, wakeStarts)

        busy.setBoolean(port!!, false)
        val drain = AndroidSpeechPort::class.java.getDeclaredMethod("drainDeferredSpeech").apply {
            isAccessible = true
        }
        drain.invoke(port!!)
        idle()
        assertEquals("deferred acknowledgement resumes wake exactly once", 1, wakeStarts)
    }

    @Test fun changedListeningGenerationIsRestoredAfterDeferredAcknowledgement() {
        val started = mutableListOf<Long>()
        port = AndroidSpeechPort(
            RuntimeEnvironment.getApplication(),
            object : WakeWordEngine {
                override fun configure(profiles: List<WakeProfile>) = Unit
                override fun start(generation: Long, onWake: (WakeHit) -> Unit) {
                    started += generation
                }
                override fun stop() = Unit
                override fun health() = WakeWordHealth(false, "test", "test")
            },
            object : WakeProfileProvider {
                override fun profiles(): List<WakeProfile> = emptyList()
            }
        )
        idle()
        port!!.onInit(TextToSpeech.SUCCESS)
        idle()

        val busy = AndroidSpeechPort::class.java.getDeclaredField("speechActive").apply {
            isAccessible = true
        }
        busy.setBoolean(port!!, true)
        port!!.setListening(SageListeningMode.WAKE_ONLY, 11L, 0L)
        port!!.speakTransient("Acknowledged.")
        port!!.setListening(SageListeningMode.WAKE_ONLY, 12L, 0L)
        idle()
        assertTrue(started.isEmpty())

        busy.setBoolean(port!!, false)
        AndroidSpeechPort::class.java.getDeclaredMethod("drainDeferredSpeech").apply {
            isAccessible = true
        }.invoke(port!!)
        idle()
        assertEquals(listOf(12L), started)
    }

    @Test fun failedTtsInitializationReleasesEveryPendingNormalTurn() {
        port = AndroidSpeechPort(
            RuntimeEnvironment.getApplication(),
            object : WakeWordEngine {
                override fun configure(profiles: List<WakeProfile>) = Unit
                override fun start(generation: Long, onWake: (WakeHit) -> Unit) = Unit
                override fun stop() = Unit
                override fun health() = WakeWordHealth(false, "test", "test")
            },
            object : WakeProfileProvider {
                override fun profiles(): List<WakeProfile> = emptyList()
            }
        )
        idle()
        AndroidSpeechPort::class.java.getDeclaredField("ttsReady").apply {
            isAccessible = true
        }.setBoolean(port!!, false)
        var first = 0
        var second = 0
        port!!.speak(10L, "First.") { first++ }
        port!!.speak(11L, "Second.") { second++ }
        idle()
        assertEquals(0, first)
        assertEquals(0, second)
        port!!.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(1, first)
        assertEquals(1, second)
        val deferred = AndroidSpeechPort::class.java.getDeclaredField("deferredSpeech").apply {
            isAccessible = true
        }.get(port!!) as java.util.ArrayDeque<*>
        assertTrue("failed TTS init cannot strand unplayed turns", deferred.isEmpty())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
