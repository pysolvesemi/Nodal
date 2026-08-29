#!/usr/bin/env python3
"""Recover the validated Increment 31 closure without workflow-token writes."""

from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
updater = root / "scripts/_increment31_evidence_close.py"
text = updater.read_text(encoding="utf-8")

old_temporary = '''TEMPORARY_PATHS = (
    ROOT / "scripts/_increment31_evidence_close.py",
    ROOT / ".github/workflows/_increment31_evidence_close.yml",
)
'''
new_temporary = '''TEMPORARY_PATHS = (
    ROOT / "scripts/_increment31_evidence_close.py",
    ROOT / "scripts/_increment31_evidence_recover.py",
)
'''
if text.count(old_temporary) != 1:
    raise RuntimeError("could not isolate the temporary closure paths")
text = text.replace(old_temporary, new_temporary, 1)

workflow_call = "    update_increment30_workflow()\n"
if text.count(workflow_call) != 1:
    raise RuntimeError("could not isolate the workflow update call")
text = text.replace(workflow_call, "", 1)
updater.write_text(text, encoding="utf-8")

subprocess.run(
    ["python3", str(updater)],
    cwd=root,
    check=True,
)
