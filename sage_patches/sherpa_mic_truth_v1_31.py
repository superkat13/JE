#!/usr/bin/env python3
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sherpa_mic_truth_v1_31.py <reconstructed-source>")
    path = Path(sys.argv[1]) / "app/src/main/java/com/pineapple/sage/SageSherpaMicTester.java"
    replace_once(path, '        String lastText = "";\n',
                 '        String lastText = "";\n        boolean endpointDetected = false;\n',
                 "endpoint telemetry state")
    replace_once(path, '                if (recognizer.isEndpoint(stream) && !text.isEmpty()) break;\n',
                 '                if (recognizer.isEndpoint(stream) && !text.isEmpty()) {\n                    endpointDetected = true;\n                    break;\n                }\n',
                 "endpoint detection")
    replace_once(path, '                            + " audio_ms=" + audioMs + " endpoint=true");\n',
                 '                            + " audio_ms=" + audioMs + " endpoint=" + endpointDetected);\n',
                 "truthful endpoint report")
    print("Applied truthful endpoint telemetry to Sage 1.31 sherpa A/B test")


if __name__ == "__main__":
    main()
