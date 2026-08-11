# Sage 1.29 owner conversation finish

This slice fixes the difference between having a confirmation architecture and the owner
actually hearing it at the right time.

## Working behavior

- A low-confidence final transcript is now held before command dispatch.
- Sage asks one exact “Did you say …?” question after the existing one bounded retry.
- “Yes” executes the held command once and stores one owner-confirmed correction record.
- “No” executes nothing, asks for one repeat, and learns the rejected-to-corrected mapping
  only after the owner supplies the replacement.
- Confirmation answers cannot recursively open another confirmation.
- Duplicate and stale callbacks remain blocked, and held commands do not increment the
  dispatch counter.
- Authentication, Red Queen, installer, permission, destructive, and authority phrases
  remain non-learnable.

## Immediately audible improvement

Voice Studio now has a one-tap **Fix robotic voice — preview & save** control. It selects
the best installed offline British voice available, resets the unusually fast robotic
rate to `0.88`, lowers pitch to `0.96`, saves the profile, and previews it immediately.
Rate and pitch values are visible while adjusting sliders. Preview no longer overwrites a
saved preset with the misleading `CUSTOM` label.

The existing owner tone setting remains one Sage identity. Saying “Sage, you can cuss
around me” now gives immediate unfiltered feedback while security, diagnostic, exact-error,
and verification text stays exact.

Physical microphone and voice quality remain owner-tablet acceptance checks; host tests do
not claim the Android recognizer or installed TTS engine produced audio.
