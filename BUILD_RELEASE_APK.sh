#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Android SDK not found. Installing command-line toolchain..."
  "$ROOT_DIR/scripts/install_android_toolchain_linux.sh"
fi

if [ ! -f "third_party/llama.cpp/CMakeLists.txt" ]; then
  echo "llama.cpp is missing. Attempting to fetch it for full local GGUF inference..."
  ./fetch_llama.sh || echo "Could not fetch llama.cpp; building with native fallback stub."
fi

mkdir -p keystore dist

if [ ! -f "keystore/je-release.jks" ]; then
  echo "Generating local release keystore..."
  keytool -genkeypair \
    -v \
    -keystore keystore/je-release.jks \
    -storepass je-release-password \
    -keypass je-release-password \
    -alias je_release \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=JE Personal,O=JE,C=US"
fi

export JE_KEYSTORE="keystore/je-release.jks"
export JE_KEYSTORE_PASSWORD="je-release-password"
export JE_KEY_ALIAS="je_release"
export JE_KEY_PASSWORD="je-release-password"

./gradlew --no-daemon :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "Release APK was not produced."
  exit 1
fi

cp "$APK" "dist/JE-production-release.apk"

echo
echo "DONE: dist/JE-production-release.apk"
