# Sage 1.29 external-action lifecycle repair

## Physical evidence

The owner report from the installed `1.29.0 (41)` build showed successful semantic execution followed by an unintended recognizer reopen:

```text
COORDINATOR RESULT  goal=click video route=command engine verified=true detail=Tapped video.
STATE  Conversation open — listening
COMMAND  Command turn opened
SILENCE  No command matched; automatic reopen blocked
```

The same sequence occurred after `go home`. Recognition itself was not duplicated: the report showed one accepted final and one dispatch per spoken command. The defect was the result lifecycle after a verified external UI action.

## Root cause

The previous checkpoint decided whether a semantic tap required a fresh wake by asking Accessibility for the foreground package *after* the tap. Android can replace or temporarily remove the active accessibility root during navigation, so the post-action package was `unavailable` and the action returned an ordinary quiet result. Natural conversation then reopened for an unnecessary five-second turn.

## Repair

- Successful semantic taps and ordinal selections capture source-package telemetry before acting.
- Every successful screen-changing action returns an explicit `external_ui` boundary.
- Media actions retain their separate `media` boundary.
- The voice service closes the conversation and cancels pending follow-ups before any result delivery can reopen recognition.
- Home, Back, Recents, scrolling, app launches, browser opens, deep links, Adobe handoff, and typing use the same deterministic external-action rule.
- Natural Brain, memory, and clarification follow-ups remain conversational.
- Self-Repair now classifies an external result followed by `Conversation open — listening` without an intervening action boundary as `likely_code_defect`, requests source modification and regression tests, and records specific confirmed evidence.

## Verification added

- Host reconstruction checks for the deterministic boundary and exact physical trace classifier.
- JVM unit tests cover the bad tablet trace, the repaired boundary trace, and an ordinary Brain follow-up.
- Both 1.29 workflows run the new regression.
- Two clean reconstructions were byte-identical locally.

Physical tablet confirmation remains required after the signed CI artifact is built.
