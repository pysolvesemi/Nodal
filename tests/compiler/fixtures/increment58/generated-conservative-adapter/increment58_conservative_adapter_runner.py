#!/usr/bin/env python3
"""Focused conservative-adapter replay; no build, historical receipts or old binaries."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import importlib.util

# Load the uniquely named sibling by absolute file location; discovery does not
# depend on sys.path order or a generic module named "generators".
_generator_path = Path(__file__).resolve().with_name("increment58_conservative_adapter_generators.py")
_generator_spec = importlib.util.spec_from_file_location("increment58_conservative_adapter_generators", _generator_path)
assert _generator_spec is not None and _generator_spec.loader is not None
_generators = importlib.util.module_from_spec(_generator_spec)
_generator_spec.loader.exec_module(_generators)
GENERATORS = _generators.GENERATORS
replace_exact = _generators.replace_exact

SOURCE = Path(__file__).resolve().parent
MIN_FREE = (200 + 37) * 1024 * 1024
PROBE_TIMEOUT_SECONDS = 60
DEFAULT_PUBLIC_PROFILE = "coherent-source53"
PUBLIC_PROFILES = ("coherent-source53", "historical")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def reserve(path, extra=0):
    free = shutil.disk_usage(path).free
    if free < MIN_FREE + extra:
        raise RuntimeError(f"Disk reserve requires {MIN_FREE + extra} bytes; available {free}")
    return free


def public_expectations(profile):
    if profile not in PUBLIC_PROFILES:
        raise ValueError(f"Unknown public expectation profile: {profile}")
    profiles = json.loads((SOURCE / 'public-profiles.json').read_text())
    return profiles[profile]


def cases(public_profile=DEFAULT_PUBLIC_PROFILE):
    rows = json.loads((SOURCE / 'cases.json').read_text())
    profile = public_expectations(public_profile)
    public_names = {row['name'] for row in rows if row['kind'] == 'public-source-output'}
    assert set(profile) == public_names
    for row in rows:
        if row['kind'] == 'public-source-output':
            expectation = profile[row['name']]
            row['input_sha256'] = expectation['input_sha256']
            row['checks'] = expectation['checks']
    return rows


def generate(root):
    """Materialize small fixtures; preserve reviewed overrides of stale recipes."""
    root.mkdir(parents=True, exist_ok=False)
    shutil.copytree(SOURCE / 'templates', root, dirs_exist_ok=True)
    shutil.copyfile(root / 'multi-leaf/multi-leaf.mlir', root / 'multi-leaf/count3.mlir')
    (root / 'nonunit-target').mkdir(exist_ok=True)
    for generator in GENERATORS:
        generator(root)
    for case in cases():
        if case['kind'] == 'native-adapter-fixture':
            path = root / case['name']
            assert digest(path.read_bytes()) == case['input_sha256'], case['name']


def budget(base, count):
    assert count in (49912, 49913)
    pos = base.rfind('}')
    assert pos >= 0 and not base[pos + 1:].strip()
    op = '"nodal.const_literal"(){metadata={},spelling="0",value=0:i64}:()->i64\n'
    return base[:pos] + op * count + base[pos:]


def valid_result(check, status, stdout, stderr):
    return (status == check['exit'] and digest(stdout) == check['stdout_sha256']
            and (check['mode'] != 'transaction' or b'failure_is_transactional=1' in stdout)
            and (check['group'] != 'budget-boundary' or check['exit'] != 5 or b'budget' in stderr))


def structural(name, outputs):
    """Original selected-child/checks-final.py positive structural assertions."""
    a, s = outputs['adapt'], outputs['emit']
    assert s.count('for (') == 1, name
    expected = 2 if name == 'two-instances' else 1
    assert a.count('"nodal.instance"()') == expected
    assert a.count('"nodal.instance_terminal"()') == 2 * expected
    assert a.count('"nodal.node_array"()') == 2 * expected
    records = [base64.b64decode(x).decode() for x in
               re.findall(r'// nodal.interface_correspondence mlir-v1 (\S+)', s)]
    names = set(re.findall(r'(?:name|sym_name) = "([^"]+)"', a))
    names.update(re.findall(r'sym_name = ([A-Za-z_][A-Za-z0-9_]*)', a))
    for record in records:
        target = re.search(r'target = "([^"]+)"', record).group(1)
        assert target in names, (name, target)
        assert 'discipline = "electrical"' in record
        assert 'selected_access = "connect"' in record
    assert len(records) == (14 if name == 'two-instances' else 10 if name == 'distinct-view' else 8)
    assert sum('flow_orientation = "into_component"' in record for record in records) == 2
    if name.startswith('count'):
        count = int(name[5:])
        assert s.count(f'electrical [0:{count - 1}] ') == 2 and f'i < {count};' in s
    if name == 'lower2-step2':
        assert 'i = 2; i < 8; i = i + 2' in s and s.count('electrical [0:2] ') == 2
    if name == 'lower1-step3':
        assert 'i = 1; i < 10; i = i + 3' in s and s.count('electrical [0:2] ') == 2
    return len(re.findall(r'"nodal\.[a-z_]+"', a))


def run(probe, public, output, public_profile=DEFAULT_PUBLIC_PROFILE):
    public_profile_rows = public_expectations(public_profile)
    reserve(output.parent, 12 * 1024 * 1024)
    output.mkdir(exist_ok=False)
    generated = output / 'inputs'
    generate(generated)
    rows = []
    operation_counts = {}
    for case in cases(public_profile):
        reserve(output, 4 * 1024 * 1024)
        name = case['name']
        is_budget = case['kind'] == 'budget-generator-and-base'
        if case['kind'] == 'public-source-output':
            path = public / public_profile_rows[name]['input_path']
        elif is_budget:
            count = int(Path(name).stem.split('-')[-1])
            path = output / 'budget-current.mlir'
            path.write_text(budget((generated / 'selected-child/count3.mlir').read_text(), count))
        else:
            path = generated / name
        assert digest(path.read_bytes()) == case['input_sha256'], name
        outputs = {}
        try:
            for check in case['checks']:
                process = subprocess.run([str(probe), str(path), check['mode']], capture_output=True, timeout=PROBE_TIMEOUT_SECONDS)
                assert valid_result(check, process.returncode, process.stdout, process.stderr), (
                    name, check['mode'], process.returncode, process.stderr.decode(errors='replace'))
                assert digest(path.read_bytes()) == case['input_sha256'], (name, 'input mutated')
                outputs[check['mode']] = process.stdout.decode()
                rows.append({'case': name, 'group': check['group'], 'mode': check['mode'],
                             'exit': process.returncode, 'stdout_sha256': digest(process.stdout)})
                (output / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
            if name.startswith('selected-child/') and 'adapt' in outputs:
                operation_counts[Path(name).stem] = structural(Path(name).stem, outputs)
        finally:
            if is_budget:
                path.unlink()
    assert len(rows) == 125
    assert len({operation_counts[n] for n in ('count1', 'count3', 'count1000')}) == 1
    summary = {'public_profile': public_profile, 'executions': len(rows), 'inputs': len(cases()), 'structural_cases': len(operation_counts),
               'probe_sha256': digest(probe.read_bytes()), 'free_bytes_after': reserve(output),
               'padded_files_retained': 0}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', required=True, type=Path)
    parser.add_argument('--public-input-dir', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--public-profile', choices=PUBLIC_PROFILES, default=DEFAULT_PUBLIC_PROFILE)
    args = parser.parse_args()
    run(args.probe.resolve(), args.public_input_dir.resolve(), args.output_dir.resolve(), args.public_profile)
