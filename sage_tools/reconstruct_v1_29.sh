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
echo "Reconstructed Sage Commander 1.29.0 with physical Brain and Surprise acceptance cleanup in $OUTPUT"
