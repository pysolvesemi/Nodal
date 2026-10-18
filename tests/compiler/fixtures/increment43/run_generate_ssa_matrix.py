#!/usr/bin/env python3
"""Verify compound generated bounds using the shared strict count runner."""

from __future__ import annotations

import re
from collections import Counter

from run_generate_count_matrix import Case, CODE, generate, main, parameter
from run_generate_count_matrix import valid_output as count_valid_output


def literal(name: str, value: int) -> str:
    return (f'%{name} = "nodal.const_literal"() <{{metadata = {{source_path = "{name}"}}, '
            f'spelling = "{value}", value = {value} : i64}}> : () -> i64\n')


def reference(name: str, symbol: str) -> str:
    return (f'%{name} = "nodal.const_parameter_ref"() <{{metadata = {{source_path = "{name}"}}, '
            f'parameter = @{symbol}}}> : () -> i64\n')


def expression(name: str, operator: str, *operands: str) -> str:
    return (f'%{name} = "nodal.const_expr"({", ".join("%" + v for v in operands)}) '
            f'<{{metadata = {{source_path = "{name}"}}, operator_name = "{operator}"}}> '
            f': ({", ".join("i64" for _ in operands)}) -> i64\n')


def generated(lower="zero", upper="bound", step="one", count=5, **kwargs) -> str:
    legacy = generate("0 : i64", "0 : i64", "1 : i64", f"{count} : i64", **kwargs)
    return legacy.replace('"nodal.generate"()', f'"nodal.generate"(%{lower}, %{upper}, %{step})'
                          ).replace('lower = 0 : i64, upper = 0 : i64, step = 1 : i64, ', ''
                                    ).replace('}) : () -> ()', '}) : (i64, i64, i64) -> ()')


def base() -> str:
    return (parameter("COUNT", 2, 1, 4).replace("si64", "i64") +
            reference("count", "COUNT") + literal("zero", 0) + literal("one", 1) +
            expression("bound", "add", "count", "one"))


def cases() -> list[Case]:
    prefix = base()
    result = [
        Case("ssa_add", prefix + generated()),
        Case("ssa_empty_same_identity", prefix + generated("bound", "bound", count=0)),
        Case("ssa_nested", prefix + generated(body=generated(identity="Fixture.outer.inner"))),
        Case("ssa_shared", prefix + generated() + generated(identity="Fixture.other")),
        Case("ssa_declared_maximum", prefix + generated(maximum="5 : i64")),
        Case("ssa_reject_understated_count", prefix + generated(count=4), CODE),
        Case("ssa_reject_overstated_count", prefix + generated(count=6), CODE),
        Case("ssa_reject_insufficient_maximum", prefix + generated(maximum="4 : i64"), CODE),
        Case("ssa_reject_missing_identity", prefix + generated(captured=False), CODE),
        Case("ssa_reject_empty_nonzero_count", prefix + generated("bound", "bound"), CODE),
        Case("ssa_reject_mixed_form", prefix + generated().replace(
            'induction = "lane"', 'induction = "lane", lower = 0 : i64'), CODE),
        Case("ssa_reject_two_operands", prefix + generated().replace(
            '(%zero, %bound, %one)', '(%zero, %bound)').replace(
            '(i64, i64, i64) -> ()', '(i64, i64) -> ()'), CODE),
        Case("ssa_reject_four_operands", prefix + generated().replace(
            '(%zero, %bound, %one)', '(%zero, %bound, %one, %one)').replace(
            '(i64, i64, i64) -> ()', '(i64, i64, i64, i64) -> ()'), CODE),
        Case("ssa_reject_independent_equal_intervals", prefix +
             parameter("OTHER", 2, 1, 4).replace("si64", "i64") +
             reference("other", "OTHER") + generated("count", "other", count=3),
             "NODAL-ITERATION-043-003"),
        Case("ssa_reject_zero_step", prefix + generated(step="zero"),
             "NODAL-ITERATION-043-002"),
        Case("ssa_reject_negative_step", prefix + literal("negative", -1) +
             generated(step="negative"), "NODAL-ITERATION-043-003"),
        Case("ssa_reject_empty_negative_step", prefix + literal("negative", -1) +
             generated("bound", "bound", "negative", count=0), "NODAL-ITERATION-043-002"),
    ]
    ordinary = prefix.replace('classification = "structural"', 'classification = "ordinary"'
                              ).replace('effects = ["topology"]', 'effects = []'
                                        ).replace('policy = "static_generate"', 'policy = "fixed_topology"')
    result.append(Case("ssa_reject_ordinary_dependency", ordinary + generated(),
                       "NODAL-PARAMETER-STRUCTURAL-001"))
    dynamic = ('%dynamic = "nodal.dynamic_value"(%count) '
               '<{metadata = {}, origin = "runtime"}> : (i64) -> i64\n')
    result.append(Case("ssa_reject_dynamic_bound", prefix + dynamic +
                       generated(upper="dynamic", count=4), CODE))
    for operator, operands, lower, upper, expected in [
        ("sub", ("count", "one"), "zero", "value", 3),
        ("mul", ("count", "count"), "zero", "value", 16),
        ("div", ("count", "two"), "zero", "value", 2),
        ("mod", ("count", "two"), "zero", "value", 1),
        ("neg", ("count",), "value", "zero", 4),
        ("sub", ("count", "count"), "zero", "value", 0),
    ]:
        result.append(Case("ssa_" + operator + "_" + "_".join(operands),
                           prefix + literal("two", 2) +
                           expression("value", operator, *operands) +
                           generated(lower, upper, count=expected)))
    for operator in ("div", "mod"):
        result.append(Case("ssa_reject_possible_zero_" + operator, prefix +
                           expression("divisor", "sub", "count", "one") +
                           expression("value", operator, "count", "divisor") +
                           generated(upper="value"), CODE))
    result.append(Case("ssa_reject_overflow", prefix + literal("huge", 9223372036854775807) +
                       expression("value", "add", "huge", "one") +
                       generated(upper="value"), CODE))
    result.append(Case("ssa_reject_negative_division_overflow", prefix +
                       literal("minimum", -9223372036854775808) + literal("negative", -1) +
                       expression("value", "div", "minimum", "negative") +
                       generated(upper="value"), CODE))
    result.append(Case("ssa_signed_division", prefix + literal("two", 2) +
                       expression("negative", "neg", "count") +
                       expression("value", "div", "negative", "two") +
                       generated("value", "zero", count=2)))
    for depth, code in ((200, None), (600, CODE)):
        chain = literal("chain0", 0)
        for index in range(1, depth + 1):
            chain += expression(f"chain{index}", "add", f"chain{index - 1}", f"chain{index - 1}")
        result.append(Case(f"ssa_shared_depth_{depth}", literal("zero", 0) +
                           literal("one", 1) + chain +
                           generated(upper=f"chain{depth}", count=0), code))
    # Fixed declarations must retain their real dependency DAG, never just their
    # default or even their independently declared (misleading) range.
    fixed = parameter("FIXED", 2, 0, 4).replace("si64", "i64").replace(
        'variability = "symbolic"', 'variability = "fixed"').replace(
        'classification = "structural"', 'classification = "ordinary"').replace(
        'effects = ["topology"]', 'effects = []').replace(
        'policy = "static_generate"', 'policy = "fixed_topology"')
    fixed += ('"nodal.parameter_value"(%count) <{metadata = {}, parameter = @FIXED}> '
              ': (i64) -> ()\n' + reference("fixed", "FIXED"))
    result += [
        Case("ssa_fixed_transitive", prefix + fixed + generated(upper="fixed", count=4)),
        Case("ssa_reject_fixed_ordinary_dependency", ordinary + fixed +
             generated(upper="fixed", count=4), "NODAL-PARAMETER-STRUCTURAL-001"),
    ]
    return result


