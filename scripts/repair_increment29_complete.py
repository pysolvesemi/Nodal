#!/usr/bin/env python3
"""Complete all Increment 29 review repairs and regression coverage."""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{relative}: expected one anchor, found {count}: {old[:160]!r}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# The first review-repair script intentionally targets the rendering-fixture
# contract, but its anchor also appears in the positive-fixture contract.
# Reformat the first occurrence without changing semantics so the intended
# anchor is unique, then run the comprehensive first-stage repair.
checker = ROOT / "scripts/check_increment29.py"
checker_text = checker.read_text(encoding="utf-8")
ambiguous = "        'constraint_kind = \"range\"', 'constraint_kind = \"exclude\"',\n"
if checker_text.count(ambiguous) == 2:
    checker_text = checker_text.replace(
        ambiguous,
        "        'constraint_kind = \"range\"',\n"
        "        'constraint_kind = \"exclude\"',\n",
        1,
    )
elif checker_text.count(ambiguous) != 1:
    raise RuntimeError("unexpected Increment 29 checker constraint anchors")
checker.write_text(checker_text, encoding="utf-8")

runpy.run_path(str(ROOT / "scripts/repair_increment29_review.py"), run_name="__main__")

# ---------------------------------------------------------------------------
# Fixed parameters are compile-time constants and cannot be overridden.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''        if (!parameter)
          continue;
        auto type = parameter->getAttrOfType<TypeAttr>("type");
''',
    '''        if (!parameter)
          continue;
        if (textAttr(parameter, "variability") == "fixed")
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden");
        auto type = parameter->getAttrOfType<TypeAttr>("type");
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''      if (!parameter)
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: override target does not resolve");
      llvm::DenseSet<Operation *> stack;
''',
    '''      if (!parameter)
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: override target does not resolve");
      if (textAttr(parameter, "variability") == "fixed")
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden");
      llvm::DenseSet<Operation *> stack;
''',
)

# ---------------------------------------------------------------------------
# Backend: dependency-first declaration ordering and fixed localparams.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '#include "llvm/ADT/DenseMap.h"\n',
    '#include "llvm/ADT/DenseMap.h"\n#include "llvm/ADT/DenseSet.h"\n',
)

ordering_helper = r'''LogicalResult orderParametersByDependency(
    Operation *definition, llvm::SmallVectorImpl<Operation *> &parameters) {
  llvm::sort(parameters,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });

  llvm::StringMap<Operation *> parametersByName;
  for (Operation *parameter : parameters) {
    llvm::StringRef name = symbolName(parameter);
    if (name.empty() || parametersByName.count(name) != 0)
      return failure();
    parametersByName[name] = parameter;
  }

  llvm::DenseMap<Operation *, llvm::SmallVector<Operation *, 4>> dependencies;
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();

  for (Operation *parameter : parameters) {
    Operation *parameterValue = nullptr;
    for (Operation &operation : region.front()) {
      if (operation.getName().getStringRef() != "nodal.parameter_value")
        continue;
      auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
      if (!reference || reference.getValue() != symbolName(parameter))
        continue;
      if (parameterValue)
        return failure();
      parameterValue = &operation;
    }
    if (!parameterValue)
      continue;

    llvm::SmallVector<Value, 8> worklist;
    worklist.push_back(parameterValue->getOperand(0));
    llvm::DenseSet<Operation *> visitedExpressions;
    while (!worklist.empty()) {
      Value value = worklist.pop_back_val();
      Operation *expression = value.getDefiningOp();
      if (!expression || !visitedExpressions.insert(expression).second)
        continue;
      if (expression->getName().getStringRef() == "nodal.const_parameter_ref") {
        auto reference = expression->getAttrOfType<FlatSymbolRefAttr>("parameter");
        if (!reference)
          return failure();
        auto dependency = parametersByName.find(reference.getValue());
        if (dependency == parametersByName.end())
          return failure();
        dependencies[parameter].push_back(dependency->second);
        continue;
      }
      for (Value operand : expression->getOperands())
        worklist.push_back(operand);
    }
  }

  for (auto &entry : dependencies) {
    auto &values = entry.second;
    llvm::sort(values,
               [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
    values.erase(std::unique(values.begin(), values.end()), values.end());
  }

  llvm::DenseSet<Operation *> emitted;
  llvm::SmallVector<Operation *, 8> ordered;
  while (ordered.size() != parameters.size()) {
    bool progressed = false;
    for (Operation *parameter : parameters) {
      if (emitted.count(parameter) != 0)
        continue;
      auto dependency = dependencies.find(parameter);
      const bool ready =
          dependency == dependencies.end() ||
          llvm::all_of(dependency->second, [&](Operation *required) {
            return emitted.count(required) != 0;
          });
      if (!ready)
        continue;
      emitted.insert(parameter);
      ordered.push_back(parameter);
      progressed = true;
    }
    if (!progressed)
      return failure();
  }

  parameters.clear();
  parameters.append(ordered.begin(), ordered.end());
  return success();
}

'''

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
''',
    ordering_helper
    + '''LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''  llvm::sort(parameters,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
''',
    '''  if (failed(orderParametersByDependency(definition, parameters)))
    return failure();
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''    output << "  parameter " << nativeType << " " << symbolName(parameter) << " = " << *initializer;
''',
    '''    auto variability = parameter->getAttrOfType<StringAttr>("variability");
    llvm::StringRef declarationKeyword =
        variability && variability.getValue() == "fixed" ? "localparam" : "parameter";
    output << "  " << declarationKeyword << " " << nativeType << " "
           << symbolName(parameter) << " = " << *initializer;
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''    if (!code.consume_front("parameter real ") && !code.consume_front("parameter integer "))
      return false;
''',
    '''    if (!code.consume_front("parameter real ") &&
        !code.consume_front("parameter integer ") &&
        !code.consume_front("localparam real ") &&
        !code.consume_front("localparam integer "))
      return false;
''',
)

