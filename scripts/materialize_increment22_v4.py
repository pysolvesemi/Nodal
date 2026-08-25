#!/usr/bin/env python3
"""Materialize Increment 22 on the verified Increment 21 native layout."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    base = Path(__file__).with_name("materialize_increment22.py")
    text = base.read_text(encoding="utf-8")
    old = '''    replace_once(
        root,
        "core/compiler/lib/CMakeLists.txt",
        "add_subdirectory(Dialect)\\nadd_subdirectory(Support)\\n",
        "add_subdirectory(Dialect)\\nadd_subdirectory(Conversion)\\nadd_subdirectory(Support)\\n",
    )
'''
    new = '''    library_path = root / "core/compiler/lib/CMakeLists.txt"
    library_text = library_path.read_text(encoding="utf-8")
    if "add_subdirectory(Conversion)" not in library_text:
        anchor = "add_subdirectory(Dialect)\\n"
        if library_text.count(anchor) != 1:
            raise RuntimeError(
                "core/compiler/lib/CMakeLists.txt: dialect anchor is not unique"
            )
        library_text = library_text.replace(
            anchor,
            anchor + "add_subdirectory(Conversion)\\n",
            1,
        )
        library_path.write_text(library_text, encoding="utf-8")
'''
    if old not in text:
        raise RuntimeError("Increment 22 library-layout materializer anchor is missing")
    patched = base.with_name("materialize_increment22_runtime.py")
    patched.write_text(text.replace(old, new, 1), encoding="utf-8")
    try:
        subprocess.run(
            [sys.executable, str(Path(__file__).with_name("materialize_increment22_v2.py")), str(root)],
            check=True,
        )
        # v2 invokes the original filename, so run the patched base explicitly,
        # then apply v2/v3 post-processing through their deterministic helpers.
    except subprocess.CalledProcessError:
        # The unpatched base is expected to fail on the Increment 21 layout;
        # materialize with the runtime-patched base instead.
        subprocess.run([sys.executable, str(patched), str(root)], check=True)
        # Apply roadmap alignment from v2 without rerunning its base.
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
    finally:
        patched.unlink(missing_ok=True)

    # Apply the final pinned API adjustments directly; these are idempotent.
    conversion = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
    source = conversion.read_text(encoding="utf-8")
    if '#include "mlir/Pass/PassRegistry.h"\n' not in source:
        source = source.replace(
            '#include "mlir/Pass/PassManager.h"\n',
            '#include "mlir/Pass/PassManager.h"\n#include "mlir/Pass/PassRegistry.h"\n',
            1,
        )
    if '#include <utility>\n' not in source:
        source = source.replace('#include <string>\n', '#include <string>\n#include <utility>\n', 1)
    source = source.replace(
        "IntegerType::Unsigned)",
        "IntegerType::SignednessSemantics::Unsigned)",
    ).replace(
        "IntegerType::Signed)",
        "IntegerType::SignednessSemantics::Signed)",
    ).replace(
        "llvm::dyn_cast_or_null<IntegerType>(converted)",
        "llvm::dyn_cast<IntegerType>(converted)",
    ).replace(
        "if (failed(runPreflight(getOperation(), rejectDeferred)))",
        "if (failed(runPreflight(getOperation(), rejectDeferred.getValue())))",
    )
    old_commit = """            module->setAttrs((*candidate)->getAttrs());
            module.getBodyRegion().takeBody(candidate->getRegion(0));"""
    new_commit = """            ModuleOp candidateModule = *candidate;
            module->setAttrs(candidateModule->getAttrs());
            module.getBodyRegion().takeBody(candidateModule.getBodyRegion());"""
    if old_commit in source:
        source = source.replace(old_commit, new_commit, 1)
    conversion.write_text(source, encoding="utf-8")

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
