#!/usr/bin/env python3
"""Close Increment 20 and make the Increment 19 checker successor-aware."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    checker_path = root / "scripts/check_increment19.py"
    checker = checker_path.read_text(encoding="utf-8")
    checker = replace_once(
        checker,
        "if revision != (1, 23) or checked not in roadmap:",
        "if revision < (1, 23) or checked not in roadmap:",
        "Increment 19 revision comparison",
    )
    checker = replace_once(
        checker,
        """        if "- [ ] **Increment 20 — Scala-to-MLIR bridge**" not in roadmap:
            problems.append(Problem("NODAL-INC19-014", "Increment 20 is not left unchecked"))""",
        """        increment20_unchecked = (
            "- [ ] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
        )
        increment20_checked = (
            "- [x] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
        )
        if not increment20_unchecked and not increment20_checked:
            problems.append(
                Problem("NODAL-INC19-014", "Increment 20 roadmap item is missing")
            )""",
        "Increment 19 successor item requirement",
    )
    checker_path.write_text(checker, encoding="utf-8")

    test_path = root / "tests/compiler/test_increment19.py"
    test = test_path.read_text(encoding="utf-8")
    anchor = "    def test_rejects_missing_type_definition(self) -> None:\n"
    addition = (
        "    def test_accepts_later_roadmap_revision(self) -> None:\n"
        "        temporary, root = self.temporary_repository()\n"
        "        self.addCleanup(temporary.cleanup)\n"
        "        path = root / \"docs/roadmap/nodal-development-todo.md\"\n"
        "        text = path.read_text(encoding=\"utf-8\")\n"
        "        if \"**Revision:** 1.24\" not in text:\n"
        "            self.fail(\"expected closed Increment 20 roadmap revision\")\n"
        "        path.write_text(\n"
        "            text.replace(\"**Revision:** 1.24\", \"**Revision:** 1.25\", 1),\n"
        "            encoding=\"utf-8\",\n"
        "        )\n"
        "        self.assertEqual(CHECKER.check_repository(root), [])\n"
        "\n"
    )
    test = replace_once(
        test,
        anchor,
        addition + anchor,
        "Increment 19 successor mutation test",
    )
    test_path.write_text(test, encoding="utf-8")

    manifest_path = root / "tests/compiler/fixtures/increment20/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("status") != "implemented-awaiting-evidence":
        raise SystemExit(f"unexpected Increment 20 status: {manifest.get('status')!r}")
    manifest["status"] = "validated-scala-mlir-bridge"
    manifest["evidence"] = {
        "pull_request": 49,
        "dedicated_run": 32850253855,
        "core_ci_run": 32850253829,
    }
    manifest_path.write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8")
    roadmap = replace_once(
        roadmap,
        "**Revision:** 1.23",
        "**Revision:** 1.24",
        "roadmap revision",
    )
    roadmap = replace_once(
        roadmap,
        "- [ ] **Increment 20 — Scala-to-MLIR bridge**",
        "- [x] **Increment 20 — Scala-to-MLIR bridge**",
        "Increment 20 roadmap checkbox",
    )
    if (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        not in roadmap
    ):
        raise SystemExit("Increment 21 must remain the next unchecked roadmap item")
    roadmap_path.write_text(roadmap, encoding="utf-8")


if __name__ == "__main__":
    main()
