from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def tracked_paths():
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached"], cwd=ROOT, check=True, capture_output=True
    ).stdout.decode("utf-8").split("\0")
    values = {value for value in result if value}
    values.update({
        "sage_forge/.env.example",
        "sage_forge/.gitignore",
        "sage_tests/test_public_disclosure_v1_27.py",
    })
    return [ROOT / value for value in sorted(values)]


paths = tracked_paths()
text_paths = [path for path in paths if path.is_file() and path.name != "android-debug.keystore.b64"]
texts = {}
for path in text_paths:
    try:
        texts[path] = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue

joined = "\n".join(texts.values())

forbidden_secret_patterns = {
    "private key material": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "GitHub token": r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b",
    "AWS access key": r"\bAKIA[0-9A-Z]{16}\b",
    "literal bearer credential": r"\bBearer [A-Za-z0-9._-]{16,}\b",
    "literal Forge credential": r"\bSageToken [A-Za-z0-9._-]{12,}\b",
    "published certificate fingerprint": r"(?i)(?:certificate|signer|fingerprint)[^\n]{0,100}\b[0-9a-f]{64}\b",
    "concrete device id": r"\bdevice_[0-9a-f]{16,}\b",
}
for label, pattern in forbidden_secret_patterns.items():
    assert re.search(pattern, joined) is None, f"public disclosure scan found {label}"

for path, value in texts.items():
    for address in re.findall(r"\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b", value):
        assert address == "127.0.0.1", f"non-placeholder network address in {path}: {address}"

private_suffixes = {".pem", ".key", ".crt", ".cer", ".p12", ".pfx", ".db", ".log"}
for path in paths:
    relative = path.relative_to(ROOT)
    if relative.parts and relative.parts[0] == "sage_forge":
        assert path.suffix.lower() not in private_suffixes, f"tracked private deployment file: {relative}"
        assert path.name != ".env", f"tracked populated environment file: {relative}"

forge_ignore = (ROOT / "sage_forge/.gitignore").read_text()
for marker in (".env", "local/", "*.pem", "*.key", "*.db", "*.log"):
    assert marker in forge_ignore, f"Forge ignore policy missing {marker}"

template = (ROOT / "sage_forge/.env.example").read_text()
for marker in ("forge.example.invalid", "replace-with-owner-local-address", "local/forge-private-key.pem"):
    assert marker in template, f"sanitized Forge template missing {marker}"

print("Sage 1.27 public disclosure scan passed")
