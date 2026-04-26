# JE Final Move-Forward Package

This package is the reproducible handoff for building and installing JE.

## What changed in this pass

- Fixed the native CMake path so `app/src/main/cpp/CMakeLists.txt` correctly finds root-level `third_party/llama.cpp`.
- Added `VERIFY_JE_BUILD.sh` to validate Java, Android SDK, Gradle wrapper, ADB, and native paths.
- Added `INSTALL_JE_DEVICE.sh` to install the produced APK with ADB.
- Preserved the release build automation in `BUILD_RELEASE_APK.sh`.

## Build

```bash
chmod +x VERIFY_JE_BUILD.sh BUILD_RELEASE_APK.sh INSTALL_JE_DEVICE.sh
./VERIFY_JE_BUILD.sh
./BUILD_RELEASE_APK.sh
```

Final APK:

```text
dist/JE-production-release.apk
```

## Install

```bash
./INSTALL_JE_DEVICE.sh
```

## First run

1. Open JE.
2. Grant microphone permission if you want voice.
3. Choose a `.gguf` model when prompted.
4. Use `remember ...`, `note ...`, `memory`, and `daily` for daily workflows.

## Notes

The generated keystore is suitable for a personal installable APK. If you ever distribute or migrate signing keys, create and store a private production keystore outside this repo.
