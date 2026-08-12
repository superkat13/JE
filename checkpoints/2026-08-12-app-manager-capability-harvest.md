# Sage App Manager Capability Harvest Checkpoint

Date: 2026-08-12
Base: eb060a04662fbb495d4702b8d3af5a01ddfdbd26
Branch: sage-app-manager-capability-harvest-20260812

## Gate inherited from Shizuku pass

The base commit passed the complete Sage 1.29 reconstruction, inherited regression suite, deterministic reconstruction proof, compile/test/lint twice, signer verification, Shizuku APK assembly verification, and artifact upload. This branch begins only after that gate.

## Source examined

Upstream reference: MuntashirAkon/AppManager, master branch.

Upstream advertises broad package inspection and management capabilities including component enumeration, app ops, permissions, signatures, shared libraries, manifest viewing, tracker/library scanning, APK installation and backup, logcat, package signing, and root/ADB operations such as permission/app-op changes, process control, clear-data/cache, network policy, battery optimization, and freeze/unfreeze.

License boundary: upstream declares GPL-3.0-or-later and notes additional licenses may apply. For this Sage harvest, use App Manager as a capability and UX reference unless a later change explicitly documents copied/adapted code and all corresponding license obligations. Do not silently paste GPL implementation into Sage.

## First harvest target: Package Inspector Plus

Keep this isolated from the proven Shizuku branch. The first implementation target should be read-mostly and low consequence:

1. Package identity snapshot
   - package name
   - label
   - version name/code
   - UID
   - min/target SDK
   - install source when available
   - APK paths and sizes

2. Signing snapshot
   - current signer certificate digest(s)
   - signer history where Android exposes it
   - file SHA-256 for selected APK
   - explicit comparison against Sage permanent signer when inspecting Sage itself

3. Manifest/component inventory
   - activities
   - services
   - receivers
   - providers
   - exported state
   - required permission per component when exposed by PackageManager

4. Permission/app-op snapshot
   - declared permissions
   - granted runtime permissions
   - requested permission flags
   - app-op state through the already proven bounded Shizuku bridge where required

5. Safety boundary
   - inspection is allowed without mutation
   - any future force-stop/freeze/permission/app-op mutation stays Red Queen only
   - mutation must remain typed/bounded and require an explicit owner consequence decision
   - protected packages remain protected

## Deliberately deferred

Do not copy App Manager terminal, root file editing, arbitrary shell, shared-preference editing, system configuration mutation, debloating, package installation/uninstallation, or backup/restore logic into this pass. Those are separate capability branches with separate consequence reviews.

## Success gate for this branch

Before this branch is considered a candidate:

- no regression to conversation/wake/Brain/Red Queen systems
- deterministic reconstruction remains intact
- package identity and signer tests cover Sage itself and at least one non-Sage package fixture
- component enumeration is read-only
- app-op reads use the typed Shizuku authority surface rather than generic shell text
- permanent Sage package id and signing identity remain unchanged
- compile/test/lint must pass twice before an APK artifact is accepted

## Next implementation slice

Build the Package Inspector Plus data model and read-only collector first. No UI redesign and no package mutation in the same commit. This keeps the blast radius small and gives Sage an immediately useful diagnostic upgrade without gambling the proven 1.29 voice stack.
