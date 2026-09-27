package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakePhraseCompilerIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    // Generated with official sentencepiece 0.2.1 and the checksum-pinned model's
    // bpe.model (TrainerSpec.UNIGRAM). Do not regenerate from our Kotlin encoder.
    @Test fun matchesOfficialSentencePieceAcross64WakePhrases() {
        val compiler = WakePhraseCompiler(context)
        val fixture = requireNotNull(javaClass.getResourceAsStream("/wake-tokenizer-oracle.tsv"))
        fixture.bufferedReader().useLines { lines ->
            var count = 0
            lines.forEach { line ->
                val parts = line.split('\t')
                assertEquals(parts[0], parts[1], compiler.compile(parts[0]))
                count++
            }
            assertEquals(64, count)
        }
    }

    @Test fun pinnedGigaSpeechBpeMatchesKnownSherpaTokenizations() {
        val compiler = WakePhraseCompiler(context)

        assertEquals("▁S AGE", compiler.compile("sage"))
        assertEquals("▁S AGE ▁G LI T CH", compiler.compile("Sage Glitch"))
        assertEquals("▁HE LL O ▁WORLD", compiler.compile("hello world"))
        assertEquals("▁HE Y ▁S I RI", compiler.compile("hey siri"))
        assertEquals("▁ L IGHT ▁UP", compiler.compile("light up"))
        assertEquals(compiler.compile("sage"), compiler.compile("  Ｓａｇｅ  "))
        assertNull(compiler.compile(""))
        assertNull(compiler.compile("☃"))
    }

    @Test fun customProfileCompilationPersistsForOfflineWakeReuse() {
        context.getSharedPreferences("sage_wake_profiles_v2", 0).edit().clear().commit()
        try {
            val store = SharedPreferencesWakeProfileStore(context)
            val custom = WakeProfile(
                id = "custom_test",
                displayName = "Hello World",
                phrases = listOf("hello world"),
                compiledPhrases = emptyMap(),
                acknowledgement = "Yep",
                enabled = true,
                legacyCommand = "open firefox"
            )

            val prepared = store.compile(custom)
            assertEquals("▁HE LL O ▁WORLD", prepared.compiledTokensFor("hello world"))
            store.upsert(prepared)

            val restored = SharedPreferencesWakeProfileStore(context)
                .profiles()
                .first { it.id == "custom_test" }
            assertEquals("▁HE LL O ▁WORLD", restored.compiledTokensFor("hello world"))
            assertEquals("open firefox", restored.legacyCommand)
            assertNotNull(restored.compiledPhrases["hello world"])
            assertFalse(restored.compiledPhrases.isEmpty())
        } finally {
            context.getSharedPreferences("sage_wake_profiles_v2", 0).edit().clear().commit()
        }
    }
}
