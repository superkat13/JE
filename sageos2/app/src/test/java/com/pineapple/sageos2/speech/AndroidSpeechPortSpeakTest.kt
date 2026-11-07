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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
