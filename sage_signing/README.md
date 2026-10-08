# Sage signing policy

Private signing keys do not belong in this repository. Release workflows restore
the legacy and current keystores only from encrypted GitHub Actions secrets.

Sage 1.33.3 introduces an Android 13 proof-of-rotation lineage from the exposed
prototype certificate to the private release certificate. The legacy signer is
retained only for upgrade compatibility with already-installed Sage builds and
older Android versions.

Certificate SHA-256 fingerprints are public verification data:

- Legacy prototype: `99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35`
- Sage release 2026: `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`

The old private key remains recoverable from Git history and must be treated as
compromised. Do not use it as the current signer for Android 13 or newer.
