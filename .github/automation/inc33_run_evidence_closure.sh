#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
work=${2:?dev checkout required}
report=${3:?report directory required}
mkdir -p "${report}"
status=${report}/status.tsv
: > "${status}"
failed=0

expected_dev=2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8
exact_postmerge_run=33714669557
closure_branch=increment/33-evidence-closure-v2

gate() {
  local name=$1
  shift
  local log=${report}/${name}.log
  echo "===== ${name} ====="
  set +e
  "$@" >"${log}" 2>&1
  local rc=$?
  set -e
  if [[ ${rc} -eq 0 ]]; then
    printf '%s\tPASS\t0\n' "${name}" >> "${status}"
    echo "${name}: PASS"
  else
    printf '%s\tFAIL\t%s\n' "${name}" "${rc}" >> "${status}"
    echo "${name}: FAIL (${rc})"
    tail -n 220 "${log}" || true
    failed=1
  fi
}

actual_dev=$(git -C "${work}" rev-parse HEAD)
if [[ ${actual_dev} != "${expected_dev}" ]]; then
  echo "expected_dev=${expected_dev}" > "${report}/metadata.txt"
  echo "actual_dev=${actual_dev}" >> "${report}/metadata.txt"
  echo "Increment 33 evidence closure requires the exact implementation merge baseline." >&2
  exit 1
fi

run_status=$(gh api "/repos/${GITHUB_REPOSITORY}/actions/runs/${exact_postmerge_run}" --jq '.status + ":" + (.conclusion // "")')
if [[ ${run_status} != "completed:success" ]]; then
  echo "Exact post-merge run ${exact_postmerge_run} is not successful: ${run_status}" >&2
  exit 1
fi

patcher=${controller}/.github/automation/inc33_evidence_closure.py
gate patcher-compile python3 -m py_compile "${patcher}"
if [[ ${failed} -ne 0 ]]; then
  exit 1
fi

git -C "${work}" config user.name github-actions[bot]
git -C "${work}" config user.email 41898282+github-actions[bot]@users.noreply.github.com
git -C "${work}" checkout -B "${closure_branch}" "${expected_dev}"

mkdir -p "${work}/docs/implementation"
cat > "${work}/docs/implementation/increment33-evidence-closure.md" <<'EOF'
# Increment 33 — Accepted-evidence closure

This branch records the separate Increment 33 evidence-closure transition.
The complete immutable evidence stamp is added after the closure PR number is
allocated and the candidate closure tree passes its own validation.
EOF

git -C "${work}" add docs/implementation/increment33-evidence-closure.md
git -C "${work}" commit -m 'docs(increment33): open evidence closure candidate'
git -C "${work}" push --force-with-lease origin HEAD:"${closure_branch}"

pr_number=$(gh pr list \
  --repo "${GITHUB_REPOSITORY}" \
  --head "${closure_branch}" \
  --state open \
  --json number \
  --jq '.[0].number // empty')
