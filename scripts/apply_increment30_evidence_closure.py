#!/usr/bin/env python3
"""Record accepted Increment 30 implementation evidence and close the roadmap item."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"
MANIFEST = ROOT / "tests/compiler/fixtures/increment30/manifest.json"
MUTATION_TESTS = ROOT / "tests/compiler/test_increment30.py"
INCREMENT28_CHECKER = ROOT / "scripts/check_increment28.py"
INCREMENT29_CHECKER = ROOT / "scripts/check_increment29.py"

IMPLEMENTATION_HEAD = "6f62937796af25714c996f8733f1adb72cefe4ee"
MERGE_COMMIT = "401f78b3836cc4e52d393ef343dc0915d60606e9"
DEDICATED_RUN = 33192880165
CORE_CI_RUN = 33192880254


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one replacement anchor, found {count}")
    return text.replace(old, new, 1)


roadmap = ROADMAP.read_text(encoding="utf-8")
roadmap = replace_once(
    roadmap,
    "**Revision:** 1.39",
    "**Revision:** 1.40",
    "roadmap revision",
)
roadmap = replace_once(
    roadmap,
    """- [ ] **Increment 30 — Analog numeric types and expression typing**
  - Define promotion, physical compatibility, comparisons/logical results, conditionals, invalid operations, and folding boundaries.
