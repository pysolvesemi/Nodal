#!/usr/bin/env python3
"""Native symmetric conservative relations and exact child-region coverage."""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import run_boundary_array_matrix as b
import run_generated_topology_matrix as g


def constants():
    return ''.join(b.literal(f'c{i}', i) for i in range(9))


def relation(left, right, dimensions, name):
    if dimensions:
        return b.connect(dimensions, actual=left, child=right, path=name)
    return g.connect(left, right, name)


def slice_(base, dimensions, result, axis, lower, upper, name):
    return (f'%{name} = "nodal.shape_slice"(%{base}, %c{lower}, %c{upper}, %c{upper-lower}) '
            f'<{{axis = {axis} : i64, dimensions = "{result}", origin = "Parent.{base}", '
            'observability = "source_mapped", metadata = {'
            f'input_path = "Parent.{base}", semantic_path = "Parent.{name}", '
            f'storage = "structural", access = "read_only"}}}}> '
            f': ({b.shaped(dimensions)}, i64, i64, i64) -> {b.shaped(result)}\n')


def view(base, dimensions, result, name):
    return (f'%{name} = "nodal.shape_view"(%{base}) <{{dimensions = "{result}", '
            f'origin = "Parent.{base}", materialization = "view", observability = "source_mapped", '
            f'metadata = {{input_path = "Parent.{base}", semantic_path = "Parent.{name}", storage = "structural", access = "read_only"}}}}> '
            f': ({b.shaped(dimensions)}) -> {b.shaped(result)}\n')


def select(base, dimensions, indices, name):
    return b.select('Parent', base, dimensions,
                    [(f'c{i}', f'literal:{i}') for i in indices], name)


def access(left, right, kind='potential', name='access'):
    function, dimension = ('V', 'voltage') if kind == 'potential' else ('I', 'current')
    return ('"nodal.analog"() <{metadata = {}}> ({\n'
            f'%{name} = "nodal.terminal_access"(%{left}, %{right}) <{{kind = "{kind}", '
            f'function = "{function}", metadata = {{semantic_path = "Parent.{name}"}}}}> '
            f': ({b.TERMINAL}, {b.TERMINAL}) -> !nodal.quantity<"real", "{dimension}">\n'
            '}) : () -> ()\n')


def partition(dimensions='4', cuts=(0, 2, 4)):
    child = b.array('Child', 'p', dimensions, 'inout')
    body = constants() + b.array('Parent', 'nodes', dimensions) + b.instance() + b.reference(dimensions)
    for n, (lower, upper) in enumerate(zip(cuts, cuts[1:])):
        size = str(upper - lower)
        body += slice_('nodes', dimensions, size, 0, lower, upper, f'a{n}')
        body += slice_('ref', dimensions, size, 0, lower, upper, f'r{n}')
        body += relation(f'a{n}', f'r{n}', size, f'join{n}')
    return child, body


