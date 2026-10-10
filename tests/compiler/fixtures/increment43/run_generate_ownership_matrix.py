#!/usr/bin/env python3
"""Independent native ownership for captured generation, using the strict runner."""

from collections import Counter
import re

from run_generate_count_matrix import Case, generate, main
from run_generate_count_matrix import valid_output as count_valid_output

CODE = "NODAL-ITERATION-043-004"


def region(identity="Fixture.outer", body="", captured=True):
    return generate("0 : i64", "2 : i64", "1 : i64",
                    "2 : i64" if captured else None,
                    identity=identity, body=body, captured=captured)


def node(owner="Fixture.outer", induction="Fixture.outer.index", name="tap"):
    return (f'%{name} = "nodal.node"() <{{name = "{name}", metadata = {{}}, '
            f'generated_owner = "{owner}", generated_induction = "{induction}"}}> '
            ': () -> !nodal.terminal<"electrical">\n')


def hardware_loop(body):
    return ('"nodal.hardware_loop"() <{induction = "i", lower = 0 : i64, '
            'upper = 2 : i64, step = 1 : i64, effect_policy = "pure", metadata = {}}> '
            '({\n^bb0:\n' + body + '}) : () -> ()\n')


def cases():
    first = region()
    other = region("Fixture.other")
    nested = region(body=region("Fixture.outer.inner"))
    legacy = region(captured=False)
    result = [
        Case("ownership_empty_body", first),
        Case("ownership_siblings", first + other),
        Case("ownership_nested", nested),
        Case("ownership_node", region(body=node())),
        Case("ownership_nested_node", region(body=region("Fixture.outer.inner", body=node(
            "Fixture.outer.inner", "Fixture.outer.inner.index")))),
        Case("ownership_legacy_siblings", legacy + legacy),
        Case("ownership_legacy_nested", region(body=legacy, captured=False)),
        Case("ownership_legacy_hardware_context", hardware_loop(legacy)),
        # The same semantic strings in an independent module do not alias.
        Case("ownership_independent_module", first +
             '"nodal.module"() <{sym_name = "Independent", metadata = {}}> '
             '({\n^bb0:\n' + first + '}) : () -> ()\n'),
        Case("ownership_reject_duplicate_region", first + other.replace(
            'region_id = "Fixture.other"', 'region_id = "Fixture.outer"'), CODE),
        Case("ownership_reject_duplicate_induction", first + other.replace(
            'induction_path = "Fixture.other.index"',
            'induction_path = "Fixture.outer.index"'), CODE),
        Case("ownership_reject_nested_duplicate_region", region(body=first), CODE),
        Case("ownership_reject_nested_duplicate_induction", nested.replace(
            'induction_path = "Fixture.outer.inner.index"',
            'induction_path = "Fixture.outer.index"'), CODE),
        Case("ownership_reject_wrong_parent", region(body=region("Fixture.unrelated")), CODE),
        Case("ownership_reject_prefix_collision", region(body=region("Fixture.outermost")), CODE),
        Case("ownership_reject_under_legacy", region(body=first, captured=False), CODE),
        Case("ownership_reject_under_hardware_loop", hardware_loop(first), CODE),
        Case("ownership_reject_erased_nested_contract", region(body=legacy), CODE),
        Case("ownership_reject_node_wrong_owner", region(body=node(owner="Fixture.other")), CODE),
        Case("ownership_reject_node_wrong_induction", region(body=node(induction="other")), CODE),
        Case("ownership_reject_detached_node", node(), CODE),
        Case("ownership_reject_partial_node_contract", node().replace(
            ', generated_induction = "Fixture.outer.index"', ''), CODE),
    ]
    for attribute, original in (("region_id", "Fixture.outer"),
                                ("induction_path", "Fixture.outer.index")):
        for suffix, value in (("empty", ""), ("space", " "),
                              ("leading", " " + original), ("trailing", original + " ")):
            result.append(Case(f"ownership_reject_{attribute}_{suffix}", first.replace(
                f'{attribute} = "{original}"', f'{attribute} = "{value}"'), CODE))
    result.append(Case("ownership_many_siblings", "".join(
        region(f"Fixture.region_{i}") for i in range(256))))
    return result


def node_contracts(data):
    return Counter(re.findall(rb'\b(generated_owner|generated_induction|name)\s*=\s*'
                              rb'("[^"\n]*")', data))


def valid_output(case, data, exit_code, stdout, stderr):
    if not count_valid_output(case, data, exit_code, stdout, stderr):
        return False
    return case.code is not None or (
        stdout.count(b'"nodal.node"') == data.count(b'"nodal.node"') and
        stdout.count(b'"nodal.module"') == data.count(b'"nodal.module"') and
        node_contracts(stdout) == node_contracts(data))


if __name__ == "__main__":
    raise SystemExit(main(cases, valid_output, "nodal.f043.generate-ownership-matrix.v1"))
