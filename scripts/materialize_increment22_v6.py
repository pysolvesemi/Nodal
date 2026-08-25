#!/usr/bin/env python3
"""Materialize Increment 22 using indentation-independent native API patches."""

from __future__ import annotations

import argparse
import re
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
    scripts = Path(__file__).resolve().parent

    base = scripts / "materialize_increment22.py"
    source = base.read_text(encoding="utf-8")
    old_call = '''    replace_once(
        root,
        "core/compiler/lib/CMakeLists.txt",
        "add_subdirectory(Dialect)\\nadd_subdirectory(Support)\\n",
        "add_subdirectory(Dialect)\\nadd_subdirectory(Conversion)\\nadd_subdirectory(Support)\\n",
    )
'''
    flexible_call = '''    library_path = root / "core/compiler/lib/CMakeLists.txt"
    library_text = library_path.read_text(encoding="utf-8")
    if "add_subdirectory(Conversion)" not in library_text:
        anchor = "add_subdirectory(Dialect)\\n"
        if library_text.count(anchor) != 1:
            raise RuntimeError(
                "core/compiler/lib/CMakeLists.txt: dialect anchor is not unique"
            )
        library_path.write_text(
            library_text.replace(
                anchor,
                anchor + "add_subdirectory(Conversion)\\n",
                1,
            ),
            encoding="utf-8",
        )
'''
    if old_call not in source:
        raise RuntimeError("base Increment 22 library-layout anchor is missing")
    runtime = scripts / "materialize_increment22_runtime_v6.py"
    runtime.write_text(source.replace(old_call, flexible_call, 1), encoding="utf-8")
    try:
        subprocess.run([sys.executable, str(runtime), str(root)], check=True)
    finally:
        runtime.unlink(missing_ok=True)

    from materialize_increment22_v2 import OLD_BLOCK, NEW_BLOCK
    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8")
    if NEW_BLOCK not in roadmap:
        if roadmap.count(OLD_BLOCK) == 1:
            roadmap_path.write_text(
                roadmap.replace(OLD_BLOCK, NEW_BLOCK, 1),
                encoding="utf-8",
            )
        elif "- [ ] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**" not in roadmap:
            raise RuntimeError("Increment 22 roadmap assignment is unsupported")

    conversion_path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
    text = conversion_path.read_text(encoding="utf-8")
    text = add_after(
        text,
        '#include "mlir/Pass/PassManager.h"\n',
        '#include "mlir/Pass/PassRegistry.h"\n',
    )
    text = add_after(text, '#include <string>\n', '#include <utility>\n')
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

    converter_pattern = re.compile(
        r"NodalToCirctTypeConverter\(\) \{\s*"
        r"addConversion\(\[\]\(Type type\) -> Type \{ return type; \}\);\s*"
        r"(?P<specific>addConversion\(\[\]\(nodal::BitsType.*?"
        r"addConversion\(\[\]\(nodal::SIntType.*?\}\);)\s*\}",
        re.S,
    )
    match = converter_pattern.search(text)
    if match:
        specific = match.group("specific")
        replacement = (
            "NodalToCirctTypeConverter() {\n"
            f"    {specific.strip()}\n"
            "    addConversion([](Type type) -> Type { return type; });\n"
            "  }"
        )
        text = text[: match.start()] + replacement + text[match.end() :]
    elif "addConversion([](Type type) -> Type { return type; });" not in text:
        raise RuntimeError("type-converter constructor is missing")

    preflight_pattern = re.compile(
        r'(if \(!operation->getAttrOfType<IntegerAttr>\("value"\)\)\s*'
        r'return operation\.emitOpError\(\s*'
        r'"\[NODAL-CIRCT-022-003\].*?"an integer value attribute"\);)\s*'
        r'return success\(\);',
        re.S,
    )
    preflight_replacement = r'''\1
  if (!operation->getResult(0).use_empty())
    return operation.emitOpError(
        "[NODAL-CIRCT-022-006] Increment 22 converts only standalone "
        "constants; users remain deferred until their operations have "
        "exact CIRCT patterns");
  return success();'''
    text, count = preflight_pattern.subn(preflight_replacement, text, count=1)
    if count == 0 and "NODAL-CIRCT-022-006" not in text:
        raise RuntimeError("standalone-constant preflight anchor is missing")

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
    conversion_path.write_text(text, encoding="utf-8")

    cmake_path = root / "core/compiler/lib/Conversion/CMakeLists.txt"
    cmake = cmake_path.read_text(encoding="utf-8")
    if "MLIRTransformUtils" not in cmake:
        cmake = cmake.replace(
            "    MLIRTransforms\n",
            "    MLIRTransforms\n    MLIRTransformUtils\n",
            1,
        )
    cmake_path.write_text(cmake, encoding="utf-8")

    checker_path = root / "scripts/check_increment22.py"
    checker = checker_path.read_text(encoding="utf-8")
    marker = '                "NODAL-CIRCT-022-002",\n'
    addition = '                "NODAL-CIRCT-022-006",\n'
    if addition not in checker:
        if checker.count(marker) != 1:
            raise RuntimeError("checker diagnostic inventory anchor is missing")
        checker = checker.replace(marker, marker + addition, 1)
    checker_path.write_text(checker, encoding="utf-8")


if __name__ == "__main__":
    main()
