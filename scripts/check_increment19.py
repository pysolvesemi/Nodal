#!/usr/bin/env python3
"""Validate Increment 19: the canonical target-neutral Nodal MLIR model."""

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
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.h",
    "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
    "core/compiler/test/IR/core-model.mlir",
    "core/compiler/test/IR/core-model-invalid-width.mlir",
    "core/compiler/test/IR/core-model-invalid-net.mlir",
    "core/compiler/test/IR/core-model-invalid-enum.mlir",
    "core/compiler/test/IR/core-model-invalid-fsm.mlir",
    "core/compiler/test/Unit/CoreModelTest.cpp",
    "docs/design-gates/NodalCoreMlirModel-DG-v1.0.md",
    "docs/implementation/increment19-core-mlir-model.md",
    "tests/compiler/fixtures/increment19/manifest.json",
    "tests/compiler/test_increment19.py",
    ".github/workflows/increment-19-core-mlir-model.yml",
)

TEMPORARY_FILES = (
    ".github/workflows/increment-19-final-supervisor.yml",
    ".github/workflows/increment-19-materialize.yml",
    ".github/workflows/increment-19-autoclose.yml",
    "scripts/materialize_increment19.py",
    "scripts/finalize_increment19.py",
)

REQUIRED_TYPES = (
    "Nodal_BitsType",
    "Nodal_UIntType",
    "Nodal_SIntType",
    "Nodal_ShapedType",
    "Nodal_InterfaceType",
    "Nodal_ValidType",
    "Nodal_StreamType",
    "Nodal_ResolvedType",
    "Nodal_DriverType",
    "Nodal_TerminalType",
    "Nodal_BranchType",
    "Nodal_EnumType",
    "Nodal_DomainType",
)

REQUIRED_OPS = (
    "Nodal_ModuleOp",
    "Nodal_PortOp",
    "Nodal_ParameterOp",
    "Nodal_InstanceOp",
    "Nodal_InterfaceOp",
    "Nodal_InterfaceRoleOp",
    "Nodal_InterfaceMemberOp",
    "Nodal_InterfaceInstanceOp",
    "Nodal_MemberAccessOp",
    "Nodal_InterfaceAbiOp",
    "Nodal_DomainOp",
    "Nodal_DomainRequirementOp",
    "Nodal_DomainBindOp",
    "Nodal_ClockRelationOp",
    "Nodal_ResetRelationOp",
    "Nodal_ConstantOp",
    "Nodal_ShapeIndexOp",
    "Nodal_ShapeFlattenOp",
    "Nodal_ShapeViewOp",
    "Nodal_GenerateOp",
    "Nodal_HardwareLoopOp",
    "Nodal_ResolvedNetOp",
    "Nodal_NetReadOp",
    "Nodal_NetDriverOp",
    "Nodal_NetDriveOp",
    "Nodal_TerminalOp",
    "Nodal_NodeOp",
    "Nodal_BranchOp",
    "Nodal_AccessOp",
    "Nodal_BridgeOp",
    "Nodal_CrossingOp",
    "Nodal_StateOwnerOp",
    "Nodal_TimingProvenanceOp",
    "Nodal_EnumOp",
    "Nodal_EnumCaseOp",
    "Nodal_FsmOp",
    "Nodal_FsmStateOp",
    "Nodal_FsmTransitionOp",
    "Nodal_FsmActionOp",
    "Nodal_FsmCompletionOp",
)


