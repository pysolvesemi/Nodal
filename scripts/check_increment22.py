#!/usr/bin/env python3
"""Validate Increment 22: cross-layer diagnostic mapping."""

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
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/include/nodal/Diagnostics/CMakeLists.txt",
    "core/compiler/include/nodal/Diagnostics/DiagnosticMapping.h",
    "core/compiler/lib/Diagnostics/CMakeLists.txt",
    "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp",
    "core/compiler/test/IR/diagnostic-mapping-inventory-invalid.mlir",
    "core/compiler/test/IR/diagnostic-mapping-operation-invalid.mlir",
    "core/scala/bridge/src/nodal/bridge/NativeDiagnosticMapper.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/CrossLayerDiagnosticTests.scala",
    "docs/design-gates/NodalCrossLayerDiagnostics-DG-v1.0.md",
    "docs/implementation/increment22-cross-layer-diagnostic-mapping.md",
    "tests/compiler/fixtures/increment22/manifest.json",
    "tests/compiler/test_increment22.py",
    "scripts/check_increment22.py",
    ".github/workflows/increment-22-cross-layer-diagnostics.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment22.py",
    "scripts/finalize_increment22.py",
    ".github/workflows/increment-22-materialize.yml",
    ".github/workflows/increment-22-finalize.yml",
    ".github/workflows/increment-22-supervisor.yml",
)

