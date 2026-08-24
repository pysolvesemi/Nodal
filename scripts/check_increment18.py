#!/usr/bin/env python3
"""Validate Increment 18: the semantics-free Nodal MLIR dialect skeleton."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
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
    "core/compiler/include/CMakeLists.txt",
    "core/compiler/include/nodal/CMakeLists.txt",
    "core/compiler/include/nodal/Dialect/CMakeLists.txt",
    "core/compiler/include/nodal/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/include/nodal/Dialect/Nodal/NodalDialect.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalDialect.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.h",
    "core/compiler/lib/Dialect/CMakeLists.txt",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Dialect/Nodal/NodalDialect.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/IR/placeholder.mlir",
    "core/compiler/test/IR/placeholder-invalid.mlir",
    "core/compiler/test/Unit/DialectTest.cpp",
    "docs/design-gates/NodalMlirDialectSkeleton-DG-v1.0.md",
    "docs/implementation/increment18-mlir-dialect-skeleton.md",
    "tests/compiler/fixtures/increment18/manifest.json",
    "tests/compiler/test_increment18.py",
    ".github/workflows/increment-18-mlir-dialect-skeleton.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment18.py",
    "scripts/materialize_increment18_clean.py",
    "scripts/finalize_increment18.py",
    ".github/workflows/increment-18-bootstrap.yml",
    ".github/workflows/increment-18-clean-materialization.yml",
    ".github/workflows/increment-18-finalize.yml",
)

FORBIDDEN_DIALECT_SEMANTICS = (
    "nodal.module",
    "nodal.port",
    "nodal.param",
    "nodal.instance",
    "nodal.contribute",
    "hw.module",
)


def _read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def _require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(
                Problem("NODAL-MLIR-001", f"missing Increment 18 file: {relative}")
            )
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem("NODAL-MLIR-014", f"temporary Increment 18 file remains: {relative}")
            )

    compiler_cmake = _read(
        root / "core/compiler/CMakeLists.txt", problems, "NODAL-MLIR-002"
    )
    include_cmake = _read(
        root / "core/compiler/include/nodal/Dialect/Nodal/CMakeLists.txt",
        problems,
        "NODAL-MLIR-002",
    )
    library_cmake = _read(
        root / "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
        problems,
        "NODAL-MLIR-002",
    )
    dialect_td = _read(
        root / "core/compiler/include/nodal/Dialect/Nodal/NodalDialect.td",
        problems,
        "NODAL-MLIR-003",
    )
    ops_td = _read(
        root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
        problems,
        "NODAL-MLIR-004",
    )
    ops_cpp = _read(
        root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
        problems,
        "NODAL-MLIR-005",
    )
    driver = _read(
        root / "core/compiler/tools/nodalc/nodalc.cpp",
        problems,
        "NODAL-MLIR-006",
    )
    driver_cmake = _read(
        root / "core/compiler/tools/nodalc/CMakeLists.txt",
        problems,
        "NODAL-MLIR-006",
    )
    native_tests = _read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-MLIR-007",
    )
    unit_tests = _read(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-MLIR-007",
    )
    workflow = _read(
        root / ".github/workflows/increment-18-mlir-dialect-skeleton.yml",
        problems,
        "NODAL-MLIR-008",
    )
    design_gate = _read(
        root / "docs/design-gates/NodalMlirDialectSkeleton-DG-v1.0.md",
        problems,
        "NODAL-MLIR-008",
    )
    roadmap = _read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-MLIR-010",
    )
    native_bootstrap = _read(
        root / "scripts/check_native_compiler_bootstrap.py",
        problems,
        "NODAL-MLIR-011",
    )

    _require(
        compiler_cmake,
        ("add_subdirectory(include)", "add_subdirectory(lib)", "if(NODAL_ENABLE_TESTS)"),
        problems,
        "NODAL-MLIR-002",
        "compiler CMake",
    )
    if compiler_cmake.find("add_subdirectory(include)") > compiler_cmake.find(
        "add_subdirectory(lib)"
    ):
        problems.append(
            Problem(
                "NODAL-MLIR-002",
                "generated dialect headers must be configured before libraries",
            )
        )

    _require(
        include_cmake,
        (
            "add_mlir_dialect(NodalOps nodal)",
            "add_mlir_doc(NodalDialect",
            "add_mlir_doc(NodalOps",
        ),
        problems,
        "NODAL-MLIR-002",
        "TableGen CMake",
    )
    _require(
        library_cmake,
        (
            "add_mlir_dialect_library(NodalDialect",
            "MLIRNodalOpsIncGen",
            "MLIRIR",
            "MLIRSupport",
        ),
        problems,
        "NODAL-MLIR-002",
        "dialect library CMake",
    )

    _require(
        dialect_td,
        (
            'let name = "nodal";',
            'let cppNamespace = "::nodal";',
            "useDefaultAttributePrinterParser = 1",
            "semantics-free placeholder operation",
        ),
        problems,
        "NODAL-MLIR-003",
        "dialect definition",
    )
    _require(
        ops_td,
        (
            'Nodal_Op<"placeholder">',
            "StrAttr:$label",
            'let assemblyFormat = "$label attr-dict";',
            "let hasVerifier = 1;",
            "no hardware, timing, hierarchy, analog, scheduling, or backend",
        ),
        problems,
        "NODAL-MLIR-004",
        "placeholder operation",
    )
    _require(
        ops_cpp,
        (
            "LogicalResult nodal::PlaceholderOp::verify()",
            "label.getValue().empty()",
            "requires a non-empty 'label' attribute",
        ),
        problems,
        "NODAL-MLIR-005",
        "placeholder verifier",
    )

    _require(
        driver,
        (
            "circt/Dialect/HW/HWDialect.h",
            "llvm::setBugReportMsg",
            "nodal::printVersion",
            "registry.insert<circt::hw::HWDialect, nodal::NodalDialect>()",
        ),
        problems,
        "NODAL-MLIR-006",
        "nodalc driver",
    )
    _require(
        driver_cmake,
        (
            "add_llvm_executable(nodalc",
            "NodalDialect",
            "NodalSupport",
            "CIRCTHW",
            "MLIROptLib",
            "MLIRParser",
        ),
        problems,
        "NODAL-MLIR-006",
        "nodalc target",
    )

    _require(
        native_tests,
        (
            "nodal.native.nodal-placeholder-roundtrip",
            "nodal.native.nodal-placeholder-generic",
            "nodal.native.nodal-placeholder-rejects-empty-label",
            "nodal-dialect-unit-tests",
        ),
        problems,
        "NODAL-MLIR-007",
        "native test registration",
    )
    _require(
        unit_tests,
        (
            "add_executable(nodal-dialect-unit-tests",
            "NodalDialect",
            "MLIRParser",
            "nodal.native.dialect-unit",
        ),
        problems,
        "NODAL-MLIR-007",
        "dialect unit-test target",
    )

    _require(
        workflow,
        (
            "increment-18/mlir-dialect-skeleton",
            "check_increment18.py",
            "./nodal core native",
            "--mode prebuilt",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-MLIR-008",
        "Increment 18 workflow",
    )
    if "contents: write" in workflow or "materialize_increment18" in workflow:
        problems.append(
            Problem(
                "NODAL-MLIR-008",
                "permanent Increment 18 workflow must be read-only",
            )
        )

    _require(
        design_gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-ir",
            "**Public API:** unchanged at 0.3",
            "nodal.placeholder",
        ),
        problems,
        "NODAL-MLIR-008",
        "Increment 18 design gate",
    )

    dialect_sources = "\n".join((dialect_td, ops_td, ops_cpp))
    for token in FORBIDDEN_DIALECT_SEMANTICS:
        if token in dialect_sources:
            problems.append(
                Problem(
                    "NODAL-MLIR-009",
                    f"Increment 18 contains deferred hardware semantics: {token}",
                )
            )

    manifest_path = root / "tests/compiler/fixtures/increment18/manifest.json"
    try:
        manifest = json.loads(_read(manifest_path, problems, "NODAL-MLIR-010"))
    except json.JSONDecodeError as exc:
        problems.append(
            Problem("NODAL-MLIR-010", f"invalid Increment 18 manifest: {exc}")
        )
        manifest = {}

    if manifest.get("increment") != 18:
        problems.append(Problem("NODAL-MLIR-010", "manifest increment must be 18"))
    if manifest.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-MLIR-010", "manifest must preserve public API 0.3")
        )
    if manifest.get("dialect") != "nodal":
        problems.append(Problem("NODAL-MLIR-010", "manifest dialect must be nodal"))
    if manifest.get("placeholder") != "nodal.placeholder":
        problems.append(
            Problem(
                "NODAL-MLIR-010",
                "manifest placeholder must be nodal.placeholder",
            )
        )

    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    unchecked = "- [ ] **Increment 18 — Nodal MLIR dialect skeleton**" in roadmap
    checked = "- [x] **Increment 18 — Nodal MLIR dialect skeleton**" in roadmap
    if status == "implemented-awaiting-evidence":
        if not unchecked or "**Revision:** 1.21" not in roadmap:
            problems.append(
                Problem(
                    "NODAL-MLIR-010",
                    "pre-evidence state must leave Increment 18 unchecked at revision 1.21",
                )
            )
    elif status == "validated-dialect-skeleton":
        if not checked or "**Revision:** 1.22" not in roadmap:
            problems.append(
                Problem(
                    "NODAL-MLIR-010",
                    "validated state must close Increment 18 at revision 1.22",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-MLIR-010",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(
            Problem(
                "NODAL-MLIR-010",
                f"unexpected Increment 18 manifest status: {status!r}",
            )
        )

    if '"NodalDialect",' in native_bootstrap:
        problems.append(
            Problem(
                "NODAL-MLIR-011",
                "Increment 6 compatibility checker still forbids the dialect skeleton",
            )
        )
    _require(
        native_bootstrap,
        ("nodal.module", "nodal.contribute", "FIRRTL"),
        problems,
        "NODAL-MLIR-011",
        "Increment 6 compatibility checker",
    )

    return problems


def compile_and_test(root: Path, toolchain: Path) -> None:
    env = dict(os.environ)
    env["NODAL_NATIVE_TOOLCHAIN"] = str(toolchain.resolve())
    commands = (
        ("cmake", "--preset", "native-release"),
        ("cmake", "--build", "--preset", "native-release"),
        ("ctest", "--preset", "native-release"),
        (
            "cmake",
            "--build",
            str(root / "out/native/release"),
            "--target",
            "check-nodal-native",
        ),
    )
    for command in commands:
        print("+", " ".join(str(part) for part in command), flush=True)
        subprocess.run(command, cwd=root, env=env, check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    parser.add_argument("--compile", action="store_true")
    parser.add_argument("--toolchain", type=Path)
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            f"Increment 18 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    if args.compile:
        toolchain = args.toolchain
        if toolchain is None:
            configured = os.environ.get("NODAL_NATIVE_TOOLCHAIN")
            if configured:
                toolchain = Path(configured)
        if toolchain is None:
            print(
                "NODAL-MLIR-013: --compile requires --toolchain or "
                "NODAL_NATIVE_TOOLCHAIN",
                file=sys.stderr,
            )
            return 2
        compile_and_test(args.root.resolve(), toolchain)

    print("Increment 18 MLIR dialect skeleton check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
