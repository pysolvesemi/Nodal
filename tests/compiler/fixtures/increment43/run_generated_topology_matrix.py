#!/usr/bin/env python3
"""Focused native lexical generated-topology witnesses and causal mutations.

These hand-authored cases exercise nodalc, not a Python substitute verifier.
They are not public Scala, target-generation or simulator acceptance evidence.
Use --case to select a focused subset; raw inputs and results belong in .work.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import run_boundary_array_matrix as boundary


TERMINAL = boundary.TERMINAL
HIERARCHY = 'nodal-verify-hierarchy'


def owned(text, scope='Parent.gen'):
    return text.replace('metadata = {',
                        f'generated_owner = "{scope}", '
                        f'generated_induction = "{scope}.index", metadata = {{', 1)


def generate(body='', scope='Parent.gen', *, lower=0, upper=3, step=1,
             index='i', count=None, bounds=None):
    if count is None:
        count = len(range(lower, upper, step))
    attr = lambda value: f'{value} : i64' if isinstance(value, int) else value
    domain = (f'lower = {attr(lower)}, upper = {attr(upper)}, step = {attr(step)}, '
              if bounds is None else '')
    operands = ', '.join('%' + value for value in bounds) if bounds else ''
    types = 'i64, i64, i64' if bounds else ''
    return (f'"nodal.generate"({operands}) <{{induction = "{index}", {domain}'
            f'region_id = "{scope}", '
            f'induction_path = "{scope}.index", metadata = {{maximum_trip_count = {count} : i64}}}}> '
            f'({{\n^bb0(%{index}: i64):\n{body}}}) : ({types}) -> ()\n')


def scalar(owner, name, direction=None):
    operation = 'terminal' if direction else 'node'
    fields = (f'direction = "{direction}", flow_orientation = "into_component", '
              if direction else '')
    return (f'%{name} = "nodal.{operation}"() <{{{fields}name = "{name}", '
            f'source_path = "{owner}.{name}", metadata = {{semantic_path = "{owner}.{name}"}}}}> '
            f': () -> {TERMINAL}\n')


def reference(instance='u', port='p', name='ref', direction='inout'):
    return (f'%{name} = "nodal.instance_terminal"() <{{instance = @{instance}, '
            f'port = "{port}", name = "{instance}.{port}", direction = "{direction}", '
            f'flow_orientation = "out_of_component", source_path = "Child.{port}", '
            f'metadata = {{semantic_path = "Parent.{name}"}}}}> : () -> {TERMINAL}\n')


def connect(left='shared', right='ref', path='binding'):
    path = path if path.startswith('Parent.') else 'Parent.' + path
    return (f'"nodal.connect"(%{left}, %{right}) <{{connection_id = "{path}", '
            f'source_path = "{path}", metadata = {{semantic_path = "{path}"}}}}> '
            f': ({TERMINAL}, {TERMINAL}) -> ()\n')


def bound_child(scope='Parent.gen', *, instance='u', actual='shared', name='ref',
                target='Child', direction='inout'):
    return (owned(boundary.instance(instance, target=target).replace(
                f'semantic_path = "Parent.{instance}"', f'semantic_path = "{scope}.{instance}"'), scope) +
            owned(reference(instance, name=name, direction=direction).replace(
                f'semantic_path = "Parent.{name}"', f'semantic_path = "{scope}.{name}"'), scope) +
            owned(connect(actual, name, path=scope + '.binding_' + name), scope))


def selected(scope='Parent.gen', *, base='nodes', dimensions='3', name='selected',
             indices=None):
    if indices is None:
        indices = [('i', 'induction:' + scope + '.index')]
    result = boundary.select('Parent', base, dimensions, indices, name).replace(
        f'Parent.use_{name}', f'{scope}.use_{name}')
    marker = '.at(' + ','.join(token for _, token in indices) + ')'
    return owned(result.replace(marker + '"', marker + f'.in({scope})"'), scope)


def cases():
    child = scalar('Child', 'p', 'inout')
    shared = scalar('Parent', 'shared')
    valid = boundary.design(child, shared + generate(bound_child()))
    yield 'shared_parent_scalar', valid, None, HIERARCHY
    yield 'empty_valid_inventory', boundary.design(child, shared + generate(
        bound_child(), lower=4, upper=4)), None, HIERARCHY
    yield 'singleton', boundary.design(child, shared + generate(bound_child(), upper=1)), None, HIERARCHY
    local = owned(scalar('Parent.gen', 'local'))
    two = (local + bound_child(actual='local') +
           bound_child(instance='v', actual='local', name='other') +
           owned(connect('shared', 'local', path='join')))
    yield 'two_children_local_node', boundary.design(child, shared + generate(two)), None, HIERARCHY
    yield 'reversed_scalar_connection', valid.replace('(%shared, %ref)', '(%ref, %shared)'), None, HIERARCHY
    siblings = generate(bound_child()) + generate(bound_child('Parent.other'), 'Parent.other', index='j')
    yield 'same_named_sibling_instances', boundary.design(child, shared + siblings), None, HIERARCHY
    nested = bound_child() + generate(bound_child('Parent.gen.inner', name='inner_ref'),
                                     'Parent.gen.inner', index='j')
    yield 'same_named_nested_instances', boundary.design(child, shared + generate(nested)), None, HIERARCHY
    yield 'definition_name_shadow', boundary.design(child, shared + generate(bound_child(
        instance='Child'))), None, HIERARCHY
    yield 'parameter_name_shadow', boundary.design(child, boundary.parameter('Parent', 'u') +
        shared + generate(bound_child())), None, HIERARCHY

    array_body = (owned(boundary.instance()) + owned(boundary.reference('2,3')) +
                  owned(boundary.connect('2,3')))
    yield 'invariant_whole_array_binding', boundary.design(
        boundary.array('Child', 'p', '2,3', 'inout'),
        boundary.array('Parent', 'nodes', '2,3') + generate(array_body)), None, HIERARCHY
    override_body = (owned(boundary.instance()) + owned(boundary.reference('N')) +
                     owned(boundary.connect('N')) + owned(boundary.override('A', 'N')))
    yield 'invariant_parameter_override_after_binding', boundary.design(
        boundary.parameter('Child', 'A') + boundary.array('Child', 'p', 'A', 'inout'),
        boundary.parameter('Parent', 'N', 2, 6) + boundary.array('Parent', 'nodes', 'N') +
        generate(override_body)), None, HIERARCHY

    indexed = boundary.design(child, boundary.array('Parent', 'nodes', '3') + generate(
        selected() + bound_child(actual='selected')))
    yield 'direct_induction_selection', indexed, None, HIERARCHY
    yield 'nonzero_origin_stride', boundary.design(child, boundary.array('Parent', 'nodes', '7') +
        generate(selected(dimensions='7') + bound_child(actual='selected'), lower=1, upper=7,
                 step=2)), None, HIERARCHY
    scope = 'Parent.gen.inner'
    axes = [('i', 'induction:Parent.gen.index'), ('j', 'induction:' + scope + '.index')]
    yield 'nested_rectangular_selection', boundary.design(child,
        boundary.array('Parent', 'nodes', '3,2') + generate(generate(
            selected(scope, dimensions='3,2', indices=axes) + bound_child(scope, actual='selected'),
            scope, upper=2, index='j'))), None, HIERARCHY
    yield 'module_static_selection_in_generate', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + boundary.literal('one', 1) + generate(
            selected(indices=[('one', 'literal:1')]) + bound_child(actual='selected'))), None, HIERARCHY
    local_literal = '%zero = "nodal.constant"() <{value = 0 : index, metadata = {}}> : () -> index\n'
    mixed = selected(dimensions='3,2', indices=[('i', 'induction:Parent.gen.index'),
                                              ('zero', 'literal:0')])
    mixed = mixed.replace('>, i64, i64)', '>, i64, index)')
    mixed_body = local_literal + mixed + bound_child(actual='selected')
    yield 'generated_literal_with_induction', boundary.design(child,
        boundary.array('Parent', 'nodes', '3,2') + generate(mixed_body)), None, HIERARCHY
    yield 'generated_i64_literal_with_induction', boundary.design(child,
        boundary.array('Parent', 'nodes', '3,2') + generate(
            mixed_body.replace(local_literal, boundary.literal('zero', 0))
                      .replace('i64, index)', 'i64, i64)'))), None, HIERARCHY
    yield 'empty_induction_does_not_waive_invalid_literal', boundary.design(child,
        boundary.array('Parent', 'nodes', '3,2') + generate(
            mixed_body.replace('value = 0 : index', 'value = -1 : index')
                      .replace('literal:0', 'literal:-1'), lower=0, upper=0)), \
        'NODAL-SHAPE-043-002', HIERARCHY

    symbolic_prefix = (boundary.parameter('Parent', 'N', 2, 6) +
                       boundary.array('Parent', 'nodes', 'N'))
    symbolic_body = selected(dimensions='N') + bound_child(actual='selected')
    symbolic = boundary.design(child, symbolic_prefix + generate(symbolic_body, upper='@N', count=6))
    yield 'symbolic_exact_upper', symbolic, None, HIERARCHY
    yield 'independent_same_envelope_upper', boundary.design(child,
        boundary.parameter('Parent', 'M', 2, 6) + symbolic_prefix +
        generate(symbolic_body, upper='@M', count=6)), 'NODAL-SHAPE-043-002', HIERARCHY
    compound_prefix = (boundary.parameter('Parent', 'N', 2, 6) + boundary.literal('zero', 0) +
                       boundary.literal('one', 1) + boundary.expression('Parent', 'extent', 'add',
                       ['N', 'one']) + boundary.array('Parent', 'nodes', 'Parent.extent'))
    yield 'compound_exact_upper', boundary.design(child, compound_prefix + generate(
        selected(dimensions='Parent.extent') + bound_child(actual='selected'), count=7,
        bounds=('zero', 'extent', 'one'))), None, HIERARCHY
    yield 'empty_selector_outside_extent', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + generate(selected() + bound_child(actual='selected'),
        lower=7, upper=7)), None, HIERARCHY
    yield 'negative_empty_selector', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + generate(selected() + bound_child(actual='selected'),
        lower=-2, upper=-2)), None, HIERARCHY
    yield 'negative_nonempty_selector', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + generate(selected() + bound_child(actual='selected'),
        lower=-1, upper=2)), 'NODAL-SHAPE-043-002', HIERARCHY
    yield 'directional_induction_outside_profile', boundary.design(child, shared + generate(
        bound_child(), lower=3, upper=0, step=-1)), 'NODAL-ITERATION-043-002', HIERARCHY
    yield 'empty_selector_still_checks_rank', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + generate(selected(indices=[
            ('i', 'induction:Parent.gen.index'), ('i', 'induction:Parent.gen.index')]) +
            bound_child(actual='selected'), lower=7, upper=7)), 'NODAL-SHAPE-043-002', HIERARCHY
    repeated = selected() + selected(name='again') + bound_child(actual='selected') + bound_child(
        instance='v', actual='again', name='other')
    yield 'same_scope_selection_normalizes', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + generate(repeated)), None, HIERARCHY
    sibling_selection = generate(selected() + bound_child(actual='selected')) + generate(
        selected('Parent.other', indices=[('j', 'induction:Parent.other.index')]) +
        bound_child('Parent.other', actual='selected'), 'Parent.other', index='j')
    yield 'sibling_selections_remain_distinct', boundary.design(child,
        boundary.array('Parent', 'nodes', '3') + sibling_selection), None, HIERARCHY
    yield 'sibling_induction_provenance_rejected', indexed.replace('induction:Parent.gen.index',
        'induction:Parent.other.index'), 'NODAL-SHAPE-043-006', HIERARCHY

    yield 'missing_port_binding', boundary.design(child, generate(owned(boundary.instance()))), \
        'NODAL-VERIFY-HIERARCHY-012', HIERARCHY
    yield 'unbound_port_reference', boundary.design(child, generate(
        owned(boundary.instance()) + owned(reference()))), 'NODAL-VERIFY-HIERARCHY-012', HIERARCHY
    yield 'duplicate_port_reference', boundary.design(child, shared + generate(
        bound_child() + owned(reference(name='duplicate')))), 'NODAL-VERIFY-HIERARCHY-010', HIERARCHY
    yield 'duplicate_port_binding', boundary.design(child, shared + generate(
        bound_child() + owned(connect(path='second')))), 'NODAL-VERIFY-HIERARCHY-010', HIERARCHY
    yield 'unknown_child_definition', valid.replace('module = @Child', 'module = @Absent'), \
        'NODAL-VERIFY-HIERARCHY-004', HIERARCHY
    yield 'empty_unknown_child_definition', boundary.design('', generate(
        owned(boundary.instance(target='Absent')), lower=0, upper=0)), \
        'NODAL-VERIFY-HIERARCHY-004', HIERARCHY
    escaped = owned(reference()) + owned(connect())
    yield 'no_outer_instance_fallback', boundary.design(child, shared + boundary.instance() +
        generate(escaped)), 'NODAL-VERIFY-HIERARCHY-006', HIERARCHY
    yield 'no_sibling_instance_fallback', boundary.design(child, shared + generate(bound_child()) +
        generate(owned(reference(), 'Parent.other') + owned(connect(), 'Parent.other'),
                 'Parent.other', index='j')), 'NODAL-VERIFY-HIERARCHY-006', HIERARCHY
    yield 'unknown_child_port', valid.replace('port = "p"', 'port = "missing"'), \
        'NODAL-VERIFY-HIERARCHY-007', HIERARCHY
    yield 'wrong_port_direction', valid.replace('name = "u.p", direction = "inout"',
        'name = "u.p", direction = "input"'), 'NODAL-VERIFY-HIERARCHY-009', HIERARCHY
    other_terminal = '!nodal.terminal<"electrical_other">'
    mismatched = boundary.design(child, shared + generate(owned(boundary.instance()) +
        owned(reference().replace(TERMINAL, other_terminal)) +
        owned(connect().replace(f'({TERMINAL}, {TERMINAL})', f'({TERMINAL}, {other_terminal})'))))
    other_discipline = ('"nodal.discipline"() <{domain = "continuous", flow = @Current, '
                        'potential = @Voltage, metadata = {}, sym_name = "electrical_other"}> '
                        ': () -> ()\n')
    mismatched = mismatched.replace('"nodal.module"', other_discipline + '"nodal.module"', 1)
    yield 'wrong_port_discipline', mismatched, 'NODAL-VERIFY-HIERARCHY-008', HIERARCHY
    yield 'wrong_port_category', boundary.design(boundary.array('Child', 'p', '3', 'inout'),
        shared + generate(bound_child())), 'NODAL-VERIFY-HIERARCHY-008', HIERARCHY
    yield 'forged_generated_owner', valid.replace('generated_owner = "Parent.gen"',
        'generated_owner = "Parent.other"', 1), 'NODAL-ITERATION-043-004', HIERARCHY
    yield 'wrong_induction_type', valid.replace('^bb0(%i: i64)', '^bb0(%i: i32)'), \
        'NODAL-ITERATION-043-004', HIERARCHY
    yield 'extra_induction_argument', valid.replace('^bb0(%i: i64)', '^bb0(%i: i64, %j: i64)'), \
        'NODAL-ITERATION-043-004', HIERARCHY
    yield 'forged_trip_count', valid.replace('maximum_trip_count = 3 : i64',
        'maximum_trip_count = 2 : i64'), 'NODAL-ITERATION-043-001', 'nodal-verify-parameters'
    yield 'generated_definition_cycle', boundary.design(
        generate(owned(boundary.instance('back', target='Parent'), 'Child.gen'), 'Child.gen'),
        generate(owned(boundary.instance()))), 'NODAL-VERIFY-HIERARCHY-005', HIERARCHY

    requirement = '"nodal.domain_requirement"() <{sym_name = "clock", metadata = {}}> : () -> ()\n'
    domain = ('"nodal.domain"() <{sym_name = "clock", edge = "rising", reset_policy = "sync", '
              'metadata = {}}> : () -> ()\n')
    domain_instance = owned(boundary.instance().replace('domain_bindings = {}',
                                                     'domain_bindings = {clock = @clock}'))
    domain_valid = boundary.design(requirement, domain + generate(domain_instance))
    yield 'generated_domain_binding', domain_valid, None, 'nodal-verify-domains'
    yield 'unknown_generated_domain_actual', domain_valid.replace('clock = @clock', 'clock = @missing'), \
        'NODAL-VERIFY-DOMAIN-007', 'nodal-verify-domains'
    yield 'digital_profile_still_rejects_topology', valid.replace('nodal.target.profile = "analog"',
        'nodal.target.profile = "digital"'), 'NODAL-VERIFY-CAPABILITY-003', 'nodal-verify-capabilities'
    port = ('"nodal.port"() <{sym_name = "input", type = i1, direction = "input", '
            'domain = @clock, metadata = {}}> : () -> ()\n')
    yield 'mixed_profile_still_requires_bridge', boundary.design(child, domain + port + shared +
        generate(bound_child())), 'NODAL-VERIFY-ANALOG-004', 'nodal-verify-analog'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nodalc', required=True)
    parser.add_argument('--work-dir', required=True)
    parser.add_argument('--case', action='append', default=[])
    parser.add_argument('--emit-only', action='store_true')
    args = parser.parse_args()
    directory = Path(args.work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    selected_cases = [case for case in cases() if not args.case or case[0] in args.case]
    missing = set(args.case) - {case[0] for case in selected_cases}
    if missing:
        parser.error('unknown cases: ' + ', '.join(sorted(missing)))
    results = []
    for name, source, expected, stage in selected_cases:
        path = directory / f'{name}.mlir'
        path.write_text(source)
        if args.emit_only:
            print(path)
            continue
        command = [args.nodalc, f'--pass-pipeline=builtin.module({stage})', str(path)]
        run = subprocess.run(command, capture_output=True, timeout=30)
        (directory / f'{name}.stdout').write_bytes(run.stdout)
        (directory / f'{name}.stderr').write_bytes(run.stderr)
        codes = sorted(set(re.findall(r'NODAL-[A-Z0-9-]+', run.stderr.decode())))
        passed = run.returncode == (1 if expected else 0) and codes == ([expected] if expected else [])
        if expected:
            passed = passed and not run.stdout
        else:
            passed = passed and run.stdout.startswith(b'module')
            for operation in ('nodal.module', 'nodal.generate', 'nodal.instance',
                              'nodal.instance_terminal', 'nodal.instance_terminal_array',
                              'nodal.node', 'nodal.node_array', 'nodal.connect', 'nodal.connect_array'):
                passed = passed and run.stdout.count(f'"{operation}"'.encode()) == source.count(f'"{operation}"')
            if passed:
                replay = directory / f'{name}.roundtrip.mlir'
                replay.write_bytes(run.stdout)
                repeated = subprocess.run([args.nodalc, str(replay)], capture_output=True, timeout=30)
                gated = subprocess.run([args.nodalc, '--pass-pipeline=builtin.module(nodal-gate-default)',
                                        str(path)], capture_output=True, timeout=30)
                (directory / f'{name}.gate.stdout').write_bytes(gated.stdout)
                (directory / f'{name}.gate.stderr').write_bytes(gated.stderr)
                passed = repeated.returncode == 0 and repeated.stdout == run.stdout and gated.returncode == 0
                # These fixtures have no module-level scalar joins. Every
                # ordinary scalar declaration therefore owns one singleton set;
                # generated endpoint templates must never enter those sets.
                if gated.returncode == 0:
                    ordinary_scalars = sum(
                        bool(re.search(r'"nodal\.(terminal|node)"', line)) and
                        'generated_owner' not in line for line in source.splitlines())
                    sets = re.findall(rb'"nodal.connection_set"\(([^)]*)\)', gated.stdout)
                    passed = passed and len(sets) == ordinary_scalars and all(
                        operands.strip() and b',' not in operands for operands in sets)
                    expected_selections = source.count('"nodal.shape_index"') - (
                        1 if name == 'same_scope_selection_normalizes' else 0)
                    passed = passed and gated.stdout.count(b'"nodal.shape_index"') == expected_selections
                    gate_replay = directory / f'{name}.gate.roundtrip.mlir'
                    gate_replay.write_bytes(gated.stdout)
                    regated = subprocess.run([
                        args.nodalc, '--pass-pipeline=builtin.module(nodal-gate-default)',
                        str(gate_replay)], capture_output=True, timeout=30)
                    (directory / f'{name}.gate.roundtrip.stderr').write_bytes(regated.stderr)
                    passed = passed and regated.returncode == 0 and regated.stdout == gated.stdout
                if not passed:
                    print(gated.stderr.decode())
        results.append({'name': name, 'passed': bool(passed), 'codes': codes, 'command': command})
        print(('PASS ' if passed else 'FAIL ') + name)
        if not passed:
            print(run.stderr.decode())
    if args.emit_only:
        return 0
    (directory / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'{sum(result["passed"] for result in results)}/{len(results)} generated-topology checks passed')
    return 0 if all(result['passed'] for result in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
