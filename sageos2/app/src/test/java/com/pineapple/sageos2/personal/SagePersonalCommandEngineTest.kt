package com.pineapple.sageos2.personal

import com.pineapple.sageos2.learning.LearnedPhrase
import com.pineapple.sageos2.learning.LearnedPhraseStore
import com.pineapple.sageos2.memory.TwinMemoryRecord
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySubject
import com.pineapple.sageos2.mode.SageModeController
import com.pineapple.sageos2.mode.SageModeSnapshot
import com.pineapple.sageos2.mode.SageTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SagePersonalCommandEngineTest {
    @Test fun helpIsImmediateAndExplainsOrdinaryUse() {
        val result = SagePersonalCommandEngine().resolve("How do I use Sage?") as SagePersonalResolution.Reply
        assertTrue(result.text.contains("talk to me normally", ignoreCase = true))
        assertTrue(result.text.contains("remember", ignoreCase = true))
        assertTrue(result.text.contains("open YouTube"))
    }

    @Test fun rememberThatPersistsWithoutWaitingForBrain() {
        val memory = FakeMemoryStore()
        val result = SagePersonalCommandEngine(memory).resolve("Remember that I prefer the purple layout")
        assertEquals(SagePersonalResolution.Reply("I'll remember that."), result)
        assertEquals("I prefer the purple layout", memory.snapshot().records.single().value)
        assertEquals(TwinMemorySource.EXPLICIT_OWNER, memory.snapshot().records.single().source)
    }

    @Test fun familiarOneStepAndPromptedMemoryCommandsStillWork() {
        val memory = FakeMemoryStore()
        val engine = SagePersonalCommandEngine(memory)

        assertEquals(SagePersonalResolution.Reply("I'll remember that."), engine.resolve("Note the blue USB is for Parrot"))
        assertEquals(SagePersonalResolution.Reply("What should I remember?"), engine.resolve("save a memory"))
        assertEquals(SagePersonalResolution.Reply("I'll remember that."), engine.resolve("The spare cable is in the drawer"))
        assertEquals(
            listOf("the blue USB is for Parrot", "The spare cable is in the drawer"),
            memory.snapshot().records.map { it.value }
        )
        assertTrue((engine.resolve("memory") as SagePersonalResolution.Reply).text.contains("blue USB"))
    }

    @Test fun twoStepTeachingRestoresFamiliarSageFlow() {
        val memory = FakeMemoryStore()
        val lessons = FakeLearnedPhraseStore()
        val engine = SagePersonalCommandEngine(memory, lessons)
        assertEquals(
            SagePersonalResolution.Reply("Okay. What phrase should I remember?"),
            engine.resolve("Teach me something")
        )
        assertEquals(
            SagePersonalResolution.Reply("Got it. What should “movie time” mean?"),
            engine.resolve("movie time")
        )
        val saved = engine.resolve("open YouTube") as SagePersonalResolution.Reply
        assertTrue(saved.text.startsWith("Saved."))
        assertEquals("open YouTube", lessons.meaningFor("movie time"))
        assertTrue(memory.snapshot().records.single().value.contains("movie time"))
    }

    @Test fun oneLineLessonCanBecomeADeviceRequestOrExactPersonalityReply() {
        val lessons = FakeLearnedPhraseStore()
        val engine = SagePersonalCommandEngine(learnedPhrases = lessons)
        engine.resolve("When I say movie time, it means open YouTube")
        assertEquals(
            SagePersonalResolution.RewrittenRequest("open YouTube"),
            engine.resolve("movie time")
        )
        lessons.save("hello sage", "say Hey, you.")
        assertEquals(SagePersonalResolution.Reply("Hey, you."), engine.resolve("hello Sage"))
    }

    @Test fun importedOwnerTaughtPersonalityReplyStillMatchesExactly() {
        val memory = FakeMemoryStore().apply {
            remember(
                TwinMemorySubject.SAGE,
                "Owner-taught reply for: pineapple",
                "You found it.",
                TwinMemorySource.IMPORTED,
                1.0
            )
        }
        assertEquals(
            SagePersonalResolution.Reply("You found it."),
            SagePersonalCommandEngine(memory).resolve("Pineapple!")
        )
    }

    @Test fun familiarToneAndRedQueenCommandsStillWorkImmediately() {
        val modes = FakeModes()
        val engine = SagePersonalCommandEngine(modes = modes)

        assertTrue((engine.resolve("You can cuss around me") as SagePersonalResolution.Reply).text.contains("Hell yes"))
        assertEquals(SageTone.UNFILTERED, modes.current().tone)
        assertTrue((engine.resolve("tone it down") as SagePersonalResolution.Reply).text.contains("Casual"))
        assertEquals(SageTone.CASUAL, modes.current().tone)
        engine.resolve("clean mode")
        assertEquals(SageTone.CLEAN, modes.current().tone)
        engine.resolve("casual mode")
        assertEquals(SageTone.CASUAL, modes.current().tone)
        assertTrue((engine.resolve("red queen mode") as SagePersonalResolution.Reply).text.contains("Red Queen"))
        assertEquals("red_queen", modes.current().modeId)
        engine.resolve("everyday Sage mode")
        assertEquals(SageModeSnapshot("sage", null, SageTone.CASUAL), modes.current())
    }

    @Test fun ownerCanCancelAStartedLessonWithoutSavingIt() {
        val lessons = FakeLearnedPhraseStore()
        val engine = SagePersonalCommandEngine(learnedPhrases = lessons)
        engine.resolve("remember this")
        val cancelled = engine.resolve("never mind") as SagePersonalResolution.Reply
        assertTrue(cancelled.text.contains("won't save"))
        assertTrue(lessons.list().isEmpty())
        assertNull(engine.resolve("movie time"))
    }

    private class FakeLearnedPhraseStore : LearnedPhraseStore {
        private val values = linkedMapOf<String, String>()
        override fun list() = values.map { LearnedPhrase(it.key, it.value) }
        override fun meaningFor(phrase: String) = values[phrase.lowercase()]
        override fun save(phrase: String, meaning: String): Boolean {
            values[phrase.lowercase()] = meaning
            return true
        }
        override fun remove(phrase: String) = values.remove(phrase.lowercase()) != null
    }

    private class FakeMemoryStore : TwinMemoryStore {
        private var state = TwinMemorySnapshot(0, emptyList())
        override fun snapshot() = state
        override fun remember(
            subject: TwinMemorySubject,
            key: String,
            value: String,
            source: TwinMemorySource,
            confidence: Double,
            nowEpochMs: Long
        ): TwinMemoryRecord {
            val record = TwinMemoryRecord(UUID.randomUUID().toString(), subject, key, value, source, confidence, nowEpochMs, nowEpochMs)
            state = TwinMemorySnapshot(state.revision + 1, state.records + record)
            return record
        }
        override fun forget(id: String, nowEpochMs: Long): Boolean {
            val before = state.records.size
            state = TwinMemorySnapshot(state.revision + 1, state.records.filterNot { it.id == id })
            return state.records.size < before
        }
    }

    private class FakeModes : SageModeController {
        private var state = SageModeSnapshot()
        override fun activate(profileId: String, modeId: String?) {
            state = state.copy(profileId = profileId, modeId = modeId)
        }
        override fun setTone(tone: SageTone) { state = state.copy(tone = tone) }
        override fun current() = state
    }
}
