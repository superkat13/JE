# Sage Toolbelt usage guide

Open **Workbench → Sage Toolbelt**. Each tool states its purpose, instructions, required permission, example voice commands, progress, cancellation where applicable, and actionable errors.

## Package Inspector

Choose a local APK. The report shows package name, version, minimum Android version, requested permissions, signer identity, certificate SHA-256, file SHA-256, and installed-versus-candidate comparison. **Install candidate** appears only after inspection and explicit owner approval; it hands the file to Android's package installer and never bypasses Android confirmation.

Example: “Sage, open Package Inspector.”

## QR Scanner

Start a deliberate scan. Decoded content is displayed before any action. URLs remain inert until **Open confirmed link** is pressed. Scanning uses Google Code Scanner delivered through compatible Google Play services; devices without a compatible scanner receive a real error instead of a placeholder result.

Example: “Sage, scan a QR code.”

## File Hasher

Choose a readable local file and calculate SHA-256. The tool reports exact file size, supports cancel while hashing, and enables copy/share after success.

Example: “Sage, hash this file.”

## Network Snapshot

Confirm ownership before starting. Sage discovers only the tablet's current RFC1918 local subnet, caps work to `/24` (254 candidate addresses), uses a small worker pool and conservative reachability timeout, and never scans public ranges or ports. Cancel stops pending discovery. Saved snapshots record IP, hostname where resolvable, reachability, response time, first seen, and last seen; comparison identifies new and missing devices.

Example: “Sage, take a local network snapshot.”

## Media Inspector

The tool shows the active media application, title where available, playback state, and supported MediaSession actions. Play, pause, next, and previous use direct MediaSession APIs; accessibility is retained only as a fallback elsewhere in Sage.

Example: “Sage, inspect active media.”

Notification-listener/MediaSession access may need to be enabled in Android settings before sessions are visible.

## Voice Command Tester

Press **Record deliberate test phrase** and speak once. The tool displays raw candidates, selected final, confidence when the recognizer supplies it, normalization, matched command, route, rejection reason, and latency. It never executes the result automatically. Review it, then press **Execute selected final** only if intended.

Example: “Sage, open Voice Command Tester.”
