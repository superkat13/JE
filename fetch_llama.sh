#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$ROOT_DIR/third_party/llama.cpp"

mkdir -p "$ROOT_DIR/third_party"

if [ -f "$TARGET/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $TARGET"
  exit 0
fi

rm -rf "$TARGET"

if ! command -v git >/dev/null 2>&1; then
  echo "git is required to fetch llama.cpp. Install git, then rerun ./fetch_llama.sh"
  exit 1
fi

git clone --depth=1 https://github.com/ggml-org/llama.cpp.git "$TARGET"
echo "llama.cpp fetched successfully."
