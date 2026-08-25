#!/usr/bin/env python3
"""Apply compile-hygiene rewrites to the Increment 21 native verifier."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"{label}: expected source fragment is absent")
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected one source fragment, found {text.count(old)}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    path = args.root.resolve() / "core/compiler/lib/Transforms/Verification.cpp"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        """  for (const auto &[symbol, operation] : modules)
    if (failed(visit(symbol)))
      return failure();""",
        """  for (const auto &entry : modules)
    if (failed(visit(entry.first)))
      return failure();""",
        "unused hierarchy binding",
    )
    text = replace_once(
        text,
        """  for (const auto &[node, edges] : graph)
    if (cycle(node))
      return reject(module, \"NODAL-VERIFY-CYCLE-001\",
                    \"combinational origin graph contains a cycle through '\" + node + \"'\");""",
        """  for (const auto &entry : graph)
    if (cycle(entry.first))
      return reject(module, \"NODAL-VERIFY-CYCLE-001\",
                    \"combinational origin graph contains a cycle through '\" + entry.first +
                        \"'\");""",
        "unused connectivity binding",
    )
    text = replace_once(
        text,
        """  std::map<std::string, Operation *> modules;
  std::map<std::string, std::set<std::string>> parameters;
  for (Operation *semanticModule : semanticModules(module)) {
    std::string symbol = stringAttribute(semanticModule, \"sym_name\").str();
    modules[symbol] = semanticModule;
    for (Operation *child : directChildren(semanticModule))""",
        """  std::map<std::string, std::set<std::string>> parameters;
  for (Operation *semanticModule : semanticModules(module)) {
    std::string symbol = stringAttribute(semanticModule, \"sym_name\").str();
    for (Operation *child : directChildren(semanticModule))""",
        "unused parameter module map",
    )
    text = replace_once(
        text,
        """    auto stateType = operation->getAttrOfType<TypeAttr>(\"state_type\");
    auto enumeration = stateType ? dyn_cast<nodal::EnumType>(stateType.getValue())
                                 : nodal::EnumType();
    if (!enumeration || !enumerations.count(enumeration.getSymbol().str())) {""",
        """    auto stateType = operation->getAttrOfType<TypeAttr>(\"state_type\");
    if (!stateType) {
      (void)reject(operation, \"NODAL-VERIFY-FSM-001\",
                   \"FSM lacks its semantic state type\");
      return WalkResult::interrupt();
    }
    auto enumeration = dyn_cast<nodal::EnumType>(stateType.getValue());
    if (!enumeration || !enumerations.count(enumeration.getSymbol().str())) {""",
        "enum type construction",
    )
    text = text.replace('if (stage == "all")', 'if (stage.getValue() == "all")')
    text = text.replace(
        "verifyNodalPipeline(getOperation(), target)",
        "verifyNodalPipeline(getOperation(), target.getValue())",
    )
    text = text.replace(
        "symbolizeVerificationStage(stage)",
        "symbolizeVerificationStage(stage.getValue())",
    )
    text = text.replace(
        "verifyNodalStage(getOperation(), *selected, target)",
        "verifyNodalStage(getOperation(), *selected, target.getValue())",
    )
    text = text.replace(
        "verifyNodalPipeline(module, target)",
        "verifyNodalPipeline(module, target.getValue())",
    )
    text = text.replace(
        "effectiveTarget(module, target)",
        "effectiveTarget(module, target.getValue())",
    )
    text = text.replace(
        "candidate.print(stream, OpPrintingFlags().enableDebugInfo(false));",
        "candidate.print(stream);",
    )

    path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
