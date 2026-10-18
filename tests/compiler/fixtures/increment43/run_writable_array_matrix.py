#!/usr/bin/env python3
"""Focused actual-SSA storage, immutable snapshot and initialization checks."""

import argparse
import json
import re
import subprocess
from pathlib import Path

from run_shape_contract_matrix import expression, literal, parameter, reference

V = '!nodal.variable<"real", "1">'
Q = '!nodal.quantity<"real", "1">'


def shape(dimensions, leaf=V):
    return f'!nodal.shaped<"{dimensions}", {leaf}>'


def var(name, dimensions, order):
    return (
        f'%{name} = "nodal.analog_variable"() <{{identity="T.{name}", '
        f'owner="T", declaration_order={order}:i64, initialized=false, '
        'initializer_value="", initializer_kind="", initializer_dimension="", '
        f'initializer_reads=[], metadata={{semantic_path="T.{name}"}}}}> '
        f': () -> {shape(dimensions)}\n'
    )


def idx(name, base, dimensions, index, result="", leaf=V):
    storage, access = ("procedural", "read_write") if leaf == V else ("structural", "read_only")
    return (
        f'%{name} = "nodal.shape_index"(%{base}, %c{index}) <{{owner="T", '
        f'metadata={{semantic_path="T.{name}", input_path="T.{base}",'
        f'storage="{storage}",access="{access}"}}}}> '
        f': ({shape(dimensions, leaf)},i64) -> {shape(result, leaf) if result else leaf}\n'
    )


def assign(name, target, target_type, order, rhs="1.0", reads="", types="", snapshot=False):
    metadata = "{}" if snapshot else '{value="' + rhs + '"}'
    form = ',value_form="snapshot"' if snapshot else ""
    return (
        f'"nodal.analog_assign"(%{target}{reads}) <{{statement_id="T.{name}",'
        f'owner="T",authored_order={order}:i64,value_kind="real",value_dimension="1",'
        'analyses=["dc","transient"],guard_present=false,guard_value="",guard_kind="",'
        f'guard_dimension="",guard_reads=[],metadata={metadata}{form}}}> '
        f': ({target_type}{types}) -> ()\n'
    )


def read(name, base, source_type, result_type):
    return (
        f'%{name}="nodal.analog_variable_read"(%{base}) <{{owner="T",'
        f'read_id="T.{name}",metadata={{semantic_path="T.{name}"}}}}> '
        f': ({source_type})->{result_type}\n'
    )


def fill(name, dimensions, value="2.0", reads="", types=""):
    return (
        f'%{name}="nodal.analog_array_fill"({reads}) <{{owner="T",value="{value}",'
        f'metadata={{semantic_path="T.{name}"}}}}> : ({types}) -> {shape(dimensions, Q)}\n'
    )


def wrap(body, declarations=""):
    constants = "".join(
        f'%c{i}="nodal.const_literal"() <{{spelling="{i}",value={i}:i64,metadata={{}}}}> '
        ': ()->i64\n' for i in range(4)
    )
    return (
        'module {\n"nodal.module"() <{sym_name="T",metadata={semantic_path="T"}}> ({\n'
        + declarations + constants + '"nodal.analog"() <{metadata={}}> ({\n'
        '"nodal.analog_procedure"() <{owner="T",metadata={}}> ({\n'
        + body + '}) : ()->()\n}) : ()->()\n}) : ()->()\n}\n'
    )


def scope(body):
    return (
        '"nodal.analog_scope"() <{owner="T",scope_id="T.scope",metadata={}}> ({\n'
        + body + '}) : ()->()\n'
    )


def view(name, base, source, target, leaf=V):
    storage, access = ("procedural", "read_write") if leaf == V else ("structural", "read_only")
    return (
        f'%{name}="nodal.shape_view"(%{base}) <{{owner="T",dimensions="{target}",'
        f'origin="T.{base}",materialization="view",observability="source_mapped",'
        f'metadata={{semantic_path="T.{name}",input_path="T.{base}",'
        f'storage="{storage}",access="{access}"}}}}> '
        f': ({shape(source, leaf)})->{shape(target, leaf)}\n'
    )


