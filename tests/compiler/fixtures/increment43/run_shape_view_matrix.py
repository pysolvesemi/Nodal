#!/usr/bin/env python3
"""Native fixed reshape proofs; these are not generated-target witnesses."""

import re
from collections import Counter

from run_generate_count_matrix import Case, main


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
    return [
        Case("view_rank_preserving", view()),
        Case("view_rank_reducing", view("2,2", "4")),
        Case("view_rank_expanding", view("6", "1,2,3")),
        Case("view_singleton", view("1", "1,1")),
        Case("view_reject_count", view("2,3", "2,2"), CODE),
        Case("view_reject_symbolic_source", view("lanes,2", "2,2"), CODE),
        Case("view_reject_symbolic_result", view("2,2", "lanes,2"), CODE),
        Case("view_reject_dimensions_attr", view(dimensions="2,3"), CODE),
        Case("view_reject_element", view(result_element="i64"), CODE),
        Case("view_reject_observability", view(observability="hidden"), CODE),
        Case("view_reject_storage", view(storage="memory"), CODE),
        Case("view_reject_overflow", view("9223372036854775807,2", "2,9223372036854775807"), CODE),
    ]


def contracts(data):
    text = data.decode()
    strict = re.findall(
        r'"nodal.shape_view"\([^)]*\).*?dimensions = "([^"]+)".*?'
        r'materialization = "view".*?storage = "([^"]+)".*?'
        r'observability = "([^"]+)".*?: \((!nodal\.shaped<[^\n]+>)\) -> '
        r'(!nodal\.shaped<[^\n]+>)', text)
    return Counter(strict)


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    return exit_code == 0 and not codes and contracts(data) == contracts(stdout) and bool(contracts(data))


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-view-matrix.v1"))
