#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
pr=111
placeholder_head=da785a1fbb0d8381a83d9100e2318c553102efc8
implementation_merge=a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49

status=${report}/status.tsv
: > "${status}"

record() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "${status}"
}

fail() {
  record "$1" FAIL 1
  echo "$2" >&2
  exit 1
}

pass() {
  record "$1" PASS 0
  echo "$1: PASS"
}

wait_for_closure_head() {
  local deadline=$((SECONDS + 7200))
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/pulls/${pr}" > "${report}/pr-live.json"
    local state draft merged head base_sha
    state=$(jq -r '.state' "${report}/pr-live.json")
    draft=$(jq -r '.draft' "${report}/pr-live.json")
    merged=$(jq -r '.merged' "${report}/pr-live.json")
    head=$(jq -r '.head.sha' "${report}/pr-live.json")
    base_sha=$(jq -r '.base.sha' "${report}/pr-live.json")
    if [[ ${merged} == true ]]; then
      echo "${head}"
      return 0
    fi
    if [[ ${state} != open ]]; then
      return 1
    fi
    if [[ ${head} != "${placeholder_head}" && ${draft} == false && ${base_sha} == "${implementation_merge}" ]]; then
      echo "${head}"
      return 0
    fi
    sleep 20
  done
  return 1
}

closure_head=$(wait_for_closure_head) || fail closure-publication \
  "Increment 34 closure PR did not reach a published, ready exact head"
pass closure-publication

gh api "repos/${repo}/contents/tests/compiler/fixtures/increment34/manifest.json?ref=${closure_head}" \
  --jq '.content' | tr -d '\n' | base64 -d > "${report}/manifest.json"
gh api "repos/${repo}/contents/docs/roadmap/nodal-development-todo.md?ref=${closure_head}" \
  --jq '.content' | tr -d '\n' | base64 -d > "${report}/roadmap.md"
gh api "repos/${repo}/contents/docs/implementation/increment34-evidence-closure.md?ref=${closure_head}" \
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
    raise SystemExit("closure manifest is not validated")
if manifest.get("tranche") != "34d-closure":
    raise SystemExit("closure manifest is not in tranche 34d")
if not isinstance(validation, dict):
    raise SystemExit("closure validation evidence is absent")
expected = {
    "implementation_pull_request": 109,
    "accepted_head": "207fd1b580e9428e9948cd4e4bd8f2060fde4b79",
    "final_review_head": "54d8523715a86e1780263b6f5227def2f0977833",
    "exact_head_workflow_count": 26,
    "exact_head_core_ci_run": 33732864482,
    "implementation_merge": "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49",
    "post_merge_core_ci_run": 33758905273,
    "exact_post_merge_validation_run": 33759112770,
    "closure_pull_request": 111,
}
for key, value in expected.items():
    if validation.get(key) != value:
        raise SystemExit(f"closure evidence mismatch: {key}")
if not re.fullmatch(r"[0-9a-f]{40}", str(validation.get("closure_validation_head", ""))):
    raise SystemExit("invalid closure validation head")
if not isinstance(validation.get("closure_validation_run"), int) or validation["closure_validation_run"] <= 0:
    raise SystemExit("invalid closure validation run")
if "**Revision:** 1.45" not in roadmap:
    raise SystemExit("roadmap revision 1.45 is absent")
if "- [x] **Increment 34 — Analog control flow**" not in roadmap:
    raise SystemExit("Increment 34 is not checked")
if "- [ ] **Increment 34 — Analog control flow**" in roadmap:
    raise SystemExit("duplicate open Increment 34 entry remains")
for token in (
    "**Implementation PR:** #109",
    "**Closure PR:** #111",
    f"**Closure validation head:** `{validation['closure_validation_head']}`",
    f"**Closure validation run:** `{validation['closure_validation_run']}`",
):
    if token not in evidence:
        raise SystemExit(f"evidence record is missing {token!r}")
PY
pass closure-static-evidence

