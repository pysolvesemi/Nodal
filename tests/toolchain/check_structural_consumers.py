#!/usr/bin/env python3
"""Focused pinned-consumer checks; unchanged SV, Slang/arcilator two-state only."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=Path(__file__).with_name('fixtures') / 'structural-consumers')
    parser.add_argument('--cxx', default='c++')
    args = parser.parse_args()
    prefix = args.toolchain.resolve()
    args.work.mkdir(parents=True, exist_ok=False)
    manifest = args.work / 'manifest.json'
    cxx = shutil.which(args.cxx)
    if cxx is None:
        raise RuntimeError('C++ compiler is unavailable')
    env = {**os.environ, 'LD_LIBRARY_PATH': str(prefix / 'lib')}
    tools = [prefix / 'bin' / name for name in ['circt-opt', 'firtool', 'circt-verilog', 'arcilator', 'llc']]
    receipt = {'status': 'running', 'semantics': 'two-state', 'environment': {'LD_LIBRARY_PATH': env['LD_LIBRARY_PATH']}, 'tools': {str(p): sha(p) for p in [*tools, Path(cxx)]}, 'commands': [], 'cases': []}
    identity = prefix / '.nodal-toolchain.json'
    receipt['toolchain'] = {'path': str(identity), 'sha256': sha(identity), 'identity': json.loads(identity.read_text())}

    def save() -> None:
        manifest.write_text(json.dumps(receipt, indent=2) + '\n')

    def run(name: str, argv: list[str | Path]) -> str:
        command = list(map(str, argv))
        result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=300)
        log = args.work / (name + '.log')
        log.write_text(result.stdout)
        receipt['commands'].append({'argv': command, 'exit': result.returncode, 'log': str(log), 'log_sha256': sha(log)})
        save()
        if result.returncode:
            raise RuntimeError(f'{name} failed: {result.stdout[-2000:]}')
        return result.stdout

    cases = [('ssa-multiple-results', 'Top', [('one', 8), ('two', 8)], [0, 1]),
             ('ssa-lvalue-early', 'LValue', [('output_0', 16)], [None]),
             ('ssa-lvalue-late', 'LValue', [('output_0', 16)], [None]),
             ('graph-instance', 'Graph', [('value', 8)], [None])]
    for name, top, outputs, configs in cases:
        source = args.fixtures / (name + '.mlir')
        post = args.work / (name + '.post.mlir')
        sv = args.work / (name + '.sv')
        run(name + '-prettify', [tools[0], source, '--prettify-verilog', '-o', post])
        run(name + '-reparse', [tools[0], post, '-o', args.work / (name + '.verified.mlir')])
        run(name + '-export', [tools[1], '--format=mlir', source, '-o', sv])
        sv_hash = sha(sv)
        for config in configs:
            key = name + ('' if config is None else f'-{config}')
            imported = args.work / (key + '.import.mlir')
            llvm = args.work / (key + '.ll')
            state = args.work / (key + '.state.json')
            obj = args.work / (key + '.o')
            driver = args.work / (key + '.cpp')
            executable = args.work / (key + '.simulate')
            override = [] if config is None else ['-G', f'ENABLE={config}']
            run(key + '-import', [tools[2], '--ir-hw', '--top', top, *override, sv, '-o', imported])
            run(key + '-arc', [tools[3], imported, '--emit-llvm', f'--state-file={state}', '-o', llvm])
            models = json.loads(state.read_text())
            assert len(models) == 1 and models[0]['name'] == top
            model = models[0]
            assert not model['initialFnSym'] and not model['finalFnSym']
            pins = {pin['name']: pin for pin in model['states']}
            assert set(pins) == {'data', *(name for name, _ in outputs)}
            assert (pins['data']['type'], pins['data']['numBits']) == ('input', 8)
            checks = []
            for output, width in outputs:
                assert (pins[output]['type'], pins[output]['numBits']) == ('output', width)
                offset = pins[output]['offset']
                value = f'state[{offset}]' if width == 8 else f'(state[{offset}] | (state[{offset + 1}] << 8))'
                expected = 'v' if width == 8 else '(v | (v << 8))'
                checks.append(f'if ({value} != {expected}) return 1;')
            driver.write_text(f'''#include <cstdint>
#include <cstdio>
#include <initializer_list>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}};
  for (unsigned v : {{0x39u, 0xa6u, 0u, 255u}}) {{
    state[{pins['data']['offset']}] = v;
    {top}_eval(state);
    {''.join(checks)}
  }}
  std::puts("STRUCTURAL_CONSUMER_PASS");
}}
''')
            run(key + '-object', [tools[4], '-filetype=obj', '-relocation-model=pic', llvm, '-o', obj])
            run(key + '-link', [cxx, '-std=c++17', driver, obj, f'-L{prefix}/lib', '-lCIRCTArcRuntime', f'-Wl,-rpath,{prefix}/lib', '-o', executable])
            assert 'STRUCTURAL_CONSUMER_PASS' in run(key + '-simulate', [executable])
            assert sha(sv) == sv_hash
        receipt['cases'].append({'name': name, 'source_sha256': sha(source), 'sv_sha256': sv_hash, 'configurations': configs})
        save()
    receipt['status'] = 'passed'
    save()


if __name__ == '__main__':
    main()
