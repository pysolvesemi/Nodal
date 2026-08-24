#!/usr/bin/env python3
"""Validate the Increment 6 native compiler bootstrap without building it."""

from __future__ import annotations

import argparse
import json
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
    "CMakeLists.txt",
    "CMakePresets.json",
    "cmake/NodalToolchain.cmake",
    "cmake/NodalVersion.inc.in",
    "core/compiler/CMakeLists.txt",
    "core/compiler/include/nodal/Support/Version.h",
    "core/compiler/lib/CMakeLists.txt",
    "core/compiler/lib/Support/CMakeLists.txt",
    "core/compiler/lib/Support/Version.cpp",
    "core/compiler/tools/CMakeLists.txt",
    "core/compiler/tools/nodalc/CMakeLists.txt",
    "core/compiler/tools/nodalc/nodalc.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/VersionTest.cpp",
    "docs/development/native-compiler.md",
    ".github/workflows/increment-6-native-compiler-bootstrap.yml",
)

# Increment 18 may register the semantics-free dialect skeleton. Hardware and
# lowering semantics remain forbidden by this historical compatibility check.
FORBIDDEN_SEMANTICS = (
    "nodal.module",
    "nodal.contribute",
    "FIRRTL",
    "firtool --",
)


def _read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(
                Problem("NODAL-COMPILER-001", f"missing native bootstrap file: {relative}")
            )

    top = _read(root / "CMakeLists.txt", problems, "NODAL-COMPILER-002")
    toolchain = _read(
        root / "cmake/NodalToolchain.cmake", problems, "NODAL-COMPILER-003"
    )
    nodalc_cmake = _read(
        root / "core/compiler/tools/nodalc/CMakeLists.txt",
        problems,
        "NODAL-COMPILER-004",
    )
    nodalc = _read(
        root / "core/compiler/tools/nodalc/nodalc.cpp",
        problems,
        "NODAL-COMPILER-005",
    )
    tests = _read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-COMPILER-006",
    )
    workflow = _read(
        root / ".github/workflows/increment-6-native-compiler-bootstrap.yml",
        problems,
        "NODAL-COMPILER-007",
    )

    required_top = (
        "include(cmake/NodalToolchain.cmake)",
        "add_subdirectory(core/compiler)",
        "configure_file(",
        "NODAL_ENABLE_TESTS",
    )
    for fragment in required_top:
        if fragment not in top:
            problems.append(
                Problem("NODAL-COMPILER-008", f"top-level CMake lacks: {fragment}")
            )

    if "include(HandleLLVMOptions)\n\n# LLVM helper modules" not in top or (
        "nodal_normalize_llvm_definitions()\n\ninclude_directories" not in top
    ):
        problems.append(
            Problem(
                "NODAL-COMPILER-020",
                "LLVM definitions must be normalized after LLVM helper modules",
            )
        )

    required_toolchain = (
        "toolchains/lock.json",
        ".nodal-toolchain.json",
        "find_package(CIRCT REQUIRED CONFIG)",
        "NODAL_CIRCT_COMMIT",
        "NODAL_LLVM_COMMIT",
        "CIRCTConfig.cmake",
        "MLIRConfig.cmake",
        "LLVMConfig.cmake",
    )
    for fragment in required_toolchain:
        if fragment not in toolchain:
            problems.append(
                Problem("NODAL-COMPILER-009", f"toolchain CMake lacks: {fragment}")
            )

    required_abi_alignment = (
        "function(nodal_normalize_llvm_definitions)",
        "foreach(_raw_definition IN LISTS LLVM_DEFINITIONS)",
        "separate_arguments(",
        "_definition_tokens NATIVE_COMMAND",
        "list(REMOVE_DUPLICATES _nodal_llvm_definitions)",
        "_GLIBCXX_USE_CXX11_ABI=([01])",
        "set(GLIBCXX_USE_CXX11_ABI",
        "list(FILTER _nodal_llvm_definitions EXCLUDE REGEX",
        "set(LLVM_DEFINITIONS ${_nodal_llvm_definitions} PARENT_SCOPE)",
        "nodal_normalize_llvm_definitions()",
    )
    for fragment in required_abi_alignment:
        if fragment not in toolchain:
            problems.append(
                Problem(
                    "NODAL-COMPILER-020",
                    f"native toolchain ABI alignment lacks: {fragment}",
                )
            )

    required_links = (
        "add_llvm_executable(nodalc",
        "NodalSupport",
        "CIRCTHW",
        "CIRCTSupport",
        "MLIROptLib",
        "MLIRParser",
    )
    for fragment in required_links:
        if fragment not in nodalc_cmake:
            problems.append(
                Problem("NODAL-COMPILER-010", f"nodalc target lacks: {fragment}")
            )

    required_driver = (
        "circt/Dialect/HW/HWDialect.h",
        "circt/Support/Version.h",
        "mlir/Tools/mlir-opt/MlirOptMain.h",
        "circt::hw::HWDialect",
        "nodal::printVersion",
    )
    for fragment in required_driver:
        if fragment not in nodalc:
            problems.append(
                Problem("NODAL-COMPILER-011", f"nodalc driver lacks: {fragment}")
            )

    for fragment in ("nodalc --version", "check-nodal-native"):
        if fragment not in tests:
            problems.append(
                Problem("NODAL-COMPILER-012", f"native test registration lacks: {fragment}")
            )
    unit_cmake = _read(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-COMPILER-013",
    )
    if "nodal-native-unit-tests" not in unit_cmake:
        problems.append(
            Problem("NODAL-COMPILER-012", "native unit-test target is missing")
        )

    combined = "\n".join((top, toolchain, nodalc_cmake, nodalc, tests, unit_cmake))
    for token in FORBIDDEN_SEMANTICS:
        if token in combined:
            problems.append(
                Problem(
                    "NODAL-COMPILER-014",
                    f"Increment 6 must not define language semantics: {token}",
                )
            )

    try:
        presets = json.loads(
            _read(root / "CMakePresets.json", problems, "NODAL-COMPILER-015")
        )
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-COMPILER-016", f"invalid CMakePresets.json: {exc}"))
    else:
        configure_names = {
            item.get("name")
            for item in presets.get("configurePresets", [])
            if isinstance(item, dict)
        }
        build_names = {
            item.get("name")
            for item in presets.get("buildPresets", [])
            if isinstance(item, dict)
        }
        test_names = {
            item.get("name")
            for item in presets.get("testPresets", [])
            if isinstance(item, dict)
        }
        if configure_names != {"native-release"}:
            problems.append(
                Problem("NODAL-COMPILER-017", "unexpected native configure presets")
            )
        if "native-release" not in build_names or "native-release" not in test_names:
            problems.append(
                Problem("NODAL-COMPILER-018", "native build/test presets are incomplete")
            )

    required_workflow = (
        "bootstrap_native_toolchain.py install",
        "--mode prebuilt",
        "cmake --preset native-release",
        "cmake --build --preset native-release",
        "nodalc --version",
        "ctest --preset native-release",
        "check_native_compiler_bootstrap.py",
        "native-build.log",
        "_GLIBCXX_USE_CXX11_ABI.*redefined",
    )
    for fragment in required_workflow:
        if fragment not in workflow:
            problems.append(
                Problem("NODAL-COMPILER-019", f"Increment 6 workflow lacks: {fragment}")
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
        print(
            f"native compiler bootstrap check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("native compiler bootstrap check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
