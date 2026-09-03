#!/usr/bin/env bash
set -u

controller=${1:?controller checkout required}
work=${2:?feature checkout required}
report=${3:?report directory required}
mkdir -p "${report}"
status=${report}/status.tsv
: > "${status}"
failed=0

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
    tail -n 280 "${log}" || true
    failed=1
  fi
}

feature_base=$(git -C "${work}" rev-parse HEAD)
git -C "${work}" fetch --no-tags origin \
  dev:refs/remotes/origin/dev \
  increment/34-analog-control-flow-v1:refs/remotes/origin/increment/34-analog-control-flow-v1
remote_feature=$(git -C "${work}" rev-parse origin/increment/34-analog-control-flow-v1)
dev_head=$(git -C "${work}" rev-parse origin/dev)

cat > "${report}/metadata.txt" <<EOF
workflow_run=${GITHUB_RUN_ID}
controller_sha=${GITHUB_SHA}
feature_base=${feature_base}
remote_feature=${remote_feature}
validated_dev_head=${dev_head}
feature_branch=increment/34-analog-control-flow-v1
review_focus=canonical-owner-and-pre-control-scope-alignment
EOF

if [[ ${feature_base} != "${remote_feature}" ]]; then
  echo 'feature branch moved before owner/scope review' >&2
  exit 1
fi

gate predecessor-ancestry bash -lc \
  "cd '${work}' && git merge-base --is-ancestor origin/dev HEAD"

gate prerequisite-v12 bash -lc \
  "cd '${work}' && grep -F 'native_unreachable_structural_reference_validation' tests/compiler/fixtures/increment34/manifest.json && grep -F 'nested_declaration_locality' tests/compiler/fixtures/increment34/manifest.json"

patcher=${controller}/.github/automation/inc34_owner_scope_review_v13.py
gate patcher-compile python3 -m py_compile "${patcher}"
if [[ ${failed} -eq 0 ]]; then
  gate apply-owner-scope-review python3 "${patcher}" --root "${work}"
fi

git -C "${work}" config user.name github-actions[bot]
git -C "${work}" config user.email 41898282+github-actions[bot]@users.noreply.github.com

gate native-bootstrap bash -lc \
  "cd '${work}' && ./nodal bootstrap --mode prebuilt --prefix '${RUNNER_TEMP}/nodal-native-toolchain'"
gate lint-bootstrap bash -lc \
  "cd '${work}' && ./nodal style bootstrap --prefix '${RUNNER_TEMP}/nodal-lint-toolchain'"
gate cpp-format bash -lc \
  "cd '${work}' && python3 scripts/run_clang_tools.py format --fix --toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
gate scala-format bash -lc \
  "cd '${work}' && ./mill -i mill.scalalib.scalafmt/reformatAll"

gate checker-python bash -lc \
  "cd '${work}' && python3 -m py_compile scripts/check_increment32.py scripts/check_increment33.py scripts/check_increment34.py tests/compiler/test_increment32.py tests/compiler/test_increment33.py tests/compiler/test_increment34.py"
gate increment32-contract bash -lc \
  "cd '${work}' && python3 scripts/check_increment32.py"
gate increment33-contract bash -lc \
  "cd '${work}' && python3 scripts/check_increment33.py"
gate increment34-contract bash -lc \
  "cd '${work}' && python3 scripts/check_increment34.py"
gate increment32-mutations bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'"
gate increment33-mutations bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment33.py'"
gate increment34-mutations bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment34.py'"
gate diff-check bash -lc "cd '${work}' && git diff --check"

gate scala-core bash -lc "cd '${work}' && ./nodal core scala"
gate runtime-witness bash -lc \
  "cd '${work}' && output='${RUNNER_TEMP}/increment34-runtime-v13.txt' && ./mill -i examples.continuousTimeApi.runMain nodal.increment34fixture.Increment34RuntimeCheck \"\${output}\" && grep -F 'nested_nonlocal=NODAL-ANALOG-034-014' \"\${output}\""
