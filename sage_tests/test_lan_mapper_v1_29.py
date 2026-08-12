#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def reconstruct(tmp: Path) -> Path:
    out = tmp / "sage"
    subprocess.run(["bash", str(ROOT / "sage_tools/reconstruct_v1_29.sh"), str(out)], cwd=ROOT, check=True)
    return out


def test_lan_mapper_is_bounded_read_only_and_integrated():
    with tempfile.TemporaryDirectory() as td:
        out = reconstruct(Path(td))
        java = out / "app/src/main/java/com/pineapple/sage"
        mapper = (java / "SageLanMapperActivity.java").read_text(encoding="utf-8")
        host = (java / "SageHostInspectorActivity.java").read_text(encoding="utf-8")
        manifest = (out / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        reconstruct_script = (ROOT / "sage_tools/reconstruct_v1_29.sh").read_text(encoding="utf-8")

        assert "SageNetworkStore.current(this)" in mapper
        assert "SageNetworkScanner.isPrivate" in mapper
        assert "SageNetworkActivity.class" in mapper
        assert "SageHostInspectorActivity.class" in mapper
        assert 'putExtra("selected_private_ip", ip)' in mapper
        assert 'getStringExtra("selected_private_ip")' in host
        assert '.SageLanMapperActivity' in manifest
        assert "lan_mapper_v1_29.py" in reconstruct_script

        forbidden = [
            "Runtime.getRuntime().exec",
            "ProcessBuilder",
            "nmap",
            "masscan",
            "all-address cidr",
            "su -c",
            "adb shell",
            "exploit",
            "credential",
            "bruteforce",
            "brute force",
        ]
        lower = mapper.lower()
        for token in forbidden:
            assert token.lower() not in lower

        # The mapper itself must not create raw network sockets. Discovery and deep host checks
        # remain delegated to the already-reviewed existing Sage network surfaces.
        assert "new Socket" not in mapper
        assert "InetAddress" not in mapper
        assert "HttpURLConnection" not in mapper


def test_identity_and_core_voice_state_machine_are_not_replaced():
    with tempfile.TemporaryDirectory() as td:
        out = reconstruct(Path(td))
        manifest = (out / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        voice = (out / "app/src/main/java/com/pineapple/sage/SageVoiceService.java").read_text(encoding="utf-8")
        assert "com.pineapple.sagecommander.stable" in manifest
        for state in ["IDLE_WAKE", "WAKE_ACCEPTED", "COMMAND_LISTENING", "DISPATCHING", "SPEAKING"]:
            assert state in voice
