#!/usr/bin/env python3
"""Materialize Increment 22 with pinned MLIR/CIRCT conversion API compatibility."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def add_after(text: str, anchor: str, addition: str) -> str:
    if addition in text:
        return text
    if anchor not in text:
        raise RuntimeError(f"include anchor is missing: {anchor!r}")
    return text.replace(anchor, anchor + addition, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("materialize_increment22_v2.py")), str(root)],
        check=True,
    )

    path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
    text = path.read_text(encoding="utf-8")
    text = add_after(text, '#include "mlir/Pass/PassManager.h"\n', '#include "mlir/Pass/PassRegistry.h"\n')
    text = add_after(text, '#include <string>\n', '#include <utility>\n')
    text = text.replace(
        "IntegerType::Unsigned)",
        "IntegerType::SignednessSemantics::Unsigned)",
    )
    text = text.replace(
        "IntegerType::Signed)",
        "IntegerType::SignednessSemantics::Signed)",
    )
    text = text.replace(
        "llvm::dyn_cast_or_null<IntegerType>(converted)",
        "llvm::dyn_cast<IntegerType>(converted)",
    )
    text = text.replace(
        "if (failed(runPreflight(getOperation(), rejectDeferred)))",
        "if (failed(runPreflight(getOperation(), rejectDeferred.getValue())))",
    )
    old_commit = """            module->setAttrs((*candidate)->getAttrs());
            module.getBodyRegion().takeBody(candidate->getRegion(0));"""
    new_commit = """            ModuleOp candidateModule = *candidate;
            module->setAttrs(candidateModule->getAttrs());
            module.getBodyRegion().takeBody(candidateModule.getBodyRegion());"""
    if old_commit in text:
        text = text.replace(old_commit, new_commit, 1)
    elif new_commit not in text:
        raise RuntimeError("transactional body-transfer anchor is missing")
    path.write_text(text, encoding="utf-8")

    cmake = root / "core/compiler/lib/Conversion/CMakeLists.txt"
    cmake_text = cmake.read_text(encoding="utf-8")
    if "MLIRTransformUtils" not in cmake_text:
        cmake_text = cmake_text.replace(
            "    MLIRTransforms\n",
            "    MLIRTransforms\n    MLIRTransformUtils\n",
            1,
        )
    cmake.write_text(cmake_text, encoding="utf-8")


if __name__ == "__main__":
    main()
