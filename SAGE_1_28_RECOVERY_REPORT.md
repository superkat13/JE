# Sage Commander 1.28.0 recovery checkpoint

Recovered source: `agent/sage-1-27-unified-20260801` at
`3fa2ffe37f093de6567b44ae000c77930fe6efbc`.

## Recovery evidence

- The newest completed 1.27 source is preserved as a deterministic patch chain, not only as an APK.
- The chain reconstructs Sage 1.20, applies every completed 1.21–1.27 slice, and passes the inherited host regression suite.
- Branches created later than the 1.27 checkpoint contain 1.26 signing/install-verification workflows only. They do not contain newer Sage feature source.
- Pull requests 1–15 and issues 16–18 were inspected before 1.28 changes.
- The permanent package is `com.pineapple.sagecommander.stable`.
- The required signer certificate SHA-256 is
  `99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35`.

## First 1.28 slice

- Advance to `1.28.0` / `versionCode 40`.
- Preserve the permanent release signing configuration.
- Fail reconstruction if the manifest is test-only, debuggable, or changes shared-user identity.
- Build the first production recovery APK before additional feature work.

Physical Android package-installer testing remains a tablet acceptance check until an owner device is connected. It must not be represented as completed by an ADB-only emulator test.
