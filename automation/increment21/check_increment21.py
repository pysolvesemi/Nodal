#!/usr/bin/env python3
"""Validate Increment 21 native staged verification and pass-pipeline contracts."""

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
    "core/compiler/include/nodal/Transforms/Verification.h",
    "core/compiler/lib/Transforms/CMakeLists.txt",
    "core/compiler/lib/Transforms/Verification.cpp",
    "core/compiler/test/IR/increment21-valid.mlir",
    "core/compiler/test/IR/increment21-invalid-construction.mlir",
    "core/compiler/test/IR/increment21-invalid-hierarchy.mlir",
    "core/compiler/test/IR/increment21-invalid-driver.mlir",
    "core/compiler/test/IR/increment21-invalid-latch.mlir",
    "core/compiler/test/IR/increment21-invalid-cycle.mlir",
    "core/compiler/test/IR/increment21-invalid-storage.mlir",
    "core/compiler/test/IR/increment21-invalid-loop.mlir",
    "core/compiler/test/IR/increment21-invalid-domain.mlir",
    "core/compiler/test/IR/increment21-invalid-protocol.mlir",
    "core/compiler/test/IR/increment21-invalid-memory.mlir",
    "core/compiler/test/IR/increment21-invalid-analog.mlir",
    "core/compiler/test/IR/increment21-invalid-target.mlir",
    "core/compiler/test/Unit/VerificationSessionTest.cpp",
    "core/compiler/test/run_increment21_tests.py",
    "docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md",
    "docs/implementation/increment21-native-verification-pipeline.md",
    "tests/compiler/fixtures/increment21/manifest.json",
    "tests/compiler/test_increment21.py",
    ".github/workflows/increment-21-native-verification-pipeline.yml",
)

TEMPORARY_FILES = (
    ".github/workflows/increment-21-finalizer.yml",
    ".github/workflows/increment-21-supervisor.yml",
    ".github/workflows/increment-21-repair.yml",
    "scripts/materialize_increment21.py",
    "scripts/finalize_increment21.py",
)

STAGES = (
    "construction",
    "hierarchy",
    "connectivity",
    "type-shape",
    "parameter-loop",
    "enum-fsm",
    "domain",
    "protocol-pipeline",
    "memory-effect",
    "analog-mixed",
    "target-capability",
)

