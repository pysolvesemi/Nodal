#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
pr=111
closure_branch=increment/34-evidence-closure-v1
implementation_head=207fd1b580e9428e9948cd4e4bd8f2060fde4b79
implementation_merge=a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49
owner_marker=docs/implementation/increment34-owner-exact-head-trigger-v7.md
validator=${controller}/.github/automation/inc34_validate_canonical_closure_v6.py
reconciler=${controller}/.github/automation/inc34_poll_exact_workflows_v6b.py
materializer=${controller}/.github/automation/inc34_evidence_closure_v1.py
status=${report}/status.tsv
: > "${status}"

record() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "${status}"; }
pass() { record "$1" PASS 0; echo "$1: PASS"; }
fail() { record "$1" FAIL 1; echo "$2" >&2; publish_state failed "$2" || true; exit 1; }

publish_state() {
  local phase=$1
  local detail=${2:-}
  local branch=audit/increment34-authoritative-v7-state
  local root=${report}/state
  rm -rf "${root}"
  git clone --no-checkout \
    "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${root}"
  git -C "${root}" checkout --orphan state-root
  git -C "${root}" rm -rf . >/dev/null 2>&1 || true
  cat > "${root}/status.json" <<EOF
{
  "schema": 1,
  "increment": 34,
  "phase": "${phase}",
  "detail": $(jq -Rn --arg value "${detail}" '$value'),
  "workflow_run": ${GITHUB_RUN_ID},
  "controller_sha": "${GITHUB_SHA}",
  "recorded_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
  git -C "${root}" add status.json
  git -C "${root}" config user.name github-actions[bot]
  git -C "${root}" config user.email 41898282+github-actions[bot]@users.noreply.github.com
  git -C "${root}" commit -m "ci(increment34): record v7 phase ${phase}"
  git -C "${root}" push --force origin HEAD:"${branch}"
}

cancel_obsolete() {
  local runs
  runs=$(gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100")
  mapfile -t ids < <(
    jq -r --arg current "${GITHUB_RUN_ID}" '
      .workflow_runs[]
      | select(.status != "completed")
      | select((.id|tostring) != $current)
      | select(.name | startswith("Increment 34"))
      | .id
    ' <<< "${runs}"
  )
  for id in "${ids[@]}"; do
    gh api --method POST "repos/${repo}/actions/runs/${id}/cancel" >/dev/null || true
  done
  sleep 15
  echo "cancelled=${#ids[@]}" > "${report}/quiesce.txt"
}

checkout_exact() {
  local sha=$1
  local destination=$2
  rm -rf "${destination}"
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${destination}"
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
  local deadline=$((SECONDS + 10800))
  local stable=0
  local previous=
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${sha}&per_page=100" \
      > "${report}/matrix-runs.json"
    python3 "${reconciler}" --input "${report}/matrix-runs.json" \
      --minimum-count 24 > "${report}/matrix-state.json"
    if [[ $(jq '.bad | length' "${report}/matrix-state.json") -gt 0 || \
          $(jq '.unsupported | length' "${report}/matrix-state.json") -gt 0 ]]; then
      cat "${report}/matrix-state.json" >&2
      return 1
    fi
    if [[ $(jq -r '.acceptable' "${report}/matrix-state.json") == true ]]; then
      signature=$(sha256sum "${report}/matrix-state.json" | awk '{print $1}')
      if [[ ${signature} == "${previous}" ]]; then stable=$((stable + 1)); else stable=1; previous=${signature}; fi
      if (( stable >= 3 )); then return 0; fi
    else
      stable=0
      previous=
    fi
    sleep 20
  done
  cat "${report}/matrix-state.json" >&2 || true
  return 1
}

wait_core_ci() {
  local sha=$1
  local deadline=$((SECONDS + 7200))
  while (( SECONDS < deadline )); do
    gh api "repos/${repo}/actions/runs?head_sha=${sha}&per_page=100" \
      > "${report}/postmerge-runs.json"
    core=$(jq -c '
      [.workflow_runs[] | select(.name == "Core CI")]
      | sort_by(.id) | reverse | .[0] // empty
    ' "${report}/postmerge-runs.json")
    if [[ -n ${core} ]]; then
      status_value=$(jq -r '.status' <<< "${core}")
      conclusion=$(jq -r '.conclusion // ""' <<< "${core}")
      if [[ ${status_value} == completed ]]; then
        [[ ${conclusion} == success ]] || return 1
        jq . <<< "${core}" > "${report}/postmerge-core.json"
        return 0
      fi
    fi
    sleep 20
  done
  return 1
}

cancel_obsolete
pass obsolete-controller-quiesce
publish_state quiesced "Only authoritative v7 remains active."

pr_json=$(gh api "repos/${repo}/pulls/${pr}")
if [[ $(jq -r '.merged' <<< "${pr_json}") == true ]]; then
  merge_sha=$(jq -r '.merge_commit_sha' <<< "${pr_json}")
  dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
  [[ ${dev_head} == "${merge_sha}" ]] || fail already-merged-dev-head \
    "PR #111 is merged but its merge is not the current dev head"
  checkout_exact "${dev_head}" "${report}/already-merged"
  python3 "${validator}" --root "${report}/already-merged" --json \
    > "${report}/already-merged-state.json" || fail already-merged-canonical \
      "PR #111 was merged without the canonical Increment 34 evidence state"
  pass already-merged-canonical
  wait_core_ci "${dev_head}" || fail already-merged-core-ci \
    "Core CI did not pass on the already-merged closure"
  pass already-merged-core-ci
  run_full_validation "${report}/already-merged" "${report}/already-merged-validation" \
    > "${report}/already-merged-validation.log" 2>&1 || {
      tail -n 500 "${report}/already-merged-validation.log" >&2 || true
      fail already-merged-validation "final validation failed on already-merged closure"
    }
  pass already-merged-validation
  final_dev=${dev_head}
  owner_head=$(jq -r '.head.sha' <<< "${pr_json}")
  closure_head=${owner_head}
else
  if [[ $(jq -r '.state' <<< "${pr_json}") != open ]]; then
    gh pr reopen "${pr}" --repo "${repo}"
  fi
  gh pr ready "${pr}" --repo "${repo}" --undo || true

  dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
  [[ ${dev_head} == "${implementation_merge}" ]] || fail implementation-base \
    "dev is not the accepted Increment 34 implementation merge"
  feature_head=$(gh api "repos/${repo}/branches/increment/34-analog-control-flow-v1" --jq '.commit.sha')
  [[ ${feature_head} == "${implementation_head}" ]] || fail implementation-head \
    "accepted Increment 34 feature head moved"
  pass implementation-base
  pass implementation-head

  work=${report}/closure-work
  rm -rf "${work}"
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${work}"
  git -C "${work}" checkout "${closure_branch}"
  remote_start=$(git -C "${work}" rev-parse HEAD)
  git -C "${work}" fetch origin dev
  git -C "${work}" reset --hard "${implementation_merge}"
  rm -f "${work}/${owner_marker}"

  python3 "${materializer}" --root "${work}" --phase candidate
  for followup in 1 2 3 4 5; do
    python3 "${controller}/.github/automation/inc34_evidence_closure_followup_v${followup}.py" \
      --root "${work}"
  done

  "${work}/nodal" style bootstrap --prefix "${report}/stage-lint"
  ruff=$(find "${report}/stage-lint" -type f -name ruff -perm -u+x | head -n 1)
  [[ -n ${ruff} ]] || fail stage-format "locked Ruff executable was not found"
  "${ruff}" format \
    "${work}/scripts/check_increment32.py" \
    "${work}/scripts/check_increment33.py" \
    "${work}/scripts/check_increment34.py" \
    "${work}/tests/compiler/test_increment32.py" \
    "${work}/tests/compiler/test_increment33.py" \
    "${work}/tests/compiler/test_increment34.py"
  pass stage-format

  git -C "${work}" config user.name github-actions[bot]
  git -C "${work}" config user.email 41898282+github-actions[bot]@users.noreply.github.com
  git -C "${work}" add -A
  git -C "${work}" commit -m 'docs(increment34): materialize canonical evidence closure'
  candidate_head=$(git -C "${work}" rev-parse HEAD)
  python3 "${materializer}" --root "${work}" --phase stamp \
    --closure-head "${candidate_head}" --closure-run "${GITHUB_RUN_ID}"

  (
    cd "${work}"
    python3 scripts/check_increment32.py
    python3 scripts/check_increment33.py
    python3 scripts/check_increment34.py
    python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'
    python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'
    python3 -m unittest discover -s tests/compiler -p 'test_increment34.py'
    python3 -m unittest discover -s tests/compiler -p 'test_*.py'
    git diff --check
  ) > "${report}/stage-validation.log" 2>&1 || {
    tail -n 500 "${report}/stage-validation.log" >&2 || true
    fail stage-validation "fast closure validation failed"
  }
  pass stage-validation

  git -C "${work}" add -A
  git -C "${work}" commit -m 'docs(increment34): stamp closure validation evidence'
  published_head=$(git -C "${work}" rev-parse HEAD)
  python3 "${validator}" --root "${work}" --json > "${report}/published-canonical.json" \
    || fail published-canonical "materialized closure is not canonical"
  test -z "$(git -C "${work}" status --porcelain)" || fail published-clean \
    "materialized closure worktree is dirty"
  pass published-canonical
  pass published-clean

  current_remote=$(git -C "${work}" ls-remote origin "refs/heads/${closure_branch}" | awk '{print $1}')
  [[ ${current_remote} == "${remote_start}" ]] || fail closure-stage-lease \
    "closure branch moved during v7 materialization"
  git -C "${work}" push \
    --force-with-lease="refs/heads/${closure_branch}:${remote_start}" \
    origin HEAD:"${closure_branch}"
  pass closure-stage-publication

  cat > "${report}/pr-body.md" <<EOF
## Summary

Records the separate, evidence-only closure for Increment 34 after implementation PR #109 was accepted, merged, and independently revalidated.

- Accepted implementation head: \`${implementation_head}\`
- Final reviewed head: \`54d8523715a86e1780263b6f5227def2f0977833\`
- Exact-head matrix: 26 successful workflows
- Dedicated boundary workflow: \`33732868285\`
- Exact-head Core CI: \`33732864482\`
- Implementation merge: \`${implementation_merge}\`
- Post-merge Core CI: \`33758905273\`
- Exact post-merge validation: \`33759112770\`
- Closure validation candidate: \`${candidate_head}\`
- Closure validation run: \`${GITHUB_RUN_ID}\`

## Validation

The canonical closure candidate passed Increment 32–34 contracts and mutations, complete compiler mutation discovery, formatting, canonical evidence checks, and a lease-protected publication gate. An owner-authored trigger commit will establish the exact PR head for the complete workflow matrix and independent Scala/native reproduction.

## Design gate

This PR changes only evidence, checker state transitions, roadmap state, and synchronized closure documentation required by \`docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md\`. Solver execution, target legalization, and Verilog-A/Verilog-AMS procedural lowering remain assigned to later increments.

## Checklist

- [x] Record PR #109, accepted implementation head, review head, and exact-head workflows.
- [x] Record implementation merge and both post-merge validations.
- [x] Retain the dedicated boundary workflow evidence.
- [x] Advance roadmap revision 1.45 and check Increment 34.
- [x] Pass canonical closure materialization and mutation checks.
- [ ] Receive the owner-authored exact-head trigger.
- [ ] Pass the complete owner-authored closure PR matrix.
- [ ] Reproduce the exact closure tree independently.
- [ ] Merge PR #111 through the owner connection.
- [ ] Pass post-closure Core CI and final \`dev\` validation.
EOF
  gh pr edit "${pr}" --repo "${repo}" --body-file "${report}/pr-body.md"
  gh pr ready "${pr}" --repo "${repo}" || true
  publish_state awaiting-owner-trigger "${published_head}"

  deadline=$((SECONDS + 7200))
  owner_head=
  while (( SECONDS < deadline )); do
    live_head=$(gh api "repos/${repo}/pulls/${pr}" --jq '.head.sha')
    if [[ ${live_head} != "${published_head}" ]]; then
      git -C "${work}" fetch origin "${closure_branch}"
      candidate=$(git -C "${work}" rev-parse "origin/${closure_branch}")
      parent_line=$(git -C "${work}" rev-list --parents -n 1 "${candidate}")
      read -r commit parent extra <<< "${parent_line}"
      changed=$(git -C "${work}" diff --name-only "${published_head}..${candidate}")
      author=$(git -C "${work}" show -s --format='%an <%ae>' "${candidate}")
      if [[ ${parent} == "${published_head}" && -z ${extra:-} && \
            ${changed} == "${owner_marker}" && \
            ${author} != *github-actions* ]]; then
        marker_text=$(git -C "${work}" show "${candidate}:${owner_marker}")
        if grep -Fq 'Increment 34 owner-authored exact-head trigger v7' <<< "${marker_text}"; then
          owner_head=${candidate}
          break
        fi
      fi
      fail owner-trigger-shape "unexpected commit appeared on the closure branch"
    fi
    sleep 15
  done
  [[ -n ${owner_head} ]] || fail owner-trigger-timeout \
    "owner-authored Increment 34 exact-head trigger was not received"
  pass owner-trigger

  gh pr ready "${pr}" --repo "${repo}" --undo || true
  publish_state validating-owner-head "${owner_head}"
  poll_matrix "${owner_head}" || fail owner-head-matrix \
    "owner-authored closure PR matrix failed or did not settle"
  pass owner-head-matrix

  checkout_exact "${owner_head}" "${report}/owner-exact"
  python3 "${validator}" --root "${report}/owner-exact" --json \
    > "${report}/owner-canonical.json" || fail owner-head-canonical \
      "owner-authored closure head is not canonical"
  pass owner-head-canonical
  run_full_validation "${report}/owner-exact" "${report}/owner-validation" \
    > "${report}/owner-validation.log" 2>&1 || {
      tail -n 600 "${report}/owner-validation.log" >&2 || true
      fail owner-head-validation "owner-authored exact closure validation failed"
    }
  pass owner-head-validation

  live=$(gh api "repos/${repo}/pulls/${pr}")
  [[ $(jq -r '.head.sha' <<< "${live}") == "${owner_head}" ]] \
    || fail owner-head-lease "PR #111 moved after exact validation"
  [[ $(jq -r '.base.sha' <<< "${live}") == "${implementation_merge}" ]] \
    || fail owner-base-lease "dev moved after exact closure validation"
  pass owner-head-lease
  pass owner-base-lease

  gh pr ready "${pr}" --repo "${repo}" || true
  publish_state ready-for-owner-merge "${owner_head}"
  gh pr comment "${pr}" --repo "${repo}" \
    --body "Increment 34 closure head \`${owner_head}\` passed the complete exact-head matrix and independent Scala/native validation. It is ready for the owner-connected SHA-locked merge."

  deadline=$((SECONDS + 7200))
  merge_sha=
  while (( SECONDS < deadline )); do
    live=$(gh api "repos/${repo}/pulls/${pr}")
    if [[ $(jq -r '.merged' <<< "${live}") == true ]]; then
      merge_sha=$(jq -r '.merge_commit_sha' <<< "${live}")
      break
    fi
    live_head=$(jq -r '.head.sha' <<< "${live}")
    [[ ${live_head} == "${owner_head}" ]] || fail external-merge-lease \
      "PR #111 moved while awaiting the owner merge"
    sleep 15
  done
  [[ -n ${merge_sha} ]] || fail owner-merge-timeout \
    "owner-connected merge for PR #111 was not observed"
  pass owner-merge
  closure_head=${owner_head}

  for _ in $(seq 1 90); do
    dev_head=$(gh api "repos/${repo}/branches/dev" --jq '.commit.sha')
    [[ ${dev_head} == "${merge_sha}" ]] && break
    sleep 10
  done
  [[ ${dev_head} == "${merge_sha}" ]] || fail closure-dev-head \
    "PR #111 merge did not become the dev head"
  pass closure-dev-head

  wait_core_ci "${merge_sha}" || fail postclosure-core-ci \
    "Core CI did not pass on the owner-connected closure merge"
  pass postclosure-core-ci
  checkout_exact "${merge_sha}" "${report}/final-dev"
  python3 "${validator}" --root "${report}/final-dev" --json \
    > "${report}/final-canonical.json" || fail final-canonical \
      "final dev is not in the canonical Increment 34 closure state"
  pass final-canonical
  run_full_validation "${report}/final-dev" "${report}/final-validation" \
    > "${report}/final-validation.log" 2>&1 || {
      tail -n 700 "${report}/final-validation.log" >&2 || true
      fail final-validation "final dev Increment 34 validation failed"
    }
  pass final-validation
  final_dev=${merge_sha}
fi

postmerge_core=$(jq -r '.id // 0' "${report}/postmerge-core.json" 2>/dev/null || echo 0)
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
  "closure_merge": "${final_dev}",
  "final_dev": "${final_dev}",
  "postclosure_core_ci_run": ${postmerge_core},
  "authoritative_run": ${GITHUB_RUN_ID}
}
EOF

cat > "${report}/README.md" <<EOF
# Increment 34 authoritative closure v7

- Implementation PR: #109
- Accepted implementation head: \`${implementation_head}\`
- Implementation merge: \`${implementation_merge}\`
- Evidence closure PR: #111
- Owner-authored closure head: \`${closure_head}\`
- Final validated \`dev\`: \`${final_dev}\`
- Post-closure Core CI: \`${postmerge_core}\`
- Authoritative closure run: \`${GITHUB_RUN_ID}\`

The owner-authored exact closure head passed the complete PR workflow matrix and
independent Increment 32–34 contract, mutation, Scala, native, style, and witness
validation. The owner-connected merge then triggered Core CI, and the exact final
\`dev\` commit passed the canonical evidence check and complete validation again.

Increment 34 is fully closed. Residual/DAE construction, solver execution,
target legalization, and Verilog-A/Verilog-AMS procedural lowering remain owned
by later increments.

## Gates

| Gate | Result | Exit |
|---|---:|---:|
EOF
while IFS=$'\t' read -r name outcome rc; do
  printf '| `%s` | %s | %s |\n' "${name}" "${outcome}" "${rc}" >> "${report}/README.md"
done < "${status}"

result_branch=audit/increment34-authoritative-closure-v7
rm -rf "${report}/result"
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" "${report}/result"
git -C "${report}/result" checkout --orphan result-root
git -C "${report}/result" rm -rf . >/dev/null 2>&1 || true
cp "${report}/README.md" "${report}/status.json" "${report}/result/"
cp "${report}/final-canonical.json" "${report}/result/canonical-state.json" 2>/dev/null || true
cp "${report}/final-validation.log" "${report}/result/" 2>/dev/null || true
git -C "${report}/result" add -A
git -C "${report}/result" config user.name github-actions[bot]
git -C "${report}/result" config user.email 41898282+github-actions[bot]@users.noreply.github.com
git -C "${report}/result" commit -m \
  "ci(increment34): record authoritative closure v7 ${GITHUB_RUN_ID}"
git -C "${report}/result" push --force origin HEAD:"${result_branch}"
publish_state complete "${final_dev}"

gh pr comment 111 --repo "${repo}" \
  --body "Increment 34 authoritative closure v7 passed on final dev \`${final_dev}\`. Post-closure Core CI: \`${postmerge_core}\`; audit run: \`${GITHUB_RUN_ID}\`." || true
