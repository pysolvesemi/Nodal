#!/usr/bin/env python3
"""Materialize Increment 22 with stable-literal fallback patching."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        subprocess.run(
            [sys.executable, str(Path(__file__).with_name("materialize_increment22_v7.py")), str(root)],
            check=True,
        )
    except subprocess.CalledProcessError:
        conversion_path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
        checker_path = root / "scripts/check_increment22.py"
        if not conversion_path.is_file() or not checker_path.is_file():
            raise
        text = conversion_path.read_text(encoding="utf-8")
        if "NODAL-CIRCT-022-006" not in text:
            marker = '"an integer value attribute");'
            marker_index = text.find(marker)
            if marker_index < 0:
                raise
            return_index = text.find("return success();", marker_index + len(marker))
            if return_index < 0:
                raise RuntimeError("constant preflight success return is missing")
            guard = '''if (!operation->getResult(0).use_empty())
    return operation.emitOpError(
        "[NODAL-CIRCT-022-006] Increment 22 converts only standalone "
        "constants; users remain deferred until their operations have "
        "exact CIRCT patterns");
  '''
            text = text[:return_index] + guard + text[return_index:]
        text = text.replace(
            "IntegerType::Unsigned)",
            "IntegerType::SignednessSemantics::Unsigned)",
        ).replace(
            "IntegerType::Signed)",
            "IntegerType::SignednessSemantics::Signed)",
        ).replace(
            "llvm::dyn_cast_or_null<IntegerType>(converted)",
            "llvm::dyn_cast<IntegerType>(converted)",
        ).replace(
            "runPreflight(getOperation(), rejectDeferred)",
            "runPreflight(getOperation(), rejectDeferred.getValue())",
        )
        body_pattern = re.compile(
            r'module->setAttrs\(\(\*candidate\)->getAttrs\(\)\);\s*'
            r'module\.getBodyRegion\(\)\.takeBody\(candidate->getRegion\(0\)\);'
        )
        text = body_pattern.sub(
            '''ModuleOp candidateModule = *candidate;
  module->setAttrs(candidateModule->getAttrs());
  module.getBodyRegion().takeBody(candidateModule.getBodyRegion());''',
            text,
            count=1,
        )
        conversion_path.write_text(text, encoding="utf-8")

        checker = checker_path.read_text(encoding="utf-8")
        if '"NODAL-CIRCT-022-006",' not in checker:
            pattern = re.compile(r'(?P<indent>\s*)"NODAL-CIRCT-022-002",\n')
            match = pattern.search(checker)
            if not match:
                raise RuntimeError("checker diagnostic inventory anchor is missing")
            checker = (
                checker[: match.start()]
                + match.group(0)
                + f'{match.group("indent")}"NODAL-CIRCT-022-006",\n'
                + checker[match.end() :]
            )
        checker_path.write_text(checker, encoding="utf-8")

    # Require the final fail-closed contract regardless of which path succeeded.
    conversion = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
    if "NODAL-CIRCT-022-006" not in conversion.read_text(encoding="utf-8"):
        raise RuntimeError("final Increment 22 source lacks standalone-constant guard")


if __name__ == "__main__":
    main()
