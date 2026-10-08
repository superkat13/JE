package com.pineapple.sageos2.personal

import com.pineapple.sageos2.learning.EmptyLearnedPhraseStore
import com.pineapple.sageos2.learning.LearnedPhraseStore
import com.pineapple.sageos2.memory.EmptyTwinMemoryProvider
import com.pineapple.sageos2.memory.TwinMemoryProvider
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySubject
import com.pineapple.sageos2.mode.SageModeController
import com.pineapple.sageos2.mode.SageTone
import java.util.Locale

sealed interface SagePersonalResolution {
    data class Reply(val text: String) : SagePersonalResolution
    data class RewrittenRequest(val text: String) : SagePersonalResolution
}

interface SagePersonalResponder {
    fun resolve(rawText: String): SagePersonalResolution?
}

object EmptySagePersonalResponder : SagePersonalResponder {
    override fun resolve(rawText: String) = null
}

/** Familiar Sage help, memory, personality replies, and owner-taught phrases without an LLM wait. */
class SagePersonalCommandEngine(
    private val memory: TwinMemoryProvider = EmptyTwinMemoryProvider,
    private val learnedPhrases: LearnedPhraseStore = EmptyLearnedPhraseStore,
    private val modes: SageModeController? = null
) : SagePersonalResponder {
    private var teachingStep = TeachingStep.NONE
    private var pendingPhrase = ""
    private var waitingForMemory = false

    override fun resolve(rawText: String): SagePersonalResolution? {
        val clean = clean(rawText)
        val normalized = normalize(clean)
        if (normalized.isEmpty()) return null

        if ((teachingStep != TeachingStep.NONE || waitingForMemory) && normalized in CANCEL_PHRASES) {
            teachingStep = TeachingStep.NONE
            pendingPhrase = ""
            waitingForMemory = false
            return SagePersonalResolution.Reply("Okay—I won't save that.")
        }

        if (waitingForMemory) {
            waitingForMemory = false
            return saveMemory(clean)
        }

        when (teachingStep) {
            TeachingStep.PHRASE -> {
                pendingPhrase = clean
                    .replace(Regex("(?i)^(?:the\\s+phrase\\s+is|when\\s+i\\s+say)\\s+"), "")
                    .trim()
                if (pendingPhrase.isEmpty()) {
                    return SagePersonalResolution.Reply("Tell me the phrase you want me to recognize.")
                }
                teachingStep = TeachingStep.MEANING
                return SagePersonalResolution.Reply("Got it. What should “$pendingPhrase” mean?")
            }
            TeachingStep.MEANING -> {
                val meaning = clean.replace(
                    Regex("(?i)^(?:i\\s+mean|that\\s+means|this\\s+means|it\\s+means)\\s+"),
                    ""
                ).trim()
                val phrase = pendingPhrase
                teachingStep = TeachingStep.NONE
                pendingPhrase = ""
                return saveLesson(phrase, meaning)
            }
            TeachingStep.NONE -> Unit
        }

        if (normalized in HELP_PHRASES) {
            return SagePersonalResolution.Reply(
                "Just talk to me normally—you don't need to learn commands or manage Advanced. " +
                    "I can think things through with you, remember what matters, learn phrases you use, " +
                    "and handle direct tablet requests like “open YouTube.”"
            )
        }

        when {
            normalized in RED_QUEEN_PHRASES -> {
                modes?.activate("sage_glitch", "red_queen")
                return SagePersonalResolution.Reply("Red Queen mode is active. Same Sage—sharper edge.")
            }
            normalized in EVERYDAY_SAGE_PHRASES -> {
                modes?.activate("sage", null)
                return SagePersonalResolution.Reply("Everyday Sage is back.")
            }
            normalized in TONE_STATUS_PHRASES -> {
                val tone = modes?.current()?.tone ?: SageTone.UNFILTERED
                return SagePersonalResolution.Reply("My language setting is ${tone.displayName().lowercase(Locale.US)}.")
            }
            normalized in UNFILTERED_TONE_PHRASES -> return selectTone(
                SageTone.UNFILTERED,
                "Unfiltered mode. Hell yes—I can cuss with you."
            )
            normalized in CLEAN_TONE_PHRASES -> return selectTone(
                SageTone.CLEAN,
                "Clean mode. I'll keep it polite."
            )
            normalized in CASUAL_TONE_PHRASES -> return selectTone(
                SageTone.CASUAL,
                "Casual mode. Sass is on."
            )
            normalized == "turn the sass up" -> {
                val current = modes?.current()?.tone ?: SageTone.UNFILTERED
                val selected = if (current == SageTone.CLEAN) SageTone.CASUAL else SageTone.UNFILTERED
                return selectTone(selected, if (selected == SageTone.CASUAL) "Casual mode. Sass is on." else "Unfiltered mode. Hell yes—I can cuss with you.")
            }
            normalized == "tone it down" -> {
                val current = modes?.current()?.tone ?: SageTone.UNFILTERED
                val selected = if (current == SageTone.UNFILTERED) SageTone.CASUAL else SageTone.CLEAN
                return selectTone(selected, if (selected == SageTone.CASUAL) "Casual mode. Sass is on." else "Clean mode. I'll keep it polite.")
            }
        }

        if (normalized in TAUGHT_PHRASES) {
            val lessons = learnedPhrases.list()
            return SagePersonalResolution.Reply(
                if (lessons.isEmpty()) {
                    "You haven't taught me a custom phrase yet. Say “teach me something” and I'll ask for the phrase and what it means."
                } else {
                    val visible = lessons.take(5).joinToString(". ") { "${it.phrase} means ${it.meaning}" }
                    "You taught me: $visible" + if (lessons.size > 5) ". And ${lessons.size - 5} more are in What Sage remembers." else "."
                }
            )
        }

        if (normalized in START_TEACHING_PHRASES) {
            teachingStep = TeachingStep.PHRASE
            pendingPhrase = ""
            return SagePersonalResolution.Reply("Okay. What phrase should I remember?")
        }

        parseLesson(clean)?.let { (phrase, meaning) -> return saveLesson(phrase, meaning) }

        if (normalized in START_MEMORY_PHRASES) {
            waitingForMemory = true
            return SagePersonalResolution.Reply("What should I remember?")
        }

        memoryValue(clean, normalized)?.let { return saveMemory(it) }

        if (normalized in MEMORY_RECALL_PHRASES) {
            val records = memory.snapshot().records.filter { it.active }
                .sortedByDescending { it.updatedAtEpochMs }
            return SagePersonalResolution.Reply(
                if (records.isEmpty()) {
                    "I don't have any saved notes about us yet. Say “remember that…” whenever something should stay with me."
                } else {
                    val visible = records.take(6).joinToString(". ") { it.value }
                    "Here's what I remember: $visible" + if (records.size > 6) ". There's more in What Sage remembers." else "."
                }
            )
        }

        personalityReply(normalized)?.let { return SagePersonalResolution.Reply(it) }

        learnedPhrases.meaningFor(normalized)?.let { meaning ->
            val spokenReply = meaning.replaceFirst(Regex("(?i)^say\\s+"), "").trim()
            return if (meaning.matches(Regex("(?i)^say\\s+.+")) && spokenReply.isNotEmpty()) {
                SagePersonalResolution.Reply(spokenReply)
            } else {
                SagePersonalResolution.RewrittenRequest(meaning)
            }
        }
        return null
    }

    private fun personalityReply(normalized: String): String? = memory.snapshot().records
        .asSequence()
        .filter { it.active && it.subject == TwinMemorySubject.SAGE }
        .firstOrNull { record ->
            record.key.startsWith(PERSONALITY_PREFIX, ignoreCase = true) &&
                normalize(record.key.substring(PERSONALITY_PREFIX.length)) == normalized
        }
        ?.value

    private fun saveLesson(phrase: String, meaning: String): SagePersonalResolution.Reply {
        val cleanPhrase = clean(phrase)
        val cleanMeaning = clean(meaning)
        if (cleanPhrase.isEmpty() || cleanMeaning.isEmpty()) {
            return SagePersonalResolution.Reply("I need both the phrase you use and what you want it to mean.")
        }
        if (cleanPhrase.length > MAX_PHRASE) {
            return SagePersonalResolution.Reply("That phrase is a little long. Keep it under $MAX_PHRASE characters and try again.")
        }
        if (cleanMeaning.length > MAX_MEANING) {
            return SagePersonalResolution.Reply("That meaning is a little long. Keep it under $MAX_MEANING characters and try again.")
        }
        val saved = learnedPhrases.save(cleanPhrase, cleanMeaning)
        if (!saved) return SagePersonalResolution.Reply("I understood the lesson, but I couldn't save it. Please try once more.")
        (memory as? TwinMemoryStore)?.remember(
            subject = TwinMemorySubject.OWNER,
            key = "When I say ${normalize(cleanPhrase)}",
            value = "When I say $cleanPhrase, I mean $cleanMeaning",
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0
        )
        return SagePersonalResolution.Reply("Saved. When you say “$cleanPhrase,” I'll treat it as “$cleanMeaning.”")
    }

    private fun saveMemory(value: String): SagePersonalResolution.Reply {
        val cleanValue = clean(value)
        if (cleanValue.isEmpty()) return SagePersonalResolution.Reply("Tell me what you want me to remember.")
        val store = memory as? TwinMemoryStore
            ?: return SagePersonalResolution.Reply("I understood, but I couldn't save that memory yet.")
        store.remember(
            subject = TwinMemorySubject.OWNER,
            key = "Owner note: ${normalize(cleanValue).take(72)}",
            value = cleanValue,
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0
        )
        return SagePersonalResolution.Reply("I'll remember that.")
    }

    private fun memoryValue(clean: String, normalized: String): String? = when {
        normalized.startsWith("remember that ") -> clean.replaceFirst(Regex("(?i)^remember\\s+that\\s+"), "").trim()
        normalized.startsWith("remember ") -> clean.replaceFirst(Regex("(?i)^remember\\s+"), "").trim()
        normalized.startsWith("note ") -> clean.replaceFirst(Regex("(?i)^note\\s+"), "").trim()
        normalized.startsWith("i prefer ") ||
            normalized.startsWith("use this app for ") ||
            normalized.startsWith("this device is ") ||
            normalized.startsWith("this project is ") -> clean
        else -> null
    }

    private fun selectTone(tone: SageTone, reply: String): SagePersonalResolution.Reply {
        modes?.setTone(tone)
        return SagePersonalResolution.Reply(reply)
    }

    private fun parseLesson(value: String): Pair<String, String>? {
        val withoutRemember = value.replaceFirst(
            Regex("(?i)^remember(?:\\s+(?:this|that))?\\s*[,.:;-]*\\s+(?=when\\s+i\\s+say)"),
            ""
        )
        val patterns = listOf(
            Regex("(?i)^when\\s+i\\s+say\\s+(.+?)\\s*[,;:-]?\\s+(?:i\\s+mean|that\\s+means|it\\s+means|it's\\s+means)\\s+(.+)$"),
            Regex("(?i)^(?:teach\\s+sage|teach\\s+you|learn\\s+that)\\s+(.+?)\\s+(?:means|should\\s+mean)\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.matchEntire(withoutRemember) ?: continue
            return clean(match.groupValues[1]) to clean(match.groupValues[2])
        }
        return null
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    private fun normalize(value: String): String = clean(value).lowercase(Locale.US)
        .replace(Regex("[^a-z0-9']+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private enum class TeachingStep { NONE, PHRASE, MEANING }

    companion object {
        private const val PERSONALITY_PREFIX = "Owner-taught reply for:"
        private const val MAX_PHRASE = 80
        private const val MAX_MEANING = 600
        private val HELP_PHRASES = setOf("help", "commands", "what can you do", "how do i use sage", "how do i use you")
        private val TAUGHT_PHRASES = setOf(
            "what have i taught you", "what did i teach you", "what phrases do you know",
            "read my custom commands", "show my custom commands"
        )
        private val START_TEACHING_PHRASES = setOf(
            "remember this", "remember that", "learn this", "teach me something", "teach sage something",
            "teach you something", "let me teach you something", "i want to teach you something"
        )
        private val START_MEMORY_PHRASES = setOf("remember", "remember something", "save a memory")
        private val MEMORY_RECALL_PHRASES = setOf(
            "memory", "what do you remember", "what do you remember about me", "what do you remember about us",
            "read my memories", "read my notes"
        )
        private val CANCEL_PHRASES = setOf("cancel", "never mind", "nevermind", "stop")
        private val RED_QUEEN_PHRASES = setOf("red queen", "red queen mode", "activate red queen mode")
        private val EVERYDAY_SAGE_PHRASES = setOf("normal sage mode", "everyday sage mode", "turn off red queen")
        private val TONE_STATUS_PHRASES = setOf("what is your language setting", "what's your language setting")
        private val UNFILTERED_TONE_PHRASES = setOf(
            "you can cuss around me", "you can swear around me", "cuss around me", "swear around me", "unfiltered mode"
        )
        private val CLEAN_TONE_PHRASES = setOf("clean mode", "stop cussing", "stop swearing")
        private val CASUAL_TONE_PHRASES = setOf("casual mode")
    }
}
