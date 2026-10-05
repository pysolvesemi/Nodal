#!/usr/bin/env python3
"""Native static indexing proofs; these are not public Scala/target witnesses."""

import re
from collections import Counter

from run_generate_count_matrix import Case, main
from run_shape_contract_matrix import parameter


CODE = "NODAL-SHAPE-043-002"


def selection(dimensions="2,3", indices=(1, 2), element="f64", result=None,
              *, unknown=False, metadata=""):
    shaped = f'!nodal.shaped<"{dimensions}", {element}>'
    body = '''%seed = "nodal.constant"() <{metadata = {}, value = 0.0 : f64}> : () -> f64
'''
    body += f'''%shape = "nodal.shape_view"(%seed) <{{dimensions = "{dimensions}", materialization = "explicit_view", metadata = {{storage = "structural"}}, observability = "source_mapped", origin = "Fixture.samples"}}> : (f64) -> {shaped}
'''
    for axis, value in enumerate(indices):
        if unknown:
            body += f'''%seed{axis} = "nodal.constant"() <{{metadata = {{}}, value = {value} : index}}> : () -> index
%i{axis} = "nodal.dynamic_value"(%seed{axis}) <{{metadata = {{}}, origin = "runtime"}}> : (index) -> index
'''
        else:
            body += f'''%i{axis} = "nodal.constant"() <{{metadata = {{}}, value = {value} : index}}> : () -> index
'''
    operands = ", ".join(["%shape"] + [f"%i{axis}" for axis in range(len(indices))])
    types = ", ".join([shaped] + ["index"] * len(indices))
    body += f'''%selected = "nodal.shape_index"({operands}) <{{metadata = {{source_path = "Fixture.selected"{metadata}}}}}> : ({types}) -> {result or element}
'''
    return body


def reference(name, symbol):
    return (f'%{name} = "nodal.const_parameter_ref"() <{{metadata = {{}}, '
            f'parameter = @{symbol}}}> : () -> i64\n')


def literal_i64(name, value):
    return (f'%{name} = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{value}", '
            f'value = {value} : i64}}> : () -> i64\n')


def expression(name, operator, *operands):
    return (f'%{name} = "nodal.const_expr"({", ".join("%" + value for value in operands)}) '
            f'<{{metadata = {{}}, operator_name = "{operator}"}}> : '
            f'({", ".join("i64" for _ in operands)}) -> i64\n')


def expression_selection(prefix, value="lane_ref", dimensions="4"):
    shaped = f'!nodal.shaped<"{dimensions}", f64>'
    return prefix + f'''%seed = "nodal.constant"() <{{metadata = {{}}, value = 0.0 : f64}}> : () -> f64
%shape = "nodal.shape_view"(%seed) <{{dimensions = "{dimensions}", materialization = "explicit_view", metadata = {{storage = "structural"}}, observability = "source_mapped", origin = "Fixture.samples"}}> : (f64) -> {shaped}
%selected = "nodal.shape_index"(%shape, %{value}) <{{metadata = {{source_path = "Fixture.selected"}}}}> : ({shaped}, i64) -> f64
'''


def port_selection(*, port="samples", port_type='!nodal.shaped<"2", f64>',
                   result_type=None, reference=None):
    reference = port if reference is None else reference
    result_type = port_type if result_type is None else result_type
    return f'''"nodal.port"() <{{direction = "input", domain = @root, metadata = {{}}, sym_name = "{port}", type = {port_type}}}> : () -> ()
%shape = "nodal.port_value"() <{{metadata = {{}}, port = @{reference}}}> : () -> {result_type}
%i0 = "nodal.constant"() <{{metadata = {{}}, value = 1 : index}}> : () -> index
%selected = "nodal.shape_index"(%shape, %i0) <{{metadata = {{source_path = "Fixture.selected"}}}}> : ({result_type}, index) -> f64
'''