def slice_value(name, base, source, target, lower, upper, axis=0, leaf=V):
    storage, access = ("procedural", "read_write") if leaf == V else ("structural", "read_only")
    return (
        f'%{name}="nodal.shape_slice"(%{base},%c{lower},%c{upper},%c{upper-lower}) '
        f'<{{owner="T",axis={axis}:i64,dimensions="{target}",origin="T.{base}",'
        f'observability="source_mapped",metadata={{semantic_path="T.{name}",'
        f'input_path="T.{base}",storage="{storage}",access="{access}"}}}}> '
        f': ({shape(source, leaf)},i64,i64,i64)->{shape(target, leaf)}\n'
    )


def branch(left, right):
    first = (
        '"nodal.analog_if_arm"() <{arm_id="T.then",owner="T",is_else=false,'
        'stage="runtime",condition_value="true",condition_kind="boolean",'
        'condition_dimension="1",condition_reads=[],static_value_present=false,'
        'static_value=false,metadata={}}> ({\n' + left + '}) : ()->()\n'
    )
    second = (
        '"nodal.analog_if_arm"() <{arm_id="T.else",owner="T",is_else=true,'
        'stage="else",condition_value="",condition_kind="",condition_dimension="",'
        'condition_reads=[],static_value_present=false,static_value=false,metadata={}}> ({\n'
        + right + '}) : ()->()\n'
    )
    return (
        '"nodal.analog_if"() <{statement_id="T.if",owner="T",metadata={}}> ({\n'
        + first + second + '}) : ()->()\n'
    )


class NativeSource(str):
    """An already wrapped module for generated lexical-owner controls."""


def generated_source(body):
    source = wrap(body)
    ownership = 'generated_owner="T.generated",generated_induction="T.generated.index",'
    source = source.replace('owner="T",', 'owner="T",' + ownership)
    source = source.replace(
        '"nodal.analog"() <{metadata={}}> ({',
        '"nodal.generate"() <{induction="g",lower=0:i64,upper=2:i64,step=1:i64,'
        'region_id="T.generated",induction_path="T.generated.index",'
        'metadata={maximum_trip_count=2:i64}}> ({\n'
        '"nodal.analog"() <{' + ownership + 'metadata={}}> ({')
    source = source[:-2] + '}) : ()->()\n}\n'
    return NativeSource(source)


def initializer_expression(name, source, captures=(), kind="real", dimension="1",
                           destination_kind=None, order=1):
    """An explicit immutable-capture initializer, evaluated at local entry."""
    operands = ",".join("%" + name for name, _ in captures)
    types = ",".join(value_type for _, value_type in captures)
    destination_kind = destination_kind or kind
    return (
        f'%{name} = "nodal.analog_variable"({operands}) <{{identity="T.{name}", '
        f'owner="T", declaration_order={order}:i64, initialized=true, '
        f'initializer_form="expression", initializer_value="{source}", '
        f'initializer_kind="{kind}", initializer_dimension="{dimension}", '
        f'initializer_reads=[], metadata={{semantic_path="T.{name}"}}}}> '
        f': ({types}) -> !nodal.variable<"{destination_kind}", "{dimension}">\n'
    )


