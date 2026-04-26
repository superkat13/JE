#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "== JE Build Verification =="

fail=0

check() {
  if "$@" >/dev/null 2>&1; then
    echo "OK: $*"
  else
    echo "MISSING/FAIL: $*"
    fail=1
  fi
}

check java -version
check keytool -help

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  echo "OK: ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
else
  echo "WARN: ANDROID_SDK_ROOT not set. BUILD_RELEASE_APK.sh will default to \$HOME/Android/Sdk."
fi

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "OK: sdkmanager found"
else
  echo "MISSING: sdkmanager not found at $SDK/cmdline-tools/latest/bin/sdkmanager"
  fail=1
fi

if [ -x "$SDK/platform-tools/adb" ]; then
  echo "OK: adb found"
else
  echo "WARN: adb not found yet. It will be installed by sdkmanager/platform-tools."
fi

if [ -x "./gradlew" ]; then
  echo "OK: gradlew executable"
else
  echo "FIXING: chmod +x gradlew"
  chmod +x ./gradlew
fi

if [ -f "app/src/main/cpp/CMakeLists.txt" ]; then
  grep -q "../../../../third_party/llama.cpp" app/src/main/cpp/CMakeLists.txt \
    && echo "OK: CMake llama.cpp path points to project root third_party" \
    || { echo "FAIL: CMake llama.cpp path looks wrong"; fail=1; }
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "Verification passed. Run: ./BUILD_RELEASE_APK.sh"
else
  echo "Verification found missing pieces. Run: ./scripts/install_android_toolchain_linux.sh, then rerun this script."
fi

exit "$fail"
