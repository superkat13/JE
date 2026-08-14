#!/usr/bin/env python3
from pathlib import Path
import sys

AAR_SHA = "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_sherpa_engine_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    state = (root / "app/src/main/java/com/pineapple/sage/SageSpeechBackendState.java").read_text(encoding="utf-8")
    require(gradle, 'implementation(files("libs/sherpa-onnx-1.13.4.aar"))', "local pinned AAR dependency")
    require(gradle, 'implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")', "Kotlin runtime for official API")
    require(state, f'SHERPA_AAR_SHA256 = "{AAR_SHA}"', "official AAR digest")
    require(state, 'Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer"', "Java API probe")
    require(state, 'libsherpa-onnx-jni.so', "JNI native probe")
    require(state, 'libonnxruntime.so', "ONNX Runtime probe")
    require(state, 'sherpaEnginePresent(Context context)', "combined engine probe")
    require(state, 'return sherpaEnginePresent(context) && sherpaModelPresent(context);', "engine plus model gate")
    require(state, 'Android SpeechRecognizer (current command recognizer)', "Android remains current recognizer")
    if 'sherpa-onnx (current command recognizer)' in state:
        raise SystemExit("packaging engine must not silently switch command STT")
    print("Sage 1.31 sherpa engine packaging regression passed")


if __name__ == "__main__":
    main()