""",
    """- [x] **Increment 30 — Analog numeric types and expression typing**
  - Define promotion, physical compatibility, comparisons/logical results, conditionals, invalid operations, and folding boundaries.
  - Evidence: implementation PR [#80](https://github.com/pysolvesemi/Nodal/pull/80), dedicated validation run [33192880165](https://github.com/pysolvesemi/Nodal/actions/runs/33192880165), merge commit [`401f78b3`](https://github.com/pysolvesemi/Nodal/commit/401f78b3836cc4e52d393ef343dc0915d60606e9), and Core CI run [33192880254](https://github.com/pysolvesemi/Nodal/actions/runs/33192880254).
""",
    "Increment 30 roadmap item",
)
if "- [ ] **Increment 31 — Potential and flow access functions**" not in roadmap:
    raise RuntimeError("Increment 31 must remain unchecked")
ROADMAP.write_text(roadmap, encoding="utf-8")

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
if manifest.get("increment") != 30:
    raise RuntimeError("Increment 30 manifest identity mismatch")
if manifest.get("status") != "implemented-awaiting-evidence":
    raise RuntimeError("Increment 30 manifest is not awaiting evidence")

manifest["status"] = "validated-analog-numeric-typing"
manifest["evidence"] = {
    "start_commit": "1c1ab49da71f0f52d2af3d93e40a92f4643be776",
    "pull_request": 80,
    "implementation_head": IMPLEMENTATION_HEAD,
    "merge_commit": MERGE_COMMIT,
    "dedicated_run": DEDICATED_RUN,
    "core_ci_run": CORE_CI_RUN,
    "inherited_runs": {
        "increment13": 33192880304,
        "increment14": 33192880239,
        "increment15": 33192880282,
        "increment16": 33192880278,
        "increment17": 33192880273,
        "increment18": 33192880300,
        "increment19": 33192880296,
        "increment20": 33192880334,
        "increment21": 33192880255,
        "increment22": 33192880280,
        "increment23": 33192880345,
        "increment24": 33192880207,
        "increment25": 33192880196,
        "increment26": 33192880175,
        "increment27": 33192880267,
        "increment28": 33192880203,
        "increment29": 33192880204,
    },
}
MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

increment28_checker = INCREMENT28_CHECKER.read_text(encoding="utf-8")
increment28_checker = replace_once(
    increment28_checker,
    '''    "scripts/check_increment29.py",
    "tests/compiler/test_increment29.py",
)
''',
    '''    "scripts/check_increment29.py",
    "tests/compiler/test_increment29.py",
    "tests/compiler/fixtures/increment30/manifest.json",
)
''',
    "Increment 28 expected Increment 30 manifest",
)
increment28_checker = replace_once(
    increment28_checker,
    '''    increment30_open = (
        "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
''',
    '''    increment30_open = (
        "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
    increment30_done = (
        "- [x] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
''',
    "Increment 28 successor roadmap states",
)
increment28_checker = replace_once(
    increment28_checker,
    '''    if not increment30_open:
        problems.append(Problem("NODAL-INC28-019", "Increment 30 must remain unchecked"))
''',
    '''    increment30_path = root / "tests/compiler/fixtures/increment30/manifest.json"
    try:
        increment30 = json.loads(read(increment30_path, problems, "NODAL-INC28-019"))
    except json.JSONDecodeError as exc:
        problems.append(
            Problem("NODAL-INC28-019", f"invalid Increment 30 manifest: {exc}")
        )
        increment30 = {}
    increment30_status = increment30.get("status")
    increment30_evidence = increment30.get("evidence", {})
    if increment30.get("increment") != 30 or increment30.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-INC28-019", "Increment 30 successor identity mismatch")
        )
    if increment30_open == increment30_done:
        problems.append(
            Problem("NODAL-INC28-019", "Increment 30 roadmap state is missing or ambiguous")
        )
    elif increment30_open:
        if increment30_status not in {
            "implementation-started",
            "implemented-awaiting-evidence",
        }:
            problems.append(
                Problem("NODAL-INC28-019", "Increment 30 pre-evidence state is inconsistent")
            )
    elif increment30_status != "validated-analog-numeric-typing" or rev < (1, 38):
        problems.append(
            Problem("NODAL-INC28-019", "validated Increment 30 state is inconsistent")
        )
    else:
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(increment30_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC28-019",
                        f"Increment 30 lacks evidence field: {field}",
                    )
                )
''',
    "Increment 28 validated Increment 30 successor handling",
)
INCREMENT28_CHECKER.write_text(increment28_checker, encoding="utf-8")

increment29_checker = INCREMENT29_CHECKER.read_text(encoding="utf-8")
increment29_checker = replace_once(
    increment29_checker,
    '''    ".github/workflows/increment-29-parameters-units.yml",
)
''',
    '''    ".github/workflows/increment-29-parameters-units.yml",
    "tests/compiler/fixtures/increment30/manifest.json",
)
''',
    "Increment 29 expected Increment 30 manifest",
)
increment29_checker = replace_once(
    increment29_checker,
    '''    increment30_open = "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
''',
    '''    increment30_open = "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    increment30_done = "- [x] **Increment 30 — Analog numeric types and expression typing**" in roadmap
''',
    "Increment 29 successor roadmap states",
)
increment29_checker = replace_once(
    increment29_checker,
    '''    if not increment30_open:
        problems.append(Problem("NODAL-INC29-020", "Increment 30 must remain unchecked"))
''',
    '''    increment30_path = root / "tests/compiler/fixtures/increment30/manifest.json"
    try:
        increment30 = json.loads(read(increment30_path, problems, "NODAL-INC29-020"))
    except json.JSONDecodeError as exc:
        problems.append(
            Problem("NODAL-INC29-020", f"invalid Increment 30 manifest: {exc}")
        )
        increment30 = {}
    increment30_status = increment30.get("status")
    increment30_evidence = increment30.get("evidence", {})
    if increment30.get("increment") != 30 or increment30.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-INC29-020", "Increment 30 successor identity mismatch")
        )
    if increment30_open == increment30_done:
        problems.append(
            Problem("NODAL-INC29-020", "Increment 30 roadmap state is missing or ambiguous")
        )
    elif increment30_open:
        if increment30_status not in {
            "implementation-started",
            "implemented-awaiting-evidence",
        }:
            problems.append(
                Problem("NODAL-INC29-020", "Increment 30 pre-evidence state is inconsistent")
            )
    elif increment30_status != "validated-analog-numeric-typing" or rev < (1, 38):
        problems.append(
            Problem("NODAL-INC29-020", "validated Increment 30 state is inconsistent")
        )
    else:
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(increment30_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC29-020",
                        f"Increment 30 lacks evidence field: {field}",
                    )
                )
''',
    "Increment 29 validated Increment 30 successor handling",
)
INCREMENT29_CHECKER.write_text(increment29_checker, encoding="utf-8")

mutation_tests = MUTATION_TESTS.read_text(encoding="utf-8")
mutation_tests = replace_once(
    mutation_tests,
    '''    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/roadmap/nodal-development-todo.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [ ] **Increment 30 — Analog numeric types and expression typing**",
            "- [x] **Increment 30 — Analog numeric types and expression typing**",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC30-006", self.codes(root))
''',
    '''    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\\n",
            encoding="utf-8",
        )

        roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap_path.read_text(encoding="utf-8")
        checked = "- [x] **Increment 30 — Analog numeric types and expression typing**"
        unchecked = "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        if checked not in text:
            text = text.replace(unchecked, checked, 1)
        roadmap_path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC30-006", self.codes(root))
''',
    "premature roadmap closure mutation test",
)
MUTATION_TESTS.write_text(mutation_tests, encoding="utf-8")

print("Increment 30 evidence closure materialized")
