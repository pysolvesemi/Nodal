#!/usr/bin/env python3
"""Verify canonical generate closing comments without rewriting emitted SV."""
from pathlib import Path
import argparse
import difflib
import hashlib
import json
import os
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--predecessor', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=Path(__file__).with_name('fixtures') / 'generate-consumers')
    parser.add_argument('--iverilog', required=True)
    parser.add_argument('--vvp', required=True)
    args = parser.parse_args()
    prefix, predecessor, work = args.toolchain.resolve(), args.predecessor.resolve(), args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    env = {**os.environ, 'LD_LIBRARY_PATH': str(prefix / 'lib')}
    before_env = {**os.environ, 'LD_LIBRARY_PATH': str(predecessor / 'lib')}
    receipt = {'status': 'running', 'scope': 'named hierarchy, unchanged generated SV, four-state',
               'toolchain': json.loads((prefix / '.nodal-toolchain.json').read_text()), 'commands': [], 'cases': []}
    def save():
        (work / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    def run(name, argv, environment=env):
        argv = list(map(str, argv))
        result = subprocess.run(argv, env=environment, capture_output=True, text=True, timeout=300)
        log = work / (name + '.log')
        log.write_text(result.stdout + result.stderr)
        receipt['commands'].append({'name': name, 'argv': argv, 'runtime': environment['LD_LIBRARY_PATH'], 'exit': result.returncode, 'log_sha256': sha(log)})
        save()
        if result.returncode:
            raise RuntimeError(f'{name}: {result.stderr[-2000:]}')
        return result.stdout
    for name in ['ssa-lvalue-early', 'ssa-lvalue-late', 'ssa-multiple-results', 'graph-instance', 'nested']:
        source, bench = args.fixtures / (name + '.mlir'), args.fixtures / (name + '-tb.sv')
        sv, before = work / (name + '.sv'), work / (name + '-baseline.sv')
        run(name + '-baseline-export', [predecessor / 'bin/firtool', source, '--format=mlir', '--verilog', '--verify-each', '-o', before], before_env)
        run(name + '-export', [prefix / 'bin/firtool', source, '--format=mlir', '--verilog', '--verify-each', '-o', sv])
        delta = list(difflib.unified_diff(before.read_text().splitlines(), sv.read_text().splitlines()))
        changed = [line for line in delta if line[:1] in ['+', '-'] and not line.startswith(('+++', '---'))]
        assert all(line.lstrip('+- ').startswith(('end: ', 'end // ')) for line in changed), changed
        assert bool(changed) == (name != 'graph-instance')
        digest = sha(sv)
        executable = work / (name + '.sim')
        run(name + '-compile', [args.iverilog, '-g2012', '-s', 'tb', '-o', executable, sv, bench])
        assert 'PASS ' + name in run(name + '-wave', [args.vvp, executable])
        assert sha(sv) == digest
        receipt['cases'].append({'name': name, 'source_sha256': sha(source), 'bench_sha256': sha(bench), 'sv_sha256': digest})
    receipt['status'] = 'passed'
    save()
    print('GENERATE_CONSUMER_PASS')


if __name__ == '__main__':
    main()
