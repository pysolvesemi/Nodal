#!/usr/bin/env python3
"""Focused native conservative boundary-array, binding and incidence proofs."""

import argparse
import json
import re
import subprocess
from pathlib import Path


TERMINAL = '!nodal.terminal<"electrical">'
CODE = "NODAL-SHAPE-043-008"


def shaped(dimensions, element=TERMINAL):
    return f'!nodal.shaped<"{dimensions}", {element}>'


def literal(name, value):
    return (f'%{name} = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{value}", '
            f'value = {value} : i64}}> : () -> i64\n')


def parameter(owner, name, low=2, high=8, default=3):
    return (f'"nodal.parameter"() <{{classification = "structural", default_value = {default} : i64, '
            f'metadata = {{semantic_path = "{owner}.{name}"}}, parameter_kind = "integer", '
            f'sym_name = "{name}", type = i64, variability = "symbolic"}}> : () -> ()\n' +
            literal(name + '_low', low) + literal(name + '_high', high) +
            f'"nodal.parameter_constraint"(%{name}_low, %{name}_high) <{{constraint_kind = "range", '
            f'lower_inclusive = true, upper_inclusive = true, parameter = @{name}, metadata = {{}}}}> '
            ': (i64, i64) -> ()\n' +
            f'"nodal.parameter_envelope"() <{{effects = ["shape"], policy = "static_generate", '
            f'parameter = @{name}, metadata = {{}}}}> : () -> ()\n' +
            f'%{name} = "nodal.const_parameter_ref"() <{{parameter = @{name}, metadata = {{}}}}> : () -> i64\n')


def expression(owner, name, operator, operands):
    values = ', '.join('%' + value for value in operands)
    types = ', '.join('i64' for _ in operands)
    return (f'%{name} = "nodal.const_expr"({values}) <{{operator_name = "{operator}", '
            f'metadata = {{semantic_path = "{owner}.{name}"}}}}> : ({types}) -> i64\n')


def array(owner, name, dimensions, direction=None):
    kind = 'terminal_array' if direction else 'node_array'
    boundary = (f'direction = "{direction}", flow_orientation = "into_component", '
                if direction else '')
    return (f'%{name} = "nodal.{kind}"() <{{name = "{name}", {boundary}'
            f'source_path = "{owner}.{name}", metadata = {{semantic_path = "{owner}.{name}", '
            f'storage = "structural"}}}}> : () -> {shaped(dimensions)}\n')


def module(name, body):
    return (f'"nodal.module"() <{{sym_name = "{name}", metadata = {{semantic_path = "{name}"}}}}> ({{\n'
            f'"nodal.component_contract"() <{{kind = "partial", connectivity_ownership = "extensible", '
            f'source_path = "{name}", metadata = {{}}}}> : () -> ()\n{body}'
            '}) : () -> ()\n')


def design(child, parent):
    return ('module attributes {nodal.target.profile = "analog"} {\n'
            '"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", '
            'metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()\n'
            '"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", '
            'metadata = {}, sym_name = "Current", units = "A"}> : () -> ()\n'
            '"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, '
            'metadata = {}, sym_name = "electrical"}> : () -> ()\n' +
            module('Child', child) + module('Parent', parent) + '}\n')


def instance(name='u', bindings='{}', target='Child'):
    return (f'"nodal.instance"() <{{sym_name = "{name}", module = @{target}, '
            f'parameter_bindings = {bindings}, domain_bindings = {{}}, '
            f'metadata = {{semantic_path = "Parent.{name}"}}}}> : () -> ()\n')


def override(parameter_name, value, instance_name='u'):
    return (f'"nodal.parameter_override"(%{value}) <{{instance = @{instance_name}, '
            f'parameter = @{parameter_name}, metadata = {{}}}}> : (i64) -> ()\n')


def reference(dimensions, *, name='ref', instance_name='u', port='p', direction='inout'):
    return (f'%{name} = "nodal.instance_terminal_array"() <{{instance = @{instance_name}, '
            f'port = "{port}", name = "{instance_name}.{port}", direction = "{direction}", '
            f'flow_orientation = "out_of_component", source_path = "Child.{port}", '
            f'metadata = {{semantic_path = "Parent.{name}", storage = "structural"}}}}> '
            f': () -> {shaped(dimensions)}\n')


