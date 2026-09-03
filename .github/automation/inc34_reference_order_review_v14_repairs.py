#!/usr/bin/env python3
"""Apply follow-up consistency repairs after the Increment 34 v14 patcher."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"missing repair anchor: {label}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    runtime = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
    text = runtime.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "    val declarations = mutable.HashSet.empty[String]\n",
        "    val declarations = mutable.HashSet.from(initiallyInitialized)\n",
        "initial declaration universe",
    )
    runtime.write_text(text, encoding="utf-8")

    native = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = native.read_text(encoding="utf-8")
    old = '''  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' is outside its lexical declaration scope");
    return failure();
  }
  if (requireInitialized && initialized.find(identity.str()) == initialized.end()) {
'''
    new = '''  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' is outside its lexical declaration scope");
    return failure();
  }
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation)) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured variable '") + identity + "' must be declared before use");
    return failure();
  }
  if (requireInitialized && initialized.find(identity.str()) == initialized.end()) {
'''
    text = replace_once(text, old, new, "SSA variable declaration order")
    native.write_text(text, encoding="utf-8")

    print("Increment 34 v14 consistency repairs applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
