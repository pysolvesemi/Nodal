#!/usr/bin/env python3
from __future__ import annotations

import subprocess


source = subprocess.check_output(
    [
        "git",
        "show",
        "origin/automation/inc33-p1-final-repair-v2-20260901:.github/workflows/_inc33_p1_final_repair_v2.yml",
    ],
    text=True,
)
begin_marker = "          python3 - <<'PY'\n"
end_marker = "\n          PY\n\n      - name: Install pinned native and lint toolchains"
begin = source.index(begin_marker) + len(begin_marker)
end = source.index(end_marker, begin)
lines = source[begin:end].splitlines()
script = "\n".join(
    line[10:] if line.startswith("          ") else line for line in lines
)

old_loop = """for old, new, label in renderer_substitutions:
    count = renderer.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    renderer = renderer.replace(old, new, 1)
"""
new_loop = """for old, new, label in renderer_substitutions:
    if label.startswith("IR "):
        position = renderer.rfind(old)
        if position < 0:
            raise SystemExit(f"{label}: match not found")
        renderer = renderer[:position] + new + renderer[position + len(old):]
    else:
        count = renderer.count(old)
        if count != 1:
            raise SystemExit(f"{label}: expected one match, found {count}")
        renderer = renderer.replace(old, new, 1)
"""
if script.count(old_loop) != 1:
    raise SystemExit("renderer substitution loop was not found exactly once")
script = script.replace(old_loop, new_loop, 1)

exec(
    compile(script, "increment33-final-p1-repair.py", "exec"),
    {"__name__": "__main__"},
)
