#!/usr/bin/env python3
"""Unified developer commands for the Nodal core repository."""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
NATIVE_PRESET = "native-release"


class DeveloperCommandError(RuntimeError):
    """Expected command-contract failure with a stable diagnostic code."""

    def __init__(self, code: str, message: str, *, exit_code: int = 2) -> None:
        super().__init__(message)
        self.code = code
        self.exit_code = exit_code

    def __str__(self) -> str:
        return f"{self.code}: {super().__str__()}"


@dataclass
class Runner:
    """Subprocess boundary kept injectable for command-contract unit tests."""

    root: Path
    base_env: Mapping[str, str] = field(default_factory=lambda: dict(os.environ))

    def run(
        self,
        command: Sequence[str],
        *,
        env: Mapping[str, str] | None = None,
        capture_output: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        rendered = shlex.join(str(part) for part in command)
        print(f"+ {rendered}", flush=True)
        merged_env = dict(self.base_env)
        if env:
            merged_env.update(env)
        return subprocess.run(
            [str(part) for part in command],
            cwd=self.root,
            env=merged_env,
            check=True,
            text=True,
            capture_output=capture_output,
        )


def _python(root: Path, script: str, *arguments: str) -> list[str]:
    return [sys.executable, str(root / "scripts" / script), *arguments]


def _mill(root: Path, *arguments: str) -> list[str]:
    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    return [str(wrapper), *arguments]


def _managed_toolchain(
    root: Path,
    runner: Runner,
    *,
    explicit: Path | None,
    require: bool,
) -> Path | None:
    command = _python(root, "bootstrap_native_toolchain.py", "status", "--json")
    if explicit is not None:
        command.extend(("--toolchain", str(explicit)))
    if require:
        command.append("--require")
    completed = runner.run(command, capture_output=True)
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise DeveloperCommandError(
            "NODAL-DEV-001",
            f"native toolchain status returned invalid JSON: {exc}",
        ) from exc
    found = payload.get("found")
    if found is None:
        if require:
            raise DeveloperCommandError(
                "NODAL-DEV-002",
                "no validated native toolchain was found; run './nodal bootstrap' first",
            )
        return None
    return Path(str(found)).expanduser().resolve()


def _managed_lint_toolchain(
    root: Path,
    runner: Runner,
    *,
    explicit: Path | None,
    require: bool,
) -> Path | None:
    command = _python(root, "bootstrap_lint_toolchain.py", "status", "--json")
    if explicit is not None:
        command.extend(("--toolchain", str(explicit)))
    if require:
        command.append("--require")
    completed = runner.run(command, capture_output=True)
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise DeveloperCommandError(
            "NODAL-DEV-007",
            f"lint toolchain status returned invalid JSON: {exc}",
        ) from exc
    found = payload.get("found")
    if found is None:
        if require:
            raise DeveloperCommandError(
                "NODAL-DEV-008",
                "no validated lint toolchain was found; run './nodal style bootstrap' first",
            )
        return None
    return Path(str(found)).expanduser().resolve()


def command_bootstrap(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    command = _python(
        root,
        "bootstrap_native_toolchain.py",
        "install",
        "--mode",
        args.mode,
    )
    if args.prefix is not None:
        command.extend(("--prefix", str(args.prefix)))
    if args.jobs is not None:
        command.extend(("--jobs", str(args.jobs)))
    if args.force:
        command.append("--force")
    if args.dry_run:
        command.append("--dry-run")
    if args.json:
        command.append("--json")
    runner.run(command)
    return 0


def command_core_scala(_args: argparse.Namespace, root: Path, runner: Runner) -> int:
    runner.run(_mill(root, "__.compile"))
    runner.run(_mill(root, "core.scala.testkit.test"))
    return 0


def command_core_native(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    toolchain = _managed_toolchain(
        root,
        runner,
        explicit=args.toolchain,
        require=True,
    )
    assert toolchain is not None
    env = {"NODAL_NATIVE_TOOLCHAIN": str(toolchain)}
    runner.run(("cmake", "--preset", NATIVE_PRESET), env=env)
    runner.run(("cmake", "--build", "--preset", NATIVE_PRESET), env=env)
    runner.run(("ctest", "--preset", NATIVE_PRESET), env=env)
    runner.run(
        (
            "cmake",
            "--build",
            str(root / "out" / "native" / "release"),
            "--target",
            "check-nodal-native",
        ),
        env=env,
    )
    lint_toolchain = _managed_lint_toolchain(
        root,
        runner,
        explicit=getattr(args, "lint_toolchain", None),
        require=False,
    )
    if lint_toolchain is not None:
        runner.run(
            _python(
                root,
                "run_clang_tools.py",
                "tidy",
                "--toolchain",
                str(lint_toolchain),
                "--build-dir",
                str(root / "out" / "native" / "release"),
            )
        )
    return 0


def command_style_bootstrap(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    command = _python(root, "bootstrap_lint_toolchain.py", "install")
    if args.prefix is not None:
        command.extend(("--prefix", str(args.prefix)))
    if args.force:
        command.append("--force")
    if args.dry_run:
        command.append("--dry-run")
    if args.json:
        command.append("--json")
    runner.run(command)
    return 0


def _style_policy_commands(root: Path, base_ref: str | None) -> list[list[str]]:
    contribution = _python(root, "check_contribution_policy.py")
    if base_ref:
        contribution.extend(("--base-ref", base_ref))
    return [
        _python(root, "check_markdown.py"),
        _python(root, "check_package_visibility.py"),
        contribution,
    ]


def command_style_check(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    lint_toolchain = _managed_lint_toolchain(
        root,
        runner,
        explicit=args.lint_toolchain,
        require=True,
    )
    assert lint_toolchain is not None
    for command in _style_policy_commands(root, args.base_ref):
        runner.run(command)
    runner.run(_mill(root, "mill.scalalib.scalafmt/checkFormatAll"))
    runner.run(_mill(root, "scalafix.check"))
    runner.run(
        _python(
            root,
            "run_clang_tools.py",
            "format",
            "--check",
            "--toolchain",
            str(lint_toolchain),
        )
    )
    return 0


def command_style_fix(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    lint_toolchain = _managed_lint_toolchain(
        root,
        runner,
        explicit=args.lint_toolchain,
        require=True,
    )
    assert lint_toolchain is not None
    runner.run(_mill(root, "mill.scalalib.scalafmt/reformatAll"))
    runner.run(_mill(root, "scalafix.fix"))
    runner.run(
        _python(
            root,
            "run_clang_tools.py",
            "format",
            "--fix",
            "--toolchain",
            str(lint_toolchain),
        )
    )
    for command in _style_policy_commands(root, None)[:2]:
        runner.run(command)
    return 0


def _contract_commands(root: Path, *, online_toolchain: bool) -> list[list[str]]:
    commands = [
        _python(root, "check_architecture.py"),
        _python(root, "check_scala_bootstrap.py"),
        _python(root, "check_native_toolchain.py"),
        _python(root, "check_native_compiler_bootstrap.py"),
        _python(root, "check_developer_commands.py"),
        _python(root, "check_formatting_baseline.py"),
        _python(root, "check_ci_baseline.py"),
        _python(root, "check_increment9.py"),
        _python(root, "check_increment10.py"),
        _python(root, "check_increment11.py"),
        _python(root, "check_increment12.py", "--compile-negative"),
    ]
    if online_toolchain:
        commands.append(_python(root, "check_native_toolchain.py", "--online"))
    for suite in (
        "architecture",
        "build",
        "toolchain",
        "compiler",
        "developer",
        "ci",
        "lint",
        "api",
    ):
        commands.append(
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                f"tests/{suite}",
                "-p",
                "test_*.py",
            ]
        )
    return commands


def command_check(args: argparse.Namespace, root: Path, runner: Runner) -> int:
    for command in _contract_commands(root, online_toolchain=args.online_toolchain):
        runner.run(command)
    command_style_check(args, root, runner)
    if args.contracts_only:
        return 0
    command_core_scala(args, root, runner)
    command_core_native(args, root, runner)
    return 0


def _safe_remove(root: Path, relative: str, *, dry_run: bool) -> None:
    target = root / relative
    resolved_root = root.resolve()
    resolved_target = target.resolve(strict=False)
    if not resolved_target.is_relative_to(resolved_root):
        raise DeveloperCommandError(
            "NODAL-DEV-003",
            f"refusing to remove path outside the repository: {target}",
        )
    print(f"remove {target}", flush=True)
    if dry_run or not target.exists() and not target.is_symlink():
        return
    if target.is_symlink() or target.is_file():
        target.unlink()
    else:
        shutil.rmtree(target)


def command_clean(args: argparse.Namespace, root: Path, _runner: Runner) -> int:
    generated = (
        "out",
        ".validation",
        ".native-build",
        ".bsp",
        ".bloop",
        ".metals",
        ".scala-build",
    )
    for relative in generated:
        _safe_remove(root, relative, dry_run=args.dry_run)
    if args.toolchains:
        _safe_remove(root, ".toolchains", dry_run=args.dry_run)
    return 0


def command_toolchain_doctor(
    args: argparse.Namespace,
    root: Path,
    runner: Runner,
) -> int:
    runner.run(_python(root, "check_native_toolchain.py"))
    if args.online:
        runner.run(_python(root, "check_native_toolchain.py", "--online"))
    toolchain = _managed_toolchain(
        root,
        runner,
        explicit=args.toolchain,
        require=args.require,
    )
    for command in (
        (sys.executable, "--version"),
        ("cmake", "--version"),
        ("ninja", "--version"),
    ):
        runner.run(command)
    if toolchain is None:
        print("managed native toolchain: not installed")
    else:
        print(f"managed native toolchain: {toolchain}")
    return 0


def command_library(_args: argparse.Namespace, _root: Path, _runner: Runner) -> int:
    raise DeveloperCommandError(
        "NODAL-DEV-004",
        "the library command namespace is reserved; reusable libraries are not part of the current core roadmap",
    )


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="nodal",
        description="Stable local and CI commands for developing Nodal core.",
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    bootstrap = subcommands.add_parser(
        "bootstrap", help="install or validate the locked native toolchain"
    )
    bootstrap.add_argument(
        "--mode", choices=("auto", "prebuilt", "source"), default="auto"
    )
    bootstrap.add_argument("--prefix", type=Path)
    bootstrap.add_argument("--jobs", type=int)
    bootstrap.add_argument("--force", action="store_true")
    bootstrap.add_argument("--dry-run", action="store_true")
    bootstrap.add_argument("--json", action="store_true")
    bootstrap.set_defaults(handler=command_bootstrap)

    core = subcommands.add_parser("core", help="build and test mandatory Nodal core")
    core_commands = core.add_subparsers(dest="core_command", required=True)
    core_scala = core_commands.add_parser("scala", help="compile and test Scala core")
    core_scala.set_defaults(handler=command_core_scala)
    core_native = core_commands.add_parser(
        "native", help="configure, build, lint, and test native core"
    )
    core_native.add_argument("--toolchain", type=Path)
    core_native.add_argument("--lint-toolchain", type=Path)
    core_native.set_defaults(handler=command_core_native)

    style = subcommands.add_parser(
        "style", help="bootstrap, check, or apply formatting and lint rules"
    )
    style_commands = style.add_subparsers(dest="style_command", required=True)
    style_bootstrap = style_commands.add_parser(
        "bootstrap", help="install pinned clang-format and clang-tidy tools"
    )
    style_bootstrap.add_argument("--prefix", type=Path)
    style_bootstrap.add_argument("--force", action="store_true")
    style_bootstrap.add_argument("--dry-run", action="store_true")
    style_bootstrap.add_argument("--json", action="store_true")
    style_bootstrap.set_defaults(handler=command_style_bootstrap)
    style_check = style_commands.add_parser(
        "check", help="check Scala, native, Markdown, visibility, and policy rules"
    )
    style_check.add_argument("--lint-toolchain", type=Path)
    style_check.add_argument("--base-ref")
    style_check.set_defaults(handler=command_style_check)
    style_fix = style_commands.add_parser(
        "fix", help="apply deterministic Scala and native formatting fixes"
    )
    style_fix.add_argument("--lint-toolchain", type=Path)
    style_fix.set_defaults(handler=command_style_fix)

    check = subcommands.add_parser(
        "check", help="run all core contracts, builds, lints, and tests"
    )
    check.add_argument("--toolchain", type=Path)
    check.add_argument("--lint-toolchain", type=Path)
    check.add_argument("--base-ref")
    check.add_argument("--online-toolchain", action="store_true")
    check.add_argument(
        "--contracts-only",
        action="store_true",
        help="run contracts, style gates, and Python suites without Scala/native builds",
    )
    check.set_defaults(handler=command_check)

    clean = subcommands.add_parser("clean", help="remove generated core outputs")
    clean.add_argument("--dry-run", action="store_true")
    clean.add_argument(
        "--toolchains",
        action="store_true",
        help="also remove repository-local managed toolchains",
    )
    clean.set_defaults(handler=command_clean)

    toolchain = subcommands.add_parser(
        "toolchain", help="inspect the locked native toolchain"
    )
    toolchain_commands = toolchain.add_subparsers(
        dest="toolchain_command", required=True
    )
    doctor = toolchain_commands.add_parser(
        "doctor", help="validate lock, host tools, and managed installation"
    )
    doctor.add_argument("--toolchain", type=Path)
    doctor.add_argument("--online", action="store_true")
    doctor.add_argument("--require", action="store_true")
    doctor.set_defaults(handler=command_toolchain_doctor)

    library = subcommands.add_parser(
        "library",
        help="reserved namespace for future independently selectable libraries",
    )
    library_commands = library.add_subparsers(dest="library_command", required=True)
    library_check = library_commands.add_parser(
        "check", help="reserved future library check command"
    )
    library_check.add_argument("library_id")
    library_check.set_defaults(handler=command_library)

    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    root: Path = ROOT,
    runner: Runner | None = None,
) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    active_runner = runner or Runner(root.resolve())
    try:
        return int(args.handler(args, root.resolve(), active_runner))
    except DeveloperCommandError as exc:
        print(exc, file=sys.stderr)
        return exc.exit_code
    except subprocess.CalledProcessError as exc:
        print(
            f"NODAL-DEV-005: command failed with exit code {exc.returncode}",
            file=sys.stderr,
        )
        return exc.returncode or 1
    except OSError as exc:
        print(f"NODAL-DEV-006: cannot execute developer command: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
