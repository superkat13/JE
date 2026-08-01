# Sage 1.25 confirmed root causes and related findings

## Numbered accessibility overlay

### Confirmed evidence

The original overlay event policy treated a different accessibility window ID as navigation even when the package had not changed. Android accessibility overlays and content-loading churn can create/report transient window IDs under the same host package. That caused an immediate call to the clear path after marker creation.

Two additional lifecycle couplings independently cleared valid markers: `MainActivity.onResume()` called the clear API, and voice follow-up cancellation cleared the overlay even though follow-up state and screen-marker state have different lifetimes.

The fixed policy ignores Sage-owned events and harmless same-package window/content events, including the 2.5-second creation grace period. It still clears for package navigation, click, scroll, manual/voice clear, successful selection, invalid selection root, tablet actions, global navigation, service interrupt/unbind, replacement, and configured timeout. Every clear records reason, event type, package, window ID, and elapsed time. Timeout choices are 15 seconds, 30 seconds, 60 seconds, two minutes, and persistent-until-action.

### Assumptions requiring tablet evidence

The reported real-device instant disappearance is consistent with the confirmed window-ID/onResume/follow-up clear paths. Repository inspection cannot identify which one fired on that specific tablet before the new timestamped diagnostics are collected.

## Speech recognition

### Confirmed evidence

The previous final-result handler combined final candidates with earlier partial candidates before selection. A stale or shorter partial could therefore compete with a better Android final result. It did not retain a complete decision record containing candidate sets, selected phrase, confidence, normalization, match result, and rejection reason, and it had no bounded quality retry distinct from the existing recognizer-busy retry.

The fixed handler chooses from valid final candidates whenever any exist and uses partials only as an error/final-empty fallback. Confidence is associated with the selected final candidate. Empty, incomplete, or below-0.35 results retry once; generation counters and retry limits prevent stale callbacks and loops. Self-echo is rejected before routing and logged.

### Assumptions requiring tablet evidence

Microphone hardware, Android speech-provider quality, room acoustics, and language-model availability can still cause recognition loss before Sage receives text. The source fix improves selection and retry behavior but cannot prove a specific tablet's acoustic performance without a live voice checklist.

## Memory

### Confirmed evidence

Generic phrase-learning and Brain fallback paths could handle remember-shaped text while the one-shot memory follow-up was still stale. The old flow lacked a separate persistent, idempotent memory record with deterministic last-item recall. A spoken acknowledgement captured as recognition could consequently re-enter command routing, while the pending follow-up could repeat its prompt/response.

The fixed command engine handles `remember_item` before generic fallbacks, clears that pending state before save/cancel, normalizes direct `remember that …` forms, saves a unique item once, acknowledges once, logs duplicates, rejects Sage speech echo in the voice service, persists both ordered items and the last saved item, and clears both keys on `clear memory`.

### Assumptions requiring tablet evidence

The user's repeated response is consistent with stale follow-up plus acknowledgement echo. Without a pre-fix diagnostic capture, the repository cannot prove whether both triggers occurred or only one.

## Related Forge/CI finding

The approved-repair GitHub workflow reconstructed only through self-repair while its test loop included Workbench and Forge tests. That path would fail because generated Workbench/Forge sources were absent. The reconstruction now applies both patch generators and explicitly runs the Forge integration suite. This fix is directly related to verifying Forge-enabled repair candidates; no unrelated workflow behavior changed.
