#!/usr/bin/env python3
"""Shared loader and validation helpers for Nodal's checked toolchain lock."""

from __future__ import annotations

import hashlib
import json
import platform
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

LOCK_RELATIVE_PATH = Path("toolchains/lock.json")
MANIFEST_NAME = ".nodal-toolchain.json"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^[0-9]+(?:\.[0-9]+){1,3}$")
MUTABLE_REFS = {"main", "master", "head", "latest", "trunk"}


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


class LockError(RuntimeError):
    pass


def load_lock(root: Path) -> dict[str, Any]:
    path = root.resolve() / LOCK_RELATIVE_PATH
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise LockError(f"cannot read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise LockError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise LockError(f"{path} must contain a JSON object")
    return value


def canonical_lock_bytes(lock: dict[str, Any]) -> bytes:
    return (json.dumps(lock, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def lock_digest(lock: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_lock_bytes(lock)).hexdigest()


def parse_version(value: str) -> tuple[int, ...]:
    match = re.match(r"^\s*([0-9]+(?:\.[0-9]+){0,3})", value)
    if not match:
        raise ValueError(f"not a numeric version: {value!r}")
    return tuple(int(part) for part in match.group(1).split("."))


def version_at_least(actual: str, minimum: str) -> bool:
    lhs = parse_version(actual)
    rhs = parse_version(minimum)
    length = max(len(lhs), len(rhs))
    return lhs + (0,) * (length - len(lhs)) >= rhs + (0,) * (length - len(rhs))


def normalize_host(
    system: str | None = None,
    machine: str | None = None,
) -> tuple[str, str]:
    system_name = (system or platform.system()).strip().lower()
    machine_name = (machine or platform.machine()).strip().lower()

    os_aliases = {
        "linux": "linux",
        "darwin": "macos",
        "macos": "macos",
        "windows": "windows",
        "msys": "windows",
        "cygwin": "windows",
    }
    arch_aliases = {
        "x86_64": "x86_64",
        "amd64": "x86_64",
        "x64": "x86_64",
        "aarch64": "aarch64",
        "arm64": "aarch64",
    }

    normalized_os = next(
        (value for key, value in os_aliases.items() if system_name.startswith(key)),
        system_name,
    )
    normalized_arch = arch_aliases.get(machine_name, machine_name)
    return normalized_os, normalized_arch


def assets(lock: dict[str, Any]) -> list[dict[str, Any]]:
    value = lock.get("assets")
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def source_asset(lock: dict[str, Any]) -> dict[str, Any]:
    matches = [item for item in assets(lock) if item.get("kind") == "source"]
    if len(matches) != 1:
        raise LockError(f"expected exactly one source asset, found {len(matches)}")
    return matches[0]


def prebuilt_asset(
    lock: dict[str, Any],
    host: tuple[str, str] | None = None,
) -> dict[str, Any] | None:
    os_name, arch = host or normalize_host()
    for item in assets(lock):
        if item.get("kind") != "prebuilt":
            continue
        item_host = item.get("host")
        if isinstance(item_host, dict) and item_host.get("os") == os_name and item_host.get("arch") == arch:
            return item
    return None


def expected_install_id(lock: dict[str, Any], host: tuple[str, str] | None = None) -> str:
    os_name, arch = host or normalize_host()
    return f"{lock.get('lock_id', 'nodal-native')}-{os_name}-{arch}"


def required_install_paths(os_name: str | None = None) -> tuple[Path, ...]:
    normalized_os, _ = normalize_host(system=os_name) if os_name else normalize_host()
    executable_suffix = ".exe" if normalized_os == "windows" else ""
    return (
        Path("bin") / f"circt-opt{executable_suffix}",
        Path("bin") / f"mlir-opt{executable_suffix}",
        Path("lib/cmake/circt/CIRCTConfig.cmake"),
        Path("lib/cmake/mlir/MLIRConfig.cmake"),
        Path("lib/cmake/llvm/LLVMConfig.cmake"),
    )


def _string(mapping: dict[str, Any], name: str) -> str | None:
    value = mapping.get(name)
    return value if isinstance(value, str) and value else None


def _contains_mutable_ref(value: str) -> bool:
    lowered = value.lower()
    if lowered in MUTABLE_REFS:
        return True
    return any(
        token in lowered
        for token in (
            "/heads/main",
            "/heads/master",
            "/tree/main",
            "/tree/master",
            "/latest/",
            "refs/heads/",
        )
    )


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_lock(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    try:
        lock = load_lock(root)
    except LockError as exc:
        return [Problem("NODAL-TOOLCHAIN-001", str(exc))]

    if lock.get("schema") != 1:
        problems.append(Problem("NODAL-TOOLCHAIN-002", "toolchains/lock.json must use schema = 1"))

    lock_id = _string(lock, "lock_id")
    if not lock_id or not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", lock_id):
        problems.append(Problem("NODAL-TOOLCHAIN-003", "lock_id must be a stable lowercase identifier"))

    policy = lock.get("policy")
    expected_policy = {
        "immutable_revisions_only": True,
        "circt_llvm_pair_source": "circt-submodule",
        "release_assets_require_sha256": True,
        "source_build_fallback_required": True,
        "allow_unpinned_default_branch": False,
    }
    if not isinstance(policy, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-004", "policy must be a JSON object"))
    else:
        for key, expected in expected_policy.items():
            if policy.get(key) != expected:
                problems.append(
                    Problem("NODAL-TOOLCHAIN-004", f"policy {key!r} must remain {expected!r}")
                )

    scala = lock.get("scala")
    if not isinstance(scala, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-005", "scala pins must be a JSON object"))
        scala = {}
    for key in ("version", "mill_version", "jvm", "utest_version"):
        if not _string(scala, key):
            problems.append(Problem("NODAL-TOOLCHAIN-005", f"missing Scala-side pin: {key}"))

    native = lock.get("native")
    if not isinstance(native, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-006", "native pins must be a JSON object"))
        return problems

    circt = native.get("circt")
    llvm = native.get("llvm")
    if not isinstance(circt, dict) or not isinstance(llvm, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-006", "native.circt and native.llvm must be objects"))
        return problems

    expected_repositories = {
        "circt": "https://github.com/llvm/circt.git",
        "llvm": "https://github.com/llvm/llvm-project.git",
    }
    if circt.get("repository") != expected_repositories["circt"]:
        problems.append(Problem("NODAL-TOOLCHAIN-007", "CIRCT repository URL is not canonical"))
    if llvm.get("repository") != expected_repositories["llvm"]:
        problems.append(Problem("NODAL-TOOLCHAIN-007", "LLVM repository URL is not canonical"))

    release_tag = _string(circt, "release_tag")
    circt_commit = _string(circt, "commit")
    submodule_commit = _string(circt, "llvm_submodule_commit")
    llvm_commit = _string(llvm, "commit")

    if not release_tag or not re.fullmatch(r"firtool-[0-9]+\.[0-9]+\.[0-9]+", release_tag):
        problems.append(Problem("NODAL-TOOLCHAIN-008", "CIRCT release_tag must be an exact firtool release"))
    if release_tag and _contains_mutable_ref(release_tag):
        problems.append(Problem("NODAL-TOOLCHAIN-009", "CIRCT release tag must not be mutable"))

    for name, value in (
        ("CIRCT commit", circt_commit),
        ("CIRCT LLVM submodule commit", submodule_commit),
        ("LLVM commit", llvm_commit),
    ):
        if not value or not SHA40.fullmatch(value):
            problems.append(Problem("NODAL-TOOLCHAIN-010", f"{name} must be a full 40-character SHA"))
        elif _contains_mutable_ref(value):
            problems.append(Problem("NODAL-TOOLCHAIN-009", f"{name} must not use a mutable ref"))

    if submodule_commit and llvm_commit and submodule_commit != llvm_commit:
        problems.append(
            Problem(
                "NODAL-TOOLCHAIN-011",
                "LLVM commit must exactly match the LLVM submodule pinned by CIRCT",
            )
        )

    requirements = native.get("requirements")
    if not isinstance(requirements, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-012", "native requirements must be an object"))
    else:
        for key in (
            "python_minimum",
            "cmake_minimum",
            "cmake_preferred",
            "ninja_minimum",
        ):
            value = requirements.get(key)
            if not isinstance(value, str) or not VERSION.fullmatch(value):
                problems.append(Problem("NODAL-TOOLCHAIN-012", f"invalid version requirement: {key}"))
        try:
            if not version_at_least(str(requirements.get("python_minimum", "0")), "3.11.0"):
                problems.append(Problem("NODAL-TOOLCHAIN-012", "Python minimum must be at least 3.11.0"))
            if not version_at_least(str(requirements.get("cmake_minimum", "0")), "3.20.0"):
                problems.append(Problem("NODAL-TOOLCHAIN-012", "CMake minimum must be at least 3.20.0"))
            if not version_at_least(str(requirements.get("ninja_minimum", "0")), "1.10.0"):
                problems.append(Problem("NODAL-TOOLCHAIN-012", "Ninja minimum must be at least 1.10.0"))
        except ValueError:
            pass
        if requirements.get("cxx_standard") != 17:
            problems.append(Problem("NODAL-TOOLCHAIN-012", "C++17 is the required source-build baseline"))

    source_build = native.get("source_build")
    if not isinstance(source_build, dict):
        problems.append(Problem("NODAL-TOOLCHAIN-013", "native.source_build must be an object"))
    else:
        expected_source_build = {
            "generator": "Ninja",
            "build_type": "Release",
            "llvm_enable_projects": ["mlir"],
            "llvm_targets_to_build": ["host"],
            "llvm_enable_assertions": True,
            "circt_slang_frontend_enabled": False,
            "circt_python_bindings_enabled": False,
        }
        for key, expected in expected_source_build.items():
            if source_build.get(key) != expected:
                problems.append(
                    Problem("NODAL-TOOLCHAIN-013", f"source-build setting {key!r} must be {expected!r}")
                )

    asset_list = lock.get("assets")
    if not isinstance(asset_list, list) or not asset_list:
        problems.append(Problem("NODAL-TOOLCHAIN-014", "assets must be a non-empty list"))
        return problems

    seen_ids: set[str] = set()
    seen_files: set[str] = set()
    seen_hosts: set[tuple[str, str]] = set()
    source_count = 0

    for index, item in enumerate(asset_list):
        if not isinstance(item, dict):
            problems.append(Problem("NODAL-TOOLCHAIN-014", f"asset {index} must be an object"))
            continue
        asset_id = _string(item, "id")
        kind = item.get("kind")
        filename = _string(item, "filename")
        url = _string(item, "url")
        digest = _string(item, "sha256")
        checksum_name = _string(item, "checksum_file")

        if not asset_id or asset_id in seen_ids:
            problems.append(Problem("NODAL-TOOLCHAIN-015", f"asset id is missing or duplicated: {asset_id!r}"))
        else:
            seen_ids.add(asset_id)
        if not filename or filename in seen_files:
            problems.append(
                Problem("NODAL-TOOLCHAIN-015", f"asset filename is missing or duplicated: {filename!r}")
            )
        else:
            seen_files.add(filename)

        if kind not in {"prebuilt", "source"}:
            problems.append(Problem("NODAL-TOOLCHAIN-016", f"asset {asset_id!r} has invalid kind"))
        if kind == "source":
            source_count += 1

        if not digest or not SHA256.fullmatch(digest):
            problems.append(Problem("NODAL-TOOLCHAIN-017", f"asset {asset_id!r} has invalid SHA-256"))

        if not url or not filename or not release_tag:
            problems.append(Problem("NODAL-TOOLCHAIN-018", f"asset {asset_id!r} has incomplete URL metadata"))
        else:
            expected_url = (
                f"https://github.com/llvm/circt/releases/download/{release_tag}/{filename}"
            )
            if url != expected_url or _contains_mutable_ref(url):
                problems.append(
                    Problem("NODAL-TOOLCHAIN-018", f"asset {asset_id!r} is not tied to the exact release")
                )

        if kind == "prebuilt":
            item_host = item.get("host")
            if not isinstance(item_host, dict):
                problems.append(Problem("NODAL-TOOLCHAIN-019", f"prebuilt {asset_id!r} lacks host"))
            else:
                os_name = _string(item_host, "os")
                arch = _string(item_host, "arch")
                if not os_name or not arch:
                    problems.append(Problem("NODAL-TOOLCHAIN-019", f"prebuilt {asset_id!r} has invalid host"))
                elif (os_name, arch) in seen_hosts:
                    problems.append(
                        Problem("NODAL-TOOLCHAIN-019", f"duplicate prebuilt host: {os_name}-{arch}")
                    )
                else:
                    seen_hosts.add((os_name, arch))
        elif item.get("host") is not None:
            problems.append(Problem("NODAL-TOOLCHAIN-019", "source asset host must be null"))

        if checksum_name and filename and digest:
            checksum_path = (root / checksum_name).resolve()
            if not checksum_path.is_relative_to((root / "toolchains/checksums").resolve()):
                problems.append(
                    Problem("NODAL-TOOLCHAIN-020", f"checksum file escapes toolchains/checksums: {checksum_name}")
                )
            elif not checksum_path.is_file():
                problems.append(Problem("NODAL-TOOLCHAIN-020", f"missing checksum file: {checksum_name}"))
            else:
                expected_line = f"{digest}  {filename}\n"
                try:
                    actual_line = checksum_path.read_text(encoding="utf-8")
                except OSError as exc:
                    problems.append(Problem("NODAL-TOOLCHAIN-020", f"cannot read {checksum_name}: {exc}"))
                else:
                    if actual_line != expected_line:
                        problems.append(
                            Problem("NODAL-TOOLCHAIN-021", f"checksum file does not match lock: {checksum_name}")
                        )
        else:
            problems.append(Problem("NODAL-TOOLCHAIN-020", f"asset {asset_id!r} lacks checksum_file"))

    if source_count != 1:
        problems.append(Problem("NODAL-TOOLCHAIN-022", "exactly one source fallback asset is required"))

    expected_hosts = {
        ("linux", "x86_64"),
        ("macos", "x86_64"),
        ("macos", "aarch64"),
    }
    if seen_hosts != expected_hosts:
        problems.append(
            Problem(
                "NODAL-TOOLCHAIN-023",
                f"prebuilt host set must be {sorted(expected_hosts)}, found {sorted(seen_hosts)}",
            )
        )

    build_file = root / "build.mill"
    if build_file.is_file() and isinstance(scala, dict):
        try:
            build_text = _read(build_file)
            mill_text = _read(root / ".mill-version").strip()
        except OSError as exc:
            problems.append(Problem("NODAL-TOOLCHAIN-024", f"cannot cross-check Scala pins: {exc}"))
        else:
            expected_fragments = (
                f'val scala3 = "{scala.get("version")}"',
                f'//| mill-version: {scala.get("mill_version")}',
                f'//| mill-jvm-version: {scala.get("jvm")}',
                f'mvn"com.lihaoyi::utest:{scala.get("utest_version")}"',
            )
            for fragment in expected_fragments:
                if fragment not in build_text:
                    problems.append(
                        Problem("NODAL-TOOLCHAIN-024", f"build.mill disagrees with lock: {fragment}")
                    )
            if mill_text != scala.get("mill_version"):
                problems.append(
                    Problem("NODAL-TOOLCHAIN-024", ".mill-version disagrees with toolchains/lock.json")
                )

    return problems


def format_problems(problems: Iterable[Problem]) -> str:
    return "\n".join(str(problem) for problem in problems)


def require_valid_lock(root: Path) -> dict[str, Any]:
    problems = validate_lock(root)
    if problems:
        raise LockError(format_problems(problems))
    return load_lock(root)


def current_python_satisfies(lock: dict[str, Any]) -> bool:
    minimum = lock["native"]["requirements"]["python_minimum"]
    actual = ".".join(str(part) for part in sys.version_info[:3])
    return version_at_least(actual, minimum)