# ---------------------------------------------------------------------------
# Native tests: both override forms, localparam emission, and topological order.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/test/Unit/ParameterModelTest.cpp",
    ''')mlir";

} // namespace

int main() {
''',
    ''')mlir";

constexpr llvm::StringLiteral kFixedBinding = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "CONST", type = i64, variability = "fixed"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {CONST = 5 : i64}, sym_name = "child"}> : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kFixedExplicitOverride = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "CONST", type = i64, variability = "fixed"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "child"}> : () -> ()
    %five = "nodal.const_literal"() <{metadata = {}, spelling = "5", value = 5 : i64}> : () -> i64
    "nodal.parameter_override"(%five) <{instance = @child, metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

} // namespace

int main() {
''',
)

replace_once(
    "core/compiler/test/Unit/ParameterModelTest.cpp",
    '''  auto cycle = mlir::parseSourceString<mlir::ModuleOp>(kCycle, &context);
  if (!cycle || mlir::succeeded(nodal::verifyParameterModel(*cycle)))
    return fail("cyclic constant expression was accepted");

  return 0;
''',
    '''  auto cycle = mlir::parseSourceString<mlir::ModuleOp>(kCycle, &context);
  if (!cycle || mlir::succeeded(nodal::verifyParameterModel(*cycle)))
    return fail("cyclic constant expression was accepted");

  auto fixedBinding =
      mlir::parseSourceString<mlir::ModuleOp>(kFixedBinding, &context);
  if (!fixedBinding || mlir::succeeded(nodal::verifyParameterModel(*fixedBinding)))
    return fail("fixed parameter dictionary binding was accepted");

  auto fixedExplicitOverride =
      mlir::parseSourceString<mlir::ModuleOp>(kFixedExplicitOverride, &context);
  if (!fixedExplicitOverride ||
      mlir::succeeded(nodal::verifyParameterModel(*fixedExplicitOverride)))
    return fail("fixed parameter explicit override was accepted");

  return 0;
''',
)

