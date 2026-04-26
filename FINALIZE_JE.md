# JE Final Production Build

This package is finalized for a personal daily-use Android APK.

## Build on Linux

```bash
chmod +x BUILD_RELEASE_APK.sh
./BUILD_RELEASE_APK.sh
```

Output:

```text
dist/JE-production-release.apk
```

## Install

```bash
adb install -r dist/JE-production-release.apk
```

## Notes

- The build script installs Android command-line tools, SDK platform 36, build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, and Gradle 9.3.1 when needed.
- If `third_party/llama.cpp` is unavailable, JE still builds and installs with fallback mode. Run `./fetch_llama.sh` before building for full GGUF local inference.
- The release keystore is generated locally in `keystore/je-release.jks`. Keep it safe if you reinstall over the same signed app later.


## Move-forward verification

Run `./VERIFY_JE_BUILD.sh` before building and `./INSTALL_JE_DEVICE.sh` after `dist/JE-production-release.apk` is created.
