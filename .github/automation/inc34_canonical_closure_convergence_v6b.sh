#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
source=${controller}/.github/automation/inc34_canonical_closure_convergence_v6.sh
reconciler=${controller}/.github/automation/inc34_poll_exact_workflows_v6b.py
patched=${RUNNER_TEMP}/inc34_canonical_closure_convergence_v6b_patched.sh

python3 - "${source}" "${patched}" "${reconciler}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
reconciler = sys.argv[3]
text = source.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "workflow_reconciler=${controller}/.github/automation/inc34_poll_exact_workflows_v6.py",
    f"workflow_reconciler={reconciler}",
    "workflow reconciler path",
)
replace_once(
    '''status=${report}/status.tsv
: > "${status}"
''',
    '''status=${report}/status.tsv
: > "${status}"
CONSISTENCY_MERGE_RESULT=
''',
    "consistency result state",
)
replace_once(
    '''canonical_checkout() {
  local sha=$1
  local destination=$2
  checkout_exact "${sha}" "${destination}"
  python3 "${validator}" --root "${destination}" --json \\
    > "${destination}/increment34-canonical-state.json"
}
''',
    '''canonical_checkout() {
  local sha=$1
  local destination=$2
  local state_name
  state_name=$(basename "${destination}")
  checkout_exact "${sha}" "${destination}"
  python3 "${validator}" --root "${destination}" --json \\
    > "${report}/${state_name}-canonical-state.json"
}
''',
    "canonical state output location",
)
replace_once(
    '''    if [[ ${head} != "${placeholder}" && ${draft} == false ]]; then
      checkout_exact "${head}" "${report}/closure-probe"
''',
    '''    if [[ ${head} != "${placeholder}" ]]; then
      checkout_exact "${head}" "${report}/closure-probe"
''',
    "canonical draft-independent probe",
)
replace_once(
    '''  pass consistency-merge
  echo "${repair_merge}"
}
''',
    '''  pass consistency-merge
  CONSISTENCY_MERGE_RESULT=${repair_merge}
}
''',
    "consistency result propagation",
)
replace_once(
    '''  final_dev=$(create_consistency_repair "${dev_head}")
  pass postclosure-consistency-repair
''',
    '''  create_consistency_repair "${dev_head}"
  final_dev=${CONSISTENCY_MERGE_RESULT}
  [[ -n ${final_dev} ]] || fail postclosure-consistency-repair \\
    "consistency repair did not return a merge commit"
  pass postclosure-consistency-repair
''',
    "consistency repair invocation",
)
replace_once(
    '''cp "${report}/final-dev/increment34-canonical-state.json" \\
  "${report}/result/canonical-state.json"
''',
    '''cp "${report}/final-dev-canonical-state.json" \\
  "${report}/result/canonical-state.json"
''',
    "final canonical state publication",
)

destination.write_text(text, encoding="utf-8")
PY

chmod +x "${patched}"
exec bash "${patched}" "${controller}" "${report}"
