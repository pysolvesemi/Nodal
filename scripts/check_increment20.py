#!/usr/bin/env python3
"""Validate Increment 20: deterministic Scala-to-MLIR bridge and process protocol."""

from __future__ import annotations

import argparse
import json
import re
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
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
    "docs/design-gates/NodalScalaToMlirBridge-DG-v1.0.md",
    "docs/implementation/increment20-scala-mlir-bridge.md",
    "tests/compiler/fixtures/increment20/manifest.json",
    "tests/compiler/test_increment20.py",
    "scripts/check_increment20.py",
    ".github/workflows/increment-20-scala-mlir-bridge.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment20.py",
    ".github/workflows/increment-20-materialize.yml",
    ".github/workflows/increment-20-finalize.yml",
    ".github/workflows/increment-20-supervisor.yml",
)


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def roadmap_revision(text: str) -> tuple[int, ...]:
    matches = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(matches) != 1:
        return ()
    try:
        return tuple(int(part) for part in matches[0].split("."))
    except ValueError:
        return ()


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC20-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem(
                    "NODAL-INC20-002",
                    f"temporary implementation file remains: {relative}",
                )
            )

    serializer = read(
        root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
        problems,
        "NODAL-INC20-003",
    )
    process = read(
        root / "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
        problems,
        "NODAL-INC20-004",
    )
    tests = read(
        root
        / "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
        problems,
        "NODAL-INC20-005",
    )
    gate = read(
        root / "docs/design-gates/NodalScalaToMlirBridge-DG-v1.0.md",
        problems,
        "NODAL-INC20-006",
    )
    workflow = read(
        root / ".github/workflows/increment-20-scala-mlir-bridge.yml",
        problems,
        "NODAL-INC20-007",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC20-008",
    )

    require(
        serializer,
        (
            'val Schema: String = "nodal.scala-to-mlir"',
            "val Version: Int = 1",
            "ConstructionKernel.inspect",
            '"nodal.module"',
            '"nodal.port"',
            '"nodal.parameter"',
            '"nodal.instance"',
            '"nodal.interface_abi"',
            '"nodal.resolved_net"',
            '"nodal.terminal"',
            '"nodal.branch"',
            "nodal.bridge.declarations",
            "nodal.bridge.origins",
            "source_end_line",
            "loc(",
            "unsupported exact MLIR type representation",
        ),
        problems,
        "NODAL-INC20-003",
        "Scala serializer",
    )
    require(
        process,
        (
            "final case class NativeCompilerRequest",
            "ProcessBuilder(command*)",
            "request.executable.isAbsolute",
            "process.getOutputStream.close()",
            "input.mlir",
            "NODAL_BRIDGE_SCHEMA",
            "NODAL_PROCESS_PROTOCOL",
            "NODAL-BRIDGE-PROCESS-005",
            "NODAL-BRIDGE-PROCESS-006",
            "NODAL-BRIDGE-PROCESS-007",
            "deleteRecursively",
        ),
        problems,
        "NODAL-INC20-004",
        "native process client",
    )
    if "Runtime.getRuntime.exec" in process or "bash -c" in process:
        problems.append(
            Problem(
                "NODAL-INC20-004",
                "bridge process implementation uses a shell command",
            )
        )
    require(
        tests,
        (
            "snapshot insertion order does not affect the bridge",
            "unsupported exact type fails before process launch",
            "argv-safe process success, cleanup, and recovery",
            "timeout is distinct",
            "locked nodalc parses and normalizes bridge MLIR",
        ),
        problems,
        "NODAL-INC20-005",
        "Scala bridge tests",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-bridge",
            "**Public API:** unchanged at 0.3",
            "nodal.scala-to-mlir",
            "Whole-design symbol resolution",
        ),
        problems,
        "NODAL-INC20-006",
        "design gate",
    )
    require(
        workflow,
        (
            "increment-20/scala-mlir-bridge",
            "check_increment20.py",
            "NODAL_NODALC",
            "./nodal core native",
            "./nodal core scala",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC20-007",
        "permanent workflow",
    )
    if "contents: write" in workflow or "materialize_increment20" in workflow:
        problems.append(
            Problem("NODAL-INC20-007", "permanent workflow must be read-only")
        )

    manifest_path = root / "tests/compiler/fixtures/increment20/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC20-008"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC20-008", f"invalid manifest: {exc}"))
        manifest = {}

    if manifest.get("increment") != 20:
        problems.append(Problem("NODAL-INC20-008", "manifest increment must be 20"))
    if manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC20-008", "public API must remain 0.3"))
    if manifest.get("schema") != "nodal.scala-to-mlir":
        problems.append(Problem("NODAL-INC20-008", "bridge schema mismatch"))
    if manifest.get("schema_version") != 1 or manifest.get("process_protocol") != 1:
        problems.append(Problem("NODAL-INC20-008", "bridge versions must be 1"))

    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    revision = roadmap_revision(roadmap)
    unchecked = "- [ ] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
    checked = "- [x] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
    increment21_unchecked = (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )

    if status == "implemented-awaiting-evidence":
        if not unchecked or revision < (1, 23):
            problems.append(
                Problem(
                    "NODAL-INC20-008",
                    "pre-evidence state must leave Increment 20 unchecked at revision 1.23 or later",
                )
            )
    elif status == "validated-scala-mlir-bridge":
        if not checked or revision < (1, 24):
            problems.append(
                Problem(
                    "NODAL-INC20-008",
                    "validated state must close Increment 20 at revision 1.24 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC20-008",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(
            Problem("NODAL-INC20-008", f"unexpected manifest status: {status!r}")
        )

    if not increment21_unchecked:
        problems.append(
            Problem("NODAL-INC20-008", "Increment 21 must remain unchecked")
        )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            f"Increment 20 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("Increment 20 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