def cases():
    roots = var('a', '2', 0) + var('b', '2', 1)
    set0 = idx('a0', 'a', '2', 0) + assign('set0', 'a0', V, 0)
    set1 = idx('a1', 'a', '2', 1) + assign('set1', 'a1', V, 1)
    whole = read('snapshot', 'a', shape('2'), shape('2', Q))
    copy = assign('copy', 'b', shape('2'), 2, reads=',%snapshot', types=',' + shape('2', Q), snapshot=True)
    fixed = roots + set0 + set1 + whole + copy
    filled = (
        roots
        + fill('fill', '2')
        + assign('filled', 'a', shape('2'), 0, reads=',%fill', types=',' + shape('2', Q), snapshot=True)
        + whole
        + assign('copy', 'b', shape('2'), 1, reads=',%snapshot', types=',' + shape('2', Q), snapshot=True)
    )
    yield ('literal_coverage_copy', fixed, True)
    yield ('fill_snapshot_copy', filled, True)
    yield ('partial_whole_read', roots + set0 + whole, False)
    yield ('partial_selected_read', roots + set0 + read('element', 'a0', V, Q), True)
    yield (
        'sibling_uninitialized_read',
        roots + set0 + idx('a1', 'a', '2', 1) + read('element', 'a1', V, Q),
        False,
    )
    yield ('duplicate_literal_not_whole', (roots + set0 + set1 + whole).replace('%a, %c1', '%a, %c0'), False)
    yield ('out_of_bounds', fixed.replace('%a, %c1', '%a, %c2'), False)
    yield ('forged_read_write_metadata', fixed.replace('storage="procedural"', 'storage="structural"'), False)
    yield ('forged_source_path', fixed.replace('input_path="T.a"', 'input_path="T.b"'), False)
    empty_source = fixed.replace('semantic_path="T.a"', 'semantic_path=""').replace(
        'input_path="T.a"', 'input_path=""')
    yield "missing_source_identity", empty_source, False
    yield (
        'snapshot_payload_competitor',
        fixed.replace('metadata={},value_form', 'metadata={value="1.0"},value_form'),
        False,
    )
    yield (
        'snapshot_shape_mismatch',
        roots + fill('fill', '3') + assign('filled', 'a', shape('2'), 0, reads=',%fill', types=',' + shape('3', Q), snapshot=True),
        False,
    )
    yield (
        'snapshot_stale_capture',
        roots + set0 + set1 + whole + assign('intervening', 'a0', V, 2) + copy.replace('authored_order=2', 'authored_order=3'),
        False,
    )
    captures = read('left', 'a0', V, Q) + read('right', 'a1', V, Q)
    arithmetic = assign('sum', 'a0', V, 2, 'analog_add(T.a0,T.a1)', ',%left,%right', ',' + Q + ',' + Q)
    yield ('distinct_selected_capture_bindings', roots + set0 + set1 + captures + arithmetic, True)
    yield (
        'forged_selected_binding',
        roots + set0 + set1 + captures + arithmetic.replace('analog_add(T.a0,T.a1)', 'analog_add(T.a0,T.a0)'),
        False,
    )
    yield (
        'selected_snapshot_order',
        roots + set0 + set1 + read('left', 'a0', V, Q) + assign('intervening', 'a1', V, 2) + assign('use', 'a0', V, 3, 'T.a0', ',%left', ',' + Q),
        False,
    )
    initialized = var('a', '2', 0).replace('"nodal.analog_variable"()', '"nodal.analog_variable"(%fill)').replace('initialized=false', 'initialized=true').replace(': () ->', ': (' + shape('2', Q) + ') ->')
    yield (
        'static_root_initializer',
        fill('fill', '2') + initialized + read('snapshot', 'a', shape('2'), shape('2', Q)),
        True,
    )
    yield ('shaped_integer_storage_rejected', fixed.replace('variable<"real"', 'variable<"integer"'), False)
    voltage = Q.replace('"1"', '"voltage"')
    mismatched_units = (
        roots + fill("voltage_fill", "2", "2.0 V").replace(Q, voltage)
        + assign("wrong_units", "a", shape("2"), 0, reads=",%voltage_fill",
                 types="," + shape("2", voltage), snapshot=True))
    yield "dimension_mismatch", mismatched_units, False
    row = (
        var('a', '2,3', 0)
        + idx('row', 'a', '2,3', 1, '3')
        + fill('fill', '3')
        + assign('row_set', 'row', shape('3'), 0, reads=',%fill', types=',' + shape('3', Q), snapshot=True)
    )
    direct = idx('element', 'row', '3', 2) + read('capture', 'element', V, Q)
    yield ('initialized_row_descendant', row + direct, True)
    yield (
        'initialized_row_sibling_rejected',
        row + idx('sibling', 'a', '2,3', 0, '3') + idx('element', 'sibling', '3', 2) + read('capture', 'element', V, Q),
        False,
    )
    partial = (
        var('a', '2,3', 0)
        + slice_value('partial', 'a', '2,3', '2,2', 0, 2, 1)
        + fill('fill', '2,2')
        + assign('partial_set', 'partial', shape('2,2'), 0, reads=',%fill', types=',' + shape('2,2', Q), snapshot=True)
    )
    yield (
        'partial_slice_reshaped_read',
        partial + view('flatten', 'partial', '2,2', '4') + read('capture', 'flatten', shape('4'), shape('4', Q)),
        True,
    )
    yield (
        'noncontiguous_slice_sibling_rejected',
        partial + idx('row', 'a', '2,3', 0, '3') + idx('outside', 'row', '3', 2) + read('capture', 'outside', V, Q),
        False,
    )
    pure = (
        roots
        + fill('fill', '2,2')
        + view('flatfill', 'fill', '2,2', '4', Q)
        + slice_value('partfill', 'flatfill', '4', '2', 1, 3, leaf=Q)
        + assign('purecopy', 'a', shape('2'), 0, reads=',%partfill', types=',' + shape('2', Q), snapshot=True)
    )
    yield ('immutable_fill_view_slice', pure, True)
    scalar_init = var('local', '2', 2).replace(shape('2'), V).replace('"nodal.analog_variable"()', '"nodal.analog_variable"(%capture)').replace('initialized=false', 'initialized=true').replace(': () ->', ': (' + Q + ') ->')
    yield (
        'selected_scalar_scope_initializer',
        roots + set0 + scope(read('capture', 'a0', V, Q) + scalar_init),
        True,
    )
    yield (
        'selected_scalar_persistent_initializer_rejected',
        roots + set0 + read('capture', 'a0', V, Q) + scalar_init,
        False,
    )
    yield (
        'scalar_initializer_competing_text',
        roots + set0 + scope(read('capture', 'a0', V, Q) + scalar_init.replace('initializer_value=""', 'initializer_value="0.0"')),
        False,
    )
    event = '%event="nodal.analog_initial_step"() <{event_id="T.event",owner="T",contract="increment37",name="",arguments=[],analyses=[],event_reads=[],metadata={}}> : ()->!nodal.analog_event\n'
    on = (
        '"nodal.analog_on"(%event) <{statement_id="T.on",owner="T",metadata={}}> ({\n'
        + set0
        + set1
        + '}) : (!nodal.analog_event)->()\n'
    )
    yield ('event_writes_do_not_escape', roots + event + on + whole, False)
    branch_whole = (
        fill('wholefill', '2')
        + assign('whole_set', 'a', shape('2'), 0, reads=',%wholefill', types=',' + shape('2', Q), snapshot=True)
    )
    branch_partial = idx('branch0', 'a', '2', 0) + assign('branch_set', 'branch0', V, 1)
    joined = roots + branch(branch_whole, branch_partial)
    yield (
        'branch_whole_meets_selected',
        joined + idx('after0', 'a', '2', 0) + read('after', 'after0', V, Q),
        True,
    )
    yield ('branch_partial_not_whole', joined + whole, False)
    mismatch = (
        roots
        + branch(idx('left0', 'a', '2', 0) + assign('left', 'left0', V, 0), idx('right1', 'a', '2', 1) + assign('right', 'right1', V, 1))
    )
    yield (
        'branch_distinct_elements_not_union',
        mismatch + idx('after0', 'a', '2', 0) + read('after', 'after0', V, Q),
        False,
    )
    declarations = parameter(name="COUNT", lower=2, upper=4)
    symbolic = (
        var("a", "COUNT", 0)
        + fill("fill", "COUNT")
        + assign("filled", "a", shape("COUNT"), 0, reads=",%fill",
                 types="," + shape("COUNT", Q), snapshot=True)
        + read("snapshot", "a", shape("COUNT"), shape("COUNT", Q)))
    yield "symbolic_fill_snapshot", symbolic, True, declarations
    selected = (
        var("a", "COUNT", 0)
        + idx("selected", "a", "COUNT", 1)
        + assign("set", "selected", V, 0)
        + idx("alias", "a", "COUNT", 1)
        + read("capture", "alias", V, Q))
    yield "symbolic_selected_identity", selected, True, declarations
    compound_declarations = (declarations + reference("count", "COUNT") + literal("one", 1)
                             + expression("extent", operands=("count", "one"), semantic_path="T.extent"))
    yield "compound_extent_fill", symbolic.replace("COUNT", "T.extent"), True, compound_declarations

    yield "generated_fill_snapshot", generated_source(filled), True
    malformed_generated = generated_source(filled).replace(
        'generated_induction="T.generated.index"', 'generated_induction="T.foreign.index"')
    yield "generated_owner_forgery", NativeSource(malformed_generated), False
    yield "maximum_cardinality_declaration", var("a", "9223372036854775807", 0), True
    yield "cardinality_overflow", var("a", "9223372036854775807,2", 0), False
    separate_parameters = declarations + parameter(name="OTHER", lower=2, upper=4)
    different_identity = (
        var("a", "COUNT", 0) + fill("fill", "OTHER")
        + assign("wrong_shape", "a", shape("COUNT"), 0, reads=",%fill",
                 types="," + shape("OTHER", Q), snapshot=True))
    yield "coincident_parameter_bounds_not_shape_identity", different_identity, False, separate_parameters

    projected_scalar = (
        roots + idx("a0", "a", "2", 0) + fill("fill", "2")
        + idx("fill_element", "fill", "2", 1, leaf=Q)
        + assign("scalar_copy", "a0", V, 0, "T.fill_element", ",%fill_element", "," + Q))
    yield "immutable_scalar_projection_capture", projected_scalar, True

    nested_fill = (
        roots + fill("inner_fill", "2")
        + idx("inner_element", "inner_fill", "2", 0, leaf=Q)
        + fill("outer_fill", "2", "T.inner_element", "%inner_element", Q)
        + assign("nested_fill_copy", "a", shape("2"), 0, reads=",%outer_fill",
                 types="," + shape("2", Q), snapshot=True))
    yield "nested_fill_scalar_projection", nested_fill, True

    # Event-free scalar payloads use the same source, type and actual-capture
    # proofs as event-controlled statements, rather than bypassing validation.
    def scalar(name, order, value="1.0"):
        return (var(name, "2", order).replace(shape("2"), V)
                .replace("initialized=false", "initialized=true")
                .replace('initializer_value=""', f'initializer_value="{value}"')
                .replace('initializer_kind=""', 'initializer_kind="real"')
                .replace('initializer_dimension=""', 'initializer_dimension="1"'))

    scalar_roots = scalar("a", 0) + scalar("b", 1)
    scalar_capture = scalar_roots + read("capture", "a", V, Q)
    scalar_copy = assign("scalar_copy", "b", V, 0, "T.a", ",%capture", "," + Q)
    yield "event_free_scalar_capture", scalar_capture + scalar_copy, True
    yield ("event_free_scalar_arithmetic", scalar_capture + scalar_copy.replace(
        'value="T.a"', 'value="analog_add(T.a,1.0)"'), True)
    yield ("event_free_missing_source_payload", scalar_capture + scalar_copy.replace(
        'metadata={value="T.a"}', 'metadata={}'), False)
    yield ("event_free_forged_capture_identity", scalar_capture + scalar_copy.replace(
        'value="T.a"', 'value="T.b"'), False)
    yield ("event_free_unused_capture", scalar_capture + scalar_copy.replace(
        'value="T.a"', 'value="1.0"'), False)
    yield ("event_free_initializer_wrong_units", scalar("a", 0).replace(
        'initializer_dimension="1"', 'initializer_dimension="voltage"').replace(
        V, V.replace('"1"', '"voltage"')), False)
    conditional = branch(assign("then_set", "a", V, 0), assign("else_set", "a", V, 1))
    forged_static = conditional.replace('stage="runtime"', 'stage="static"').replace(
        'static_value_present=false', 'static_value_present=true', 1)
    yield "event_free_forged_static_condition", scalar_roots + forged_static, False
    static_loop = (
        '"nodal.analog_loop"() <{owner="T",statement_id="T.loop",stage="static",'
        'bound_value="2",bound_kind="integer",bound_dimension="1",bound_reads=[],'
        'minimum_iterations=3:i64,maximum_iterations=3:i64,static_trip_count_present=true,'
        'static_trip_count=3:i64,metadata={}}> ({\n'
        + assign("loop_set", "a", V, 0) + '}) : ()->()\n')
    yield "event_free_forged_static_loop", scalar_roots + static_loop, False


    # Local expression initializers consume actual immutable scalar captures.
    root = var("array", "2", 0)
    selection = idx("element", "array", "2", 0)
    written = root + selection + assign("initialize", "element", V, 0)
    capture = read("capture", "element", V, Q)
    expression_init = initializer_expression(
        "local", "analog_add(T.element,1.0)", (("capture", Q),))
    one_capture = written + scope(capture + expression_init)
    yield "initializer_expression_zero_capture", scope(initializer_expression(
        "local", "analog_add(1.0,2.0)", order=0)), True
    yield "initializer_expression_one_capture", one_capture, True
    scalar_local = scope(read("capture", "scalar", V, Q) + initializer_expression(
        "local", "analog_add(T.scalar,1.0)", (("capture", Q),)))
    yield "initializer_expression_scalar_only", scalar("scalar", 0) + scalar_local, True
    yield "initializer_expression_scalar_only_uninitialized", var(
        "scalar", "2", 0).replace(shape("2"), V) + scalar_local, False
    yield "initializer_expression_integer_to_real", scope(initializer_expression(
        "local", "2", kind="integer", destination_kind="real", order=0)), True
    yield "initializer_expression_boolean_result", written + scope(
        capture + initializer_expression("local", "real_gt(T.element,0.0)",
                                         (("capture", Q),), kind="boolean")), True
    second = idx("other", "array", "2", 1) + assign("initialize_other", "other", V, 1)
    two_captures = capture + read("other_capture", "other", V, Q)
    multi = written + second + scope(two_captures + initializer_expression(
        "local", "analog_add(T.element,T.other)",
        (("capture", Q), ("other_capture", Q))))
    yield "initializer_expression_multiple_captures", multi, True
    dimensioned = one_capture.replace('"1"', '"voltage"').replace(
        'value="1.0"', 'value="1.0 V"').replace(
        'analog_add(T.element,1.0)', 'analog_add(T.element,1.0 V)')
    yield "initializer_expression_dimensioned", dimensioned, True
    projected_fill = (fill("values", "2")
                      + idx("value", "values", "2", 0, leaf=Q))
    yield "initializer_expression_projected_fill", scope(
        projected_fill + initializer_expression("local", "analog_mul(T.value,3.0)",
                                                (("value", Q),), order=0)), True
    yield "initializer_expression_shared_fill", scope(
        projected_fill + idx("other_value", "values", "2", 1, leaf=Q)
        + initializer_expression("local", "analog_add(T.value,T.other_value)",
                                 (("value", Q), ("other_value", Q)), order=0)), True
    yield "initializer_expression_generated_local", generated_source(one_capture), True
    on_local = (
        '"nodal.analog_on"(%event) <{statement_id="T.on",owner="T",metadata={}}> ({\n'
        + capture + expression_init + '}) : (!nodal.analog_event)->()\n')
    yield "initializer_expression_event_local", written + event + on_local, True

    yield "initializer_expression_root_rejected", written + capture + expression_init, False
    yield "initializer_expression_shaped_rejected", written + scope(
        capture + expression_init.replace('-> ' + V, '-> ' + shape("2"))), False
    yield "initializer_expression_uninitialized_rejected", one_capture.replace(
        'initialized=true', 'initialized=false'), False
    yield "initializer_expression_unknown_form", one_capture.replace(
        'initializer_form="expression"', 'initializer_form="unknown"'), False
    yield "initializer_expression_text_inventory", scalar("existing", 0) + scope(
        initializer_expression("local", "1.0").replace(
            'initializer_reads=[]', 'initializer_reads=["T.existing"]')), False
    yield "initializer_expression_missing_capture", written + scope(
        initializer_expression("local", "analog_add(T.element,1.0)")), False
    yield "initializer_expression_unused_capture", one_capture.replace(
        'analog_add(T.element,1.0)', '1.0'), False
    yield "initializer_expression_duplicate_capture", written + scope(
        capture + initializer_expression("local", "T.element",
                                         (("capture", Q), ("capture", Q)))), False
    yield "initializer_expression_live_handle", written + scope(initializer_expression(
        "local", "T.element", (("element", V),))), False
    yield "initializer_expression_shaped_capture", scope(
        fill("values", "2") + initializer_expression("local", "T.values",
                                                      (("values", shape("2", Q)),), order=0)), False
    yield "initializer_expression_wrong_kind", one_capture.replace(
        'initializer_kind="real"', 'initializer_kind="boolean"'), False
    yield "initializer_expression_wrong_units", one_capture.replace(
        'initializer_dimension="1", initializer_reads=[], metadata={semantic_path="T.local"}',
        'initializer_dimension="voltage", initializer_reads=[], metadata={semantic_path="T.local"}'), False
    yield "initializer_expression_wrong_destination", one_capture.replace(
        ': (' + Q + ') -> ' + V, ': (' + Q + ') -> !nodal.variable<"boolean", "1">'), False
    yield "initializer_expression_incoming_uninitialized", root + selection + scope(
        capture + expression_init), False
    yield "initializer_expression_crosses_write", written + scope(
        capture + assign("intervening", "element", V, 1) + expression_init), False
    yield "initializer_expression_capture_after_consumer", written + scope(
        expression_init + capture), False
    foreign_generated = generated_source(one_capture).replace(
        'owner="T",generated_owner="T.generated",generated_induction="T.generated.index", '
        'declaration_order=1',
        'owner="T",generated_owner="T.foreign",generated_induction="T.generated.index", '
        'declaration_order=1')
    yield "initializer_expression_generated_owner_forgery", NativeSource(foreign_generated), False
    yield "initializer_expression_capture_scope_escape", written + capture + scope(
        expression_init), False


