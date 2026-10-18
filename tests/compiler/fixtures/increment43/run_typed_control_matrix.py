#!/usr/bin/env python3
"""Focused live storage references at actual control/event evaluation sites."""

import argparse
import json
import re
import subprocess
from pathlib import Path

from run_writable_array_matrix import (
    NativeSource, Q, V, assign, generated_source, idx, parameter, read,
    shape, slice_value, var, view, wrap,
)

BINDING = ["NODAL-ANALOG-037-010"]
UNINITIALIZED = ["NODAL-ANALOG-034-004"]
EVENT_READS = ["NODAL-ANALOG-037-005"]


def bindings(references, types=None):
    operands = ",".join("%" + name for name in references)
    types = ",".join(types or [V] * len(references))
    inventory = ",".join('"T.' + name + '"' for name in references)
    return operands, types, inventory


def arm(name, source, references, body, *, form="storage", stage="runtime", types=None):
    operands, types, inventory = bindings(references, types)
    form_attr = "" if form is None else f'reference_form="{form}",'
    return (
        f'"nodal.analog_if_arm"({operands}) <{{arm_id="T.{name}",owner="T",'
        f'{form_attr}is_else=false,stage="{stage}",condition_value="{source}",'
        f'condition_kind="boolean",condition_dimension="1",condition_reads=[{inventory}],'
        f'static_value_present={str(stage == "static").lower()},'
        f'static_value={str(stage == "static" and source == "true").lower()},metadata={{}}}}> ({{\n'
        + body + f'}}) : ({types})->()\n'
    )


def conditional(arms, name="if"):
    return (
        f'"nodal.analog_if"() <{{statement_id="T.{name}",owner="T",metadata={{}}}}> ({{\n'
        + arms + '}) : ()->()\n'
    )


def loop(source, references, body):
    operands, types, inventory = bindings(references)
    return (
        f'"nodal.analog_loop"({operands}) <{{statement_id="T.loop",owner="T",'
        'reference_form="storage",stage="runtime",minimum_iterations=0:i64,'
        f'maximum_iterations=2:i64,bound_value="{source}",bound_kind="integer",'
        f'bound_dimension="1",bound_reads=[{inventory}],static_trip_count_present=false,'
        'static_trip_count=0:i64,metadata={}}> ({\n'
        + body + f'}}) : ({types})->()\n'
    )


def selection(source, references, body):
    operands, types, inventory = bindings(references)
    return (
        f'"nodal.analog_case"({operands}) <{{statement_id="T.case",owner="T",'
        f'reference_form="storage",selector_value="{source}",selector_kind="boolean",'
        f'selector_dimension="1",selector_reads=[{inventory}],static_value_present=false,'
        'static_value="",metadata={}}> ({\n'
        '"nodal.analog_case_arm"() <{arm_id="T.caseTrue",owner="T",is_default=false,'
        'labels=["boolean:true"],metadata={}}> ({\n' + body
        + '}) : ()->()\n' + f'}}) : ({types})->()\n'
    )


def monitor(name, kind, source, references, *, dimension="1", types=None):
    operands, types, inventory = bindings(references, types)
    arguments = (
        f'{{slot=0:i64,value="{source}",kind="real",dimension="{dimension}",'
        f'reads=[{inventory}]}}'
    )
    return (
        f'%{name}="nodal.analog_{kind}"({operands}) <{{event_id="T.{name}",owner="T",'
        f'reference_form="storage",contract="increment37",name="",arguments=[{arguments}],'
        f'analyses=[],event_reads=[{inventory}],metadata={{}}}}> : ({types})->!nodal.analog_event\n'
    )


def on(event, body):
    return (
        f'"nodal.analog_on"(%{event}) <{{statement_id="T.on",owner="T",metadata={{}}}}> ({{\n'
        + body + '}) : (!nodal.analog_event)->()\n'
    )