def graph_contract(data: bytes) -> tuple:
    """Capture source-identified nodes and ordered edges, ignoring SSA renames."""
    definitions = {}
    nodes = []
    generates = []
    text = data.decode()
    pattern = re.compile(r'(%[A-Za-z_0-9]+)\s*=\s*"(nodal\.const_[a-z_]+)"\(([^)]*)\)'
                         r'\s*<\{(.*?)\}>\s*:\s*\(([^)]*)\)\s*->\s*([a-z]+[0-9]+)')
    for match in pattern.finditer(text):
        name, operation, operands, attrs, types, result = match.groups()
        identity = re.search(r'source_path\s*=\s*"([^"]+)"', attrs)
        # Inherited parameter-constraint literals lack source_path. Retain their
        # literal value as identity; fixture DAG nodes all have unique paths.
        value = re.search(r'\bvalue\s*=\s*(-?\d+\s*:\s*[us]?i\d+)', attrs)
        path = identity.group(1) if identity else "literal:" + value.group(1)
        if name in definitions:
            raise ValueError("duplicate SSA definition")
        definitions[name] = path
        operator = re.search(r'operator_name\s*=\s*"([^"]+)"', attrs)
        parameter_ref = re.search(r'\bparameter\s*=\s*(@\w+)', attrs)
        nodes.append((path, operation, tuple(operands.split(",") if operands else []),
                      operator.group(1) if operator else None,
                      parameter_ref.group(1) if parameter_ref else None,
                      re.sub(r"\s+", "", value.group(1)) if value else None,
                      re.sub(r"\s+", "", types), result))
    def edges(operands):
        return tuple(definitions[operand.strip()] for operand in operands)
    normalized = Counter((p, op, edges(args), operator, param, value, types, result)
                         for p, op, args, operator, param, value, types, result in nodes)
    for match in re.finditer(r'"nodal.generate"\(([^)]*)\)\s*<\{(.*?)\}>', text):
        operands, attrs = match.groups()
        identity = re.search(r'region_id\s*=\s*"([^"]+)"', attrs)
        generates.append((identity.group(1), edges(operands.split(","))))
    if not generates:
        raise ValueError("missing generated region")
    return normalized, Counter(generates)


def valid_output(case, data, exit_code, stdout, stderr):
    if not count_valid_output(case, data, exit_code, stdout, stderr):
        return False
    if case.code is not None:
        return True
    try:
        return graph_contract(data) == graph_contract(stdout)
    except (KeyError, ValueError, AttributeError, UnicodeError):
        return False


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.generate-ssa-matrix.v1"))
