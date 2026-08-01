"""Sage Forge local-service command line."""

from __future__ import annotations

import argparse
import ipaddress
import os
from pathlib import Path

from .security import PairingGrant, certificate_sha256, new_pairing_code
from .server import ForgeApplication, SageForgeServer
from .store import ForgeStore


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the owner-controlled Sage Forge service")
    parser.add_argument("--bind", default=os.environ.get("SAGE_FORGE_BIND", "127.0.0.1"),
                        help="exact loopback or private LAN address to bind")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("SAGE_FORGE_PORT", "8743")))
    parser.add_argument("--database", type=Path,
                        default=Path(os.environ.get("SAGE_FORGE_DATABASE", "sage-forge.db")))
    parser.add_argument("--certificate", type=Path,
                        default=Path(os.environ.get("SAGE_FORGE_CERTIFICATE", "sage-forge-cert.pem")))
    parser.add_argument("--private-key", type=Path,
                        default=Path(os.environ.get("SAGE_FORGE_PRIVATE_KEY", "sage-forge-key.pem")))
    parser.add_argument("--open-pairing", type=int, metavar="SECONDS", default=0,
                        help="open one explicit one-use pairing window (30-900 seconds)")
    args = parser.parse_args()
    if not (1 <= args.port <= 65535):
        parser.error("port must be between 1 and 65535")
    try:
        bind_address = ipaddress.ip_address(args.bind)
    except ValueError:
        parser.error("bind must be an exact IP address")
    if not (bind_address.is_private or bind_address.is_loopback):
        parser.error("bind must be loopback or a private local-network address")
    code = None
    grant = None
    if args.open_pairing:
        code = new_pairing_code()
        grant = PairingGrant.create(code, args.open_pairing)
    store = ForgeStore(args.database)
    application = ForgeApplication(store, grant)
    server = SageForgeServer((args.bind, args.port), application,
                             args.certificate, args.private_key)
    print("Sage Forge 0.1.0")
    print(f"Listening with TLS on {args.bind}:{args.port}")
    print(f"Certificate SHA-256: {certificate_sha256(args.certificate)}")
    if code:
        print(f"ONE-TIME PAIRING CODE ({args.open_pairing}s): {code}")
    else:
        print("Pairing is closed. Restart with --open-pairing SECONDS to approve one tablet.")
    print("Only locally registered tools can execute. Press Ctrl+C to stop.")
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        application.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