replace_once(
    "core/compiler/test/IR/parameter-rendering.mlir",
    '''    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i128, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
''',
    '''    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i128, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2 : i64, metadata = {}, parameter_kind = "integer", sym_name = "A_DEP", type = i64, variability = "symbolic"}> : () -> ()
    %z_ref = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @Z_BASE}> : () -> i64
    "nodal.parameter_value"(%z_ref) <{metadata = {}, parameter = @A_DEP}> : (i64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2 : i64, metadata = {}, parameter_kind = "integer", sym_name = "Z_BASE", type = i64, variability = "symbolic"}> : () -> ()
    %z_base = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    "nodal.parameter_value"(%z_base) <{metadata = {}, parameter = @Z_BASE}> : (i64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
''',
)

replace_once(
    ".github/workflows/increment-29-parameters-units.yml",
    '''          grep -F 'parameter integer WIDE = 340282366920938463463374607431768211455;' /tmp/parameter-rendering.va
          grep -F 'parameter integer ENABLE = 1;' /tmp/parameter-rendering.va
''',
    '''          grep -F 'localparam integer WIDE = 340282366920938463463374607431768211455;' /tmp/parameter-rendering.va
          grep -F 'parameter integer Z_BASE = 2;' /tmp/parameter-rendering.va
          grep -F 'parameter integer A_DEP = Z_BASE;' /tmp/parameter-rendering.va
          z_base_line=$(grep -nF 'parameter integer Z_BASE = 2;' /tmp/parameter-rendering.va | cut -d: -f1)
          a_dep_line=$(grep -nF 'parameter integer A_DEP = Z_BASE;' /tmp/parameter-rendering.va | cut -d: -f1)
          test "${z_base_line}" -lt "${a_dep_line}"
          grep -F 'parameter integer ENABLE = 1;' /tmp/parameter-rendering.va
''',
)

# ---------------------------------------------------------------------------
# Repository contracts and mutation tests for the final review repairs.
# ---------------------------------------------------------------------------

replace_once(
    "scripts/check_increment29.py",
    '''        "exactInteger", "adoptParameterUnit",
        "renderParameterConstantExpression(Value value,",
''',
    '''        "exactInteger", "adoptParameterUnit",
        "renderParameterConstantExpression(Value value,",
        "fixed parameter cannot be overridden",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        "validCanonicalCommentText", "renderIntegerAttribute",
''',
    '''        "validCanonicalCommentText", "renderIntegerAttribute",
        "orderParametersByDependency", "declarationKeyword", "localparam real",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        "bare parameter magnitude did not inherit target unit",
''',
    '''        "bare parameter magnitude did not inherit target unit",
        "fixed parameter dictionary binding was accepted",
        "fixed parameter explicit override was accepted",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        'sym_name = "kOhmPretty"', 'symbol = "kΩ/V"',
''',
    '''        'sym_name = "kOhmPretty"', 'symbol = "kΩ/V"',
        'sym_name = "A_DEP"', 'sym_name = "Z_BASE"', 'sym_name = "WIDE"',
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        "permissions:\n  contents: read",
''',
    '''        "permissions:\n  contents: read", "localparam integer WIDE",
        "z_base_line", "a_dep_line",
''',
)

replace_once(
    "tests/compiler/test_increment29.py",
    '''    def test_rejects_missing_dynamic_value_guard(self) -> None:
''',
    '''    def test_rejects_missing_fixed_override_guard(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "fixed parameter cannot be overridden",
                "fixed parameter may be overridden",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_dependency_ordering(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "orderParametersByDependency", "alphabeticalParameterOrder"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-008", self.codes(root))

    def test_rejects_missing_fixed_localparam_rendering(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "declarationKeyword", "parameterKeyword"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-008", self.codes(root))

    def test_rejects_missing_dynamic_value_guard(self) -> None:
''',
)

print("Increment 29 complete review repair materialized")
