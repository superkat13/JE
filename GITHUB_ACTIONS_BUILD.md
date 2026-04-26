# JE GitHub Actions APK Build

This project includes a ready-to-run GitHub Actions workflow:

```text
.github/workflows/build-je-apk.yml
```

## What it does

- Checks out JE.
- Installs JDK 17.
- Uses Gradle caching.
- Installs Android SDK platform/build tools.
- Installs NDK and CMake.
- Verifies JE build inputs.
- Builds a signed release APK.
- Uploads the APK as a GitHub Actions artifact.

## How to use it

1. Create a GitHub repository.
2. Upload all files from this JE folder to the repository root.
3. Open the repository on GitHub.
4. Go to **Actions**.
5. Select **Build JE APK**.
6. Click **Run workflow**.
7. Wait for the build to finish.
8. Open the completed workflow run.
9. Download the artifact named:

```text
JE-production-release-apk
```

Inside it will be:

```text
JE-production-release.apk
```

## Install on Android

Transfer the APK to your phone and tap it, or install with adb:

```bash
adb install -r JE-production-release.apk
```

## Notes

The included release keystore is generated for a personal build. For a long-term production/private build, replace it with your own keystore and keep it safe.
