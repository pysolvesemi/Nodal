#!/usr/bin/env python3
"""Materialize Increment 21 with compatibility for the pinned MLIR/CIRCT API."""

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
        [sys.executable, str(Path(__file__).with_name("materialize_increment21.py")), str(root)],
        check=True,
    )

    path = root / "core/compiler/lib/Transforms/NodalVerification.cpp"
    text = path.read_text(encoding="utf-8")
    text = add_after(text, '#include "llvm/ADT/DenseMap.h"\n', '#include "llvm/ADT/STLExtras.h"\n')
    text = add_after(text, '#include "llvm/ADT/StringSet.h"\n', '#include "llvm/ADT/Twine.h"\n')
    text = text.replace(
        "operation->getParentOp() != module)",
        "operation->getParentOp() != module.getOperation())",
    )
    text = text.replace("parseStage(stage);", "parseStage(stage.getValue());")
    text = text.replace(
        "Twine(\"unknown verification stage '\") + stage + \"'\"",
        "Twine(\"unknown verification stage '\") + stage.getValue() + \"'\"",
    )
    text = text.replace(
        "runStage(getOperation(), *selected, targetProfile)",
        "runStage(getOperation(), *selected, targetProfile.getValue())",
    )
    text = text.replace(
        "runAllStages(getOperation(), targetProfile)",
        "runAllStages(getOperation(), targetProfile.getValue())",
    )
    text = text.replace(
        "runAllStages(*candidate, targetProfile)",
        "runAllStages(*candidate, targetProfile.getValue())",
    )
    text = text.replace(
        "builder.getStringAttr(targetProfile)",
        "builder.getStringAttr(targetProfile.getValue())",
    )
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