def read(root: Path, relative: str, problems: list[Problem], code: str) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {relative}: {exc}"))
        return ""


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    label: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{label} lacks: {fragment}"))


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
            problems.append(Problem("NODAL-INC19-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC19-002", f"temporary file remains: {relative}"))

    types = read(
        root,
        "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
        problems,
        "NODAL-INC19-003",
    )
    dialect = read(
        root,
        "core/compiler/include/nodal/Dialect/Nodal/NodalDialect.td",
        problems,
        "NODAL-INC19-003",
    )
    ops = read(
        root,
        "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
        problems,
        "NODAL-INC19-004",
    )
    type_impl = read(
        root,
        "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
        problems,
        "NODAL-INC19-005",
    )
    op_impl = read(
        root,
        "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
        problems,
        "NODAL-INC19-006",
    )
    include_cmake = read(
        root,
        "core/compiler/include/nodal/Dialect/Nodal/CMakeLists.txt",
        problems,
        "NODAL-INC19-007",
    )
    lib_cmake = read(
        root,
        "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
        problems,
        "NODAL-INC19-007",
    )
    native_cmake = read(
        root,
        "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-INC19-008",
    )
    unit_cmake = read(
        root,
        "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-INC19-008",
    )
    fixture = read(
        root,
        "core/compiler/test/IR/core-model.mlir",
        problems,
        "NODAL-INC19-009",
    )
    workflow = read(
        root,
        ".github/workflows/increment-19-core-mlir-model.yml",
        problems,
        "NODAL-INC19-010",
    )
    gate = read(
        root,
        "docs/design-gates/NodalCoreMlirModel-DG-v1.0.md",
        problems,
        "NODAL-INC19-011",
    )
    roadmap = read(
        root,
        "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC19-012",
    )

    require(
        dialect,
        ("useDefaultTypePrinterParser = 1", "void registerTypes();"),
        problems,
        "NODAL-INC19-003",
        "dialect definition",
    )
    require(types, REQUIRED_TYPES, problems, "NODAL-INC19-003", "type inventory")
    require(ops, REQUIRED_OPS, problems, "NODAL-INC19-004", "operation inventory")
    require(
        type_impl,
        (
            "NodalDialect::registerTypes",
            "BitsType::verify",
            "ShapedType::verify",
            "ResolvedType::verify",
            "EnumType::verify",
        ),
        problems,
        "NODAL-INC19-005",
        "type verification",
    )
    require(
        op_impl,
        (
            "ModuleOp::verify",
            "ParameterOp::verify",
            "InterfaceMemberOp::verify",
            "HardwareLoopOp::verify",
            "NetDriveOp::verify",
            "BranchOp::verify",
            "CrossingOp::verify",
            "EnumOp::verify",
            "FsmOp::verify",
        ),
        problems,
        "NODAL-INC19-006",
        "operation verification",
    )
    require(
        include_cmake,
        ("add_mlir_dialect(NodalOps nodal)", "-gen-typedef-doc"),
        problems,
        "NODAL-INC19-007",
        "TableGen CMake",
    )
    require(
        lib_cmake,
        ("NodalTypes.cpp", "MLIRNodalOpsIncGen", "MLIRBytecodeOpInterface"),
        problems,
        "NODAL-INC19-007",
        "dialect library CMake",
    )
    require(
        native_cmake,
        (
            "core-model-roundtrip",
            "core-model-generic",
            "core-model-rejects-${_fixture}",
            "nodal-core-model-unit-tests",
        ),
        problems,
        "NODAL-INC19-008",
        "native tests",
    )
    require(
        unit_cmake,
        ("add_executable(nodal-core-model-unit-tests", "CoreModelTest.cpp"),
        problems,
        "NODAL-INC19-008",
        "typed unit tests",
    )
    require(
        fixture,
        (
            '"nodal.interface"',
            '"nodal.module"',
            '"nodal.parameter"',
            '"nodal.hardware_loop"',
            '"nodal.resolved_net"',
            '"nodal.branch"',
            '"nodal.crossing"',
            '"nodal.enum"',
            '"nodal.fsm"',
            "!nodal.shaped",
        ),
        problems,
        "NODAL-INC19-009",
        "positive fixture",
    )
    require(
        workflow,
        (
            "increment-19/core-mlir-model",
            "permissions:\n  contents: read",
            "check_increment18.py",
            "check_increment19.py",
            "./nodal core native",
            "core-model-invalid-width.mlir",
            "git diff --check",
        ),
        problems,
        "NODAL-INC19-010",
        "permanent workflow",
    )
    if "contents: write" in workflow:
        problems.append(Problem("NODAL-INC19-010", "permanent workflow is writable"))
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-ir",
            "**Public API:** unchanged at 0.3",
            "Increment 21",
        ),
        problems,
        "NODAL-INC19-011",
        "design gate",
    )

    manifest_path = root / "tests/compiler/fixtures/increment19/manifest.json"
    try:
        manifest = json.loads(read(root, str(manifest_path.relative_to(root)), problems, "NODAL-INC19-012"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC19-012", f"invalid manifest: {exc}"))
        manifest = {}

    if manifest.get("increment") != 19 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC19-012", "manifest identity is invalid"))
    if manifest.get("type_count") != len(REQUIRED_TYPES):
        problems.append(Problem("NODAL-INC19-012", "manifest type count is invalid"))

    revision = roadmap_revision(roadmap)
    unchecked = "- [ ] **Increment 19 — Core MLIR module, port, parameter, and domain model**"
    checked = unchecked.replace("[ ]", "[x]", 1)
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if status == "implemented-awaiting-evidence":
        if revision != (1, 22) or unchecked not in roadmap:
            problems.append(Problem("NODAL-INC19-013", "pre-evidence roadmap state is invalid"))
        if evidence != {"pull_request": None, "dedicated_run": None, "core_ci_run": None}:
            problems.append(Problem("NODAL-INC19-013", "pre-evidence manifest is malformed"))
    elif status == "validated-core-model":
        if revision < (1, 23) or checked not in roadmap:
            problems.append(Problem("NODAL-INC19-014", "validated roadmap state is invalid"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC19-014", f"missing integer evidence: {field}"))
        increment20_unchecked = (
            "- [ ] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
        )
        increment20_checked = (
            "- [x] **Increment 20 — Scala-to-MLIR bridge**" in roadmap
        )
        if not increment20_unchecked and not increment20_checked:
            problems.append(
                Problem("NODAL-INC19-014", "Increment 20 roadmap item is missing")
            )
    else:
        problems.append(Problem("NODAL-INC19-012", f"unexpected manifest status: {status!r}"))

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 19 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 19 core MLIR model check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