def scalar(name, kind="real", value="1.0", order=1):
    leaf = f'!nodal.variable<"{kind}", "1">'
    return (
        var(name, "2", order).replace(shape("2"), leaf)
        .replace("initialized=false", "initialized=true")
        .replace('initializer_value=""', f'initializer_value="{value}"')
        .replace('initializer_kind=""', f'initializer_kind="{kind}"')
        .replace('initializer_dimension=""', 'initializer_dimension="1"')
    )


def cases():
    roots = var("a", "2", 0) + idx("a0", "a", "2", 0) + idx("a1", "a", "2", 1)
    initialized = roots + assign("initialize", "a0", V, 0)
    body = assign("body", "a0", V, 1)
    predicate = "real_gt(T.a0,0.0)"
    control = conditional(arm("condition", predicate, ["a0"], body))
    yield "selected_condition", initialized + control, []
    yield "selected_boolean_case", initialized + selection(predicate, ["a0"], body), []
    bound = "analog_select(real_gt(T.a0,0.0),1,2)"
    yield "native_selected_integer_bound", initialized + loop(bound, ["a0"], body), []
    yield "zero_reference_literal", initialized + conditional(arm("literal", "true", [], body)), []

    both = initialized + assign("initializeSibling", "a1", V, 1)
    paired = conditional(arm("condition", "real_gt(T.a0,T.a1)", ["a0", "a1"],
                             assign("body", "a0", V, 2)))
    yield "two_distinct_selected_leaves", both + paired, []
    alias = idx("alias", "a", "2", 0)
    yield "equal_physical_distinct_provenance", initialized + alias + conditional(
        arm("condition", "real_ge(T.a0,T.alias)", ["a0", "alias"], body)), []
    flag = '!nodal.variable<"boolean", "1">'
    mixed = arm("condition", "bool_and(T.flag,real_gt(T.a0,0.0))", ["flag", "a0"],
                body, types=[flag, V])
    yield "mixed_scalar_selected", initialized + scalar("flag", "boolean", "true") + conditional(mixed), []
    yield "mixed_missing_scalar_binding", initialized + scalar("flag", "boolean", "true") + conditional(
        arm("condition", "bool_and(T.flag,real_gt(T.a0,0.0))", ["a0"], body)), BINDING
    yield "missing_binding", initialized + conditional(arm("condition", predicate, [], body)), BINDING
    yield "duplicate_binding", initialized + conditional(
        arm("condition", predicate, ["a0", "a0"], body)), BINDING
    yield "unused_binding", both + conditional(arm("condition", predicate, ["a0", "a1"],
                                                   assign("body", "a0", V, 2))), BINDING
    yield "wrong_source_binding", initialized + control.replace(predicate, "real_gt(T.a1,0.0)"), BINDING
    yield "forged_read_inventory", initialized + control.replace('condition_reads=["T.a0"]',
                                                                 'condition_reads=[]'), BINDING
    yield "absent_form_with_operands", initialized + control.replace('reference_form="storage",', ''), ["NODAL-ANALOG-034-014"]
    yield "unknown_form", initialized + control.replace('reference_form="storage"',
                                                         'reference_form="snapshot"'), BINDING
    yield "wrong_units", initialized + control.replace(predicate, "real_gt(T.a0,0.0 V)"), BINDING
    yield "snapshot_is_not_live_handle", initialized + read("snapshot", "a0", V, Q) + conditional(
        arm("condition", "real_gt(T.snapshot,0.0)", ["snapshot"], body, types=[Q])), BINDING
    yield "bare_array_is_not_scalar_handle", initialized + conditional(
        arm("condition", "real_gt(T.a,0.0)", ["a"], body, types=[shape("2")])), BINDING
    yield "static_control_with_reference", initialized + conditional(
        arm("condition", "true", ["a0"], body, stage="static")), BINDING
    yield "uninitialized_selected_condition", roots + conditional(
        arm("condition", predicate, ["a0"], assign("body", "a0", V, 0))), UNINITIALIZED
    yield "uninitialized_selected_sibling", initialized + conditional(
        arm("condition", "real_gt(T.a1,0.0)", ["a1"], body)), UNINITIALIZED
    yield "loop_body_cannot_initialize_bound", roots + loop(bound, ["a0"],
                                                           assign("body", "a0", V, 0)), UNINITIALIZED
    first = arm("first", "true", [], body, form=None, stage="static")
    second = arm("second", "real_gt(T.a1,0.0)", ["a1"], assign("later", "a0", V, 2))
    yield "statically_skipped_arm_has_no_read", initialized + conditional(first + second), []
    first = arm("first", predicate, ["a0"], assign("firstWrite", "a1", V, 1))
    yield "earlier_arm_write_cannot_initialize_later_condition", initialized + conditional(first + second), UNINITIALIZED

    event = monitor("event", "cross", "T.a0", ["a0"])
    yield "live_cross", initialized + event + on("event", body), []
    yield "live_unused_above", initialized + monitor("event", "above", "T.a0", ["a0"]), []
    event2 = monitor("event2", "above", "T.a0", ["a0"])
    event_or = (
        '%either="nodal.analog_event_or"(%event,%event2) <{event_id="T.either",owner="T",'
        'contract="increment37",name="",metadata={}}> : '
        '(!nodal.analog_event,!nodal.analog_event)->!nodal.analog_event\n'
    )
    yield "live_event_or", initialized + event + event2 + event_or + on("either", body), []
    yield "same_root_write_barrier", initialized + event + assign("intervening", "a1", V, 1) + on(
        "event", assign("body", "a0", V, 2)), EVENT_READS
    yield "independent_root_write_allowed", initialized + scalar("b") + event + assign(
        "intervening", "b", V, 1) + on("event", assign("body", "a0", V, 2)), []
    yield "event_argument_inventory_forgery", initialized + event.replace('reads=["T.a0"]}',
                                                                         'reads=[]}') + on("event", body), EVENT_READS
    yield "event_union_inventory_forgery", initialized + event.replace('event_reads=["T.a0"]',
                                                                       'event_reads=[]') + on("event", body), EVENT_READS
    extra_event = monitor("event", "cross", "T.a0", ["a0", "a1"]).replace(
        '["T.a0","T.a1"]', '["T.a0"]')
    yield "event_unused_reference", both + extra_event + on(
        "event", assign("body", "a0", V, 2)), EVENT_READS
    yield "event_uninitialized_selection", roots + event + on("event", assign("body", "a0", V, 0)), UNINITIALIZED
    event_write = on("event", assign("eventWrite", "a1", V, 1))
    yield "event_write_does_not_initialize_later_control", initialized + event + event_write + conditional(
        arm("condition", "real_gt(T.a1,0.0)", ["a1"], assign("body", "a0", V, 2))), UNINITIALIZED
    time_variable = V.replace('"1"', '"time"')
    time_initialized = initialized.replace(V, time_variable).replace('value_dimension="1"',
                                                                   'value_dimension="time"').replace(
                                                                       'value="1.0"', 'value="1.0 s"')
    yield "live_timer_time_quantity", time_initialized + monitor(
        "event", "timer", "T.a0", ["a0"], dimension="time", types=[time_variable]), []

    yield "symbolic_shape_selected_condition", (initialized + control).replace('"2",', '"COUNT",'), [], parameter(
        name="COUNT", lower=2, upper=4)
    mapped = (var("a", "3,2", 0) + slice_value("column", "a", "3,2", "3,1", 0, 1, axis=1)
              + view("flat", "column", "3,1", "3") + idx("selected", "flat", "3", 1)
              + assign("initialize", "selected", V, 0))
    yield "noncontiguous_selected_condition", mapped + conditional(arm(
        "condition", "real_gt(T.selected,0.0)", ["selected"], assign("body", "selected", V, 1))), []
    yield "generated_selected_condition", generated_source(initialized + control), []
    malformed = generated_source(initialized + control).replace(
        'generated_induction="T.generated.index",reference_form="storage",',
        'generated_induction="T.foreign",reference_form="storage",', 1)
    yield "generated_reference_owner_forgery", NativeSource(malformed), [
        "NODAL-ANALOG-037-010", "NODAL-ITERATION-043-004"]

    forward = conditional(arm("condition", "real_gt(T.later,0.0)", ["later"], body))
    yield "non_dominating_reference", initialized + forward + idx("later", "a", "2", 0), [
        "NODAL-ANALOG-033-019", "NODAL-ANALOG-037-010"]
    missing_pair = generated_source(initialized + control).replace(
        'generated_induction="T.generated.index",reference_form="storage",',
        'reference_form="storage",', 1)
    yield "generated_reference_pair_missing", NativeSource(missing_pair), [
        "NODAL-ANALOG-037-010", "NODAL-ITERATION-043-004"]
    lifecycle = (
        '%event="nodal.analog_initial_step"() <{event_id="T.event",owner="T",'
        'reference_form="storage",contract="increment37",name="",arguments=[],'
        'analyses=[],event_reads=[],metadata={}}> : ()->!nodal.analog_event\n')
    yield "lifecycle_rejects_storage_form", initialized + lifecycle + on("event", body), BINDING
    otherwise = arm("else", "", [], assign("elseBody", "a0", V, 2)).replace(
        'is_else=false,stage="runtime"', 'is_else=true,stage="else"').replace(
        'condition_kind="boolean",condition_dimension="1"',
        'condition_kind="",condition_dimension=""')
    yield "else_rejects_storage_form", initialized + conditional(
        arm("first", "false", [], body, form=None, stage="static") + otherwise), BINDING
    direction = ('{slot=1:i64,value="analog_select(real_gt(T.a0,0.0),1,0)",'
                 'kind="integer",dimension="1",reads=["T.a0"]}')
    direction_event = event.replace('}],analyses=', '},' + direction + '],analyses=', 1)
    yield "mutable_cross_direction_rejected", initialized + direction_event + on("event", body), [
        "NODAL-ANALOG-037-004"]
    yield "event_or_same_root_write_barrier", initialized + event + event2 + event_or + assign(
        "intervening", "a1", V, 1) + on("either", assign("body", "a0", V, 2)), EVENT_READS
    yield "generated_live_event", generated_source(initialized + event + on("event", body)), []
    reused = initialized + control + assign("intervening", "a0", V, 2) + conditional(
        arm("conditionLater", predicate, ["a0"], assign("bodyLater", "a0", V, 3)), "ifLater")
    yield "same_handle_reused_after_write", reused, []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--nodalc", required=True)
    parser.add_argument("--work-dir", required=True)
    args = parser.parse_args()
    directory = Path(args.work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    for name, body, expected, *declarations in cases():
        source = str(body) if isinstance(body, NativeSource) else wrap(body, "".join(declarations))
        path = directory / (name + ".mlir")
        path.write_text(source)
        run = subprocess.run([args.nodalc, str(path)], capture_output=True, text=True, timeout=30)
        (directory / (name + ".stdout")).write_text(run.stdout)
        (directory / (name + ".stderr")).write_text(run.stderr)
        codes = sorted(set(re.findall(r"NODAL-[A-Z0-9-]+", run.stderr)))
        passed = run.returncode == (1 if expected else 0) and codes == expected
        if passed and not expected:
            replay = directory / (name + ".roundtrip.mlir")
            replay.write_text(run.stdout)
            repeated = subprocess.run([args.nodalc, str(replay)], capture_output=True, text=True, timeout=30)
            gated = subprocess.run([args.nodalc, "--pass-pipeline=builtin.module(nodal-gate-default)",
                                    str(path)], capture_output=True, text=True, timeout=30)
            (directory / (name + ".gate.stderr")).write_text(gated.stderr)
            passed = repeated.returncode == 0 and repeated.stdout == run.stdout and gated.returncode == 0
            if gated.returncode:
                print(gated.stderr)
        results.append({"name": name, "passed": passed, "expected": expected,
                        "exit": run.returncode, "codes": codes})
        print(("PASS" if passed else "FAIL") + " " + name)
        if not passed:
            print(run.stderr)
    (directory / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f'{sum(item["passed"] for item in results)}/{len(results)} typed-control checks passed')
    return 0 if all(item["passed"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
