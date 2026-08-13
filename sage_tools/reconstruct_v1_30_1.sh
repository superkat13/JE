#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo "usage: reconstruct_v1_30_1.sh <output-directory>" >&2; exit 2; fi
OUTPUT="$1"
bash sage_tools/reconstruct_v1_30.sh "$OUTPUT"
python3 sage_patches/normalize_forge_activity_v1_30_1b.py "$OUTPUT"
python3 sage_patches/forge_compatibility_v1_30_1.py "$OUTPUT"
python3 sage_patches/release_v1_30_1.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.30.1 (43) with Forge compatibility negotiation and continuity preserved in $OUTPUT"
