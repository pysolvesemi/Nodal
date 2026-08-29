#!/usr/bin/env python3
"""Rerun the named-branch repair with an escaped mutation-test newline."""

from __future__ import annotations

import subprocess
from pathlib import Path

SELF = Path(__file__).resolve()
ROOT = SELF.parents[1]
SOURCE_COMMIT = "a18f90ba508df1b0ac414aa262b779195435e3de"

source = subprocess.run(
    ["git", "show", f"{SOURCE_COMMIT}:scripts/finalize_increment31.py"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
).stdout
old = '        path.write_text(json.dumps(surface, indent=2) + "\\n", encoding="utf-8")\n'
new = '        path.write_text(json.dumps(surface, indent=2) + "\\\\n", encoding="utf-8")\n'
if source.count(old) != 1:
    raise RuntimeError(
        f"named-branch repair newline anchor count is {source.count(old)}, expected 1"
    )
source = source.replace(old, new, 1)
exec(
    compile(source, str(SELF), "exec"),
    {"__name__": "__main__", "__file__": str(SELF)},
)
