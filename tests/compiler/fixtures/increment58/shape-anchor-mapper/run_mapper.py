#!/usr/bin/env python3
"""Run exactly the thirteen preserved mapper contract cases; never build a probe."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', required=True, type=Path,
                        help='explicit existing map-controls executable, not the backend probe')
    parser.add_argument('--receipt', required=True, type=Path,
                        help='new JSON receipt; existing files are never overwritten')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    probe = args.probe.resolve(strict=True)
    if args.receipt.exists():
        parser.error('--receipt already exists')
    manifest = json.loads((root / 'cases.json').read_text())
    if digest((root / 'map-controls.cpp').read_bytes()) != manifest['source_sha256']:
        parser.error('probe source differs from preserved accepted source')
    rows = []
    for case in manifest['cases']:
        fixture = root / case['file']
        if digest(fixture.read_bytes()) != case['sha256']:
            parser.error('fixture hash mismatch: ' + case['file'])
        command = [str(probe), str(fixture), case['mode']]
        result = subprocess.run(command, capture_output=True, timeout=120, check=False)
        matches = re.findall(rb'^SUMMARY checks=(\d+) failures=(\d+)$', result.stdout,
                             flags=re.MULTILINE)
        passed = (result.returncode == 0 and
                  matches == [(str(case['checks']).encode(), b'0')] and
                  digest(result.stdout) == case['stdout_sha256'] and
                  digest(result.stderr) == case['stderr_sha256'])
        rows.append({'case': case['file'], 'mode': case['mode'],
                     'argv': command, 'exit_code': result.returncode,
                     'checks': case['checks'] if passed else 0, 'passed': passed,
                     'stdout_sha256': digest(result.stdout),
                     'stderr_sha256': digest(result.stderr)})
        print(('PASS ' if passed else 'FAIL ') + case['file'])
    total = sum(row['checks'] for row in rows)
    passed = (len(rows) == manifest['expected_calls'] == 13 and
              total == manifest['expected_checks'] == 1796 and
              all(row['passed'] for row in rows))
    receipt = {'probe_sha256': digest(probe.read_bytes()),
               'runner_sha256': digest(Path(__file__).read_bytes()),
               'cases_sha256': digest((root / 'cases.json').read_bytes()),
               'source_sha256': manifest['source_sha256'], 'calls': len(rows),
               'checks': total, 'passed': passed, 'results': rows}
    with args.receipt.open('x') as stream:
        json.dump(receipt, stream, indent=2)
        stream.write('\n')
    print(f"calls={len(rows)} checks={total} passed={passed}")
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
