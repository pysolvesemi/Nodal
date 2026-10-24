#!/usr/bin/env python3
"""Derive four focused controls from the actual nested Scala bridge output.

This is an anchored fixture transformation, not an MLIR evaluator. Prefix and
nested Real SSA cases are admission refusals. Unused-prefix and signed32 product
overflow cases must verify first, then fail backend emission transactionally.
"""

import argparse
from pathlib import Path


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError(f"expected exactly one occurrence of {before!r}")
    return text.replace(before, after, 1)


def line_with(text, prefix, contains=""):
    lines = [line for line in text.splitlines(keepends=True)
             if line.startswith(prefix) and contains in line]
    if len(lines) != 1 or not lines[0].endswith("\n"):
        raise ValueError(f"expected exactly one complete line: {prefix!r}, {contains!r}")
    return lines[0]


def generate(text):
    """Return filename -> text without reading, writing or invoking a compiler."""
    controls = {}
    prefix = line_with(text, "    %selected_family_10 = ")
    forged = replace_once(
        prefix,
        '"nodal.generated_member"(%generated_family_8, %shape_index_static_0)',
        '"nodal.generated_member"(%selected_family_9, %shape_index_static_0)')
    controls["prefix-provenance.mlir"] = replace_once(text, prefix, forged)

    original = line_with(text, "    %selected_family_9 = ")
    unused = replace_once(original, "%selected_family_9 =", "%unused_nested_family =")
    unused = replace_once(
        unused, "NestedConservativeSource.instance_0_interface_3_selected_7.family0",
        "NestedConservativeSource.unused_nested_family")
    if "%unused_nested_family" in text:
        raise ValueError("unused-family control name already exists")
    controls["unused-prefix.mlir"] = replace_once(text, original, original + unused)

    instance = line_with(
        text, '        "nodal.instance"()',
        'sym_name = "generate_0_rows_generate_1_columns_f_ff081437"')
    unbound = replace_once(instance, "parameter_bindings = {gain = 3.0 : f64}",
                           "parameter_bindings = {}")
    actual = ('    %nested_real_actual = "nodal.const_literal"() '
              '<{spelling = "3.0", value = 3.0 : f64, metadata = {}}> '
              ': () -> f64 loc(unknown)\n')
    override = ('        "nodal.parameter_override"(%nested_real_actual) '
                '<{instance = @generate_0_rows_generate_1_columns_f_ff081437, '
                'parameter = @gain, generated_owner = '
                '"NestedConservativeSource.generate_0.generate_1", '
                'generated_induction = "NestedConservativeSource.map_source_l29_34acce0b", '
                'metadata = {}}> : (f64) -> () loc(unknown)\n')
    if "%nested_real_actual" in text:
        raise ValueError("Real SSA control name already exists")
    ssa = replace_once(text, instance, unbound + override)
    outer = line_with(ssa, "    %generated_family_8 = ")
    controls["nested-real-ssa.mlir"] = replace_once(ssa, outer, actual + outer)

    overflow = text
    for bound_prefix, family_prefix, extent in (
            ("    %shape_index_static_1 = ", "    %generated_family_8 = ", "2"),
            ("      %shape_index_static_4 = ", "      %generated_family_7 = ", "3")):
        bound = line_with(overflow, bound_prefix)
        changed = replace_once(bound, f'spelling = "{extent}", value = {extent} : i64',
                               'spelling = "46341", value = 46341 : i64')
        overflow = replace_once(overflow, bound, changed)
        family = line_with(overflow, family_prefix)
        changed = replace_once(family, f"maximum_trip_count = {extent} : i64",
                               "maximum_trip_count = 46341 : i64")
        overflow = replace_once(overflow, family, changed)
    controls["overflow.mlir"] = overflow
    return controls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="actual nested.mlir bridge output")
    parser.add_argument("output", type=Path, help="new output directory; must not exist")
    args = parser.parse_args()
    controls = generate(args.input.read_bytes().decode("utf-8"))
    args.output.mkdir(exist_ok=False)
    for name, text in controls.items():
        with (args.output / name).open("xb") as stream:
            stream.write(text.encode("utf-8"))


if __name__ == "__main__":
    main()
