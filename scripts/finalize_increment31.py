#!/usr/bin/env python3
"""Apply Increment 31 named-branch backend rendering fixes."""

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
    backend = ROOT / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
    replace_once(
        backend,
        '''struct ModuleRenderState {
  llvm::DenseMap<Value, std::string> terminals;
  llvm::DenseMap<Value, std::pair<std::string, std::string>> branches;
  llvm::DenseMap<Value, std::string> expressions;
  llvm::StringMap<std::string> parameters;
};

FailureOr<std::string> renderBranch(Value value, ModuleRenderState &state, llvm::StringRef access) {
  auto iterator = state.branches.find(value);
  if (iterator == state.branches.end())
    return failure();
  return (llvm::Twine(access) + "(" + iterator->second.first + ", " + iterator->second.second + ")")
      .str();
}
''',
        '''struct ModuleRenderState {
  llvm::DenseMap<Value, std::string> terminals;
  llvm::DenseMap<Value, std::pair<std::string, std::string>> branches;
  llvm::DenseMap<Value, std::string> branchNames;
  llvm::DenseMap<Value, std::string> expressions;
  llvm::StringMap<std::string> parameters;
};

FailureOr<std::string> renderBranch(Value value, ModuleRenderState &state, llvm::StringRef access) {
  auto iterator = state.branches.find(value);
  if (iterator == state.branches.end())
    return failure();
  if (auto named = state.branchNames.find(value); named != state.branchNames.end())
    return (llvm::Twine(access) + "(" + named->second + ")").str();
  return (llvm::Twine(access) + "(" + iterator->second.first + ", " + iterator->second.second + ")")
      .str();
}
''',
    )
    replace_once(
        backend,
        '''LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
                                 llvm::SmallVectorImpl<Operation *> &parameters,
                                 llvm::SmallVectorImpl<Operation *> &ports,
                                 llvm::SmallVectorImpl<Operation *> &nodes,
                                 llvm::SmallVectorImpl<Operation *> &analogs) {
''',
        '''LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
                                 llvm::SmallVectorImpl<Operation *> &parameters,
                                 llvm::SmallVectorImpl<Operation *> &ports,
                                 llvm::SmallVectorImpl<Operation *> &nodes,
                                 llvm::SmallVectorImpl<Operation *> &namedBranches,
                                 llvm::SmallVectorImpl<Operation *> &analogs) {
''',
    )
    replace_once(
        backend,
        '''      state.branches.try_emplace(operation.getResult(0),
                                 std::make_pair(positive->second, negative->second));
    } else if (name == "nodal.analog") {
''',
        '''      state.branches.try_emplace(operation.getResult(0),
                                 std::make_pair(positive->second, negative->second));
      if (auto branchName = operation.getAttrOfType<StringAttr>("name")) {
        if (!branchName.getValue().trim().empty()) {
          namedBranches.push_back(&operation);
          state.branchNames.try_emplace(operation.getResult(0), branchName.getValue().str());
        }
      }
    } else if (name == "nodal.analog") {
''',
    )
    replace_once(
        backend,
        '''  llvm::sort(nodes, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  return success();
}
''',
        '''  llvm::sort(nodes, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  llvm::sort(namedBranches, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  return success();
}
''',
    )
    replace_once(
        backend,
        '''  llvm::SmallVector<Operation *, 8> ports;
  llvm::SmallVector<Operation *, 8> nodes;
  llvm::SmallVector<Operation *, 2> analogs;
  if (failed(collectModuleState(definition, state, parameters, ports, nodes, analogs)))
''',
        '''  llvm::SmallVector<Operation *, 8> ports;
  llvm::SmallVector<Operation *, 8> nodes;
  llvm::SmallVector<Operation *, 4> namedBranches;
  llvm::SmallVector<Operation *, 2> analogs;
  if (failed(
          collectModuleState(definition, state, parameters, ports, nodes, namedBranches, analogs)))
''',
    )
    replace_once(
        backend,
        '''    output << ";\n";
  }

  for (Operation *parameter : parameters) {
''',
        '''    output << ";\n";
  }

  for (Operation *branch : namedBranches) {
    auto endpoints = state.branches.find(branch->getResult(0));
    auto name = branch->getAttrOfType<StringAttr>("name");
    if (endpoints == state.branches.end() || !name || name.getValue().trim().empty())
      return emitMappedFailure(branch, "NODAL-BACKEND-RC-004",
                               "named branch is not losslessly renderable");
    output << "  branch (" << endpoints->second.first << ", " << endpoints->second.second << ") "
           << name.getValue() << ";\n";
  }

  for (Operation *parameter : parameters) {
''',
    )
    replace_once(
        backend,
        '''  if ((!ports.empty() || !nodes.empty() || !parameters.empty()) && !analogs.empty())
''',
        '''  if ((!ports.empty() || !nodes.empty() || !namedBranches.empty() || !parameters.empty()) &&
      !analogs.empty())
''',
    )
    replace_once(
        backend,
        '''    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ") ||
         line.starts_with("electrical ")) &&
        line.ends_with(";") && validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back()))
      continue;
''',
        '''    if (line.starts_with("branch (") && line.ends_with(";")) {
      llvm::StringRef declaration = line.drop_front(sizeof("branch (") - 1).drop_back();
      size_t close = declaration.find(") ");
      if (close == llvm::StringRef::npos ||
          !validIdentifierList(declaration.take_front(close)) ||
          !validIdentifierList(declaration.drop_front(close + 2)))
        return failure();
      continue;
    }
    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ") ||
         line.starts_with("electrical ")) &&
        line.ends_with(";") && validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back()))
      continue;
''',
    )

    fixture = ROOT / "core/compiler/test/IR/potential-flow-access.mlir"
    replace_once(
        fixture,
        '''      %branch_flow = "nodal.access"(%branch) <{function = "Through", kind = "flow", metadata = {source_path = "PotentialFlowAccess.branch_flow"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "current">
''',
        '''      %branch_flow = "nodal.access"(%branch) <{function = "Through", kind = "flow", metadata = {source_path = "PotentialFlowAccess.branch_flow"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "current">
      %named_branch_potential = "nodal.access"(%named_parallel) <{function = "Across", kind = "potential", metadata = {source_path = "PotentialFlowAccess.named_branch_potential"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "voltage">
''',
    )
    replace_once(
        fixture,
        '''      "nodal.contribute"(%branch, %branch_flow) <{kind = "flow", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "current">) -> ()
''',
        '''      "nodal.contribute"(%branch, %branch_flow) <{kind = "flow", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "current">) -> ()
      "nodal.contribute"(%branch, %named_branch_potential) <{kind = "potential", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "voltage">) -> ()
''',
    )

    cmake = ROOT / "core/compiler/test/CMakeLists.txt"
    replace_once(
        cmake,
        '''    PASS_REGULAR_EXPRESSION "Across[(]p, n[)]"
''',
        '''    PASS_REGULAR_EXPRESSION "Across[(]named_parallel[)]"
''',
    )

    surface_path = ROOT / "tests/compiler/fixtures/increment31/access-surface.json"
    surface = json.loads(surface_path.read_text(encoding="utf-8"))
    surface["backend"] = {
        "namedBranchAccess": "function(branch-name)",
        "implicitBranchAccess": "function(positive, negative)",
    }
    surface_path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")

    manifest_path = ROOT / "tests/compiler/fixtures/increment31/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["implementation"]["named_branch_rendering"] = (
        "declaration-and-identity"
    )
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    note = ROOT / "docs/implementation/increment31-potential-flow-access.md"
    replace_once(
        note,
        '''- Named branches remain distinct from endpoint-only two-terminal access during
  probe grouping; only implicit branches may coalesce by endpoint pair.
''',
        '''- Named branches remain distinct from endpoint-only two-terminal access during
  probe grouping; only implicit branches may coalesce by endpoint pair.
- Named branch access emits a deterministic Verilog-A/Verilog-AMS branch
  declaration and renders `function(branch-name)`; implicit branch access keeps
  the oriented endpoint form.
''',
    )

    checker = ROOT / "scripts/check_increment31.py"
    replace_once(
        checker,
        '''            "Named branches remain distinct",
''',
        '''            "Named branches remain distinct",
            "Named branch access emits",
''',
    )
    replace_once(
        checker,
        '''        "named_branch_isolation": "implicit-only-endpoint-coalescing",
        "positive_fixture": "core/compiler/test/IR/potential-flow-access.mlir",
''',
        '''        "named_branch_isolation": "implicit-only-endpoint-coalescing",
        "named_branch_rendering": "declaration-and-identity",
        "positive_fixture": "core/compiler/test/IR/potential-flow-access.mlir",
''',
    )
    replace_once(
        checker,
        '''    probes = surface.get("probes", {})
''',
        '''    backend_surface = surface.get("backend", {})
    if backend_surface.get("namedBranchAccess") != "function(branch-name)" or \
       backend_surface.get("implicitBranchAccess") != "function(positive, negative)":
        problems.append(
            Problem("NODAL-INC31-008", "backend branch access surface mismatch")
        )
    probes = surface.get("probes", {})
''',
    )
    replace_once(
        checker,
        '''            'getAttrOfType<StringAttr>("function")',
            "normalizePotentialFlowAccess(module)",
''',
        '''            'getAttrOfType<StringAttr>("function")',
            "state.branchNames.find(value)",
            "namedBranches",
            'output << "  branch ("',
            'line.starts_with("branch (")',
            "normalizePotentialFlowAccess(module)",
''',
    )
    replace_once(
        checker,
        '''            "potential-flow-access-backend-discipline",
            "potential-flow-access-backend-generic",
''',
        '''            "potential-flow-access-backend-discipline",
            "Across[(]named_parallel[)]",
            "potential-flow-access-backend-generic",
''',
    )
    replace_once(
        checker,
        '''            'name = "named_parallel"',
        ),
''',
        '''            'name = "named_parallel"',
            '"nodal.access"(%named_parallel)',
            '%named_branch_potential',
        ),
''',
    )

    tests = ROOT / "tests/compiler/test_increment31.py"
    replace_once(
        tests,
        '''    def test_rejects_writable_workflow(self) -> None:
''',
        r'''    def test_rejects_missing_named_branch_backend_surface(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment31/access-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        del surface["backend"]
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-008", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
''',
    )

    Path(__file__).unlink()


if __name__ == "__main__":
    main()
