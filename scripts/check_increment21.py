#!/usr/bin/env python3
"""Validate Increment 21: native staged semantic verification and transactions."""

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
    "core/compiler/include/nodal/Transforms/Passes.h",
    "core/compiler/include/nodal/Transforms/CMakeLists.txt",
    "core/compiler/lib/Transforms/CMakeLists.txt",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/test/IR/semantic-pipeline-valid.mlir",
    "core/compiler/test/IR/semantic-pipeline-invalid.mlir",
    "core/compiler/test/Unit/SemanticPipelineTest.cpp",
    "docs/design-gates/NodalNativeSemanticPipeline-DG-v1.0.md",
    "docs/implementation/increment21-native-semantic-pipeline.md",
    "tests/compiler/fixtures/increment21/manifest.json",
    "tests/compiler/test_increment21.py",
    "scripts/check_increment21.py",
    ".github/workflows/increment-21-native-semantic-pipeline.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment21.py",
    "scripts/finalize_increment21.py",
    ".github/workflows/increment-21-materialize.yml",
    ".github/workflows/increment-21-finalize.yml",
    ".github/workflows/increment-21-supervisor.yml",
    ".github/workflows/increment-21-repair.yml",
)

STAGE_ARGUMENTS = (
    "nodal-verify-construction",
    "nodal-verify-drivers",
    "nodal-verify-latches",
    "nodal-verify-cycles",
    "nodal-verify-hierarchy",
    "nodal-verify-types",
    "nodal-verify-parameters",
    "nodal-verify-enum-fsm",
    "nodal-verify-domains",
    "nodal-verify-protocols",
    "nodal-verify-effects",
    "nodal-verify-analog",
    "nodal-verify-capabilities",
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
            problems.append(Problem("NODAL-INC21-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem("NODAL-INC21-002", f"temporary closure file remains: {relative}")
            )

    header = read(
        root / "core/compiler/include/nodal/Transforms/Passes.h",
        problems,
        "NODAL-INC21-003",
    )
    implementation = read(
        root / "core/compiler/lib/Transforms/Passes.cpp",
        problems,
        "NODAL-INC21-004",
    )
    include_cmake = read(
        root / "core/compiler/include/nodal/CMakeLists.txt",
        problems,
        "NODAL-INC21-005",
    )
    library_cmake = read(
        root / "core/compiler/lib/CMakeLists.txt",
        problems,
        "NODAL-INC21-005",
    )
    transform_cmake = read(
        root / "core/compiler/lib/Transforms/CMakeLists.txt",
        problems,
        "NODAL-INC21-005",
    )
    driver = read(
        root / "core/compiler/tools/nodalc/nodalc.cpp",
        problems,
        "NODAL-INC21-006",
    )
    driver_cmake = read(
        root / "core/compiler/tools/nodalc/CMakeLists.txt",
        problems,
        "NODAL-INC21-006",
    )
    unit_cmake = read(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-INC21-007",
    )
    native_tests = read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-INC21-007",
    )
    unit_test = read(
        root / "core/compiler/test/Unit/SemanticPipelineTest.cpp",
        problems,
        "NODAL-INC21-007",
    )
    valid_fixture = read(
        root / "core/compiler/test/IR/semantic-pipeline-valid.mlir",
        problems,
        "NODAL-INC21-008",
    )
    invalid_fixture = read(
        root / "core/compiler/test/IR/semantic-pipeline-invalid.mlir",
        problems,
        "NODAL-INC21-008",
    )
    gate = read(
        root / "docs/design-gates/NodalNativeSemanticPipeline-DG-v1.0.md",
        problems,
        "NODAL-INC21-009",
    )
    workflow = read(
        root / ".github/workflows/increment-21-native-semantic-pipeline.yml",
        problems,
        "NODAL-INC21-010",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC21-011",
    )

    require(
        header,
        (
            "enum class GateProfile",
            "registerNodalPasses",
            "runNodalPipelineTransaction",
            "class PipelineSession",
            "cloneAccepted",
        ),
        problems,
        "NODAL-INC21-003",
        "transform API",
    )
    require(
        implementation,
        STAGE_ARGUMENTS
        + (
            "nodal-gate-fast",
            "nodal-gate-default",
            "nodal-gate-release",
            "getAnalysis<InventoryAnalysis>",
            "markAllAnalysesPreserved",
            "manager.enableVerifier(true)",
            "candidate->clone()",
            "takeBody",
            "nodal.pipeline.normalized",
            "NODAL-VERIFY-CONSTRUCTION-001",
            "NODAL-VERIFY-DRIVER-001",
            "NODAL-VERIFY-LATCH-001",
            "NODAL-VERIFY-CYCLE-001",
            "NODAL-VERIFY-HIERARCHY-004",
            "NODAL-VERIFY-TYPE-003",
            "NODAL-VERIFY-PARAMETER-003",
            "NODAL-VERIFY-FSM-006",
            "NODAL-VERIFY-DOMAIN-003",
            "NODAL-VERIFY-PROTOCOL-003",
            "NODAL-VERIFY-EFFECT-003",
            "NODAL-VERIFY-ANALOG-004",
            "NODAL-VERIFY-CAPABILITY-003",
        ),
        problems,
        "NODAL-INC21-004",
        "native pass implementation",
    )
    if implementation.count("NODAL_DEFINE_VERIFICATION_PASS(") != 14:
        problems.append(
            Problem(
                "NODAL-INC21-004",
                "native pass implementation does not define exactly thirteen stage passes",
            )
        )

    require(
        include_cmake,
        ("add_subdirectory(Dialect)", "add_subdirectory(Transforms)"),
        problems,
        "NODAL-INC21-005",
        "compiler include CMake",
    )
    require(
        library_cmake,
        ("add_subdirectory(Dialect)", "add_subdirectory(Support)", "add_subdirectory(Transforms)"),
        problems,
        "NODAL-INC21-005",
        "compiler library CMake",
    )
    require(
        transform_cmake,
        ("add_mlir_library(NodalTransforms", "NodalDialect", "MLIRPass", "MLIRTransforms"),
        problems,
        "NODAL-INC21-005",
        "transform library CMake",
    )
    require(
        driver,
        ('#include "nodal/Transforms/Passes.h"', "nodal::registerNodalPasses()"),
        problems,
        "NODAL-INC21-006",
        "nodalc driver",
    )
    require(
        driver_cmake,
        ("NodalTransforms", "MLIRPass"),
        problems,
        "NODAL-INC21-006",
        "nodalc target",
    )
    require(
        unit_cmake,
        ("nodal-semantic-pipeline-unit-tests", "NodalTransforms", "MLIRPass"),
        problems,
        "NODAL-INC21-007",
        "semantic pipeline unit target",
    )
    require(
        native_tests,
        (
            "semantic-pipeline-fast",
            "semantic-pipeline-default",
            "semantic-pipeline-release",
            "semantic-pipeline-rejects-invalid-stages",
            "--split-input-file",
        ),
        problems,
        "NODAL-INC21-007",
        "semantic CTest registration",
    )
    require(
        unit_test,
        (
            "failed candidate replaced the last accepted state",
            "failed in-place transaction mutated its input module",
            "nodal.pipeline.normalized",
            "GateProfile::Release",
        ),
        problems,
        "NODAL-INC21-007",
        "transactional unit test",
    )
    require(
        valid_fixture,
        (
            "// DEFAULT:",
            "// RELEASE:",
            "// FAST:",
            'nodal.target.profile = "mixed_signal"',
            '"nodal.module"',
            '"nodal.interface"',
            '"nodal.resolved_net"',
            '"nodal.bridge"',
            '"nodal.fsm"',
        ),
        problems,
        "NODAL-INC21-008",
        "valid gate fixture",
    )
    if invalid_fixture.count("// -----") < 12:
        problems.append(
            Problem("NODAL-INC21-008", "invalid gate fixture does not cover all stages")
        )
    require(
        invalid_fixture,
        (
            "construction_closed = false",
            "driver_coverage = false",
            "latch_free = false",
            "combinational_acyclic = false",
            "module = @Missing",
            '!nodal.shaped<"MISSING"',
            "UNKNOWN = 8 : i64",
            "enum_fsm = false",
            "domain = @missing",
            'role = "missing"',
            "memory_effects = false",
            "analog_topology = false",
            'nodal.target.profile = "digital"',
        ),
        problems,
        "NODAL-INC21-008",
        "invalid gate fixture",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-ir",
            "**Public API:** unchanged at 0.3",
            "clone-before-commit",
            "nodal-gate-default",
            "thirteen",
        ),
        problems,
        "NODAL-INC21-009",
        "design gate",
    )
    require(
        workflow,
        (
            "increment-21/native-semantic-pipeline",
            "check_increment21.py",
            "nodal-gate-fast",
            "nodal-gate-default",
            "nodal-gate-release",
            "FileCheck",
            "NODAL-VERIFY-CAPABILITY-003",
            "./nodal core native",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC21-010",
        "permanent workflow",
    )
    if "contents: write" in workflow or "materialize_increment21" in workflow:
        problems.append(
            Problem("NODAL-INC21-010", "permanent Increment 21 workflow must be read-only")
        )

    manifest_path = root / "tests/compiler/fixtures/increment21/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC21-011"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC21-011", f"invalid manifest: {exc}"))
        manifest = {}

    if manifest.get("increment") != 21:
        problems.append(Problem("NODAL-INC21-011", "manifest increment must be 21"))
    if manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC21-011", "public API must remain 0.3"))
    if manifest.get("mandatory_default_stage_count") != 13:
        problems.append(Problem("NODAL-INC21-011", "manifest stage count must be thirteen"))
    if manifest.get("transaction") != "clone-before-commit":
        problems.append(Problem("NODAL-INC21-011", "manifest transaction contract mismatch"))

    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    revision = roadmap_revision(roadmap)
    unchecked = (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
    checked = (
        "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
    increment22_unchecked = "- [ ] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap

    if status == "implemented-awaiting-evidence":
        if not unchecked or revision < (1, 24):
            problems.append(
                Problem(
                    "NODAL-INC21-011",
                    "pre-evidence state must leave Increment 21 unchecked at revision 1.24 or later",
                )
            )
    elif status == "validated-native-semantic-pipeline":
        if not checked or revision < (1, 25):
            problems.append(
                Problem(
                    "NODAL-INC21-011",
                    "validated state must close Increment 21 at revision 1.25 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC21-011",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(
            Problem("NODAL-INC21-011", f"unexpected manifest status: {status!r}")
        )

    if not increment22_unchecked:
        problems.append(Problem("NODAL-INC21-011", "Increment 22 must remain unchecked"))

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
            f"Increment 21 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("Increment 21 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
