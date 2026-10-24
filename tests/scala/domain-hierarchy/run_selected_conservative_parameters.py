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
         'SelectedConservativeTwoChildParametersSource.scala', 'TwoChildReplay.scala',
         'ParentForwardingSource.scala', 'ParentForwardingReplay.scala',
         'ParentForwardingForged.scala')
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



def parent_mutations(s):
    """Single-cause corruptions of a fresh symbolic-parent witness."""
    reference = one_line(s, '"nodal.const_parameter_ref"', 'parameter = @parentGain')
    override = one_line(s, '"nodal.parameter_override"')
    instance = one_line(s, '"nodal.instance"()')
    parent = one_line(s, '"nodal.parameter"()', 'sym_name = "parentGain"')
    formal = one_line(s, '"nodal.parameter"()', 'sym_name = "gain"')
    value = re.match(r'\s*(%[\w]+) =', reference).group(1)
    interface = 'NODAL-INTERFACE-054-001: child Interface projection requires digital data or scalar conservative leaves'
    def actual(new_value):
        return replace(override, '(' + value + ')', '(' + new_value + ')')
    yield 'parent-generated-local-ref', replace(replace(s, reference, ''), override,
          '  ' + reference + override), interface
    expression = (f'    %parent_zero = "nodal.const_literal"() <{{metadata = {{}}, spelling = "0.0", value = 0.0 : f64}}> : () -> f64\n'
                  f'    %parent_sum = "nodal.const_expr"({value}, %parent_zero) <{{metadata = {{}}, operator_name = "add"}}> : (f64, f64) -> f64\n')
    yield 'parent-expression-actual', replace(replace(s, reference, reference + expression),
          override, actual('%parent_sum')), interface
    runtime = f'    %parent_runtime = "nodal.dynamic_value"({value}) <{{metadata = {{}}, origin = "runtime"}}> : (f64) -> f64\n'
    yield 'parent-runtime-actual', replace(replace(s, reference, reference + runtime),
          override, actual('%parent_runtime')), interface
    yield 'parent-unknown-formal', replace(s, override, replace(override,
          'parameter = @gain', 'parameter = @unknownGain')), interface
    yield 'parent-duplicate-symbolic', replace(s, override, override + override), interface
    for name, number in [('equal', 5), ('unequal', 6)]:
        changed = replace(instance, 'parameter_bindings = {offset = 7.0 : f64}',
                          f'parameter_bindings = {{gain = {number}.0 : f64, offset = 7.0 : f64}}')
        yield 'parent-' + name + '-literal-ssa', replace(s, instance, changed), interface
    # Selected Interface/CC admission precedes declaration kind/profile verification.
    yield 'parent-wrong-kind', replace(s, parent, replace(parent,
          'type = f64', 'parameter_kind = "integer", type = f64')), interface
    yield 'parent-structural-real', replace(s, parent, replace(parent,
          'classification = "ordinary"', 'classification = "structural"')), interface
    # Native units are canonical attributes, not descriptive bridge metadata.
    unit = '  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "ForwardingVolt", symbol = "V"}> : () -> ()\n'
    first_line = s.splitlines(True)[0]
    unit_case = replace(s, first_line, first_line + unit)
    yield 'parent-unit', replace(unit_case, parent, replace(parent,
          'type = f64', 'type = f64, unit = @ForwardingVolt')), interface
    changed = replace(parent, 'default_value = 5.0 : f64', 'default_value = 5 : i64')
    changed = replace(changed, 'type = f64', 'type = i64')
    wrong_type = replace(replace(replace(s, parent, changed), reference,
                                 replace(reference, '-> f64', '-> i64')), override,
                         replace(override, ': (f64) -> ()', ': (i64) -> ()'))
    yield 'parent-integer-actual', wrong_type, interface
    # Ownership must be retained discardable operation attributes, not unknown inherent properties.
    yield 'parent-forged-owner', replace(s, reference, replace(reference,
          '}> :', '}> {generated_owner = "Foreign.family", generated_induction = "Foreign.i"} :')), interface
    # Existing malformed-family ownership admission, not Real forwarding proof: a second
    # f64 argument fails the digital region profile before parameter/Generate verification.
    block = one_line(s, '^bb0(', ': i64):')
    block_case = replace(s, block, replace(block, ': i64):', ': i64, %parent_argument: f64):'))
    yield 'parent-blockarg-profile', replace(block_case, override, actual('%parent_argument')), 'NODAL-HIERARCHY-057-001: instance requires its exact bounded Module/Generate lexical owner'
    yield 'parent-fixed-formal', replace(s, formal, replace(formal,
          'variability = "symbolic"', 'variability = "fixed"')), interface
    excluded = ('    %excluded_five = "nodal.const_literal"() <{metadata = {}, spelling = "5.0", value = 5.0 : f64}> : () -> f64\n'
                '    "nodal.parameter_constraint"(%excluded_five) <{constraint_kind = "exclude", lower_inclusive = false, metadata = {}, parameter = @gain, upper_inclusive = false}> : (f64) -> ()\n')
    yield 'parent-excluded-default-five', replace(s, formal, formal + excluded), 'NODAL-PARAMETER-OVERRIDE-001: explicit override violates parameter contract'


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
        try:
            completed = subprocess.run(list(map(str, command)), cwd=source, env=env,
                                       capture_output=True, text=True, timeout=args.timeout)
        except subprocess.TimeoutExpired as expired:
            # TimeoutExpired may retain bytes even when text=True; preserve them exactly.
            def captured(value):
                return value if isinstance(value, bytes) else (value or '').encode('utf-8')
            (output / (name + '.stdout')).write_bytes(captured(expired.stdout))
            (output / (name + '.stderr')).write_bytes(captured(expired.stderr))
            rows.append({'name': name, 'command': list(map(str, command)), 'exit': None,
                         'timed_out': True, 'timeout_seconds': args.timeout})
            (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
            raise
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
             ('two-child', 'ReplayTwoLiteralChildren', ['two-child.mlir']),
             ('parent-reference', 'ReplayParentForwarding', ['parent-reference.mlir']),
             ('forged-parent', 'ReplayForgedParentForwarding', [])]
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
    adapted = native('two-child', 'adapt')
    emitted = native('two-child', 'emit')
    for text in ('parameter real gain = 2;', 'V(observed, reference) <+ (gain * 1);',
                 'i = 2; i < 8; i = i + 2'):
        require(text in emitted, 'two-child: missing ' + text)
    require(emitted.count('#(.gain(3))') == 1 and emitted.count('#(.gain(7))') == 1,
            'two-child: distinct literal bindings lost')
    require(adapted.count('"nodal.instance"()') == 2 and
            adapted.count('"nodal.instance_terminal"()') == 4,
            'two-child: adapter instance/terminal structure mismatch')
    records = [base64.b64decode(x, validate=True).decode() for x in
               re.findall(r'// nodal.interface_correspondence mlir-v1 (\S+)', emitted)]
    require(len(records) == 14 and
            sum('flow_orientation = "into_component"' in x for x in records) == 2,
            'two-child: correspondence/flow inventory mismatch')
    require(all('discipline = "electrical"' in x and 'selected_access = "connect"' in x
                for x in records), 'two-child: correspondence capability mismatch')
    bindings = [json.loads(x) for x in re.findall(r'// nodal.array_binding (.+)', emitted)]
    aliases = [x for x in bindings if x.get('alias_helper') == 'nodal_alias_electrical']
    selected = [x for x in aliases if not x.get('source_scope')]
    generated = [x for x in aliases if x.get('source_scope')]
    require(len(bindings) == 10 and len(selected) == 2 and len(generated) == 4,
            'two-child: shared-anchor relation inventory mismatch')
    require(all(x['right']['target_actual'] == 'top' and
                x['right']['root'] == 'SelectedTwoLiteralParameters.top' for x in aliases),
            'two-child: relation no longer reaches the actual shared module anchor')
    require(len({x['left']['root'] for x in generated}) == 4 and
            len({x['left']['root'] for x in selected}) == 2 and
            {x['left']['root'] for x in selected} <= {x['left']['root'] for x in generated},
            'two-child: distinct generated endpoints/selected occurrences lost')
    require(all(len(x['left']['stages']) == 1 and
                x['left']['stages'][0]['expression'] == '1' for x in selected),
            'two-child: selected ordinal changed')
    require(all(len(x['left']['stages']) == 1 and
                x['left']['stages'][0]['expression'] == '((i - 2) / 2)' for x in generated),
            'two-child: nonunit occurrence normalization changed')
    # Backend failures are independent of the now-supported shared-anchor topology.
    # Verifier/parser failures cannot exercise the backend transaction mode.
    require('failure_is_transactional=1' in native('fixed-override', 'transaction'),
            'fixed-override: nontransactional backend refusal')
    broken = replace((output / 'two-child.mlir').read_text(),
                     'variability = "symbolic"', 'variability = "fixed"')
    (output / 'two-child-fixed-override.mlir').write_text(broken)
    native('two-child-fixed-override', 'emit', False,
           'NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden')
    require('failure_is_transactional=1' in native('two-child-fixed-override', 'transaction'),
            'two-child-fixed-override: nontransactional backend refusal')
    for name, expected, count in [
            ('parent-reference', ['.gain(parentGain)', '.offset(7)'], 1),
            ('two-parent-references', ['.gain(parentFirst)', '.gain(parentSecond)'], 2),
            ('equal-parent-references', ['.gain(parentFirst)', '.gain(parentSecond)'], 2)]:
        native(name, 'verify')
        adapted = native(name, 'adapt')
        emitted = native(name, 'emit')
        again = run(name + '-emit-repeat', [probe, output / (name + '.mlir'), 'emit'])
        native_count += 1
        require(emitted == again, name + ': nondeterministic actual Verilog-A')
        require(all(emitted.count(actual) == 1 for actual in expected),
                name + ': lost exact symbolic actual identity')
        require('.gain(5)' not in emitted and '.gain(2)' not in emitted and '.gain(9)' not in emitted,
                name + ': actual was replaced by a parent or child default')
        require('parameter real gain = 2;' in emitted and 'i = 2; i < 8; i = i + 2' in emitted,
                name + ': child default or nonunit family changed')
        require(adapted.count('"nodal.instance"()') == count and
                adapted.count('"nodal.instance_terminal"()') == 2 * count,
                name + ': adapter instance/terminal shape changed')
        records = [base64.b64decode(x, validate=True).decode() for x in
                   re.findall(r'// nodal.interface_correspondence mlir-v1 (\S+)', emitted)]
        require(len(records) == (8 if count == 1 else 14) and
                sum('flow_orientation = "into_component"' in x for x in records) == 2,
                name + ': correspondence/flow identity changed')
        if count == 1:
            require('parameter real parentGain = 5;' in emitted,
                    name + ': emitted parent default changed')
        if count == 2:
            original = (output / (name + '.mlir')).read_text()
            child_definitions = []
            for member, source_name in [('first', 'parentFirst'), ('second', 'parentSecond')]:
                expected_symbol = 'generate_0_lanes_' + member + '_instance'
                child_instance = one_line(original, '"nodal.instance"()',
                                          'sym_name = "' + expected_symbol + '"')
                symbol = field(child_instance, 'sym_name')
                child_definitions.append(re.search(r'\bmodule = (@(?:"[^"]+"|[A-Za-z_]\w*))', child_instance).group(1))
                reference = one_line(original, '"nodal.const_parameter_ref"',
                                     'parameter = @' + source_name + '}')
                source_value = re.match(r'\s*(%[\w]+) =', reference).group(1)
                binding = one_line(original, '"nodal.parameter_override"',
                                   'instance = @' + symbol + ',')
                require('"nodal.parameter_override"(' + source_value + ')' in binding and
                        'parameter = @gain' in binding,
                        name + ': wrong MLIR actual for authored ' + member + ' child')
                instance_line = one_line(emitted, ') ' + symbol + '(')
                require('#(.gain(' + source_name + ')) ' + symbol + '(' in instance_line,
                        name + ': symbolic actual attached to wrong emitted child ' + member)
            require(len(set(child_definitions)) == 1, name + ': canonical child definition was not reused')
            require('parameter real parentFirst = 5;' in emitted and
                    ('parameter real parentSecond = 5;' if name.startswith('equal') else
                     'parameter real parentSecond = 9;') in emitted,
                    name + ': parent declaration defaults changed')
            bindings = [json.loads(x) for x in re.findall(r'// nodal.array_binding (.+)', emitted)]
            aliases = [x for x in bindings if x.get('alias_helper') == 'nodal_alias_electrical']
            selected = [x for x in aliases if not x.get('source_scope')]
            generated = [x for x in aliases if x.get('source_scope')]
            require(len(bindings) == 10 and len(selected) == 2 and len(generated) == 4,
                    name + ': reused child topology inventory changed')
            require(all(x['right']['target_actual'] == 'top' for x in aliases) and
                    len({x['left']['root'] for x in generated}) == 4 and
                    len({x['left']['root'] for x in selected}) == 2 and
                    {x['left']['root'] for x in selected} <= {x['left']['root'] for x in generated},
                    name + ': distinct endpoint identities or shared anchor lost')
    parent_text = (output / 'parent-reference.mlir').read_text()
    parent_decl = one_line(parent_text, '"nodal.parameter"()', 'sym_name = "parentGain"')
    default_expr = ('    %parent_two = "nodal.const_literal"() <{metadata = {}, spelling = "2.0", value = 2.0 : f64}> : () -> f64\n'
                    '    %parent_three = "nodal.const_literal"() <{metadata = {}, spelling = "3.0", value = 3.0 : f64}> : () -> f64\n'
                    '    %parent_five = "nodal.const_expr"(%parent_two, %parent_three) <{metadata = {}, operator_name = "add"}> : (f64, f64) -> f64\n'
                    '    "nodal.parameter_value"(%parent_five) <{metadata = {}, parameter = @parentGain}> : (f64) -> ()\n')
    (output / 'parent-expression-default.mlir').write_text(replace(parent_text, parent_decl, parent_decl + default_expr))
    native('parent-expression-default', 'verify')
    expression_default = native('parent-expression-default', 'emit')
    require('.gain(parentGain)' in expression_default and '.gain(5)' not in expression_default,
            'parent-expression-default: direct symbolic identity lost')
    for name, text, diagnostic in parent_mutations((output / 'parent-reference.mlir').read_text()):
        (output / (name + '.mlir')).write_text(text)
        refused_target = native(name, 'emit', False, diagnostic)
        require(not refused_target, name + ': refusal emitted target stdout')
    (output / 'summary.json').write_text(json.dumps({'status': 'pass', 'scala_mains': len(mains),
        'native_invocations': native_count, 'historical_old_native_comparisons': 0}, indent=2) + '\n')
    print(f'PASS: {len(mains)} Scala mains; {native_count} native invocations. Results: {output}')


if __name__ == '__main__':
    main()
