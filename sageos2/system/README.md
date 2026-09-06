# SageOS 2 system layer

This directory is the privileged half of SageOS 2. It is intentionally separate from the Android application runtime.

## Components

- `rootd/`: tiny init-started root broker. It has no LLM, web client, speech stack, or behavioral policy.
- `sepolicy/private/`: Sage app/root-daemon domains, socket/data labels, and the package-to-domain mapping.
- `sageos2_product.mk`: product integration fragment for the eventual VASOUN Android source tree.

## Root trust path

1. The final Sage APK is platform-signed and installed as `com.pineapple.sagecommander.stable`.
2. `seapp_contexts` maps that exact platform package into `u:r:sage_app:s0`.
3. SELinux allows only `sage_app` to connect to `u:r:sage_rootd:s0` through `/dev/socket/sage_rootd`.
4. `sage-rootd` independently verifies both `SO_PEERCRED` and `SO_PEERSEC` before parsing a request.
5. Accepted operations receive an audit ID and a bounded audit record under `/data/misc/sage`.

A normal debug/sideloaded APK therefore cannot gain the `sage_app` domain and cannot become root merely by knowing the socket name.

## Protocol v1

Transport is a versioned line protocol. UTF-8 payload values are hex encoded, operations are typed, and process execution is executable + argv + environment + working directory + timeout. There is no concatenated `sh -c` command surface.

Protocol v1 intentionally rejects empty field/argv/environment values. That edge case is reserved for the next protocol revision rather than introducing ambiguous framing.

## Device-policy bring-up

The checked-in SELinux policy defines identities and the trust boundary. Concrete Binder/service-manager permissions for the VASOUN image are completed from real AVC traces during device-image bring-up. Do not replace that work with `permissive` or a broad app-domain exemption.