poll_checks() {
  local head=$1
  local deadline=$((SECONDS + 7200))
  local stable=0
  local previous=
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${head}&per_page=100" \
      > "${report}/workflow-runs.json"
    python3 - "${report}/workflow-runs.json" > "${report}/workflow-state.json" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
latest: dict[tuple[str, str], dict] = {}
for run in document.get("workflow_runs", []):
    key = (str(run.get("name")), str(run.get("event")))
    if key not in latest or int(run.get("id", 0)) > int(latest[key].get("id", 0)):
        latest[key] = run
runs = list(latest.values())
active = [
    run for run in runs if run.get("status") in {"queued", "in_progress", "waiting", "pending", "requested"}
]
bad_conclusions = {
    "failure", "cancelled", "timed_out", "action_required", "startup_failure", "stale"
}
bad = [run for run in runs if run.get("conclusion") in bad_conclusions]
names = {str(run.get("name")) for run in runs}
required = {
    "Core CI",
    "Increment 32 Equation Contribution Semantics",
    "Increment 33 Analog Procedural Assignment",
    "Increment 34 Analog Control Flow",
    "Increment 133 Analog Semantic API",
}
state = {
    "count": len(runs),
    "active": sorted(str(run.get("name")) for run in active),
    "bad": sorted(
        f"{run.get('name')}:{run.get('conclusion')}" for run in bad
    ),
    "missing": sorted(required - names),
    "success": sorted(
        str(run.get("name")) for run in runs if run.get("conclusion") == "success"
    ),
}
state["settled"] = bool(runs) and not active
state["acceptable"] = (
    state["settled"]
    and not state["bad"]
    and not state["missing"]
    and state["count"] >= 5
)
print(json.dumps(state, indent=2, sort_keys=True))
PY
    local acceptable settled signature
    acceptable=$(jq -r '.acceptable' "${report}/workflow-state.json")
    settled=$(jq -r '.settled' "${report}/workflow-state.json")
    signature=$(sha256sum "${report}/workflow-state.json" | awk '{print $1}')
    if [[ ${acceptable} == true && ${settled} == true ]]; then
      if [[ ${signature} == "${previous}" ]]; then
        stable=$((stable + 1))
      else
        stable=1
        previous=${signature}
      fi
      if (( stable >= 3 )); then
        return 0
      fi
    else
      stable=0
      previous=${signature}
      if [[ $(jq '.bad | length' "${report}/workflow-state.json") -gt 0 ]]; then
        return 1
      fi
    fi
    sleep 20
  done
  return 1
}

poll_checks "${closure_head}" || fail closure-pr-matrix \
  "Increment 34 closure exact-head workflow matrix failed or did not settle"
pass closure-pr-matrix

rm -rf exact-head
git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" exact-head
git -C exact-head checkout --detach "${closure_head}"

run_exact_validation() {
  local root=$1
  local prefix=$2
  (
    cd "${root}"
    python3 scripts/check_increment32.py
    python3 scripts/check_increment33.py
    python3 scripts/check_increment34.py
    python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'
    python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'
    python3 -m unittest discover -s tests/compiler -p 'test_increment34.py'
    python3 -m unittest discover -s tests/compiler -p 'test_*.py'
    ./nodal bootstrap --mode prebuilt --prefix "${prefix}/native"
    ./nodal style bootstrap --prefix "${prefix}/lint"
    ./nodal core scala
    ./nodal core native --toolchain "${prefix}/native" --lint-toolchain "${prefix}/lint"
    ./nodal style check --lint-toolchain "${prefix}/lint"
    output="${prefix}/runtime.txt"
    ./mill -i examples.continuousTimeApi.runMain \
      nodal.increment34fixture.Increment34RuntimeCheck "${output}"
    grep -F 'forward_reference=NODAL-ANALOG-034-014' "${output}"
    output="${prefix}/construction.txt"
    ./mill -i examples.continuousTimeApi.runMain \
      nodal.increment34fixture.Increment34ConstructionCheck "${output}"
    grep -F 'precontrol_scope_aligned=true' "${output}"
    test -z "$(git status --porcelain)"
    git diff --check
  )
}

run_exact_validation exact-head "${RUNNER_TEMP}/inc34-closure-premerge" \
  > "${report}/exact-head-validation.log" 2>&1 || {
    tail -n 400 "${report}/exact-head-validation.log" >&2 || true
    fail closure-exact-tree-validation "exact closure tree validation failed"
  }
pass closure-exact-tree-validation

live_head=$(gh api "repos/${repo}/pulls/${pr}" --jq '.head.sha')
[[ ${live_head} == "${closure_head}" ]] || fail closure-lease \
  "closure PR moved after validation"
pass closure-lease

merge_json=${report}/merge.json
gh api --method PUT "repos/${repo}/pulls/${pr}/merge" \
  -f sha="${closure_head}" \
  -f merge_method=squash \
  -f commit_title='Increment 34 — Record accepted evidence and close roadmap (#111)' \
  -f commit_message='Close Increment 34 with immutable implementation, exact-head, post-merge, and separate evidence-validation records.' \
  > "${merge_json}"
[[ $(jq -r '.merged' "${merge_json}") == true ]] || fail closure-merge \
  "GitHub did not merge Increment 34 closure PR #111"
merge_sha=$(jq -r '.sha' "${merge_json}")
pass closure-merge

echo "closure_head=${closure_head}" > "${report}/metadata.txt"
echo "closure_merge_sha=${merge_sha}" >> "${report}/metadata.txt"
echo "closure_pr_matrix_count=$(jq -r '.count' "${report}/workflow-state.json")" \
  >> "${report}/metadata.txt"

