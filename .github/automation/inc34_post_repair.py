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

    workflow = root / ".github/workflows/increment-34-analog-control-flow.yml"
    text = workflow.read_text(encoding="utf-8")
    old = '''          "${RUNNER_TEMP}/nodal-native-toolchain/bin/nodalc" \\
            --mlir-print-op-generic \\
            --mlir-print-debuginfo \\
            core/compiler/test/IR/analog-control-flow.mlir \\
            > "${RUNNER_TEMP}/increment34-structured.mlir"
'''
    new = '''          nodalc=$(find out -type f -name nodalc -perm -u+x | head -n 1)
          test -n "${nodalc}"
          "${nodalc}" \\
            --mlir-print-op-generic \\
            --mlir-print-debuginfo \\
            core/compiler/test/IR/analog-control-flow.mlir \\
            > "${RUNNER_TEMP}/increment34-structured.mlir"
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif 'nodalc=$(find out -type f -name nodalc' not in text:
        raise SystemExit("dedicated workflow nodalc invocation was not found")
    workflow.write_text(text, encoding="utf-8")

    verifier = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = verifier.read_text(encoding="utf-8")
    if "conditional arm owner does not match its parent" not in text:
        old = '''    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
'''
        new = '''    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "conditional arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
'''
        text = replace_once(text, old, new, "conditional arm owner check")
    if "case arm owner does not match its parent" not in text:
        old = '''    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
'''
        new = '''    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "case arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
'''
        text = replace_once(text, old, new, "case arm owner check")
    verifier.write_text(text, encoding="utf-8")

    print("Increment 34 post-materialization repairs applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