def connect(dimensions, *, actual='nodes', child='ref', path='binding', child_dimensions=None):
    child_dimensions = child_dimensions or dimensions
    return (f'"nodal.connect_array"(%{actual}, %{child}) <{{connection_id = "Parent.{path}", '
            f'source_path = "Parent.{path}", metadata = {{semantic_path = "Parent.{path}"}}}}> '
            f': ({shaped(dimensions)}, {shaped(child_dimensions)}) -> ()\n')


def select(owner, base, dimensions, indices, name='selected'):
    operands = ', '.join(['%' + base] + ['%' + value for value, _ in indices])
    types = ', '.join([shaped(dimensions)] + ['i64' for _ in indices])
    path = f'{owner}.{base}.at(' + ','.join(token for _, token in indices) + ')'
    return (f'%{name} = "nodal.shape_index"({operands}) <{{metadata = {{input_path = "{owner}.{base}", '
            f'semantic_path = "{path}", source_uses = ["{owner}.use_{name}"], '
            f'storage = "structural", selection_kind = "node_array"}}}}> : ({types}) -> {TERMINAL}\n')


def port_flow(value='selected'):
    return ('"nodal.analog"() <{metadata = {}}> ({\n'
            f'%flow = "nodal.port_flow_access"(%{value}) <{{kind = "flow", function = "I", '
            f'metadata = {{semantic_path = "Parent.flow"}}}}> : ({TERMINAL}) '
            '-> !nodal.quantity<"real", "current">\n}) : () -> ()\n')


def prefix(base='nodes', dimensions='2,3', result='3', index='one', name='row'):
    return (f'%{name} = "nodal.shape_index"(%{base}, %{index}) <{{metadata = {{'
            f'input_path = "Parent.{base}", semantic_path = "Parent.{name}", '
            f'storage = "structural", access = "read_only"}}}}> '
            f': ({shaped(dimensions)}, i64) -> {shaped(result)}\n')


def sliced(base='nodes', dimensions='2,4', result='2,2', name='columns'):
    return (f'%{name} = "nodal.shape_slice"(%{base}, %one, %three, %two) <{{axis = 1 : i64, '
            f'dimensions = "{result}", origin = "Parent.{base}", observability = "source_mapped", '
            f'metadata = {{input_path = "Parent.{base}", semantic_path = "Parent.{name}", '
            f'storage = "structural", access = "read_only"}}}}> '
            f': ({shaped(dimensions)}, i64, i64, i64) -> {shaped(result)}\n')


