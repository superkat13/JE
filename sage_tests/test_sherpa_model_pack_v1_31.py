#!/usr/bin/env python3
from pathlib import Path
import sys


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_sherpa_model_pack_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    manager = (java / "SageSherpaModelManager.java").read_text(encoding="utf-8")
    state = (java / "SageSpeechBackendState.java").read_text(encoding="utf-8")
    lab = (java / "SageSpeechLabActivity.java").read_text(encoding="utf-8")

    require(manager, 'MODEL_COMMIT = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16"', "immutable model commit")
    require(manager, 'TOTAL_BYTES = 45_202_074L', "four-file total")
    for name, size, sha in (
        ("tokens.txt", "5_048L", "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
        ("encoder-epoch-99-avg-1.int8.onnx", "42_845_182L", "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"),
        ("decoder-epoch-99-avg-1.onnx", "2_092_272L", "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30"),
        ("joiner-epoch-99-avg-1.int8.onnx", "259_572L", "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"),
    ):
        require(manager, name, name)
        require(manager, size, name + " byte size")
        require(manager, sha, name + " sha256")
    require(manager, 'setRequestProperty("Range", "bytes=" + existing + "-")', "resumable range request")
    require(manager, 'response == 206', "partial-content resume gate")
    require(manager, 'MessageDigest.getInstance("SHA-256")', "runtime hash verification")
    require(manager, 'STORAGE_HEADROOM = 64L * 1024L * 1024L', "storage headroom")
    require(manager, 'verified.properties', "atomic verified marker")
    require(manager, 'target.renameTo(backup)', "old-model atomic backup")
    require(manager, 'staging.renameTo(target)', "staged atomic activation")
    require(manager, 'License metadata: Apache-2.0', "model license notice")
    require(manager, 'Android STT remains the active command backend', "no premature route switch")
    require(state, 'exactFile(dir, "tokens.txt", 5_048L)', "runtime exact-size readiness")
    require(state, 'Verified model pack present:', "Speech Lab readiness wording")
    require(lab, 'Install local command speech (~45 MB)', "single install action")
    require(lab, 'Verify local command speech', "adaptive verify action")
    require(lab, 'Repair local command speech', "adaptive repair action")
    require(lab, 'Cancel speech model download', "temporary cancel action")
    if "/resolve/main/" in manager:
        raise SystemExit("model installer must not use a floating main branch URL")
    if "tar.bz2" in manager:
        raise SystemExit("tablet installer should fetch only the four required model files")
    print("Sage 1.31 verified sherpa model-pack regression passed")


if __name__ == "__main__":
    main()
