#!/usr/bin/env python3
"""Fresh driven and automatic private diagnostic controls; no API or lookup-tree build.

Run with python3 -I -B. See DIAGNOSTIC_POLICY.md for scope and inputs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

MANIFEST = 'driven_diagnostic.fixtures.json'
RESERVE = 246808576
FLAGS = ('-color:never', '-Wunused:all', '-Werror')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def relative(value):
    path = Path(value)
    require(path.parts and not path.is_absolute() and '..' not in path.parts,
            'expected nonempty relative manifest path')
    return path


def main():
    require(sys.flags.isolated and sys.dont_write_bytecode, 'invoke with python3 -I -B')
    maintained = Path(__file__).resolve().parent
    manifest_path = maintained / MANIFEST
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    require(manifest['schema'] == 1, 'unsupported manifest schema')
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repository-root', 'compiler-classpath', 'runtime-classpath',
                 'api-jar', 'java', 'output-root', 'effective-source-map'):
        parser.add_argument('--' + name, required=True)
    for group in ('policy',):
        parser.add_argument('--' + group + '-source-root')
        parser.add_argument('--' + group + '-source-pins')
    parser.add_argument('--bridge-jar', action='append', required=True)
    parser.add_argument('--timeout', type=int, required=True)
    parser.add_argument('--suite', action='append', choices=list(manifest['suites']))
    args = parser.parse_args()
    require(args.timeout > 0, 'timeout must be positive')
    repo = Path(args.repository_root).resolve(strict=True)
    output = Path(args.output_root).resolve()
    require((repo / '.git').exists(), 'repository root must contain .git')
    require(output != repo and output.is_relative_to(repo) and not output.exists(),
            'output must be new and strictly inside repository')
    require(subprocess.run(['git', '-C', str(repo), 'check-ignore', '-q', str(output)],
                           timeout=args.timeout, check=False).returncode == 0,
            'output must be git-ignored')
    selected = args.suite or manifest['default_suites']
    require(len(selected) == len(set(selected)), 'duplicate suite')
    groups = sorted({manifest['suites'][name]['group'] for name in selected})
    expected = {}

    def pin(path, expected_hash=None):
        path = path.resolve(strict=True)
        require(path.is_file() and not path.is_relative_to(output), 'invalid input: ' + str(path))
        actual = digest(path)
        require(expected_hash is None or actual == expected_hash, 'input hash mismatch: ' + str(path))
        expected[path] = actual
        return path

    mapping = json.loads(pin(Path(args.effective_source_map)).read_text())
    roots, fixtures = {}, {}
    for group in groups:
        spec = manifest['groups'][group]
        root_arg = getattr(args, group + '_source_root')
        pins_arg = getattr(args, group + '_source_pins')
        require(root_arg and pins_arg, 'selected group needs explicit source root and pins: ' + group)
        root = Path(root_arg).resolve(strict=True)
        roots[group] = root
        rows = json.loads(pin(Path(pins_arg)).read_text())
        for row in rows:
            if 'virtual_path' in row:
                virtual = relative(row['virtual_path'])
            else:
                # Legacy repository-relative pin lists preserve their exact matched root.
                virtual = (repo / relative(row['path'])).relative_to(root)
            path = pin(root / virtual, row['sha256'])
            require(path.is_relative_to(root), 'lookup pin outside supplied root')
        for virtual, item in mapping.items():
            pin(root / relative(virtual), item['sha256'])
        for marker in ('build.mill', 'core/scala', 'docs'):
            require((root / marker).exists(), 'incoherent lookup root: missing ' + marker)
        fixtures[group] = []
        for entry in spec['fixtures']:
            bundled = maintained / relative(entry['maintained_path'])
            require(bundled.resolve().is_relative_to(maintained), 'bundled fixture escapes test tree')
            pin(bundled, entry['sha256'])
            source = pin(root / relative(entry['virtual_path']), entry['sha256'])
            if entry['compile']:
                fixtures[group].append(source)
    java = pin(Path(args.java))
    require(os.access(java, os.X_OK), 'java must be executable')
    compiler = [pin(Path(p)) for p in args.compiler_classpath.split(os.pathsep)]
    runtime = [pin(Path(p)) for p in args.runtime_classpath.split(os.pathsep)]
    api = pin(Path(args.api_jar), manifest['api_sha256'])
    bridge = [pin(Path(p)) for p in args.bridge_jar]
    require(all(p.suffix == '.jar' for p in compiler + runtime + [api] + bridge),
            'classpath inputs must be explicit JAR files, not class directories')
    pin(Path(__file__))
    pin(manifest_path)
    require(shutil.disk_usage(repo).free >= RESERVE, 'need at least 235 MiB free')
    output.mkdir(parents=True)
    for name in ('tmp', 'home', 'cache', 'runtime'):
        (output / name).mkdir()
    save(output / 'inputs.json', {str(p): value for p, value in expected.items()})
    env = {key: value for key, value in os.environ.items() if key not in (
        'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH',
        'PYTHONPATH', 'PYTHONHOME', 'NODAL_WORKSPACE', 'GITHUB_WORKSPACE',
        'BUILD_WORKSPACE_DIRECTORY')}
    env.update(HOME=str(output / 'home'), TMPDIR=str(output / 'tmp'),
               XDG_CACHE_HOME=str(output / 'cache'))
    rows = []

    def verify():
        for path, value in expected.items():
            require(digest(path) == value, 'input changed during run: ' + str(path))

    def run(name, command, group, success=()):
        verify()
        require(shutil.disk_usage(repo).free >= RESERVE, 'disk reserve exhausted')
        command = list(map(str, command))
        save(output / (name + '.command.json'), command)
        row = dict(name=name, status='running', source_root=str(roots[group]))
        rows.append(row)
        save(output / 'results.json', rows)
        try:
            with (output / (name + '.stdout')).open('w') as stdout, \
                    (output / (name + '.stderr')).open('w') as stderr:
                done = subprocess.run(command, cwd=repo,
                    env=dict(env, NODAL_WORKSPACE=str(roots[group])), stdout=stdout,
                    stderr=stderr, timeout=args.timeout, check=False)
            row['exit'] = done.returncode
            require(done.returncode == 0, name + ': nonzero exit; inspect logs')
            text = (output / (name + '.stdout')).read_text()
            require(all(marker in text for marker in success), name + ': missing completion marker')
            verify()
            row['status'] = 'passed'
        except Exception as failure:
            row.update(status='failed', error=str(failure))
            raise
        finally:
            save(output / 'results.json', rows)

    java_base = [java, '-XX:-UsePerfData', '-Djava.io.tmpdir=' + str(output / 'tmp'),
                 '-Duser.home=' + str(output / 'home')]
    cp = os.pathsep.join(map(str, [api] + bridge + runtime))
    for group in groups:
        classes = output / (group + '-classes')
        classes.mkdir()
        run(group + '-compile', java_base + ['-cp', os.pathsep.join(map(str, compiler)),
            'dotty.tools.dotc.Main', '-classpath', cp, '-d', classes, *FLAGS, *fixtures[group]], group)
        for name in selected:
            suite = manifest['suites'][name]
            if suite['group'] != group:
                continue
            arguments = [arg.format(lookup=roots[group], runtime=output / 'runtime')
                         for arg in suite['arguments']]
            run(name, java_base + ['-cp', str(classes) + os.pathsep + cp,
                'nodal.internal.testkit.' + suite['main'], *arguments], group, suite['success'])
    verify()
    save(output / 'summary.json', dict(passed=selected,
        scope='private opt-in policy diagnostics only; default65536 remains unaccepted; driven131072 topology is separate'))
    print('PASS private diagnostic-policy controls: ' + ', '.join(selected))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print('Groundwork controls failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
