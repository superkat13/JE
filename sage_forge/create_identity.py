"""Generate a local TLS identity with a hostname/IP SAN using fixed OpenSSL arguments."""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import shutil
import subprocess
from pathlib import Path

from .security import certificate_sha256


def validated_subject_alt_name(host: str) -> str:
    try:
        return f"IP:{ipaddress.ip_address(host)}"
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?", host):
            raise ValueError("host must be an IP address or valid DNS name")
        return f"DNS:{host}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Create the owner's Sage Forge TLS identity")
    parser.add_argument("--host", default=os.environ.get("SAGE_FORGE_HOST"),
                        help="exact private IP or DNS name used by the tablet")
    parser.add_argument("--certificate", type=Path,
                        default=Path(os.environ.get("SAGE_FORGE_CERTIFICATE", "sage-forge-cert.pem")))
    parser.add_argument("--private-key", type=Path,
                        default=Path(os.environ.get("SAGE_FORGE_PRIVATE_KEY", "sage-forge-key.pem")))
    args = parser.parse_args()
    if not args.host:
        parser.error("host is required through --host or SAGE_FORGE_HOST")
    if args.certificate.exists() or args.private_key.exists():
        parser.error("refusing to overwrite an existing identity")
    if shutil.which("openssl") is None:
        parser.error("OpenSSL is required to create the initial local identity")
    try:
        san = validated_subject_alt_name(args.host)
    except ValueError as error:
        parser.error(str(error))
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes",
        "-keyout", str(args.private_key), "-out", str(args.certificate), "-days", "825",
        "-subj", "/CN=Sage Forge", "-addext", f"subjectAltName={san}",
        "-addext", "keyUsage=digitalSignature,keyEncipherment",
        "-addext", "extendedKeyUsage=serverAuth",
    ], check=True)
    try:
        os.chmod(args.private_key, 0o600)
    except OSError:
        pass
    print(f"Certificate: {args.certificate}")
    print(f"Private key: {args.private_key} (keep private on the Dell)")
    print(f"Certificate SHA-256: {certificate_sha256(args.certificate)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