gate construction-witness bash -lc \
  "cd '${work}' && output='${RUNNER_TEMP}/increment34-construction-v13.txt' && ./mill -i examples.continuousTimeApi.runMain nodal.increment34fixture.Increment34ConstructionCheck \"\${output}\" && grep -F 'precontrol_scope_aligned=true' \"\${output}\" && grep -F 'empty_owner=NODAL-ANALOG-034-001' \"\${output}\" && grep -F 'padded_owner=NODAL-ANALOG-034-001' \"\${output}\""

if [[ -d ${RUNNER_TEMP}/nodal-native-toolchain && \
      -d ${RUNNER_TEMP}/nodal-lint-toolchain ]]; then
  gate native-core bash -lc \
    "cd '${work}' && ./nodal core native --toolchain '${RUNNER_TEMP}/nodal-native-toolchain' --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
  nodalc=$(find "${work}/out" -type f -name nodalc -perm -u+x | head -n 1)
  if [[ -n ${nodalc} ]]; then
    gate direct-unreachable-reference-diagnostic bash -lc \
      "cd '${work}' && set +e; '${nodalc}' core/compiler/test/IR/analog-control-flow-invalid-unreachable-reference.mlir > '${report}/direct-unreachable-reference.out' 2>&1; rc=\$?; set -e; test \${rc} -ne 0; grep -F 'NODAL-ANALOG-034-014' '${report}/direct-unreachable-reference.out'"
  else
    printf '%s\tFAIL\t1\n' direct-unreachable-reference-diagnostic >> "${status}"
    failed=1
  fi
  gate repository-style bash -lc \
    "cd '${work}' && ./nodal style check --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
else
  printf '%s\tSKIP\t1\n' native-core >> "${status}"
  printf '%s\tSKIP\t1\n' direct-unreachable-reference-diagnostic >> "${status}"
  printf '%s\tSKIP\t1\n' repository-style >> "${status}"
  failed=1
fi

if [[ ${failed} -eq 0 ]]; then
  current_remote=$(git -C "${work}" ls-remote origin \
    refs/heads/increment/34-analog-control-flow-v1 | awk '{print $1}')
  if [[ ${current_remote} != "${feature_base}" ]]; then
    printf '%s\tFAIL\t1\n' lease-guard >> "${status}"
    echo "feature_moved_to=${current_remote}" >> "${report}/metadata.txt"
    failed=1
  else
    printf '%s\tPASS\t0\n' lease-guard >> "${status}"
  fi
fi

published=
if [[ ${failed} -eq 0 ]]; then
  git -C "${work}" add -A
  if ! git -C "${work}" diff --cached --quiet; then
    git -C "${work}" commit -m \
      'fix(increment34): canonicalize owners and align lexical scopes'
  fi
  published=$(git -C "${work}" rev-parse HEAD)
  git -C "${work}" push origin HEAD:increment/34-analog-control-flow-v1
  echo "published_sha=${published}" >> "${report}/metadata.txt"
fi

git -C "${work}" diff --stat origin/dev...HEAD > "${report}/diff-stat.txt" || true
git -C "${work}" status --short --branch > "${report}/git-status.txt"

{
  echo '# Increment 34 owner and scope review v13'
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
  echo
  echo '## Feature diff relative to validated dev'
  echo '```text'
  cat "${report}/diff-stat.txt"
  echo '```'
} > "${report}/README.md"

result_branch=automation/inc34-owner-scope-results-v13
rm -rf result
git clone --no-checkout \
  "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" result
git -C result checkout --orphan result-root
git -C result rm -rf . >/dev/null 2>&1 || true
cp -R "${report}/." result/
git -C result add -A
git -C result config user.name github-actions[bot]
git -C result config user.email 41898282+github-actions[bot]@users.noreply.github.com
git -C result commit -m \
  "ci(increment34): record owner and scope review v13 ${GITHUB_RUN_ID}"
git -C result push --force origin HEAD:"${result_branch}"

if [[ ${failed} -eq 0 ]]; then
  gh pr comment 109 --repo "${GITHUB_REPOSITORY}" \
    --body "Increment 34 owner/scope review passed. Exact published head: \`${published}\`; run: \`${GITHUB_RUN_ID}\`."
  exit 0
fi

gh pr comment 109 --repo "${GITHUB_REPOSITORY}" \
  --body "Increment 34 owner/scope review did not publish because a staged gate failed. Logs: branch \`${result_branch}\`, run \`${GITHUB_RUN_ID}\`."
exit 1
