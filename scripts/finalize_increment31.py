#!/usr/bin/env python3
"""Rerun named-branch backend repair with raw C++ newline anchors."""

from __future__ import annotations

import subprocess
from pathlib import Path

SELF = Path(__file__).resolve()
ROOT = SELF.parents[1]
SOURCE_COMMIT = "635dda25b21788108594d450d747521974e7082f"

source = subprocess.run(
    ["git", "show", f"{SOURCE_COMMIT}:scripts/finalize_increment31.py"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
).stdout
anchor = '        \'\'\'    output << ";\\n";\n'
replacement = '        r\'\'\'    output << ";\\n";\n'
if source.count(anchor) != 2:
    raise RuntimeError(
        f"backend repair newline anchor count is {source.count(anchor)}, expected 2"
    )
source = source.replace(anchor, replacement)
exec(
    compile(source, str(SELF), "exec"),
    {"__name__": "__main__", "__file__": str(SELF)},
)
