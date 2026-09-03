#!/usr/bin/env bash
set -Eeuo pipefail

repo=${GITHUB_REPOSITORY:-pysolvesemi/Nodal}
pr_number=111
implementation_pr=109
accepted_head=207fd1b580e9428e9948cd4e4bd8f2060fde4b79
implementation_merge=a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49
implementation_matrix_core=33732864482
postmerge_core=33758905273
postmerge_validation=33759112770
closure_validation=33761024228
result_branch=audit/increment34-final-confirmation-v8-20260904
report_dir=${RUNNER_TEMP}/increment34-final-close-v8
work=${RUNNER_TEMP}/increment34-final-work
mkdir -p "${report_dir}"
status_file=${report_dir}/status.json
stage=initialize
final_state=failed
closure_head=
closure_merge=
final_dev=
final_core_run=

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'
}

write_status() {
  local detail=${1:-}
  python3 - "${status_file}" "${final_state}" "${stage}" "${detail}" \
    "${closure_head}" "${closure_merge}" "${final_dev}" "${final_core_run}" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

path, state, stage, detail, closure_head, closure_merge, final_dev, core_run = sys.argv[1:]
payload = {
    "schema": 1,
    "increment": 34,
    "state": state,
    "stage": stage,
    "detail": detail,
    "repository": os.environ.get("GITHUB_REPOSITORY"),
    "controller_run": int(os.environ.get("GITHUB_RUN_ID", "0") or 0),
    "controller_attempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "0") or 0),
    "closure_pull_request": 111,
    "closure_head": closure_head or None,
    "closure_merge": closure_merge or None,
    "final_dev": final_dev or None,
    "final_core_ci_run": int(core_run) if core_run else None,
    "recorded_at": datetime.now(timezone.utc).isoformat(),
}
Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

publish_result() {
  local exit_code=$?
  set +e
  if [[ ! -f ${status_file} ]]; then
    write_status "controller exited before producing a status record"
  fi
  cat > "${report_dir}/README.md" <<EOF
# Increment 34 final closure v8

- State: **${final_state}**
- Stage: \`${stage}\`
- Controller run: \`${GITHUB_RUN_ID:-unknown}\`
- Closure PR: #111
- Closure head: \`${closure_head:-unavailable}\`
- Closure merge: \`${closure_merge:-unavailable}\`
- Final validated dev: \`${final_dev:-unavailable}\`
- Final Core CI run: \`${final_core_run:-unavailable}\`

The machine-readable record is in \`status.json\`. A complete state is emitted
only after the closure PR, exact closure merge, Core CI, contracts, mutations,
Scala, native compiler, source-semantic witnesses, repository style, roadmap,
manifest, and immutable evidence checks all pass.
EOF
  rm -rf "${RUNNER_TEMP}/inc34-result-repo"
  git clone --no-checkout "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
    "${RUNNER_TEMP}/inc34-result-repo" >/dev/null 2>&1
  git -C "${RUNNER_TEMP}/inc34-result-repo" checkout --orphan result-root >/dev/null 2>&1
  git -C "${RUNNER_TEMP}/inc34-result-repo" rm -rf . >/dev/null 2>&1 || true
  cp -R "${report_dir}/." "${RUNNER_TEMP}/inc34-result-repo/"
  git -C "${RUNNER_TEMP}/inc34-result-repo" add -A
  git -C "${RUNNER_TEMP}/inc34-result-repo" config user.name github-actions[bot]
  git -C "${RUNNER_TEMP}/inc34-result-repo" config user.email 41898282+github-actions[bot]@users.noreply.github.com
  git -C "${RUNNER_TEMP}/inc34-result-repo" commit -m \
    "ci(increment34): record final closure v8 run ${GITHUB_RUN_ID}" >/dev/null 2>&1
  git -C "${RUNNER_TEMP}/inc34-result-repo" push --force origin HEAD:"${result_branch}" >/dev/null 2>&1
  if [[ ${final_state} == complete ]]; then
    gh api --method POST "/repos/${repo}/statuses/${closure_merge}" \
      -f state=success \
      -f context=increment34/final-closure \
      -f description='Increment 34 final closure passed' \
      -f target_url="https://github.com/${repo}/actions/runs/${GITHUB_RUN_ID}" >/dev/null || true
  fi
  exit ${exit_code}
}
trap publish_result EXIT

wait_for_matrix() {
  local head=$1
  local attempts=0
  while (( attempts < 160 )); do
    attempts=$((attempts + 1))
    gh api "/repos/${repo}/actions/runs?head_sha=${head}&per_page=100" \
      > "${report_dir}/closure-head-runs.json"
    set +e
    python3 - "${report_dir}/closure-head-runs.json" "${head}" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
head = sys.argv[2]
runs = [run for run in payload.get("workflow_runs", []) if run.get("head_sha") == head]
latest = {}
for run in runs:
    key = (run.get("workflow_id"), run.get("event"))
    previous = latest.get(key)
    if previous is None or (run.get("created_at") or "") > (previous.get("created_at") or ""):
        latest[key] = run
selected = list(latest.values())
failed = [run for run in selected if run.get("status") == "completed" and run.get("conclusion") != "success"]
pending = [run for run in selected if run.get("status") != "completed"]
names = {run.get("name") for run in selected}
print(f"workflow_count={len(selected)} pending={len(pending)} failed={len(failed)}")
if failed:
    for run in failed:
        print(f"FAILED {run.get('name')} {run.get('id')} {run.get('conclusion')}")
    raise SystemExit(3)
if pending:
    raise SystemExit(2)
if len(selected) < 26:
    raise SystemExit(2)
required = {"Core CI", "Increment 34 Analog Control Flow"}
if not required.issubset(names):
    print("missing required workflows: " + ", ".join(sorted(required - names)))
    raise SystemExit(2)
raise SystemExit(0)
PY
    rc=$?
    set -e
    if [[ ${rc} -eq 0 ]]; then
      return 0
    fi
    if [[ ${rc} -eq 3 ]]; then
      return 3
    fi
    sleep 30
  done
  return 2
}

wait_for_core_ci() {
  local sha=$1
  local started_epoch=$2
  local attempts=0
  local dispatched=0
  while (( attempts < 160 )); do
    attempts=$((attempts + 1))
    gh api "/repos/${repo}/actions/workflows/ci.yml/runs?head_sha=${sha}&per_page=30" \
      > "${report_dir}/final-core-runs.json"
    set +e
    selection=$(python3 - "${report_dir}/final-core-runs.json" "${sha}" "${started_epoch}" <<'PY'
import datetime as dt
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
sha = sys.argv[2]
started = int(sys.argv[3])
selected = []
for run in payload.get("workflow_runs", []):
    if run.get("head_sha") != sha:
        continue
    created = run.get("created_at")
    try:
        epoch = int(dt.datetime.fromisoformat(created.replace("Z", "+00:00")).timestamp())
    except Exception:
        epoch = 0
    if epoch >= started - 60:
        selected.append(run)
selected.sort(key=lambda run: run.get("created_at") or "", reverse=True)
if not selected:
    raise SystemExit(4)
run = selected[0]
print(f"{run.get('id')}\t{run.get('status')}\t{run.get('conclusion') or ''}")
if run.get("status") != "completed":
    raise SystemExit(2)
if run.get("conclusion") != "success":
    raise SystemExit(3)
PY
)
    rc=$?
    set -e
    if [[ ${rc} -eq 0 ]]; then
      final_core_run=$(printf '%s' "${selection}" | cut -f1)
      return 0
    fi
    if [[ ${rc} -eq 3 ]]; then
      printf '%s\n' "${selection}" > "${report_dir}/final-core-failure.txt"
      return 3
    fi
    if [[ ${rc} -eq 4 && ${dispatched} -eq 0 ]]; then
      current_dev=$(gh api "/repos/${repo}/git/ref/heads/dev" --jq '.object.sha')
      if [[ ${current_dev} != "${sha}" ]]; then
        echo "dev moved before exact Core CI dispatch: ${current_dev}" >&2
        return 5
      fi
      gh workflow run ci.yml --repo "${repo}" --ref dev
      dispatched=1
    fi
    sleep 30
  done
  return 2
}

stage=inspect-closure-pr
pr_json=${report_dir}/pr111.json
gh api "/repos/${repo}/pulls/${pr_number}" > "${pr_json}"
closure_head=$(jq -r '.head.sha' "${pr_json}")
pr_state=$(jq -r '.state' "${pr_json}")
pr_merged=$(jq -r '.merged' "${pr_json}")

if [[ ${pr_merged} != true ]]; then
  if [[ ${pr_state} != open ]]; then
    write_status "closure PR is neither open nor merged"
    exit 1
  fi
  stage=validate-closure-head-content
  rm -rf "${work}"
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${work}" >/dev/null 2>&1
  git -C "${work}" checkout --detach "${closure_head}" >/dev/null 2>&1
  python3 - "${work}" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
manifest = json.loads((root / "tests/compiler/fixtures/increment34/manifest.json").read_text())
roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
assert manifest.get("increment") == 34
assert manifest.get("status") == "validated-analog-control-flow"
assert manifest.get("tranche") == "34d-closure"
assert isinstance(manifest.get("validation"), dict)
assert "**Revision:** 1.45" in roadmap
assert len(re.findall(r"(?m)^- \[x\] \*\*Increment 34 — Analog control flow\*\*$", roadmap)) == 1
assert "- [ ] **Increment 34 — Analog control flow**" not in roadmap
assert (root / "docs/implementation/increment34-evidence-closure.md").is_file()
PY

  stage=wait-closure-exact-head-matrix
  if ! wait_for_matrix "${closure_head}"; then
    write_status "closure exact-head workflow matrix is incomplete or failed"
    exit 1
  fi

  stage=mark-closure-ready
  draft=$(jq -r '.draft' "${pr_json}")
  if [[ ${draft} == true ]]; then
    gh pr ready "${pr_number}" --repo "${repo}"
  fi

  stage=merge-closure-pr
  gh pr merge "${pr_number}" --repo "${repo}" --squash \
    --subject 'Increment 34 — Record accepted evidence and close roadmap (#111)' \
    --body 'Close Increment 34 with immutable implementation, exact-head, post-merge, and evidence-validation records. Residual/DAE construction, solver execution, target legalization, and Verilog-A/Verilog-AMS lowering remain deferred.'
fi

stage=resolve-closure-merge
for _ in $(seq 1 40); do
  gh api "/repos/${repo}/pulls/${pr_number}" > "${pr_json}"
  pr_merged=$(jq -r '.merged' "${pr_json}")
  closure_merge=$(jq -r '.merge_commit_sha // empty' "${pr_json}")
  if [[ ${pr_merged} == true && -n ${closure_merge} ]]; then
    break
  fi
  sleep 5
done
if [[ ${pr_merged} != true || -z ${closure_merge} ]]; then
  write_status "closure PR did not reach the merged state"
  exit 1
fi

stage=verify-dev-contains-closure
rm -rf "${work}"
git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${work}" >/dev/null 2>&1
git -C "${work}" fetch --no-tags origin dev:refs/remotes/origin/dev >/dev/null 2>&1
final_dev=$(git -C "${work}" rev-parse origin/dev)
if ! git -C "${work}" merge-base --is-ancestor "${closure_merge}" "${final_dev}"; then
  write_status "closure merge is not an ancestor of dev"
  exit 1
fi

# Closure should be validated at its exact merge commit even when later dev work exists.
git -C "${work}" checkout --detach "${closure_merge}" >/dev/null 2>&1

stage=validate-final-closure-contracts
python3 "${work}/scripts/check_increment32.py"
python3 "${work}/scripts/check_increment33.py"
python3 "${work}/scripts/check_increment34.py"
python3 -m unittest discover -s "${work}/tests/compiler" -p 'test_increment32.py'
python3 -m unittest discover -s "${work}/tests/compiler" -p 'test_increment33.py'
python3 -m unittest discover -s "${work}/tests/compiler" -p 'test_increment34.py'
python3 -m unittest discover -s "${work}/tests/compiler" -p 'test_*.py'

stage=validate-final-closure-state
python3 - "${work}" "${closure_head}" "${closure_merge}" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
closure_head = sys.argv[2]
closure_merge = sys.argv[3]
manifest = json.loads((root / "tests/compiler/fixtures/increment34/manifest.json").read_text())
roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
evidence = (root / "docs/implementation/increment34-evidence-closure.md").read_text()
validation = manifest.get("validation")
assert manifest.get("schema") == 1
assert manifest.get("increment") == 34
assert manifest.get("status") == "validated-analog-control-flow"
assert manifest.get("tranche") == "34d-closure"
assert isinstance(validation, dict)
assert validation.get("implementation_pull_request") == 109
assert validation.get("accepted_head") == "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
assert validation.get("implementation_merge") == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
assert validation.get("post_merge_core_ci_run") == 33758905273
assert validation.get("exact_post_merge_validation_run") == 33759112770
assert validation.get("closure_pull_request") == 111
assert validation.get("closure_validation_run") == 33761024228
assert re.fullmatch(r"[0-9a-f]{40}", str(validation.get("closure_validation_head")))
assert "**Revision:** 1.45" in roadmap
assert len(re.findall(r"(?m)^- \[x\] \*\*Increment 34 — Analog control flow\*\*$", roadmap)) == 1
assert "- [ ] **Increment 34 — Analog control flow**" not in roadmap
for token in (
    "**Implementation PR:** #109",
    "`207fd1b580e9428e9948cd4e4bd8f2060fde4b79`",
    "`a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49`",
    "33758905273",
    "33759112770",
    "#111",
    "33761024228",
):
    assert token in evidence, token
PY

stage=validate-final-scala
(
  cd "${work}"
  ./nodal core scala
  runtime_report=${RUNNER_TEMP}/increment34-final-runtime.txt
  construction_report=${RUNNER_TEMP}/increment34-final-construction.txt
  ./mill -i examples.continuousTimeApi.runMain \
    nodal.increment34fixture.Increment34RuntimeCheck "${runtime_report}"
  ./mill -i examples.continuousTimeApi.runMain \
    nodal.increment34fixture.Increment34ConstructionCheck "${construction_report}"
  grep -F 'conditional_definite=true' "${runtime_report}"
  grep -F 'case_definite=true' "${runtime_report}"
  grep -F 'loop_definite=true' "${runtime_report}"
  grep -F 'public_conditional_snapshots=1' "${construction_report}"
  grep -F 'public_case_snapshots=1' "${construction_report}"
  grep -F 'flat_assignments=0' "${construction_report}"
)

stage=validate-final-native-and-style
(
  cd "${work}"
  ./nodal bootstrap --mode prebuilt --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
  ./nodal style bootstrap --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"
  ./nodal core native \
    --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"
  ./nodal style check \
    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" \
    --base-ref "${closure_merge}^"
  git diff --check "${closure_merge}^" "${closure_merge}"
  test -z "$(git status --porcelain)"
)

stage=wait-final-core-ci
started_epoch=$(date +%s)
if ! wait_for_core_ci "${closure_merge}" "${started_epoch}"; then
  write_status "Core CI failed or did not complete on the exact closure merge"
  exit 1
fi

stage=finalize-pr-record
body_file=${report_dir}/pr-body.md
gh pr view "${pr_number}" --repo "${repo}" --json body --jq '.body' > "${body_file}"
python3 - "${body_file}" "${closure_merge}" "${final_core_run}" "${GITHUB_RUN_ID}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
merge_sha, core_run, final_run = sys.argv[2:]
text = path.read_text(encoding="utf-8")
for item in (
    "Pass the owner-authored closure exact-head workflow matrix.",
    "Merge the evidence closure to `dev`.",
    "Verify the final closure merge on `dev` with Core CI and dedicated validation.",
):
    text = text.replace(f"- [ ] {item}", f"- [x] {item}")
appendix = f"""

## Final closure confirmation

- Closure merge: `{merge_sha}`
- Exact closure-merge Core CI: `{core_run}`
- Final dedicated validation: `{final_run}`
- Audit branch: `audit/increment34-final-confirmation-v8-20260904`
"""
if "## Final closure confirmation" not in text:
    text = text.rstrip() + appendix
path.write_text(text, encoding="utf-8")
PY
gh pr edit "${pr_number}" --repo "${repo}" --body-file "${body_file}"
gh pr comment "${pr_number}" --repo "${repo}" \
  --body "Increment 34 final closure passed on merge \`${closure_merge}\`. Exact Core CI: \`${final_core_run}\`; dedicated validation: \`${GITHUB_RUN_ID}\`; audit: \`${result_branch}\`."

final_state=complete
stage=complete
write_status "Increment 34 implementation and evidence closure are fully validated"
