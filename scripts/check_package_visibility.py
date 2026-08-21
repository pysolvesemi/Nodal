#!/usr/bin/env python3
"""Enforce Scala package ownership and public/internal visibility boundaries."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$", re.MULTILINE)
IMPORT_INTERNAL = re.compile(r"^\s*import\s+nodal\.internal(?:\.|$)", re.MULTILINE)
MODULE_ROOTS = {
    "api": ROOT / "core" / "scala" / "api",
    "frontend": ROOT / "core" / "scala" / "frontend",
    "bridge": ROOT / "core" / "scala" / "bridge",
    "sim": ROOT / "core" / "scala" / "sim",
    "cli": ROOT / "core" / "scala" / "cli",
    "testkit": ROOT / "core" / "scala" / "testkit",
    "integrations": ROOT / "core" / "integrations",
}


@dataclass(frozen=True)
class Problem:
    code: str
    path: Path
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.path}: {self.message}"


def _scala_files(path: Path) -> list[Path]:
    return sorted(path.rglob("*.scala")) if path.exists() else []


def _package(content: str) -> str | None:
    match = PACKAGE.search(content)
    return match.group(1) if match else None


def _valid_package(module: str, package: str) -> bool:
    if module == "api":
        return (
            package == "nodal"
            or package.startswith("nodal.api")
            or package.startswith("nodal.bootstrap.api")
        ) and ".internal" not in package
    allowed = (
        f"nodal.internal.{module}",
        f"nodal.{module}.internal",
        f"nodal.bootstrap.{module}",
    )
    return any(package == prefix or package.startswith(prefix + ".") for prefix in allowed)


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    module_roots = {
        name: root / path.relative_to(ROOT)
        for name, path in MODULE_ROOTS.items()
    }
    for module, module_root in module_roots.items():
        for path in _scala_files(module_root):
            relative = path.relative_to(root)
            try:
                content = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                problems.append(Problem("NODAL-PKG-001", relative, f"cannot read Scala source: {exc}"))
                continue
            package = _package(content)
            if package is None:
                problems.append(Problem("NODAL-PKG-002", relative, "Scala source has no package declaration"))
                continue
            if not _valid_package(module, package):
                problems.append(
                    Problem(
                        "NODAL-PKG-003",
                        relative,
                        f"package {package!r} is not owned by module {module!r}",
                    )
                )
            if module == "api" and IMPORT_INTERNAL.search(content):
                problems.append(
                    Problem(
                        "NODAL-PKG-004",
                        relative,
                        "public API code must not import nodal.internal packages",
                    )
                )
            if module != "api" and (
                package == "nodal" or package.startswith("nodal.api")
            ):
                problems.append(
                    Problem(
                        "NODAL-PKG-005",
                        relative,
                        "only core/scala/api may own public nodal API packages",
                    )
                )
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"package visibility check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("package visibility check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