for attempt in $(seq 1 60); do
  dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
  if [[ ${dev_head} == "${merge_sha}" ]]; then
    break
  fi
  sleep 10
done
[[ ${dev_head:-} == "${merge_sha}" ]] || fail closure-dev-head \
  "closure merge did not become the dev head"
pass closure-dev-head

poll_core_ci() {
  local head=$1
  local deadline=$((SECONDS + 5400))
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${head}&per_page=100" \
      > "${report}/postclosure-runs.json"
    python3 - "${report}/postclosure-runs.json" > "${report}/postclosure-state.json" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path

doc = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
runs = [run for run in doc.get("workflow_runs", []) if run.get("name") == "Core CI"]
runs.sort(key=lambda run: int(run.get("id", 0)), reverse=True)
run = runs[0] if runs else None
state = {
    "found": run is not None,
    "id": run.get("id") if run else None,
    "status": run.get("status") if run else None,
    "conclusion": run.get("conclusion") if run else None,
}
print(json.dumps(state, indent=2, sort_keys=True))
PY
    if [[ $(jq -r '.found' "${report}/postclosure-state.json") == true ]]; then
      status_value=$(jq -r '.status' "${report}/postclosure-state.json")
      conclusion=$(jq -r '.conclusion' "${report}/postclosure-state.json")
      if [[ ${status_value} == completed ]]; then
        [[ ${conclusion} == success ]]
        return
      fi
    fi
    sleep 20
  done
  return 1
}

poll_core_ci "${merge_sha}" || fail postclosure-core-ci \
  "Core CI did not pass on the exact Increment 34 closure merge"
pass postclosure-core-ci
postclosure_core_run=$(jq -r '.id' "${report}/postclosure-state.json")
echo "postclosure_core_ci_run=${postclosure_core_run}" >> "${report}/metadata.txt"

git -C exact-head fetch origin dev
git -C exact-head checkout --detach "${merge_sha}"
run_exact_validation exact-head "${RUNNER_TEMP}/inc34-closure-final" \
  > "${report}/final-dev-validation.log" 2>&1 || {
    tail -n 400 "${report}/final-dev-validation.log" >&2 || true
    fail final-dev-validation "final dev validation failed"
  }
pass final-dev-validation

cat > "${report}/README.md" <<EOF
# Increment 34 final closure validation

- Implementation PR: #109
- Accepted implementation head: \`207fd1b580e9428e9948cd4e4bd8f2060fde4b79\`
- Implementation merge: \`${implementation_merge}\`
- Closure PR: #111
- Closure exact head: \`${closure_head}\`
- Closure merge: \`${merge_sha}\`
- Closure PR workflow count: $(jq -r '.count' "${report}/workflow-state.json")
- Post-closure Core CI: \`${postclosure_core_run}\`

The exact closure head passed every triggered owner-authored PR workflow and an
independent full Increment 32–34, Scala, native, witness, and style validation.
The squash-merged \`dev\` commit then passed Core CI and the same independent
validation again.

Increment 34 is closed. Solver execution, target legalization, and Verilog-A or
Verilog-AMS procedural lowering remain assigned to later roadmap increments.

## Gates

| Gate | Result | Exit |
|---|---:|---:|
EOF
while IFS=$'\t' read -r name outcome rc; do
  printf '| `%s` | %s | %s |\n' "${name}" "${outcome}" "${rc}" \
    >> "${report}/README.md"
done < "${status}"

result_branch=audit/increment34-final-closure-results-v1
rm -rf result
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" result
git -C result checkout --orphan result-root
git -C result rm -rf . >/dev/null 2>&1 || true
cp -R "${report}/." result/
git -C result add -A
git -C result config user.name github-actions[bot]
git -C result config user.email 41898282+github-actions[bot]@users.noreply.github.com
git -C result commit -m \
  "ci(increment34): record final closure validation ${GITHUB_RUN_ID}"
git -C result push --force origin HEAD:"${result_branch}"

gh pr comment 111 --repo "${repo}" \
  --body "Increment 34 closure is complete. Closure head: \`${closure_head}\`; merge: \`${merge_sha}\`; post-closure Core CI: \`${postclosure_core_run}\`; final audit run: \`${GITHUB_RUN_ID}\`."

body_file=${report}/closed-pr-body.md
python3 - "${report}/pr-live.json" "${body_file}" <<'PY'
from pathlib import Path
import json
import sys

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["body"]
source = source.replace(
    "- [ ] Pass the normal owner-authored closure PR workflow matrix.",
    "- [x] Pass the normal owner-authored closure PR workflow matrix.",
)
source = source.replace(
    "- [ ] Merge the evidence-only closure to `dev`.",
    "- [x] Merge the evidence-only closure to `dev`.",
)
Path(sys.argv[2]).write_text(source, encoding="utf-8")
PY
gh pr edit 111 --repo "${repo}" --body-file "${body_file}" || true
