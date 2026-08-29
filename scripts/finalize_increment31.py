#!/usr/bin/env python3
"""Repair Increment 31 bytecode artifact inventory without masking tracked files."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CHECKER_OLD = '    for path in root.rglob("__pycache__"):\n        if ".git" not in path.parts and path.is_dir():\n            problems.append(\n                Problem(\n                    "NODAL-INC31-002",\n                    f"Python bytecode cache remains: {path.relative_to(root)}",\n                )\n            )\n    for pattern in ("*.pyc", "*.pyo"):\n        for path in root.rglob(pattern):\n            if ".git" not in path.parts and path.is_file():\n                problems.append(\n                    Problem(\n                        "NODAL-INC31-002",\n                        f"Python bytecode artifact remains: {path.relative_to(root)}",\n                    )\n                )\n'
CHECKER_NEW = '    try:\n        inventory = subprocess.run(\n            [\n                "git",\n                "-C",\n                str(root),\n                "ls-files",\n                "--cached",\n                "--others",\n                "--exclude-standard",\n                "-z",\n            ],\n            check=True,\n            stdout=subprocess.PIPE,\n            stderr=subprocess.PIPE,\n        ).stdout.decode("utf-8")\n    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError) as exc:\n        problems.append(\n            Problem(\n                "NODAL-INC31-002",\n                f"cannot inventory repository artifacts: {exc}",\n            )\n        )\n        inventory = ""\n    for relative in sorted(path for path in inventory.split("\\0") if path):\n        path = Path(relative)\n        if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:\n            problems.append(\n                Problem(\n                    "NODAL-INC31-002",\n                    f"Python bytecode artifact remains: {path.as_posix()}",\n                )\n            )\n'

TEST_OLD = '        path.parent.mkdir(parents=True, exist_ok=True)\n        path.write_bytes(b"not-bytecode")\n        self.assertIn("NODAL-INC31-002", self.codes(root))\n'
TEST_NEW = '        path.parent.mkdir(parents=True, exist_ok=True)\n        path.write_bytes(b"not-bytecode")\n        subprocess.run(\n            [\n                "git",\n                "-C",\n                str(root),\n                "add",\n                "-f",\n                path.relative_to(root).as_posix(),\n            ],\n            check=True,\n            stdout=subprocess.PIPE,\n            stderr=subprocess.PIPE,\n        )\n        self.assertIn("NODAL-INC31-002", self.codes(root))\n'


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(
            f"{path.relative_to(ROOT)} replacement count is {text.count(old)}, expected 1"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    checker = ROOT / "scripts/check_increment31.py"
    text = checker.read_text(encoding="utf-8")
    if "import subprocess\n" not in text:
        if text.count("import re\nimport sys\n") != 1:
            raise RuntimeError("checker import anchor is missing or ambiguous")
        text = text.replace(
            "import re\nimport sys\n",
            "import re\nimport subprocess\nimport sys\n",
            1,
        )
        checker.write_text(text, encoding="utf-8")
    replace_once(checker, CHECKER_OLD, CHECKER_NEW)

    test = ROOT / "tests/compiler/test_increment31.py"
    text = test.read_text(encoding="utf-8")
    if "import subprocess\n" not in text:
        if text.count("import shutil\nimport sys\n") != 1:
            raise RuntimeError("test import anchor is missing or ambiguous")
        text = text.replace(
            "import shutil\nimport sys\n",
            "import shutil\nimport subprocess\nimport sys\n",
            1,
        )
        test.write_text(text, encoding="utf-8")
    replace_once(test, TEST_OLD, TEST_NEW)

    Path(__file__).unlink()


if __name__ == "__main__":
    main()
