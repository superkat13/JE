#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_28.sh <output-directory>" >&2
  exit 2
fi

OUTPUT="$1"

bash sage_tools/reconstruct_v1_20.sh "$OUTPUT"
python3 sage_patches/stabilize_v1_21.py "$OUTPUT"
python3 sage_patches/fix_android_lint_v1_21.py "$OUTPUT"
python3 sage_patches/brain_watchdog_v1_22.py "$OUTPUT"
python3 sage_patches/wake_profiles_and_recognition_v1_23.py "$OUTPUT"
python3 sage_patches/fix_wake_profiles_compile_v1_23.py "$OUTPUT"
python3 sage_patches/continuity_report_v1_24.py "$OUTPUT"
python3 sage_patches/number_overlay_release_v1_24_1.py "$OUTPUT"
python3 sage_patches/sage_release_v1_24_2.py "$OUTPUT"
python3 sage_patches/self_repair_foundation_v1_25.py "$OUTPUT"
python3 sage_patches/workbench_v1_25.py "$OUTPUT"
python3 sage_patches/sage_forge_v1_25.py "$OUTPUT"
patch --fuzz=0 -p1 -d "$OUTPUT" < SAGE_1_26_UNIFIED.diff
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/continuity_finish_v1_26.patch
patch --fuzz=0 -p1 -d "$OUTPUT" < sage_patches/sage_1_27_unified.patch
python3 sage_patches/creative_studio_v1_27.py "$OUTPUT"
python3 sage_patches/living_sage_v1_28.py "$OUTPUT"
python3 sage_patches/red_queen_v1_28.py "$OUTPUT"
python3 sage_patches/orchestrator_registry_v1_28.py "$OUTPUT"
python3 sage_patches/package_file_labs_v1_28.py "$OUTPUT"
python3 sage_patches/network_operations_v1_28.py "$OUTPUT"

echo "Reconstructed Sage Commander 1.28.0 source in $OUTPUT"
