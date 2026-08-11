#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_29.sh <output-directory>" >&2
  exit 2
fi

OUTPUT="$1"
bash sage_tools/reconstruct_v1_28.sh "$OUTPUT"
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/brain_repair_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/conversation_media_echo_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/intent_semantic_actions_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/confirmation_tone_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/surprise_discovery_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/brain_route_repair_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/professional_brain_repair_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/physical_acceptance_cleanup_v1_29.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/owner_home_categories_v1_29.patch
python3 sage_patches/media_voice_repair_v1_29.py "$OUTPUT"
python3 sage_patches/external_action_lifecycle_v1_29.py "$OUTPUT"
python3 sage_patches/surprise_fun_reliability_v1_29.py "$OUTPUT"
python3 sage_patches/monster_mode_v1_29.py "$OUTPUT"
python3 sage_patches/one_sage_routing_v1_29.py "$OUTPUT"
python3 sage_patches/easter_egg_personality_v1_29.py "$OUTPUT"
python3 sage_patches/voice_tolerance_v1_29.py "$OUTPUT"
python3 sage_patches/internal_specialist_routing_v1_29.py "$OUTPUT"
python3 sage_patches/owner_authority_cleanup_v1_29.py "$OUTPUT"
python3 sage_patches/red_queen_entry_v1_29.py "$OUTPUT"
python3 sage_patches/device_authority_v1_29.py "$OUTPUT"
python3 sage_patches/privilege_readiness_v1_29.py "$OUTPUT"
python3 sage_patches/dell_evidence_import_v1_29.py "$OUTPUT"
python3 sage_patches/capability_snapshot_v1_29.py "$OUTPUT"
python3 sage_patches/background_survival_v1_29.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.29.0 with preserved Brain/conversation systems, normal one-Sage owner experience, spoken hidden Red Queen doorway, automatic internal specialist routing, centralized owner consequence decisions, truthful Android device-authority inspection, optional owner-approved device-admin activation, read-only root/boot privilege readiness evidence, paste-only Dell evidence interpretation, live capability snapshot, persistent Easter egg personality replies, bounded alternate-candidate voice recovery, foreground background-listening survival, and deterministic compatibility in $OUTPUT"