def cases():
    fixed_child = array('Child', 'p', '2,3', 'inout')
    fixed_parent = array('Parent', 'nodes', '2,3') + instance() + reference('2,3') + connect('2,3')
    fixed = design(fixed_child, fixed_parent)
    yield 'fixed_whole_local', fixed, []
    same_name_instance = array('Parent', 'nodes', '2,3') + instance('Child')
    yield 'same_name_instance_bound', design(fixed_child, same_name_instance +
        reference('2,3', instance_name='Child') + connect('2,3')), []
    yield 'same_name_instance_missing', design(fixed_child, same_name_instance), ['NODAL-VERIFY-HIERARCHY-012']
    same_name_parameter = parameter('Parent', 'Child') + array('Parent', 'nodes', '2,3') + instance()
    yield 'same_name_parameter_bound', design(fixed_child, same_name_parameter +
        reference('2,3') + connect('2,3')), []
    yield 'same_name_parameter_missing', design(fixed_child, same_name_parameter), ['NODAL-VERIFY-HIERARCHY-012']
    for direction in ('input', 'output', 'inout'):
        yield 'boundary_' + direction, design(array('Child', 'p', '2,3', direction),
            array('Parent', 'nodes', '2,3', direction) + instance() +
            reference('2,3', direction=direction) + connect('2,3')), []
    yield 'singleton', design(array('Child', 'p', '1', 'inout'),
        array('Parent', 'nodes', '1') + instance() + reference('1') + connect('1')), []
    yield 'prefix_actual', design(array('Child', 'p', '3', 'inout'),
        array('Parent', 'nodes', '2,3') + literal('one', 1) + prefix() + instance() +
        reference('3') + connect('3', actual='row')), []
    constants = literal('one', 1) + literal('two', 2) + literal('three', 3)
    yield 'noncontiguous_actual', design(array('Child', 'p', '2,2', 'inout'),
        array('Parent', 'nodes', '2,4') + constants + sliced() + instance() + reference('2,2') +
        connect('2,2', actual='columns')), []
    local_flow = (array('Parent', 'p', '2,3', 'inout') + literal('one', 1) +
                  select('Parent', 'p', '2,3', [('one', 'literal:1'), ('one', 'literal:1')]) + port_flow())
    yield 'selected_local_port_flow', design('', local_flow), []
    yield 'selected_port_view_flow', design('', array('Parent', 'p', '2,3', 'inout') +
        literal('one', 1) + prefix(base='p') +
        select('Parent', 'row', '3', [('one', 'literal:1')]) + port_flow()), []
    symbolic_child = parameter('Child', 'A') + array('Child', 'p', 'A', 'inout')
    symbolic_parent = parameter('Parent', 'N', 2, 6) + array('Parent', 'nodes', 'N') + instance() + override('A', 'N')
    yield 'parent_parameter_actual', design(symbolic_child, symbolic_parent + reference('N') + connect('N')), []
    repeated_parent = parameter('Parent', 'N', 2, 4) + parameter('Parent', 'M', 5, 8, 6)
    for instance_name, actual_name, extent, authored_child in (
            ('u', 'left', 'N', 'Authored.first'), ('v', 'right', 'M', 'Authored.second')):
        repeated_parent += array('Parent', actual_name, extent)
        repeated_parent += instance(instance_name).replace(
            f'semantic_path = "Parent.{instance_name}"',
            f'semantic_path = "Parent.{instance_name}", child_path = "{authored_child}"')
        repeated_parent += override('A', extent, instance_name)
        repeated_parent += reference(extent, name=actual_name + '_ref', instance_name=instance_name).replace(
            'source_path = "Child.p"', f'source_path = "{authored_child}.p"').replace(
            f'semantic_path = "Parent.{actual_name}_ref"',
            f'semantic_path = "Parent.{actual_name}_ref", child_path = "{authored_child}"')
        repeated_parent += connect(extent, actual=actual_name, child=actual_name + '_ref', path=actual_name + '_binding')
    yield 'reused_definition_distinct_actuals', design(symbolic_child, repeated_parent), []
    yield 'wrong_authored_child_provenance', design(symbolic_child, repeated_parent.replace('child_path = "Authored.first"', 'child_path = "Other.first"', 1)), [CODE]
    yield 'two_child_ports_share_actual', design(fixed_child + array('Child', 'q', '2,3', 'input'),
        fixed_parent + reference('2,3', name='second_ref', port='q', direction='input') +
        connect('2,3', child='second_ref', path='second_binding')), []
    yield 'literal_override', design(symbolic_child, array('Parent', 'nodes', '4') +
        instance(bindings='{A = 4 : i64}') + reference('4') + connect('4')), []
    yield 'omitted_default', design(symbolic_child, array('Parent', 'nodes', '3') + instance() +
        reference('3') + connect('3')), []
    yield 'override_after_connect', design(symbolic_child, parameter('Parent', 'N', 2, 6) +
        array('Parent', 'nodes', 'N') + instance() + reference('N') + connect('N') + override('A', 'N')), []
    dependent_child = (parameter('Child', 'A') + parameter('Child', 'B', 3, 9, 4) + literal('one', 1) +
        expression('Child', 'default_b', 'add', ['A', 'one']) +
        '"nodal.parameter_value"(%default_b) <{parameter = @B, metadata = {}}> : (i64) -> ()\n' +
        array('Child', 'p', 'B', 'inout'))
    dependent_parent = (parameter('Parent', 'N', 2, 6) + literal('one', 1) +
        expression('Parent', 'extent', 'add', ['N', 'one']) + array('Parent', 'nodes', 'Parent.extent') +
        instance() + override('A', 'N') + reference('Parent.extent') + connect('Parent.extent'))
    yield 'dependent_default_substitution', design(dependent_child, dependent_parent), []
    yield 'dependent_defaults_omitted', design(dependent_child, array('Parent', 'nodes', '4') +
        instance() + reference('4') + connect('4')), []
    twice_child = (parameter('Child', 'A') + literal('two', 2) +
        expression('Child', 'twice', 'mul', ['two', 'A']) + array('Child', 'p', 'Child.twice', 'inout'))
    twice_parent = (parameter('Parent', 'N', 2, 6) + literal('two', 2) +
        expression('Parent', 'twice', 'mul', ['two', 'N']) + array('Parent', 'nodes', 'Parent.twice') +
        instance() + override('A', 'N') + reference('Parent.twice') + connect('Parent.twice'))
    yield 'exact_substituted_expression', design(twice_child, twice_parent), []
    yield 'equal_count_wrong_rank', fixed.replace('"2,3"', '"6"', 1), [CODE]
    yield 'wrong_extents', fixed.replace('"2,3"', '"3,2"', 1), [CODE]
    yield 'forged_effective_type', fixed.replace(reference('2,3'), reference('3,2')).replace(
        connect('2,3'), connect('2,3', child_dimensions='3,2')), [CODE]
    yield 'wrong_direction', fixed.replace('name = "u.p", direction = "inout"',
        'name = "u.p", direction = "input"'), [CODE]
    yield 'wrong_orientation', fixed.replace('flow_orientation = "out_of_component"',
        'flow_orientation = "into_component"'), [CODE]
    yield 'wrong_child_source', fixed.replace('source_path = "Child.p", metadata = {semantic_path = "Parent.ref"',
        'source_path = "Other.p", metadata = {semantic_path = "Parent.ref"'), [CODE]
    yield 'unknown_child_port', fixed.replace('port = "p", name = "u.p"', 'port = "internal", name = "u.p"'), [CODE]
    yield 'child_internal_array', fixed.replace(fixed_child, array('Child', 'p', '2,3')), [CODE]
    yield 'duplicate_binding', design(fixed_child, fixed_parent + connect('2,3', path='second')), ['NODAL-VERIFY-HIERARCHY-010']
    yield 'missing_binding', design(fixed_child, array('Parent', 'nodes', '2,3') + instance()), ['NODAL-VERIFY-HIERARCHY-012']
    yield 'unbound_reference', design(fixed_child, array('Parent', 'nodes', '2,3') + instance() + reference('2,3')), ['NODAL-VERIFY-HIERARCHY-012']
    yield 'child_projection_rejected', design(fixed_child, fixed_parent + literal('one', 1) +
        prefix(base='ref')), [CODE]
    yield 'distinct_equal_envelope_parameters', design(symbolic_child,
        parameter('Parent', 'N', 2, 6) + parameter('Parent', 'M', 2, 6) + array('Parent', 'nodes', 'M') +
        instance() + override('A', 'N') + reference('M') + connect('M')), [CODE]
    yield 'actual_envelope_escapes_formal', design(symbolic_child,
        parameter('Parent', 'N', 2, 10) + array('Parent', 'nodes', 'N') + instance() +
        override('A', 'N') + reference('N') + connect('N')), [CODE]
    yield 'default_is_not_parent_actual', design(symbolic_child,
        parameter('Parent', 'N', 2, 6) + array('Parent', 'nodes', '3') + instance() +
        override('A', 'N') + reference('3') + connect('3')), [CODE]
    yield 'local_net_not_port_incidence', design('', local_flow.replace(
        array('Parent', 'p', '2,3', 'inout'), array('Parent', 'p', '2,3'))), ['NODAL-ACCESS-PORT-001']
    yield 'sparse_array_not_complete_equations', fixed.replace('kind = "partial", connectivity_ownership = "extensible"',
        'kind = "concrete", connectivity_ownership = "local"', 1), ['NODAL-SHAPE-043-005']
    yield 'duplicate_child_reference', design(fixed_child, fixed_parent + reference('2,3', name='again')), ['NODAL-VERIFY-HIERARCHY-010']
    yield 'nonstructural_boundary_forgery', fixed.replace('storage = "structural"', 'storage = "procedural"', 1), ['NODAL-SHAPE-043-005']
    yield 'nonpositive_legal_extent_rejected', design(parameter('Child', 'A', 0, 2, 1) +
        array('Child', 'p', 'A', 'inout'), ''), ['NODAL-SHAPE-043-005']
    yield 'metadata_does_not_grant_internal_incidence', design('', local_flow.replace(
        array('Parent', 'p', '2,3', 'inout'),
        array('Parent', 'p', '2,3').replace('storage = "structural"',
            'storage = "structural", declaration_kind = "analog-inout"'))), ['NODAL-ACCESS-PORT-001']
    joined_ports = (array('Parent', 'p', '2', 'inout') + array('Parent', 'q', '2', 'inout') +
                    literal('zero', 0) + select('Parent', 'p', '2', [('zero', 'literal:0')], 'left') +
                    select('Parent', 'q', '2', [('zero', 'literal:0')], 'right') +
                    f'"nodal.connect"(%left, %right) <{{connection_id = "joined", source_path = "Parent.joined", metadata = {{}}}}> : ({TERMINAL}, {TERMINAL}) -> ()\n' +
                    port_flow('left') + port_flow('right').replace('%flow', '%other_flow').replace('Parent.flow', 'Parent.other_flow'))
    yield 'joined_ports_keep_distinct_incidence', design('', joined_ports), []
    recursive_child = fixed_child + instance('back', target='Parent')
    yield 'definition_cycle_rejected', design(recursive_child, fixed_parent), ['gate:NODAL-VERIFY-HIERARCHY-005']
    malformed = design('', instance())
    malformed = malformed.replace(module('Child', ''), '')
    malformed = malformed[:-2] + '"nodal.module"() <{sym_name = "Child", metadata = {}}> ({}) : () -> ()\n}\n'
    yield 'malformed_child_inventory', malformed, [CODE]
    yield 'digital_profile_rejects_arrays', fixed.replace('nodal.target.profile = "analog"',
        'nodal.target.profile = "digital"'), ['gate:NODAL-VERIFY-CAPABILITY-003']
    digital = ('"nodal.domain"() <{sym_name = "clk", edge = "rising", reset_policy = "sync", metadata = {}}> : () -> ()\n'
               '"nodal.port"() <{sym_name = "digital", direction = "input", domain = @clk, type = i1, metadata = {}}> : () -> ()\n')
    yield 'mixed_boundary_requires_bridge', design(fixed_child, digital + fixed_parent), ['gate:NODAL-VERIFY-ANALOG-004']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--nodalc', required=True)
    parser.add_argument('--work-dir', required=True)
    args = parser.parse_args()
    directory = Path(args.work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    for name, source, expected in cases():
        path = directory / f'{name}.mlir'
        path.write_text(source)
        gate_negative = bool(expected and expected[0].startswith('gate:'))
        expected = [code.removeprefix('gate:') for code in expected]
        command = [args.nodalc]
        if gate_negative:
            command.append('--pass-pipeline=builtin.module(nodal-gate-default)')
        run = subprocess.run(command + [str(path)], capture_output=True, timeout=20)
        (directory / f'{name}.stdout').write_bytes(run.stdout)
        (directory / f'{name}.stderr').write_bytes(run.stderr)
        codes = sorted(set(re.findall(r'NODAL-[A-Z0-9-]+', run.stderr.decode())))
        passed = run.returncode == (1 if expected else 0) and codes == expected
        if not expected and passed:
            replay = directory / f'{name}.roundtrip.mlir'
            replay.write_bytes(run.stdout)
            repeated = subprocess.run([args.nodalc, str(replay)], capture_output=True, timeout=20)
            gated = subprocess.run([args.nodalc, '--pass-pipeline=builtin.module(nodal-gate-default)',
                                    str(path)], capture_output=True, timeout=20)
            (directory / f'{name}.gate.stdout').write_bytes(gated.stdout)
            (directory / f'{name}.gate.stderr').write_bytes(gated.stderr)
            passed = repeated.returncode == 0 and repeated.stdout == run.stdout and gated.returncode == 0
            if name == 'joined_ports_keep_distinct_incidence' and gated.returncode == 0:
                subjects = re.findall(rb'"nodal.port_flow_access"\(([^)]*)\)', gated.stdout)
                passed = passed and len(subjects) == 2 and len(set(subjects)) == 2
            if gated.returncode:
                print(gated.stderr.decode())
        results.append({'name': name, 'passed': passed, 'codes': codes})
        print(('PASS ' if passed else 'FAIL ') + name)
        if not passed:
            print(run.stderr.decode())
    (directory / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'{sum(result["passed"] for result in results)}/{len(results)} boundary-array checks passed')
    return 0 if all(result['passed'] for result in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
