#!/usr/bin/env python3
"""Install, discover, and validate Nodal's pinned native lint tools."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import venv
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "toolchains" / "lint-lock.json"
DEFAULT_PREFIX = ROOT / ".toolchains" / "lint"
MANIFEST = ".nodal-lint-toolchain.json"


class LintToolchainError(RuntimeError):
    """Expected lint-toolchain failure with a stable diagnostic code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code

    def __str__(self) -> str:
        return f"{self.code}: {super().__str__()}"


@dataclass(frozen=True)
class ToolSpec:
    package: str
    version: str
    executable: str


@dataclass(frozen=True)
class Lock:
    lock_id: str
    digest: str
    tools: tuple[ToolSpec, ...]


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise LintToolchainError("NODAL-LINT-001", f"cannot read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise LintToolchainError("NODAL-LINT-002", f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise LintToolchainError("NODAL-LINT-002", f"{path} must contain a JSON object")
    return value


def load_lock(path: Path = LOCK_PATH) -> Lock:
    raw = path.read_bytes()
    value = _load_json(path)
    if value.get("schema") != 1:
        raise LintToolchainError("NODAL-LINT-003", "unsupported lint lock schema")
    lock_id = value.get("lock_id")
    native = value.get("native")
    if not isinstance(lock_id, str) or not lock_id:
        raise LintToolchainError("NODAL-LINT-003", "lint lock_id is missing")
    if not isinstance(native, dict):
        raise LintToolchainError("NODAL-LINT-003", "native lint tools are missing")
    tools: list[ToolSpec] = []
    for key in ("clang_format", "clang_tidy"):
        item = native.get(key)
        if not isinstance(item, dict):
            raise LintToolchainError("NODAL-LINT-003", f"native.{key} is missing")
        package = item.get("package")
        version = item.get("version")
        executable = item.get("executable")
        if not all(isinstance(part, str) and part for part in (package, version, executable)):
            raise LintToolchainError("NODAL-LINT-003", f"native.{key} is incomplete")
        tools.append(ToolSpec(package, version, executable))
    return Lock(lock_id, hashlib.sha256(raw).hexdigest(), tuple(tools))


def _bin_dir(prefix: Path) -> Path:
    return prefix / ("Scripts" if os.name == "nt" else "bin")


def executable_path(prefix: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return _bin_dir(prefix) / f"{name}{suffix}"


def _run_version(path: Path) -> str:
    try:
        result = subprocess.run(
            [str(path), "--version"],
            check=True,
            text=True,
            capture_output=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise LintToolchainError(
            "NODAL-LINT-004", f"cannot execute {path} --version: {exc}"
        ) from exc
    return (result.stdout + result.stderr).strip()


def inspect_toolchain(prefix: Path, lock: Lock) -> dict[str, Any]:
    prefix = prefix.expanduser().resolve()
    manifest_path = prefix / MANIFEST
    manifest = _load_json(manifest_path)
    if manifest.get("lock_id") != lock.lock_id:
        raise LintToolchainError(
            "NODAL-LINT-005",
            f"{prefix} uses lint lock {manifest.get('lock_id')!r}, expected {lock.lock_id!r}",
        )
    if manifest.get("lock_sha256") != lock.digest:
        raise LintToolchainError(
            "NODAL-LINT-005", f"{prefix} was installed from a different lint lock"
        )
    versions: dict[str, str] = {}
    for tool in lock.tools:
        path = executable_path(prefix, tool.executable)
        if not path.is_file():
            raise LintToolchainError("NODAL-LINT-006", f"missing lint executable: {path}")
        output = _run_version(path)
        if tool.version not in output:
            raise LintToolchainError(
                "NODAL-LINT-007",
                f"{tool.executable} reports {output!r}, expected version {tool.version}",
            )
        versions[tool.executable] = output
    return {
        "found": str(prefix),
        "lock_id": lock.lock_id,
        "lock_sha256": lock.digest,
        "versions": versions,
    }


def _candidate_prefixes(explicit: Path | None) -> list[Path]:
    values: list[Path] = []
    if explicit is not None:
        values.append(explicit)
    environment = os.environ.get("NODAL_LINT_TOOLCHAIN")
    if environment:
        values.append(Path(environment))
    values.append(DEFAULT_PREFIX)
    unique: list[Path] = []
    seen: set[Path] = set()
    for value in values:
        resolved = value.expanduser().resolve()
        if resolved not in seen:
            seen.add(resolved)
            unique.append(resolved)
    return unique


def discover_toolchain(
    explicit: Path | None = None,
    *,
    require: bool = False,
    lock: Lock | None = None,
) -> dict[str, Any]:
    active_lock = lock or load_lock()
    rejected: dict[str, str] = {}
    for candidate in _candidate_prefixes(explicit):
        if not candidate.exists():
            continue
        try:
            payload = inspect_toolchain(candidate, active_lock)
        except LintToolchainError as exc:
            rejected[str(candidate)] = str(exc)
            continue
        payload["rejected"] = rejected
        return payload
    if require:
        details = "; ".join(f"{path}: {reason}" for path, reason in rejected.items())
        suffix = f" Rejected: {details}" if details else ""
        raise LintToolchainError(
            "NODAL-LINT-008",
            "no validated lint toolchain was found; run './nodal style bootstrap'." + suffix,
        )
    return {
        "found": None,
        "lock_id": active_lock.lock_id,
        "lock_sha256": active_lock.digest,
        "rejected": rejected,
    }


def _safe_remove(prefix: Path) -> None:
    resolved = prefix.resolve()
    if resolved == Path(resolved.anchor) or len(resolved.parts) < 3:
        raise LintToolchainError("NODAL-LINT-009", f"refusing to remove unsafe path: {prefix}")
    if resolved.exists():
        shutil.rmtree(resolved)


def install_toolchain(prefix: Path, *, force: bool, dry_run: bool) -> dict[str, Any]:
    lock = load_lock()
    prefix = prefix.expanduser().resolve()
    plan = {
        "lock_id": lock.lock_id,
        "prefix": str(prefix),
        "packages": [f"{tool.package}=={tool.version}" for tool in lock.tools],
        "dry_run": dry_run,
    }
    if dry_run:
        return plan
    if prefix.exists():
        try:
            return inspect_toolchain(prefix, lock)
        except LintToolchainError:
            if not force:
                raise LintToolchainError(
                    "NODAL-LINT-010",
                    f"{prefix} exists but does not match the lock; pass --force to replace it",
                )
            _safe_remove(prefix)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    try:
        venv.EnvBuilder(with_pip=True, clear=False).create(prefix)
        python = executable_path(prefix, "python")
        command: Sequence[str] = (
            str(python),
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-input",
            *(f"{tool.package}=={tool.version}" for tool in lock.tools),
        )
        subprocess.run(command, check=True)
        (prefix / MANIFEST).write_text(
            json.dumps(
                {
                    "schema": 1,
                    "lock_id": lock.lock_id,
                    "lock_sha256": lock.digest,
                    "packages": plan["packages"],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        return inspect_toolchain(prefix, lock)
    except Exception:
        _safe_remove(prefix)
        raise


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)

    status = subcommands.add_parser("status")
    status.add_argument("--toolchain", type=Path)
    status.add_argument("--require", action="store_true")
    status.add_argument("--json", action="store_true")

    install = subcommands.add_parser("install")
    install.add_argument("--prefix", type=Path, default=DEFAULT_PREFIX)
    install.add_argument("--force", action="store_true")
    install.add_argument("--dry-run", action="store_true")
    install.add_argument("--json", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    try:
        if args.command == "status":
            payload = discover_toolchain(args.toolchain, require=args.require)
        else:
            payload = install_toolchain(
                args.prefix,
                force=args.force,
                dry_run=args.dry_run,
            )
        if args.json:
            print(json.dumps(payload, indent=2, sort_keys=True))
        else:
            if payload.get("found"):
                print(f"lint toolchain: {payload['found']}")
            else:
                print(f"lint toolchain plan: {payload['prefix']}")
            print(f"lock: {payload['lock_id']}")
        return 0
    except LintToolchainError as exc:
        print(exc, file=sys.stderr)
        return 1
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"NODAL-LINT-011: lint toolchain operation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
