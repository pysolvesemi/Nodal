#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
work=${2:?feature checkout required}
report=${3:?report directory required}
source_runner=${controller}/.github/automation/inc34_run_reference_order_review_v14.sh
patched_runner=${RUNNER_TEMP}/inc34_run_reference_order_review_v14_patched.sh

python3 - "${source_runner}" "${patched_runner}" "${controller}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
controller = sys.argv[3]
text = source.read_text(encoding="utf-8")
anchor = '''  gate apply-reference-order-review python3 "${patcher}" --root "${work}"
'''
addition = anchor + f'''  repairs={controller}/.github/automation/inc34_reference_order_review_v14_repairs.py
gate apply-reference-order-consistency python3 "${{repairs}}" --root "${{work}}"
'''
if anchor not in text:
    raise SystemExit("v14 runner patch anchor was not found")
text = text.replace(anchor, addition, 1)
destination.write_text(text, encoding="utf-8")
PY

chmod +x "${patched_runner}"
bash "${patched_runner}" "${controller}" "${work}" "${report}"