DIAGNOSTIC_PREFIXES = (
    "NODAL-VERIFY-CONSTRUCTION-",
    "NODAL-VERIFY-HIERARCHY-",
    "NODAL-VERIFY-DRIVER-",
    "NODAL-VERIFY-LATCH-",
    "NODAL-VERIFY-CYCLE-",
    "NODAL-VERIFY-TYPE-",
    "NODAL-VERIFY-SHAPE-",
    "NODAL-VERIFY-STORAGE-",
    "NODAL-VERIFY-LAYOUT-",
    "NODAL-VERIFY-LOOP-",
    "NODAL-VERIFY-PARAMETER-",
    "NODAL-VERIFY-FSM-",
    "NODAL-VERIFY-DOMAIN-",
    "NODAL-VERIFY-PROTOCOL-",
    "NODAL-VERIFY-PIPELINE-",
    "NODAL-VERIFY-MEMORY-",
    "NODAL-VERIFY-EFFECT-",
    "NODAL-VERIFY-ANALOG-",
    "NODAL-VERIFY-TARGET-",
    "NODAL-VERIFY-TRANSACTION-",
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
            problems.append(Problem("NODAL-INC21-001", f"missing Increment 21 file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC21-002", f"temporary Increment 21 file remains: {relative}"))

    header = read(
        root / "core/compiler/include/nodal/Transforms/Verification.h",
        problems,
        "NODAL-INC21-003",
    )
    implementation = read(
        root / "core/compiler/lib/Transforms/Verification.cpp",
        problems,
        "NODAL-INC21-003",
    )
    transform_cmake = read(
        root / "core/compiler/lib/Transforms/CMakeLists.txt",
        problems,
        "NODAL-INC21-004",
    )
    library_cmake = read(
        root / "core/compiler/lib/CMakeLists.txt", problems, "NODAL-INC21-004"
    )
    driver = read(
        root / "core/compiler/tools/nodalc/nodalc.cpp", problems, "NODAL-INC21-005"
    )
    driver_cmake = read(
        root / "core/compiler/tools/nodalc/CMakeLists.txt",
        problems,
        "NODAL-INC21-005",
    )
    test_cmake = read(
        root / "core/compiler/test/CMakeLists.txt", problems, "NODAL-INC21-006"
    )
    unit_cmake = read(
        root / "core/compiler/test/Unit/CMakeLists.txt", problems, "NODAL-INC21-006"
    )
    runner = read(
        root / "core/compiler/test/run_increment21_tests.py",
        problems,
        "NODAL-INC21-006",
    )
    unit = read(
        root / "core/compiler/test/Unit/VerificationSessionTest.cpp",
        problems,
        "NODAL-INC21-007",
    )
    design_gate = read(
        root / "docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md",
        problems,
        "NODAL-INC21-008",
    )
    workflow = read(
        root / ".github/workflows/increment-21-native-verification-pipeline.yml",
        problems,
        "NODAL-INC21-009",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC21-010",
    )

    require(
        header,
        (
            "enum class VerificationStage",
            "verifyNodalStage",
            "verifyNodalPipeline",
            "class VerificationSession",
            "createNodalVerifyStagePass",
            "createNodalTransactionalGatePass",
            "registerNodalPasses",
        ),
        problems,
        "NODAL-INC21-003",
        "verification API",
    )
    for stage in STAGES:
        if f'return "{stage}";' not in implementation:
            problems.append(Problem("NODAL-INC21-003", f"implementation lacks stage: {stage}"))
    require(
        implementation,
        (
            "PassRegistration<VerifyStagePass>",
            "PassRegistration<TransactionalGatePass>",
            '"nodal-gate-check"',
            '"nodal-gate-normalize"',
            '"nodal.pipeline.normalized"',
            '"nodal.pipeline.stages"',
            "module->setAttrs(originalAttributes)",
            "verifyNodalPipeline(module, target)",
            "acceptedIR = std::move(text)",
        ),
        problems,
        "NODAL-INC21-003",
        "verification implementation",
    )
    for prefix in DIAGNOSTIC_PREFIXES:
        if prefix not in implementation:
            problems.append(Problem("NODAL-INC21-003", f"implementation lacks diagnostic family: {prefix}"))

    require(
        transform_cmake,
        ("add_mlir_library(NodalTransforms", "Verification.cpp", "NodalDialect", "MLIRPass"),
        problems,
        "NODAL-INC21-004",
        "transform CMake",
    )
    require(
        library_cmake,
        ("add_subdirectory(Transforms)",),
        problems,
        "NODAL-INC21-004",
        "compiler library CMake",
    )
    require(
        driver,
        ('#include "nodal/Transforms/Verification.h"', "nodal::registerNodalPasses()"),
        problems,
        "NODAL-INC21-005",
        "nodalc registration",
    )
    require(
        driver_cmake,
        ("NodalTransforms",),
        problems,
        "NODAL-INC21-005",
        "nodalc linkage",
    )
    require(
        test_cmake,
        ("run_increment21_tests.py", "nodal.native.increment21-gate-pipeline"),
        problems,
        "NODAL-INC21-006",
        "native test registration",
    )
    require(
        unit_cmake,
        (
            "nodal-verification-session-tests",
            "VerificationSessionTest.cpp",
            "NodalTransforms",
            "nodal.native.increment21-transaction-session",
        ),
        problems,
        "NODAL-INC21-006",
        "unit test registration",
    )
    require(
        runner,
        (
            "--nodal-gate-check",
            "--nodal-gate-normalize",
            "--pass-pipeline=builtin.module",
            "NODAL-VERIFY-DRIVER-001",
            "NODAL-VERIFY-LATCH-001",
            "NODAL-VERIFY-CYCLE-001",
            "recovery.stdout != normalized.stdout",
        ),
        problems,
        "NODAL-INC21-006",
        "native fixture runner",
    )
    require(
        unit,
        (
            "VerificationSession session",
            "failed(session.accept(*invalid))",
            "failed candidate replaced the last accepted state",
            "session did not recover after rejection",
        ),
        problems,
        "NODAL-INC21-007",
        "transactional unit test",
    )
    require(
        design_gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-verification",
            "**Public API:** unchanged at 0.3",
            "Parsing success is not design acceptance",
            "last accepted",
            "lit/FileCheck",
        ),
        problems,
        "NODAL-INC21-008",
        "Increment 21 design gate",
    )
    require(
        workflow,
        (
            "Increment 21 Native Verification Pipeline",
            "check_increment21.py",
            "run_increment21_tests.py",
            "./nodal core native",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC21-009",
        "Increment 21 workflow",
    )
    if "contents: write" in workflow or "pull-requests: write" in workflow:
        problems.append(Problem("NODAL-INC21-009", "permanent Increment 21 workflow must be read-only"))

    manifest_path = root / "tests/compiler/fixtures/increment21/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC21-010"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC21-010", f"invalid Increment 21 manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 21:
        problems.append(Problem("NODAL-INC21-010", "manifest increment must be 21"))
    if manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC21-010", "manifest must preserve public API 0.3"))
    if tuple(manifest.get("stages", ())) != STAGES:
        problems.append(Problem("NODAL-INC21-010", "manifest stage ordering differs from the binding gate"))

    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    revision = roadmap_revision(roadmap)
    unchecked = "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**" in roadmap
    checked = "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**" in roadmap
    if status == "implemented-awaiting-evidence":
        if not unchecked or revision != (1, 24):
            problems.append(Problem("NODAL-INC21-010", "pre-evidence state must leave Increment 21 unchecked at revision 1.24"))
    elif status == "validated-native-verification-pipeline":
        if not checked or revision < (1, 25):
            problems.append(Problem("NODAL-INC21-010", "validated state must close Increment 21 at revision 1.25 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC21-010", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC21-010", f"unexpected Increment 21 manifest status: {status!r}"))

    if "- [x] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**" in roadmap:
        problems.append(Problem("NODAL-INC21-011", "Increment 21 must not close Increment 22"))

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 21 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 21 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
