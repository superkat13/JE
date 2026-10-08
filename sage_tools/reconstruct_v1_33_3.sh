#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "usage: reconstruct_v1_33_3.sh <output-directory>" >&2
  exit 2
fi
OUTPUT="$1"

# The v1.32-v1.33.2 reconstruction sources are deliberately stored as seven
# verified chunks so GitHub can preserve the exact archive without truncation.
# Restore them on demand, making this entry point usable from a fresh checkout
# as well as from CI's already-restored workspace.
if [ ! -x sage_tools/reconstruct_v1_33_2.sh ]; then
  RECOVERY_ARCHIVE="$(mktemp /tmp/sage-recovery.XXXXXX.tar.xz)"
  trap 'rm -f "$RECOVERY_ARCHIVE"' EXIT
  test "$(find sage_recovery/chunks -maxdepth 1 -name 'part-*.b64' | wc -l)" -eq 7
  cat sage_recovery/chunks/part-*.b64 | tr -d '\r\n ' | base64 --decode > "$RECOVERY_ARCHIVE"
  echo '92ae23cc90f0da5c7aab16efe71c4d075b2dc28597a5bb519efa68510eb0fe17  '"$RECOVERY_ARCHIVE" | sha256sum -c -
  tar -xJf "$RECOVERY_ARCHIVE"
  chmod +x sage_tools/reconstruct_v1_32.sh sage_tools/reconstruct_v1_33.sh sage_tools/reconstruct_v1_33_2.sh
fi

if [ ! -f sage_patches/sherpa_primary_v1_31.py ]; then
  SHERPA_ARCHIVE="$(mktemp /tmp/sherpa-primary.XXXXXX.py.xz)"
  trap 'rm -f "${RECOVERY_ARCHIVE:-}" "$SHERPA_ARCHIVE"' EXIT
  base64 --decode sage_recovery/sherpa_primary_v1_31.py.xz.b64 > "$SHERPA_ARCHIVE"
  echo '19cfef2113c696bb7c3db1f98ff0bd8e2e7faa8c1ee65b0776532c1bbb8599b7  '"$SHERPA_ARCHIVE" | sha256sum -c -
  xz -dc "$SHERPA_ARCHIVE" > sage_patches/sherpa_primary_v1_31.py
  echo '7eee79ffb09a30eb65c9a8187475fa07bfbf392a28aadcbf64d6b03d8b4999a4  sage_patches/sherpa_primary_v1_31.py' | sha256sum -c -
fi

bash sage_tools/reconstruct_v1_33_2.sh "$OUTPUT"
patch -d "$OUTPUT" -p1 --forward --batch < sage_recovery/compile_fix_v1_33_2.patch
patch -d "$OUTPUT" -p1 --forward --batch < sage_patches/normal_tablet_v1_33_3.patch
python3 sage_tests/test_normal_tablet_v1_33_3.py "$OUTPUT"
echo "Reconstructed Sage Commander 1.33.3 (49): normal tablet wake recovery, lossless queued text chat, preserved Brain watchdog, and external signing configuration in $OUTPUT"
