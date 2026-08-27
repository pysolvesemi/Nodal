#!/usr/bin/env python3
"""Validate Increment 23: backend framework and capability profiles."""

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
    "core/compiler/backend-profiles-v0.1.json",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/include/nodal/CMakeLists.txt",
    "core/compiler/include/nodal/Backend/CMakeLists.txt",
    "core/compiler/include/nodal/Backend/Backend.h",
    "core/compiler/lib/CMakeLists.txt",
    "core/compiler/lib/Backend/CMakeLists.txt",
    "core/compiler/lib/Backend/Backend.cpp",
    "core/compiler/tools/CMakeLists.txt",
    "core/compiler/tools/nodal-translate/CMakeLists.txt",
    "core/compiler/tools/nodal-translate/nodal-translate.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/BackendTest.cpp",
    "core/compiler/test/IR/backend-minimal-analog.mlir",
    "core/compiler/test/IR/backend-minimal-ams.mlir",
    "core/compiler/test/IR/backend-invalid-config.mlir",
    "core/compiler/test/IR/backend-unsupported-verilog-a.mlir",
    "docs/design-gates/NodalBackendFramework-DG-v1.0.md",
    "docs/implementation/increment23-backend-framework.md",
    "tests/compiler/fixtures/increment23/golden/minimal-analog.va",
    "tests/compiler/fixtures/increment23/golden/minimal-ams.vams",
    "tests/compiler/fixtures/increment23/manifest.json",
    "tests/compiler/test_increment23.py",
    "scripts/check_increment23.py",
    ".github/workflows/increment-23-backend-framework.yml",
    "docs/roadmap/nodal-development-todo.md",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment23.py",
    "scripts/finalize_increment23.py",
    ".github/workflows/increment-23-materialize.yml",
    ".github/workflows/increment-23-finalize.yml",
    ".github/workflows/increment-23-supervisor.yml",
)

