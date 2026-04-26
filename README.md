# JE Daily

A private, daily-usable Android AI companion.

This build focuses on the features that make JE useful every day:

- Local GGUF model loading through Android's file picker
- Private app-storage model copy to avoid scoped-storage issues
- Chat-style UI with visible conversation history
- Voice input through Android SpeechRecognizer
- Text-to-speech replies
- Persistent personal memory
- Personal commands:
  - `remember I prefer concise replies`
  - `note buy coffee tomorrow`
  - `memory`
  - `daily`
  - `summarize today`
- Lightweight fallback mode when no model is loaded
- Native llama.cpp bridge with defensive error reporting

## Build requirements

Install in Android Studio SDK Manager:

- Android SDK Platform 36
- Android Build Tools 36.x
- NDK side-by-side
- CMake 3.22.1+

## llama.cpp requirement

This repo intentionally does not bundle llama.cpp source.

Clone it before building:

```bash
cd JE/third_party
git clone https://github.com/ggml-org/llama.cpp.git
```

Then open the `JE` folder in Android Studio.

## Daily usage

1. Install/run the app.
2. Tap **Model** and select a `.gguf` model.
3. Use **Send** for typed chat.
4. Use **Voice** for spoken input.
5. Use **Daily** for a quick memory/task check.
6. Use **Clear** to reset local memory.

A small phone-friendly model is recommended, such as a Q4 GGUF model around 1B-3B parameters.

## Privacy

- Memory is stored locally in app SharedPreferences.
- The selected model is copied into app-private storage.
- No cloud calls are made by this build.

## Notes

Accessibility and Notification services remain declared for future personal automation, but this daily build does not aggressively control apps. JE suggests; you decide.


## Final daily UX polish

This build includes:

- Haptic feedback for primary actions.
- Light sound feedback for taps, success, and errors.
- Gesture navigation:
  - Swipe right inside chat: voice input.
  - Swipe left inside chat: daily check.
  - Double tap chat: focus input.
- Typing indicator.
- Streaming text animation for JE replies.
- Persistent chat history with local SharedPreferences.
- Low-end device mode that avoids automatic model loading and disables automatic TTS to protect memory.

## Build

Open this folder in Android Studio, allow Gradle sync, then use:

Build > Generate Signed Bundle / APK > APK

Unsigned/debug alternative:

```bash
./gradlew :app:assembleDebug
```

Release APK location:

```text
app/build/outputs/apk/release/app-release.apk
```

Debug APK location:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install with ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release builds, install:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```


## GitHub Actions APK Build

This package includes `.github/workflows/build-je-apk.yml`. Upload this folder to GitHub, open the Actions tab, run **Build JE APK**, then download the `JE-production-release-apk` artifact.
