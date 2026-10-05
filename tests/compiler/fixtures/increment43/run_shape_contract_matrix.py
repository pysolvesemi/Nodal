#!/usr/bin/env python3
"""Exercise native fixed/direct-symbolic shaped declaration contracts."""

import re

from run_generate_count_matrix import Case, main


CODE = "NODAL-SHAPE-043-001"


def parameter(name="lanes", lower=1, upper=4, classification="structural",
              effects='["shape"]', constraint=True):
    result = f'''"nodal.parameter"() <{{classification = "{classification}", default_value = 2 : i64, metadata = {{}}, parameter_kind = "integer", sym_name = "{name}", type = i64, variability = "symbolic"}}> : () -> ()
'''
    if constraint:
        result += f'''%{name}_lower = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{lower}", value = {lower} : i64}}> : () -> i64
%{name}_upper = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{upper}", value = {upper} : i64}}> : () -> i64
"nodal.parameter_constraint"(%{name}_lower, %{name}_upper) <{{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {{}}, parameter = @{name}}}> : (i64, i64) -> ()
'''
    result += f'''"nodal.parameter_envelope"() <{{effects = {effects}, metadata = {{}}, parameter = @{name}, policy = "static_generate"}}> : () -> ()
'''
    return result


def port(dimensions):
    return f'''"nodal.domain"() <{{edge = "rising", metadata = {{}}, reset_policy = "sync", sym_name = "root"}}> : () -> ()
"nodal.port"() <{{direction = "input", domain = @root, metadata = {{}}, sym_name = "samples", type = !nodal.shaped<"{dimensions}", f64>}}> : () -> ()
'''


def cases():
    return [
        Case("shape_fixed", port("2,3")),
        Case("shape_symbolic", parameter() + port("lanes,2")),
        Case("shape_reject_missing_parameter", port("missing,2"), CODE),
        Case("shape_reject_ordinary_parameter",
             parameter(classification="ordinary") + port("lanes"), CODE),
        Case("shape_reject_missing_shape_envelope",
             parameter(effects='["topology"]') + port("lanes"), CODE),
        Case("shape_reject_zero_capable_range",
             parameter(lower=0) + port("lanes"), CODE),
        Case("shape_reject_missing_range",
             parameter(constraint=False) + port("lanes"), CODE),
    ]


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    return exit_code == 0 and not codes and b"!nodal.shaped" in stdout


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-contract-matrix.v1"))
