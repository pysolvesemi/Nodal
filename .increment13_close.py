#!/usr/bin/env python3
from pathlib import Path

path = Path("docs/roadmap/nodal-development-todo.md")
text = path.read_text(encoding="utf-8")
start_old = "- [ ] **Increment 13 — Core semantic candidate prototypes and architecture comparison**"
start_new = "- [x] **Increment 13 — Core semantic candidate prototypes and architecture comparison**"
if text.count(start_old) != 1:
    raise SystemExit(f"expected exactly one unchecked Increment 13 marker, found {text.count(start_old)}")
text = text.replace(start_old, start_new, 1)

next_marker = "- [ ] **Increment 14 — Automatic pipeline candidate prototypes and architecture comparison**"
position = text.find(next_marker)
if position < 0:
    raise SystemExit("Increment 14 marker not found")
section_start = text.find(start_new)
if section_start < 0 or section_start > position:
    raise SystemExit("Increment 13/14 ordering invalid")
section = text[section_start:position]
evidence = (
    "  - Evidence: [`NodalCoreSemanticCandidates-DG-v0.3.md`](../design-gates/NodalCoreSemanticCandidates-DG-v0.3.md), "
    "[`CoreSemanticsCandidateApi.scala`](../../core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala), "
    "[`tests/api/fixtures/increment13/manifest.json`](../../tests/api/fixtures/increment13/manifest.json), "
    "[`scripts/check_increment13.py`](../../scripts/check_increment13.py), PR [#32](https://github.com/pysolvesemi/Nodal/pull/32), "
    "and dedicated validation run [32587119017](https://github.com/pysolvesemi/Nodal/actions/runs/32587119017).\n\n"
)
if "tests/api/fixtures/increment13/manifest.json" not in section:
    text = text[:position] + evidence + text[position:]

path.write_text(text, encoding="utf-8")
