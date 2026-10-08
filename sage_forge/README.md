# Sage Forge 0.1 owner-controlled prototype

Requires Python 3.12+ and OpenSSL. No external Python package is required.

Private deployment values belong in environment variables or an ignored local configuration file. Copy `.env.example` to `.env`, replace every placeholder locally, and load those values into the service environment. Never commit the populated file.

```powershell
$env:SAGE_FORGE_HOST = "replace-with-owner-local-address-or-dns"
$env:SAGE_FORGE_BIND = "replace-with-owner-local-address"
$env:SAGE_FORGE_CERTIFICATE = "local/forge-certificate.pem"
$env:SAGE_FORGE_PRIVATE_KEY = "local/forge-private-key.pem"
$env:SAGE_FORGE_DATABASE = "local/forge.db"
python -m sage_forge.create_identity
python -m sage_forge --open-pairing 300
```

Forge prints its certificate SHA-256 and one-use pairing code only at runtime. In Sage Commander, open **Sage Workbench → Sage Forge**, enter the private endpoint configured locally (the public placeholder is `https://forge.example.invalid:8743`), the runtime certificate pin, and the runtime pairing code. Review the confirmation, then pair.

The enabled job is **Dell system information**. It reads platform/hostname/CPU/storage/local-address facts with Python standard-library APIs and executes no shell command. Commander shows progress and structured logs, stores the result, supports cancellation, and can revoke trust.

Run the service/security suite from the repository root:

```powershell
python -m unittest -v sage_forge.tests.test_forge
```

Keep the private key, certificate, database, populated `.env`, logs, and runtime pairing values out of source control. Firewall access, if needed, must be manually limited to the owner-authorized private network. Never expose the service through a router or public interface.
