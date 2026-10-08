# Sage Commander 1.26.0 root-cause report

## Conversation and speech

The repeated-command behavior was a lifecycle ownership failure, not a recognition-quality problem. Recognition callbacks could arrive more than once, partial and final paths did not share a single completed-turn gate, and callbacks were not rejected by recognizer generation. A wake utterance could therefore be accepted repeatedly while state flags moved between waiting and wake modes.

Contributing causes:

1. No immutable turn identity connected wake, command capture, final selection, and dispatch.
2. No completed-turn ledger prevented a late or duplicated final callback from executing again.
3. Wake acceptance lacked a normalized transcript debounce window.
4. Recognizer recreation had no generation token, so callbacks from an older instance remained actionable.
5. Conversation mode was coordinated by interacting booleans and delayed callbacks, allowing automatic reopen loops.
6. Echo handling relied on timing without retaining a normalized fingerprint of recent Sage speech. Rejected echoes were consequently not measurable.
7. Active device playback was not a recognition trust boundary, so speaker audio could resemble authorized user input.

The repair is an explicit state machine with a unique turn ID, recognizer-generation ownership, a single executable-final latch, bounded deduplication and wake debounce, one incomplete-recognition retry, explicit authorization for media-time capture, TTS fingerprint matching, and structured rejection diagnostics.

## Brain health and routing

The apparent 30-second silence was caused by treating model readiness and response generation as one opaque operation. The old watchdog could report a generic timeout without identifying the blocked stage, canceling native work reliably, or giving the user a prompt fallback. Concurrent callers could also attempt redundant preparation.

The repair separates load and generation watchdogs, joins callers onto one pending load, invalidates stale request generations, invokes native cancellation, persists a visible status and exact stage, and returns a deterministic labeled fallback. The generation timeout remains 30 seconds; it was not increased to conceal the failure.

## Screen control

Number markers had become the primary discovery mechanism. Geometry could survive visual churn while the underlying node identity changed, and separate marker windows made lifetime and stability difficult to reason about.

The repair resolves semantic identity first, exposes a stable target drawer, then grid selection, then a single-container numbered fallback. Every action reacquires and revalidates text/content description/resource ID/role/clickable ancestry; geometry narrows candidates but cannot establish identity.

## Toolbelt

Workbench did not have a cohesive, permission-aware utilities surface. The repair adds six functional tools with explicit instructions, permissions, confirmation boundaries, progress/cancel behavior, and real error reporting. Android remains the authority for APK installation confirmation; links require confirmation; network discovery is restricted to the current private local subnet.