if [[ -z ${pr_number} ]]; then
  pr_url=$(gh pr create \
    --repo "${GITHUB_REPOSITORY}" \
    --base dev \
    --head "${closure_branch}" \
    --draft \
    --title 'Increment 33 — Record accepted evidence and close roadmap' \
    --body 'Records the separate Increment 33 evidence closure after implementation PR #102, exact implementation merge validation, and post-merge Core CI. Increment 34 remains unchecked.')
  pr_number=${pr_url##*/}
fi

python3 "${patcher}" \
  --root "${work}" \
  --closure-pr "${pr_number}" \
  --validation-head "${expected_dev}" \
  --validation-run "${GITHUB_RUN_ID}"

gate initial-python bash -lc \
  "cd '${work}' && python3 -m py_compile scripts/check_increment32.py scripts/check_increment33.py tests/compiler/test_increment32.py tests/compiler/test_increment33.py"
gate initial-increment32 bash -lc \
  "cd '${work}' && python3 scripts/check_increment32.py"
gate initial-increment33 bash -lc \
  "cd '${work}' && python3 scripts/check_increment33.py"
gate initial-mutations32 bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'"
gate initial-mutations33 bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'"
gate initial-diff-check bash -lc "cd '${work}' && git diff --check"

if [[ ${failed} -ne 0 ]]; then
  gh pr comment "${pr_number}" --repo "${GITHUB_REPOSITORY}" \
    --body "Increment 33 evidence closure preparation failed in run \`${GITHUB_RUN_ID}\`; no validated closure stamp was published."
  exit 1
fi

git -C "${work}" add -A
git -C "${work}" commit -m 'docs(increment33): prepare accepted evidence closure'
validation_head=$(git -C "${work}" rev-parse HEAD)

python3 "${patcher}" \
  --root "${work}" \
  --closure-pr "${pr_number}" \
  --validation-head "${validation_head}" \
  --validation-run "${GITHUB_RUN_ID}"

git -C "${work}" add -A
git -C "${work}" commit -m 'docs(increment33): stamp closure validation evidence'
final_head=$(git -C "${work}" rev-parse HEAD)

cat > "${report}/metadata.txt" <<EOF
workflow_run=${GITHUB_RUN_ID}
controller_sha=${GITHUB_SHA}
implementation_head=ea7f7da51e85ba275dac71db7823ba0223f8d4ac
implementation_merge=${expected_dev}
post_merge_core_ci_run=33605996500
exact_post_merge_validation_run=${exact_postmerge_run}
closure_pr=${pr_number}
closure_validation_head=${validation_head}
closure_final_head=${final_head}
EOF

gate final-python bash -lc \
  "cd '${work}' && python3 -m py_compile scripts/check_increment32.py scripts/check_increment33.py tests/compiler/test_increment32.py tests/compiler/test_increment33.py"
gate final-increment32 bash -lc \
  "cd '${work}' && python3 scripts/check_increment32.py --compile"
gate final-increment33 bash -lc \
  "cd '${work}' && python3 scripts/check_increment33.py --compile"
gate final-mutations32 bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'"
gate final-mutations33 bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'"
gate final-scala bash -lc "cd '${work}' && ./nodal core scala"
gate native-bootstrap bash -lc \
  "cd '${work}' && ./nodal bootstrap --mode prebuilt --prefix '${RUNNER_TEMP}/nodal-native-toolchain'"
gate lint-bootstrap bash -lc \
  "cd '${work}' && ./nodal style bootstrap --prefix '${RUNNER_TEMP}/nodal-lint-toolchain'"
if [[ -d ${RUNNER_TEMP}/nodal-native-toolchain && -d ${RUNNER_TEMP}/nodal-lint-toolchain ]]; then
  gate final-native bash -lc \
    "cd '${work}' && ./nodal core native --toolchain '${RUNNER_TEMP}/nodal-native-toolchain' --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
  gate final-style bash -lc \
    "cd '${work}' && ./nodal style check --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
else
  failed=1
fi
gate final-diff-check bash -lc "cd '${work}' && git diff --check"

if [[ ${failed} -eq 0 ]]; then
  git -C "${work}" push origin HEAD:"${closure_branch}"
  gh pr comment "${pr_number}" --repo "${GITHUB_REPOSITORY}" \
    --body "Increment 33 evidence closure candidate passed exact state-aware checkers, mutation suites, Scala, native compiler, style, and witness validation in run \`${GITHUB_RUN_ID}\`. Exact closure head: \`${final_head}\`; recorded validation head: \`${validation_head}\`."
else
  gh pr comment "${pr_number}" --repo "${GITHUB_REPOSITORY}" \
    --body "Increment 33 evidence closure validation failed in run \`${GITHUB_RUN_ID}\`; the validated closure commits were not pushed."
fi

{
  echo '# Increment 33 evidence closure controller'
  echo
  echo '```text'
  cat "${report}/metadata.txt"
  echo '```'
  echo
  echo '| Gate | Result | Exit |'
  echo '|---|---:|---:|'
  while IFS=$'\t' read -r name outcome rc; do
    printf '| `%s` | %s | %s |\n' "${name}" "${outcome}" "${rc}"
  done < "${status}"
} > "${report}/README.md"

result_branch=automation/inc33-evidence-results-20260903
rm -rf result
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" result
git -C result checkout --orphan result-root
git -C result rm -rf . >/dev/null 2>&1 || true
cp -R "${report}/." result/
git -C result add -A
git -C result config user.name github-actions[bot]
git -C result config user.email 41898282+github-actions[bot]@users.noreply.github.com
git -C result commit -m "ci(increment33): record evidence closure ${GITHUB_RUN_ID}"
git -C result push --force origin HEAD:"${result_branch}"

if [[ ${failed} -ne 0 ]]; then
  exit 1
fi
