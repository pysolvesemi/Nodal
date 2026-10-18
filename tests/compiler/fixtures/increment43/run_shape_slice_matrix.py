#!/usr/bin/env python3
"""Focused native structural slice proofs, independent of Scala and target lowering."""

import re
from collections import Counter

from run_generate_count_matrix import Case, main
from run_shape_contract_matrix import expression, literal, parameter, reference
from run_shape_view_matrix import expression_contracts


CODE = "NODAL-SHAPE-043-004"


def shape(dimensions, element="f64"):
    return f'!nodal.shaped<"{dimensions}", {element}>'


def port(dimensions="4,3", element="f64"):
    data_type = shape(dimensions, element)
    return f'''"nodal.domain"() <{{edge = "rising", metadata = {{}}, reset_policy = "sync", sym_name = "root"}}> : () -> ()
"nodal.port"() <{{direction = "input", domain = @root, metadata = {{semantic_path = "Fixture.samples"}}, sym_name = "samples", type = {data_type}}}> : () -> ()
%source = "nodal.port_value"() <{{metadata = {{semantic_path = "Fixture.samples"}}, port = @samples}}> : () -> {data_type}
'''


def selection(source="4,3", target="2,3", *, axis=0, lower="lower", upper="upper",
              length="length", element="f64", result_element=None, dimensions=None,
              input_value="source", input_path="Fixture.samples", origin=None,
              path="Fixture.result", storage="structural", access="read_only",
              observability="source_mapped", output="result"):
    result_element = element if result_element is None else result_element
    dimensions = target if dimensions is None else dimensions
    origin = input_path if origin is None else origin
    return (f'%{output} = "nodal.shape_slice"(%{input_value}, %{lower}, %{upper}, %{length}) '
            f'<{{axis = {axis} : i64, dimensions = "{dimensions}", '
            f'metadata = {{access = "{access}", input_path = "{input_path}", '
            f'semantic_path = "{path}", storage = "{storage}"}}, '
            f'observability = "{observability}", origin = "{origin}"}}> : '
            f'({shape(source, element)}, i64, i64, i64) -> {shape(target, result_element)}\n')


def fixed(source="4,3", target="2,3", *, low=1, high=3, count=2, element="f64", **kwargs):
    return (port(source, element) + literal("lower", low) + literal("upper", high) +
            literal("length", count) + selection(source, target, element=element, **kwargs))


def symbolic(*, source="8", lower=0, name="lanes", low=2, high=4,
             target=None, classification="structural", effects='["shape"]', constraint=True):
    prefix = (parameter(name=name, lower=low, upper=high, classification=classification,
                        effects=effects, constraint=constraint) +
              reference("upper", name) + literal("lower", lower))
    if lower == 0:
        return prefix + port(source) + selection(source, target or name, upper="upper", length="upper")
    return (prefix + expression("length", operator="sub", operands=("upper", "lower"),
                                semantic_path="Fixture.length") + port(source) +
            selection(source, target or "Fixture.length"))


