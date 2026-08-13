#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_30.sh <output-directory>" >&2
  exit 2
fi

OUTPUT="$1"
bash sage_tools/reconstruct_v1_29.sh "$OUTPUT"
python3 sage_patches/autonomy_forge_transport_v1_30.py "$OUTPUT"
python3 sage_patches/release_v1_30.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.30.0 with direct paired-Forge autonomy dispatch/result transport layered on the verified 1.29 autonomy checkpoint. Permanent package/signer continuity, installed data, memories, wake profiles, Brain, conversation state machine, Red Queen owner boundary, Shizuku authority, bounded tools, and glass verification remain preserved in $OUTPUT"
