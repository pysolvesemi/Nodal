#!/usr/bin/env python3
"""Exercise native fixed/direct-symbolic shaped declaration contracts."""

import re
from collections import Counter

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


def reference(name="lanes_ref", symbol="lanes"):
    return (f'%{name} = "nodal.const_parameter_ref"() <{{metadata = {{}}, '
            f'parameter = @{symbol}}}> : () -> i64\n')


def literal(name="one", value=1):
    return (f'%{name} = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{value}", '
            f'value = {value} : i64}}> : () -> i64\n')


def expression(name="extent", operator="add", operands=("lanes_ref", "one"),
               source_path="Fixture.extent"):
    return (f'%{name} = "nodal.const_expr"({", ".join("%" + value for value in operands)}) '
            f'<{{metadata = {{source_path = "{source_path}"}}, operator_name = "{operator}"}}> : '
            f'({", ".join("i64" for _ in operands)}) -> i64\n')


def cases():
    compound = parameter() + reference() + literal() + expression()
    ordinary = compound.replace('classification = "structural"',
                                'classification = "ordinary"')
    nonpositive = (parameter(lower=1, upper=4) + reference() + literal() +
                   expression(operator="sub"))
    wrong_type = '''%extent = "nodal.const_literal"() <{metadata = {source_path = "Fixture.extent"}, spelling = "2.0", value = 2.0 : f64}> : () -> f64
'''
    forged_reference = parameter() + '''%extent = "nodal.const_parameter_ref"() <{metadata = {source_path = "Fixture.extent"}, parameter = @lanes}> : () -> i64
'''
    return [
        Case("shape_fixed", port("2,3")),
        Case("shape_symbolic", parameter() + port("lanes,2")),
        Case("shape_compound", compound + port("Fixture.extent,2")),
        Case("shape_compound_repeated_axes", compound + port(
            ",".join(["Fixture.extent"] * 64))),
        Case("shape_reject_missing_parameter", port("missing,2"), CODE),
        Case("shape_reject_ordinary_parameter",
             parameter(classification="ordinary") + port("lanes"), CODE),
        Case("shape_reject_missing_shape_envelope",
             parameter(effects='["topology"]') + port("lanes"), CODE),
        Case("shape_reject_zero_capable_range",
             parameter(lower=0) + port("lanes"), CODE),
        Case("shape_reject_missing_range",
             parameter(constraint=False) + port("lanes"), CODE),
        Case("shape_reject_missing_compound_value", port("Fixture.extent"), CODE),
        Case("shape_reject_nonpositive_compound",
             nonpositive + port("Fixture.extent"), CODE),
        Case("shape_reject_ordinary_compound_dependency",
             ordinary + port("Fixture.extent"), CODE),
        Case("shape_reject_compound_wrong_type",
             wrong_type + port("Fixture.extent"), CODE),
        Case("shape_reject_direct_reference_as_compound_root",
             forged_reference + port("Fixture.extent"), CODE),
    ]


def contracts(data):
    text = data.decode()
    return (
        Counter(re.findall(r'"(nodal\.(?:port|const_[a-z_]+))"\(', text)),
        Counter(re.findall(r'!nodal\.shaped<"([^"]+)"', text)),
        Counter(re.findall(r'operator_name\s*=\s*"([^"]+)"', text)),
        Counter(re.findall(r'parameter\s*=\s*@([A-Za-z_][A-Za-z0-9_]*)', text)),
        Counter(re.findall(r'source_path\s*=\s*"([^"]+)"', text)),
        Counter(re.findall(r'spelling\s*=\s*"(-?\d+)"', text)),
        Counter(re.findall(r'classification\s*=\s*"([^"]+)"', text)),
        Counter(re.findall(r'effects\s*=\s*\[([^]]*)\]', text)),
    )


def valid_output(case, data, exit_code, stdout, stderr):
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    return (exit_code == 0 and not codes and b"!nodal.shaped" in stdout
            and contracts(data) == contracts(stdout))


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.shape-contract-matrix.v1"))
