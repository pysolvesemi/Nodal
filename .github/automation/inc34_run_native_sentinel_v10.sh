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
    tail -n 220 "${log}" || true
    failed=1
  fi
}

feature_base=$(git -C "${work}" rev-parse HEAD)
cat > "${report}/metadata.txt" <<EOF
workflow_run=${GITHUB_RUN_ID}
controller_sha=${GITHUB_SHA}
feature_base=${feature_base}
feature_branch=increment/34-analog-control-flow-v1
EOF

patcher=${controller}/.github/automation/inc34_native_sentinel_v10.py
gate patcher-compile python3 -m py_compile "${patcher}"
if [[ ${failed} -eq 0 ]]; then
  gate apply-sentinel-hardening python3 "${patcher}" --root "${work}"
fi

gate native-bootstrap bash -lc \
  "cd '${work}' && ./nodal bootstrap --mode prebuilt --prefix '${RUNNER_TEMP}/nodal-native-toolchain'"
gate lint-bootstrap bash -lc \
  "cd '${work}' && ./nodal style bootstrap --prefix '${RUNNER_TEMP}/nodal-lint-toolchain'"

if [[ -d ${RUNNER_TEMP}/nodal-lint-toolchain ]]; then
  gate cpp-format bash -lc \
    "clang_format=\$(find '${RUNNER_TEMP}/nodal-lint-toolchain' -type f -name clang-format -perm -u+x | head -n 1); test -n \"\${clang_format}\"; \"\${clang_format}\" -i '${work}/core/compiler/lib/Dialect/Nodal/NodalOps.cpp'"
else
  printf '%s\tSKIP\t1\n' cpp-format >> "${status}"
  failed=1
fi

gate checker-python bash -lc \
  "cd '${work}' && python3 -m py_compile scripts/check_increment34.py tests/compiler/test_increment34.py"
gate increment33-contract bash -lc \
  "cd '${work}' && python3 scripts/check_increment33.py"
gate increment34-contract bash -lc \
  "cd '${work}' && python3 scripts/check_increment34.py"
gate increment34-mutations bash -lc \
  "cd '${work}' && python3 -m unittest discover -s tests/compiler -p 'test_increment34.py'"
gate diff-check bash -lc "cd '${work}' && git diff --check"
gate scala-core bash -lc "cd '${work}' && ./nodal core scala"

if [[ -d ${RUNNER_TEMP}/nodal-native-toolchain && \
      -d ${RUNNER_TEMP}/nodal-lint-toolchain ]]; then
  gate native-core bash -lc \
    "cd '${work}' && ./nodal core native --toolchain '${RUNNER_TEMP}/nodal-native-toolchain' --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
else
  printf '%s\tSKIP\t1\n' native-core >> "${status}"
  failed=1
fi

gate runtime-witness bash -lc \
  "cd '${work}' && output='${RUNNER_TEMP}/increment34-runtime.txt' && ./mill -i examples.continuousTimeApi.runMain nodal.increment34fixture.Increment34RuntimeCheck \"\${output}\" && grep -F 'conditional_definite=true' \"\${output}\" && grep -F 'case_definite=true' \"\${output}\" && grep -F 'loop_definite=true' \"\${output}\""
gate construction-witness bash -lc \
  "cd '${work}' && output='${RUNNER_TEMP}/increment34-construction.txt' && ./mill -i examples.continuousTimeApi.runMain nodal.increment34fixture.Increment34ConstructionCheck \"\${output}\" && grep -F 'public_conditional_snapshots=1' \"\${output}\" && grep -F 'public_case_snapshots=1' \"\${output}\" && grep -F 'flat_assignments=0' \"\${output}\""

