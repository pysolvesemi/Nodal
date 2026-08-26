#!/usr/bin/env python3
"""Make Increment 22 successor compatibility depend on evidence, never global roadmap revision."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    checker_path = root / "scripts/check_increment22.py"
    checker = checker_path.read_text(encoding="utf-8")
    old = '''    if revision < (1, 27):
        if not increment23_unchecked:
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    "Increment 23 must remain unchecked before roadmap revision 1.27",
                )
            )
    elif not increment23_checked:
        problems.append(
            Problem(
                "NODAL-INC22-009",
                "Increment 23 must be checked at roadmap revision 1.27 or later",
            )
        )
'''
    new = '''    increment23_status = None
    increment23_manifest = root / "tests/compiler/fixtures/increment23/manifest.json"
    if increment23_manifest.is_file():
        try:
            increment23_value = json.loads(
                increment23_manifest.read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError) as exc:
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    f"cannot read Increment 23 successor evidence: {exc}",
                )
            )
        else:
            increment23_status = increment23_value.get("status")

    if increment23_status == "validated-backend-framework":
        if not increment23_checked:
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    "validated Increment 23 evidence requires its roadmap item to be checked",
                )
            )
    elif not increment23_unchecked:
        problems.append(
            Problem(
                "NODAL-INC22-009",
                "Increment 23 must remain unchecked until validated evidence exists",
            )
        )
'''
    checker = replace_once(checker, old, new, "Increment 22 successor evidence rule")
    checker_path.write_text(checker, encoding="utf-8")

    tests_path = root / "tests/compiler/test_increment22.py"
    tests = tests_path.read_text(encoding="utf-8")
    if "import json\n" not in tests:
        tests = replace_once(
            tests,
            "import importlib.util\n",
            "import importlib.util\nimport json\n",
            "Increment 22 test JSON import",
        )
    tests = replace_once(
        tests,
        '''SUPPORT_FILES = (
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "docs/roadmap/nodal-development-todo.md",
)
''',
        '''SUPPORT_FILES = (
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "docs/roadmap/nodal-development-todo.md",
    "tests/compiler/fixtures/increment23/manifest.json",
)
''',
        "Increment 22 test support files",
    )
    old_test = '''    def test_accepts_validated_increment23_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.26", "**Revision:** 1.27", 1)
            .replace(
                "- [ ] **Increment 23 — Backend framework and capability profiles**",
                "- [x] **Increment 23 — Backend framework and capability profiles**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])
'''
    new_test = '''    def test_accepts_validated_increment23_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment23/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-backend-framework"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\\n",
            encoding="utf-8",
        )
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 23 — Backend framework and capability profiles**",
                "- [x] **Increment 23 — Backend framework and capability profiles**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])
'''
    tests = replace_once(tests, old_test, new_test, "Increment 22 successor test")
    tests_path.write_text(tests, encoding="utf-8")


if __name__ == "__main__":
    main()
