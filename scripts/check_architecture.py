#!/usr/bin/env python3
"""Validate Nodal core module and future-library dependency boundaries."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:
    import tomllib
except ModuleNotFoundError as exc:  # pragma: no cover - depends on host Python
    raise SystemExit("NODAL-ARCH-000: Python 3.11 or newer is required") from exc


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


@dataclass(frozen=True)
class Module:
    module_id: str
    path: Path
    role: str
    dependencies: tuple[str, ...]
    descriptor: Path


SOURCE_SUFFIXES = {
    ".scala",
    ".sc",
    ".java",
    ".kt",
    ".c",
    ".cc",
    ".cpp",
    ".cxx",
    ".h",
    ".hh",
    ".hpp",
    ".td",
    ".mlir",
    ".cmake",
    ".mill",
    ".sbt",
}
SOURCE_FILENAMES = {"CMakeLists.txt", "Makefile"}
CORE_LIBRARY_PATTERNS = (
    re.compile(r"^\s*(?:import|export)\s+(?:_root_\.)?(?:nodal\.)?libraries(?:\.|\s|\{|$)"),
    re.compile(r"(?:^|[\"'<(])libraries/"),
    re.compile(r"\blibraries\.[A-Za-z_][A-Za-z0-9_.-]*"),
)
COMPILER_FRONTEND_PATTERNS = (
    re.compile(r"core/scala/frontend"),
    re.compile(r"\bcore\.scala\.frontend\b"),
    re.compile(r"\bnodal\.internal\.frontend\b"),
)


def _load_toml(path: Path) -> dict:
    with path.open("rb") as stream:
        return tomllib.load(stream)


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
    except ValueError:
        return False
    return True


def _source_files(root: Path) -> Iterable[Path]:
    if not root.exists():
        return ()
    return (
        path
        for path in root.rglob("*")
        if path.is_file()
        and (path.suffix in SOURCE_SUFFIXES or path.name in SOURCE_FILENAMES)
    )


def _find_cycle(modules: dict[str, Module]) -> tuple[str, ...] | None:
    visiting: set[str] = set()
    visited: set[str] = set()
    stack: list[str] = []

    def visit(module_id: str) -> tuple[str, ...] | None:
        if module_id in visiting:
            start = stack.index(module_id)
            return tuple(stack[start:] + [module_id])
        if module_id in visited:
            return None

        visiting.add(module_id)
        stack.append(module_id)
        for dependency in modules[module_id].dependencies:
            if dependency in modules:
                cycle = visit(dependency)
                if cycle:
                    return cycle
        stack.pop()
        visiting.remove(module_id)
        visited.add(module_id)
        return None

    for module_id in sorted(modules):
        cycle = visit(module_id)
        if cycle:
            return cycle
    return None


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    manifest_path = root / "core" / "modules.toml"

    if not manifest_path.is_file():
        return [Problem("NODAL-ARCH-001", f"missing manifest: {manifest_path}")]

    try:
        manifest = _load_toml(manifest_path)
    except (OSError, tomllib.TOMLDecodeError) as exc:
        return [Problem("NODAL-ARCH-002", f"cannot read {manifest_path}: {exc}")]

    if manifest.get("schema") != 1:
        problems.append(Problem("NODAL-ARCH-003", "core/modules.toml must use schema = 1"))

    policy = manifest.get("policy", {})
    if not isinstance(policy, dict):
        problems.append(Problem("NODAL-ARCH-014", "manifest policy must be a TOML table"))
        policy = {}
    required_false_policies = (
        "allow_core_to_libraries",
        "allow_compiler_to_frontend",
        "allow_dependency_cycles",
        "allow_empty_library_placeholders",
    )
    for policy_name in required_false_policies:
        if policy.get(policy_name) is not False:
            problems.append(
                Problem("NODAL-ARCH-014", f"architecture policy must remain false: {policy_name}")
            )

    core_root = root / str(manifest.get("core_root", "core"))
    library_root = root / str(manifest.get("reserved_library_root", "libraries"))
    library_descriptor_name = str(manifest.get("future_library_descriptor", "library.toml"))
    library_module_prefix = str(manifest.get("future_library_module_prefix", "libraries."))
    descriptor_names = manifest.get("descriptors", [])
    if (
        not isinstance(descriptor_names, list)
        or not descriptor_names
        or not all(isinstance(name, str) and name for name in descriptor_names)
    ):
        problems.append(Problem("NODAL-ARCH-004", "manifest descriptors must be a non-empty list"))
        return problems
    if len(descriptor_names) != len(set(descriptor_names)):
        problems.append(Problem("NODAL-ARCH-004", "manifest descriptor paths must be unique"))

    declared_descriptor_paths = {(root / name).resolve() for name in descriptor_names}
    discovered_descriptor_paths = {
        path.resolve() for path in core_root.rglob("module.toml") if path.is_file()
    } if core_root.exists() else set()
    for undeclared in sorted(discovered_descriptor_paths - declared_descriptor_paths):
        problems.append(
            Problem(
                "NODAL-ARCH-015",
                f"module descriptor is not registered in core/modules.toml: {undeclared.relative_to(root)}",
            )
        )

    modules: dict[str, Module] = {}
    paths: set[Path] = set()

    for relative_name in descriptor_names:
        descriptor = root / str(relative_name)
        if not descriptor.is_file():
            problems.append(Problem("NODAL-ARCH-005", f"missing module descriptor: {relative_name}"))
            continue
        try:
            data = _load_toml(descriptor)
        except (OSError, tomllib.TOMLDecodeError) as exc:
            problems.append(Problem("NODAL-ARCH-002", f"cannot read {relative_name}: {exc}"))
            continue

        module_id = data.get("id")
        module_path_value = data.get("path")
        kind = data.get("kind")
        role = data.get("role")
        visibility = data.get("visibility")
        owner = data.get("owner")
        description = data.get("description")
        dependencies = data.get("dependencies", [])

        if data.get("schema") != 1 or not isinstance(module_id, str) or not module_id:
            problems.append(Problem("NODAL-ARCH-006", f"invalid schema or id in {relative_name}"))
            continue
        if module_id in modules:
            problems.append(Problem("NODAL-ARCH-006", f"duplicate module id: {module_id}"))
            continue
        if not isinstance(module_path_value, str) or not module_path_value:
            problems.append(Problem("NODAL-ARCH-006", f"invalid path for {module_id}"))
            continue
        required_strings = (kind, role, visibility, owner, description)
        if not all(isinstance(value, str) and value for value in required_strings):
            problems.append(
                Problem(
                    "NODAL-ARCH-006",
                    f"{module_id} must declare kind, role, visibility, owner, and description",
                )
            )
            continue
        if not isinstance(dependencies, list) or not all(isinstance(item, str) for item in dependencies):
            problems.append(Problem("NODAL-ARCH-006", f"invalid dependencies for {module_id}"))
            continue

        module_path = (root / module_path_value).resolve()
        expected_descriptor = module_path / "module.toml"
        if descriptor.resolve() != expected_descriptor:
            problems.append(
                Problem("NODAL-ARCH-006", f"{module_id} descriptor path does not match declared module path")
            )
        if not module_path.is_dir():
            problems.append(Problem("NODAL-ARCH-005", f"module directory does not exist: {module_path_value}"))
        if not _is_relative_to(module_path, core_root.resolve()):
            problems.append(Problem("NODAL-ARCH-007", f"core module is outside core/: {module_id}"))
        if module_path in paths:
            problems.append(Problem("NODAL-ARCH-006", f"duplicate module path: {module_path_value}"))

        paths.add(module_path)
        modules[module_id] = Module(
            module_id=module_id,
            path=module_path,
            role=role,
            dependencies=tuple(dependencies),
            descriptor=descriptor.resolve(),
        )

    for module in modules.values():
        for dependency in module.dependencies:
            if dependency.startswith(library_module_prefix):
                problems.append(
                    Problem("NODAL-ARCH-007", f"{module.module_id} illegally depends on future library {dependency}")
                )
            elif dependency not in modules:
                problems.append(
                    Problem("NODAL-ARCH-009", f"{module.module_id} references undeclared dependency {dependency}")
                )

    compiler = modules.get("core.compiler")
    if compiler:
        frontend_ids = {module.module_id for module in modules.values() if module.role == "frontend"}
        pending = list(compiler.dependencies)
        seen: set[str] = set()
        while pending:
            dependency = pending.pop()
            if dependency in seen:
                continue
            seen.add(dependency)
            if dependency in frontend_ids:
                problems.append(
                    Problem("NODAL-ARCH-008", "core.compiler must not depend on Scala frontend internals")
                )
                break
            if dependency in modules:
                pending.extend(modules[dependency].dependencies)

    cycle = _find_cycle(modules)
    if cycle:
        problems.append(Problem("NODAL-ARCH-010", f"dependency cycle: {' -> '.join(cycle)}"))

    for source in _source_files(core_root):
        try:
            lines = source.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(lines, start=1):
            if any(pattern.search(line) for pattern in CORE_LIBRARY_PATTERNS):
                problems.append(
                    Problem(
                        "NODAL-ARCH-011",
                        f"core source references future libraries: {source.relative_to(root)}:{line_number}",
                    )
                )
            if compiler and _is_relative_to(source.resolve(), compiler.path):
                if any(pattern.search(line) for pattern in COMPILER_FRONTEND_PATTERNS):
                    problems.append(
                        Problem(
                            "NODAL-ARCH-012",
                            f"native compiler references frontend internals: {source.relative_to(root)}:{line_number}",
                        )
                    )

    if library_root.exists() and not library_root.is_dir():
        problems.append(
            Problem("NODAL-ARCH-013", f"reserved library root is not a directory: {library_root.relative_to(root)}")
        )
    elif library_root.is_dir():
        for child in sorted(library_root.iterdir()):
            if child.is_dir() and not (child / library_descriptor_name).is_file():
                problems.append(
                    Problem(
                        "NODAL-ARCH-013",
                        f"future library directory lacks {library_descriptor_name} and is treated as a placeholder: {child.relative_to(root)}",
                    )
                )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"architecture check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1

    print("architecture check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
