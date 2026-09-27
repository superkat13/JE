#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA="${1:-$ROOT/third_party/llama.cpp}"
BUILD="${2:-$ROOT/.deps/native-brain-test}"
PIN=d73c1d6b22a2d3ecc74c2c9cde354015ee72e862
test "$(git -C "$LLAMA" rev-parse HEAD)" = "$PIN"
mkdir -p "$BUILD"
MODEL="$BUILD/stories260K.gguf"
if [[ ! -f "$MODEL" ]]; then
  curl -fL --retry 3 --connect-timeout 15 --max-time 120 \
    https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf -o "$MODEL"
fi
echo "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d  $MODEL" | sha256sum -c -
# A test-only tiny GGUF: never package it or replace the owner's private model.
cmake -S "$LLAMA" -B "$BUILD/build" -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS='-O1 -fsanitize=address -fno-omit-frame-pointer' \
  -DCMAKE_C_FLAGS='-O1 -fsanitize=address -fno-omit-frame-pointer' \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build "$BUILD/build" --target llama -j 4
mapfile -t libs < <(find "$BUILD/build" -name '*.a' -type f | sort)
g++ -std=c++17 -O1 -g -fsanitize=address -fno-omit-frame-pointer \
  -I"$ROOT/tools/native-test-shims" -I"$LLAMA/include" -I"$LLAMA/ggml/include" \
  "$ROOT/tools/test-native-brain.cpp" \
  -Wl,--start-group "${libs[@]}" -Wl,--end-group -pthread -ldl -o "$BUILD/native-brain-test"
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 "$BUILD/native-brain-test" "$MODEL"
