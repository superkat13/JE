# Sage 1.33.3 physical tablet evidence

Source: owner-exported diagnostic and supervised-repair packet from the installed VASOUN L10_T05 tablet on Android 13 (API 33), captured 2026-09-03.

## Confirmed working

- In-place update continuity: version 1.33.3 (49), stable package `com.pineapple.sagecommander.stable`.
- Rotated signer active: SHA-256 `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`.
- Existing custom wake profile preserved: `sage glitch` → Red Queen.
- Accessibility, notification access, usage access, battery exemption, boot startup, owner profile, Forge trust, local Brain, and device admin reported active.
- Typed commands dispatched as `TYPED_FINAL`/`OWNER_SPEECH` and completed through the local tablet Brain.
- Local Brain requests ran longer than 30 seconds and completed without the former fixed 30-second timeout.

## Confirmed remaining defects

- No successful wake occurred after installation during the captured test window.
- `Okay Sage`-style attempts were rendered as fragments or alternate tokens including `[unk] say page`, `age`, `save`, and `okay`; the 1.33.3 exact phrase table could not rejoin split final hypotheses.
- The saved `sage glitch` Red Queen profile was rendered as `glitch`; the recognizer dropped its leading `sage` token and the exact custom-profile matcher rejected the distinctive remainder.
- Natural capability questions (`what all can you do`, `what all capabilities do you have`) missed the narrow deterministic phrase list and fell through to a generic local Brain response.
- `finish your thought` did not carry the immediately preceding exchange because continuation selection required lexical overlap.
- The ordinary concise Brain ceiling remained 16 output tokens, too small for reliable complete conversational sentences.

## 1.33.4 repair boundary

- Add the exact two-word observed alias `say page`; do not restore unsafe one-word aliases.
- Rejoin only a recent `hey`/`okay`/`ok` final fragment with a Sage-like final suffix inside a 2.5-second window.
- Recover the distinctive suffix of an owner-created profile when Vosk drops its leading built-in Sage token, while retaining the unsafe-word blocklist.
- Route natural capability paraphrases to the truthful live capability snapshot before Brain fallback.
- Treat explicit continuation language as conversational and carry the latest exchange without keyword overlap.
- Raise the adaptive complete-sentence output budget while preserving the 120-second absolute watchdog and live progress cancellation.