REQUIRED_CODES = (
    "NODAL-INTERFACE-STORAGE-001",
    "NODAL-INTERFACE-ROLE-001",
    "NODAL-INTERFACE-ROLE-002",
    "NODAL-INTERFACE-MEMBER-001",
    "NODAL-INTERFACE-MONITOR-001",
    "NODAL-INTERFACE-INVERSION-001",
    "NODAL-INTERFACE-LAYOUT-001",
    "NODAL-DRIVER-MULTIPLE-001",
    "NODAL-INOUT-OPEN-DRAIN-001",
    "NODAL-INOUT-OPEN-SOURCE-001",
    "NODAL-INOUT-RESOLUTION-001",
    "NODAL-INOUT-HIERARCHY-001",
    "NODAL-AMS-DISCIPLINE-001",
    "NODAL-AMS-ACCESS-001",
    "NODAL-AMS-BRIDGE-001",
    "NODAL-DIAGNOSTIC-PARSER-001",
    "NODAL-DIAGNOSTIC-VERIFIER-001",
    "NODAL-DIAGNOSTIC-PASS-001",
    "NODAL-DIAGNOSTIC-BACKEND-001",
    "NODAL-DIAGNOSTIC-EXTERNAL-001",
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
            problems.append(Problem("NODAL-INC22-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem("NODAL-INC22-002", f"temporary closure file remains: {relative}")
            )

    header = read(
        root / "core/compiler/include/nodal/Diagnostics/DiagnosticMapping.h",
        problems,
        "NODAL-INC22-003",
    )
    native = read(
        root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp",
        problems,
        "NODAL-INC22-004",
    )
    passes = read(
        root / "core/compiler/lib/Transforms/Passes.cpp",
        problems,
        "NODAL-INC22-005",
    )
    client = read(
        root / "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
        problems,
        "NODAL-INC22-006",
    )
    mapper = read(
        root / "core/scala/bridge/src/nodal/bridge/NativeDiagnosticMapper.scala",
        problems,
        "NODAL-INC22-006",
    )
    bridge = read(
        root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
        problems,
        "NODAL-INC22-006",
    )
    workflow = read(
        root / ".github/workflows/increment-22-cross-layer-diagnostics.yml",
        problems,
        "NODAL-INC22-007",
    )
    gate = read(
        root / "docs/design-gates/NodalCrossLayerDiagnostics-DG-v1.0.md",
        problems,
        "NODAL-INC22-008",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC22-009",
    )

    require(
        header,
        (
            "struct DiagnosticContext",
            "collectDiagnosticContext",
            "emitMappedFailure",
            "emitMappedFailureForPath",
            "createCrossLayerDiagnosticPass",
        ),
        problems,
        "NODAL-INC22-003",
        "diagnostic mapping header",
    )
    require(
        native,
        (
            "semantic_path",
            "hierarchy_path",
            "index_path",
            "source_end_line",
            "source_end_column",
            "source-range=",
            "nodal-verify-cross-layer-diagnostics",
            "markAllAnalysesPreserved",
            "current && !file",
        ) + REQUIRED_CODES[:15],
        problems,
        "NODAL-INC22-004",
        "native diagnostic mapper",
    )
    if "context.hierarchyPath = path.str();" in native:
        problems.append(
            Problem(
                "NODAL-INC22-004",
                "inventory-only diagnostics invent hierarchy context from semantic paths",
            )
        )
    require(
        passes,
        (
            '#include "nodal/Diagnostics/DiagnosticMapping.h"',
            "return emitMappedFailure(operation, code, message);",
            "manager.addPass(createCrossLayerDiagnosticPass());",
        ),
        problems,
        "NODAL-INC22-005",
        "Increment 21 integration",
    )
    require(
        mapper,
        REQUIRED_CODES[15:]
        + (
            "semantic-path",
            "hierarchy-path",
            "index-path",
            "source-range",
            "StagedInputPath",
            "<bridge-input>",
        ),
        problems,
        "NODAL-INC22-006",
        "Scala native diagnostic mapper",
    )
    require(
        bridge,
        (
            "hierarchyPath: Option[String]",
            "indexPath: Option[String]",
            "sourceRange: Option[String]",
        ),
        problems,
        "NODAL-INC22-006",
        "BridgeDiagnostic",
    )
    require(
        client,
        (
            "NativeDiagnosticMapper.classify",
            'mapped.code == "NODAL-DIAGNOSTIC-EXTERNAL-001"',
            '"NODAL-BRIDGE-PROCESS-007"',
        ),
        problems,
        "NODAL-INC22-006",
        "native compiler client",
    )
    require(
        workflow,
        (
            "increment-22/cross-layer-diagnostics",
            "check_increment22.py",
            "nodal-verify-cross-layer-diagnostics",
            "diagnostic-mapping-inventory-invalid.mlir",
            "diagnostic-mapping-operation-invalid.mlir",
            "./nodal core native",
            "./nodal core scala",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC22-007",
        "permanent workflow",
    )
    if "contents: write" in workflow or "materialize_increment22" in workflow:
        problems.append(Problem("NODAL-INC22-007", "permanent workflow must be read-only"))
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-diagnostics",
            "**Public API:** unchanged at 0.3",
            "CIRCT conversion and legalization",
            "Raw MLIR, CIRCT, C++, JVM",
        ),
        problems,
        "NODAL-INC22-008",
        "design gate",
    )

    catalog_path = root / "core/compiler/diagnostics-v0.1.json"
    try:
        catalog = json.loads(read(catalog_path, problems, "NODAL-INC22-010"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC22-010", f"invalid diagnostic catalog: {exc}"))
        catalog = {}
    catalog_text = json.dumps(catalog, sort_keys=True)
    for code in REQUIRED_CODES:
        if code not in catalog_text:
            problems.append(Problem("NODAL-INC22-010", f"catalog lacks code: {code}"))

    manifest_path = root / "tests/compiler/fixtures/increment22/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC22-009"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC22-009", f"invalid manifest: {exc}"))
        manifest = {}

    if manifest.get("increment") != 22:
        problems.append(Problem("NODAL-INC22-009", "manifest increment must be 22"))
    if manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC22-009", "public API must remain 0.3"))
    if manifest.get("native_pass") != "nodal-verify-cross-layer-diagnostics":
        problems.append(Problem("NODAL-INC22-009", "native pass identity mismatch"))

    revision = roadmap_revision(roadmap)
    increment21_checked = (
        "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
    increment22_unchecked = "- [ ] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
    increment22_checked = "- [x] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
    increment23_unchecked = "- [ ] **Increment 23 — Backend framework and capability profiles**" in roadmap
    increment23_checked = "- [x] **Increment 23 — Backend framework and capability profiles**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})

    if not increment21_checked:
        problems.append(Problem("NODAL-INC22-009", "Increment 21 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment22_unchecked or revision < (1, 25):
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    "pre-evidence state must leave Increment 22 unchecked at revision 1.25 or later",
                )
            )
    elif status == "validated-cross-layer-diagnostics":
        if not increment22_checked or revision < (1, 26):
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    "validated state must close Increment 22 at revision 1.26 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC22-009",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(Problem("NODAL-INC22-009", f"unexpected manifest status: {status!r}"))

    increment23_status = None
    increment23_manifest = root / "tests/compiler/fixtures/increment23/manifest.json"
    if increment23_manifest.is_file():
        try:
            increment23_value = json.loads(
                increment23_manifest.read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError) as exc:
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    f"cannot read Increment 23 successor evidence: {exc}",
                )
            )
        else:
            increment23_status = increment23_value.get("status")

    if increment23_status == "validated-backend-framework":
        if not increment23_checked:
            problems.append(
                Problem(
                    "NODAL-INC22-009",
                    "validated Increment 23 evidence requires its roadmap item to be checked",
                )
            )
    elif not increment23_unchecked:
        problems.append(
            Problem(
                "NODAL-INC22-009",
                "Increment 23 must remain unchecked until validated evidence exists",
            )
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
            f"Increment 22 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("Increment 22 cross-layer diagnostic mapping check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