def cases():
    symbolic = parameter(lower=2, upper=4)
    exclusive = symbolic.replace("lower_inclusive = true", "lower_inclusive = false")
    intersection = symbolic + '''%tight_lower = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
"nodal.parameter_constraint"(%tight_lower, %lanes_upper) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @lanes}> : (i64, i64) -> ()
'''
    # Default 3 satisfies both exclusive and intersecting ranges. It remains
    # irrelevant to the proof, which must cover every legal override.
    exclusive = exclusive.replace("default_value = 2 : i64", "default_value = 3 : i64")
    intersection = intersection.replace("default_value = 2 : i64", "default_value = 3 : i64")
    lane = parameter(name="lane", lower=1, upper=3) + reference("lane_ref", "lane")
    compound = lane + literal_i64("one", 1) + expression("lane_index", "sub", "lane_ref", "one")
    ordinary = lane.replace('classification = "structural"', 'classification = "ordinary"')
    dynamic = lane + ('%dynamic = "nodal.dynamic_value"(%lane_ref) '
                      '<{metadata = {}, origin = "runtime"}> : (i64) -> i64\n')
    zero_divisor = (lane + parameter(name="divisor", lower=-1, upper=1) +
                    reference("divisor_ref", "divisor") +
                    expression("quotient", "div", "lane_ref", "divisor_ref"))
    return [
        Case("index_fixed_first", selection(indices=(0, 0))),
        Case("index_fixed_last", selection()),
        Case("index_singleton", selection("1", (0,))),
        Case("index_nested_element", selection("2", (1,), '!nodal.shaped<"3", f64>')),
        Case("index_symbolic_minimum", symbolic + selection("lanes,3", (1, 2))),
        Case("index_symbolic_exclusive", exclusive + selection("lanes", (2,))),
        Case("index_symbolic_intersection", intersection + selection("lanes", (2,))),
        Case("index_repeated_symbolic_axes", symbolic + selection(",".join(["lanes"] * 64), (1,) * 64)),
        Case("index_large_extent", selection("9223372036854775807", (9223372036854775806,))),
        Case("index_public_port_value", port_selection()),
        Case("index_expression_parameter", expression_selection(lane)),
        Case("index_expression_compound", expression_selection(compound, "lane_index", "3")),
        Case("index_expression_same_subtraction", expression_selection(
            lane + expression("same", "sub", "lane_ref", "lane_ref"), "same", "1")),
        Case("index_reject_negative", selection(indices=(-1, 0)), CODE),
        Case("index_reject_first_axis_end", selection(indices=(2, 0)), CODE),
        Case("index_reject_last_axis_end", selection(indices=(0, 3)), CODE),
        Case("index_reject_missing_axis", selection(indices=(0,)), CODE),
        Case("index_reject_extra_axis", selection(indices=(0, 0, 0)), CODE),
        Case("index_reject_empty_dimension", selection("2,", (0, 0)), CODE),
        Case("index_reject_result_type", selection(result="i64"), CODE),
        Case("index_reject_unproved_runtime", selection(unknown=True), CODE),
        Case("index_reject_forged_metadata", selection(unknown=True,
             metadata=', bounds_proven = true, index_upper = 0 : i64'), CODE),
        Case("index_reject_symbolic_default_only", symbolic + selection("lanes", (2,)), CODE),
        Case("index_reject_symbolic_maximum", symbolic + selection("lanes", (3,)), CODE),
        Case("index_reject_exclusive_end", exclusive + selection("lanes", (3,)), CODE),
        Case("index_reject_intersection_end", intersection + selection("lanes", (3,)), CODE),
        Case("index_reject_extreme_negative", selection("2", (-9223372036854775808,)), CODE),
        Case("index_reject_large_extent_end", selection("9223372036854775807", (9223372036854775807,)), CODE),
        Case("index_reject_missing_port_value", port_selection(reference="missing"), CODE),
        Case("index_reject_port_value_type", port_selection(result_type='!nodal.shaped<"3", f64>'), CODE),
        Case("index_reject_expression_upper_bound", expression_selection(
            parameter(name="lane", lower=1, upper=4) + reference("lane_ref", "lane")), CODE),
        Case("index_reject_expression_ordinary_parameter", expression_selection(ordinary), CODE),
        Case("index_reject_expression_dynamic", expression_selection(dynamic, "dynamic"), CODE),
        Case("index_reject_expression_zero_divisor", expression_selection(
            zero_divisor, "quotient"), CODE),
    ]


def contracts(data):
    """Keep operand order/value, shape/element types and source/storage identity.

    SSA names may change during native printing. Resolve each selected index
    through its actual literal definition instead of comparing those names.
    """
    text = data.decode()
    constants = dict(re.findall(
        r'(%[A-Za-z_0-9]+)\s*=\s*"nodal.constant"\(\)\s*<\{[^\n]*?value = (-?\d+) : index', text))
    selections = []
    for operands, types, result in re.findall(
            r'"nodal.shape_index"\(([^)]*)\)\s*<\{[^\n]*\}>\s*:\s*\(([^\n]*)\)\s*->\s*([^\n]+)', text):
        names = [name.strip() for name in operands.split(",")]
        selections.append((tuple(constants.get(name, "unresolved") for name in names[1:]),
                           re.sub(r"\s+", "", types), re.sub(r"\s+", "", result)))
    return (
        Counter(re.findall(r'"(nodal\.[a-z_]+)"\(', text)),
        Counter(re.findall(r'\b(source_path|origin|storage|dimensions)\s*=\s*"([^"]*)"', text)),
        Counter(selections),
        Counter(re.findall(r'operator_name\s*=\s*"([^"]+)"', text)),
        Counter(re.findall(r'parameter\s*=\s*@([A-Za-z_][A-Za-z0-9_]*)', text)),
        Counter(re.findall(r'spelling\s*=\s*"(-?\d+)"', text)),
    )


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    retained = contracts(stdout)
    return (exit_code == 0 and not codes and bool(retained[2])
            and contracts(data) == retained)


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-index-matrix.v1"))
