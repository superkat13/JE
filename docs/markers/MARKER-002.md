# MARKER #002 — Red Queen Alive physical glass checkpoint

## Physical truth
- Installed Sage reports version 1.29.0 (41), package `com.pineapple.sagecommander.stable`.
- Offline wake listener is active and physically accepts `sage` wake events.
- Command speech recognition successfully produced full finals for `what is 20 * 20` and `what's the meaning of life`.
- Local Brain completed both requests successfully, but observed generation latency was approximately 8.7 s and 10.1 s respectively.
- `Conversation mode` is currently OFF, so one-command behavior is expected and must not be misclassified as a conversation regression.
- Current diagnostic still retains an intermittent Android command-recognition error 2 indicating Android voice typing could not connect. Wake recognition remains independent and operational.
- Stale callback protection is active (`Stale callbacks blocked: 18`). Echo rejection remains low but nonzero (`Echoes rejected: 3`).

## Anchored core
- Do not replace the stabilized wake/conversation state machine.
- Preserve one Sage identity, saved data, wake profiles, Brain, Red Queen, Workbench, Toolbelt, Forge, package identity, signer identity, permissions, and update-over-existing continuity.
- Preserve offline wake-word packaging and native Brain packaging gates.
- Preserve current Red Queen Alive branch behavior unless a regression test proves a defect.

## New friction point
1. Command recognition still depends on Android voice typing and can transiently fail with recognizer error 2/network-service unavailability.
2. Local Brain time-to-answer for simple prompts is too slow for natural conversation.
3. Repair bundles can describe older source commits / stale historical last-error state, so physical-current diagnostics must outrank stale packet metadata when timestamps/build lineage disagree.

## Next engineering target
Add an additive command-recognition recovery layer around the stabilized recognizer. On transient Android recognizer service/network errors, recover without destroying wake authorization or conversation state, record exact fallback diagnostics, and prefer a bounded offline/local recognition path when technically available. Do not rip out the state machine.

Separately, reduce time-to-first-useful-response for deterministic/simple requests by routing them before full local-LLM generation where a verified deterministic answer exists. Preserve Brain fallback for non-deterministic knowledge/conversation requests.

## Anti-loop rule
Do not re-open the wake-model packaging incident unless current glass evidence shows wake initialization failing again. Historical `FileNotFoundException` values in older repair packets are not current proof when the active listener is `Offline wake word` and current wake events succeed.
