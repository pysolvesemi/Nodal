#!/usr/bin/env python3
"""Apply Increment 31 named-branch isolation review fixes."""

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
    implementation = ROOT / "core/compiler/lib/Dialect/Nodal/PotentialFlowAccess.cpp"
    replace_once(
        implementation,
        '''AccessGroup *findExplicitGroup(llvm::MutableArrayRef<AccessGroup> groups, Value positive,
                               Value negative) {
  for (AccessGroup &group : groups) {
    if (!group.branchOperation || group.branchOperation->getNumOperands() != 2)
      continue;
    if (matchingTerminalPair(group.branchOperation->getOperand(0),
                             group.branchOperation->getOperand(1), positive, negative))
      return &group;
  }
  return nullptr;
}
''',
        '''bool isImplicitBranchOperation(Operation *operation) {
  if (!isNamed(operation, "nodal.branch"))
    return false;
  llvm::StringRef declarationKind = textAttr(operation, "declaration_kind");
  llvm::StringRef name = textAttr(operation, "name");
  return name.empty() && (declarationKind.empty() || declarationKind == "implicit");
}

AccessGroup *findImplicitBranchGroup(llvm::MutableArrayRef<AccessGroup> groups, Value positive,
                                     Value negative) {
  for (AccessGroup &group : groups) {
    if (!isImplicitBranchOperation(group.branchOperation) ||
        group.branchOperation->getNumOperands() != 2)
      continue;
    if (matchingTerminalPair(group.branchOperation->getOperand(0),
                             group.branchOperation->getOperand(1), positive, negative))
      return &group;
  }
  return nullptr;
}
''',
    )
    replace_once(
        implementation,
        '''    if (arity == 2)
      group = findExplicitGroup(groups, operation->getOperand(0), operation->getOperand(1));
''',
        '''    if (arity == 2)
      group = findImplicitBranchGroup(groups, operation->getOperand(0),
                                      operation->getOperand(1));
''',
    )

    fixture = ROOT / "core/compiler/test/IR/potential-flow-access.mlir"
    replace_once(
        fixture,
        '''    %probe_p = "nodal.node"() <{metadata = {}, name = "probe_p"}> : () -> !nodal.terminal<"customElectrical">
    %probe_n = "nodal.node"() <{metadata = {}, name = "probe_n"}> : () -> !nodal.terminal<"customElectrical">
''',
        '''    %probe_p = "nodal.node"() <{metadata = {}, name = "probe_p"}> : () -> !nodal.terminal<"customElectrical">
    %probe_n = "nodal.node"() <{metadata = {}, name = "probe_n"}> : () -> !nodal.terminal<"customElectrical">
    %named_p = "nodal.node"() <{metadata = {}, name = "named_p"}> : () -> !nodal.terminal<"customElectrical">
    %named_n = "nodal.node"() <{metadata = {}, name = "named_n"}> : () -> !nodal.terminal<"customElectrical">
''',
    )
    replace_once(
        fixture,
        '''    %probe_branch = "nodal.branch"(%probe_p, %probe_n) <{metadata = {identity = "probe_p_probe_n"}}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.branch<"customElectrical">
''',
        '''    %probe_branch = "nodal.branch"(%probe_p, %probe_n) <{metadata = {identity = "probe_p_probe_n"}}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.branch<"customElectrical">
    %named_parallel = "nodal.branch"(%named_p, %named_n) <{declaration_kind = "named", metadata = {}, name = "named_parallel", source_path = "PotentialFlowAccess.named_parallel"}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.branch<"customElectrical">
''',
    )
    replace_once(
        fixture,
        '''      %generic_pair = "nodal.terminal_access"(%p, %n) <{function = "potential", kind = "potential", metadata = {}, source_path = "PotentialFlowAccess.generic_pair"}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "voltage">
''',
        '''      %generic_pair = "nodal.terminal_access"(%p, %n) <{function = "potential", kind = "potential", metadata = {}, source_path = "PotentialFlowAccess.generic_pair"}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "voltage">
      %named_pair_probe = "nodal.terminal_access"(%named_p, %named_n) <{function = "potential", kind = "potential", metadata = {}, source_path = "PotentialFlowAccess.named_pair_probe"}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "voltage">
''',
    )

    cmake = ROOT / "core/compiler/test/CMakeLists.txt"
    replace_once(
        cmake,
        '''set_tests_properties(
  nodal.native.potential-flow-access-normalize
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]probe"
)
''',
        '''set_tests_properties(
  nodal.native.potential-flow-access-normalize
  PROPERTIES
    PASS_REGULAR_EXPRESSION "form = .two-terminal."
)
''',
    )

    surface_path = ROOT / "tests/compiler/fixtures/increment31/access-surface.json"
    surface = json.loads(surface_path.read_text(encoding="utf-8"))
    surface["normalization"]["namedBranchIsolation"] = True
    surface_path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")

    manifest_path = ROOT / "tests/compiler/fixtures/increment31/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["implementation"]["named_branch_isolation"] = (
        "implicit-only-endpoint-coalescing"
    )
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    note = ROOT / "docs/implementation/increment31-potential-flow-access.md"
    replace_once(
        note,
        '''- `nodal.terminal_access` preserves one-terminal and oriented two-terminal
  source forms. One-terminal access deterministically records the canonical
  discipline-global reference.
''',
        '''- `nodal.terminal_access` preserves one-terminal and oriented two-terminal
  source forms. One-terminal access deterministically records the canonical
  discipline-global reference.
- Named branches remain distinct from endpoint-only two-terminal access during
  probe grouping; only implicit branches may coalesce by endpoint pair.
''',
    )

    checker = ROOT / "scripts/check_increment31.py"
    replace_once(
        checker,
        '''            "transactional Fast, Default, and Release semantic gates",
''',
        '''            "transactional Fast, Default, and Release semantic gates",
            "Named branches remain distinct",
''',
    )
    replace_once(
        checker,
        '''        "semantic_pipeline": "normalize-before-verification",
        "positive_fixture": "core/compiler/test/IR/potential-flow-access.mlir",
''',
        '''        "semantic_pipeline": "normalize-before-verification",
        "named_branch_isolation": "implicit-only-endpoint-coalescing",
        "positive_fixture": "core/compiler/test/IR/potential-flow-access.mlir",
''',
    )
    replace_once(
        checker,
        '''    if surface.get("forms", {}).get("portFlow") != "function(<port>)":
        problems.append(Problem("NODAL-INC31-008", "surface port-flow form mismatch"))
    probes = surface.get("probes", {})
''',
        '''    if surface.get("forms", {}).get("portFlow") != "function(<port>)":
        problems.append(Problem("NODAL-INC31-008", "surface port-flow form mismatch"))
    normalization = surface.get("normalization", {})
    if normalization.get("namedBranchIsolation") is not True:
        problems.append(
            Problem(
                "NODAL-INC31-008",
                "named branches must remain isolated from endpoint-only access",
            )
        )
    probes = surface.get("probes", {})
''',
    )
    replace_once(
        checker,
        '''            "probeProvenanceMatches",
            'return "nodal-normalize-potential-flow-access"',
''',
        '''            "probeProvenanceMatches",
            "isImplicitBranchOperation",
            "findImplicitBranchGroup",
            'return "nodal-normalize-potential-flow-access"',
''',
    )
    replace_once(
        checker,
        '''            "builtin.module(nodal-gate-release)",
            "potential-flow-access-rejects-${_fixture}",
''',
        '''            "builtin.module(nodal-gate-release)",
            "form = .two-terminal.",
            "potential-flow-access-rejects-${_fixture}",
''',
    )
    replace_once(
        checker,
        '''            'function = "Through"',
        ),
''',
        '''            'function = "Through"',
            'declaration_kind = "named"',
            'name = "named_parallel"',
        ),
''',
    )

    tests = ROOT / "tests/compiler/test_increment31.py"
    replace_once(
        tests,
        '''    def test_rejects_writable_workflow(self) -> None:
''',
        '''    def test_rejects_missing_named_branch_isolation(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment31/access-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        del surface["normalization"]["namedBranchIsolation"]
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-008", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
''',
    )

    Path(__file__).unlink()


if __name__ == "__main__":
    main()
