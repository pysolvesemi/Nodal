#!/usr/bin/env python3
"""Materialize the fail-closed Increment 22 exact CIRCT subset."""

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

    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("materialize_increment22_v4.py")), str(root)],
        check=True,
    )

    path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
    text = path.read_text(encoding="utf-8")
    old_converter = """        NodalToCirctTypeConverter() {
            addConversion([](Type type) -> Type { return type; });
            addConversion([](nodal::BitsType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth());
            });
            addConversion([](nodal::UIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::SignednessSemantics::Unsigned);
            });
            addConversion([](nodal::SIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::SignednessSemantics::Signed);
            });
          }"""
    new_converter = """        NodalToCirctTypeConverter() {
            addConversion([](nodal::BitsType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth());
            });
            addConversion([](nodal::UIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::SignednessSemantics::Unsigned);
            });
            addConversion([](nodal::SIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::SignednessSemantics::Signed);
            });
            addConversion([](Type type) -> Type { return type; });
          }"""
    if old_converter in text:
        text = text.replace(old_converter, new_converter, 1)
    elif new_converter not in text:
        raise RuntimeError("type-converter ordering anchor is missing")

    old_preflight = """          if (!operation->getAttrOfType<IntegerAttr>("value"))
            return operation.emitOpError(
                "[NODAL-CIRCT-022-003] finite-width CIRCT conversion requires "
                "an integer value attribute");
          return success();"""
    new_preflight = """          if (!operation->getAttrOfType<IntegerAttr>("value"))
            return operation.emitOpError(
                "[NODAL-CIRCT-022-003] finite-width CIRCT conversion requires "
                "an integer value attribute");
          if (!operation->getResult(0).use_empty())
            return operation.emitOpError(
                "[NODAL-CIRCT-022-006] Increment 22 converts only standalone "
                "constants; users remain deferred until their operations have "
                "exact CIRCT patterns");
          return success();"""
    if old_preflight in text:
        text = text.replace(old_preflight, new_preflight, 1)
    elif new_preflight not in text:
        raise RuntimeError("fail-closed constant preflight anchor is missing")
    path.write_text(text, encoding="utf-8")

    checker = root / "scripts/check_increment22.py"
    checker_text = checker.read_text(encoding="utf-8")
    marker = '                "NODAL-CIRCT-022-002",\n'
    addition = '                "NODAL-CIRCT-022-006",\n'
    if addition not in checker_text:
        if checker_text.count(marker) != 1:
            raise RuntimeError("Increment 22 diagnostic inventory anchor is missing")
        checker_text = checker_text.replace(marker, marker + addition, 1)
    checker.write_text(checker_text, encoding="utf-8")


if __name__ == "__main__":
    main()
