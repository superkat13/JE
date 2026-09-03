#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_33_3.sh <output-directory>" >&2
  exit 2
fi
OUTPUT="$1"
bash sage_tools/reconstruct_v1_33_2.sh "$OUTPUT"
patch -d "$OUTPUT" -p1 --forward --batch < sage_recovery/compile_fix_v1_33_2.patch
patch -d "$OUTPUT" -p1 --forward --batch < sage_patches/normal_tablet_v1_33_3.patch
python3 sage_tests/test_normal_tablet_v1_33_3.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.33.3 (49): normal tablet wake recovery, lossless queued text chat, preserved Brain watchdog, and external signing configuration in $OUTPUT"
