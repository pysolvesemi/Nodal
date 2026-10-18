#!/usr/bin/env python3
"""Focused generated probe placement and independent malformed-record rejection.

Inputs are native fixtures, not public Scala or generated-HDL acceptance evidence.
Each positive passes parse, the real default gate, replay and idempotence checks.
Mutations start from accepted normalized IR; failures pin a causal code and text.
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
import run_generated_topology_matrix as g


ROOT = Path(__file__).resolve().parents[4]
PIPELINE = '--pass-pipeline=builtin.module(nodal-gate-default)'
SSA = r'%[-A-Za-z0-9_.$]+(?:#[0-9]+)?'
OPERATION = re.compile(rf'^\s*(?:({SSA}) = )?"(nodal\.[a-z_]+)"\(([^)]*)\)')
CODES = re.compile(r'NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+')
PROVENANCE = 'NODAL-PROBE-PROVENANCE-001'
SCOPE = 'NODAL-PROBE-SCOPE-001'
OWNER = 'NODAL-ITERATION-043-004'
KIND = 'NODAL-PROBE-KIND-001'


@dataclass(frozen=True)
class Probe:
    scope: str
    form: str
    kind: str
    subjects: tuple[str, ...]
    provenance: tuple[str, ...]


@dataclass(frozen=True)
class Case:
    name: str
    source: str
    probes: tuple[Probe, ...] = ()
    diagnostic: str | None = None
    cause: str | None = None


def text_attr(line, name):
    match = re.search(rf'\b{re.escape(name)} = "([^"\n]*)"', line)
    return match.group(1) if match else None


def access(left='p', right='n', *, kind='potential', name='read', scope=None,
           branch=False, wrapped=True):
    function, dimension = ('V', 'voltage') if kind == 'potential' else ('I', 'current')
    path = (scope or 'Parent') + '.' + name
    operation = 'access' if branch else 'terminal_access'
    operands = f'%{left}' if branch else f'%{left}, %{right}'
    types = '!nodal.branch<"electrical">' if branch else f'{b.TERMINAL}, {b.TERMINAL}'
    body = (f'%{name} = "nodal.{operation}"({operands}) <{{function = "{function}", '
            f'kind = "{kind}", metadata = {{source_path = "{path}"}}}}> : '
            f'({types}) -> !nodal.quantity<"real", "{dimension}">\n')
    return analog(body, scope) if wrapped else body


def analog(body, scope=None):
    head = '"nodal.analog"() <{metadata = {}}> ({\n'
    if scope:
        head = g.owned(head, scope)
        body += g.owned('"nodal.analog_procedure"() <{owner = "Parent", metadata = {}}> '
                        '({\n^bb0:\n}) : () -> ()\n', scope)
    return head + body + '}) : () -> ()\n'


def branch(name, scope='Parent.gen'):
    return g.owned(
        f'%{name} = "nodal.branch"(%p, %n) <{{declaration_kind = "named", '
        f'name = "{name}", source_path = "{scope}.{name}", '
        f'metadata = {{semantic_path = "{scope}.{name}"}}}}> : '
        f'({b.TERMINAL}, {b.TERMINAL}) -> !nodal.branch<"electrical">\n', scope)


def expectation(scope, kind='potential', subjects=('Parent.p', 'Parent.n'),
                sources=None, form='two-terminal'):
    return Probe(scope, form, kind, subjects, tuple(sorted(sources or (scope + '.read',))))


def cases():
    scope, other, inner = 'Parent.gen', 'Parent.other', 'Parent.gen.inner'
    roots = g.scalar('Parent', 'p') + g.scalar('Parent', 'n')
    retained = Path(__file__).with_name('generated-probe-nested-singleton.mlir').read_text()
    yield Case('nested_singleton_family_distinct_lanes', retained, (
        expectation(inner, 'flow',
                    ('Parent.a.at(literal:0).in(Parent.gen.inner)',
                     'Parent.a.at(literal:1).in(Parent.gen.inner)'), ('Parent.access',)),))

    locals_ = g.owned(g.scalar(scope, 'p'), scope) + g.owned(g.scalar(scope, 'n'), scope)
    yield Case('fresh_generated_endpoints', b.design('', g.generate(
        locals_ + access(kind='flow', scope=scope))), (
            expectation(scope, 'flow', (scope + '.p', scope + '.n')),))

    arrays = b.array('Parent', 'a', '3') + b.array('Parent', 'z', '3')
    selected = (g.selected(base='a', name='p') + g.selected(base='z', name='n'))
    yield Case('direct_induction_endpoints', b.design('', arrays + g.generate(
        selected + access(kind='flow', scope=scope))), (
            expectation(scope, 'flow',
                        ('Parent.a.at(induction:Parent.gen.index).in(Parent.gen)',
                         'Parent.z.at(induction:Parent.gen.index).in(Parent.gen)')),))

    named = (branch('sense') + access('sense', name='outer_read', scope=scope, branch=True) +
             g.generate(access('sense', name='inner_read', scope=inner, branch=True),
                        inner, index='j', upper=2))
    yield Case('generated_named_branch_descendant', b.design('', roots + g.generate(named)), (
        expectation(scope, subjects=(scope + '.sense',), form='branch',
                    sources=(scope + '.sense', scope + '.outer_read', inner + '.inner_read')),))
    two_named = (branch('first') + branch('second') +
                 analog(access('first', name='voltage', scope=scope, branch=True, wrapped=False) +
                        access('second', kind='flow', name='current', scope=scope, branch=True,
                               wrapped=False), scope))
    yield Case('coincident_named_branches_remain_distinct', b.design('', roots + g.generate(two_named)), (
        expectation(scope, subjects=(scope + '.first',), form='branch',
                    sources=(scope + '.first', scope + '.voltage')),
        expectation(scope, 'flow', (scope + '.second',),
                    (scope + '.second', scope + '.current'), 'branch')))

    siblings = (g.generate(access(scope=scope), scope) +
                g.generate(access(name='other_read', scope=other), other, index='j', upper=2))
    yield Case('same_kind_sibling_module_endpoints', b.design('', roots + siblings), (
        expectation(scope, sources=(scope + '.read', other + '.other_read')),))
    mixed = (g.generate(access(scope=scope), scope) +
             g.generate(access(kind='flow', name='other_read', scope=other), other, index='j', upper=2))
    yield Case('sibling_same_physical_mixed_kind', b.design('', roots + mixed),
               diagnostic=KIND, cause='source-free branch cannot be both a potential and a flow probe')

    empty = g.generate(access(scope=scope), scope, upper=0)
    live = g.generate(access(kind='flow', name='other_read', scope=other), other, index='j')
    yield Case('empty_sibling_has_no_kind', b.design('', roots + empty + live), (
        expectation(other, 'flow', sources=(other + '.other_read',)),))
    inner_mixed = analog(access(scope=inner, wrapped=False) +
                         access(kind='flow', name='current', scope=inner, wrapped=False), inner)
    nested_empty = g.generate(g.generate(inner_mixed, inner, index='j'), scope, upper=0)
    yield Case('empty_ancestor_has_no_probe', b.design('', roots + nested_empty))
    yield Case('empty_scope_still_rejects_owner', b.design('', roots + empty.replace(
        'generated_owner = "Parent.gen"', 'generated_owner = "Parent.forged"')),
        diagnostic=OWNER, cause='generated analog ownership or induction does not match')

    symbolic = b.parameter('Parent', 'N', low=0, high=3, default=2)
    maybe_empty = g.generate(access(scope=scope), scope, upper='@N', count=3)
    yield Case('maybe_empty_source_keeps_generated_support',
               b.design('', symbolic + roots + maybe_empty), (expectation(scope),))
    same_bounds = g.generate(analog(access(scope=scope, wrapped=False) +
                                    access(kind='flow', name='current', scope=scope, wrapped=False),
                                    scope),
                            scope, lower='@N', upper='@N', count=0)
    yield Case('identical_symbolic_bounds_are_empty', b.design('', symbolic + roots + same_bounds))
    yield Case('reject_forged_generate_count', b.design('', roots + g.generate(
        access(scope=scope), scope, count=1)), diagnostic='NODAL-ITERATION-043-001',
        cause='maximum_trip_count must equal the independently proven generate envelope')
    yield Case('reject_zero_step_empty_scope', b.design('', roots + empty.replace(
        'step = 1 : i64', 'step = 0 : i64')), diagnostic='',
        cause="'nodal.generate' op step must not be zero")

    independent = symbolic + b.parameter('Parent', 'M', low=0, high=3, default=1)
    maybe_sibling = g.generate(access(name='other_read', scope=other), other,
                               upper='@M', count=3, index='j')
    union = (scope + '.read', other + '.other_read')
    yield Case('maybe_empty_siblings_keep_both_supports', b.design('', independent + roots +
        maybe_empty + maybe_sibling),
        (expectation(scope, sources=union), expectation(other, sources=union)))

    maybe_mixed = g.generate(access(kind='flow', name='other_read', scope=other), other,
                             upper='@M', count=3, index='j')
    yield Case('maybe_empty_sibling_mixed_kind_overlap_unknown', b.design('', independent + roots +
        maybe_empty + maybe_mixed), diagnostic=SCOPE,
        cause='mixed probe kinds have unproved conditional support overlap')

    implicit = ('%pair = "nodal.branch"(%p, %n) <{declaration_kind = "implicit", source_path = "Parent.pair", metadata = {identity = "Parent.pair", semantic_path = "Parent.pair"}}> : '
                f'({b.TERMINAL}, {b.TERMINAL}) -> !nodal.branch<"electrical">\n')
    def source_body():
        return (g.owned('"nodal.analog"() <{metadata = {}}> ({\n', scope) +
                '%drive = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64\n' +
                g.owned('"nodal.contribute"(%pair, %drive) <{kind = "potential", metadata = {}}> : '
                        '(!nodal.branch<"electrical">, f64) -> ()\n', scope) +
                '}) : () -> ()\n')
    prefix = roots + implicit + access()
    yield Case('guaranteed_sibling_contribution_sources_module_access',
               b.design('', prefix + g.generate(source_body(), scope)))
    yield Case('empty_contribution_does_not_source_module_access',
               b.design('', prefix + g.generate(source_body(), scope, upper=0)),
               (expectation('Parent'),))
    yield Case('maybe_empty_contribution_cannot_source_module_access',
               b.design('', symbolic + prefix + g.generate(source_body(), scope,
                                                          upper='@N', count=3)),
               diagnostic=SCOPE,
               cause='contribution support does not provably cover this physical access')
    legacy_empty = ('"nodal.generate"() <{induction = "legacy", lower = 0 : i64, '
                    'upper = 0 : i64, step = -1 : i64, metadata = {}}> ({\n^bb0:\n'
                    '}) : () -> ()\n')
    yield Case('legacy_negative_noarg_empty_domain', b.design('', roots + access() + legacy_empty),
               (expectation('Parent'),))

    legacy_read = access('pair', name='read', scope='Parent.legacy', branch=True, wrapped=False)
    legacy_negative = ('"nodal.generate"() <{induction = "legacy", lower = 3 : i64, '
                       'upper = 0 : i64, step = -1 : i64, metadata = {}}> ({\n^bb0:\n' +
                       legacy_read + '}) : () -> ()\n')
    yield Case('legacy_negative_noarg_source_free', b.design('', roots + implicit + legacy_negative),
               (expectation('Parent/legacy:legacy', subjects=('Parent.pair',), form='branch',
                            sources=('Parent.pair', 'Parent.legacy.read')),))

    fixed_roots = b.literal('zero', 0) + arrays
    def fixed(owner, index, kind):
        members = (g.selected(owner, base='a', name='p_' + index, indices=[('zero', 'literal:0')]) +
                   g.selected(owner, base='z', name='n_' + index, indices=[('zero', 'literal:0')]))
        return g.generate(members + access('p_' + index, 'n_' + index,
                                           kind=kind, name='read_' + index, scope=owner),
                          owner, index=index)
    fixed_same = fixed(scope, 'i', 'potential') + fixed(other, 'j', 'potential')
    yield Case('sibling_fixed_alias_same_kind', b.design('', fixed_roots + fixed_same), (
        expectation(scope, subjects=('Parent.a.at(literal:0).in(Parent.gen)',
                                     'Parent.z.at(literal:0).in(Parent.gen)'),
                    sources=(scope + '.read_i', other + '.read_j')),))
    local_other = (g.owned(g.scalar(other, 'p_other'), other) +
                   g.owned(g.scalar(other, 'n_other'), other) +
                   access('p_other', 'n_other', kind='flow', scope=other))
    yield Case('fresh_sibling_roots_remain_distinct', b.design('',
        g.generate(locals_ + access(scope=scope)) +
        g.generate(local_other, other, index='j')), (
            expectation(scope, subjects=(scope + '.p', scope + '.n')),
            expectation(other, 'flow', (other + '.p_other', other + '.n_other'))))
    empty_fixed = fixed(scope, 'i', 'potential').replace('upper = 3 : i64', 'upper = 0 : i64').replace(
        'maximum_trip_count = 3 : i64', 'maximum_trip_count = 0 : i64')
    yield Case('empty_fixed_sibling_has_no_physical_kind', b.design('', fixed_roots + empty_fixed +
        fixed(other, 'j', 'flow')), (
            expectation(other, 'flow', ('Parent.a.at(literal:0).in(Parent.other)',
                                       'Parent.z.at(literal:0).in(Parent.other)'),
                        (other + '.read_j',)),))
    shared_scope = 'Parent.shared_join'
    shared_join = (g.selected(shared_scope, base='a', name='joined',
                              indices=[('k', 'induction:' + shared_scope + '.index')]) +
                   g.owned(g.connect('joined', 'shared', path=shared_scope + '.join'), shared_scope))
    second_lane = fixed(other, 'j', 'potential').replace(
        '%a, %zero', '%a, %one').replace('Parent.a.at(literal:0)', 'Parent.a.at(literal:1)')
    yield Case('sibling_shared_join_identity_unknown', b.design('', fixed_roots + b.literal('one', 1) +
        g.scalar('Parent', 'shared') + g.generate(shared_join, shared_scope, index='k') +
        fixed(scope, 'i', 'potential') + second_lane), diagnostic='NODAL-SHAPE-043-009',
        cause='access grouping has unproved physical alias identity')
    yield Case('reject_escaped_sibling_source', b.design('', fixed_roots + fixed_same.replace(
        '"nodal.terminal_access"(%p_j, %n_j)', '"nodal.terminal_access"(%p_i, %n_j)')),
        diagnostic='', cause='use of undeclared SSA value name')
    yield Case('sibling_fixed_alias_mixed_kind', b.design('', fixed_roots +
        fixed(scope, 'i', 'potential') + fixed(other, 'j', 'flow')),
        diagnostic=KIND, cause='source-free branch cannot be both a potential and a flow probe')


def inspect(text):
    """Read canonical generic printing, retaining lexical SSA and immediate scope."""
    frames = []
    operations, probes, ranges = [], [], {}
    for number, line in enumerate(text.splitlines(keepends=True)):
        if line.lstrip().startswith('})'):
            if not frames:
                raise AssertionError('unmatched generic region close')
            frame = frames.pop()
            ranges[frame['scope']] = (frame['start'], number)
            continue
        match = OPERATION.match(line)
        if not match:
            continue
        result, operation, operands = match.groups()
        parent = frames[-1]['scope'] if frames else None
        values = {}
        for frame in frames:
            values.update(frame['values'])
        identity = text_attr(line, 'semantic_path') or text_attr(line, 'source_path')
        if result and frames:
            frames[-1]['values'][result] = identity or (operation + ':' + result)
        operations.append((operation, parent, number))
        if operation == 'nodal.probe':
            names = tuple(x.strip() for x in operands.split(',') if x.strip())
            source = re.search(r'\bprovenance = (\[[^\n]*?\])', line)
            if not source:
                raise AssertionError('probe missing provenance array')
            provenance = tuple(json.loads(source.group(1)))
            owner = text_attr(line, 'generated_owner')
            induction = text_attr(line, 'generated_induction')
            generated = (frames and frames[-1]['operation'] == 'nodal.generate' and
                         frames[-1]['captured'])
            if (owner, induction) != ((parent, parent + '.index') if generated else (None, None)):
                raise AssertionError('probe generated pair does not match actual immediate scope')
            kind = text_attr(line, 'kind')
            if text_attr(line, 'constraint_intent') != {'flow': 'zero-potential', 'potential': 'zero-flow'}.get(kind):
                raise AssertionError('probe kind/intent mismatch')
            if ('compiler_owned = true' not in line or
                    text_attr(line, 'generated_by') != 'increment31-potential-flow-access'):
                raise AssertionError('probe lost compiler provenance')
            probes.append((Probe(parent, text_attr(line, 'form'), kind,
                                 tuple(values.get(name, 'UNRESOLVED:' + name) for name in names),
                                 provenance), number))
        if line.rstrip().endswith('({'):
            scope = (text_attr(line, 'region_id') or text_attr(line, 'sym_name') or
                     (f"{parent}/legacy:{text_attr(line, 'induction')}"
                      if operation == 'nodal.generate' else f'{parent}/{operation}:{number}'))
            frames.append({'scope': scope, 'operation': operation, 'start': number, 'values': {},
                           'captured': text_attr(line, 'region_id') is not None})
    if frames:
        raise AssertionError('unclosed generic regions')
    return operations, probes, ranges


def validate_positive(case, normalized):
    operations, probes, _ = inspect(normalized)
    if Counter(probe for probe, _ in probes) != Counter(case.probes):
        raise AssertionError(f'probe contract mismatch: expected {case.probes!r}, actual {probes!r}')
    source_counts = Counter(re.findall(r'"(nodal\.(?:access|terminal_access|generate|branch))"', case.source))
    actual_counts = Counter(operation for operation, _, _ in operations if operation in source_counts)
    if not source_counts or actual_counts != source_counts:
        raise AssertionError('source operation inventory changed or positive fixture is empty')
    if 'nodal.pipeline.normalized = "v1"' not in normalized:
        raise AssertionError('default semantic gate did not complete')


def replace_probe(text, number, replacement):
    lines = text.splitlines(keepends=True)
    lines[number:number + 1] = [replacement] if replacement else []
    return ''.join(lines)


def move_probe(text, number, scope, *, remove_owner=False):
    _, _, ranges = inspect(text)
    line = text.splitlines(keepends=True)[number]
    if remove_owner:
        line = re.sub(r'generated_(?:owner|induction) = "[^"]+",?\s*', '', line)
    else:
        line = re.sub(r'generated_owner = "[^"]+"', f'generated_owner = "{scope}"', line)
        line = re.sub(r'generated_induction = "[^"]+"', f'generated_induction = "{scope}.index"', line)
    lines = text.splitlines(keepends=True)
    # Insert before the destination close, preserving all other source records.
    destination = ranges[scope][1]
    lines[number] = ''
    lines.insert(destination, line)
    return ''.join(lines)


def mutants(normalized):
    base = normalized['same_kind_sibling_module_endpoints']
    _, records, _ = inspect(base)
    number = next(number for probe, number in records if probe.scope == 'Parent.gen')
    line = base.splitlines(keepends=True)[number]
    yield Case('reject_duplicate_probe', replace_probe(base, number, line + line),
               diagnostic=PROVENANCE, cause='source-free support occurrence requires exactly one placed probe record')
    named = normalized['coincident_named_branches_remain_distinct']
    _, named_records, _ = inspect(named)
    yield Case('reject_missing_probe', replace_probe(named, named_records[0][1], ''),
               diagnostic=PROVENANCE, cause='source-free support occurrence requires exactly one placed probe record')
    yield Case('reject_wrong_provenance', replace_probe(base, number, re.sub(
        r'provenance = \[[^\]]*\]', 'provenance = ["Parent.forged"]', line)),
        diagnostic=PROVENANCE, cause='probe provenance does not match')
    yield Case('reject_wrong_owner', replace_probe(base, number, line.replace(
        'generated_owner = "Parent.gen"', 'generated_owner = "Parent.forged"')),
        diagnostic=OWNER, cause='structural operation requires its exact generated owner and induction')
    yield Case('reject_unpaired_owner', replace_probe(base, number, re.sub(
        r'generated_induction = "[^"]+",?\s*', '', line)),
        diagnostic=OWNER, cause='structural operation requires its exact generated owner and induction')
    yield Case('reject_wrong_induction', replace_probe(base, number, line.replace(
        'generated_induction = "Parent.gen.index"', 'generated_induction = "Parent.other.index"')),
        diagnostic=OWNER, cause='structural operation requires its exact generated owner and induction')
    yield Case('reject_forged_compiler_provenance', replace_probe(base, number, line.replace(
        'compiler_owned = true', 'compiler_owned = false')),
        diagnostic=PROVENANCE, cause='probe records are compiler-owned Increment 31 artifacts')
    yield Case('reject_module_hoist', move_probe(base, number, 'Parent', remove_owner=True),
               diagnostic=PROVENANCE,
               cause='source-free support occurrence requires exactly one placed probe record')
    yield Case('reject_sibling_move', move_probe(base, number, 'Parent.other'),
               diagnostic=PROVENANCE,
               cause='source-free support occurrence requires exactly one placed probe record')
    yield Case('reject_wrong_kind', replace_probe(base, number, line.replace(
        'kind = "potential"', 'kind = "flow"').replace('zero-flow', 'zero-potential')),
        diagnostic=KIND, cause='probe record kind does not match')
    nested = normalized['nested_singleton_family_distinct_lanes']
    _, records, _ = inspect(nested)
    number = records[0][1]
    yield Case('reject_nested_scope_escape', move_probe(nested, number, 'Parent', remove_owner=True),
               diagnostic='', cause='use of undeclared SSA value name')
    fresh = normalized['fresh_generated_endpoints']
    _, fresh_records, ranges = inspect(fresh)
    number = fresh_records[0][1]
    lines = fresh.splitlines(keepends=True)
    line, lines[number] = lines[number], ''
    insertion = ranges['Parent.gen'][0] + 1
    if lines[insertion].lstrip().startswith('^bb'):
        insertion += 1
    lines.insert(insertion, line)
    yield Case('reject_non_dominating_order', ''.join(lines), diagnostic=SCOPE,
               cause='probe subject does not dominate its actual structural placement')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(compiler, path, pipeline, output, label):
    command = [str(compiler), *([PIPELINE] if pipeline else []), str(path)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=30, check=False)
    (output / (label + '.stdout')).write_text(result.stdout)
    (output / (label + '.stderr')).write_text(result.stderr)
    return result


def validate_negative(case, result):
    expected = {case.diagnostic} if case.diagnostic else set()
    if result.returncode != 1 or set(CODES.findall(result.stderr)) != expected:
        raise AssertionError(f'expected exit 1 and {expected}, got exit {result.returncode}: {result.stderr}')
    if not case.cause or case.cause not in result.stderr:
        raise AssertionError(f'expected causal diagnostic {case.cause!r}: {result.stderr}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nodalc', type=Path)
    parser.add_argument('--output-root', type=Path, required=True)
    parser.add_argument('--emit-only', action='store_true')
    args = parser.parse_args()
    output = args.output_root.resolve()
    if not output.is_relative_to(ROOT / '.work'):
        parser.error('--output-root must be below the repository .work directory')
    output.mkdir(parents=True, exist_ok=True)
    matrix = list(cases())
    if not matrix or len({case.name for case in matrix}) != len(matrix):
        raise AssertionError('empty or duplicate source fixture inventory')
    for case in matrix:
        (output / (case.name + '.mlir')).write_text(case.source)
    if args.emit_only:
        print(f'Emitted {len(matrix)} native fixtures; no validation executed.')
        return 0
    if args.nodalc is None:
        parser.error('--nodalc is required unless --emit-only')
    compiler = args.nodalc.resolve(strict=True)
    results, normalized = [], {}
    for case in matrix:
        path = output / (case.name + '.mlir')
        valid, error = False, None
        try:
            if case.diagnostic is not None:
                validate_negative(case, run(compiler, path, True, output, case.name + '.gate'))
            else:
                parsed = run(compiler, path, False, output, case.name + '.parse')
                if parsed.returncode != 0 or CODES.search(parsed.stderr):
                    raise AssertionError('positive parse failed: ' + parsed.stderr)
                gate = run(compiler, path, True, output, case.name + '.gate')
                if gate.returncode != 0 or CODES.search(gate.stderr):
                    raise AssertionError('default gate failed: ' + gate.stderr)
                validate_positive(case, gate.stdout)
                replay_path = output / (case.name + '.normalized.mlir')
                replay_path.write_text(gate.stdout)
                replay = run(compiler, replay_path, True, output, case.name + '.replay')
                repeat = run(compiler, path, True, output, case.name + '.repeat')
                if replay.returncode or repeat.returncode or replay.stdout != gate.stdout or repeat.stdout != gate.stdout:
                    raise AssertionError('normalization is not deterministic and idempotent')
                normalized[case.name] = gate.stdout
            valid = True
        except (AssertionError, OSError, subprocess.TimeoutExpired) as exception:
            error = str(exception)
        results.append({'name': case.name, 'valid': valid, 'error': error,
                        'source_sha256': digest(case.source.encode())})
        print(f'{"PASS" if valid else "FAIL"} {case.name}' + (f': {error}' if error else ''), flush=True)
    prerequisites = {'same_kind_sibling_module_endpoints', 'nested_singleton_family_distinct_lanes',
                     'coincident_named_branches_remain_distinct', 'fresh_generated_endpoints'}
    if prerequisites <= normalized.keys():
        mutations = list(mutants(normalized))
        if not mutations:
            raise AssertionError('mutation fixture inventory is empty')
        for case in mutations:
            path = output / (case.name + '.mlir')
            path.write_text(case.source)
            valid, error = False, None
            try:
                validate_negative(case, run(compiler, path, True, output, case.name + '.gate'))
                valid = True
            except (AssertionError, OSError, subprocess.TimeoutExpired) as exception:
                error = str(exception)
            results.append({'name': case.name, 'valid': valid, 'error': error,
                            'source_sha256': digest(case.source.encode())})
            print(f'{"PASS" if valid else "FAIL"} {case.name}' + (f': {error}' if error else ''), flush=True)
    else:
        results.append({'name': 'normalized_record_mutations', 'valid': False,
                        'error': 'required positive control did not pass'})
    passed = bool(results) and all(result['valid'] for result in results)
    (output / 'results.json').write_text(json.dumps({
        'schema': 'nodal.f043.generated-probe-placement.v1',
        'compiler_sha256': digest(compiler.read_bytes()), 'passed': passed,
        'cases': results}, indent=2) + '\n')
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
