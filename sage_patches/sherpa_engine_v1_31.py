#!/usr/bin/env python3
"""Package the pinned sherpa-onnx Android engine without routing command STT to it yet."""
from pathlib import Path
import sys

AAR_NAME = "sherpa-onnx-1.13.4.aar"
AAR_SHA256 = "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sherpa_engine_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = root / "app/build.gradle.kts"
    state = root / "app/src/main/java/com/pineapple/sage/SageSpeechBackendState.java"
    if not gradle.is_file() or not state.is_file():
        raise SystemExit("Sage speech-router source is missing")

    gradle_text = gradle.read_text(encoding="utf-8")
    dependency = f'implementation(files("libs/{AAR_NAME}"))'
    if dependency not in gradle_text:
        gradle_text += f'''\n\n// Pinned local sherpa-onnx Android engine. The release workflow downloads and SHA-256 verifies it.\ndependencies {{\n    {dependency}\n    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")\n}}\n'''
        gradle.write_text(gradle_text, encoding="utf-8")
    (root / "app/libs").mkdir(parents=True, exist_ok=True)

    replace_once(state,
'''    static final String SHERPA_VERSION = "1.13.4";
    static final String MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17";
''',
f'''    static final String SHERPA_VERSION = "1.13.4";
    static final String SHERPA_AAR_NAME = "{AAR_NAME}";
    static final String SHERPA_AAR_SHA256 = "{AAR_SHA256}";
    static final String MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17";
''', "pinned sherpa AAR identity")

    replace_once(state,
'''    static boolean sherpaNativePresent(Context context) {
        if (context == null || context.getApplicationInfo() == null
                || context.getApplicationInfo().nativeLibraryDir == null) return false;
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libsherpa-onnx-jni.so").isFile()
                && new File(nativeDir, "libonnxruntime.so").isFile();
    }
''',
'''    static boolean sherpaJavaApiPresent(Context context) {
        if (context == null) return false;
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer", false,
                    context.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean sherpaNativePresent(Context context) {
        if (context == null || context.getApplicationInfo() == null
                || context.getApplicationInfo().nativeLibraryDir == null) return false;
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libsherpa-onnx-jni.so").isFile()
                && new File(nativeDir, "libonnxruntime.so").isFile();
    }

    static boolean sherpaEnginePresent(Context context) {
        return sherpaJavaApiPresent(context) && sherpaNativePresent(context);
    }
''', "sherpa Java/native engine probe")

    replace_once(state,
'''    static boolean sherpaReady(Context context) {
        return sherpaNativePresent(context) && sherpaModelPresent(context);
    }
''',
'''    static boolean sherpaReady(Context context) {
        return sherpaEnginePresent(context) && sherpaModelPresent(context);
    }
''', "engine plus model readiness")

    replace_once(state,
'''        return "Current command backend: " + activeCommandBackend(context)
                + "\\nTarget local backend: sherpa-onnx " + SHERPA_VERSION
                + "\\nTarget English streaming model: " + MODEL_ID
                + "\\nSherpa native libraries present: " + yesNo(sherpaNativePresent(context))
''',
'''        return "Current command backend: " + activeCommandBackend(context)
                + "\\nTarget local backend: sherpa-onnx " + SHERPA_VERSION
                + "\\nPinned engine asset: " + SHERPA_AAR_NAME
                + "\\nPinned engine SHA-256: " + SHERPA_AAR_SHA256
                + "\\nTarget English streaming model: " + MODEL_ID
                + "\\nSherpa Java API present: " + yesNo(sherpaJavaApiPresent(context))
                + "\\nSherpa native libraries present: " + yesNo(sherpaNativePresent(context))
                + "\\nSherpa engine packaged: " + yesNo(sherpaEnginePresent(context))
''', "engine evidence in Speech Lab")

    gradle_text = gradle.read_text(encoding="utf-8")
    state_text = state.read_text(encoding="utf-8")
    for marker in (
        dependency,
        'implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")',
        f'SHERPA_AAR_SHA256 = "{AAR_SHA256}"',
        'Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer"',
        "sherpaEnginePresent(Context context)",
        "Sherpa engine packaged:",
    ):
        if marker not in gradle_text + state_text:
            raise SystemExit("missing sherpa engine marker: " + marker)
    if 'return "sherpa-onnx (current command recognizer)"' in state_text:
        raise SystemExit("engine packaging pass must not switch command recognition yet")
    print("Applied Sage 1.31 verified sherpa-onnx engine packaging seam")


if __name__ == "__main__":
    main()
