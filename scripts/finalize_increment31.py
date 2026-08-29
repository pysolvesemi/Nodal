#!/usr/bin/env python3
# Apply Increment 31 semantic-pipeline and probe-provenance review fixes.

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{path.relative_to(ROOT)} replacement count is {count}, expected 1"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    header = ROOT / "core/compiler/include/nodal/Dialect/Nodal/PotentialFlowAccess.h"
    replace_once(
        header,
        '#include "mlir/IR/Operation.h"\n#include "mlir/Support/LogicalResult.h"\n',
        '#include "mlir/IR/Operation.h"\n#include "mlir/Pass/Pass.h"\n'
        '#include "mlir/Support/LogicalResult.h"\n',
    )
    replace_once(header, "#include <string>\n", "#include <memory>\n#include <string>\n")
    replace_once(
        header,
        "mlir::LogicalResult verifyPotentialFlowAccessOperation(mlir::Operation *operation);\n\n"
        "/// Normalize one-terminal references and source-free probe records. The\n",
        "mlir::LogicalResult verifyPotentialFlowAccessOperation(mlir::Operation *operation);\n\n"
        "/// Construct the normalizer used by the transactional semantic gate and\n"
        "/// explicit pass pipelines.\n"
        "std::unique_ptr<mlir::Pass> createNormalizePotentialFlowAccessPass();\n\n"
        "/// Normalize one-terminal references and source-free probe records. The\n",
    )

    implementation = ROOT / "core/compiler/lib/Dialect/Nodal/PotentialFlowAccess.cpp"
    replace_once(
        implementation,
        "#include <string>\n",
        "#include <memory>\n#include <string>\n",
    )
    replace_once(
        implementation,
        '''bool probeSubjectMatches(Operation *probe, const AccessGroup &group) {
  if (textAttr(probe, "form") != group.form || probe->getNumOperands() != group.subject.size())
    return false;
  if (group.subject.size() == 1)
    return probe->getOperand(0) == group.subject[0];
  return matchingTerminalPair(probe->getOperand(0), probe->getOperand(1), group.subject[0],
                              group.subject[1]);
}

''',
        '''bool probeSubjectMatches(Operation *probe, const AccessGroup &group) {
  if (textAttr(probe, "form") != group.form || probe->getNumOperands() != group.subject.size())
    return false;
  if (group.subject.size() == 1)
    return probe->getOperand(0) == group.subject[0];
  return probe->getOperand(0) == group.subject[0] &&
         probe->getOperand(1) == group.subject[1];
}

bool probeProvenanceMatches(Operation *probe, const AccessGroup &group) {
  auto provenance = probe->getAttrOfType<ArrayAttr>("provenance");
  const size_t expectedSize = group.provenance.empty() ? 1 : group.provenance.size();
  if (!provenance || provenance.size() != expectedSize)
    return false;
  if (group.provenance.empty()) {
    auto value = llvm::dyn_cast<StringAttr>(provenance[0]);
    return value && value.getValue() == group.form;
  }
  for (auto [index, expected] : llvm::enumerate(group.provenance)) {
    auto value = llvm::dyn_cast<StringAttr>(provenance[index]);
    if (!value || value.getValue() != expected)
      return false;
  }
  return true;
}

''',
    )
    replace_once(
        implementation,
        "LogicalResult normalizeModule(Operation *module) {\n",
        "LogicalResult verifyNormalizedModule(Operation *module);\n\n"
        "LogicalResult normalizeModule(Operation *module) {\n",
    )
    replace_once(
        implementation,
        '''  for (Operation *probe : existingProbes) {
    if (failed(verifyProbeOperation(probe)))
      return failure();
  }
  for (Operation *probe : existingProbes)
    probe->erase();
''',
        '''  for (Operation *probe : existingProbes) {
    if (failed(verifyProbeOperation(probe)))
      return failure();
  }
  if (!existingProbes.empty() && failed(verifyNormalizedModule(module)))
    return failure();
  for (Operation *probe : existingProbes)
    probe->erase();
''',
    )
    replace_once(
        implementation,
        '''    if (needsProbe) {
      llvm::StringRef expected =
          group.potential ? llvm::StringRef("potential") : llvm::StringRef("flow");
      if (textAttr(matching.front(), "kind") != expected)
        return fail(matching.front(), "NODAL-PROBE-KIND-001",
                    "probe record kind does not match its access group");
    }
''',
        '''    if (needsProbe) {
      llvm::StringRef expected =
          group.potential ? llvm::StringRef("potential") : llvm::StringRef("flow");
      if (textAttr(matching.front(), "kind") != expected)
        return fail(matching.front(), "NODAL-PROBE-KIND-001",
                    "probe record kind does not match its access group");
      if (!probeProvenanceMatches(matching.front(), group))
        return fail(matching.front(), "NODAL-PROBE-PROVENANCE-001",
                    "probe provenance does not match its source-free access group");
    }
''',
    )
    replace_once(
        implementation,
        '''static PassRegistration<NormalizePotentialFlowAccessPass> registerNormalizePotentialFlowAccessPass;

} // namespace

FailureOr<ResolvedAccessNature> resolvePotentialFlowAccessNature(Operation *scope,
''',
        '''static PassRegistration<NormalizePotentialFlowAccessPass> registerNormalizePotentialFlowAccessPass;

} // namespace

std::unique_ptr<Pass> createNormalizePotentialFlowAccessPass() {
  return std::make_unique<NormalizePotentialFlowAccessPass>();
}

FailureOr<ResolvedAccessNature> resolvePotentialFlowAccessNature(Operation *scope,
''',
    )

    passes = ROOT / "core/compiler/lib/Transforms/Passes.cpp"
    replace_once(
        passes,
        '#include "nodal/Dialect/Nodal/ParameterModel.h"\n',
        '#include "nodal/Dialect/Nodal/ParameterModel.h"\n'
        '#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"\n',
    )
    replace_once(
        passes,
        '''          name == "nodal.flow_conservation" || name == "nodal.access" || name == "nodal.analog" ||
          name == "nodal.real_literal" || name == "nodal.analog_integer_literal" ||
''',
        '''          name == "nodal.flow_conservation" || name == "nodal.access" ||
          name == "nodal.terminal_access" || name == "nodal.port_flow_access" ||
          name == "nodal.probe" || name == "nodal.analog" || name == "nodal.real_literal" ||
          name == "nodal.analog_integer_literal" ||
''',
    )
    replace_once(
        passes,
        '''        name == "nodal.flow_conservation" || name == "nodal.access" || name == "nodal.bridge" ||
        name == "nodal.analog" || name == "nodal.real_literal" ||
''',
        '''        name == "nodal.flow_conservation" || name == "nodal.access" ||
        name == "nodal.terminal_access" || name == "nodal.port_flow_access" ||
        name == "nodal.probe" || name == "nodal.bridge" || name == "nodal.analog" ||
        name == "nodal.real_literal" ||
''',
    )
    replace_once(
        passes,
        '''  manager.addPass(std::make_unique<MaterializeConservativeConnectivityPass>());
  addVerifierPasses(manager, profile);
''',
        '''  manager.addPass(std::make_unique<MaterializeConservativeConnectivityPass>());
  manager.addPass(createNormalizePotentialFlowAccessPass());
  addVerifierPasses(manager, profile);
''',
    )

    cmake = ROOT / "core/compiler/test/CMakeLists.txt"
    replace_once(
        cmake,
        '''add_test(
  NAME nodal.native.potential-flow-access-reference
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-normalize-potential-flow-access)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access.mlir"
)
''',
        '''add_test(
  NAME nodal.native.potential-flow-access-reference
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-gate-release)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access.mlir"
)
''',
    )

    fixture = ROOT / "core/compiler/test/IR/potential-flow-access-invalid-probe-provenance.mlir"
    fixture.write_text(
        '''module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadProbeProvenance"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %potential = "nodal.access"(%branch) <{function = "Across", kind = "potential", metadata = {source_path = "BadProbeProvenance.potential"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
    "nodal.probe"(%branch) <{constraint_intent = "zero-flow", form = "branch", kind = "potential", metadata = {compiler_owned = true, generated_by = "increment31-potential-flow-access"}, provenance = ["BadProbeProvenance.forged"]}> : (!nodal.branch<"electrical">) -> ()
  }) : () -> ()
}
''',
        encoding="utf-8",
    )

    manifest_path = ROOT / "tests/compiler/fixtures/increment31/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["implementation"]["semantic_pipeline"] = "normalize-before-verification"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    note = ROOT / "docs/implementation/increment31-potential-flow-access.md"
    replace_once(
        note,
        '''- `normalizePotentialFlowAccess` and
  `nodal-normalize-potential-flow-access` provide deterministic, idempotent
  reference and probe normalization.
''',
        '''- `normalizePotentialFlowAccess` and
  `nodal-normalize-potential-flow-access` provide deterministic, idempotent
  reference and probe normalization.
- The transactional Fast, Default, and Release semantic gates run access
  normalization after conservative-connectivity materialization and before
  mandatory verification.
''',
    )

    checker = ROOT / "scripts/check_increment31.py"
    replace_once(
        checker,
        '    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",\n'
        '    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",\n',
        '    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",\n'
        '    "core/compiler/lib/Transforms/Passes.cpp",\n'
        '    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",\n',
    )
    replace_once(
        checker,
        '            "public Scala API remains v0.3",\n',
        '            "public Scala API remains v0.3",\n'
        '            "transactional Fast, Default, and Release semantic gates",\n',
    )
    replace_once(
        checker,
        '        "backend_entry": "normalizePotentialFlowAccess-before-quantity-erasure",\n',
        '        "backend_entry": "normalizePotentialFlowAccess-before-quantity-erasure",\n'
        '        "semantic_pipeline": "normalize-before-verification",\n',
    )
    replace_once(
        checker,
        '            "verifyPotentialFlowAccessOperation",\n'
        '            "normalizePotentialFlowAccess",\n',
        '            "verifyPotentialFlowAccessOperation",\n'
        '            "createNormalizePotentialFlowAccessPass",\n'
        '            "normalizePotentialFlowAccess",\n',
    )
    replace_once(
        checker,
        '            "verifyPotentialFlowAccessModel",\n'
        '            \'return "nodal-normalize-potential-flow-access"\',\n',
        '            "verifyPotentialFlowAccessModel",\n'
        '            "probeProvenanceMatches",\n'
        '            \'return "nodal-normalize-potential-flow-access"\',\n',
    )
    replace_once(
        checker,
        '''        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
''',
        '''        "core/compiler/lib/Transforms/Passes.cpp": (
            "createNormalizePotentialFlowAccessPass",
            '"nodal.terminal_access"',
            '"nodal.port_flow_access"',
            '"nodal.probe"',
        ),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
''',
    )
    replace_once(
        checker,
        '            "potential-flow-access-reference",\n',
        '            "potential-flow-access-reference",\n'
        '            "builtin.module(nodal-gate-release)",\n',
    )

    Path(__file__).unlink()


if __name__ == "__main__":
    main()
