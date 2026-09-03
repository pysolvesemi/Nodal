#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
pr=111
placeholder=da785a1fbb0d8381a83d9100e2318c553102efc8

pr_json() {
  gh api "repos/${repo}/pulls/${pr}"
}

current_state() {
  pr_json > "${report}/pr.json"
  jq -r '.state + ":" + (.merged|tostring) + ":" + (.draft|tostring) + ":" + .head.sha' \
    "${report}/pr.json"
}

state=$(current_state)
IFS=: read -r pr_state merged draft head <<< "${state}"

if [[ ${merged} != true && ( ${head} == "${placeholder}" || ${draft} == true ) ]]; then
  echo "Closure PR is not published yet; waiting for the v3 materializer."
  for _ in $(seq 1 120); do
    runs=$(gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100")
    v3=$(jq -r '
      [.workflow_runs[] | select(.name == "Increment 34 Evidence Closure v3")]
      | sort_by(.id) | reverse | .[0]
      | if . == null then "missing" else (.status + ":" + (.conclusion // "")) end
    ' <<< "${runs}")
    state=$(current_state)
    IFS=: read -r pr_state merged draft head <<< "${state}"
    if [[ ${merged} == true || ( ${head} != "${placeholder}" && ${draft} == false ) ]]; then
      break
    fi
    case "${v3}" in
      queued:*|in_progress:*|waiting:*|pending:*|requested:*) sleep 20 ;;
      *) break ;;
    esac
  done

  state=$(current_state)
  IFS=: read -r pr_state merged draft head <<< "${state}"
  if [[ ${merged} != true && ( ${head} == "${placeholder}" || ${draft} == true ) ]]; then
    echo "Running one guarded v3 materializer recovery."
    rm -rf "${report}/materializer-work"
    git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
      "${report}/materializer-work"
    git -C "${report}/materializer-work" checkout \
      increment/34-evidence-closure-v1
    bash "${controller}/.github/automation/inc34_run_evidence_closure_v3.sh" \
      "${controller}" \
      "${report}/materializer-work" \
      "${report}/materializer"
  fi
fi

state=$(current_state)
IFS=: read -r pr_state merged draft head <<< "${state}"

if [[ ${merged} != true ]]; then
  echo "Closure PR is open at ${head}; running the exact-head finalizer."
  set +e
  bash "${controller}/.github/automation/inc34_finalize_closure_v1.sh" \
    "${controller}" "${report}/open-finalizer"
  rc=$?
  set -e
  if [[ ${rc} -eq 0 ]]; then
    exit 0
  fi
  state=$(current_state)
  IFS=: read -r pr_state merged draft head <<< "${state}"
  if [[ ${merged} != true ]]; then
    echo "Open-state finalizer failed and PR #111 is not merged." >&2
    exit ${rc}
  fi
  echo "Another lease-protected finalizer merged PR #111; continuing with final audit."
fi

merge_sha=$(jq -r '.merge_commit_sha' "${report}/pr.json")
if [[ -z ${merge_sha} || ${merge_sha} == null ]]; then
  state=$(current_state)
  merge_sha=$(jq -r '.merge_commit_sha' "${report}/pr.json")
fi

dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
[[ ${dev_head} == "${merge_sha}" ]] || {
  echo "Increment 34 closure merge ${merge_sha} is not the current dev head ${dev_head}." >&2
  exit 1
}

gh api "repos/${repo}/contents/tests/compiler/fixtures/increment34/manifest.json?ref=${merge_sha}" \
  --jq '.content' | tr -d '\n' | base64 -d > "${report}/manifest.json"
gh api "repos/${repo}/contents/docs/roadmap/nodal-development-todo.md?ref=${merge_sha}" \
  --jq '.content' | tr -d '\n' | base64 -d > "${report}/roadmap.md"
gh api "repos/${repo}/contents/docs/implementation/increment34-evidence-closure.md?ref=${merge_sha}" \
  --jq '.content' | tr -d '\n' | base64 -d > "${report}/evidence.md"

python3 - "${report}/manifest.json" "${report}/roadmap.md" "${report}/evidence.md" <<'PY'
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
roadmap = Path(sys.argv[2]).read_text(encoding="utf-8")
evidence = Path(sys.argv[3]).read_text(encoding="utf-8")
validation = manifest.get("validation")
if manifest.get("status") != "validated-analog-control-flow":
    raise SystemExit("final manifest status is not validated-analog-control-flow")
if manifest.get("tranche") != "34d-closure":
    raise SystemExit("final manifest tranche is not 34d-closure")
if not isinstance(validation, dict):
    raise SystemExit("final manifest validation object is absent")
expected = {
    "implementation_pull_request": 109,
    "accepted_head": "207fd1b580e9428e9948cd4e4bd8f2060fde4b79",
    "implementation_merge": "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49",
    "post_merge_core_ci_run": 33758905273,
    "exact_post_merge_validation_run": 33759112770,
    "closure_pull_request": 111,
}
for key, value in expected.items():
    if validation.get(key) != value:
        raise SystemExit(f"final evidence mismatch: {key}")
if not re.fullmatch(r"[0-9a-f]{40}", str(validation.get("closure_validation_head", ""))):
    raise SystemExit("final closure validation head is invalid")
if not isinstance(validation.get("closure_validation_run"), int) or validation["closure_validation_run"] <= 0:
    raise SystemExit("final closure validation run is invalid")
if "**Revision:** 1.45" not in roadmap:
    raise SystemExit("final roadmap revision is not 1.45")
if "- [x] **Increment 34 — Analog control flow**" not in roadmap:
    raise SystemExit("Increment 34 is not checked on final roadmap")
if "- [ ] **Increment 34 — Analog control flow**" in roadmap:
    raise SystemExit("an open duplicate Increment 34 entry remains")
for token in (
    "**Status:** Validated evidence closure",
    "**Implementation PR:** #109",
    "**Closure PR:** #111",
):
    if token not in evidence:
        raise SystemExit(f"final evidence document is missing {token!r}")
PY

postclosure_run=
for _ in $(seq 1 270); do
  runs=$(gh api "repos/${repo}/actions/runs?head_sha=${merge_sha}&per_page=100")
  postclosure_run=$(jq -r '
    [.workflow_runs[] | select(.name == "Core CI")]
    | sort_by(.id) | reverse | .[0]
    | if . == null then "" else (.id|tostring) + ":" + .status + ":" + (.conclusion // "") end
  ' <<< "${runs}")
  if [[ -n ${postclosure_run} ]]; then
    IFS=: read -r core_id core_status core_conclusion <<< "${postclosure_run}"
    if [[ ${core_status} == completed ]]; then
      [[ ${core_conclusion} == success ]] || {
        echo "Post-closure Core CI failed: ${postclosure_run}" >&2
        exit 1
      }
      break
    fi
  fi
  sleep 20
done
[[ -n ${postclosure_run} ]] || {
  echo "No post-closure Core CI was observed." >&2
  exit 1
}
IFS=: read -r core_id core_status core_conclusion <<< "${postclosure_run}"
[[ ${core_status} == completed && ${core_conclusion} == success ]] || {
  echo "Post-closure Core CI did not settle successfully." >&2
  exit 1
}

rm -rf "${report}/final-dev"
git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
  "${report}/final-dev"
git -C "${report}/final-dev" checkout --detach "${merge_sha}"
(
  cd "${report}/final-dev"
  python3 scripts/check_increment32.py
  python3 scripts/check_increment33.py
  python3 scripts/check_increment34.py
  python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'
  python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'
  python3 -m unittest discover -s tests/compiler -p 'test_increment34.py'
  python3 -m unittest discover -s tests/compiler -p 'test_*.py'
  ./nodal bootstrap --mode prebuilt --prefix "${RUNNER_TEMP}/inc34-v4-native"
  ./nodal style bootstrap --prefix "${RUNNER_TEMP}/inc34-v4-lint"
  ./nodal core scala
  ./nodal core native \
    --toolchain "${RUNNER_TEMP}/inc34-v4-native" \
    --lint-toolchain "${RUNNER_TEMP}/inc34-v4-lint"
  ./nodal style check --lint-toolchain "${RUNNER_TEMP}/inc34-v4-lint"
  runtime="${RUNNER_TEMP}/inc34-v4-runtime.txt"
  ./mill -i examples.continuousTimeApi.runMain \
    nodal.increment34fixture.Increment34RuntimeCheck "${runtime}"
  grep -F 'forward_reference=NODAL-ANALOG-034-014' "${runtime}"
  construction="${RUNNER_TEMP}/inc34-v4-construction.txt"
  ./mill -i examples.continuousTimeApi.runMain \
    nodal.increment34fixture.Increment34ConstructionCheck "${construction}"
  grep -F 'precontrol_scope_aligned=true' "${construction}"
  test -z "$(git status --porcelain)"
  git diff --check
) > "${report}/final-validation.log" 2>&1 || {
  tail -n 500 "${report}/final-validation.log" >&2 || true
  exit 1
}

cat > "${report}/README.md" <<EOF
# Increment 34 idempotent final closure audit

- Implementation PR: #109
- Accepted implementation head: \`207fd1b580e9428e9948cd4e4bd8f2060fde4b79\`
- Implementation merge: \`a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49\`
- Evidence closure PR: #111
- Evidence closure merge: \`${merge_sha}\`
- Post-closure Core CI: \`${core_id}\`
- Final audit run: \`${GITHUB_RUN_ID}\`

The exact final \`dev\` commit passed Increment 32–34 contracts and mutation
suites, complete compiler mutation discovery, Scala and native core validation,
repository style, both Increment 34 witnesses, and whitespace checks.

Increment 34 is fully closed. Solver execution, target legalization, and
Verilog-A/Verilog-AMS procedural lowering remain owned by later increments.
EOF

result_branch=audit/increment34-final-closure-results-v2
rm -rf "${report}/result"
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
  "${report}/result"
git -C "${report}/result" checkout --orphan result-root
git -C "${report}/result" rm -rf . >/dev/null 2>&1 || true
cp -R "${report}/README.md" "${report}/manifest.json" \
  "${report}/roadmap.md" "${report}/evidence.md" \
  "${report}/final-validation.log" "${report}/result/"
git -C "${report}/result" add -A
git -C "${report}/result" config user.name github-actions[bot]
git -C "${report}/result" config user.email \
  41898282+github-actions[bot]@users.noreply.github.com
git -C "${report}/result" commit -m \
  "ci(increment34): record idempotent final audit ${GITHUB_RUN_ID}"
git -C "${report}/result" push --force origin HEAD:"${result_branch}"

gh pr comment 111 --repo "${repo}" \
  --body "Increment 34 final closure audit passed on \`${merge_sha}\`. Post-closure Core CI: \`${core_id}\`; audit run: \`${GITHUB_RUN_ID}\`."
