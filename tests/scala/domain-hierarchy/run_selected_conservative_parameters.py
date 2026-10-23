#!/usr/bin/env python3
"""Focused selected-conservative literal-parameter replay; no tool discovery/builds.

Invoke with python3 -I -B. All artifacts are explicit inputs; only fresh fixture
classes are loaded. See SELECTED_CONSERVATIVE_PARAMETERS.md for composition.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

FIXTURES = Path('tests/scala/domain-hierarchy/fixtures/selected-conservative-parameters')
NAMES = ('Controls.scala', 'ControlsReplay.scala', 'DuplicateAttributes.scala',
         'DuplicateSnapshot.scala', 'Forged.scala', 'Replay.scala',
         'SelectedConservativeLiteralParametersSource.scala',
         'SelectedConservativeTwoChildParametersSource.scala', 'TwoChildReplay.scala')
RESERVE = 200 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def replace(text, old, new, count=1):
    require(text.count(old) == count, f'anchor count: expected {count}: {old!r}')
    return text.replace(old, new)


def one_line(text, *parts):
    matches = [line for line in text.splitlines(True) if all(p in line for p in parts)]
    require(len(matches) == 1, f'expected one line: {parts!r}, got {len(matches)}')
    return matches[0]


def field(line, name):
    values = re.findall(r'\b' + re.escape(name) + r' = "([^"]+)"', line)
    require(len(values) == 1, f'expected one {name} field')
    return values[0]


def mutations(s):
    """Textual boundary corruptions, not a second parameter semantic engine."""
    bindings = 'parameter_bindings = {gain = 3.0 : f64, offset = 7.0 : f64}'
    yield 'unknown-binding', replace(s, bindings, bindings.replace('gain', 'foreign')), False
    yield 'binding-type', replace(s, bindings, bindings.replace('3.0 : f64', '3 : i64')), False
    yield 'binding-string', replace(s, bindings, bindings.replace('3.0 : f64', '"Foreign.gain"')), False
    yield 'nonfinite-default', replace(s, 'default_value = 2.0 : f64', 'default_value = 0x7FF8000000000000 : f64'), False
    yield 'fixed-override', replace(s, 'variability = "symbolic"', 'variability = "fixed"', 2), False
    boundary = one_line(s, '"nodal.interface_boundary"(', 'member = "first"')
    instance = one_line(s, '"nodal.instance"()')
    symbol = field(instance, 'sym_name')
    occurrence = re.findall(r'\binstance = (@(?:"[^"]+"|[A-Za-z_][\w.]*))', boundary)
    require(len(occurrence) == 1 and occurrence[0].lstrip('@').strip('"') == symbol,
            'selected boundary must refer to authored instance')
    yield 'foreign-selected-instance', replace(s, boundary, replace(boundary, occurrence[0], '@"foreign_instance"')), False
    parameter = one_line(s, '"nodal.parameter"', 'sym_name = "gain"')
    const = '    %default_two = "nodal.const_literal"() <{metadata = {}, spelling = "2.0", value = 2.0 : f64}> : () -> f64\n'
    attach = '    "nodal.parameter_value"(%default_two) <{metadata = {}, parameter = @gain}> : (f64) -> ()\n'
    literal = replace(s, parameter, parameter + const + attach)
    yield 'literal-default-attachment', literal, True
    expr = '    %default_one = "nodal.const_literal"() <{metadata = {}, spelling = "1.0", value = 1.0 : f64}> : () -> f64\n    %default_sum = "nodal.const_expr"(%default_one, %default_one) <{metadata = {}, operator_name = "add"}> : (f64, f64) -> f64\n'
    yield 'expression-default-attachment', replace(replace(literal, const, expr), '"nodal.parameter_value"(%default_two)', '"nodal.parameter_value"(%default_sum)'), False
    malformed = replace(attach, '(%default_two)', '()')
    malformed = replace(malformed, ': (f64) -> ()', ': () -> ()')
    yield 'malformed-default-attachment', replace(literal, attach, malformed), False
    owner, induction = field(instance, 'generated_owner'), field(instance, 'generated_induction')
    override = (f'      %explicit_actual = "nodal.const_literal"() <{{metadata = {{}}, spelling = "3.0", value = 3.0 : f64}}> : () -> f64\n'
                f'      "nodal.parameter_override"(%explicit_actual) <{{instance = @"{symbol}", parameter = @gain, generated_owner = "{owner}", generated_induction = "{induction}", metadata = {{}}}}> : (f64) -> ()\n')
    changed = replace(instance, bindings, 'parameter_bindings = {offset = 7.0 : f64}')
    yield 'explicit-parameter-override', replace(s, instance, changed + override), False
    extras = ''.join(replace(replace(parameter, 'sym_name = "gain"', f'sym_name = "extra{i}"'), '.gain"', f'.extra{i}"') for i in range(16))
    values = ', '.join(f'extra{i} = {100+i}.0 : f64' for i in range(16))
    many = replace(replace(s, parameter, parameter + extras), bindings,
                   'parameter_bindings = {' + values + ', gain = 3.0 : f64, offset = 7.0 : f64}')
    yield 'many-bindings', many, True
    yield 'late-foreign-binding', replace(many, 'extra15 = 115.0 : f64', 'foreign15 = 115.0 : f64'), False


def main():
    require(sys.flags.isolated and sys.dont_write_bytecode, 'invoke with python3 -I -B')
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('repository-root', 'source-root', 'compiler-classpath', 'runtime-classpath',
                'api-jar', 'bridge-jar', 'plugin-jar', 'probe', 'output-root', 'java'):
        parser.add_argument('--' + arg, required=True)
    parser.add_argument('--api-support-jar', action='append', default=[])
    parser.add_argument('--bridge-support-jar', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    require(args.timeout > 0, 'timeout must be positive')
    repo, source, output = map(lambda p: Path(p).resolve(),
                               (args.repository_root, args.source_root, args.output_root))
    require((repo / '.git').exists(), 'repository root must contain .git')
    require(output.is_relative_to(repo / '.work') and output != repo / '.work',
            'output must be strictly inside repository-root .work')
    require(not output.exists(), 'refusing existing output directory')
    ignored = subprocess.run(['git', '-C', str(repo), 'check-ignore', '-q', str(output)],
                             timeout=args.timeout, check=False)
    require(ignored.returncode == 0, 'output must be git-ignored')
    require(shutil.disk_usage(repo).free >= RESERVE, 'need at least 200 MiB free')
    for marker in ('build.mill', 'core/scala', 'docs'):
        require((source / marker).exists(), 'canonical source root missing ' + marker)
    fixtures = [source / FIXTURES / name for name in NAMES]
    def files(paths):
        result = [Path(p).resolve(strict=True) for p in paths]
        require(all(p.is_file() for p in result), 'inputs must be files, not class directories')
        return result
    compiler = files(args.compiler_classpath.split(os.pathsep))
    runtime = files(args.runtime_classpath.split(os.pathsep))
    api = files([args.api_jar] + args.api_support_jar)
    bridge = files([args.bridge_jar] + args.bridge_support_jar)
    java, plugin, probe = files([args.java, args.plugin_jar, args.probe])
    files(fixtures)
    bundled = Path(__file__).resolve().parent / 'fixtures/selected-conservative-parameters'
    for fixture in fixtures:
        require(fixture.read_bytes() == (bundled / fixture.name).read_bytes(),
                'canonical fixture differs from maintained source: ' + fixture.name)
    output.mkdir(parents=True)
    for name in ('classes', 'tmp', 'home', 'cache'):
        (output / name).mkdir()
    env = {k: v for k, v in os.environ.items()
           if k not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH', 'PYTHONPATH', 'PYTHONHOME')}
    env.update(NODAL_WORKSPACE=str(source), HOME=str(output / 'home'),
               TMPDIR=str(output / 'tmp'), XDG_CACHE_HOME=str(output / 'cache'))
    rows = []
    inputs = list(dict.fromkeys(fixtures + compiler + runtime + api + bridge + [java, plugin, probe, Path(__file__).resolve()]))
    def digest(path):
        with path.open('rb') as handle:
            return hashlib.file_digest(handle, 'sha256').hexdigest()
    (output / 'inputs.json').write_text(json.dumps({str(p): digest(p) for p in inputs}, indent=2) + '\n')
    def run(name, command, good=True, fragment=None):
        require(shutil.disk_usage(repo).free >= RESERVE, 'disk reserve exhausted')
        completed = subprocess.run(list(map(str, command)), cwd=source, env=env,
                                   capture_output=True, text=True, timeout=args.timeout)
        (output / (name + '.stdout')).write_text(completed.stdout)
        (output / (name + '.stderr')).write_text(completed.stderr)
        rows.append({'name': name, 'command': list(map(str, command)), 'exit': completed.returncode})
        (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
        require((completed.returncode == 0) == good, f'{name}: unexpected exit {completed.returncode}: {completed.stderr}')
        require(completed.returncode >= 0, f'{name}: signal is not a valid refusal')
        require(fragment is None or fragment in completed.stderr, f'{name}: missing diagnostic {fragment}')
        return completed.stdout
    java_base = [java, '-XX:-UsePerfData', '-Djava.io.tmpdir=' + str(output / 'tmp'), '-Duser.home=' + str(output / 'home')]
    cp = os.pathsep.join(map(str, bridge + api + runtime))
    run('compile', java_base + ['-cp', os.pathsep.join(map(str, compiler)), 'dotty.tools.dotc.Main',
        '-classpath', cp, '-d', output / 'classes', '-color:never', '-Wunused:all', '-Werror',
        '-Xplugin:' + str(plugin)] + fixtures)
    mains = [('literal', 'ReplayLiteralParameters', ['literal.mlir']),
             ('controls', 'ReplayLiteralControls', ['default.mlir']),
             ('forged', 'ReplayForgedParameters', []),
             ('duplicate', 'ReplayDuplicateSnapshot', ['duplicate-snapshot.mlir']),
             ('duplicate-attributes', 'ReplayDuplicateAttributes', []),
             ('two-child', 'ReplayTwoLiteralChildren', ['two-child.mlir'])]
    for name, main_class, outputs in mains:
        run(name, java_base + ['-cp', str(output / 'classes') + os.pathsep + cp,
            'nodal.internal.testkit.' + main_class] + [output / p for p in outputs])
    native_count = 0
    def native(name, mode, good=True, fragment=None):
        nonlocal native_count
        native_count += 1
        return run(name + '-' + mode, [probe, output / (name + '.mlir'), mode], good, fragment)
    for name, actual in [('literal', '#(.gain(3), .offset(7))'),
                         ('alternate', '#(.gain(11), .offset(13))'), ('default', None)]:
        native(name, 'verify')
        adapted = native(name, 'adapt')
        emitted = native(name, 'emit')
        for text in ('parameter real gain = 2;', 'parameter real offset = 1;',
                     'V(observed, reference) <+ ((gain + offset) * 1);', 'i = 2; i < 8; i = i + 2'):
            require(text in emitted, name + ': missing ' + text)
        require(actual in emitted if actual else '#(.gain' not in emitted, name + ': binding mismatch')
        require(adapted.count('"nodal.instance"()') == 1 and adapted.count('"nodal.instance_terminal"()') == 2,
                name + ': adapter structure mismatch')
        records = [base64.b64decode(x, validate=True).decode() for x in
                   re.findall(r'// nodal.interface_correspondence mlir-v1 (\S+)', emitted)]
        require(len(records) == 8, name + ': correspondence count')
        require(sum('flow_orientation = "into_component"' in x for x in records) == 2, name + ': flow count')
        require(all('discipline = "electrical"' in x and 'selected_access = "connect"' in x for x in records),
                name + ': correspondence mismatch')
    for name, text, good in mutations((output / 'literal.mlir').read_text()):
        (output / (name + '.mlir')).write_text(text)
        diagnostic = None
        if not good:
            diagnostic = {
                'fixed-override': 'NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden',
                'foreign-selected-instance': 'selected child Interface requires its exact exported instance',
                'malformed-default-attachment': "'nodal.parameter_value' op requires a single operand",
            }.get(name, 'NODAL-INTERFACE-054-001: child Interface projection requires digital data or scalar conservative leaves')
        emitted = native(name, 'emit', good, diagnostic)
        if name == 'many-bindings':
            require(all(f'.extra{i}({100+i})' in emitted for i in range(16)), 'lost extra bindings')
    native('duplicate-snapshot', 'emit', False, "duplicate key 'gain'")
    native('two-child', 'verify')
    native('two-child', 'emit', False, 'unproved physical alias identity')
    require('failure_is_transactional=1' in native('two-child', 'transaction'), 'nontransactional refusal')
    (output / 'summary.json').write_text(json.dumps({'status': 'pass', 'scala_mains': len(mains),
        'native_invocations': native_count, 'historical_old_native_comparisons': 0}, indent=2) + '\n')
    print(f'PASS: {len(mains)} Scala mains; {native_count} native invocations. Results: {output}')


if __name__ == '__main__':
    main()