REQUIRED_CODES = (
    "NODAL-BACKEND-CONFIG-001",
    "NODAL-BACKEND-CONFIG-002",
    "NODAL-BACKEND-PROFILE-001",
    "NODAL-BACKEND-PROFILE-002",
    "NODAL-BACKEND-CAPABILITY-001",
    "NODAL-BACKEND-NAMING-001",
    "NODAL-BACKEND-VERIFY-001",
    "NODAL-BACKEND-REPARSE-001",
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


def load_json(
    path: Path, problems: list[Problem], code: str
) -> dict[str, object]:
    try:
        value = json.loads(read(path, problems, code))
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC23-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem("NODAL-INC23-002", f"temporary closure file remains: {relative}")
            )

    header = read(
        root / "core/compiler/include/nodal/Backend/Backend.h",
        problems,
        "NODAL-INC23-003",
    )
    backend = read(
        root / "core/compiler/lib/Backend/Backend.cpp",
        problems,
        "NODAL-INC23-004",
    )
    backend_extension_path = (
        root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
    )
    backend_extension = (
        backend_extension_path.read_text(encoding="utf-8")
        if backend_extension_path.is_file()
        else ""
    )
    backend_contract = backend + "\n" + backend_extension
    include_cmake = read(
        root / "core/compiler/include/nodal/CMakeLists.txt",
        problems,
        "NODAL-INC23-005",
    )
    lib_cmake = read(
        root / "core/compiler/lib/CMakeLists.txt",
        problems,
        "NODAL-INC23-005",
    )
    backend_cmake = read(
        root / "core/compiler/lib/Backend/CMakeLists.txt",
        problems,
        "NODAL-INC23-005",
    )
    tools_cmake = read(
        root / "core/compiler/tools/CMakeLists.txt",
        problems,
        "NODAL-INC23-006",
    )
    tool_cmake = read(
        root / "core/compiler/tools/nodal-translate/CMakeLists.txt",
        problems,
        "NODAL-INC23-006",
    )
    tool = read(
        root / "core/compiler/tools/nodal-translate/nodal-translate.cpp",
        problems,
        "NODAL-INC23-006",
    )
    native_tests = read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-INC23-007",
    )
    unit_cmake = read(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-INC23-007",
    )
    unit_test = read(
        root / "core/compiler/test/Unit/BackendTest.cpp",
        problems,
        "NODAL-INC23-007",
    )
    gate = read(
        root / "docs/design-gates/NodalBackendFramework-DG-v1.0.md",
        problems,
        "NODAL-INC23-008",
    )
    workflow = read(
        root / ".github/workflows/increment-23-backend-framework.yml",
        problems,
        "NODAL-INC23-009",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC23-010",
    )

    require(
        header,
        (
            "enum class BackendKind",
            "enum class ShapedValueLayout",
            "enum class MaterializationPolicy",
            "enum class NamingPolicy",
            "struct BackendProfile",
            "struct BackendConfiguration",
            "class TargetVerificationHooks",
            "resolveBackendConfiguration",
            "emitBackend",
            "registerNodalBackendTranslations",
        ),
        problems,
        "NODAL-INC23-003",
        "backend public native header",
    )
    require(
        backend_contract,
        (
            "nodal-to-verilog-a",
            "nodal-to-verilog-ams",
            "scalar-or-flat",
            "flat-packed",
            "safe-inline",
            "readable",
            "nodal.backend.check_profile",
            "runNodalPipelineTransaction",
            "llvm::sort",
            "std::string candidate",
            "raw_string_ostream candidateStream",
            "selectedHooks.verifyTarget",
            "selectedHooks.reparseTarget",
            "output << candidate",
            "TranslateFromMLIRRegistration",
        )
        + REQUIRED_CODES,
        problems,
        "NODAL-INC23-004",
        "native backend implementation",
    )
    verify_index = backend.find("selectedHooks.verifyTarget")
    reparse_index = backend.find("selectedHooks.reparseTarget")
    publish_index = backend.find("output << candidate")
    if not (0 <= verify_index < reparse_index < publish_index):
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "candidate publication must occur after target verify and reparse hooks",
            )
        )
    if "renderCandidate(definitions, *configuration, output)" in backend:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "renderer writes directly to caller output instead of a private candidate",
            )
        )

    require(
        include_cmake,
        ("add_subdirectory(Backend)",),
        problems,
        "NODAL-INC23-005",
        "native header CMake",
    )
    require(
        lib_cmake,
        ("add_subdirectory(Backend)",),
        problems,
        "NODAL-INC23-005",
        "native library CMake",
    )
    require(
        backend_cmake,
        (
            "add_mlir_library(NodalBackend",
            "NodalDiagnostics",
            "NodalTransforms",
            "MLIRTranslateLib",
        ),
        problems,
        "NODAL-INC23-005",
        "backend library target",
    )
    require(
        tools_cmake,
        ("add_subdirectory(nodal-translate)",),
        problems,
        "NODAL-INC23-006",
        "native tools CMake",
    )
    require(
        tool_cmake,
        (
            "add_llvm_executable(nodal-translate",
            "NodalBackend",
            "MLIRTranslateLib",
        ),
        problems,
        "NODAL-INC23-006",
        "translation tool target",
    )
    require(
        tool,
        (
            "registerNodalBackendTranslations",
            "mlirTranslateMain",
            "AddExtraVersionPrinter",
        ),
        problems,
        "NODAL-INC23-006",
        "translation driver",
    )
    require(
        native_tests,
        (
            "backend-translation-registration",
            "backend-verilog-a",
            "backend-verilog-ams",
            "backend-rejects-invalid-config",
            "backend-rejects-unsupported-verilog-a",
            "nodal-backend-unit-tests",
            "nodal-translate",
        ),
        problems,
        "NODAL-INC23-007",
        "native backend CTest registration",
    )
    require(
        unit_cmake,
        (
            "nodal-backend-unit-tests",
            "BackendTest.cpp",
            "NodalBackend",
        ),
        problems,
        "NODAL-INC23-007",
        "backend unit target",
    )
    require(
        unit_test,
        (
            "repeated emissions are not byte-identical",
            "module output is not sorted by semantic name",
            "failed capability checking published partial output",
            "failed target reparse published partial output",
            "CheckProfile configuration was not retained",
        ),
        problems,
        "NODAL-INC23-007",
        "backend transaction unit test",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-backend-framework",
            "**Public API:** unchanged at 0.3",
            "private in-memory candidate",
            "Increment 24",
            "Backend.Auto",
        ),
        problems,
        "NODAL-INC23-008",
        "backend design gate",
    )
    require(
        workflow,
        (
            "increment-23/backend-framework",
            "check_increment23.py",
            "nodal-to-verilog-a",
            "nodal-to-verilog-ams",
            "backend-minimal-analog.mlir",
            "backend-minimal-ams.mlir",
            "NODAL-BACKEND-CAPABILITY-001",
            "NODAL-BACKEND-CONFIG-002",
            "./nodal core native",
            "./nodal core scala",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC23-009",
        "permanent Increment 23 workflow",
    )
    if "contents: write" in workflow or "materialize_increment23" in workflow:
        problems.append(
            Problem("NODAL-INC23-009", "permanent Increment 23 workflow must be read-only")
        )

    catalog = load_json(
        root / "core/compiler/diagnostics-v0.1.json",
        problems,
        "NODAL-INC23-011",
    )
    catalog_text = json.dumps(catalog, sort_keys=True)
    for code in REQUIRED_CODES:
        if code not in catalog_text:
            problems.append(
                Problem("NODAL-INC23-011", f"diagnostic catalog lacks code: {code}")
            )
    if "NODAL-BACKEND-" not in catalog_text:
        problems.append(
            Problem("NODAL-INC23-011", "diagnostic catalog lacks backend prefix preservation")
        )

    profiles = load_json(
        root / "core/compiler/backend-profiles-v0.1.json",
        problems,
        "NODAL-INC23-012",
    )
    if profiles.get("public_api") != "0.3-unchanged":
        problems.append(
            Problem("NODAL-INC23-012", "backend profiles must leave public API at 0.3")
        )
    if profiles.get("transaction") != "clone-gate-buffer-verify-reparse-publish":
        problems.append(
            Problem("NODAL-INC23-012", "backend profile transaction contract mismatch")
        )
    profile_entries = profiles.get("profiles")
    if not isinstance(profile_entries, list) or len(profile_entries) != 2:
        problems.append(
            Problem("NODAL-INC23-012", "profile catalog must contain exactly two profiles")
        )
    else:
        by_id = {
            entry.get("id"): entry
            for entry in profile_entries
            if isinstance(entry, dict)
        }
        expected = {
            "verilog-a": (
                "nodal-to-verilog-a",
                "scalar-or-flat",
                "safe-inline",
                ["analog"],
            ),
            "verilog-ams": (
                "nodal-to-verilog-ams",
                "flat-packed",
                "readable",
                ["analog", "mixed_signal"],
            ),
        }
        for profile_id, (
            translation,
            layout,
            materialization,
            design_kinds,
        ) in expected.items():
            entry = by_id.get(profile_id)
            if not isinstance(entry, dict):
                problems.append(
                    Problem("NODAL-INC23-012", f"missing profile: {profile_id}")
                )
                continue
            if (
                entry.get("translation") != translation
                or entry.get("shaped_value_layout") != layout
                or entry.get("expression_materialization") != materialization
                or entry.get("naming") != "semantic"
                or entry.get("design_kinds") != design_kinds
            ):
                problems.append(
                    Problem(
                        "NODAL-INC23-012",
                        f"profile contract mismatch for {profile_id}",
                    )
                )

    analog_golden = read(
        root / "tests/compiler/fixtures/increment23/golden/minimal-analog.va",
        problems,
        "NODAL-INC23-013",
    )
    ams_golden = read(
        root / "tests/compiler/fixtures/increment23/golden/minimal-ams.vams",
        problems,
        "NODAL-INC23-013",
    )
    require(
        analog_golden,
        (
            "profile: verilog-a",
            "shaped-layout: scalar-or-flat",
            "materialization: safe-inline",
            "module Alpha;",
            "module Zeta;",
        ),
        problems,
        "NODAL-INC23-013",
        "Verilog-A framework golden",
    )
    if analog_golden.find("module Alpha;") > analog_golden.find("module Zeta;"):
        problems.append(
            Problem("NODAL-INC23-013", "Verilog-A golden is not semantic-name ordered")
        )
    require(
        ams_golden,
        (
            "profile: verilog-ams",
            "check-profile: release",
            "shaped-layout: flat-packed",
            "materialization: readable",
            "module MixedTop;",
        ),
        problems,
        "NODAL-INC23-013",
        "Verilog-AMS framework golden",
    )

    manifest = load_json(
        root / "tests/compiler/fixtures/increment23/manifest.json",
        problems,
        "NODAL-INC23-010",
    )
    if manifest.get("increment") != 23:
        problems.append(Problem("NODAL-INC23-010", "manifest increment must be 23"))
    if manifest.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-INC23-010", "Increment 23 must leave public API at 0.3")
        )
    if manifest.get("transaction") != "clone-gate-buffer-verify-reparse-publish":
        problems.append(
            Problem("NODAL-INC23-010", "manifest transaction contract mismatch")
        )
    if manifest.get("translations") != [
        "nodal-to-verilog-a",
        "nodal-to-verilog-ams",
    ]:
        problems.append(
            Problem("NODAL-INC23-010", "manifest translation identities mismatch")
        )

    revision = roadmap_revision(roadmap)
    increment22_checked = (
        "- [x] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
    )
    increment23_unchecked = (
        "- [ ] **Increment 23 — Backend framework and capability profiles**" in roadmap
    )
    increment23_checked = (
        "- [x] **Increment 23 — Backend framework and capability profiles**" in roadmap
    )
    increment24_unchecked = (
        "- [ ] **Increment 24 — Minimal analog expression and contribution IR**" in roadmap
    )
    increment24_checked = (
        "- [x] **Increment 24 — Minimal analog expression and contribution IR**" in roadmap
    )
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})

    if not increment22_checked:
        problems.append(
            Problem("NODAL-INC23-010", "Increment 22 prerequisite is not closed")
        )
    if status == "implemented-awaiting-evidence":
        if not increment23_unchecked or revision < (1, 26):
            problems.append(
                Problem(
                    "NODAL-INC23-010",
                    "pre-evidence state must leave Increment 23 unchecked at revision 1.26 or later",
                )
            )
    elif status == "validated-backend-framework":
        if not increment23_checked or revision < (1, 27):
            problems.append(
                Problem(
                    "NODAL-INC23-010",
                    "validated state must close Increment 23 at revision 1.27 or later",
                )
            )
        if not isinstance(evidence, dict):
            problems.append(
                Problem("NODAL-INC23-010", "validated manifest evidence must be an object")
            )
        else:
            for field in ("pull_request", "dedicated_run", "core_ci_run"):
                if not isinstance(evidence.get(field), int):
                    problems.append(
                        Problem(
                            "NODAL-INC23-010",
                            f"validated manifest lacks integer evidence field: {field}",
                        )
                    )
    else:
        problems.append(
            Problem("NODAL-INC23-010", f"unexpected manifest status: {status!r}")
        )

    increment24_status = None
    increment24_manifest = root / "tests/compiler/fixtures/increment24/manifest.json"
    if increment24_manifest.is_file():
        try:
            increment24_value = json.loads(
                increment24_manifest.read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError) as exc:
            problems.append(
                Problem(
                    "NODAL-INC23-010",
                    f"cannot read Increment 24 successor evidence: {exc}",
                )
            )
        else:
            increment24_status = increment24_value.get("status")

    if increment24_status == "validated-minimal-analog-ir":
        if not increment24_checked:
            problems.append(
                Problem(
                    "NODAL-INC23-010",
                    "validated Increment 24 evidence requires its roadmap item to be checked",
                )
            )
    elif not increment24_unchecked:
        problems.append(
            Problem(
                "NODAL-INC23-010",
                "Increment 24 must remain unchecked until validated evidence exists",
            )
        )


    for fragment in (
        "kVerilogReservedIdentifiers",
        '"input"',
        "Attribute raw = module->getAttr(attribute)",
    ):
        if fragment not in backend:
            problems.append(
                Problem(
                    "NODAL-INC23-004",
                    f"backend target parser lacks review contract: {fragment}",
                )
            )
    legacy_target_parser = (
        "countModuleDeclarations" in backend
        and "countExactLines" in backend
    )
    delegated_target_parser = (
        "verifyBackendTarget" in backend_contract
        and "reparseBackendTarget" in backend_contract
    )
    if not legacy_target_parser and not delegated_target_parser:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "backend target parser lacks either the original exact-line contract "
                "or the verified Increment 25 delegation",
            )
        )
    if "countOccurrences" in backend_contract:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "backend target verification uses substring occurrence counting",
            )
        )

    if "  output << candidate;\n  return success();" not in backend:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "backend candidate must be published only after target verify and reparse hooks",
            )
        )
    for owned_attribute in (
        "nodal.backend.shaped_layout",
        "nodal.backend.materialization",
        "nodal.backend.naming",
    ):
        if owned_attribute not in backend:
            problems.append(
                Problem(
                    "NODAL-INC23-004",
                    f"backend profile ownership check lacks attribute: {owned_attribute}",
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
            f"Increment 23 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("Increment 23 backend framework check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
