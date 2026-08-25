#!/usr/bin/env python3
"""Run the pinned clang-format and clang-tidy tools over Nodal native sources."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

from bootstrap_lint_toolchain import (
    LintToolchainError,
    discover_toolchain,
    executable_path,
    load_lock,
)

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "core" / "compiler"
FORMAT_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}
TIDY_SUFFIXES = {".c", ".cc", ".cpp", ".cxx"}

# clang-tidy 22.1.8 reports StackAddressEscape through the locked MLIR
# type-registration templates even though the compiled registration and all
# native tests are valid. Disable only that analyzer check, only while checking
# the translation unit that instantiates those third-party templates.
TIDY_CHECK_WAIVERS = {
    Path("core/compiler/lib/Dialect/Nodal/NodalTypes.cpp"): (
        "clang-analyzer-core.StackAddressEscape",
        "locked MLIR type-registration template false positive",
    )
}


def _sources(suffixes: set[str]) -> list[Path]:
    if not SOURCE_ROOT.exists():
        return []
    return sorted(
        path
        for path in SOURCE_ROOT.rglob("*")
        if path.is_file() and path.suffix.lower() in suffixes
    )


def _toolchain(args: argparse.Namespace) -> Path:
    payload = discover_toolchain(args.toolchain, require=True)
    return Path(str(payload["found"]))


def _tidy_waiver(path: Path) -> tuple[str, str] | None:
    try:
        relative = path.resolve().relative_to(ROOT)
    except ValueError:
        return None
    return TIDY_CHECK_WAIVERS.get(relative)


def command_format(args: argparse.Namespace) -> int:
    prefix = _toolchain(args)
    executable = executable_path(prefix, "clang-format")
    files = _sources(FORMAT_SUFFIXES)
    if not files:
        print("no native files require formatting")
        return 0
    command = [str(executable), "--style=file"]
    if args.check:
        command.extend(("--dry-run", "--Werror"))
    else:
        command.append("-i")
    command.extend(str(path.relative_to(ROOT)) for path in files)
    subprocess.run(command, cwd=ROOT, check=True)
    print(
        f"clang-format checked {len(files)} file(s)"
        if args.check
        else f"clang-format updated {len(files)} file(s)"
    )
    return 0


def command_tidy(args: argparse.Namespace) -> int:
    prefix = _toolchain(args)
    executable = executable_path(prefix, "clang-tidy")
    build_dir = args.build_dir.expanduser().resolve()
    compile_commands = build_dir / "compile_commands.json"
    if not compile_commands.is_file():
        raise LintToolchainError(
            "NODAL-LINT-012",
            f"clang-tidy requires {compile_commands}; configure the native build first",
        )
    files = _sources(TIDY_SUFFIXES)
    if not files:
        print("no native translation units require clang-tidy")
        return 0
    header_filter = "^" + re.escape(str(SOURCE_ROOT.resolve())) + "/"
    for path in files:
        command = [
            str(executable),
            "-p",
            str(build_dir),
            f"--config-file={ROOT / '.clang-tidy'}",
            f"--header-filter={header_filter}",
            "--warnings-as-errors=*",
        ]
        waiver = _tidy_waiver(path)
        if waiver is not None:
            check, reason = waiver
            command.append(f"--checks=-{check}")
            print(f"clang-tidy waiver: {path.relative_to(ROOT)}: {check} ({reason})")
        command.append(str(path))
        subprocess.run(command, cwd=ROOT, check=True)
    print(f"clang-tidy checked {len(files)} translation unit(s)")
    return 0


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)

    format_parser = subcommands.add_parser("format")
    format_parser.add_argument("--toolchain", type=Path)
    mode = format_parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--fix", action="store_true")
    format_parser.set_defaults(handler=command_format)

    tidy = subcommands.add_parser("tidy")
    tidy.add_argument("--toolchain", type=Path)
    tidy.add_argument(
        "--build-dir",
        type=Path,
        default=ROOT / "out" / "native" / "release",
    )
    tidy.set_defaults(handler=command_tidy)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    try:
        load_lock()
        return int(args.handler(args))
    except LintToolchainError as exc:
        print(exc, file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as exc:
        print(
            f"NODAL-LINT-013: clang tool failed with exit code {exc.returncode}",
            file=sys.stderr,
        )
        return exc.returncode or 1
    except OSError as exc:
        print(f"NODAL-LINT-014: cannot execute clang tool: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
