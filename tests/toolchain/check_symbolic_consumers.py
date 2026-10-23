#!/usr/bin/env python3
"""Focused symbolic Comb checks on an explicitly selected managed prefix."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--predecessor', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=Path(__file__).with_name('fixtures') / 'symbolic-consumers')
    parser.add_argument('--iverilog', required=True)
    parser.add_argument('--vvp', required=True)
    parser.add_argument('--cxx', default='c++')
    args = parser.parse_args()
    t = args.toolchain.resolve()
    previous = args.predecessor.resolve()
    fixtures = args.fixtures.resolve()
    o = args.work.resolve()
    o.mkdir(parents=True, exist_ok=False)
    h = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    env = {**os.environ, 'LD_LIBRARY_PATH': str(t / 'lib')}
    cxx = shutil.which(args.cxx)
    if cxx is None:
        raise RuntimeError('C++ compiler unavailable')
    m = {'status': 'running', 'toolchain': json.loads((t / '.nodal-toolchain.json').read_text()),
         'environment': {'LD_LIBRARY_PATH': env['LD_LIBRARY_PATH']},
         'fixtures': {p.name: h(p) for p in fixtures.iterdir() if p.is_file()},
         'commands': [], 'widths': []}
    def save():
        (o / 'manifest.json').write_text(json.dumps(m, indent=2) + '\n')
    def run(name, argv, expected=0, environment=None):
        argv = list(map(str, argv))
        result = subprocess.run(argv, capture_output=True, text=True, env=environment or env, timeout=300)
        log = o / (name + '.log')
        log.write_text(result.stdout + result.stderr)
        m['commands'].append({'name': name, 'argv': argv, 'exit': result.returncode, 'expected': expected, 'log_sha256': h(log)})
        save()
        if result.returncode != expected:
            raise RuntimeError(f'{name}: unexpected exit {result.returncode}: {result.stderr[-2000:]}')
        return result.stdout + result.stderr
    for name in ['concat', 'concat-generic', 'nested-static', 'logical-static', 'fixed', 'fixed-constant', 'symbolic-result', 'single-symbolic', 'wrong-result', 'invalid-carrier']:
        expected = 1 if name in ['wrong-result', 'invalid-carrier'] else 0
        log = run(name, [t / 'bin/firtool', '--format=mlir', '--verify-each', fixtures / (name + '.mlir'), '-o', o / (name + '.sv')], expected)
        if expected:
            assert 'comb.concat' in log
    for name in ['fixed', 'fixed-constant']:
        dst = o / (name + '-predecessor.sv')
        run(name + '-predecessor', [previous / 'bin/firtool', '--format=mlir', '--verify-each', fixtures / (name + '.mlir'), '-o', dst], environment={**os.environ, 'LD_LIBRARY_PATH': str(previous / 'lib')})
        assert dst.read_bytes() == (o / (name + '.sv')).read_bytes()
    query = o / 'query'
    flags = subprocess.check_output([t / 'bin/llvm-config', '--cxxflags'], text=True).split()
    run('query-compile', [cxx, *flags, '-std=c++17', '-I' + str(t / 'include'), fixtures / 'Query.cpp', '-L' + str(t / 'lib'), '-Wl,-rpath,' + str(t / 'lib'), '-lCIRCTComb', '-lCIRCTHW', '-lMLIRIR', '-lMLIRSupport', '-lLLVMSupport', '-lMLIRInferIntRangeCommon', '-lMLIRInferIntRangeInterface', '-o', query])
    selected = {Path(relative).name for relative in m['toolchain']['overlay']['output_sha256'] if relative.startswith('lib/')}
    linked = {}
    for executable in [t / 'bin/firtool', t / 'bin/circt-opt', t / 'bin/circt-verilog', t / 'bin/arcilator', query]:
        listing = run(executable.name + '-linked-libraries', ['ldd', executable])
        for library, target in re.findall(r'(lib[^\s]+\.so) => ([^\s]+)', listing):
            if library in selected:
                path = Path(target)
                assert path.resolve() == (t / 'lib' / library).resolve(), (library, target)
                assert h(path) == m['toolchain']['overlay']['output_sha256']['lib/' + library]
                linked[library] = {'path': str(path), 'sha256': h(path)}
    assert set(linked) == selected, (selected, linked)
    m['linked_libraries'] = linked
    run('query', [query])
    sv = o / 'concat.sv'
    m['sv_sha256'] = h(sv)
    for width in [1,3,8]:
     name=str(width);tb=o/(name+'.sv');exe=o/(name+'.vvp')
     tb.write_text(f'''module tb;
    reg signed [{width-1}:0] data; wire [7:0] slot;
    Concat #(.W({width})) dut(.input_0(data),.slot(slot));
    integer v;
    initial begin
    for(v=0;v<{1<<width};v=v+1)begin data=v;#1;if(slot!==v[7:0])$fatal(1,"raw bits");end
    data='z;#1;if(slot[{width-1}:0]!=={{{width}{{1'bz}}}})$fatal(1,"Z lost");
    data='x;#1;if(slot[{width-1}:0]!=={{{width}{{1'bx}}}})$fatal(1,"X lost");
    $display("CONCAT_RAW_BITS_PASS");$finish;
    end
    endmodule
    ''')
     run(name+'-iverilog',[args.iverilog,'-g2012','-s','tb','-o',exe,sv,tb]);assert 'CONCAT_RAW_BITS_PASS' in run(name+'-vvp',[args.vvp,exe])
     imp,ll,state,obj,cpp,ae=[o/(name+s) for s in ['.mlir','.ll','.json','.o','.cpp','.run']]
     run(name+'-import',[t/'bin/circt-verilog','--ir-hw','--top','Concat','-G',f'W={width}',sv,'-o',imp]);run(name+'-arc',[t/'bin/arcilator',imp,'--emit-llvm',f'--state-file={state}','-o',ll]);model=json.loads(state.read_text())[0];pins={p['name']:p for p in model['states']};assert {n:(p['type'],p['numBits']) for n,p in pins.items()}=={'input_0':('input',width),'slot':('output',8)}
     cpp.write_text(f'#include <cstdint>\n#include <cstdio>\nextern "C" void Concat_eval(uint8_t*);\nint main(){{alignas(16) uint8_t s[{model["numStateBytes"]}]{{}};for(unsigned v=0;v<{1<<width};++v){{s[{pins["input_0"]["offset"]}]=v;Concat_eval(s);if(s[{pins["slot"]["offset"]}]!=v)return 1;}}std::puts("CONCAT_RAW_BITS_PASS");}}\n');run(name+'-object',[t/'bin/llc','-filetype=obj','-relocation-model=pic',ll,'-o',obj]);run(name+'-link',[cxx,'-std=c++17',cpp,obj,f'-L{t}/lib','-lCIRCTArcRuntime',f'-Wl,-rpath,{t}/lib','-o',ae]);assert 'CONCAT_RAW_BITS_PASS' in run(name+'-run',[ae]);assert h(sv)==m['sv_sha256'];m['widths'].append(width)
    assert m['fixtures'] == {p.name: h(p) for p in fixtures.iterdir() if p.is_file()}
    m['status'] = 'passed'
    save()
    print('SYMBOLIC_CONSUMER_PASS', m['widths'])


if __name__ == '__main__':
    main()
