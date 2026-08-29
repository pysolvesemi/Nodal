#!/usr/bin/env python3
"""Run the semantic review repair with one ambiguous checker anchor narrowed."""

from __future__ import annotations

import subprocess
from pathlib import Path

SELF = Path(__file__).resolve()
ROOT = SELF.parents[1]
SOURCE_COMMIT = "3d99c0e474263385375f0088a3314671b23ec9db"

source = subprocess.run(
    ["git", "show", f"{SOURCE_COMMIT}:scripts/finalize_increment31.py"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
).stdout

old = r'''        '            "verifyPotentialFlowAccessOperation",\n'
        '            "normalizePotentialFlowAccess",\n',
        '            "verifyPotentialFlowAccessOperation",\n'
        '            "createNormalizePotentialFlowAccessPass",\n'
        '            "normalizePotentialFlowAccess",\n',
'''
new = r'''        '            "ResolvedAccessNature",\n'
        '            "resolvePotentialFlowAccessNature",\n'
        '            "verifyPotentialFlowAccessOperation",\n'
        '            "normalizePotentialFlowAccess",\n',
        '            "ResolvedAccessNature",\n'
        '            "resolvePotentialFlowAccessNature",\n'
        '            "verifyPotentialFlowAccessOperation",\n'
        '            "createNormalizePotentialFlowAccessPass",\n'
        '            "normalizePotentialFlowAccess",\n',
'''
if source.count(old) != 1:
    raise RuntimeError(
        f"semantic repair source anchor count is {source.count(old)}, expected 1"
    )
source = source.replace(old, new, 1)
exec(
    compile(source, str(SELF), "exec"),
    {"__name__": "__main__", "__file__": str(SELF)},
)
