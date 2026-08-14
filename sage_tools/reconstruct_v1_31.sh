#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo "usage: reconstruct_v1_31.sh <output-directory>" >&2; exit 2; fi
OUTPUT="$1"
bash sage_tools/reconstruct_v1_30_1.sh "$OUTPUT"
python3 sage_patches/brain_presence_v1_31.py "$OUTPUT"
python3 sage_patches/response_route_v1_31.py "$OUTPUT"
python3 sage_patches/natural_voice_v1_31.py "$OUTPUT"
python3 sage_patches/route_introspection_v1_31.py "$OUTPUT"
python3 sage_patches/release_v1_31.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.31.0 (44) with the current verified 1.31 feature set preserved in $OUTPUT"
