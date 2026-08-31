#!/usr/bin/env python3
"""Make Increment 33 temporary-file checks repository-aware."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


checker = "scripts/check_increment33.py"
replace_once(
    checker,
    "def check_repository(root: Path, compile_witnesses: bool = False) -> None:\n",
    '''def repository_files(root: Path) -> tuple[Path, ...]:
    completed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if completed.returncode == 0:
        return tuple(
            root / relative
            for relative in completed.stdout.split(chr(0))
            if relative
        )
    return tuple(
        path
        for path in root.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts
    )


def check_repository(root: Path, compile_witnesses: bool = False) -> None:
''',
)
replace_once(
    checker,
    '    for path in root.rglob("*"):\n',
    "    for path in repository_files(root):\n",
)

unit_tests = "tests/compiler/test_increment33.py"
replace_once(
    unit_tests,
    "    def test_variable_type_diagnostic_mutation_is_rejected(self) -> None:\n",
    '''    def test_untracked_python_cache_is_ignored(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "scripts/__pycache__/probe.cpython-312.pyc"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"runtime cache")
            CHECKER.check_repository(root)

    def test_variable_type_diagnostic_mutation_is_rejected(self) -> None:
''',
)
