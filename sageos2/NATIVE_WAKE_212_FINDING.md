# Build 212 native wake failure

The Android 13 physical report shows four wake-process disconnects at startup,
then recovery pauses. These occur before any Brain request. The report alone
does not include the Android native crash stack.

Independent reproduction: extract the wake assets from the signed 212 APK
(SHA-256 e9eb05b41df5218c1fbf71e7558470b262f91d8ea2d29e89eaf499184a8083e2),
load them in sherpa-onnx 1.13.7 on Linux, create the Sage/Sage Glitch keyword
stream, and feed 16 kHz silence. Native decoding fails with:

    /downsample/Reshape_1
    Input shape:{17,1,128}, requested shape:{8,2,1,128}

The pinned mobile model archive has a reproducible inference failure. The
standard export of the same 3.3M model, from the same upstream release, passes.
Its SHA-256 f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
matches the upstream checksum file. This changes the wake model export only;
it does not replace the private Brain GGUF or identity data.

The new native test covers three streams, silence, reset, and positive detection
of a known recorded phrase from the upstream test fixture. Both verification and
signed-release workflows must execute it. Tests of mocked service callbacks did
not exercise native inference; 202 passing JVM tests did not establish wake health.

Local result: old APK assets FAIL; standard export PASS, including spoken phrase.
Android JNI, actual microphone and physical background reliability remain pending.
This is a strong candidate explanation for the process deaths, not a recovered
Android tombstone proving their precise cause.

Separate unresolved finding: the device spends about 64 seconds prefilling 424
Brain prompt tokens and about 8 seconds generating 24 tokens. Wake repair does
not establish a fix for this delay. Core revision remains 0; no identity is seeded.

The speech diagnostic `chars=0` was a literal, not an actual transcript length.
Replace it with `nonempty=true`, consistent with the branch that emits it and
without disclosing conversation text.
