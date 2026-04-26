# JE install checklist

## 1) Clone llama.cpp into the project
From the JE project root:

```bash
./fetch_llama.sh
```

Or manually:

```bash
cd third_party
git clone https://github.com/ggml-org/llama.cpp.git
```

## 2) Open in Android Studio
Open the `JE` folder as a project.

## 3) Install SDK packages if Android Studio prompts
Use SDK Manager to install:
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- NDK (Side by side) 28.2.13676358
- CMake 3.22.1+

## 4) Choose your model in the app
Copy a `.gguf` model anywhere visible to Android's file picker, for example Downloads.
Open JE and tap **Choose GGUF Model**. The app copies the model into private app storage and loads it from there.

## 5) Build and run
- Build > Make Project
- Run > Run 'app'

## 6) Turn on permissions after install
- Accessibility > JE Accessibility
- Notifications > Notification access > JE Notification Listener
