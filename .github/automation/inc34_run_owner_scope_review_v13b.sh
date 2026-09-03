#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
work=${2:?feature checkout required}
report=${3:?report directory required}
source_patcher=${controller}/.github/automation/inc34_owner_scope_review_v13.py
corrected_patcher=${RUNNER_TEMP}/inc34_owner_scope_review_v13_corrected.py
source_runner=${controller}/.github/automation/inc34_run_owner_scope_review_v13.sh
corrected_runner=${RUNNER_TEMP}/inc34_run_owner_scope_review_v13_corrected.sh

python3 - "${source_patcher}" "${corrected_patcher}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
text = source.read_text(encoding="utf-8")
text = text.replace(
    "Solver execution and target lowering remain deferred.",
    "Solver\\nconstruction, target legalization, and Verilog-A or Verilog-AMS\\nprocedural lowering remain deferred to their owning increments.",
    1,
)
text = text.replace(
    '"empty_owner=NODAL-ANALOG-034-001"',
    '"empty_owner="',
    1,
)
text = text.replace(
    '"padded_owner=NODAL-ANALOG-034-001"',
    '"padded_owner="',
    1,
)
destination.write_text(text, encoding="utf-8")
PY

python3 - "${source_runner}" "${corrected_runner}" "${corrected_patcher}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
patcher = sys.argv[3]
text = source.read_text(encoding="utf-8")
old = 'patcher=${controller}/.github/automation/inc34_owner_scope_review_v13.py\n'
new = f'patcher={patcher}\n'
if old not in text:
    raise SystemExit("v13 runner patcher anchor was not found")
text = text.replace(old, new, 1)
destination.write_text(text, encoding="utf-8")
PY

chmod +x "${corrected_runner}"
bash "${corrected_runner}" "${controller}" "${work}" "${report}"
