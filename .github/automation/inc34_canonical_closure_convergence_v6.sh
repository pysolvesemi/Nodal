#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
closure_pr=111
closure_branch=increment/34-evidence-closure-v1
consistency_branch=increment/34-evidence-closure-consistency-v6
placeholder=da785a1fbb0d8381a83d9100e2318c553102efc8
implementation_head=207fd1b580e9428e9948cd4e4bd8f2060fde4b79
implementation_merge=a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49
validator=${controller}/.github/automation/inc34_validate_canonical_closure_v6.py
workflow_reconciler=${controller}/.github/automation/inc34_poll_exact_workflows_v6.py
status=${report}/status.tsv
: > "${status}"

record() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "${status}"
}

pass() {
  record "$1" PASS 0
  echo "$1: PASS"
}

fail() {
  record "$1" FAIL 1
  echo "$2" >&2
  exit 1
}

pr_json() {
  gh api "repos/${repo}/pulls/${closure_pr}"
}

latest_named_state() {
  local name=$1
  gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100" \
    | jq -r --arg name "${name}" '
        [.workflow_runs[] | select(.name == $name)]
        | sort_by(.id) | reverse | .[0]
        | if . == null then "missing" else (.status + ":" + (.conclusion // "")) end
      '
}

checkout_exact() {
  local sha=$1
  local destination=$2
  rm -rf "${destination}"
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
    "${destination}"
  git -C "${destination}" checkout --detach "${sha}"
}

run_full_validation() {
  local root=$1
  local prefix=$2
  mkdir -p "${prefix}"
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
    ./nodal core native \
      --toolchain "${prefix}/native" \
      --lint-toolchain "${prefix}/lint"
    ./nodal style check --lint-toolchain "${prefix}/lint"
    runtime="${prefix}/runtime.txt"
    ./mill -i examples.continuousTimeApi.runMain \
      nodal.increment34fixture.Increment34RuntimeCheck "${runtime}"
    grep -F 'conditional_definite=true' "${runtime}"
    grep -F 'case_definite=true' "${runtime}"
    grep -F 'loop_definite=true' "${runtime}"
    grep -F 'forward_reference=NODAL-ANALOG-034-014' "${runtime}"
    construction="${prefix}/construction.txt"
    ./mill -i examples.continuousTimeApi.runMain \
      nodal.increment34fixture.Increment34ConstructionCheck "${construction}"
    grep -F 'public_conditional_snapshots=1' "${construction}"
    grep -F 'public_case_snapshots=1' "${construction}"
    grep -F 'flat_assignments=0' "${construction}"
    grep -F 'precontrol_scope_aligned=true' "${construction}"
    test -z "$(git status --porcelain)"
    git diff --check
  )
}

poll_matrix() {
  local sha=$1
  local label=$2
  local deadline=$((SECONDS + 10800))
  local stable=0
  local previous=
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${sha}&per_page=100" \
      > "${report}/${label}-runs.json"
    python3 "${workflow_reconciler}" \
      --input "${report}/${label}-runs.json" \
      --minimum-count 24 \
      > "${report}/${label}-state.json"
    if [[ $(jq '.bad | length' "${report}/${label}-state.json") -gt 0 ]]; then
      cat "${report}/${label}-state.json" >&2
      return 1
    fi
    if [[ $(jq -r '.acceptable' "${report}/${label}-state.json") == true ]]; then
      signature=$(sha256sum "${report}/${label}-state.json" | awk '{print $1}')
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
      previous=
    fi
    sleep 20
  done
  cat "${report}/${label}-state.json" >&2 || true
  return 1
}

wait_core_ci() {
  local sha=$1
  local label=$2
  local deadline=$((SECONDS + 7200))
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${sha}&per_page=100" \
      > "${report}/${label}-runs.json"
    python3 - "${report}/${label}-runs.json" \
      > "${report}/${label}-state.json" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
runs = [
    run for run in document.get("workflow_runs", []) if run.get("name") == "Core CI"
]
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
    if [[ $(jq -r '.found' "${report}/${label}-state.json") == true ]]; then
      local run_status conclusion
      run_status=$(jq -r '.status' "${report}/${label}-state.json")
      conclusion=$(jq -r '.conclusion' "${report}/${label}-state.json")
      if [[ ${run_status} == completed ]]; then
        [[ ${conclusion} == success ]]
        return
      fi
    fi
    sleep 20
  done
  return 1
}

canonical_checkout() {
  local sha=$1
  local destination=$2
  checkout_exact "${sha}" "${destination}"
  python3 "${validator}" --root "${destination}" --json \
    > "${destination}/increment34-canonical-state.json"
}

run_materializer_recovery() {
  local version=$1
  local runner=${controller}/.github/automation/inc34_run_evidence_closure_${version}.sh
  local work=${report}/materializer-${version}-work
  rm -rf "${work}"
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${work}"
  git -C "${work}" checkout "${closure_branch}"
  bash "${runner}" "${controller}" "${work}" \
    "${report}/materializer-${version}-result"
}

merge_pr_exact() {
  local number=$1
  local sha=$2
  local title=$3
  local message=$4
  local output=$5
  set +e
  gh api --method PUT "repos/${repo}/pulls/${number}/merge" \
    -f sha="${sha}" \
    -f merge_method=squash \
    -f commit_title="${title}" \
    -f commit_message="${message}" \
    > "${output}" 2> "${output}.err"
  local rc=$?
  set -e
  if [[ ${rc} -eq 0 && $(jq -r '.merged' "${output}") == true ]]; then
    jq -r '.sha' "${output}"
    return 0
  fi
  local live
  live=$(gh api "repos/${repo}/pulls/${number}")
  if [[ $(jq -r '.merged' <<< "${live}") == true ]]; then
    jq -r '.merge_commit_sha' <<< "${live}"
    return 0
  fi
  cat "${output}.err" >&2 || true
  cat "${output}" >&2 || true
  return 1
}

create_consistency_repair() {
  local base_sha=$1
  local root=${report}/consistency-work
  checkout_exact "${base_sha}" "${root}"
  git -C "${root}" switch -c "${consistency_branch}"
  python3 "${controller}/.github/automation/inc34_evidence_closure_followup_v1.py" \
    --root "${root}"
  python3 "${controller}/.github/automation/inc34_evidence_closure_followup_v2.py" \
    --root "${root}"
  python3 "${controller}/.github/automation/inc34_evidence_closure_followup_v3.py" \
    --root "${root}"
  python3 "${controller}/.github/automation/inc34_evidence_closure_followup_v4.py" \
    --root "${root}"

  "${root}/nodal" style bootstrap --prefix "${report}/consistency-lint"
  ruff=$(find "${report}/consistency-lint" -type f -name ruff -perm -u+x | head -n 1)
  [[ -n ${ruff} ]] || fail consistency-format \
    "locked Ruff executable was not found for the consistency repair"
  "${ruff}" format \
    "${root}/scripts/check_increment32.py" \
    "${root}/scripts/check_increment33.py" \
    "${root}/scripts/check_increment34.py" \
    "${root}/tests/compiler/test_increment32.py" \
    "${root}/tests/compiler/test_increment33.py" \
    "${root}/tests/compiler/test_increment34.py"
  pass consistency-format

  python3 "${validator}" --root "${root}" --json \
    > "${report}/consistency-canonical-state.json"
  pass consistency-static-state
  run_full_validation "${root}" "${report}/consistency-validation" \
    > "${report}/consistency-validation.log" 2>&1 || {
      tail -n 500 "${report}/consistency-validation.log" >&2 || true
      fail consistency-validation "Increment 34 consistency repair validation failed"
    }
  pass consistency-validation

  git -C "${root}" add -A
  if git -C "${root}" diff --cached --quiet; then
    fail consistency-diff \
      "canonical validation failed but the consistency repair produced no changes"
  fi
  git -C "${root}" config user.name github-actions[bot]
  git -C "${root}" config user.email \
    41898282+github-actions[bot]@users.noreply.github.com
  git -C "${root}" commit -m \
    'docs(increment34): canonicalize final evidence closure records'
  local repair_head
  repair_head=$(git -C "${root}" rev-parse HEAD)

  local remote_old
  remote_old=$(git -C "${root}" ls-remote origin \
    "refs/heads/${consistency_branch}" | awk '{print $1}')
  if [[ -n ${remote_old} ]]; then
    git -C "${root}" push \
      --force-with-lease="refs/heads/${consistency_branch}:${remote_old}" \
      origin HEAD:"${consistency_branch}"
  else
    git -C "${root}" push origin HEAD:"${consistency_branch}"
  fi

  local repair_pr
  repair_pr=$(gh pr list --repo "${repo}" --head "${consistency_branch}" \
    --state all --json number,state,mergedAt --jq '.[0].number // empty')
  if [[ -z ${repair_pr} ]]; then
    cat > "${report}/consistency-pr-body.md" <<EOF
## Summary

Canonicalizes the already-merged Increment 34 evidence closure without changing the accepted analog control-flow implementation.

- retains the accepted dedicated Increment 34 boundary run \`33732868285\`;
- removes stale open-tranche language from the design-gate and fixture records;
- keeps the validated manifest, roadmap revision 1.45, and checked Increment 34 item consistent;
- adds mutation coverage locking the canonical evidence shape.

## Validation

The exact repair head passed the Increment 32, 33, and 34 contracts and mutation suites, complete compiler mutation discovery, Scala and native core builds, repository style, both Increment 34 witnesses, and canonical closure validation.

## Design gate

This is evidence and documentation consistency repair only. It does not alter the accepted public or compiler semantics and does not add solver execution, target legalization, or Verilog-A/Verilog-AMS procedural lowering.

## Checklist

- [x] Retain canonical dedicated-boundary evidence.
- [x] Remove contradictory open-state documentation.
- [x] Pass exact-head contract, mutation, Scala, native, witness, and style validation.
- [ ] Pass the owner-authored PR workflow matrix.
- [ ] Merge the consistency repair to \`dev\`.
EOF
    repair_pr=$(gh pr create --repo "${repo}" \
      --base dev --head "${consistency_branch}" \
      --title 'Increment 34 — Canonicalize final evidence closure records' \
      --body-file "${report}/consistency-pr-body.md")
    repair_pr=${repair_pr##*/}
  else
    local repair_state
    repair_state=$(gh api "repos/${repo}/pulls/${repair_pr}" --jq '.state')
    if [[ ${repair_state} == closed && \
          $(gh api "repos/${repo}/pulls/${repair_pr}" --jq '.merged') != true ]]; then
      gh pr reopen "${repair_pr}" --repo "${repo}"
    fi
  fi
  gh pr ready "${repair_pr}" --repo "${repo}" || true

  poll_matrix "${repair_head}" consistency-pr || fail consistency-pr-matrix \
    "Increment 34 consistency PR matrix failed or did not settle"
  pass consistency-pr-matrix

  checkout_exact "${repair_head}" "${report}/consistency-exact"
  python3 "${validator}" --root "${report}/consistency-exact" --json \
    > "${report}/consistency-exact-state.json"
  run_full_validation "${report}/consistency-exact" \
    "${report}/consistency-exact-validation" \
    > "${report}/consistency-exact-validation.log" 2>&1 || {
      tail -n 500 "${report}/consistency-exact-validation.log" >&2 || true
      fail consistency-exact-validation \
        "exact consistency PR tree validation failed"
    }
  pass consistency-exact-validation

  local live_head live_base
  live_head=$(gh api "repos/${repo}/pulls/${repair_pr}" --jq '.head.sha')
  live_base=$(gh api "repos/${repo}/pulls/${repair_pr}" --jq '.base.sha')
  [[ ${live_head} == "${repair_head}" ]] || fail consistency-lease \
    "consistency PR moved after exact validation"
  [[ ${live_base} == "${base_sha}" ]] || fail consistency-base-lease \
    "dev moved after consistency repair validation"
  pass consistency-lease
  pass consistency-base-lease

  local repair_merge
  repair_merge=$(merge_pr_exact "${repair_pr}" "${repair_head}" \
    'Increment 34 — Canonicalize final evidence closure records' \
    'Retain canonical boundary evidence and remove contradictory open-state documentation.' \
    "${report}/consistency-merge.json") || fail consistency-merge \
      "Increment 34 consistency repair did not merge"
  echo "consistency_pr=${repair_pr}" >> "${report}/metadata.txt"
  echo "consistency_head=${repair_head}" >> "${report}/metadata.txt"
  echo "consistency_merge=${repair_merge}" >> "${report}/metadata.txt"
  pass consistency-merge
  echo "${repair_merge}"
}

# Ensure no older controller can make a merge decision while v6 is authoritative.
bash "${controller}/.github/automation/inc34_quiesce_obsolete_closure_v3.sh" \
  > "${report}/quiesce.log" 2>&1 || {
    cat "${report}/quiesce.log" >&2 || true
    fail obsolete-controller-quiesce "failed to quiesce older Increment 34 controllers"
  }
pass obsolete-controller-quiesce

pr=$(pr_json)
pr_state=$(jq -r '.state' <<< "${pr}")
merged=$(jq -r '.merged' <<< "${pr}")
closure_merge=$(jq -r '.merge_commit_sha // empty' <<< "${pr}")

if [[ ${merged} != true ]]; then
  recovered=0
  deadline=$((SECONDS + 11400))
  while (( SECONDS < deadline )); do
    pr=$(pr_json)
    head=$(jq -r '.head.sha' <<< "${pr}")
    draft=$(jq -r '.draft' <<< "${pr}")
    if [[ ${head} != "${placeholder}" && ${draft} == false ]]; then
      checkout_exact "${head}" "${report}/closure-probe"
      if python3 "${validator}" --root "${report}/closure-probe" --json \
          > "${report}/closure-probe-state.json" 2> "${report}/closure-probe.err"; then
        closure_head=${head}
        break
      fi
    fi

    materializer_state=$(latest_named_state "Increment 34 Evidence Closure v5")
    case "${materializer_state}" in
      queued:*|in_progress:*|waiting:*|pending:*|requested:*)
        sleep 20
        ;;
      completed:success)
        sleep 10
        ;;
      *)
        if (( recovered == 0 )); then
          run_materializer_recovery v5 \
            > "${report}/materializer-recovery.log" 2>&1 || {
              tail -n 500 "${report}/materializer-recovery.log" >&2 || true
              fail closure-materialization \
                "canonical Increment 34 evidence materialization failed"
            }
          recovered=1
        else
          fail closure-materialization \
            "canonical Increment 34 evidence head was not published"
        fi
        ;;
    esac
  done
  [[ -n ${closure_head:-} ]] || fail closure-materialization \
    "canonical Increment 34 evidence head did not become ready"
  pass closure-materialization

  gh pr ready "${closure_pr}" --repo "${repo}" || true
  live_head=$(gh api "repos/${repo}/pulls/${closure_pr}" --jq '.head.sha')
  [[ ${live_head} == "${closure_head}" ]] || fail closure-ready-lease \
    "closure head changed while marking PR #111 ready"
  pass closure-ready-lease

  poll_matrix "${closure_head}" closure-pr || fail closure-pr-matrix \
    "Increment 34 closure PR matrix failed or did not settle"
  pass closure-pr-matrix

  canonical_checkout "${closure_head}" "${report}/closure-exact"
  run_full_validation "${report}/closure-exact" \
    "${report}/closure-exact-validation" \
    > "${report}/closure-exact-validation.log" 2>&1 || {
      tail -n 500 "${report}/closure-exact-validation.log" >&2 || true
      fail closure-exact-validation \
        "exact Increment 34 closure tree validation failed"
    }
  pass closure-exact-validation

  live=$(pr_json)
  [[ $(jq -r '.head.sha' <<< "${live}") == "${closure_head}" ]] \
    || fail closure-merge-lease "closure PR moved after exact validation"
  [[ $(jq -r '.base.sha' <<< "${live}") == "${implementation_merge}" ]] \
    || fail closure-base-lease "dev moved after closure validation"
  pass closure-merge-lease
  pass closure-base-lease

  closure_merge=$(merge_pr_exact "${closure_pr}" "${closure_head}" \
    'Increment 34 — Record accepted evidence and close roadmap (#111)' \
    'Close Increment 34 with immutable implementation, exact-head, post-merge, and separate evidence-validation records.' \
    "${report}/closure-merge.json") || fail closure-merge \
      "Increment 34 closure PR #111 did not merge"
  pass closure-merge
else
  closure_head=$(jq -r '.head.sha' <<< "${pr}")
  echo "PR #111 was already merged as ${closure_merge}."
  pass closure-already-merged
fi

printf 'closure_pr=%s\nclosure_head=%s\nclosure_merge=%s\n' \
  "${closure_pr}" "${closure_head}" "${closure_merge}" \
  > "${report}/metadata.txt"

# Wait until the merge is visible on dev, while permitting a later consistency repair.
for _ in $(seq 1 90); do
  dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
  if [[ ${dev_head} == "${closure_merge}" ]]; then
    break
  fi
  sleep 10
done

dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
checkout_exact "${dev_head}" "${report}/postclosure-probe"
if ! git -C "${report}/postclosure-probe" merge-base --is-ancestor \
    "${implementation_merge}" "${dev_head}"; then
  fail implementation-ancestry \
    "the accepted Increment 34 implementation is not an ancestor of final dev"
fi
pass implementation-ancestry

if python3 "${validator}" --root "${report}/postclosure-probe" --json \
    > "${report}/postclosure-canonical-state.json" \
    2> "${report}/postclosure-canonical.err"; then
  final_dev=${dev_head}
  pass postclosure-canonical-state
else
  cat "${report}/postclosure-canonical.err" >&2 || true
  final_dev=$(create_consistency_repair "${dev_head}")
  pass postclosure-consistency-repair
fi

# Confirm the expected final commit is the live dev head.
for _ in $(seq 1 90); do
  live_dev=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
  if [[ ${live_dev} == "${final_dev}" ]]; then
    break
  fi
  sleep 10
done
live_dev=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
[[ ${live_dev} == "${final_dev}" ]] || fail final-dev-lease \
  "dev moved before the final Increment 34 audit"
pass final-dev-lease

wait_core_ci "${final_dev}" postclosure-core || fail postclosure-core-ci \
  "Core CI did not pass on the final Increment 34 closure state"
pass postclosure-core-ci
postclosure_core_run=$(jq -r '.id' "${report}/postclosure-core-state.json")

canonical_checkout "${final_dev}" "${report}/final-dev"
run_full_validation "${report}/final-dev" "${report}/final-validation" \
  > "${report}/final-validation.log" 2>&1 || {
    tail -n 600 "${report}/final-validation.log" >&2 || true
    fail final-dev-validation "final Increment 34 dev validation failed"
  }
pass final-dev-validation

echo "final_dev=${final_dev}" >> "${report}/metadata.txt"
echo "postclosure_core_ci_run=${postclosure_core_run}" >> "${report}/metadata.txt"
echo "convergence_run=${GITHUB_RUN_ID}" >> "${report}/metadata.txt"

cat > "${report}/status.json" <<EOF
{
  "schema": 1,
  "increment": 34,
  "state": "complete",
  "implementation_pr": 109,
  "accepted_head": "${implementation_head}",
  "implementation_merge": "${implementation_merge}",
  "closure_pr": 111,
  "closure_head": "${closure_head}",
  "closure_merge": "${closure_merge}",
  "final_dev": "${final_dev}",
  "postclosure_core_ci_run": ${postclosure_core_run},
  "convergence_run": ${GITHUB_RUN_ID}
}
EOF

cat > "${report}/README.md" <<EOF
# Increment 34 canonical final closure

- Implementation PR: #109
- Accepted implementation head: \`${implementation_head}\`
- Implementation merge: \`${implementation_merge}\`
- Evidence closure PR: #111
- Evidence closure exact head: \`${closure_head}\`
- Evidence closure merge: \`${closure_merge}\`
- Final validated \`dev\`: \`${final_dev}\`
- Post-closure Core CI: \`${postclosure_core_run}\`
- Canonical convergence run: \`${GITHUB_RUN_ID}\`

The final \`dev\` state contains the validated Increment 34 manifest, roadmap
revision 1.45 with Increment 34 checked, canonical dedicated-boundary and
post-merge evidence, synchronized design-gate and fixture documentation, and no
duplicate closure record.

The exact final commit passed Increment 32–34 contract and mutation suites,
complete compiler mutation discovery, Scala and native core validation,
repository style, both Increment 34 witnesses, and whitespace checks.

Increment 34 is fully closed. Residual/DAE construction, solver execution,
target legalization, and Verilog-A/Verilog-AMS procedural lowering remain owned
by later roadmap increments.

## Gates

| Gate | Result | Exit |
|---|---:|---:|
EOF
while IFS=$'\t' read -r name outcome rc; do
  printf '| `%s` | %s | %s |\n' "${name}" "${outcome}" "${rc}" \
    >> "${report}/README.md"
done < "${status}"

result_branch=audit/increment34-final-closure-results-v6
rm -rf "${report}/result"
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
  "${report}/result"
git -C "${report}/result" checkout --orphan result-root
git -C "${report}/result" rm -rf . >/dev/null 2>&1 || true
cp "${report}/README.md" "${report}/status.json" \
  "${report}/metadata.txt" "${report}/final-validation.log" \
  "${report}/postclosure-core-state.json" \
  "${report}/result/"
cp "${report}/final-dev/increment34-canonical-state.json" \
  "${report}/result/canonical-state.json"
git -C "${report}/result" add -A
git -C "${report}/result" config user.name github-actions[bot]
git -C "${report}/result" config user.email \
  41898282+github-actions[bot]@users.noreply.github.com
git -C "${report}/result" commit -m \
  "ci(increment34): record canonical final closure ${GITHUB_RUN_ID}"
git -C "${report}/result" push --force origin HEAD:"${result_branch}"

cat > "${report}/closed-pr-body.md" <<EOF
## Summary

Records the separate, evidence-only closure for Increment 34 after its implementation was accepted, merged, and independently revalidated.

- Implementation PR: #109
- Accepted implementation head: \`${implementation_head}\`
- Implementation merge: \`${implementation_merge}\`
- Closure exact head: \`${closure_head}\`
- Closure merge: \`${closure_merge}\`
- Final validated \`dev\`: \`${final_dev}\`
- Post-closure Core CI: \`${postclosure_core_run}\`
- Canonical final audit: \`${GITHUB_RUN_ID}\`

## Validation

The closure and final \`dev\` states passed Increment 32–34 contracts and mutation suites, complete compiler mutation discovery, Scala and native core validation, repository style, both Increment 34 executable witnesses, canonical evidence validation, and exact-head lease checks.

## Design gate

This PR performs only the evidence and roadmap transition required by \`docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md\`. Solver execution, target legalization, and Verilog-A/Verilog-AMS procedural lowering remain assigned to later increments.

## Checklist

- [x] Record PR #109, its accepted head, final review, and exact-head workflows.
- [x] Record the implementation merge and both post-merge validations.
- [x] Retain canonical dedicated-boundary evidence.
- [x] Advance the roadmap to revision 1.45 and check Increment 34.
- [x] Pass the owner-authored closure PR workflow matrix.
- [x] Reproduce the exact closure tree independently.
- [x] Merge the evidence-only closure to \`dev\`.
- [x] Pass post-closure Core CI and the canonical final \`dev\` audit.
EOF
gh pr edit "${closure_pr}" --repo "${repo}" \
  --body-file "${report}/closed-pr-body.md" || true
gh pr comment "${closure_pr}" --repo "${repo}" \
  --body "Increment 34 canonical closure is complete on \`${final_dev}\`. Post-closure Core CI: \`${postclosure_core_run}\`; convergence audit: \`${GITHUB_RUN_ID}\`." || true
