#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = path.read_text(encoding="utf-8")

    replacements = (
        (
            "    if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(&operation)) {\n",
            "    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {\n",
            "variable collection wrapper",
        ),
        (
            "  if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(operation)) {\n",
            "  if (llvm::isa<nodal::AnalogVariableOp>(operation)) {\n",
            "declaration dataflow wrapper",
        ),
        (
            "  if (auto read = llvm::dyn_cast<nodal::AnalogVariableReadOp>(operation)) {\n",
            "  if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {\n",
            "read dataflow wrapper",
        ),
        (
            "  if (auto assignment = llvm::dyn_cast<nodal::AnalogAssignOp>(operation)) {\n",
            "  if (llvm::isa<nodal::AnalogAssignOp>(operation)) {\n",
            "assignment dataflow wrapper",
        ),
        (
            "    if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(&operation))\n"
            "      locals.push_back(textAttr(&operation, \"identity\").str());\n",
            "    if (llvm::isa<nodal::AnalogVariableOp>(operation))\n"
            "      locals.push_back(textAttr(&operation, \"identity\").str());\n",
            "local declaration wrapper",
        ),
    )
    for old, new, label in replacements:
        if old in text:
            text = text.replace(old, new, 1)
        elif new not in text:
            raise SystemExit(f"{label}: neither source nor repaired form was found")

    old_case = '''  nodal::AnalogCaseArmOp defaultArm;

  if (staticPresent.getValue()) {
    nodal::AnalogCaseArmOp selected;
    for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
      auto arm = llvm::cast<nodal::AnalogCaseArmOp>(&operation);
      auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
      if (isDefault.getValue()) {
        defaultArm = arm;
        continue;
      }
      auto labels = operation.getAttrOfType<ArrayAttr>("labels");
      for (Attribute attribute : labels) {
        if (llvm::cast<StringAttr>(attribute).getValue() == staticValue) {
          selected = arm;
          break;
        }
      }
      if (selected)
        break;
    }
    nodal::AnalogCaseArmOp reachable = selected ? selected : defaultArm;
    if (!reachable) {
      result.normal = input;
      return result;
    }
    auto branch = analyzeStructuredDataflowBlock(
        reachable.getOperation()->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
'''
    new_case = '''  Operation *defaultArm = nullptr;

  if (staticPresent.getValue()) {
    Operation *selected = nullptr;
    for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
      auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
      if (isDefault.getValue()) {
        defaultArm = &operation;
        continue;
      }
      auto labels = operation.getAttrOfType<ArrayAttr>("labels");
      for (Attribute attribute : labels) {
        if (llvm::cast<StringAttr>(attribute).getValue() == staticValue) {
          selected = &operation;
          break;
        }
      }
      if (selected)
        break;
    }
    Operation *reachable = selected ? selected : defaultArm;
    if (!reachable) {
      result.normal = input;
      return result;
    }
    auto branch = analyzeStructuredDataflowBlock(
        reachable->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
'''
    if old_case in text:
        text = text.replace(old_case, new_case, 1)
    elif "Operation *defaultArm = nullptr;" not in text:
        raise SystemExit("static case dataflow selection block was not found")

    path.write_text(text, encoding="utf-8")
    print("Increment 34 native dataflow portability repairs applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