def cases():
    compound = (parameter(lower=2, upper=4) + reference() + literal("one", 1) +
                expression() + literal("lower", 0) + port("Fixture.extent") +
                selection("Fixture.extent", "Fixture.extent", upper="extent", length="extent"))
    endpoints = (parameter("low", lower=1, upper=2) + parameter("high", lower=4, upper=6)
                 .replace("default_value = 2 : i64", "default_value = 4 : i64") +
                 reference("lower", "low") + reference("upper", "high") +
                 expression("length", operator="sub", operands=("upper", "lower"),
                            semantic_path="Fixture.length") + port("8") +
                 selection("8", "Fixture.length"))
    nested = fixed(output="first", path="Fixture.first") + selection(
        "2,3", "2,2", axis=1, input_value="first", input_path="Fixture.first")
    # The second operation reuses [1, 3), now on the three-element second axis.
    reshaped = (port("2,6") +
                '%view = "nodal.shape_view"(%source) <{dimensions = "4,3", '
                'materialization = "view", metadata = {semantic_path = "Fixture.view", '
                'storage = "structural"}, observability = "source_mapped", '
                'origin = "Fixture.samples"}> : (!nodal.shaped<"2,6", f64>) -> '
                '!nodal.shaped<"4,3", f64>\n' + literal("lower", 1) + literal("upper", 3) +
                literal("length", 2) + selection(input_value="view", input_path="Fixture.view"))
    dynamic = fixed().replace('%result = "nodal.shape_slice"',
                             '%runtime = "nodal.dynamic_value"(%lower) '
                             '<{metadata = {}, origin = "runtime"}> : (i64) -> i64\n'
                             '%result = "nodal.shape_slice"').replace(
                                 '(%source, %lower, %upper, %length)',
                                 '(%source, %runtime, %upper, %length)')
    forged_parameter = (parameter("lanes", lower=2, upper=4) +
                        parameter("rows", lower=2, upper=4) + reference("upper", "lanes") +
                        reference("length", "rows") + literal("lower", 0) + port("8") +
                        selection("8", "rows"))
    forged_expression = endpoints.replace('operator_name = "sub"', 'operator_name = "add"')
    duplicate_path = compound.replace('%lower = ', expression(
        "duplicate", operands=("lanes_ref", "one")) + '%lower = ', 1).replace(
            shape("Fixture.extent"), shape("8"), 3)
    wrong_literal_form = (port() + literal("lower", 1) + literal("upper", 3) +
                          expression("length", operator="sub", operands=("upper", "lower"),
                                     semantic_path="Fixture.length") +
                          selection(target="Fixture.length,3"))
    different_parameter_operand = endpoints.replace(
        '%length = ', parameter("other", lower=1, upper=2) + reference("other_ref", "other") +
        '%length = ').replace('"nodal.const_expr"(%upper, %lower)',
                             '"nodal.const_expr"(%upper, %other_ref)')
    correlated = (parameter(lower=2, upper=4) + reference() + literal("one", 1) +
                  expression(operator="sub") + literal("lower", 0) + port("lanes") +
                  selection("lanes", "Fixture.extent", upper="extent", length="extent"))
    negative_lower = (port() + literal("one", 1) +
                      expression("lower", operator="neg", operands=("one",),
                                 semantic_path="Fixture.lower") + literal("upper", 3) +
                      literal("length", 2) + selection())
    fake_source = fixed().replace(
        '%result = ', '%fake = "nodal.dynamic_value"(%source) '
        '<{metadata = {semantic_path = "Fixture.fake"}, origin = "runtime"}> : '
        '(!nodal.shaped<"4,3", f64>) -> !nodal.shaped<"4,3", f64>\n%result = ').replace(
            '(%source, %lower, %upper, %length)', '(%fake, %lower, %upper, %length)').replace(
                'input_path = "Fixture.samples"', 'input_path = "Fixture.fake"').replace(
                    'origin = "Fixture.samples"', 'origin = "Fixture.fake"')
    relabeled_source = fixed().replace(
        '%source = "nodal.port_value"() <{metadata = {semantic_path = "Fixture.samples"}',
        '%source = "nodal.port_value"() <{metadata = {semantic_path = "Fixture.other"}').replace(
            'input_path = "Fixture.samples"', 'input_path = "Fixture.other"').replace(
                'origin = "Fixture.samples"', 'origin = "Fixture.other"')
    duplicate_identity = nested.replace('semantic_path = "Fixture.result"',
                                        'semantic_path = "Fixture.samples"')
    return [
        Case("slice_fixed_middle", fixed()),
        Case("slice_fixed_full", fixed(target="4,3", low=0, high=4, count=4)),
        Case("slice_fixed_last_axis", fixed(target="4,2", axis=1)),
        Case("slice_fixed_singleton", fixed(target="1,3", low=3, high=4, count=1)),
        Case("slice_existing_integer_element", fixed(element="i64")),
        Case("slice_symbolic_upper", symbolic()),
        Case("slice_full_symbolic_extent", symbolic(source="lanes")),
        Case("slice_nonzero_full_symbolic_extent", symbolic(source="lanes", lower=1)),
        Case("slice_symbolic_endpoints", endpoints),
        Case("slice_full_compound_extent", compound),
        Case("slice_composed_slices", nested),
        Case("slice_reshape_input", reshaped),
        Case("slice_signed64_boundary", fixed("9223372036854775807", "1",
                                             low=9223372036854775806,
                                             high=9223372036854775807, count=1)),
        Case("slice_reject_negative_endpoint", negative_lower, CODE),
        Case("slice_reject_fake_source", fake_source, CODE),
        Case("slice_reject_relabeled_source", relabeled_source, CODE),
        Case("slice_reject_duplicate_result_identity", duplicate_identity, CODE),
        Case("slice_reject_reversed_length_operands", endpoints.replace(
            '"nodal.const_expr"(%upper, %lower)', '"nodal.const_expr"(%lower, %upper)'), CODE),
        Case("slice_reject_negative_axis", fixed(axis=-1), CODE),
        Case("slice_reject_axis_end", fixed(axis=2), CODE),
        Case("slice_reject_empty", fixed(low=2, high=2), CODE),
        Case("slice_reject_reversed", fixed(low=3, high=1), CODE),
        Case("slice_reject_outside", fixed(low=3, high=5), CODE),
        Case("slice_reject_literal_length", fixed(count=3), CODE),
        Case("slice_reject_literal_expression_length", wrong_literal_form, CODE),
        Case("slice_reject_remaining_axis", fixed(target="2,2"), CODE),
        Case("slice_reject_rank", fixed(target="2"), CODE),
        Case("slice_reject_element", fixed(result_element="i64"), CODE),
        Case("slice_reject_dimensions", fixed(dimensions="3,2"), CODE),
        Case("slice_reject_storage", fixed(storage="memory"), CODE),
        Case("slice_reject_writable_access", fixed(access="read_write"), CODE),
        Case("slice_reject_observability", fixed(observability="hidden"), CODE),
        Case("slice_reject_origin", fixed(origin="Fixture.other"), CODE),
        Case("slice_reject_input_identity", fixed(input_path="Fixture.other"), CODE),
        Case("slice_reject_foreign_identity", fixed(path="Other.result"), CODE),
        Case("slice_reject_runtime_endpoint", dynamic, CODE),
        Case("slice_reject_ordinary_endpoint", symbolic(classification="ordinary"), CODE),
        Case("slice_reject_unbounded_endpoint", symbolic(constraint=False), CODE),
        Case("slice_reject_missing_shape_envelope", symbolic(effects='["topology"]'), CODE),
        Case("slice_reject_unsafe_symbolic_upper", symbolic(source="3"), CODE),
        Case("slice_reject_equal_bounds_parameter", forged_parameter, CODE),
        Case("slice_reject_length_operator", forged_expression, CODE),
        Case("slice_reject_length_operand_identity", different_parameter_operand, CODE),
        Case("slice_reject_duplicate_compound_path", duplicate_path, CODE),
        Case("slice_reject_unproven_correlation", correlated, CODE),
        Case("slice_reject_source_cardinality_overflow", fixed("9223372036854775807,2", "2,2"), CODE),
    ]


