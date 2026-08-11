#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

printf 'Sage agent preflight\n'
printf 'Repo: %s\n' "$ROOT"
printf 'Branch: %s\n' "$(git branch --show-current)"
printf 'Commit: %s\n' "$(git rev-parse HEAD)"

if [ -n "$(git status --porcelain)" ]; then
  echo 'FAIL: working tree is not clean.' >&2
  git status --short >&2
  exit 1
fi

echo 'Working tree: clean'

test -f SAGE_AGENT_OPERATING_CONTRACT.md || { echo 'FAIL: missing SAGE_AGENT_OPERATING_CONTRACT.md' >&2; exit 1; }
test -f sage_tools/reconstruct_v1_29.sh || { echo 'FAIL: missing 1.29 reconstruction script' >&2; exit 1; }

grep -Rqs 'com.pineapple.sagecommander.stable' sage_patches sage_tools .github/workflows || {
  echo 'FAIL: stable package identity not found in preserved source metadata.' >&2
  exit 1
}

grep -Rqs '99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35' .github/workflows sage_signing || {
  echo 'FAIL: permanent signer fingerprint not found in release gates.' >&2
  exit 1
}

echo 'Package identity continuity marker: PASS'
echo 'Permanent signer continuity marker: PASS'
echo
printf 'Agent task files:\n'
printf '  %s\n' 'SAGE_AGENT_TASKS.md'
printf '  %s\n' 'SAGE_AGENT_OPERATING_CONTRACT.md'
echo
printf 'Preflight PASS. Create an isolated worktree/branch before allowing edits.\n'
