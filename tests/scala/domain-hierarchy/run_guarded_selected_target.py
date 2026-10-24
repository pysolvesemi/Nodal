#!/usr/bin/env python3
"""Actual count>1 selected child source: unchanged SV for count 0, 1, 2, 3."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from run_selected_instance_target import Profile, digest, harness


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ['nodalc', 'toolchain', 'input', 'output', 'cxx']:
        p.add_argument('--' + key, type=Path, required=True)
    a = p.parse_args()
    if '.work' not in a.output.resolve().parts:
        p.error('outputs must remain in ignored .work')
    a.output.mkdir(parents=True, exist_ok=False)
    o = a.output
    m = {'status': 'running', 'compiler_sha256': digest(a.nodalc),
         'source_sha256': digest(a.input), 'runner_sha256': digest(Path(__file__)),
         'harness_owner_sha256': digest(Path(__file__).with_name('run_selected_instance_target.py')),
         'toolchain_descriptor_sha256': digest(a.toolchain / '.nodal-toolchain.json'),
         'runtime_library_path': os.environ.get('LD_LIBRARY_PATH', ''),
         'commands': [], 'configurations': []}
    def save():
        (o / 'manifest.json').write_text(json.dumps(m, indent=2) + '\n')
    def run(label, cmd):
        cmd = list(map(str, cmd))
        q = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        text = q.stdout + q.stderr
        (o / (label + '.log')).write_text(text)
        m['commands'].append({'label': label, 'argv': cmd, 'exit': q.returncode})
        save()
        assert q.returncode == 0, (label, text[-4000:])
        return text
    gate, lower, sv = [o / ('guarded' + suffix) for suffix in ['.gated.mlir', '.circt.mlir', '.sv']]
    run('gate', [a.nodalc, a.input, '--nodal-gate-default', '-o', gate])
    run('lower', [a.nodalc, gate, '--nodal-sequential-to-circt=preserve-constant-loops=true boundary-abi=named-fields', '-o', lower])
    assert lower.read_text().count('nodal.generated_template') == 1
    run('export', [a.toolchain / 'bin/firtool', '--format=mlir', '--verify-each', lower, '-o', sv])
    m['sv_sha256'] = digest(sv)
    assert sv.read_text().count('for (genvar ') == 1
    top = 'GuardedSelectedTarget'
    for count in range(4):
        key = f'count-{count}'
        imp, ll, state, obj, driver, exe = [o / (key + suffix) for suffix in
            ['.import.mlir', '.ll', '.state.json', '.o', '.cpp', '.simulate']]
        run(key + '-import', [a.toolchain / 'bin/circt-verilog', '--ir-hw', '--top', top,
                              '-G', f'count={count}', sv, '-o', imp])
        run(key + '-arc', [a.toolchain / 'bin/arcilator', imp, '--emit-llvm', f'--state-file={state}', '-o', ll])
        models = json.loads(state.read_text())
        assert len(models) == 1 and models[0]['name'] == top
        assert not models[0]['initialFnSym'] and not models[0]['finalFnSym']
        driver.write_text(harness(models[0], top, Profile('', lanes=3), 8, int(count > 1)))
        run(key + '-object', [a.toolchain / 'bin/llc', '-filetype=obj', '-relocation-model=pic', ll, '-o', obj])
        run(key + '-link', [a.cxx, '-std=c++17', driver, obj, f'-L{a.toolchain}/lib',
                            '-lCIRCTArcRuntime', f'-Wl,-rpath,{a.toolchain}/lib', '-o', exe])
        assert 'SELECTED_INSTANCE_TARGET_PASS' in run(key + '-simulate', [exe.resolve()])
        assert digest(sv) == m['sv_sha256'] and digest(a.input) == m['source_sha256']
        m['configurations'].append({'count': count, 'active': count > 1,
                                    'model_sha256': digest(state), 'driver_sha256': digest(driver)})
        save()
    m['status'] = 'passed'
    save()
    print('GUARDED_SELECTED_TARGET_PASS count=0,1,2,3', flush=True)


if __name__ == '__main__':
    main()
