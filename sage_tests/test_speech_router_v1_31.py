#!/usr/bin/env python3
from pathlib import Path
import sys


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_speech_router_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    main_text = (java / "MainActivity.java").read_text(encoding="utf-8")
    manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    state = (java / "SageSpeechBackendState.java").read_text(encoding="utf-8")
    lab = (java / "SageSpeechLabActivity.java").read_text(encoding="utf-8")

    require(main_text, "Speech Lab — recognizer evidence", "Speech Lab home entry")
    require(main_text, "SageSpeechLabActivity.class", "Speech Lab launch")
    require(manifest, '.SageSpeechLabActivity', "Speech Lab manifest")
    require(state, 'SHERPA_VERSION = "1.13.4"', "pinned sherpa integration target")
    require(state, 'MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"', "small English streaming model")
    require(state, 'libsherpa-onnx-jni.so', "sherpa native readiness")
    require(state, 'libonnxruntime.so', "onnxruntime readiness")
    require(state, 'encoder-epoch-99-avg-1.int8.onnx', "int8 encoder readiness")
    require(state, 'decoder-epoch-99-avg-1.onnx', "decoder readiness")
    require(state, 'joiner-epoch-99-avg-1.int8.onnx', "int8 joiner readiness")
    require(state, 'Android SpeechRecognizer (current command recognizer)', "truthful current backend")
    require(state, 'sherpa-onnx is not active yet; Android remains the truthful fallback', "truthful fallback state")
    require(lab, "A / B recognizer evidence", "A/B diagnostics")
    require(lab, "Sherpa readiness", "readiness heading")
    require(lab, "Recent speech evidence", "speech evidence heading")
    require(lab, "latency_ms=", "latency evidence filter")
    require(lab, "confidence=", "confidence evidence filter")
    require(lab, "confirmation_required", "confirmation evidence filter")
    if "sherpa-onnx (current command recognizer)" in state:
        raise SystemExit("speech router must not claim sherpa is already active")
    print("Sage 1.31 speech router/readiness regression passed")


if __name__ == "__main__":
    main()
