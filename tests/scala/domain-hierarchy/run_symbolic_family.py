#!/usr/bin/env python3
"""Focused symbolic-family successor checks; explicit frozen tools, no builds/discovery.

Run only after coordinator approval, with python3 -I -B under its resource lock.
Historical fixtures and runners are unchanged; this runner compiles one new source
witness and directly affected predecessor replays, not the prior broad suite.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys

RESERVE = 246808576
MARKER_SHA256 = 'd48dad0d7bb294fa5d7417118b5f0cd9b735646232fcc574fbe99a82af8e18ed'
HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[2]
SCALA = HERE / 'fixtures/selected-conservative-parameters'
NATIVE = SOURCE / 'tests/compiler/fixtures/increment58/parent-real-forwarding/fixtures'
SHAPE_FIXTURE = SOURCE / 'tests/compiler/fixtures/increment58/generated-conservative-adapter/symbolic-family-view-slice.mlir'
MAPPER = SOURCE / 'tests/compiler/fixtures/increment58/shape-anchor-mapper/fixtures'
MAPPER_CASES = (('shared-scalar.mlir', 'anchor'), ('lower2-step2.mlir', 'paired'),
                ('authored-capture.mlir', 'capture'))
NAMES = ('SelectedConservativeTwoChildParametersSource.scala', 'ParentArithmeticSource.scala',
         'ParentArithmeticReplay.scala', 'SymbolicFamilySource.scala', 'SymbolicFamilyReplay.scala')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def dual_parameter_fixture(text):
    """Retain LANES; clone canonical OTHER declaration, constraint and envelope."""
    lines = text.splitlines()
    clones = []
    envelope = None
    for index, line in enumerate(lines):
        if ('"nodal.parameter"()' in line and 'sym_name = "LANES"' in line or
            ('"nodal.parameter_constraint"' in line or '"nodal.parameter_envelope"' in line)
                and 'parameter = @LANES' in line):
            clones.append(line.replace('SelectedSymbolicFamily.LANES', 'SelectedSymbolicFamily.OTHER')
                          .replace('sym_name = "LANES"', 'sym_name = "OTHER"')
                          .replace('parameter = @LANES', 'parameter = @OTHER'))
            if '"nodal.parameter_envelope"' in line:
                envelope = index
    require(len(clones) == 3 and envelope is not None, 'canonical LANES domain inventory changed')
    references = [index for index, line in enumerate(lines)
                  if '"nodal.const_parameter_ref"' in line and 'parameter = @LANES' in line]
    require(len(references) == 1, 'exact family upper reference changed')
    index = references[0]
    lines[index] = lines[index].replace('SelectedSymbolicFamily.LANES', 'SelectedSymbolicFamily.OTHER').replace(
        'parameter = @LANES', 'parameter = @OTHER')
    lines[envelope + 1:envelope + 1] = clones
    return '\n'.join(line.rstrip() for line in lines) + '\n'


def main():
    require(sys.flags.isolated and sys.dont_write_bytecode, 'invoke with python3 -I -B')
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repository-root', 'compiler-classpath', 'runtime-classpath', 'api-jar',
                 'bridge-jar', 'plugin-jar', 'probe', 'output-root', 'java'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--api-support-jar', action='append', default=[])
    parser.add_argument('--bridge-support-jar', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    require(args.timeout > 0, 'positive timeout required')
    repo = Path(args.repository_root).resolve(strict=True)
    output = Path(args.output_root).resolve()
    stage = repo / '.work/f58-symbolic-conservative-family'
    require((SOURCE / 'build.mill').is_file() and digest(SOURCE / 'build.mill') == MARKER_SHA256,
            'canonical HEAD69 fixture-root build.mill marker missing or changed')
    require(output.is_relative_to(stage / 'test-output'), 'output must be below successor test-output')
    require(not output.exists(), 'refusing existing output')
    require(shutil.disk_usage(repo).free >= RESERVE, 'disk reserve required')

    def files(paths):
        values = [Path(path).resolve(strict=True) for path in paths]
        require(all(path.is_file() for path in values), 'explicit file inputs required')
        return values

    compiler = files(args.compiler_classpath.split(os.pathsep))
    runtime = files(args.runtime_classpath.split(os.pathsep))
    api = files([args.api_jar] + args.api_support_jar)
    bridge = files([args.bridge_jar] + args.bridge_support_jar)
    java, plugin, probe = files([args.java, args.plugin_jar, args.probe])
    fixtures = files([SCALA / name for name in NAMES])
    proof_fixtures = files([SHAPE_FIXTURE] + [MAPPER / name for name, _ in MAPPER_CASES])
    inputs = list(dict.fromkeys(fixtures + proof_fixtures + list(NATIVE.glob('*.mlir')) + compiler + runtime +
                               api + bridge + [java, plugin, probe, SOURCE / 'build.mill', Path(__file__).resolve()]))
    original = {str(path): digest(path) for path in inputs}
    for name in ('classes', 'tmp', 'home', 'cache'):
        (output / name).mkdir(parents=True)
    (output / 'inputs.json').write_text(json.dumps(original, indent=2) + '\n')
    env = {key: value for key, value in os.environ.items() if key not in (
        'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH',
        'PYTHONPATH', 'PYTHONHOME')}
    env.update(NODAL_WORKSPACE=str(SOURCE), HOME=str(output / 'home'),
               TMPDIR=str(output / 'tmp'), XDG_CACHE_HOME=str(output / 'cache'))
    rows = []

    def run(label, command, good=True, diagnostic=None):
        require(shutil.disk_usage(repo).free >= RESERVE, 'disk reserve exhausted')
        command = list(map(str, command))
        try:
            result = subprocess.run(command, cwd=SOURCE, env=env, capture_output=True,
                                    timeout=args.timeout, check=False)
        except subprocess.TimeoutExpired as expired:
            (output / (label + '.stdout')).write_bytes(expired.stdout or b'')
            (output / (label + '.stderr')).write_bytes(expired.stderr or b'')
            rows.append({'name': label, 'command': command, 'exit': None, 'timed_out': True})
            (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
            raise
        (output / (label + '.stdout')).write_bytes(result.stdout)
        (output / (label + '.stderr')).write_bytes(result.stderr)
        rows.append({'name': label, 'command': command, 'exit': result.returncode})
        (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
        stderr = result.stderr.decode('utf-8', errors='replace')
        require(result.returncode >= 0, label + ': signal is not a valid refusal')
        require((result.returncode == 0) == good,
                label + ': unexpected exit ' + str(result.returncode) + ': ' + stderr)
        require(diagnostic is None or diagnostic in stderr,
                label + ': missing causal diagnostic ' + str(diagnostic))
        return result.stdout.decode('utf-8', errors='replace')

    java_base = [java, '-XX:-UsePerfData', '-Djava.io.tmpdir=' + str(output / 'tmp'),
                 '-Duser.home=' + str(output / 'home')]
    cp = os.pathsep.join(map(str, bridge + api + runtime))
    run('compile', java_base + ['-cp', os.pathsep.join(map(str, compiler)), 'dotty.tools.dotc.Main',
        '-classpath', cp, '-d', output / 'classes', '-color:never', '-Wunused:all', '-Werror',
        '-Xplugin:' + str(plugin)] + fixtures)
    for label, main_class, argument in (
            ('symbolic', 'ReplaySymbolicFamily', output),
            ('arithmetic', 'ReplayParentArithmetic', output / 'parent-arithmetic.mlir')):
        run(label, java_base + ['-cp', str(output / 'classes') + os.pathsep + cp,
            'nodal.internal.testkit.' + main_class, argument])

    native_count = 0
    def native(name, mode):
        nonlocal native_count
        native_count += 1
        return run(name + '-' + mode, [probe, output / (name + '.mlir'), mode])

    targets = {}
    for name in ('symbolic-3', 'symbolic-4', 'symbolic-unused', 'literal-nonunit',
                 'symbolic-node-array', 'parent-arithmetic'):
        targets[name] = native(name, 'emit')
    for name in ('symbolic-3', 'symbolic-4', 'symbolic-unused'):
        target = targets[name]
        require('i < LANES' in target, name + ': symbolic loop upper bound was specialized')
        require('[0:LANES-1]' in target or '[0:(LANES)-1]' in target or '[0:(LANES - 1)]' in target,
                name + ': symbolic array extent was specialized')
        require('parameter real gain = 2;' in target, name + ': child default changed')
        require('.gain(3)' in target and '.gain(7)' in target, name + ': distinct child overrides lost')
        adapted = native(name, 'adapt')
        require(adapted.count('"nodal.node_array"') == 4, name + ': leaf roots duplicated or merged')
        require('!nodal.shaped<"LANES", !nodal.terminal<"electrical">>' in adapted,
                name + ': adapted arrays lost exact parameter identity')
        require('normalized_index' not in adapted, name + ': invented symbolic induction identity')
        bindings = [json.loads(value) for value in re.findall(r'// nodal.array_binding (.+)', target)]
        aliases = [value for value in bindings if value.get('alias_helper') == 'nodal_alias_electrical']
        generated = [value for value in aliases if value.get('source_scope')]
        selected = [value for value in aliases if not value.get('source_scope')]
        require(len(generated) == 4 and len({value['left']['root'] for value in generated}) == 4,
                name + ': distinct authored child/leaf roots lost')
        require(len(selected) == (0 if name == 'symbolic-unused' else 2),
                name + ': selected alias inventory changed')
        require({value['left']['root'] for value in selected} <=
                {value['left']['root'] for value in generated},
                name + ': selected roots differ from body roots')
        require(all(value['left']['stages'][0]['expression'] == '1' for value in selected),
                name + ': selected ordinal changed')
        require(all(value['left']['stages'][0]['expression'] == 'i' for value in generated),
                name + ': original symbolic induction identity changed')
    repeated = run('symbolic-3-emit-repeat', [probe, output / 'symbolic-3.mlir', 'emit'])
    require(repeated == targets['symbolic-3'], 'symbolic emission is nondeterministic')
    native_count += 1
    native('symbolic-3', 'identity')
    native('symbolic-3', 'child-pairs')
    for mutation in ('valid', 'shape-effect', 'upper-identity', 'upper-order', 'count',
                     'maximum', 'body-incidence', 'capability', 'absent-endpoint', 'local-family', 'budget-zero'):
        text = native('symbolic-3', 'symbolic-family-query-' + mutation)
        if mutation == 'budget-zero':
            require('budget_exhausted=1 unchanged=1' in text, text)
        elif mutation in ('valid', 'upper-identity'):
            require('parsed_valid=1 cc=1 exact_extent=1 unchanged=1' in text, text)
        else:
            expected = int(mutation in ('body-incidence', 'capability'))
            require(f'parsed_valid=1 cc={expected} exact_extent={expected} unchanged=1' in text and
                    'failure_is_transactional=1' in text, text)
    require('((i - 2) / 2)' in targets['literal-nonunit'], 'nonunit ordinal lowering changed')
    require('.gain((((parentFirst + parentSecond) * 2.0) - (-parentFirst)))' in
            targets['parent-arithmetic'], 'parent real arithmetic spelling changed')
    require('LANES' in targets['symbolic-node-array'], 'symbolic NodeArray predecessor changed')
    for mutation in ('valid', 'depth', 'render-limit'):
        text = native('parent-arithmetic', 'parent-real-query-' + mutation)
        expected = {'valid': (1, 1), 'depth': (0, 0), 'render-limit': (0, 1)}[mutation]
        require(f'parsed_valid=1 pm={expected[0]} cc={expected[1]} unchanged=1' in text, text)
    # Additive future-runner cases; historical execution used the recorded focused composition.
    dual_input = output / 'dual-parameter-other.mlir'
    dual_input.write_text(dual_parameter_fixture((output / 'symbolic-3.mlir').read_text()))
    target = run('dual-parameter-other-emit', [probe, dual_input, 'emit'])
    adapted = run('dual-parameter-other-adapt', [probe, dual_input, 'adapt'])
    native_count += 2
    require('i < OTHER' in target and 'i < LANES' not in target, 'upper identity lost in loop')
    require('[0:(OTHER - 1)]' in target and '[0:(LANES - 1)]' not in target,
            'physical extent must be OTHER, not LANES/default/maximum')
    require('parameter integer LANES = 3' in target and 'parameter integer OTHER = 3' in target,
            'equal-default distinct parameters must coexist')
    roots = [line for line in adapted.splitlines() if '"nodal.node_array"' in line]
    require(len(roots) == 4 and all('!nodal.shaped<"OTHER", !nodal.terminal<"electrical">>'
                                  in line for line in roots), 'four exact OTHER leaf roots required')
    require('normalized_index' not in adapted, 'invented symbolic induction')
    bindings = [json.loads(value) for value in re.findall(r'// nodal.array_binding (.+)', target)]
    aliases = [value for value in bindings if value.get('alias_helper') == 'nodal_alias_electrical']
    generated = [value for value in aliases if value.get('source_scope')]
    selected = [value for value in aliases if not value.get('source_scope')]
    require(len(generated) == 4 and len({value['left']['root'] for value in generated}) == 4 and
            len(selected) == 2 and {value['left']['root'] for value in selected} <=
            {value['left']['root'] for value in generated}, 'body/selected root identity changed')
    require(all(value['left']['root_shape'] == 'OTHER' for value in aliases),
            'root extent used equal-default LANES or fixed count')
    require(all(value['left']['stages'][0]['expression'] == '1' for value in selected) and
            all(value['left']['stages'][0]['expression'] == 'i' for value in generated),
            'selected ordinal or original induction changed')
    # Same reviewed public-query controls as the focused production replay.
    for fixture, mode in [(SHAPE_FIXTURE, 'repair-shape-controls')] + [
            (MAPPER / name, 'repair-mapper-' + mode) for name, mode in MAPPER_CASES]:
        text = run(mode, [probe, fixture, mode])
        native_count += 1
        summaries = re.findall(r'^SUMMARY checks=(\d+) failures=(\d+)$', text, re.M)
        require(len(summaries) == 1 and int(summaries[0][0]) > 0 and summaries[0][1] == '0',
                mode + ': proof controls failed')
    require(native_count == 32, 'focused native inventory changed')
    require(all(digest(Path(path)) == value for path, value in original.items()), 'frozen input changed')
    summary = {'status': 'pass', 'scala_mains': 2, 'native_invocations': native_count,
               'ci': 'not-run', 'historical_binary_comparisons': 0}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
