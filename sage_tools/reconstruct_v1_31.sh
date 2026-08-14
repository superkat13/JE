#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo "usage: reconstruct_v1_31.sh <output-directory>" >&2; exit 2; fi
OUTPUT="$1"
bash sage_tools/reconstruct_v1_30_1.sh "$OUTPUT"
python3 sage_patches/brain_presence_v1_31.py "$OUTPUT"
python3 sage_patches/response_route_v1_31.py "$OUTPUT"
python3 sage_patches/natural_voice_v1_31.py "$OUTPUT"
python3 sage_patches/speech_router_v1_31.py "$OUTPUT"
python3 sage_patches/sherpa_engine_v1_31.py "$OUTPUT"
python3 sage_patches/sherpa_model_pack_v1_31.py "$OUTPUT"
python3 sage_patches/release_v1_31.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.31.0 (44) with Brain presence, response-route telemetry, natural Voice Studio, Speech Lab, verified sherpa engine packaging, resumable hash-verified local speech model management, and all 1.30.1 continuity preserved in $OUTPUT"
