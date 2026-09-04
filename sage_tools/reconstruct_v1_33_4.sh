#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_33_4.sh <output-directory>" >&2
  exit 2
fi
OUTPUT="$1"
bash sage_tools/reconstruct_v1_33_3.sh "$OUTPUT"
patch -d "$OUTPUT" -p1 --forward --batch < sage_patches/physical_followup_v1_33_4.patch
python3 sage_tests/test_physical_followup_v1_33_4.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.33.4 (50): physical wake-fragment recovery, natural capability routing, continuation-aware text chat, and complete-sentence Brain budgets in $OUTPUT"
