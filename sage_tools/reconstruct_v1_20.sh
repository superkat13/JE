#!/usr/bin/env bash
set -euo pipefail

DESTINATION="${1:-sage_build}"
rm -rf "$DESTINATION"
mkdir -p "$DESTINATION"

cat sage_package/part_*.b64 | tr -d '\n\r ' | base64 --decode > Sage_Tablet_Control_v1_source.zip
echo "9d23d8566cd30a81818b99f7f032a823b89d98221da029b31c6d4838d5a07d91  Sage_Tablet_Control_v1_source.zip" | sha256sum --check
unzip -q Sage_Tablet_Control_v1_source.zip -d "$DESTINATION"

patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/result_click_v1_1.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/voice_redqueen_v1_2.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/recognition_v1_4.patch

base64 --decode sage_patches/natural_voice_v1_5.patch.gz.b64 | gunzip > /tmp/natural_voice_v1_5.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < /tmp/natural_voice_v1_5.patch

patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/updater_v1_6.patch

base64 --decode sage_patches/wake_learn_click_v1_7.patch.gz.b64 | gunzip > /tmp/wake_learn_click_v1_7.patch
echo "c5d5ee8681c03f393a77ff7da9b8afc0d6c2ff7f7d0d54ea4687e1144bab77dc  /tmp/wake_learn_click_v1_7.patch" | sha256sum --check
patch --fuzz=0 -d "$DESTINATION" -p1 < /tmp/wake_learn_click_v1_7.patch

patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/native_speech_v1_9.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/voice_accuracy_v1_10.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/native_wake_teach_v1_11.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/echo_guard_v1_12.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/conversation_voice_v1_13.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/translation_v1_14.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/wake_learning_v1_15.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/media_responses_v1_16.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/screen_targeting_v1_17.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/conversation_diagnostics_v1_18.patch
patch --fuzz=0 -d "$DESTINATION" -p1 < sage_patches/brain_v1_19.patch

base64 --decode sage_patches/wake_match_v1_20.patch.gz.b64 | gunzip > /tmp/wake_match_v1_20.patch
echo "7342e6ba2fc5a374b0ff77e222471912633e5f775a3ae92d5553a79a160a8547  /tmp/wake_match_v1_20.patch" | sha256sum --check
patch --fuzz=0 -d "$DESTINATION" -p1 < /tmp/wake_match_v1_20.patch

test -f "$DESTINATION/app/src/main/java/com/pineapple/sage/SageVoiceService.java"
test -f "$DESTINATION/app/src/main/java/com/pineapple/sage/SageAccessibilityService.java"
echo "Reconstructed exact Sage 1.20 source in $DESTINATION"
