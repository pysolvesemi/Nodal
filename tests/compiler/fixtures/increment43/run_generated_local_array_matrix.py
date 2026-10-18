#!/usr/bin/env python3
"""Focused native generated-local conservative array and lexical-proof witnesses.

These fixtures exercise the real parser and default semantic gate. They are not
Scala, target-generation, simulator, or exhaustive qualification evidence. All
raw fixtures, compiler output and receipts stay below the repository .work path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

sys.dont_write_bytecode = True
import run_boundary_array_matrix as b
import run_generated_probe_placement_matrix as p
import run_generated_topology_matrix as g

ROOT = Path(__file__).resolve().parents[4]
PIPELINE = '--pass-pipeline=builtin.module(nodal-gate-default)'
SCOPE = 'Parent.gen'
OTHER = 'Parent.other'
INNER = SCOPE + '.inner'
DECLARATION = 'NODAL-SHAPE-043-005'
OWNER = 'NODAL-ITERATION-043-004'
DIMENSIONS = ('node-array dimensions require positive finite owned proofs and '
              'signed 64-bit cardinality')
IDENTITY = 'node array requires canonical structural declaration identity'
OWNERSHIP = 'structural operation requires its exact generated owner and induction'


@dataclass(frozen=True)
class Case:
    name: str
    source: str
    probes: tuple[p.Probe, ...] = ()
    diagnostic: str | None = None
    cause: str | None = None


def constants():
    return ''.join(b.literal(f'c{value}', value) for value in range(5))


def array(name='a', dimensions='3', scope=SCOPE):
    # Source identity remains module-owned. Actual region membership and the
    # paired generated attributes, not a manufactured path prefix, prove scope.
    return g.owned(b.array('Parent', name, dimensions), scope)


def generate(body, scope=SCOPE, *, noarg=False, **kwargs):
    source = g.generate(body, scope, **kwargs)
    if noarg:
        source = re.sub(r'\^bb0\(%[^:]+: i64\):', '^bb0:', source, count=1)
    return source


def selected(base='a', dimensions='3', name='selected', *, scope=SCOPE,
             indices=(('c0', 'literal:0'),)):
    return g.selected(scope, base=base, dimensions=dimensions, name=name,
                      indices=list(indices))


def view(base, dimensions, result, name, scope=SCOPE):
    return g.owned(
        f'%{name} = "nodal.shape_view"(%{base}) <{{dimensions = "{result}", '
        f'origin = "Parent.{base}", materialization = "view", '
        'observability = "source_mapped", metadata = {'
        f'input_path = "Parent.{base}", semantic_path = "Parent.{name}", '
        'storage = "structural", access = "read_only"}}> '
        f': ({b.shaped(dimensions)}) -> {b.shaped(result)}\n', scope)


def slice_(base, dimensions, result, name, *, scope=SCOPE, axis=1,
           lower='c1', upper='c3', length='c2'):
    return g.owned(
        f'%{name} = "nodal.shape_slice"(%{base}, %{lower}, %{upper}, %{length}) '
        f'<{{axis = {axis} : i64, dimensions = "{result}", origin = "Parent.{base}", '
        'observability = "source_mapped", metadata = {'
        f'input_path = "Parent.{base}", semantic_path = "Parent.{name}", '
        'storage = "structural", access = "read_only"}}> '
        f': ({b.shaped(dimensions)}, i64, i64, i64) -> {b.shaped(result)}\n', scope)


def prefix(base, dimensions, result, name, *, scope=SCOPE, index='c1'):
    return g.owned(b.prefix(base, dimensions, result, index, name), scope)


def relation(left, right, dimensions, *, scope=SCOPE, name='join'):
    return g.owned(b.connect(dimensions, actual=left, child=right,
                             path=scope.removeprefix('Parent.') + '.' + name), scope)


def read(left='left', right='right', *, scope=SCOPE, kind='potential', name='read'):
    return p.access(left, right, scope=scope, kind=kind, name=name)


def endpoint(base, scope=SCOPE, tokens=('literal:0',)):
    return 'Parent.' + base + '.at(' + ','.join(tokens) + ').in(' + scope + ')'


def probe(left, right, *, scope=SCOPE, kind='potential', name='read'):
    return p.expectation(scope, kind, (left, right), (scope + '.' + name,))


def design(body, prefix_=''):
    return b.design('', prefix_ + body)


def branch(left, right, name, scope=SCOPE):
    return g.owned(
        f'%{name} = "nodal.branch"(%{left}, %{right}) <{{declaration_kind = "implicit", '
        f'source_path = "Parent.{name}", metadata = {{identity = "Parent.{name}", '
        f'semantic_path = "Parent.{name}"}}}}> : ({b.TERMINAL}, {b.TERMINAL}) '
        '-> !nodal.branch<"electrical">\n', scope)


def contribution(branch_name, value, value_type, scope=SCOPE):
    return g.owned(
        f'"nodal.contribute"(%{branch_name}, %{value}) <{{kind = "flow", '
        f'metadata = {{semantic_path = "{scope}.source"}}}}> : '
        f'(!nodal.branch<"electrical">, {value_type}) -> ()\n', scope)


def alias_regression_cases():
    # Reduced from the actual public nested-array witness: a source-free local
    # branch and a driven, disconnected module-array branch share a reference.
    outer = (array('a', '2,3') + slice_('a', '2,3', '2,2', 'columns') +
             view('columns', '2,2', '4', 'flat') + view('flat', '4', '2,2', 'reshaped'))
    local_indices = (('j', 'induction:' + INNER + '.index'), ('c1', 'literal:1'))
    output_indices = (('i', 'induction:' + SCOPE + '.index'),
                      ('j', 'induction:' + INNER + '.index'))
    inner = (selected('reshaped', '2,2', 'local', scope=INNER, indices=local_indices) +
             selected('outputs', '2,2', 'output', scope=INNER, indices=output_indices) +
             branch('output', 'common', 'driven', INNER))
    body = p.access('local', 'common', scope=INNER, kind='flow', name='local_read', wrapped=False)
    body += p.access('output', 'common', scope=INNER, kind='flow', name='output_read', wrapped=False)
    body += contribution('driven', 'local_read', '!nodal.quantity<"real", "current">', INNER)
    inner += (g.owned('"nodal.analog"() <{metadata = {}}> ({\n', INNER) +
              body + '}) : () -> ()\n')
    yield Case('nested_composed_local_disconnected_module_source', design(generate(
        outer + generate(inner, INNER, index='j', upper=2), upper=2), constants() +
        b.array('Parent', 'outputs', '2,2', 'inout') + g.scalar('Parent', 'common', 'inout')),
        (probe(endpoint('reshaped', INNER, tuple(token for _, token in local_indices)),
               'Parent.common', scope=INNER, kind='flow', name='local_read'),))

    # Each repeated template has two distinct local lanes attached to two
    # shared scalars. Disjoint local regions cannot be collapsed to one root.
    local = (array('a', '2') + selected('a', '2', 'left') +
             selected('a', '2', 'right', indices=(('c1', 'literal:1'),)) +
             g.owned(g.connect('left', 'p', SCOPE + '.left_join')) +
             g.owned(g.connect('right', 'n', SCOPE + '.right_join')) +
             branch('left', 'right', 'device'))
    # Preserve the public bridge's consumed legacy numeric branch-access form.
    driven = ('%flow = "nodal.access"(%device) <{kind = "flow", '
              'metadata = {semantic_path = "Parent.gen.flow"}}> : '
              '(!nodal.branch<"electrical">) -> f64\n'
              '%voltage = "nodal.access"(%device) <{kind = "potential", '
              'metadata = {semantic_path = "Parent.gen.voltage"}}> : '
              '(!nodal.branch<"electrical">) -> f64\n')
    driven += contribution('device', 'voltage', 'f64')
    local += (g.owned('"nodal.analog"() <{metadata = {}}> ({\n', SCOPE) +
              driven + '}) : () -> ()\n')
    yield Case('fresh_repeated_two_shared_scalar_sources', design(generate(local, noarg=True, upper=2),
        constants() + g.scalar('Parent', 'p', 'inout') + g.scalar('Parent', 'n', 'inout')))

    # The same fresh local lanes mapped onto overlapping shared regions really
    # do short shared lanes. This must not acquire the disjoint-region proof.
    overlap = (array('a', '2') +
               slice_('shared', '3', '2', 'first', axis=0, lower='c0', upper='c2') +
               slice_('shared', '3', '2', 'last', axis=0, lower='c1', upper='c3') +
               relation('a', 'first', '2', name='first_join') +
               relation('a', 'last', '2', name='last_join') +
               selected('shared', '3', 'left') +
               selected('shared', '3', 'right', indices=(('c1', 'literal:1'),)) + read(kind='flow'))
    yield Case('reject_fresh_overlapping_shared_region_short', design(generate(overlap, upper=2),
        constants() + b.array('Parent', 'shared', '3')), diagnostic='NODAL-ACCESS-FORM-001',
        cause='oriented flow access requires distinct terminals')


def cases():
    yield from alias_regression_cases()
    declaration = design(generate(array()))
    yield Case('fixed_local_declaration', declaration)
    yield Case('constant_only_noarg_scope', design(generate(
        array() + array('z') + selected(name='left') + selected('z', name='right') + read(),
        noarg=True), constants()),
        (probe(endpoint('a'), endpoint('z')),))

    composed = array('a', '2,4') + array('z', '2,4')
    for base in ('a', 'z'):
        composed += slice_(base, '2,4', '2,2', base + 'cols')
        composed += view(base + 'cols', '2,2', '4', base + 'flat')
    composed += relation('aflat', 'zflat', '4')
    composed += selected('aflat', '4', 'left')
    composed += selected('zflat', '4', 'right', indices=(('c1', 'literal:1'),))
    composed += read(kind='flow')
    yield Case('composed_slices_views_relation_flow', design(generate(composed), constants()),
        (probe(endpoint('aflat'), endpoint('zflat', tokens=('literal:1',)), kind='flow'),))

    prefixed = (array('a', '2,3') + array('z') + prefix('a', '2,3', '3', 'row') +
                relation('row', 'z', '3') + selected('row', name='left') +
                selected('z', name='right', indices=(('c1', 'literal:1'),)) + read())
    yield Case('prefix_relation_potential', design(generate(prefixed), constants()),
        (probe(endpoint('row'), endpoint('z', tokens=('literal:1',))),))

    parameter = b.parameter('Parent', 'N', 2, 6)
    symbolic = (array('a', 'N') + array('z', 'N') +
                selected('a', 'N', 'left', indices=(('i', 'induction:' + SCOPE + '.index'),)) +
                selected('z', 'N', 'right', indices=(('i', 'induction:' + SCOPE + '.index'),)) +
                read(kind='flow'))
    yield Case('module_parameter_dimension_induction_selection', design(generate(
        symbolic, upper='@N', count=6), parameter),
        (probe(endpoint('a', tokens=('induction:' + SCOPE + '.index',)),
               endpoint('z', tokens=('induction:' + SCOPE + '.index',)), kind='flow'),))

    extent = parameter + constants() + b.expression('Parent', 'extent', 'add', ['N', 'c1'])
    compound = (array('a', '2,Parent.extent') + array('z', 'Parent.extent,2') +
                view('a', '2,Parent.extent', 'Parent.extent,2', 'reshaped') +
                relation('reshaped', 'z', 'Parent.extent,2') +
                selected('reshaped', 'Parent.extent,2', 'left',
                         indices=(('c0', 'literal:0'), ('c0', 'literal:0'))) +
                selected('z', 'Parent.extent,2', 'right',
                         indices=(('c0', 'literal:0'), ('c1', 'literal:1'))) + read())
    yield Case('module_compound_dimension_reshape_relation', design(generate(compound), extent),
        (probe(endpoint('reshaped', tokens=('literal:0', 'literal:0')),
               endpoint('z', tokens=('literal:0', 'literal:1'))),))

    inner = (prefix('a', '2,3', '3', 'row', scope=INNER) + array('z', scope=INNER) +
             relation('row', 'z', '3', scope=INNER) +
             selected('row', name='left', scope=INNER) +
             selected('z', name='right', scope=INNER, indices=(('c1', 'literal:1'),)) +
             read(scope=INNER, kind='flow'))
    nested = generate(array('a', '2,3') + generate(inner, INNER, index='j', upper=2))
    yield Case('nested_descendant_uses_ancestor_root', design(nested, constants()),
        (probe(endpoint('row', INNER), endpoint('z', INNER, ('literal:1',)),
               scope=INNER, kind='flow'),))

    siblings = ''
    expectations = []
    for scope, index, kind in ((SCOPE, 'i', 'potential'), (OTHER, 'j', 'flow')):
        body = (array('a', scope=scope) + array('z', scope=scope) +
                selected(name='left', scope=scope) + selected('z', name='right', scope=scope) +
                read(scope=scope, kind=kind))
        siblings += generate(body, scope, index=index, noarg=True)
        expectations.append(probe(endpoint('a', scope), endpoint('z', scope),
                                  scope=scope, kind=kind))
    yield Case('fresh_same_name_sibling_roots', design(siblings, constants()), tuple(expectations))

    module_relation = (array() + relation('a', 'shared', '3') + selected(name='left') +
                       selected('shared', name='right', indices=(('c1', 'literal:1'),)) + read())
    yield Case('local_to_module_visible_relation', design(generate(module_relation),
        constants() + b.array('Parent', 'shared', '3')),
        (probe(endpoint('a'), endpoint('shared', tokens=('literal:1',))),))
    child_relation = (array() + g.owned(b.instance()) + g.owned(b.reference('3')) +
                      relation('a', 'ref', '3') + selected(name='left') +
                      selected(name='right', indices=(('c1', 'literal:1'),)) + read(kind='flow'))
    yield Case('local_to_exact_generated_child_relation', b.design(
        b.array('Child', 'p', '3', 'inout'), constants() + generate(child_relation)),
        (probe(endpoint('a'), endpoint('a', tokens=('literal:1',)), kind='flow'),))
    yield Case('empty_domain_retains_checked_declaration',
               design(generate(array(), upper=0)))
    yield Case('singleton_domain_local_declaration',
               design(generate(array(dimensions='1'), upper=1)))

    for name, before, after in (
            ('missing_owner', f'generated_owner = "{SCOPE}", ', ''),
            ('missing_induction', f'generated_induction = "{SCOPE}.index", ', ''),
            ('wrong_owner', f'generated_owner = "{SCOPE}"', f'generated_owner = "{OTHER}"'),
            ('wrong_induction', f'generated_induction = "{SCOPE}.index"',
             f'generated_induction = "{OTHER}.index"')):
        yield Case('reject_' + name, declaration.replace(before, after, 1),
                   diagnostic=OWNER, cause=OWNERSHIP)
    yield Case('reject_module_claims_generated_owner', design(array()), diagnostic=OWNER,
               cause='module operation cannot claim generated ownership')
    yield Case('reject_forged_source_path', declaration.replace('source_path = "Parent.a"',
        'source_path = "Foreign.a"'), diagnostic=DECLARATION, cause=IDENTITY)
    yield Case('reject_forged_semantic_path', declaration.replace('semantic_path = "Parent.a"',
        'semantic_path = "Foreign.a"'), diagnostic=DECLARATION, cause=IDENTITY)
    yield Case('reject_region_prefix_as_source_identity', declaration.replace('"Parent.a"',
        f'"{SCOPE}.a"'), diagnostic=DECLARATION, cause=IDENTITY)
    yield Case('reject_procedural_root_storage', declaration.replace('storage = "structural"',
        'storage = "procedural"'), diagnostic=DECLARATION, cause=IDENTITY)
    yield Case('reject_duplicate_same_scope_root', design(generate(array() +
        array().replace('%a =', '%duplicate ='))), diagnostic=DECLARATION,
        cause='node-array declaration identity must be unique')
    yield Case('reject_wrong_terminal_discipline', declaration.replace(b.TERMINAL,
        '!nodal.terminal<"thermal">'), diagnostic=DECLARATION,
        cause='node array requires directly owned shaped Electrical terminals')
    yield Case('reject_quantity_element_units', declaration.replace(b.TERMINAL,
        '!nodal.quantity<"real", "voltage">'), diagnostic=DECLARATION,
        cause='node array requires directly owned shaped Electrical terminals')
    for name, dimensions in (('unknown_dimension', 'Missing'),
                             ('induction_dimension', SCOPE + '.index'),
                             ('cardinality_overflow', '9223372036854775807,2')):
        yield Case('reject_' + name, design(generate(array(dimensions=dimensions))),
                   diagnostic=DECLARATION, cause=DIMENSIONS)
    yield Case('reject_nonpositive_parameter_dimension', design(generate(array(dimensions='N')),
        b.parameter('Parent', 'N', 0, 3, 1)), diagnostic=DECLARATION, cause=DIMENSIONS)
    local_expression = b.expression('Parent', 'extent', 'add', ['c1', 'c2'])
    yield Case('reject_generated_static_dimension_dag', design(generate(local_expression +
        array(dimensions='Parent.extent')), constants()), diagnostic=DECLARATION, cause=DIMENSIONS)
    yield Case('reject_empty_domain_unknown_dimension',
               design(generate(array(dimensions='Missing'), upper=0)),
               diagnostic=DECLARATION, cause=DIMENSIONS)

    wrong_view = array() + view('a', '3', '2', 'bad')
    yield Case('reject_view_cardinality_forgery', design(generate(wrong_view)),
        diagnostic='NODAL-SHAPE-043-003',
        cause='shape view requires equal literal products and bounded structural static-expression '
              'root multisets within signed 64-bit counts')
    wrong_slice = array('a', '2,4') + slice_('a', '2,4', '2,2', 'columns').replace(
        'origin = "Parent.a"', 'origin = "Parent.other"')
    yield Case('reject_slice_source_forgery', design(generate(wrong_slice), constants()),
        diagnostic='NODAL-SHAPE-043-004',
        cause='slice requires owned source-mapped read-only structural identity')
    fake_root = (array() + f'%fake = "nodal.dynamic_value"(%a) <{{origin = "runtime", '
                 f'metadata = {{semantic_path = "Parent.fake"}}}}> : ({b.shaped("3")}) -> '
                 f'{b.shaped("3")}\n' + selected('fake'))
    yield Case('reject_nonconservative_root_kind', design(generate(fake_root), constants()),
        diagnostic='NODAL-SHAPE-043-006',
        cause='conservative shape capability requires an actual conservative array root')
    scalar = array() + selected()
    yield Case('reject_scalar_source_forgery', design(generate(scalar.replace(
        'input_path = "Parent.a"', 'input_path = "Parent.forged"')), constants()),
        diagnostic='NODAL-SHAPE-043-006',
        cause='conservative projection requires owned immediate-input structural provenance')
    boundary = g.owned(b.array('Parent', 'p', '3', 'inout'))
    yield Case('reject_generated_boundary_declaration', design(generate(boundary)),
               diagnostic='', cause="'nodal.terminal_array' op expects parent op 'nodal.module'")

    # SSA lexical visibility is itself a native trust boundary. Keep these
    # parser-level failures distinct from semantic metadata proof failures.
    module_escape = generate(array()) + b.select('Parent', 'a', '3', [('c0', 'literal:0')])
    sibling_escape = generate(array()) + generate(selected(scope=OTHER), OTHER, index='j')
    view_escape = generate(array() + view('a', '3', '3', 'escaped')) + generate(
        selected('escaped', scope=OTHER), OTHER, index='j')
    for name, body in (('module_root_escape', module_escape),
                       ('sibling_root_escape', sibling_escape),
                       ('sibling_view_escape', view_escape)):
        yield Case('reject_' + name, design(body, constants()), diagnostic='',
                   cause='use of undeclared SSA value name')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def root_inventory(source):
    operations, _, _ = p.inspect(source)
    lines = source.splitlines()
    return Counter((scope, *(p.text_attr(lines[number], field) for field in
                    ('name', 'source_path', 'semantic_path', 'generated_owner',
                     'generated_induction')), lines[number].split(' -> ', 1)[1])
                   for operation, scope, number in operations if operation == 'nodal.node_array')


def validate_positive(case, normalized):
    p.validate_positive(p.Case(case.name, case.source, case.probes), normalized)
    if case.source.count('"nodal.contribute"') != normalized.count('"nodal.contribute"'):
        raise AssertionError('source contribution inventory changed')
    before, after = root_inventory(case.source), root_inventory(normalized)
    if not before or before != after:
        raise AssertionError(f'local root declaration inventory changed: {before!r} != {after!r}')
    for record in after:
        scope, name, source, semantic, owner, induction, _ = record
        if source != 'Parent.' + name or semantic != source:
            raise AssertionError('generated root lost canonical authored source identity')
        expected_owner = (None, None) if scope == 'Parent' else (scope, scope + '.index')
        if (owner, induction) != expected_owner:
            raise AssertionError('generated root escaped its actual immediate owner')


def run(compiler, path, output, label, *, gate=True):
    result = subprocess.run([str(compiler), *([PIPELINE] if gate else []), str(path)],
                            capture_output=True, text=True, timeout=30, check=False)
    (output / (label + '.stdout')).write_text(result.stdout)
    (output / (label + '.stderr')).write_text(result.stderr)
    return result


def validate_negative(case, result):
    expected = {case.diagnostic} if case.diagnostic else set()
    codes = set(p.CODES.findall(result.stderr))
    if result.returncode != 1 or codes != expected:
        raise AssertionError(f'expected exit 1 and {expected}, got exit {result.returncode}, '
                             f'{codes}: {result.stderr}')
    if not case.cause or case.cause not in result.stderr:
        raise AssertionError(f'expected causal diagnostic {case.cause!r}: {result.stderr}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nodalc', type=Path)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--case', action='append')
    parser.add_argument('--emit-only', action='store_true')
    args = parser.parse_args()
    output = args.work_dir.resolve()
    if not output.is_relative_to(ROOT / '.work'):
        parser.error('--work-dir must be below the repository .work directory')
    matrix = list(cases())
    if not matrix or len({case.name for case in matrix}) != len(matrix):
        raise AssertionError('empty or duplicate source fixture inventory')
    unknown = set(args.case or ()) - {case.name for case in matrix}
    if unknown:
        parser.error('unknown case selector(s): ' + ', '.join(sorted(unknown)))
    matrix = [case for case in matrix if not args.case or case.name in args.case]
    if not matrix:
        parser.error('fixture selection is empty')
    output.mkdir(parents=True, exist_ok=True)
    for case in matrix:
        (output / (case.name + '.mlir')).write_text(case.source)
    if args.emit_only:
        print(f'Emitted {len(matrix)} native fixtures; no compiler validation executed.')
        return 0
    if args.nodalc is None:
        parser.error('--nodalc is required unless --emit-only')
    compiler = args.nodalc.resolve(strict=True)
    results = []
    for case in matrix:
        path = output / (case.name + '.mlir')
        passed, error = False, None
        try:
            if case.diagnostic is not None:
                validate_negative(case, run(compiler, path, output, case.name + '.gate'))
            else:
                parsed = run(compiler, path, output, case.name + '.parse', gate=False)
                if parsed.returncode or p.CODES.search(parsed.stderr):
                    raise AssertionError('positive parse failed: ' + parsed.stderr)
                gate = run(compiler, path, output, case.name + '.gate')
                if gate.returncode or p.CODES.search(gate.stderr):
                    raise AssertionError('default gate failed: ' + gate.stderr)
                validate_positive(case, gate.stdout)
                replay_path = output / (case.name + '.normalized.mlir')
                replay_path.write_text(gate.stdout)
                replay = run(compiler, replay_path, output, case.name + '.replay')
                repeat = run(compiler, path, output, case.name + '.repeat')
                if (replay.returncode or repeat.returncode or p.CODES.search(replay.stderr) or
                        p.CODES.search(repeat.stderr) or replay.stdout != gate.stdout or
                        repeat.stdout != gate.stdout):
                    raise AssertionError('default gate is not deterministic and idempotent')
            passed = True
        except (AssertionError, OSError, subprocess.TimeoutExpired) as exception:
            error = str(exception)
        results.append({'name': case.name, 'passed': passed, 'error': error,
                        'source_sha256': digest(case.source.encode())})
        print(f'{"PASS" if passed else "FAIL"} {case.name}' +
              (f': {error}' if error else ''), flush=True)
    passed = bool(results) and len(results) == len(matrix) and all(x['passed'] for x in results)
    (output / 'results.json').write_text(json.dumps({
        'schema': 'nodal.f043.generated-local-arrays.v1',
        'compiler_sha256': digest(compiler.read_bytes()), 'passed': passed,
        'selected_cases': [case.name for case in matrix], 'cases': results}, indent=2) + '\n')
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
