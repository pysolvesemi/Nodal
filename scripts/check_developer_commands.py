#!/usr/bin/env python3
"""Validate the unified Nodal developer command contract."""

from __future__ import annotations

import argparse
import ast
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


EXPECTED_FILES = (
    "nodal",
    "nodal.bat",
    "scripts/nodal.py",
    "scripts/check_developer_commands.py",
    "tests/developer/test_developer_commands.py",
    "tests/developer/test_developer_command_contract.py",
    "docs/development/commands.md",
    ".github/workflows/increment-7-unified-developer-commands.yml",
    "scripts/bootstrap_lint_toolchain.py",
    "scripts/run_clang_tools.py",
)

ALLOWED_IMPORT_ROOTS = {
    "__future__",
    "argparse",
    "dataclasses",
    "json",
    "os",
    "pathlib",
    "shlex",
    "shutil",
    "subprocess",
    "sys",
    "typing",
}


def _read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def _parse_command_ast(source: str, problems: list[Problem]) -> ast.AST | None:
    try:
        return ast.parse(source)
    except SyntaxError as exc:
        problems.append(Problem("NODAL-DEV-CHECK-004", f"invalid scripts/nodal.py: {exc}"))
        return None


def _check_imports(tree: ast.AST | None, problems: list[Problem]) -> None:
    if tree is None:
        return
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".", 1)[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".", 1)[0])
    unexpected = sorted(imported - ALLOWED_IMPORT_ROOTS)
    if unexpected:
        problems.append(
            Problem(
                "NODAL-DEV-CHECK-005",
                "unified command must use the Python standard library only: "
                + ", ".join(unexpected),
            )
        )


def _parser_names(tree: ast.AST | None) -> set[str]:
    if tree is None:
        return set()
    names: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        if node.func.attr != "add_parser" or not node.args:
            continue
        first = node.args[0]
        if isinstance(first, ast.Constant) and isinstance(first.value, str):
            names.add(first.value)
    return names


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-DEV-CHECK-001", f"missing developer-command file: {relative}"))

    shell = _read(root / "nodal", problems, "NODAL-DEV-CHECK-002")
    batch = _read(root / "nodal.bat", problems, "NODAL-DEV-CHECK-003")
    command = _read(root / "scripts/nodal.py", problems, "NODAL-DEV-CHECK-004")
    tests = _read(
        root / "tests/developer/test_developer_commands.py",
        problems,
        "NODAL-DEV-CHECK-006",
    )
    docs = _read(root / "docs/development/commands.md", problems, "NODAL-DEV-CHECK-007")
    workflow = _read(
        root / ".github/workflows/increment-7-unified-developer-commands.yml",
        problems,
        "NODAL-DEV-CHECK-008",
    )

    if "scripts/nodal.py" not in shell or "exec python3" not in shell:
        problems.append(Problem("NODAL-DEV-CHECK-009", "POSIX wrapper must delegate to scripts/nodal.py"))
    if "scripts\\nodal.py" not in batch or "%~dp0" not in batch:
        problems.append(Problem("NODAL-DEV-CHECK-010", "Windows wrapper must delegate to scripts\\nodal.py"))
    try:
        if (root / "nodal").is_file() and not ((root / "nodal").stat().st_mode & 0o111):
            problems.append(Problem("NODAL-DEV-CHECK-011", "POSIX nodal wrapper is not executable"))
    except OSError as exc:
        problems.append(Problem("NODAL-DEV-CHECK-011", f"cannot stat nodal wrapper: {exc}"))

    command_ast = _parse_command_ast(command, problems)
    _check_imports(command_ast, problems)
    required_parsers = {
        "bootstrap",
        "core",
        "scala",
        "native",
        "style",
        "fix",
        "check",
        "clean",
        "toolchain",
        "doctor",
        "library",
    }
    missing = sorted(required_parsers - _parser_names(command_ast))
    if missing:
        problems.append(Problem("NODAL-DEV-CHECK-012", "developer command lacks parser(s): " + ", ".join(missing)))

    required_command_fragments = (
        'bootstrap_native_toolchain.py"',
        'bootstrap_lint_toolchain.py"',
        'check_architecture.py"',
        'check_scala_bootstrap.py"',
        'check_native_toolchain.py"',
        'check_native_compiler_bootstrap.py"',
        'check_developer_commands.py"',
        'check_formatting_baseline.py"',
        'check_ci_baseline.py"',
        'check_increment9.py"',
        'check_markdown.py"',
        'check_package_visibility.py"',
        'check_contribution_policy.py"',
        'core.scala.testkit.test"',
        'check-nodal-native"',
        'mill.scalalib.scalafmt/checkFormatAll"',
        'scalafix.check"',
        'run_clang_tools.py"',
        'if args.contracts_only:',
        'NODAL-DEV-004',
        'if args.toolchains:',
    )
    for fragment in required_command_fragments:
        if fragment not in command:
            problems.append(Problem("NODAL-DEV-CHECK-012", f"developer command lacks: {fragment}"))

    for forbidden in (
        'root / "libraries"',
        "root / 'libraries'",
        "libraries.mkdir",
        "mkdir libraries",
    ):
        if forbidden in command:
            problems.append(
                Problem(
                    "NODAL-DEV-CHECK-013",
                    f"core developer command must not create or consume libraries: {forbidden}",
                )
            )

    required_docs = (
        "./nodal bootstrap",
        "./nodal core scala",
        "./nodal core native",
        "./nodal style bootstrap",
        "./nodal style check",
        "./nodal style fix",
        "./nodal check",
        "./nodal check --contracts-only",
        "./nodal clean",
        "./nodal toolchain doctor",
        "./nodal library check",
        "NODAL-DEV-004",
    )
    for fragment in required_docs:
        if fragment not in docs:
            problems.append(Problem("NODAL-DEV-CHECK-014", f"developer command documentation lacks: {fragment}"))

    required_workflow = (
        "increment/7-unified-developer-commands",
        "./nodal bootstrap",
        "./nodal toolchain doctor",
        "./nodal check",
        "./nodal clean --dry-run",
        "./nodal library check reserved-fixture",
        "increment-7/unified-developer-commands",
    )
    for fragment in required_workflow:
        if fragment not in workflow:
            problems.append(Problem("NODAL-DEV-CHECK-015", f"Increment 7 workflow lacks: {fragment}"))
    for forbidden in (
        "./mill ",
        "cmake --preset",
        "ctest --preset",
        "scripts/bootstrap_native_toolchain.py install",
    ):
        if forbidden in workflow:
            problems.append(
                Problem(
                    "NODAL-DEV-CHECK-016",
                    f"CI must use the same public developer commands as local use: {forbidden}",
                )
            )

    required_tests = (
        "test_bootstrap_forwards_locked_toolchain_options",
        "test_core_scala_uses_repository_mill_wrapper",
        "test_core_native_uses_managed_toolchain",
        "test_style_bootstrap_uses_pinned_installer",
        "test_style_check_runs_all_language_and_policy_gates",
        "test_full_check_includes_all_contract_suites",
        "test_contract_only_check_skips_builds",
        "test_clean_preserves_toolchains_by_default",
        "test_library_namespace_is_reserved",
    )
    for fragment in required_tests:
        if fragment not in tests:
            problems.append(Problem("NODAL-DEV-CHECK-017", f"developer command tests lack: {fragment}"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"developer command check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("developer command check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
