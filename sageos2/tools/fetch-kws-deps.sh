#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
DEPS="$ROOT/.deps/kws"
LIBS="$APP/libs"
ASSETS="$APP/src/main/assets/sherpa-kws"
AAR_VERSION="1.13.7"
AAR_NAME="sherpa-onnx-${AAR_VERSION}.aar"
AAR_SHA="c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
CHECKSUM_SHA="284637b2b9fec1287aca10315dcc960710c6ec14224fb1dfa9fe427e77eb6c18"
MODEL_NAME="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2"
MODEL_SHA="2e6ac2577310bfa2f4b6b5fab0478b868c9d0b2cb2c51b3e13b50581b588864d"
MARKER="$DEPS/ready-v1"

mkdir -p "$DEPS" "$LIBS" "$ASSETS"

if [[ -f "$MARKER" ]] \
  && grep -qx "$AAR_SHA $MODEL_SHA" "$MARKER" \
  && [[ -f "$LIBS/$AAR_NAME" ]] \
  && [[ -f "$ASSETS/encoder.int8.onnx" ]] \
  && [[ -f "$ASSETS/decoder.onnx" ]] \
  && [[ -f "$ASSETS/joiner.int8.onnx" ]] \
  && [[ -f "$ASSETS/tokens.txt" ]] \
  && [[ -f "$ASSETS/bpe.model" ]]; then
  echo "Pinned Sage KWS dependencies already prepared"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fL --retry 3 -o "$TMP/$AAR_NAME" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VERSION}/${AAR_NAME}"
echo "$AAR_SHA  $TMP/$AAR_NAME" | sha256sum -c -

curl -fL --retry 3 -o "$TMP/checksum.txt" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/checksum.txt"
echo "$CHECKSUM_SHA  $TMP/checksum.txt" | sha256sum -c -
grep -F "$MODEL_NAME" "$TMP/checksum.txt" | grep -F "$MODEL_SHA" >/dev/null

curl -fL --retry 3 -o "$TMP/$MODEL_NAME" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL_NAME"
echo "$MODEL_SHA  $TMP/$MODEL_NAME" | sha256sum -c -

tar --no-same-owner -xjf "$TMP/$MODEL_NAME" -C "$TMP"
MODEL_DIR="$TMP/${MODEL_NAME%.tar.bz2}"
test -d "$MODEL_DIR"

rm -rf "$ASSETS"
mkdir -p "$ASSETS" "$LIBS" "$DEPS"
cp "$TMP/$AAR_NAME" "$LIBS/$AAR_NAME"
cp "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS/encoder.int8.onnx"
cp "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$ASSETS/decoder.onnx"
cp "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS/joiner.int8.onnx"
cp "$MODEL_DIR/tokens.txt" "$ASSETS/tokens.txt"
cp "$MODEL_DIR/bpe.model" "$ASSETS/bpe.model"
cp "$MODEL_DIR/keywords.txt" "$ASSETS/keywords.txt"
printf '%s %s\n' "$AAR_SHA" "$MODEL_SHA" > "$MARKER"

echo "Prepared sherpa-onnx $AAR_VERSION and verified English mobile KWS model"
