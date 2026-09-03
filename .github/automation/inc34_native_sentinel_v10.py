#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    return replace_once(text, marker, addition + marker, label)


def patch_verifier(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)
    text = replace_once(
        text,
        '''        !reads.empty() || staticPresent.getValue())''',
        '''        !reads.empty() || staticPresent.getValue() || staticValue.getValue())''',
        "else-arm static sentinel",
    )
    text = replace_once(
        text,
        '''  if (stage == "runtime" && staticPresent.getValue())''',
        '''  if (stage == "runtime" && (staticPresent.getValue() || staticValue.getValue()))''',
        "runtime-condition static sentinel",
    )
    text = replace_once(
        text,
        '''    if (maximumValue == 0 || staticPresent.getValue())''',
        '''    if (maximumValue == 0 || staticPresent.getValue() || staticCount.getInt() != 0)''',
        "runtime-loop static sentinel",
    )
    write(path, text)


def make_fixtures(root: Path) -> None:
    directory = root / "core/compiler/test/IR"
    valid = read(directory / "analog-control-flow.mlir")

    runtime_old = 'stage = "runtime", static_value = false, static_value_present = false'
    runtime_new = 'stage = "runtime", static_value = true, static_value_present = false'
    if runtime_old not in valid:
        raise SystemExit("runtime conditional sentinel anchor was not found")
    write(
        directory / "analog-control-flow-invalid-runtime-static-sentinel.mlir",
        valid.replace(runtime_old, runtime_new, 1),
    )

    else_old = 'stage = "else", static_value = false, static_value_present = false'
    else_new = 'stage = "else", static_value = true, static_value_present = false'
    if else_old not in valid:
        raise SystemExit("else conditional sentinel anchor was not found")
    write(
        directory / "analog-control-flow-invalid-else-static-sentinel.mlir",
        valid.replace(else_old, else_new, 1),
    )

    loop_old = "static_trip_count = 0 : i64, static_trip_count_present = false"
    loop_new = "static_trip_count = 2 : i64, static_trip_count_present = false"
    if loop_old not in valid:
        raise SystemExit("runtime loop sentinel anchor was not found")
    write(
        directory / "analog-control-flow-invalid-loop-static-sentinel.mlir",
        valid.replace(loop_old, loop_new, 1),
    )


def patch_cmake(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    addition = '''foreach(_fixture IN ITEMS runtime-static-sentinel else-static-sentinel)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-003"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

add_test(
  NAME nodal.native.analog-control-flow-rejects-loop-static-sentinel
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-loop-static-sentinel.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-008"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

'''
    text = insert_before(
        text,
        "add_custom_target(check-nodal-native\n",
        addition,
        "canonical staging sentinel tests",
    )
    write(path, text)


def patch_manifest_docs(root: Path) -> None:
    manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
    manifest = json.loads(read(manifest_path))
    semantics = manifest["semantics"]
    semantics["native_canonical_condition_sentinels"] = True
    semantics["native_canonical_loop_sentinels"] = True
    write(manifest_path, json.dumps(manifest, indent=2) + "\n")

    implementation_path = root / "docs/implementation/increment34-analog-control-flow.md"
    implementation = read(implementation_path)
    marker = '''- [x] Reject non-canonical integer and Boolean case-label spellings at the
  compiler boundary.
'''
    addition = '''- [x] Reject hidden static values on runtime or else conditions and hidden
  static trip counts on runtime loops.
'''
    if addition.strip() not in implementation:
        implementation = replace_once(
            implementation,
            marker,
            marker + addition,
            "implementation sentinel checklist",
        )
    write(implementation_path, implementation)

    readme_path = root / "tests/compiler/fixtures/increment34/README.md"
    readme = read(readme_path)
    old = '''orders, assignment-guard dependencies, and canonical integer and Boolean case
labels. Solver construction, target legalization, and Verilog-A or Verilog-AMS
'''
    new = '''orders, assignment-guard dependencies, canonical integer and Boolean case labels,
and canonical absent-value sentinels for runtime conditions and loops. Solver
construction, target legalization, and Verilog-A or Verilog-AMS
'''
    if old in readme:
        readme = readme.replace(old, new, 1)
    elif "canonical absent-value sentinels" not in readme:
        raise SystemExit("fixture README sentinel paragraph was not found")
    write(readme_path, readme)


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    semantic = '''    for key in (
        "native_canonical_condition_sentinels",
        "native_canonical_loop_sentinels",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-043: native staging semantic {key!r} is not enabled",
        )

'''
    text = insert_before(
        text,
        "    integration = manifest.get(\"integration\")\n",
        semantic,
        "manifest staging sentinel checks",
    )
    checks = '''    require_tokens(
        native_verifier,
        (
            "staticPresent.getValue() || staticValue.getValue()",
            "staticPresent.getValue() || staticCount.getInt() != 0",
        ),
        "NODAL-INC34-044",
        "native canonical staging sentinels",
    )
    for fixture in (
        "runtime-static-sentinel",
        "else-static-sentinel",
        "loop-static-sentinel",
    ):
        require(
            (root / f"core/compiler/test/IR/analog-control-flow-invalid-{fixture}.mlir").is_file(),
            f"NODAL-INC34-045: missing staging sentinel fixture {fixture}",
        )

'''
    text = insert_before(
        text,
        "    forbidden_names = {\n",
        checks,
        "native staging sentinel checker block",
    )
    write(path, text)


def patch_mutations(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    marker = '''    "core/compiler/test/IR/analog-control-flow-invalid-case-label.mlir",
'''
    addition = '''    "core/compiler/test/IR/analog-control-flow-invalid-runtime-static-sentinel.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-else-static-sentinel.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-loop-static-sentinel.mlir",
'''
    if addition.strip() not in text:
        text = replace_once(
            text,
            marker,
            marker + addition,
            "staging sentinel fixture inventory",
        )

    methods = '''    def test_native_runtime_static_sentinel_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "staticPresent.getValue() || staticValue.getValue()",
                    "staticPresent.getValue()",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native canonical staging sentinels is missing")

    def test_native_loop_static_sentinel_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "staticPresent.getValue() || staticCount.getInt() != 0",
                    "staticPresent.getValue()",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native canonical staging sentinels is missing")

    def test_native_staging_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["native_canonical_condition_sentinels"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native staging semantic")

'''
    text = insert_before(
        text,
        "    def test_write_enabled_workflow_is_rejected(self) -> None:\n",
        methods,
        "staging sentinel mutation tests",
    )
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_verifier(root)
    make_fixtures(root)
    patch_cmake(root)
    patch_manifest_docs(root)
    patch_checker(root)
    patch_mutations(root)
    print("Increment 34 canonical staging sentinel hardening v10 applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
