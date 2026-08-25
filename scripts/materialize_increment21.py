#!/usr/bin/env python3
"""Materialize Increment 21 files into a checkout based on merged Increment 20."""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def copy(template_root: Path, name: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(template_root / name, destination)


def append_once(path: Path, fragment: str) -> None:
    text = path.read_text(encoding="utf-8")
    if fragment.strip() in text:
        return
    path.write_text(text.rstrip() + "\n\n" + fragment.strip() + "\n", encoding="utf-8")


def insert_after(path: Path, anchor: str, addition: str) -> None:
    text = path.read_text(encoding="utf-8")
    if addition.strip() in text:
        return
    if text.count(anchor) != 1:
        raise SystemExit(f"{path}: expected one anchor {anchor!r}")
    path.write_text(text.replace(anchor, anchor + addition, 1), encoding="utf-8")


def semantic_module(body: str = "", attributes: str = "") -> str:
    module_attributes = f" attributes {{{attributes}}}" if attributes else ""
    indented = "\n".join(f"    {line}" if line else "" for line in body.splitlines())
    if indented:
        indented = "\n" + indented
    return f'''module{module_attributes} {{
  "nodal.module"() <{{
    sym_name = "Top",
    metadata = {{}}
  }}> ({{
  ^bb0:{indented}
  }}) : () -> ()
}}
'''


def fixtures(root: Path) -> None:
    ir = root / "core/compiler/test/IR"
    ir.mkdir(parents=True, exist_ok=True)

    write(
        ir / "increment21-valid.mlir",
        """// RUN: nodalc --nodal-gate-check %s | FileCheck %s --check-prefix=CHECK
// RUN: nodalc --nodal-gate-normalize %s | FileCheck %s --check-prefix=NORMALIZED
// CHECK: nodal.module
// NORMALIZED: nodal.pipeline.normalized = true
// NORMALIZED: nodal.pipeline.stages
module attributes {nodal.target = "core"} {
  "nodal.module"() <{
    sym_name = "Top",
    metadata = {}
  }> ({
  ^bb0:
  }) : () -> ()
}
""",
    )
    write(
        ir / "increment21-invalid-construction.mlir",
        """// RUN: not nodalc --pass-pipeline='builtin.module(nodal-verify-stage{stage=construction})' %s 2>&1 | FileCheck %s
// CHECK: NODAL-VERIFY-CONSTRUCTION-001
module {
}
""",
    )
    write(
        ir / "increment21-invalid-hierarchy.mlir",
        semantic_module(
            '''"nodal.instance"() <{
  sym_name = "missing",
  module = @Missing,
  parameter_bindings = {},
  domain_bindings = {},
  metadata = {}
}> : () -> ()'''
        ),
    )
    declarations = (
        'nodal.bridge.declarations = [{attributes = {}, data_type = "UInt(8)", '
        'domain = "Top.core", kind = "output", name = "out", path = "Top.out"}], '
        'nodal.bridge.origins = []'
    )
    write(ir / "increment21-invalid-driver.mlir", semantic_module(attributes=declarations))
    latch_attributes = (
        'nodal.bridge.declarations = [{attributes = {assignment_coverage = "partial"}, '
        'data_type = "UInt(8)", domain = "Top.core", kind = "output", name = "out", '
        'path = "Top.out"}], '
        'nodal.bridge.origins = [{id = "assign", inlined = false, kind = "operation", '
        'operation = "assignment", parents = [], path = "Top.assign", sink = "Top.out", '
        'source = "fixture"}]'
    )
    write(ir / "increment21-invalid-latch.mlir", semantic_module(attributes=latch_attributes))
    cycle_attributes = (
        'nodal.bridge.declarations = ['
        '{attributes = {}, data_type = "UInt(8)", domain = "", kind = "wire", name = "a", path = "Top.a"}, '
        '{attributes = {}, data_type = "UInt(8)", domain = "", kind = "wire", name = "b", path = "Top.b"}], '
        'nodal.bridge.origins = ['
        '{id = "oa", inlined = false, kind = "operation", operation = "assignment", '
        'parents = ["ob"], path = "Top.a", sink = "Top.a", source = "fixture"}, '
        '{id = "ob", inlined = false, kind = "operation", operation = "assignment", '
        'parents = ["oa"], path = "Top.b", sink = "Top.b", source = "fixture"}]'
    )
    write(ir / "increment21-invalid-cycle.mlir", semantic_module(attributes=cycle_attributes))
    storage_attributes = (
        'nodal.bridge.declarations = [{attributes = {storage = "structural"}, '
        'data_type = "UInt(8)", domain = "Top.core", kind = "memory", name = "mem", '
        'path = "Top.mem"}]'
    )
    write(ir / "increment21-invalid-storage.mlir", semantic_module(attributes=storage_attributes))
    write(
        ir / "increment21-invalid-loop.mlir",
        semantic_module(
            '''"nodal.generate"() <{
  induction = "i",
  lower = 8 : i64,
  upper = 0 : i64,
  step = 1 : i64,
  metadata = {}
}> ({
^bb0:
}) : () -> ()'''
        ),
    )
    write(
        ir / "increment21-invalid-domain.mlir",
        semantic_module(
            '''"nodal.port"() <{
  sym_name = "out",
  type = !nodal.uint<8>,
  direction = "output",
  domain = @missing,
  metadata = {}
}> : () -> ()'''
        ),
    )
    write(
        ir / "increment21-invalid-protocol.mlir",
        semantic_module(
            '''"nodal.interface_instance"() <{
  sym_name = "link",
  definition = @Missing,
  role = "producer",
  metadata = {}
}> : () -> ()'''
        ),
    )
    memory_attributes = (
        'nodal.bridge.declarations = [{attributes = {depth = "0", ordering = "Ordered", '
        'readlatency = "1"}, data_type = "UInt(8)", domain = "Top.core", '
        'kind = "memory", name = "mem", path = "Top.mem"}]'
    )
    write(ir / "increment21-invalid-memory.mlir", semantic_module(attributes=memory_attributes))
    analog_attributes = (
        'nodal.bridge.declarations = ['
        '{attributes = {}, data_type = "Bits(1)", domain = "", kind = "digital-inout", name = "a", path = "Top.a"}, '
        '{attributes = {}, data_type = "Bits(1)", domain = "", kind = "digital-inout", name = "b", path = "Top.b"}], '
        'nodal.bridge.topology = [{kind = "terminal-connect", left = "Top.a", right = "Top.b"}]'
    )
    write(ir / "increment21-invalid-analog.mlir", semantic_module(attributes=analog_attributes))
    write(
        ir / "increment21-invalid-target.mlir",
        semantic_module(
            '''%terminal = "nodal.terminal"() <{
  name = "a",
  metadata = {}
}> : () -> !nodal.terminal<electrical>''',
            'nodal.target = "digital"',
        ),
    )


def patch_native_bootstrap(root: Path) -> None:
    path = root / "scripts/check_native_compiler_bootstrap.py"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    forbidden = (
        '"NodalTransforms",',
        '"registerNodalPasses",',
        '"nodal-verify-stage",',
        '"PassPipelineRegistration",',
    )
    lines = []
    in_forbidden = False
    for line in text.splitlines():
        if line.startswith("FORBIDDEN_SEMANTICS") and "(" in line:
            in_forbidden = True
        if not (in_forbidden and any(token in line for token in forbidden)):
            lines.append(line)
        if in_forbidden and line.strip() == ")":
            in_forbidden = False
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("templates", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    templates = args.templates.resolve()

    copy(templates, "Verification.h", root / "core/compiler/include/nodal/Transforms/Verification.h")
    copy(templates, "Verification.cpp", root / "core/compiler/lib/Transforms/Verification.cpp")
    copy(
        templates,
        "VerificationSessionTest.cpp",
        root / "core/compiler/test/Unit/VerificationSessionTest.cpp",
    )
    copy(templates, "run_increment21_tests.py", root / "core/compiler/test/run_increment21_tests.py")
    copy(
        templates,
        "NodalNativeVerificationPipeline-DG-v1.0.md",
        root / "docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md",
    )
    copy(
        templates,
        "increment21-native-verification-pipeline.md",
        root / "docs/implementation/increment21-native-verification-pipeline.md",
    )
    copy(templates, "manifest.json", root / "tests/compiler/fixtures/increment21/manifest.json")
    copy(templates, "check_increment21.py", root / "scripts/check_increment21.py")
    copy(templates, "test_increment21.py", root / "tests/compiler/test_increment21.py")
    copy(
        templates,
        "increment-21-native-verification-pipeline.yml",
        root / ".github/workflows/increment-21-native-verification-pipeline.yml",
    )

    write(
        root / "core/compiler/lib/Transforms/CMakeLists.txt",
        """add_mlir_library(NodalTransforms
  Verification.cpp

  LINK_LIBS PUBLIC
  NodalDialect
  MLIRIR
  MLIRPass
  MLIRSupport
)

mlir_check_all_link_libraries(NodalTransforms)
""",
    )
    append_once(root / "core/compiler/lib/CMakeLists.txt", "add_subdirectory(Transforms)")

    driver = root / "core/compiler/tools/nodalc/nodalc.cpp"
    driver_text = driver.read_text(encoding="utf-8")
    include = '#include "nodal/Transforms/Verification.h"\n'
    if include not in driver_text:
        include_anchor = '#include "nodal/Dialect/Nodal/NodalOps.h"\n'
        if include_anchor in driver_text:
            driver_text = driver_text.replace(include_anchor, include_anchor + include, 1)
        else:
            driver_text = include + driver_text
    if "nodal::registerNodalPasses();" not in driver_text:
        match = re.search(r"^(\s*)mlir::DialectRegistry registry;", driver_text, re.MULTILINE)
        if not match:
            raise SystemExit("nodalc.cpp lacks DialectRegistry construction")
        indentation = match.group(1)
        driver_text = driver_text[: match.start()] + indentation + "nodal::registerNodalPasses();\n" + driver_text[match.start() :]
    driver.write_text(driver_text, encoding="utf-8")

    driver_cmake = root / "core/compiler/tools/nodalc/CMakeLists.txt"
    cmake_text = driver_cmake.read_text(encoding="utf-8")
    if "NodalTransforms" not in cmake_text:
        if "NodalDialect" not in cmake_text:
            raise SystemExit("nodalc CMake lacks NodalDialect linkage")
        cmake_text = cmake_text.replace("NodalDialect", "NodalDialect\n  NodalTransforms", 1)
    driver_cmake.write_text(cmake_text, encoding="utf-8")

    append_once(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        """
add_executable(nodal-verification-session-tests
  VerificationSessionTest.cpp
)
llvm_update_compile_flags(nodal-verification-session-tests)
target_link_libraries(nodal-verification-session-tests PRIVATE
  NodalDialect
  NodalTransforms
  MLIRIR
  MLIRParser
  MLIRSupport
)
add_test(
  NAME nodal.native.increment21-transaction-session
  COMMAND nodal-verification-session-tests
)
""",
    )
    append_once(
        root / "core/compiler/test/CMakeLists.txt",
        """
find_package(Python3 REQUIRED COMPONENTS Interpreter)
add_test(
  NAME nodal.native.increment21-gate-pipeline
  COMMAND
    ${Python3_EXECUTABLE}
    ${CMAKE_CURRENT_SOURCE_DIR}/run_increment21_tests.py
    $<TARGET_FILE:nodalc>
    ${CMAKE_CURRENT_SOURCE_DIR}/IR
)
""",
    )

    fixtures(root)
    patch_native_bootstrap(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
