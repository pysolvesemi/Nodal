#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
work=${2:?closure checkout required}
report=${3:?report directory required}
source_runner=${controller}/.github/automation/inc34_run_evidence_closure_v1.sh
patched_runner=${RUNNER_TEMP}/inc34_run_evidence_closure_v2_patched.sh
followup=${controller}/.github/automation/inc34_evidence_closure_followup_v1.py

python3 - "${source_runner}" "${patched_runner}" "${followup}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
followup = sys.argv[3]
text = source.read_text(encoding="utf-8")
anchor = '''    --root "${work}" --phase candidate
fi

gate native-bootstrap'''
replacement = f'''    --root "${{work}}" --phase candidate
  gate closure-successor-compatibility python3 "{followup}" --root "${{work}}"
fi

gate native-bootstrap'''
if anchor not in text:
    raise SystemExit("closure runner injection anchor was not found")
text = text.replace(anchor, replacement, 1)
destination.write_text(text, encoding="utf-8")
PY
chmod +x "${patched_runner}"
exec bash "${patched_runner}" "${controller}" "${work}" "${report}"
