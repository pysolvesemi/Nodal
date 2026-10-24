#!/usr/bin/env python3
"""Focused arithmetic-parent successor checks; explicit frozen tools, no builds/discovery.

Run only after coordinator approval, with python3 -I -B under its resource lock.
Historical fixtures and runners are unchanged; this runner compiles one new source
witness and directly affected parent-forwarding replays, not the prior broad suite.
"""
import argparse
import hashlib
import json
import os
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
NAMES = ('SelectedConservativeLiteralParametersSource.scala',
         'SelectedConservativeTwoChildParametersSource.scala', 'Controls.scala',
         'ParentForwardingSource.scala', 'ParentForwardingReplay.scala',
         'ParentForwardingForged.scala', 'ParentArithmeticSource.scala',
         'ParentArithmeticReplay.scala')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


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
    stage = repo / '.work/f58-parent-real-expressions'
    require((SOURCE / 'build.mill').is_file() and digest(SOURCE / 'build.mill') == MARKER_SHA256,
            'canonical HEAD68 fixture-root build.mill marker missing or changed')
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
    inputs = list(dict.fromkeys(fixtures + list(NATIVE.glob('*.mlir')) + compiler + runtime +
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
            ('parent', 'ReplayParentForwarding', 'parent-reference.mlir'),
            ('forged-parent', 'ReplayForgedParentForwarding', None),
            ('arithmetic', 'ReplayParentArithmetic', 'parent-arithmetic.mlir')):
        run(label, java_base + ['-cp', str(output / 'classes') + os.pathsep + cp,
            'nodal.internal.testkit.' + main_class] + ([output / argument] if argument else []))

    native_count = 0
    def native(name, mode, good=True, diagnostic=None, maintained=False):
        nonlocal native_count
        native_count += 1
        path = (NATIVE if maintained else output) / (name + '.mlir')
        return run(name + '-' + mode, [probe, path, mode], good, diagnostic)

    for name, fragments in (
            ('parent-reference', ('.gain(parentGain)', '.offset(7)')),
            ('equal-parent-references', ('.gain(parentFirst)', '.gain(parentSecond)')),
            ('parent-expression-actual', ('.gain((parentGain + 1.0))',)),
            ('parent-arithmetic', ('.gain((((parentFirst + parentSecond) * 2.0) - (-parentFirst)))',
                                   '.gain((((parentFirst + parentSecond) * 2.0) - (-parentSecond)))'))):
        target = native(name, 'emit')
        for fragment in fragments:
            require(fragment in target, name + ': missing named arithmetic actual ' + fragment)
        require('parameter real gain = 2;' in target, name + ': child default changed')
    native_count += 1
    target = run('parent-arithmetic-emit-repeat', [probe, output / 'parent-arithmetic.mlir', 'emit'])
    require(target == (output / 'parent-arithmetic-emit.stdout').read_text(),
            'nondeterministic arithmetic Verilog-A')
    for mutation in ('valid', 'foreign', 'runtime', 'nested', 'type', 'unit', 'operator',
                     'edge-order', 'cycle', 'depth', 'render-limit', 'fixed',
                     'child-expression-default', 'nonfinite'):
        pm = mutation in ('valid', 'fixed', 'child-expression-default', 'nonfinite')
        cc = mutation in ('valid', 'nonfinite', 'render-limit')
        text = native('parent-arithmetic', 'parent-real-query-' + mutation)
        require(f'parsed_valid=1 pm={int(pm)} cc={int(cc)} unchanged=1' in text,
                mutation + ': independent query result mismatch: ' + text)
        if mutation in ('fixed', 'nonfinite'):
            require('parameter_contract=0' in text, mutation + ': parameter contract bypass')
            diagnostic = ('fixed parameter cannot be overridden' if mutation == 'fixed' else
                          'explicit override violates parameter contract')
            stderr = (output / ('parent-arithmetic-parent-real-query-' + mutation + '.stderr')).read_text()
            require('NODAL-PARAMETER-OVERRIDE-001: ' + diagnostic in stderr,
                    mutation + ': missing exact downstream contract diagnostic')

    for mutation, accepted in (('sharing', True), ('depth-510', True), ('depth-511', False),
                               ('memo-depth', False), ('attribute-boundary', True),
                               ('attachment-boundary', False), ('cycle', False),
                               ('parameter-cycle', False)):
        text = native('general-parent-real', 'constant-evaluation-' + mutation, maintained=True)
        require(f'parsed_valid=1 parameter_contract={int(accepted)} unchanged=1' in text,
                mutation + ': generic evaluator result mismatch: ' + text)
        if not accepted:
            stderr = (output / ('general-parent-real-constant-evaluation-' + mutation + '.stderr')).read_text()
            require('NODAL-CONSTANT-CYCLE-001: parameter default is cyclic or cannot be folded' in stderr,
                    mutation + ': wrong default-evaluation diagnostic')

    for name in ('general-parent-real', 'integer-finite-signed', 'integer-checked-empty'):
        require(not native(name, 'parameter-contract', maintained=True), name + ': unexpected output')
    native('integer-all-lane-not-default', 'parameter-contract', False,
           'NODAL-DIGITAL-055-014: static arithmetic is not valid over every legal value in its actual scope', True)
    native('arithmetic-excluded-default', 'parameter-contract', False,
           'NODAL-PARAMETER-OVERRIDE-001: explicit override violates parameter contract', True)
    scalar = native('scalar-direct-forwarding', 'materialized-scalar', maintained=True)
    require('localparam real nodal_h1_gain = gain' in scalar and 'module Branch' not in scalar,
            'scalar direct-parent materialized predecessor changed')
    scalar = native('scalar-direct-forwarding', 'emit', maintained=True)
    require('Branch #(.gain(gain))' in scalar, 'scalar named predecessor changed')
    scalar = native('scalar-compound-forwarding', 'emit', maintained=True)
    require('Branch #(.gain((gain + 1.0)))' in scalar, 'scalar named compound actual changed')
    native('scalar-compound-forwarding', 'materialized-scalar', False,
           'NODAL-BACKEND-HIERARCHY-MATERIALIZE-001: symbolic child actuals require direct parent parameter references', True)
    require(all(digest(Path(path)) == value for path, value in original.items()), 'frozen input changed')
    summary = {'status': 'pass', 'scala_mains': 3, 'native_invocations': native_count,
               'independent_query_controls': 14, 'generic_evaluator_controls': 8,
               'ci': 'not-run', 'historical_binary_comparisons': 0}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
