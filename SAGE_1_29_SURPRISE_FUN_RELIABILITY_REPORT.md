# Sage 1.29 Surprise Me reliability slice

## Owner-visible improvement

- `Surprise me`, `Surprise me on YouTube`, `Cure my boredom`, and `Send me down a rabbit hole` now complete through either the YouTube app or the exact browser package Android resolved for the YouTube search.
- A compact overlay exposes two real controls after discovery starts: **Another** selects again and **Stop** cancels pending discovery and stops or pauses active media.
- `Surprise me` while YouTube is already visible uses video semantics, not generic page controls.
- Natural follow-ups include `one more`, `something else`, `surprise me again`, `stop surprising me`, and `that's enough`.

## Root cause repaired

The prior fallback launched a YouTube results URL in a browser but accepted accessibility events only when the Android package name contained `youtube`. That made browser fallback permanently pending even though the correct YouTube page had opened. The repair records the exact package resolved by Android before launch, accepts only that package during the bounded selection window, revalidates the semantic candidate, and then opens one result.

## Safety and lifecycle

- No numbered overlay is invoked.
- Red Queen storage is never queried.
- Player controls, navigation, ads, stale nodes, and disabled targets are excluded from random selection.
- The pending operation expires after 20 seconds and removes its visible controls.
- Cancellation no longer clears unrelated Accessibility handler callbacks.
- Controlled randomness rotates curated topics while retaining the existing persistent recent-selection exclusion list.

Physical tablet verification remains required for the exact YouTube app/browser installed by the owner.