def contracts(data):
    """Compare exact static identities/operand order while allowing SSA renaming."""
    text = data.decode()
    identities = {}
    ssa = r'%[-A-Za-z0-9_.$]+(?:#[0-9]+)?'
    operation = re.compile(
        rf'^\s*({ssa})\s*=\s*"nodal\.([a-z_]+)"\(([^)]*)\)\s*<\{{([^\n]*)\}}>\s*:\s*([^\n]+)',
        re.MULTILINE)
    slices = Counter()
    for result, kind, operands, attributes, types in operation.findall(text):
        path = re.search(r'\bsemantic_path = "([^"]+)"', attributes)
        if kind == "const_literal":
            value = re.search(r'\bvalue = (-?[0-9]+) : ([a-z0-9]+)', attributes)
            identity = ("literal", value.groups()) if value else ("invalid-literal",)
        elif kind == "const_parameter_ref":
            symbol = re.search(r'\bparameter = @([A-Za-z_][A-Za-z0-9_]*)', attributes)
            identity = ("parameter", symbol.group(1)) if symbol else ("invalid-parameter",)
        else:
            identity = (kind, path.group(1) if path else "")
        if kind == "shape_slice":
            names = [name.strip() for name in operands.split(",")]
            slices[(tuple(identities.get(name, ("unresolved", name)) for name in names),
                    attributes, types)] += 1
        identities[result] = identity
    return (slices, expression_contracts(text),
            Counter(re.findall(r'"(nodal\.[a-z_]+)"\(', text)),
            Counter(re.findall(r'\b(semantic_path|origin|input_path|storage|access|observability|dimensions|classification|spelling) = "([^"]*)"', text)),
            Counter(re.findall(r'\b(value = -?[0-9]+ : i64|parameter = @[A-Za-z_0-9]+|effects = \[[^\]]*\])', text)),
            Counter(re.findall(r'!nodal.shaped<"([^"]+)", ([^>]+)>', text)))


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    actual = contracts(stdout)
    return exit_code == 0 and not codes and bool(actual[0]) and actual == contracts(data)


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-slice-matrix.v1"))
