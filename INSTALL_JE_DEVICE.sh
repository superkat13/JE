#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APK="${1:-dist/JE-production-release.apk}"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK"
  echo "Build it first with: ./BUILD_RELEASE_APK.sh"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install platform-tools with:"
  echo "./scripts/install_android_toolchain_linux.sh"
  exit 1
fi

adb devices
echo
echo "Installing $APK ..."
adb install -r "$APK"
echo "Installed JE."