# Exact causal diagnostic sets. Syntax/CLI errors or crashes never count as rejections.
EXPECTED_DIAGNOSTICS = {
    "initializer_expression_scalar_only_uninitialized": ["NODAL-ANALOG-034-004"],
    "initializer_expression_root_rejected": ["NODAL-ANALOG-033-004"],
    "initializer_expression_shaped_rejected": ["NODAL-ANALOG-033-004"],
    "initializer_expression_uninitialized_rejected": ["NODAL-ANALOG-033-004"],
    "initializer_expression_unknown_form": ["NODAL-ANALOG-033-004"],
    "initializer_expression_text_inventory": ["NODAL-ANALOG-033-004"],
    "initializer_expression_missing_capture": ["NODAL-ANALOG-037-010"],
    "initializer_expression_unused_capture": ["NODAL-ANALOG-037-010"],
    "initializer_expression_duplicate_capture": ["NODAL-ANALOG-037-010"],
    "initializer_expression_live_handle": ["NODAL-ANALOG-034-014"],
    "initializer_expression_shaped_capture": ["NODAL-ANALOG-037-010"],
    "initializer_expression_wrong_kind": ["NODAL-ANALOG-037-010"],
    "initializer_expression_wrong_units": ["NODAL-ANALOG-037-010"],
    "initializer_expression_wrong_destination": ["NODAL-ANALOG-033-004"],
    "initializer_expression_incoming_uninitialized": ["NODAL-ANALOG-034-004"],
    "initializer_expression_crosses_write": ["NODAL-ANALOG-033-017"],
    "initializer_expression_capture_after_consumer": ["NODAL-ANALOG-033-017"],
    "initializer_expression_capture_scope_escape": ["NODAL-ANALOG-033-017"],
    "initializer_expression_generated_owner_forgery": ["NODAL-ANALOG-034-014", "NODAL-ITERATION-043-004"],

    "event_free_missing_source_payload": ["NODAL-ANALOG-037-010"],
    "event_free_forged_capture_identity": ["NODAL-ANALOG-037-010"],
    "event_free_unused_capture": ["NODAL-ANALOG-037-010"],
    "event_free_initializer_wrong_units": ["NODAL-ANALOG-037-010"],
    "event_free_forged_static_condition": ["NODAL-ANALOG-037-010"],
    "event_free_forged_static_loop": ["NODAL-ANALOG-037-010"],

    "missing_source_identity": ["NODAL-ANALOG-034-014", "NODAL-SHAPE-043-003"],
    "generated_owner_forgery": ["NODAL-ITERATION-043-004"],
    "cardinality_overflow": ["NODAL-ANALOG-033-004"],
    "coincident_parameter_bounds_not_shape_identity": ["NODAL-ANALOG-033-012"],
    'partial_whole_read': ['NODAL-ANALOG-034-004'],
    'sibling_uninitialized_read': ['NODAL-ANALOG-034-004'],
    'duplicate_literal_not_whole': ['NODAL-ANALOG-034-004'],
    'out_of_bounds': ['NODAL-ANALOG-034-014', 'NODAL-SHAPE-043-002'],
    'forged_read_write_metadata': ['NODAL-ANALOG-034-014', 'NODAL-SHAPE-043-003'],
    'forged_source_path': ['NODAL-ANALOG-034-014', 'NODAL-SHAPE-043-003'],
    'snapshot_payload_competitor': ['NODAL-ANALOG-033-012'],
    'snapshot_shape_mismatch': ['NODAL-ANALOG-033-012'],
    'snapshot_stale_capture': ['NODAL-ANALOG-033-017'],
    'forged_selected_binding': ['NODAL-ANALOG-037-010'],
    'selected_snapshot_order': ['NODAL-ANALOG-033-017'],
    'shaped_integer_storage_rejected': ['NODAL-ANALOG-033-019', 'NODAL-ANALOG-034-014'],
    'dimension_mismatch': ['NODAL-ANALOG-033-012'],
    'initialized_row_sibling_rejected': ['NODAL-ANALOG-034-004'],
    'noncontiguous_slice_sibling_rejected': ['NODAL-ANALOG-034-004'],
    'selected_scalar_persistent_initializer_rejected': ['NODAL-ANALOG-037-009'],
    'scalar_initializer_competing_text': ['NODAL-ANALOG-033-004'],
    'event_writes_do_not_escape': ['NODAL-ANALOG-034-004'],
    'branch_partial_not_whole': ['NODAL-ANALOG-034-004'],
    'branch_distinct_elements_not_union': ['NODAL-ANALOG-034-004'],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--nodalc", required=True)
    parser.add_argument("--work-dir", required=True)
    args = parser.parse_args()
    directory = Path(args.work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    for name, body, accepted, *declarations in cases():
        path = directory / (name + ".mlir")
        path.write_text(str(body) if isinstance(body, NativeSource) else wrap(body, "".join(declarations)))
        run = subprocess.run([args.nodalc, str(path)], capture_output=True, timeout=20)
        (directory / (name + ".stdout")).write_bytes(run.stdout)
        (directory / (name + ".stderr")).write_bytes(run.stderr)
        codes = sorted(set(re.findall(r"NODAL-[A-Z0-9-]+", run.stderr.decode())))
        expected = [] if accepted else EXPECTED_DIAGNOSTICS[name]
        passed = run.returncode == (0 if accepted else 1) and codes == expected
        if accepted and passed:
            replay = directory / (name + ".roundtrip.mlir")
            replay.write_bytes(run.stdout)
            repeated = subprocess.run([args.nodalc, str(replay)], capture_output=True, timeout=20)
            passed = repeated.returncode == 0 and repeated.stdout == run.stdout
            gated = subprocess.run(
                [args.nodalc, "--pass-pipeline=builtin.module(nodal-gate-default)", str(path)],
                capture_output=True, timeout=20)
            (directory / (name + ".gate.stdout")).write_bytes(gated.stdout)
            (directory / (name + ".gate.stderr")).write_bytes(gated.stderr)
            passed = passed and gated.returncode == 0
            if gated.returncode:
                print(gated.stderr.decode())
        results.append({"name": name, "passed": passed, "accepted": accepted,
                        "exit": run.returncode, "codes": codes})
        print(("PASS" if passed else "FAIL") + " " + name)
        if not passed:
            print(run.stderr.decode())
    (directory / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f'{sum(result["passed"] for result in results)}/{len(results)} writable storage checks passed')
    return 0 if all(result["passed"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
