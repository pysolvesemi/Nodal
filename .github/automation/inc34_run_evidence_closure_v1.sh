#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
work=${2:?closure checkout required}

implementation_merge=a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49
closure_branch=increment/34-evidence-closure-v1
postmerge_core_run=33758905273
postmerge_exact_run=33759112770

require_successful_run() {
  local run_id=$1
  local status conclusion
  status=$(gh api "/repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}" --jq .status)
  conclusion=$(gh api "/repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}" --jq .conclusion)
  test "${status}" = completed
  test "${conclusion}" = success
}

require_successful_run "${postmerge_core_run}"
require_successful_run "${postmerge_exact_run}"

remote_dev=$(git -C "${work}" ls-remote origin refs/heads/dev | awk '{print $1}')
test "${remote_dev}" = "${implementation_merge}"

feature_base=$(git -C "${work}" rev-parse HEAD)
remote_feature=$(git -C "${work}" ls-remote origin \
  "refs/heads/${closure_branch}" | awk '{print $1}')
test "${feature_base}" = "${remote_feature}"

git -C "${work}" fetch --no-tags origin \
  dev:refs/remotes/origin/dev

git -C "${work}" merge-base --is-ancestor origin/dev HEAD

python3 "${controller}/.github/automation/inc34_close_payload_v1.py" \
  --root "${work}" \
  --closure-head "${feature_base}" \
  --closure-run "${GITHUB_RUN_ID}"

python3 -m py_compile \
  "${work}/scripts/check_increment32.py" \
  "${work}/scripts/check_increment33.py" \
  "${work}/scripts/check_increment34.py" \
  "${work}/tests/compiler/test_increment32.py" \
  "${work}/tests/compiler/test_increment33.py" \
  "${work}/tests/compiler/test_increment34.py"

python3 "${work}/scripts/check_increment32.py" --root "${work}"
python3 "${work}/scripts/check_increment33.py" --root "${work}"
python3 "${work}/scripts/check_increment34.py" --root "${work}"

python3 -m unittest discover \
  -s "${work}/tests/compiler" \
  -p 'test_increment32.py'
python3 -m unittest discover \
  -s "${work}/tests/compiler" \
  -p 'test_increment33.py'
python3 -m unittest discover \
  -s "${work}/tests/compiler" \
  -p 'test_increment34.py'

cd "${work}"
./nodal core scala

runtime_report=${RUNNER_TEMP}/increment34-closure-runtime.txt
construction_report=${RUNNER_TEMP}/increment34-closure-construction.txt
./mill -i examples.continuousTimeApi.runMain \
  nodal.increment34fixture.Increment34RuntimeCheck \
  "${runtime_report}"
./mill -i examples.continuousTimeApi.runMain \
  nodal.increment34fixture.Increment34ConstructionCheck \
  "${construction_report}"
grep -F 'conditional_definite=true' "${runtime_report}"
grep -F 'case_definite=true' "${runtime_report}"
grep -F 'loop_definite=true' "${runtime_report}"
grep -F 'precontrol_scope_aligned=true' "${construction_report}"
grep -F 'flat_assignments=0' "${construction_report}"

./nodal bootstrap \
  --mode prebuilt \
  --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
./nodal style bootstrap \
  --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

./nodal core native \
  --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
  --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"

./nodal check \
  --contracts-only \
  --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" \
  --base-ref origin/dev

git diff --check
test -z "$(find . -name '*.pyc' -o -name '__pycache__' | head -n 1)" || \
  find . -name '*.pyc' -delete
find . -type d -name '__pycache__' -empty -delete

git config user.name github-actions[bot]
git config user.email 41898282+github-actions[bot]@users.noreply.github.com
git add \
  docs/implementation/increment34-analog-control-flow.md \
  docs/implementation/increment34-evidence-closure.md \
  docs/implementation/increment34-exact-head-validation.md \
  docs/roadmap/nodal-development-todo.md \
  scripts/check_increment33.py \
  scripts/check_increment34.py \
  tests/compiler/fixtures/increment34/manifest.json \
  tests/compiler/test_increment33.py \
  tests/compiler/test_increment34.py

git diff --cached --check
test -n "$(git diff --cached --name-only)"
git commit -m 'docs(increment34): record validated evidence closure'
published=$(git rev-parse HEAD)

current_remote=$(git ls-remote origin \
  "refs/heads/${closure_branch}" | awk '{print $1}')
test "${current_remote}" = "${feature_base}"
git push origin "HEAD:${closure_branch}"

gh pr comment 111 --repo "${GITHUB_REPOSITORY}" \
  --body "Increment 34 evidence-closure materialization passed all staged contracts, mutation suites, Scala/native builds, semantic witnesses, and repository-style checks. Published head: \`${published}\`; validation run: \`${GITHUB_RUN_ID}\`; exact implementation merge: \`${implementation_merge}\`."

printf '%s\n' \
  "feature_base=${feature_base}" \
  "published=${published}" \
  "closure_run=${GITHUB_RUN_ID}" \
  "implementation_merge=${implementation_merge}"
