#!/usr/bin/env python3
"""Native fixed/symbolic reshape proofs, not generated-target witnesses."""

import re
from collections import Counter

from run_generate_count_matrix import Case, main
from run_shape_contract_matrix import expression, literal, parameter, reference


CODE = "NODAL-SHAPE-043-003"


def view(source="2,3", target="3,2", *, element="f64", result_element=None,
         dimensions=None, materialization="view", observability="source_mapped",
         storage="structural"):
    result_element = element if result_element is None else result_element
    dimensions = target if dimensions is None else dimensions
    source_type = f'!nodal.shaped<"{source}", {element}>'
    result_type = f'!nodal.shaped<"{target}", {result_element}>'
    return f'''%seed = "nodal.constant"() <{{metadata = {{}}, value = 0.0 : f64}}> : () -> f64
%source = "nodal.shape_view"(%seed) <{{dimensions = "{source}", materialization = "explicit_view", metadata = {{storage = "structural"}}, observability = "source_mapped", origin = "Fixture.samples"}}> : (f64) -> {source_type}
%result = "nodal.shape_view"(%source) <{{dimensions = "{dimensions}", materialization = "{materialization}", metadata = {{source_path = "Fixture.result", storage = "{storage}"}}, observability = "{observability}", origin = "Fixture.samples"}}> : ({source_type}) -> {result_type}
'''


def cases():
    compound = parameter() + reference() + literal() + expression()
    distinct_compound = (compound + literal("two", 1) +
                         expression("other", operands=("lanes_ref", "two"),
                                    semantic_path="Fixture.other"))
    nonpositive_compound = (parameter() + reference() + literal() +
                            expression(operator="sub"))
    ordinary_compound = compound.replace('classification = "structural"',
                                         'classification = "ordinary"')
    forged_compound = (parameter() +
                       '%extent = "nodal.const_parameter_ref"() '
                       '<{metadata = {semantic_path = "Fixture.extent"}, '
                       'parameter = @lanes}> : () -> i64\n')
    return [
        Case("view_rank_preserving", view()),
        Case("view_rank_reducing", view("2,2", "4")),
        Case("view_rank_expanding", view("6", "1,2,3")),
        Case("view_singleton", view("1", "1,1")),
        Case("view_symbolic_permutation", parameter() + view("lanes,2", "2,lanes")),
        Case("view_repeated_symbol", parameter() + view("lanes,lanes,2", "2,lanes,lanes")),
        Case("view_distinct_symbols", parameter() + parameter("rows") + view("lanes,rows,2", "2,rows,lanes")),
        Case("view_symbolic_regroup_literals", parameter() + view("2,lanes,3", "lanes,6")),
        Case("view_repeated_axes", parameter(lower=1, upper=2) + view(",".join(["lanes"] * 32), ",".join(["lanes"] * 32))),
        Case("view_compound_permutation", compound + view("Fixture.extent,2", "2,Fixture.extent")),
        Case("view_compound_repeated", compound + view("Fixture.extent,Fixture.extent,2", "2,Fixture.extent,Fixture.extent")),
        Case("view_reject_count", view("2,3", "2,2"), CODE),
        Case("view_reject_unknown_materialization", view(materialization="unknown"), CODE),
        Case("view_reject_materialization_whitespace", view(materialization="view "), CODE),
        Case("view_reject_legacy_count_bypass", view("2,3", "2,2", materialization="explicit_view"), CODE),
        Case("view_reject_legacy_storage_bypass", view(materialization="explicit_view", storage="memory"), CODE),
        Case("view_reject_symbolic_source", view("lanes,2", "2,2"), CODE),
        Case("view_reject_symbolic_result", view("2,2", "lanes,2"), CODE),
        Case("view_reject_dimensions_attr", view(dimensions="2,3"), CODE),
        Case("view_reject_element", view(result_element="i64"), CODE),
        Case("view_reject_observability", view(observability="hidden"), CODE),
        Case("view_reject_storage", view(storage="memory"), CODE),
        Case("view_reject_overflow", view("9223372036854775807,2", "2,9223372036854775807"), CODE),
        Case("view_reject_missing_parameter", view("lanes,2", "2,lanes"), CODE),
        Case("view_reject_dropped_factor", parameter() + view("lanes,lanes,2", "lanes,2"), CODE),
        Case("view_reject_added_factor", parameter() + view("lanes,2", "lanes,lanes,2"), CODE),
        Case("view_reject_equal_default_different_identity", parameter() + parameter("rows") + view("lanes,2", "rows,2"), CODE),
        Case("view_reject_symbolic_overflow", parameter(upper=2147483647) + view("lanes,lanes,lanes", "lanes,lanes,lanes"), CODE),
        Case("view_reject_ordinary_parameter", parameter(classification="ordinary") + view("lanes,2", "2,lanes"), CODE),
        Case("view_reject_missing_envelope", parameter(effects='["topology"]') + view("lanes,2", "2,lanes"), CODE),
        Case("view_reject_unbounded_parameter", parameter(constraint=False) + view("lanes,2", "2,lanes"), CODE),
        Case("view_reject_zero_capable_parameter", parameter(lower=0) + view("lanes,2", "2,lanes"), CODE),
        Case("view_reject_foreign_parameter", parameter() + '"nodal.module"() <{metadata = {}, sym_name = "Child"}> ({\n' + view("lanes,2", "2,lanes") + '}) : () -> ()\n', CODE),
        Case("view_reject_distinct_compound_identity", distinct_compound + view("Fixture.extent,2", "2,Fixture.other"), CODE),
        Case("view_reject_missing_compound_root", view("Fixture.extent,2", "2,Fixture.extent"), CODE),
        Case("view_reject_nonpositive_compound", nonpositive_compound + view("Fixture.extent,2", "2,Fixture.extent"), CODE),
        Case("view_reject_ordinary_compound_dependency", ordinary_compound + view("Fixture.extent,2", "2,Fixture.extent"), CODE),
        Case("view_reject_forged_compound_root", forged_compound + view("Fixture.extent,2", "2,Fixture.extent"), CODE),
    ]


def contracts(data):
    text = data.decode()
    strict = re.findall(
        r'"nodal.shape_view"\([^)]*\).*?dimensions = "([^"]+)".*?'
        r'materialization = "view".*?storage = "([^"]+)".*?'
        r'observability = "([^"]+)".*?: \((!nodal\.shaped<[^\n]+>)\) -> '
        r'(!nodal\.shaped<[^\n]+>)', text)
    parameters = re.findall(r'\b(classification|parameter_kind|variability|sym_name) = "([^"]+)"', text)
    ranges = re.findall(r'\b(value = -?[0-9]+ : i64|parameter = @[A-Za-z_0-9]+|effects = \[[^\]]*\])', text)
    origins = re.findall(
        r'\b(source_path|semantic_path|origin|dimensions) = "([^"]+)"', text)
    return Counter(strict), Counter(parameters), Counter(ranges), Counter(origins)


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    return exit_code == 0 and not codes and contracts(data) == contracts(stdout) and bool(contracts(data)[0])


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-view-matrix.v1"))
