#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    checker_path = root / "scripts/check_increment34.py"
    checker = checker_path.read_text(encoding="utf-8")
    old = '''        "authoritative_serialization",
    ):
'''
    new = '''        "authoritative_serialization",
        "native_branch_sensitive_definite_assignment",
    ):
'''
    if old in checker:
        checker = checker.replace(old, new, 1)
    elif '        "native_branch_sensitive_definite_assignment",\n    ):' not in checker:
        raise SystemExit("completed integration evidence loop was not found")
    checker_path.write_text(checker, encoding="utf-8")

    tests_path = root / "tests/compiler/test_increment34.py"
    tests = tests_path.read_text(encoding="utf-8")
    mutation = r'''
    def test_native_dataflow_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["integration"][
                "native_branch_sensitive_definite_assignment"
            ] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(
                root,
                "integration 'native_branch_sensitive_definite_assignment' is not complete",
            )

'''
    if "test_native_dataflow_manifest_mutation_is_rejected" not in tests:
        marker = "    def test_native_branch_intersection_mutation_is_rejected(self) -> None:\n"
        tests = replace_once(
            tests,
            marker,
            mutation + marker,
            "native dataflow manifest mutation test",
        )
    tests_path.write_text(tests, encoding="utf-8")

    print("Increment 34 native dataflow evidence contract repaired.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
