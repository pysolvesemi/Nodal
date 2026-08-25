#!/usr/bin/env python3
"""Materialize Increment 21 with indentation-independent native API patches."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


OWNERSHIP = """# Increment 21 native staged semantic verification pipeline.
/core/compiler/include/nodal/Transforms/ @pysolvesemi
/core/compiler/lib/Transforms/ @pysolvesemi
/scripts/check_increment21.py @pysolvesemi
/tests/compiler/test_increment21.py @pysolvesemi
/tests/compiler/fixtures/increment21/ @pysolvesemi
/.github/workflows/increment-21-native-verification-pipeline.yml @pysolvesemi
/docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md @pysolvesemi
/docs/implementation/increment21-native-verification-pipeline.md @pysolvesemi
"""


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
        [sys.executable, str(Path(__file__).with_name("materialize_increment21_v2.py")), str(root)],
        check=True,
    )

    path = root / "core/compiler/lib/Transforms/NodalVerification.cpp"
    text = path.read_text(encoding="utf-8")
    text = add_after(
        text,
        '#include "mlir/Pass/PassManager.h"\n',
        '#include "mlir/Pass/PassRegistry.h"\n#include "mlir/Support/LogicalResult.h"\n',
    )
    text = add_after(
        text,
        '#include "llvm/Support/CommandLine.h"\n',
        '#include "llvm/Support/ErrorHandling.h"\n',
    )
    text = text.replace(
        "operation->getParentOp() != module)",
        "operation->getParentOp() != module.getOperation())",
    ).replace(
        "parseStage(stage);",
        "parseStage(stage.getValue());",
    ).replace(
        "runStage(getOperation(), *selected, targetProfile)",
        "runStage(getOperation(), *selected, targetProfile.getValue())",
    ).replace(
        "runAllStages(getOperation(), targetProfile)",
        "runAllStages(getOperation(), targetProfile.getValue())",
    ).replace(
        "runAllStages(*candidate, targetProfile)",
        "runAllStages(*candidate, targetProfile.getValue())",
    ).replace(
        "builder.getStringAttr(targetProfile)",
        "builder.getStringAttr(targetProfile.getValue())",
    )
    text = re.sub(
        r'Twine\("unknown verification stage \'"\) \+ stage \+ "\'"',
        r'Twine("unknown verification stage \'") + stage.getValue() + "\'"',
        text,
        count=1,
    )
    text = text.replace(
        "operation->emitError() << '[' << code << \"] \" << message;",
        "operation->emitError() << '[' << code << \"] \" << message.str();",
    )
    body_pattern = re.compile(
        r'module->setAttrs\(\(\*candidate\)->getAttrs\(\)\);\s*'
        r'module\.getBodyRegion\(\)\.takeBody\(candidate->getRegion\(0\)\);'
    )
    body_replacement = '''ModuleOp candidateModule = *candidate;
  module->setAttrs(candidateModule->getAttrs());
  module.getBodyRegion().takeBody(candidateModule.getBodyRegion());'''
    text, count = body_pattern.subn(body_replacement, text, count=1)
    if count == 0 and "candidateModule.getBodyRegion()" not in text:
        raise RuntimeError("transactional body-transfer anchor is missing")
    path.write_text(text, encoding="utf-8")

    codeowners = root / ".github/CODEOWNERS"
    owners = codeowners.read_text(encoding="utf-8")
    marker = "# Increment 21 native staged semantic verification pipeline."
    if marker not in owners:
        codeowners.write_text(owners.rstrip() + "\n\n" + OWNERSHIP, encoding="utf-8")


if __name__ == "__main__":
    main()