def cases():
    child, body = partition()
    yield 'adjacent_child_partitions', b.design(child, body), None
    yield 'reversed_endpoint_order', b.design(child, body.replace('(%a0, %r0)', '(%r0, %a0)')), None
    yield 'missing_partition', b.design(child, body.replace(relation('a1', 'r1', '2', 'join1'), '')), 'NODAL-SHAPE-043-009'
    yield 'duplicate_partition', b.design(child, body + relation('a0', 'r0', '2', 'again')), 'NODAL-VERIFY-HIERARCHY-010'
    overlap = constants() + b.array('Parent', 'nodes', '4') + b.instance() + b.reference('4')
    overlap += slice_('ref', '4', '3', 0, 0, 3, 'first') + slice_('ref', '4', '3', 0, 1, 4, 'last')
    overlap += b.array('Parent', 'actual', '3') + relation('actual', 'first', '3', 'firstJoin') + relation('actual', 'last', '3', 'lastJoin')
    yield 'overlapping_child_regions', b.design(child, overlap), 'NODAL-SHAPE-043-009'
    yield 'forged_partial_full_root', b.design(child, b.array('Parent', 'nodes', '2') + b.instance() + b.reference('2') + relation('nodes', 'ref', '2', 'wrong')), 'NODAL-SHAPE-043-008'
    yield 'duplicate_whole', b.design(child, constants() + b.array('Parent', 'nodes', '4') + b.instance() + b.reference('4') + relation('nodes', 'ref', '4', 'first') + relation('nodes', 'ref', '4', 'second')), 'NODAL-VERIFY-HIERARCHY-010'
    local = constants() + b.array('Parent', 'a', '4') + b.array('Parent', 'z', '4') + relation('a', 'z', '4', 'join')
    yield 'local_local_whole', b.design('', local), None
    two = constants() + b.instance('u') + b.instance('v') + b.reference('4', name='left') + b.reference('4', name='right', instance_name='v') + relation('left', 'right', '4', 'children')
    yield 'child_child_whole', b.design(child, two), None
    for shape, axis, result in [('2,4', 1, '2,2'), ('4,2', 0, '2,2')]:
        body2 = constants() + b.instance() + b.reference(shape) + b.array('Parent', 'nodes', shape)
        for n, lower in enumerate((0, 2)):
            body2 += slice_('ref', shape, result, axis, lower, lower + 2, f'part{n}')
            body2 += slice_('nodes', shape, result, axis, lower, lower + 2, f'actual{n}')
            body2 += relation(f'actual{n}', f'part{n}', result, f'join{n}')
        yield ('column' if axis else 'row') + '_partition', b.design(b.array('Child', 'p', shape, 'inout'), body2), None
        reshaped = body2.replace(relation('actual0', 'part0', result, 'join0'), view('actual0', result, '4', 'aflat') + view('part0', result, '4', 'rflat') + relation('aflat', 'rflat', '4', 'join0'))
        yield ('column' if axis else 'row') + '_reshaped_image', b.design(b.array('Child', 'p', shape, 'inout'), reshaped), None
    boxes = constants() + b.instance() + b.reference('2,2') + b.array('Parent', 'nodes', '2,2')
    for i in range(2):
        for j in range(2):
            tag = f'{i}{j}'
            boxes += select('nodes', '2,2', [i,j], 'a'+tag) + select('ref', '2,2', [i,j], 'r'+tag)
            boxes += relation('a'+tag, 'r'+tag, '', 'join'+tag)
    yield 'scalar_grid_complete_coverage', b.design(b.array('Child', 'p', '2,2', 'inout'), boxes), None
    yield 'scalar_grid_missing_lane', b.design(b.array('Child', 'p', '2,2', 'inout'), boxes.replace(relation('a11', 'r11', '', 'join11'), '')), 'NODAL-SHAPE-043-009'
    for extent in ('1', '1000000000000'):
        yield 'whole_' + extent, b.design(b.array('Child', 'p', extent, 'inout'), b.array('Parent', 'nodes', extent) + b.instance() + b.reference(extent) + relation('nodes', 'ref', extent, 'join')), None
    body3 = constants() + b.array('Parent', 'nodes', '4') + g.generate(g.owned(b.instance()) + g.owned(b.reference('4')) + g.owned(relation('nodes', 'ref', '4', 'join')), upper=4)
    yield 'generated_full_child_per_occurrence', b.design(child, body3), None
    descendant = g.selected(base='a', dimensions='4', name='x') + g.selected(base='z', dimensions='4', name='y')
    descendant += access('x', 'y', 'flow').replace('metadata = {}', 'generated_owner = "Parent.gen", generated_induction = "Parent.gen.index", metadata = {}', 1)
    procedure = g.owned('"nodal.analog_procedure"() <{owner = "Parent", metadata = {}}> ({\n^bb0:\n}) : () -> ()\n')
    closing = descendant.rfind('}) : () -> ()')
    descendant = descendant[:closing] + procedure + descendant[closing:]
    yield 'ancestor_join_descendant_flow_identity', b.design('', local + g.generate(descendant, upper=4)), 'NODAL-ACCESS-FORM-001'
    family = g.selected(base='a', dimensions='4', name='gx') + g.selected(base='z', dimensions='4', name='gy') + g.owned(relation('gx', 'gy', '', 'family'))
    outside = select('a', '4', [0], 'x') + select('z', '4', [0], 'y') + access('x', 'y')
    roots = constants() + b.array('Parent', 'a', '4') + b.array('Parent', 'z', '4')
    yield 'descendant_family_outside_alias_unknown', b.design('', roots + g.generate(family, upper=4) + outside), 'NODAL-SHAPE-043-009'
    yield 'empty_descendant_family_inert', b.design('', roots + g.generate(family, upper=0) + outside), None
    invalid_empty = g.selected(base='a', dimensions='4', name='bad', indices=[('c8', 'literal:8')])
    yield 'empty_scope_still_rejects_bad_literal', b.design('', roots + g.generate(invalid_empty, upper=0)), 'NODAL-SHAPE-043-002'
    shared_family = g.selected(base='a', dimensions='4', name='ga') + g.selected(base='z', dimensions='4', name='gz', indices=[('c0', 'literal:0')])
    shared_family += g.owned(relation('ga', 'gz', '', 'sharedFamily'))
    shared_family += g.selected(base='a', dimensions='4', name='first', indices=[('c0', 'literal:0')]) + g.selected(base='a', dimensions='4', name='second', indices=[('c1', 'literal:1')])
    yield 'visible_repeated_family_not_injective_tree', b.design('', roots + g.generate(shared_family, upper=3)), 'NODAL-SHAPE-043-009'

    fresh_roots = constants() + b.array('Parent', 'taps', '3,2')
    fresh_selections = g.selected(base='taps', dimensions='3,2', name='leftLane',
                                  indices=[('i', 'induction:Parent.gen.index'), ('c0', 'literal:0')])
    fresh_selections += g.selected(base='taps', dimensions='3,2', name='rightLane',
                                   indices=[('i', 'induction:Parent.gen.index'), ('c1', 'literal:1')])
    fresh_locals = g.owned(g.scalar('Parent.gen', 'leftLeaf')) + g.owned(g.scalar('Parent.gen', 'rightLeaf'))
    fresh_locals += g.owned(relation('leftLane', 'leftLeaf', '', 'freshLeft'))
    fresh_locals += g.owned(relation('rightLane', 'rightLeaf', '', 'freshRight'))
    yield 'repeated_fresh_local_tree_keeps_lanes_distinct', b.design('', fresh_roots + g.generate(fresh_selections + fresh_locals)), None
    fresh_children = g.bound_child(actual='leftLane', name='leftChild')
    fresh_children += g.bound_child(instance='v', actual='rightLane', name='rightChild')
    yield 'repeated_fresh_child_tree_keeps_lanes_distinct', b.design(g.scalar('Child', 'p', 'inout'), fresh_roots + g.generate(fresh_selections + fresh_children)), None

    fresh_inner = (fresh_selections + fresh_locals).replace('Parent.gen', 'Parent.gen.inner')
    fresh_inner = fresh_inner.replace('induction:Parent.gen.inner.index', 'induction:Parent.gen.index')
    yield 'outer_repeated_fresh_inner_tree_keeps_lanes_distinct', b.design('', fresh_roots + g.generate(g.generate(fresh_inner, 'Parent.gen.inner', upper=1, index='j'))), None

    inner_scope = 'Parent.gen.inner'
    nested_family = g.owned(relation('ga', 'n', '', 'nestedFamily'), inner_scope)
    nested_family += g.selected(inner_scope, base='a', dimensions='4', name='first', indices=[('c0', 'literal:0')])
    nested_family += g.selected(inner_scope, base='a', dimensions='4', name='second', indices=[('c1', 'literal:1')])
    nested_access = g.owned(access('first', 'second', 'flow'), inner_scope)
    inner_procedure = g.owned('"nodal.analog_procedure"() <{owner = "Parent", metadata = {}}> ({\n^bb0:\n}) : () -> ()\n', inner_scope)
    closing = nested_access.rfind('}) : () -> ()')
    nested_access = nested_access[:closing] + inner_procedure + nested_access[closing:]
    nested_family += nested_access
    outer_family = g.selected(base='a', dimensions='4', name='ga')
    outer_family += g.generate(nested_family, inner_scope, upper=1, index='j')
    nested_roots = constants() + b.array('Parent', 'a', '4') + g.scalar('Parent', 'n')
    yield 'outer_repeated_inner_singleton_family_unknown', b.design('', nested_roots + g.generate(outer_family, upper=2)), 'NODAL-SHAPE-043-009'
    yield 'nested_singleton_family_distinct_lanes', b.design('', nested_roots + g.generate(outer_family.replace(nested_access, ''), upper=1)), None

    per_lane = g.owned(b.instance()) + g.owned(b.reference('4')) + g.selected(base='ref', dimensions='4', name='childLane') + g.selected(base='nodes', dimensions='4', name='actualLane') + g.owned(relation('childLane', 'actualLane', '', 'laneJoin'))
    yield 'generated_single_lane_not_whole_child', b.design(child, constants() + b.array('Parent', 'nodes', '4') + g.generate(per_lane, upper=4)), 'NODAL-SHAPE-043-009'
    alias = local + select('a', '4', [1], 'x') + select('z', '4', [1], 'y')
    yield 'whole_relation_potential_same_endpoint', b.design('', alias + access('x', 'y')), None
    yield 'whole_relation_flow_same_endpoint', b.design('', alias + access('x', 'y', 'flow')), 'NODAL-ACCESS-FORM-001'
    yield 'whole_relation_distinct_coordinates', b.design('', local + select('a', '4', [0], 'x') + select('z', '4', [1], 'y') + access('x', 'y', 'flow')), None
    partial = constants() + b.array('Parent', 'a', '4') + b.array('Parent', 'z', '4')
    partial += slice_('a', '4', '2', 0, 0, 2, 'left') + slice_('z', '4', '2', 0, 2, 4, 'right') + relation('left', 'right', '2', 'partial')
    partial += select('a', '4', [1], 'x') + select('z', '4', [3], 'y')
    yield 'partial_relation_inverse_potential', b.design('', partial + access('x', 'y')), None
    yield 'partial_relation_inverse_flow', b.design('', partial + access('x', 'y', 'flow')), 'NODAL-ACCESS-FORM-001'
    column_alias = constants() + b.array('Parent', 'a', '2,4') + b.array('Parent', 'z', '2,4')
    column_alias += slice_('a', '2,4', '2,2', 1, 0, 2, 'acols') + slice_('z', '2,4', '2,2', 1, 2, 4, 'zcols')
    column_alias += view('acols', '2,2', '4', 'aflat') + view('zcols', '2,2', '4', 'zflat') + relation('aflat', 'zflat', '4', 'columnJoin')
    column_alias += select('a', '2,4', [1,1], 'x') + select('z', '2,4', [1,3], 'y')
    yield 'column_reshape_inverse_potential', b.design('', column_alias + access('x', 'y')), None
    yield 'column_reshape_inverse_flow', b.design('', column_alias + access('x', 'y', 'flow')), 'NODAL-ACCESS-FORM-001'
    scalar_alias = constants() + b.array('Parent', 'a', '1') + select('a', '1', [0], 'x') + g.scalar('Parent', 'n') + relation('x', 'n', '', 'scalarJoin')
    yield 'selected_to_scalar_potential', b.design('', scalar_alias + access('x', 'n')), None
    yield 'selected_to_scalar_flow', b.design('', scalar_alias + access('x', 'n', 'flow')), 'NODAL-ACCESS-FORM-001'
    grouping = local + select('a', '4', [0], 'a0') + select('a', '4', [1], 'a1') + select('z', '4', [0], 'z0') + select('z', '4', [1], 'z1')
    grouping += access('a0', 'a1', name='first') + access('z0', 'z1', 'flow', name='second')
    yield 'aliased_source_free_probe_group_conflict', b.design('', grouping), 'gate:NODAL-PROBE-KIND-001'
    child_ports = b.array('Child', 'a', '4', 'inout') + b.array('Child', 'z', '4', 'inout')
    parent_ports = constants() + b.array('Parent', 'a', '4') + b.array('Parent', 'z', '4') + b.instance()
    parent_ports += b.reference('4', name='ra', port='a') + b.reference('4', name='rz', port='z')
    parent_ports += relation('a', 'ra', '4', 'aJoin') + relation('z', 'rz', '4', 'zJoin')
    parent_ports += select('a', '4', [0], 'x') + select('z', '4', [0], 'y') + access('x', 'y', 'flow')
    yield 'unjoined_child_boundaries_remain_distinct', b.design(child_ports, parent_ports), None
    child_join = relation('a', 'z', '4', 'inside').replace('Parent.', 'Child.')
    yield 'child_internal_true_net_alias_unknown', b.design(child_ports + child_join, parent_ports), 'NODAL-SHAPE-043-009'
    same_root_reads = parent_ports.replace(select('z', '4', [0], 'y'), select('a', '4', [1], 'y'))
    yield 'child_cross_port_tree_same_root_lanes_distinct', b.design(child_ports + child_join, same_root_reads), None

    tree_child = b.array('Child', 'p', '4', 'inout') + b.array('Child', 'inside', '4')
    tree_child += relation('p', 'inside', '4', 'tree').replace('Parent.', 'Child.')
    tree_parent = constants() + b.array('Parent', 'nodes', '4') + b.instance() + b.reference('4') + relation('nodes', 'ref', '4', 'whole')
    tree_parent += select('nodes', '4', [0], 'x') + select('nodes', '4', [1], 'y') + access('x', 'y', 'flow')
    yield 'child_injective_tree_preserves_distinct_lanes', b.design(tree_child, tree_parent), None

    child_branch = b.literal('zero', 0) + b.select('Child', 'a', '4', [('zero', 'literal:0')], 'ca')
    child_branch += b.select('Child', 'z', '4', [('zero', 'literal:0')], 'cz')
    child_branch += (f'%device = "nodal.branch"(%ca, %cz) <{{name = "device", declaration_kind = "named", '
                     f'source_path = "Child.device", metadata = {{}}}}> : ({b.TERMINAL}, {b.TERMINAL}) -> !nodal.branch<"electrical">\n')
    yield 'child_branch_is_not_net_alias', b.design(child_ports + child_branch, parent_ports), None
    self_join = constants() + b.array('Parent', 'nodes', '4') + slice_('nodes', '4', '3', 0, 0, 3, 'left') + slice_('nodes', '4', '3', 0, 1, 4, 'right') + relation('left', 'right', '3', 'shift')
    yield 'overlapping_self_relation_capability', b.design('', self_join), 'NODAL-SHAPE-043-009'
    yield 'same_map_self_relation', b.design('', b.array('Parent', 'nodes', '4') + relation('nodes', 'nodes', '4', 'self')), None
    yield 'wrong_relation_rank', b.design('', b.array('Parent', 'nodes', '4') + b.array('Parent', 'other', '2,2') + b.connect('4', child_dimensions='2,2', child='other')), 'NODAL-SHAPE-043-008'
    symbolic = b.parameter('Child', 'N', 2, 8, 4) + b.array('Child', 'p', 'N', 'inout')
    symbolic_body = b.parameter('Parent', 'N', 2, 8, 4) + b.array('Parent', 'nodes', 'N') + b.instance() + b.override('N', 'N') + b.reference('N') + relation('nodes', 'ref', 'N', 'join')
    yield 'exact_symbolic_full_root', b.design(symbolic, symbolic_body), None
    row_child = b.parameter('Child', 'N', 2, 8, 4) + b.array('Child', 'p', '2,N', 'inout')
    row_body = constants() + b.parameter('Parent', 'N', 2, 8, 4)
    row_body += b.array('Parent', 'first', 'N') + b.array('Parent', 'second', 'N')
    row_body += b.instance() + b.override('N', 'N') + b.reference('2,N')
    for index, root in enumerate(('first', 'second')):
        row_body += (f'%row{index} = "nodal.shape_index"(%ref, %c{index}) <{{metadata = {{'
                     f'input_path = "Parent.ref", semantic_path = "Parent.row{index}", '
                     'storage = "structural", access = "read_only"}}> '
                     f': ({b.shaped("2,N")}, i64) -> {b.shaped("N")}\n')
        row_body += relation(root, f'row{index}', 'N', f'rowJoin{index}')
    yield 'symbolic_child_row_partition', b.design(row_child, row_body), None
    yield 'default_is_not_effective_type', b.design(symbolic, symbolic_body.replace(b.reference('N'), b.reference('4')).replace(relation('nodes', 'ref', 'N', 'join'), b.connect('N', child_dimensions='4', path='join'))), 'NODAL-SHAPE-043-008'
    symbolic_child = b.parameter('Child', 'N', 4, 8, 4) + b.array('Child', 'p', 'N', 'inout')
    symbolic_parts = constants() + b.parameter('Parent', 'N', 4, 8, 4) + b.expression('Parent', 'tail', 'sub', ['N', 'c2'])
    symbolic_parts += b.array('Parent', 'nodes', 'N') + b.instance() + b.override('N', 'N') + b.reference('N')
    for root, prefix_name in [('nodes', 'a'), ('ref', 'r')]:
        symbolic_parts += slice_(root, 'N', '2', 0, 0, 2, prefix_name+'first')
        symbolic_parts += (f'%{prefix_name}last = "nodal.shape_slice"(%{root}, %c2, %N, %tail) '
                           f'<{{axis = 0 : i64, dimensions = "Parent.tail", origin = "Parent.{root}", '
                           'observability = "source_mapped", metadata = {'
                           f'input_path = "Parent.{root}", semantic_path = "Parent.{prefix_name}last", '
                           'storage = "structural", access = "read_only"}}> '
                           f': ({b.shaped("N")}, i64, i64, i64) -> {b.shaped("Parent.tail")}\n')
    symbolic_parts += relation('afirst', 'rfirst', '2', 'first') + relation('alast', 'rlast', 'Parent.tail', 'last')
    yield 'symbolic_exact_cut_partition', b.design(symbolic_child, symbolic_parts), None
    delayed_child = b.parameter('Child', 'N', 2, 8, 2) + b.array('Child', 'p', 'N', 'inout')
    yield 'override_after_view_uses_final_environment', b.design(delayed_child, body + b.override('N', 'c4')), None
    yield 'forged_child_root_path', b.design(child, body.replace('semantic_path = "Parent.ref"', 'semantic_path = "Foreign.ref"')), 'NODAL-SHAPE-043-008'
    opaque = b.parameter('Parent', 'N', 0, 3, 1) + b.parameter('Parent', 'M', 0, 3, 1) + local
    opaque += b.select('Parent', 'a', '4', [('N', 'parameter:Parent.N')], 'x') + b.select('Parent', 'z', '4', [('M', 'parameter:Parent.M')], 'y')
    yield 'relation_opaque_equal_envelopes_unknown', b.design('', opaque), 'NODAL-SHAPE-043-009'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--nodalc', required=True)
    parser.add_argument('--work-dir', required=True)
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    selected_cases = list(cases())
    unknown = set(args.case or ()) - {name for name, _, _ in selected_cases}
    if unknown:
        parser.error('unknown case selector(s): ' + ', '.join(sorted(unknown)))
    directory = Path(args.work_dir)
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    for name, source, expected in selected_cases:
        if args.case and name not in args.case:
            continue
        path = directory / f'{name}.mlir'
        path.write_text(source)
        command = [args.nodalc]
        if expected and expected.startswith('gate:'):
            command.append('--pass-pipeline=builtin.module(nodal-gate-default)')
            expected = expected.removeprefix('gate:')
        run = subprocess.run(command + [str(path)], capture_output=True, timeout=30)
        (directory / f'{name}.stdout').write_bytes(run.stdout)
        (directory / f'{name}.stderr').write_bytes(run.stderr)
        codes = sorted(set(re.findall(r'NODAL-[A-Z0-9-]+', run.stderr.decode())))
        passed = run.returncode == (1 if expected else 0) and codes == ([expected] if expected else [])
        if not expected and passed:
            replay = directory / f'{name}.roundtrip.mlir'
            replay.write_bytes(run.stdout)
            repeat = subprocess.run([args.nodalc, str(replay)], capture_output=True, timeout=30)
            gated = subprocess.run([args.nodalc, '--pass-pipeline=builtin.module(nodal-gate-default)', str(path)], capture_output=True, timeout=30)
            (directory / f'{name}.gate.stdout').write_bytes(gated.stdout)
            (directory / f'{name}.gate.stderr').write_bytes(gated.stderr)
            passed = repeat.returncode == 0 and repeat.stdout == run.stdout and gated.returncode == 0
            if gated.returncode:
                print(gated.stderr.decode())
        results.append({'name': name, 'passed': passed, 'codes': codes})
        print(('PASS ' if passed else 'FAIL ') + name)
        if not passed:
            print(run.stderr.decode())
    (directory / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'{sum(result["passed"] for result in results)}/{len(results)} conservative-relation checks passed')
    return 0 if results and all(result['passed'] for result in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
