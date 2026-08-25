#!/usr/bin/env python3
"""Materialize Increment 22 on a checkout whose Increment 21 is closed."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.strip("\n") + "\n", encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative}: expected one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(relative: str, marker: str, content: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    path.write_text(text.rstrip() + "\n\n" + content.strip("\n") + "\n", encoding="utf-8")


def verify_prerequisite() -> None:
    roadmap = (ROOT / "docs/roadmap/nodal-development-todo.md").read_text(
        encoding="utf-8"
    )
    required = (
        "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
        "- [ ] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**",
    )
    for fragment in required:
        if fragment not in roadmap:
            raise RuntimeError(f"roadmap prerequisite missing: {fragment}")
    manifest_path = ROOT / "tests/compiler/fixtures/increment21/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not str(manifest.get("status", "")).startswith("validated"):
        raise RuntimeError("Increment 21 manifest is not in a validated state")
    evidence = manifest.get("evidence", {})
    if not all(
        isinstance(evidence.get(key), int)
        for key in ("pull_request", "dedicated_run", "core_ci_run")
    ):
        raise RuntimeError("Increment 21 evidence is incomplete")


def main() -> None:
    verify_prerequisite()

    write(
        "core/compiler/include/nodal/Conversion/CIRCT/CIRCTLegalizer.h",
        r'''
#ifndef NODAL_CONVERSION_CIRCT_CIRCTLEGALIZER_H
#define NODAL_CONVERSION_CIRCT_CIRCTLEGALIZER_H

#include "mlir/Pass/Pass.h"

#include <memory>

namespace nodal {

std::unique_ptr<mlir::Pass> createCIRCTLegalizerPass();
void registerCIRCTConversionPasses();

} // namespace nodal

#endif // NODAL_CONVERSION_CIRCT_CIRCTLEGALIZER_H
''',
    )

    write(
        "core/compiler/lib/Conversion/CMakeLists.txt",
        "add_subdirectory(CIRCT)\n",
    )
    write(
        "core/compiler/lib/Conversion/CIRCT/CMakeLists.txt",
        r'''
add_mlir_library(NodalCIRCTConversion
  CIRCTLegalizer.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Conversion/CIRCT"

  LINK_LIBS PUBLIC
    NodalDialect
    CIRCTHW
    MLIRIR
    MLIRPass
    MLIRTransformUtils
    MLIRTransforms
)
''',
    )

    write(
        "core/compiler/lib/Conversion/CIRCT/CIRCTLegalizer.cpp",
        r'''
#include "nodal/Conversion/CIRCT/CIRCTLegalizer.h"

#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinDialect.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Pass/PassRegistry.h"
#include "mlir/Transforms/DialectConversion.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/CommandLine.h"

#include <cstdint>
#include <memory>
#include <string>

namespace nodal {
namespace {

constexpr llvm::StringLiteral kLegalizerVersion = "1";
constexpr llvm::StringLiteral kAuditProfile = "audit";
constexpr llvm::StringLiteral kHwStructuralProfile = "circt-hw-structural";
constexpr llvm::StringLiteral kHwDigitalProfile = "circt-hw-digital";

const llvm::StringSet<> &structuralReadyOperations() {
  static const llvm::StringSet<> operations = [] {
    llvm::StringSet<> result;
    for (llvm::StringRef name : {
             "nodal.module",
             "nodal.port",
             "nodal.parameter",
             "nodal.instance",
             "nodal.domain",
             "nodal.domain_requirement",
             "nodal.domain_bind",
             "nodal.clock_relation",
             "nodal.reset_relation",
             "nodal.interface_abi",
             "nodal.origin",
         })
      result.insert(name);
    return result;
  }();
  return operations;
}

const llvm::StringSet<> &digitalReadyOperations() {
  static const llvm::StringSet<> operations = [] {
    llvm::StringSet<> result;
    for (const auto &entry : structuralReadyOperations())
      result.insert(entry.getKey());
    for (llvm::StringRef name : {
             "nodal.constant",
             "nodal.shape_index",
             "nodal.shape_flatten",
             "nodal.shape_view",
             "nodal.generate",
             "nodal.hardware_loop",
             "nodal.enum",
             "nodal.enum_case",
             "nodal.enum_constant",
             "nodal.fsm",
             "nodal.fsm_state",
             "nodal.fsm_transition",
             "nodal.fsm_action",
             "nodal.fsm_completion",
             "nodal.state_owner",
             "nodal.timing_provenance",
             "nodal.crossing",
         })
      result.insert(name);
    return result;
  }();
  return operations;
}

bool isNodalOperation(mlir::Operation *operation) {
  return operation->getName().getDialectNamespace() == "nodal";
}

bool isReadyForProfile(mlir::Operation *operation, llvm::StringRef profile) {
  if (!isNodalOperation(operation))
    return true;
  if (profile == kAuditProfile)
    return false;
  llvm::StringRef name = operation->getName().getStringRef();
  if (profile == kHwStructuralProfile)
    return structuralReadyOperations().contains(name);
  if (profile == kHwDigitalProfile)
    return digitalReadyOperations().contains(name);
  return false;
}

class CIRCTLegalizerPass final
    : public mlir::PassWrapper<CIRCTLegalizerPass,
                               mlir::OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(CIRCTLegalizerPass)

  CIRCTLegalizerPass() = default;
  CIRCTLegalizerPass(const CIRCTLegalizerPass &other) : PassWrapper(other) {}

  llvm::StringRef getArgument() const final { return "nodal-circt-legalizer"; }
  llvm::StringRef getDescription() const final {
    return "Audit the staged Nodal-to-CIRCT legality boundary without claiming backend lowering";
  }

  void getDependentDialects(mlir::DialectRegistry &registry) const final {
    registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();
  }

  void runOnOperation() final {
    mlir::ModuleOp module = getOperation();
    llvm::StringRef selectedProfile(profile);
    if (selectedProfile != kAuditProfile &&
        selectedProfile != kHwStructuralProfile &&
        selectedProfile != kHwDigitalProfile) {
      module.emitError()
          << "NODAL-CIRCT-LEGALIZE-001: unsupported conversion profile '"
          << selectedProfile << "'";
      signalPassFailure();
      return;
    }

    std::int64_t ready = 0;
    std::int64_t deferred = 0;
    mlir::Operation *firstDeferred = nullptr;
    module.walk([&](mlir::Operation *operation) {
      if (!isNodalOperation(operation))
        return;
      if (isReadyForProfile(operation, selectedProfile))
        ++ready;
      else {
        ++deferred;
        if (!firstDeferred)
          firstDeferred = operation;
      }
    });

    if (failOnDeferred && firstDeferred) {
      firstDeferred->emitError()
          << "NODAL-CIRCT-LEGALIZE-002: operation '"
          << firstDeferred->getName()
          << "' is deferred by profile '" << selectedProfile << "'";
      signalPassFailure();
      return;
    }

    mlir::ConversionTarget target(getContext());
    target.addLegalDialect<mlir::BuiltinDialect, circt::hw::HWDialect>();
    target.addDynamicallyLegalDialect<nodal::NodalDialect>(
        [&](mlir::Operation *operation) {
          return isReadyForProfile(operation, selectedProfile) ||
                 !failOnDeferred;
        });

    mlir::RewritePatternSet patterns(&getContext());
    if (mlir::failed(mlir::applyPartialConversion(module, target,
                                                   std::move(patterns)))) {
      module.emitError()
          << "NODAL-CIRCT-LEGALIZE-003: partial-conversion legality failed";
      signalPassFailure();
      return;
    }

    mlir::Builder builder(&getContext());
    module->setAttr("nodal.circt.legalizer.version",
                    builder.getStringAttr(kLegalizerVersion));
    module->setAttr("nodal.circt.strategy",
                    builder.getStringAttr("staged-partial-conversion"));
    module->setAttr("nodal.circt.profile",
                    builder.getStringAttr(selectedProfile));
    module->setAttr("nodal.circt.ready_ops",
                    builder.getI64IntegerAttr(ready));
    module->setAttr("nodal.circt.deferred_ops",
                    builder.getI64IntegerAttr(deferred));
    module->setAttr("nodal.circt.converted_ops",
                    builder.getI64IntegerAttr(0));
  }

private:
  mlir::Pass::Option<std::string> profile{
      *this, "profile",
      llvm::cl::desc("audit, circt-hw-structural, or circt-hw-digital"),
      llvm::cl::init(kAuditProfile.str())};
  mlir::Pass::Option<bool> failOnDeferred{
      *this, "fail-on-deferred",
      llvm::cl::desc("Reject the first operation outside the selected ready set"),
      llvm::cl::init(false)};
};

} // namespace

std::unique_ptr<mlir::Pass> createCIRCTLegalizerPass() {
  return std::make_unique<CIRCTLegalizerPass>();
}

void registerCIRCTConversionPasses() {
  mlir::PassRegistration<CIRCTLegalizerPass>();
  mlir::PassPipelineRegistration<>(
      "nodal-circt-legalize-pipeline",
      "Audit the versioned Nodal-to-CIRCT legality boundary",
      [](mlir::OpPassManager &manager) {
        manager.addPass(createCIRCTLegalizerPass());
      });
}

} // namespace nodal
''',
    )

    replace_once(
        "core/compiler/lib/CMakeLists.txt",
        "add_subdirectory(Dialect)\n",
        "add_subdirectory(Conversion)\nadd_subdirectory(Dialect)\n",
    )

    nodalc = ROOT / "core/compiler/tools/nodalc/nodalc.cpp"
    nodalc_text = nodalc.read_text(encoding="utf-8")
    include = '#include "nodal/Conversion/CIRCT/CIRCTLegalizer.h"\n'
    if include not in nodalc_text:
        anchor = '#include "nodal/Dialect/Nodal/NodalDialect.h"\n'
        if nodalc_text.count(anchor) != 1:
            raise RuntimeError("nodalc include anchor is not unique")
        nodalc_text = nodalc_text.replace(anchor, include + anchor, 1)
    registration = "  nodal::registerCIRCTConversionPasses();\n"
    if registration not in nodalc_text:
        anchor = "  mlir::DialectRegistry registry;\n"
        if nodalc_text.count(anchor) != 1:
            raise RuntimeError("nodalc registration anchor is not unique")
        nodalc_text = nodalc_text.replace(anchor, registration + "\n" + anchor, 1)
    nodalc.write_text(nodalc_text, encoding="utf-8")

    cmake = ROOT / "core/compiler/tools/nodalc/CMakeLists.txt"
    cmake_text = cmake.read_text(encoding="utf-8")
    if "    NodalCIRCTConversion\n" not in cmake_text:
        anchor = "    NodalDialect\n"
        if cmake_text.count(anchor) != 1:
            raise RuntimeError("nodalc link anchor is not unique")
        cmake_text = cmake_text.replace(
            anchor, "    NodalCIRCTConversion\n" + anchor, 1
        )
    cmake.write_text(cmake_text, encoding="utf-8")

    write(
        "core/compiler/test/IR/circt-legalizer-structural.mlir",
        r'''
module {
  "nodal.module"() <{
    metadata = {},
    sym_name = "LegalizerTop"
  }> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "core"
    }> : () -> ()
    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {},
      sym_name = "enable",
      type = !nodal.bits<1>
    }> : () -> ()
  }) : () -> ()
}
''',
    )
    write(
        "core/compiler/test/IR/circt-legalizer-deferred.mlir",
        r'''
module {
  "nodal.module"() <{
    metadata = {},
    sym_name = "DeferredAnalog"
  }> ({
    %terminal = "nodal.terminal"() <{
      metadata = {},
      name = "pin"
    }> : () -> !nodal.terminal<"electrical">
  }) : () -> ()
}
''',
    )

    append_once(
        "core/compiler/test/CMakeLists.txt",
        "nodal.native.circt-legalizer-audit",
        r'''
add_test(
  NAME nodal.native.circt-legalizer-audit
  COMMAND nodalc
    --pass-pipeline=builtin.module(nodal-circt-legalizer)
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer-structural.mlir"
)
set_tests_properties(nodal.native.circt-legalizer-audit PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal.circt.legalizer.version"
)

add_test(
  NAME nodal.native.circt-legalizer-structural
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-circt-legalizer{profile=circt-hw-structural fail-on-deferred=true})"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer-structural.mlir"
)
set_tests_properties(nodal.native.circt-legalizer-structural PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal.circt.profile"
)
''',
    )

    write(
        "docs/design-gates/NodalCirctConversionLegalizer-DG-v1.0.md",
        r'''
# Nodal CIRCT conversion strategy and legalizer design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 22 creates a versioned, explicit boundary between canonical Nodal
MLIR and future CIRCT lowering. It does not claim that Nodal hardware semantics
have already been converted to CIRCT or that HDL emission is available.

The first legalizer is an audit and legality pass. It classifies canonical
operations against named profiles, publishes deterministic readiness/deferred
counts, uses MLIR's `ConversionTarget` and partial-conversion machinery, and can
reject the first deferred operation with a stable diagnostic.

## Strategy

- `nodal.module`, ports, parameters, instances, and compatible finite-width
  structural values are future candidates for CIRCT `hw` lowering.
- Constants and pure digital value operations may reuse CIRCT/MLIR operations
  only after exact width, sign, shape, four-state, source-map, and parameter
  semantics are proven equivalent.
- Domain, reset, crossing, protocol, interface ABI, FSM, memory/effect, and
  pipeline identity remain explicit until dedicated conversions preserve every
  mandatory verifier contract.
- Conservative analog topology, continuous-time behavior, and mixed-signal
  bridges are not forced into CIRCT `hw`; they remain Nodal-owned or lower to
  later target-appropriate IR.
- No profile may silently drop, approximate, flatten, specialize, or clone
  unsupported semantics.

## Profiles

`audit` records the complete deferred inventory. `circt-hw-structural` exposes
the minimal hierarchy boundary. `circt-hw-digital` additionally identifies
candidate pure-digital structural/control operations. All profiles report zero
converted operations in Increment 22.

## Transactional boundary

The pass validates the complete selected legality boundary before publishing
module attributes. Failure leaves no accepted partial conversion. Increment 21
verification remains mandatory before and after this pass.

## Deferred

Actual rewrite patterns, CIRCT type conversion, interface flattening, domain and
state lowering, scheduling, backend translation, and HDL emission remain later
roadmap work.
''',
    )

    write(
        "docs/implementation/increment22-circt-conversion-legalizer.md",
        r'''
# Increment 22 — CIRCT conversion strategy and legalizer skeleton

The native compiler now registers `nodal-circt-legalizer` and the explicit
`nodal-circt-legalize-pipeline` textual pipeline.

The pass uses MLIR `ConversionTarget` infrastructure but deliberately contains
no rewrite patterns. It classifies Nodal operations into ready and deferred
sets for three versioned profiles, optionally rejects deferred operations, and
publishes deterministic strategy, profile, version, ready, deferred, and
converted-count attributes. The converted count is always zero in this
increment.

This structure gives later conversion increments a stable place to add proven
patterns without conflating Nodal semantics with CIRCT spelling. Analog and
mixed-signal operations remain explicitly deferred rather than being coerced
into digital hardware IR.
''',
    )

    write(
        "tests/compiler/fixtures/increment22/manifest.json",
        json.dumps(
            {
                "increment": 22,
                "public_api": "0.3",
                "status": "implemented-awaiting-evidence",
                "legalizer_version": 1,
                "pipeline": "nodal-circt-legalize-pipeline",
                "profiles": [
                    "audit",
                    "circt-hw-structural",
                    "circt-hw-digital",
                ],
                "converted_operations": 0,
                "evidence": {},
            },
            indent=2,
        )
        + "\n",
    )

    write(
        "scripts/check_increment22.py",
        r'''
#!/usr/bin/env python3
"""Validate Increment 22 CIRCT conversion strategy and legalizer skeleton."""

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


EXPECTED = (
    "core/compiler/include/nodal/Conversion/CIRCT/CIRCTLegalizer.h",
    "core/compiler/lib/Conversion/CMakeLists.txt",
    "core/compiler/lib/Conversion/CIRCT/CMakeLists.txt",
    "core/compiler/lib/Conversion/CIRCT/CIRCTLegalizer.cpp",
    "core/compiler/test/IR/circt-legalizer-structural.mlir",
    "core/compiler/test/IR/circt-legalizer-deferred.mlir",
    "docs/design-gates/NodalCirctConversionLegalizer-DG-v1.0.md",
    "docs/implementation/increment22-circt-conversion-legalizer.md",
    "tests/compiler/fixtures/increment22/manifest.json",
    ".github/workflows/increment-22-circt-legalizer.yml",
)


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC22-001", f"missing file: {relative}"))

    source_path = root / "core/compiler/lib/Conversion/CIRCT/CIRCTLegalizer.cpp"
    source = source_path.read_text(encoding="utf-8") if source_path.exists() else ""
    required = (
        "mlir::ConversionTarget",
        "mlir::applyPartialConversion",
        '"nodal-circt-legalizer"',
        '"nodal-circt-legalize-pipeline"',
        "NODAL-CIRCT-LEGALIZE-001",
        "NODAL-CIRCT-LEGALIZE-002",
        "nodal.circt.converted_ops",
        "llvm::cl::init(false)",
    )
    for fragment in required:
        if fragment not in source:
            problems.append(
                Problem("NODAL-INC22-002", f"legalizer lacks: {fragment}")
            )
    if "addLegalDialect<mlir::BuiltinDialect, circt::hw::HWDialect>" not in source:
        problems.append(
            Problem("NODAL-INC22-003", "CIRCT HW legality boundary is missing")
        )
    if "RewritePattern" in source and "RewritePatternSet patterns" not in source:
        problems.append(
            Problem("NODAL-INC22-004", "unexpected rewrite implementation")
        )

    workflow_path = root / ".github/workflows/increment-22-circt-legalizer.yml"
    workflow = workflow_path.read_text(encoding="utf-8") if workflow_path.exists() else ""
    for fragment in (
        "permissions:\n  contents: read",
        "check_increment21.py",
        "check_increment22.py",
        "./nodal core native",
        "nodal-circt-legalizer",
    ):
        if fragment not in workflow:
            problems.append(
                Problem("NODAL-INC22-005", f"workflow lacks: {fragment}")
            )
    if "contents: write" in workflow:
        problems.append(Problem("NODAL-INC22-005", "workflow is writable"))

    manifest_path = root / "tests/compiler/fixtures/increment22/manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(Problem("NODAL-INC22-006", f"invalid manifest: {exc}"))
    else:
        if manifest.get("increment") != 22 or manifest.get("public_api") != "0.3":
            problems.append(Problem("NODAL-INC22-006", "manifest identity mismatch"))
        if manifest.get("converted_operations") != 0:
            problems.append(
                Problem("NODAL-INC22-006", "skeleton must claim zero conversions")
            )
        if manifest.get("status") not in {
            "implemented-awaiting-evidence",
            "validated-circt-legalizer-skeleton",
        }:
            problems.append(Problem("NODAL-INC22-006", "unexpected manifest status"))

    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8") if roadmap_path.exists() else ""
    if "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**" not in roadmap:
        problems.append(Problem("NODAL-INC22-007", "Increment 21 is not closed"))
    if not any(
        marker in roadmap
        for marker in (
            "- [ ] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**",
            "- [x] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**",
        )
    ):
        problems.append(Problem("NODAL-INC22-007", "Increment 22 roadmap entry missing"))
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print("Increment 22 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
''',
    )

    write(
        "tests/compiler/test_increment22.py",
        r'''
from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment22.py"
SPEC = importlib.util.spec_from_file_location("check_increment22", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment22CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        support = (
            "docs/roadmap/nodal-development-todo.md",
            "scripts/check_increment22.py",
        )
        for relative in dict.fromkeys(CHECKER.EXPECTED + support):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self):
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_conversion_target(self):
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Conversion/CIRCT/CIRCTLegalizer.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "mlir::ConversionTarget", "RemovedConversionTarget"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-002", self.codes(root))

    def test_rejects_false_conversion_claim(self):
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment22/manifest.json"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '"converted_operations": 0', '"converted_operations": 1'
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-006", self.codes(root))


if __name__ == "__main__":
    unittest.main()
''',
    )

    write(
        ".github/workflows/increment-22-circt-legalizer.yml",
        r'''
name: Increment 22 CIRCT Legalizer Skeleton

on:
  push:
    branches:
      - increment/22-circt-conversion-legalizer-skeleton
  pull_request:
    branches:
      - dev
  workflow_dispatch:

permissions:
  contents: read

jobs:
  legalizer:
    name: increment-22/circt-legalizer
    runs-on: ubuntu-24.04
    timeout-minutes: 120

    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - uses: actions/cache@v5
        with:
          path: ~/.cache/nodal/downloads
          key: nodal-native-${{ runner.os }}-${{ hashFiles('toolchains/lock.json', 'toolchains/checksums/*.sha256') }}

      - name: Install locked toolchains
        run: |
          ./nodal bootstrap --mode prebuilt --prefix "${RUNNER_TEMP}/native"
          ./nodal style bootstrap --prefix "${RUNNER_TEMP}/lint"

      - name: Validate predecessor and structural contracts
        run: |
          python3 scripts/check_increment21.py
          python3 scripts/check_increment22.py
          python3 -m unittest discover -s tests/compiler -p 'test_*.py'
          ./nodal check --contracts-only --online-toolchain \
            --lint-toolchain "${RUNNER_TEMP}/lint" --base-ref origin/dev
          git diff --check

      - name: Build and test native compiler
        run: |
          ./nodal core native \
            --toolchain "${RUNNER_TEMP}/native" \
            --lint-toolchain "${RUNNER_TEMP}/lint"

      - name: Exercise explicit legalizer pipelines
        run: |
          compiler=out/native/release/bin/nodalc
          "${compiler}" \
            '--pass-pipeline=builtin.module(nodal-circt-legalizer{profile=audit})' \
            core/compiler/test/IR/circt-legalizer-structural.mlir \
            | tee /tmp/circt-audit.mlir
          grep -F 'nodal.circt.legalizer.version' /tmp/circt-audit.mlir
          grep -F 'nodal.circt.converted_ops = 0' /tmp/circt-audit.mlir

          "${compiler}" \
            '--pass-pipeline=builtin.module(nodal-circt-legalizer{profile=circt-hw-structural fail-on-deferred=true})' \
            core/compiler/test/IR/circt-legalizer-structural.mlir \
            | tee /tmp/circt-structural.mlir
          grep -F 'circt-hw-structural' /tmp/circt-structural.mlir

          if "${compiler}" \
              '--pass-pipeline=builtin.module(nodal-circt-legalizer{profile=circt-hw-structural fail-on-deferred=true})' \
              core/compiler/test/IR/circt-legalizer-deferred.mlir \
              >/tmp/circt-deferred.log 2>&1; then
            echo 'deferred analog operation was accepted' >&2
            exit 1
          fi
          grep -F 'NODAL-CIRCT-LEGALIZE-002' /tmp/circt-deferred.log
''',
    )

    append_once(
        ".github/CODEOWNERS",
        "# Increment 22 CIRCT conversion strategy and legalizer skeleton.",
        r'''
# Increment 22 CIRCT conversion strategy and legalizer skeleton.
/core/compiler/include/nodal/Conversion/CIRCT/ @pysolvesemi
/core/compiler/lib/Conversion/CIRCT/ @pysolvesemi
/scripts/check_increment22.py @pysolvesemi
/tests/compiler/test_increment22.py @pysolvesemi
/tests/compiler/fixtures/increment22/ @pysolvesemi
/.github/workflows/increment-22-circt-legalizer.yml @pysolvesemi
/docs/design-gates/NodalCirctConversionLegalizer-DG-v1.0.md @pysolvesemi
''',
    )


if __name__ == "__main__":
    main()
