#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_131")
java = root / "app/src/main/java/com/pineapple/sage"
service = (java / "SageVoiceService.java").read_text(encoding="utf-8")
sherpa = (java / "SageSherpaRecognitionService.java").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

checks = {
    "private RecognitionService exists": "extends RecognitionService" in sherpa,
    "primary component selection": "SageSherpaRecognitionService.primaryComponent(this)" in service,
    "android fallback preserved": "SpeechRecognizer.createSpeechRecognizer(this);" in service,
    "single SpeechRecognizer callback boundary": "SpeechRecognizer.createSpeechRecognizer(this, sherpa)" in service,
    "sherpa runtime pinned": 'libs/sherpa-onnx-1.13.4.aar' in gradle,
    "kotlin runtime present": 'org.jetbrains.kotlin:kotlin-stdlib:2.0.21' in gradle,
    "model pinned by identity": "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-int8" in sherpa,
    "int8 encoder": "encoder-epoch-99-avg-1.int8.onnx" in sherpa,
    "int8 joiner": "joiner-epoch-99-avg-1.int8.onnx" in sherpa,
    "offline tokens": 'sherpa-asr/tokens.txt' in sherpa,
    "local audio record": "AudioRecord" in sherpa and "VOICE_RECOGNITION" in sherpa,
    "streaming partials": "partialResults(resultBundle(text))" in sherpa,
    "endpoint finalization": "isEndpoint(stream)" in sherpa,
    "bounded utterance": "MAX_UTTERANCE_MS = 14_000L" in sherpa,
    "backend cooldown fallback": "unhealthyUntilMs" in sherpa and "ERROR_RECOGNIZER_BUSY" in sherpa,
    "no sherpa hotword dependency": "createStream(\"\")" in sherpa,
    "same app internal service": 'android:name=".SageSherpaRecognitionService"' in manifest and 'android:exported="false"' in manifest,
    "same package continuity": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "same 1.31 engineering version": 'versionCode = 44' in gradle and 'versionName = "1.31.0"' in gradle,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + name)
if failed:
    raise SystemExit("Sherpa primary ASR regression failed: " + ", ".join(failed))
print("Sherpa primary ASR regression: all checks passed")