if [[ -d ${work}/out ]]; then
  gate direct-sentinel-diagnostics bash -lc \
    "cd '${work}' && nodalc=\$(find out -type f -name nodalc -perm -u+x | head -n 1) && test -n \"\${nodalc}\" && for item in runtime-static-sentinel:003 else-static-sentinel:003 loop-static-sentinel:008; do fixture=\${item%%:*}; code=\${item##*:}; set +e; \"\${nodalc}\" \"core/compiler/test/IR/analog-control-flow-invalid-\${fixture}.mlir\" >'${RUNNER_TEMP}/'\"\${fixture}\"'.out' 2>'${RUNNER_TEMP}/'\"\${fixture}\"'.err'; rc=\$?; set -e; test \"\${rc}\" -ne 0; grep -F \"NODAL-ANALOG-034-\${code}\" '${RUNNER_TEMP}/'\"\${fixture}\"'.err'; done"
else
  printf '%s\tSKIP\t1\n' direct-sentinel-diagnostics >> "${status}"
  failed=1
fi

if [[ -d ${RUNNER_TEMP}/nodal-lint-toolchain ]]; then
  gate repository-style bash -lc \
    "cd '${work}' && ./nodal style check --lint-toolchain '${RUNNER_TEMP}/nodal-lint-toolchain'"
else
  printf '%s\tSKIP\t1\n' repository-style >> "${status}"
  failed=1
fi

git -C "${work}" diff --binary > "${report}/candidate.patch"
git -C "${work}" diff --stat > "${report}/diff-stat.txt"
git -C "${work}" status --short --branch > "${report}/git-status.txt"

if [[ ${failed} -eq 0 ]]; then
  git -C "${work}" fetch --no-tags origin \
    increment/34-analog-control-flow-v1:refs/remotes/origin/increment/34-analog-control-flow-v1
  remote=$(git -C "${work}" rev-parse origin/increment/34-analog-control-flow-v1)
  if [[ ${feature_base} != "${remote}" ]]; then
    printf '%s\tFAIL\t1\n' lease-guard >> "${status}"
    echo "feature_moved_to=${remote}" >> "${report}/metadata.txt"
    failed=1
  else
    printf '%s\tPASS\t0\n' lease-guard >> "${status}"
  fi
fi

published=
if [[ ${failed} -eq 0 ]]; then
  git -C "${work}" config user.name github-actions[bot]
  git -C "${work}" config user.email 41898282+github-actions[bot]@users.noreply.github.com
  git -C "${work}" add -A
  if git -C "${work}" diff --cached --quiet; then
    published=$(git -C "${work}" rev-parse HEAD)
  else
    git -C "${work}" commit -m \
      'fix(increment34): canonicalize native staging sentinels'
    published=$(git -C "${work}" rev-parse HEAD)
    git -C "${work}" push origin HEAD:increment/34-analog-control-flow-v1
  fi
  echo "published_sha=${published}" >> "${report}/metadata.txt"
fi

{
  echo '# Increment 34 canonical staging sentinels v10'
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
  echo '## Candidate diff'
  echo '```text'
  cat "${report}/diff-stat.txt"
  echo '```'
  echo
  if [[ ${failed} -eq 0 ]]; then
    echo "**PASS — published exact sentinel-hardening head \`${published}\` to PR #109.**"
  else
    echo '**FAIL — PR #109 was not modified. See the per-gate logs in this branch.**'
  fi
} > "${report}/README.md"

result_branch=automation/inc34-native-sentinel-results-v10
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
  "ci(increment34): record sentinel hardening v10 ${GITHUB_RUN_ID}"
git -C result push --force origin HEAD:"${result_branch}"

if [[ ${failed} -eq 0 ]]; then
  gh pr comment 109 --repo "${GITHUB_REPOSITORY}" \
    --body "Increment 34 canonical staging sentinel hardening v10 passed every staged gate. Exact published head: \`${published}\`. Diagnostic record: branch \`${result_branch}\`, run \`${GITHUB_RUN_ID}\`."
  exit 0
fi

gh pr comment 109 --repo "${GITHUB_REPOSITORY}" \
  --body "Increment 34 canonical staging sentinel hardening v10 did not publish because at least one staged gate failed. Exact logs and candidate patch: branch \`${result_branch}\`, run \`${GITHUB_RUN_ID}\`."
exit 1
