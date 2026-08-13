# MARKER #003 — Sage Autonomy / Self-Repair Pivot

Base: `ccf60ae279b49d9273e701c3c018f97ea95633ab`
Branch: `sage-autonomy-self-repair-v1`

## Mission

Stop growing Sage primarily as a collection of screens and buttons. Make Sage the persistent coordinator of her own work.

The target loop is:

`observe -> diagnose -> reproduce -> plan -> delegate -> execute in isolation -> test -> compare -> explain -> owner approval when consequential -> install -> verify -> rollback if verification fails`

Sage owns the job. Forge, local Brain, developer agents, GitHub, and future providers are delegates.

## Non-negotiable continuity

Preserve the existing package identity, permanent signer, app data, saved memories, wake profiles, conversation mode, Brain/model files, wake assets, Workbench, Toolbelt, accessibility controls, Forge pairing/trust, Shizuku authority bridge, Red Queen trigger, and every currently working capability.

Do not rebuild working subsystems merely to fit the new architecture.

## First vertical slice

The first autonomy milestone is deliberately narrow and measurable:

1. Sage records a durable job with a goal, evidence, state, checkpoint, next action, and history.
2. A diagnostic observation can open or update a repair job.
3. Sage creates a structured repair packet from her own evidence rather than dumping an unbounded log.
4. Sage chooses a delegate by capability rather than hard-coding ChatGPT, Codex, or Forge as the boss.
5. The delegate works in an isolated branch/workspace.
6. Sage records progress and can resume after app/process/device interruption.
7. Tests and verification evidence are attached to the job.
8. A candidate may be prepared automatically, but consequential install/permission/destructive boundaries remain explicit owner decisions.
9. After install, Sage verifies the original symptom. Failed verification rolls back to the previous checkpoint/candidate where technically available.

## Durable job model

Each job must have at minimum:

- stable job ID
- owner goal
- normalized problem statement
- creation/update timestamps
- current state
- priority
- evidence references
- hypotheses with confidence/status
- selected plan
- delegate/capability requirements
- current checkpoint
- next unblocked action
- attempts and outcomes
- produced artifacts
- verification requirements/results
- owner approvals still required
- terminal result: solved / blocked / cancelled / rolled_back

Suggested states:

`OBSERVING, DIAGNOSING, PLANNED, WAITING_DEPENDENCY, READY_TO_DELEGATE, DELEGATED, EXECUTING, VERIFYING, READY_FOR_OWNER, INSTALLING, POST_VERIFY, SOLVED, BLOCKED, ROLLED_BACK, CANCELLED`

State transitions must be explicit and logged. Reopening Sage must restore the job graph rather than reconstructing intent from UI state.

## Delegation contract

A delegate is selected by declared capability. Initial capability families:

- `brain.local.reason`
- `forge.inspect`
- `forge.test`
- `forge.build`
- `developer.code_change`
- `developer.review`
- `github.branch`
- `github.ci`
- `tablet.verify`
- `owner.physical_test`

A delegate request must contain a bounded goal, evidence, constraints, allowed workspace/scope, expected artifacts, and verification criteria. It must not silently widen its own authority.

## Red Queen purpose

Standard Sage operates Sage and completes ordinary user goals.

Red Queen is Sage's owner-authorized engineering room. It may expose self-repair jobs, experiments, candidate comparison, authority-dependent engineering operations, checkpoint/rollback controls, and private research surfaces that are not duplicated in ordinary Tools.

Acceptance rule: an advanced Red Queen capability duplicated in normal Tools is an architecture regression.

## First physical proof

Use a real observed defect from the current installed build as the first end-to-end proof:

A recognized knowledge/conversation request can report a `tablet Brain` route but skip the expected Brain execution/result path. The autonomy system should be able to ingest the relevant diagnostic evidence, create a repair job, commission an isolated investigation, attach tests/build evidence, and return a candidate for physical verification.

Do not claim the symptom is fixed until the tablet reproducer passes after installation.

## Friction guards

- Fewer surfaces, deeper agency.
- No generic arbitrary-shell interface as the orchestration API.
- No silent self-install of consequential updates.
- No self-modification of the verification policy in the same repair transaction being verified.
- No deleting history to make a failed repair appear successful.
- Do not turn every diagnostic warning into a repair job; deduplicate and require meaningful evidence.
- Do not loop indefinitely. Attempts have budgets and must end in solved, blocked, owner-needed, or rollback.

## Success criterion

Sage detects or accepts one real defect, owns the durable repair job, delegates the engineering work, survives interruption, verifies the resulting candidate through automated gates, asks for the minimum necessary physical/owner boundary, and then verifies whether the original defect is actually gone.
