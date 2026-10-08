# Sage supervised self-repair architecture

## Release boundary

Sage 1.25.0 diagnoses locally, performs only allowlisted reversible configuration normalization after an explicit owner command, prepares a private repair draft, displays its contents, and exports only after the owner taps approval. It does not rewrite, compile, install, provision, merge, or release code on the tablet.

## Components

- `SageRepairManager`: sanitizes diagnostics, classifies the problem, builds JSON/Markdown/log files, and packages them into a ZIP in private cache.
- `SageRepairActivity`: review surface and explicit export approval gate.
- `SageAuthority`: verified Android capability-state inspection. It never treats availability as activation.
- `SageAuthorityActivity`: current-style authority dashboard with manual Android setup links.
- `repair-packet.schema.json`: repository, branch, identity, classification, size, and operation constraints.
- `sage-approved-repair.yml`: manually dispatched, schema-constrained candidate pipeline. It builds on an isolated local branch and uploads artifacts only.

## Data flow

1. Owner says “Sage, diagnose yourself,” taps Diagnose, or says “Sage, diagnose and prepare a fix.”
2. Sage snapshots package/signing/device/authority/Brain/wake state and sanitized lifecycle diagnostics.
3. Sage separates observations (`confirmed_evidence`) from hypotheses (`theories`).
4. Sage classifies the issue as configuration, permission, transient runtime, or likely code defect.
5. The prepare-fix command may normalize only the allowlisted overlay-timeout preference. Every action is logged.
6. Sage writes a private draft ZIP and renders its Markdown for review.
7. The owner may edit reproduction steps and refresh the private draft.
8. Only **Approve and export repair bundle** launches Android's share sheet.
9. A repository owner may manually supply the approved JSON packet to the workflow.
10. The workflow validates all constraints, builds and tests a candidate, and uploads it as a workflow artifact. It never pushes or merges the repair branch.

## Reversibility and review

- The only in-app configuration repair in 1.25.0 is resetting an unsupported overlay-timeout value to the documented 60-second default.
- Export is a standard Android owner-mediated share action.
- Repository changes remain isolated and require ordinary human review.
- Candidate APK installation remains an explicit Android installation action using the permanent Sage signing identity.

## Future supervised stages

Later releases may add signed repair-plan responses, strict patch-path allowlists, local before/after preference snapshots, and owner-approved candidate installation. Those are intentionally outside 1.25.0.
