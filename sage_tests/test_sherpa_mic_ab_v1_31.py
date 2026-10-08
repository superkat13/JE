#!/usr/bin/env python3
from pathlib import Path
import sys


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_sherpa_mic_ab_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    tester = (java / "SageSherpaMicTester.java").read_text(encoding="utf-8")
    lab = (java / "SageSpeechLabActivity.java").read_text(encoding="utf-8")

    require(tester, 'SAMPLE_RATE = 16_000', "16 kHz local audio")
    require(tester, 'FEATURE_DIM = 80', "80-bin feature config")
    require(tester, 'MAX_TEST_MS = 15_000L', "bounded test duration")
    require(tester, 'MediaRecorder.AudioSource.MIC', "official-style mic source")
    require(tester, 'OnlineRecognizerKt.getModelConfig(10)', "exact small English model config")
    require(tester, 'new FeatureConfig()', "Java-visible FeatureConfig ABI")
    require(tester, 'new OnlineRecognizerConfig()', "Java-visible recognizer config ABI")
    require(tester, 'new OnlineRecognizer(null, config)', "file-backed recognizer")
    require(tester, 'stream.acceptWaveform(samples, SAMPLE_RATE)', "streaming audio feed")
    require(tester, 'while (recognizer.isReady(stream)) recognizer.decode(stream);', "streaming decoder loop")
    require(tester, 'recognizer.isEndpoint(stream)', "endpoint detection")
    require(tester, 'endpointDetected = true', "truthful endpoint state")
    require(tester, 'first_partial_ms=', "first-partial telemetry")
    require(tester, 'route=speech_lab_only executed=false', "non-executing A/B route")
    require(tester, 'Stop Sage background listening and retry', "microphone ownership diagnostic")
    require(lab, 'Local microphone A / B test', "Speech Lab A/B section")
    require(lab, 'Run local STT mic test', "single local STT test action")
    require(lab, 'Stop local STT test', "adaptive stop action")
    require(lab, 'Speech Lab only; no command executed.', "visible non-execution result")
    for forbidden in ('SageCommandEngine', 'dispatchTypedCommand', 'executeCommand(', 'startService('):
        if forbidden in tester:
            raise SystemExit("isolated mic test must not execute/route commands: " + forbidden)
    print("Sage 1.31 isolated sherpa microphone A/B regression passed")


if __name__ == "__main__":
    main()
