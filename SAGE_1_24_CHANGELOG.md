# Sage Commander 1.24

## Completed

- Preserves the permanent package identity and signing path.
- Advances the update identity to version 1.24 / versionCode 34.
- Renames the diagnostics section to **Sage Diagnostics and Continuity**.
- Includes saved Custom Wake Profiles in the shareable continuity report.
- States Sage's continuity invariant directly in the exported report.
- Runs the complete Sage 1.21, 1.22, and 1.23 regression suite.
- Reconstructs the source independently a second time and compares the resulting trees.
- Verifies ZIP integrity, 16 KiB alignment, signing certificate, package identity, and version twice after APK assembly.

## Preserved

- Existing settings and app data
- Sage Brain and its watchdog
- Custom Wake Profiles
- Complete-command recognition
- Red Queen mode
- Translation, saved lessons, media responses, and tablet controls

## Rollback

Sage Commander 1.23 remains the known-good previous release. Do not uninstall Sage before updating, because uninstalling removes Android app data. The 1.24 APK must match the permanent signing certificate before installation.
