#!/usr/bin/env python3
"""Twelve bounded native witnesses; explicit probe only, no builds or discovery.

Invoke with python3 -I -B under the coordinator's reviewed resource/lock wrapper.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

RESERVE = 235 * 1024 * 1024
HERE = Path(__file__).resolve().parent
OVERRIDE_GATE = ('NODAL-DIGITAL-055-014: generated child parameter override requires '
                 'exact all-lane Integer formal/actual proof or exact direct parent ordinary '
                 'dimensionless Real reference')
CASES = (
    ('general-parent-real', 'parameter-contract', True, None),
    ('general-foreign-parent', 'parameter-contract', False,
     'NODAL-CONSTANT-EXPR-001: parameter reference is missing or has the wrong result type'),
    ('general-nested-parent', 'parameter-contract', False, OVERRIDE_GATE),
    ('general-runtime-parent', 'parameter-contract', False, OVERRIDE_GATE),
    ('general-integer-parent', 'parameter-contract', False, OVERRIDE_GATE),
    ('general-excluded-parent-default', 'verify', True, None),
    ('general-excluded-parent-default', 'parameter-contract', False,
     'NODAL-PARAMETER-OVERRIDE-001: explicit override violates parameter contract'),
    ('integer-finite-signed', 'parameter-contract', True, None),
    ('integer-checked-empty', 'parameter-contract', True, None),
    ('integer-all-lane-not-default', 'parameter-contract', False,
     'NODAL-DIGITAL-055-014: static arithmetic is not valid over every legal value in its actual scope'),
    ('scalar-direct-forwarding', 'materialized-scalar', True, None),
    ('scalar-direct-forwarding', 'emit', True, None),
)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def captured(value):
    return value if isinstance(value, bytes) else (value or '').encode('utf-8')


def main():
    require(sys.flags.isolated and sys.dont_write_bytecode, 'invoke with python3 -I -B')
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repository-root', 'probe', 'output-root'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    require(args.timeout > 0, 'timeout must be positive')
    repo = Path(args.repository_root).resolve(strict=True)
    probe = Path(args.probe).resolve(strict=True)
    output = Path(args.output_root).resolve()
    require((repo / '.git').exists() and probe.is_file(), 'invalid explicit repository/probe')
    require(output.is_relative_to(repo / '.work') and output != repo / '.work',
            'fresh output must be strictly inside repository .work')
    require(not output.exists(), 'refusing existing output')
    require(shutil.disk_usage(repo).free >= RESERVE, '235 MiB disk reserve required')
    # Maintained fixtures are script-relative; the caller freezes probe/source provenance.
    names = sorted({name for name, _, _, _ in CASES})
    require({path.name for path in (HERE / 'fixtures').glob('*.mlir')} ==
            {name + '.mlir' for name in names}, 'fixture inventory changed')
    fixtures = [HERE / 'fixtures' / (name + '.mlir') for name in names]
    for fixture in fixtures:
        require(fixture.is_file(), 'missing fixture: ' + fixture.name)
    output.mkdir(parents=True)
    (output / 'tmp').mkdir()
    (output / 'home').mkdir()
    env = {k: v for k, v in os.environ.items() if k not in ('PYTHONPATH', 'PYTHONHOME')}
    env.update(TMPDIR=str(output / 'tmp'), HOME=str(output / 'home'))
    inputs = [probe, Path(__file__).resolve()] + fixtures
    original = {str(path): digest(path) for path in inputs}
    (output / 'inputs.json').write_text(json.dumps(original, indent=2) + '\n')
    rows, failures = [], []

    def check(condition, message):
        if not condition:
            failures.append(message)

    for name, mode, good, diagnostic in CASES:
        require(shutil.disk_usage(repo).free >= RESERVE, '235 MiB disk reserve exhausted')
        label = name + '-' + mode
        command = [str(probe), str(HERE / 'fixtures' / (name + '.mlir')), mode]
        try:
            result = subprocess.run(command, cwd=repo, env=env, capture_output=True,
                                    timeout=args.timeout, check=False)
        except subprocess.TimeoutExpired as expired:
            (output / (label + '.stdout')).write_bytes(captured(expired.stdout))
            (output / (label + '.stderr')).write_bytes(captured(expired.stderr))
            rows.append({'name': label, 'command': command, 'exit': None,
                         'timed_out': True, 'timeout_seconds': args.timeout})
            (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
            raise
        (output / (label + '.stdout')).write_bytes(result.stdout)
        (output / (label + '.stderr')).write_bytes(result.stderr)
        rows.append({'name': label, 'command': command, 'exit': result.returncode})
        (output / 'commands.json').write_text(json.dumps(rows, indent=2) + '\n')
        check(result.returncode >= 0, label + ': signal is not a valid refusal')
        check((result.returncode == 0) == good, label + ': unexpected exit ' + str(result.returncode))
        stderr = result.stderr.decode('utf-8', errors='replace')
        stdout = result.stdout.decode('utf-8', errors='replace')
        check(diagnostic is None or diagnostic in stderr, label + ': missing exact causal diagnostic')
        if mode in ('verify', 'parameter-contract'):
            check(not result.stdout, label + ': verifier unexpectedly emitted target text')
        if mode == 'materialized-scalar':
            # Probe also retains the unit's two same-source emissions and exact IR comparison.
            for fragment in (
                'module Top', 'parameter real gain = 4', 'localparam real nodal_h1_gain = gain',
                'localparam real nodal_h3_gain = 3', 'localparam real nodal_h5_gain = 5',
                'nodal_h2_identitySignal', 'nodal_h4_identitySignal', 'nodal_h6_identitySignal',
                'nodal.hierarchy_correspondence',
            ):
                check(fragment in stdout, label + ': missing materialized identity ' + fragment)
            check('module Branch' not in stdout and 'module Leaf' not in stdout,
                  label + ': retained named child module')
        if mode == 'emit':
            check('module Branch' in stdout and 'Branch #(.gain(gain))' in stdout,
                  label + ': named direct-parent forwarding changed')
    for path, before in original.items():
        check(digest(Path(path)) == before, 'input changed: ' + path)
    check(len(rows) == 12, 'supplement must execute exactly 12 native invocations')
    summary = {'status': 'fail' if failures else 'pass', 'native_invocations': len(rows),
               'materialized_internal_emissions': 2, 'historical_old_native_comparisons': 0,
               'failures': failures}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    if failures:
        for failure in failures:
            print('FAIL: ' + failure, file=sys.stderr)
        raise RuntimeError('supplement failed; preserved per-case stdout/stderr and summary')
    print('PASS: 12 native invocations; 2 in-process materialized emissions; no historical binaries.')


if __name__ == '__main__':
    main()
