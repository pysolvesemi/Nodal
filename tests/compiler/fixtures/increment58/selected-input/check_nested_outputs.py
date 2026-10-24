"""Focused checker for four real 2x2 Bool fixtures, not a general MLIR parser.
Native validation is a separate prerequisite; this checks actual SSA wiring/order.
"""
from pathlib import Path
import argparse, re, json, sys, hashlib
sys.dont_write_bytecode = True
ROOT = 'Increment58SelectedInputNestedTwo'
SSA = r'%[\w.$]+'
def one(pattern, text):
    found = re.findall(pattern, text)
    assert len(found) == 1, (pattern, found)
    return found[0]
def check(source_path, target_path, mode):
    assert mode in ('nested-two', 'nested-reverse', 'nested-nonunit', 'nested-matching')
    source_path, target_path = Path(source_path), Path(target_path)
    source = source_path.read_text(); target = target_path.read_text()
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    # Root-only source SSA; child-local SSA names may repeat and must never overwrite it.
    source_root = source.split('  "nodal.module"()', 2)[1]
    constants = {a: int(b) for a, b in re.findall(r'('+SSA+r') = "nodal.const_literal"\(\).*? value = (-?\d+) : i64', source_root)}
    generations = re.findall(r'('+SSA+r') = "nodal.generate"\(([^)]*)\).*?region_id = "([^"]+)"', source_root)
    assert len(generations) == 2
    axes = [(2,6,2),(3,9,3)] if mode == 'nested-nonunit' else [(0,2,1),(0,2,1)]
    for (name, args, family), axis, expected_family in zip(generations, axes, [ROOT+'.generate_0',ROOT+'.generate_0.generate_1']):
        assert tuple(constants[x.strip()] for x in args.split(',')) == axis and family == expected_family
    outer = generations[0][0]
    child_name = 'generate_0_outer_generate_1_inner_child'
    instance_name = child_name + '_instance'
    assert one(r'"nodal.generated_export"\(('+SSA+r')\) <\{kind = "family", member = "inner", access = "read"', source_root) == generations[1][0]
    assert one(r'"nodal.generated_export"\(\) <\{kind = "instance", target = @"([^"]+)", member = "child", access = "read"', source_root) == instance_name
    instance_line = one(r'(?m)^        ("nodal.instance".*)$', source_root)
    assert 'generated_owner = "'+ROOT+'.generate_0.generate_1"' in instance_line
    assert 'module = @'+child_name+', ' in instance_line and 'sym_name = "'+instance_name+'"' in instance_line
    assert source_root.count('"nodal.generated_export"') == 2
    members = {}
    for name, family, index in re.findall(r'('+SSA+r') = "nodal.generated_member"\(('+SSA+r'), ('+SSA+r')\).*?kind = "family", member = "inner"', source_root):
        assert family == outer and name not in members
        members[name] = constants[index]
    assert len(members) == 8
    ports = {}
    for name, family, index, direction in re.findall(r'('+SSA+r') = "nodal.instance_port"\(('+SSA+r'), ('+SSA+r')\).*?direction = "(input|output)"', source_root):
        ports[name] = (direction, (members[family],constants[index]))
    assert len(ports) == 8
    port_lines = [line for line in source_root.splitlines() if ' = "nodal.instance_port"' in line]
    assert len(port_lines) == 8
    for line in port_lines:
        direction = one(r'direction = "(input|output)"', line)
        assert 'instance = @'+instance_name+', member = "child"' in line
        assert 'port = "'+direction+'"' in line
    named = dict(re.findall(r'('+SSA+r') = "nodal.port_value"\(\).*?port = @([\w]+)', source_root))
    drivers, outputs = [], []
    for lhs,rhs in re.findall(r'"nodal.digital_assign"\(('+SSA+r'), ('+SSA+r')\)', source_root):
        if lhs in ports:
            assert ports[lhs][0]=='input'; drivers.append((ports[lhs][1],named[rhs]))
        elif rhs in ports:
            assert ports[rhs][0]=='output'; outputs.append((ports[rhs][1],named[lhs]))
    tuples = [(0,0),(0,1),(1,0),(1,1)]
    assert len(drivers)==4 and dict(drivers)==dict(zip(tuples,['first','second','third','fourth']))
    assert len(outputs)==4 and dict(outputs)==dict(zip(tuples,['output00','output01','output10','output11']))
    if mode=='nested-reverse': assert [x[0] for x in drivers]==list(reversed(tuples))
    else: assert [x[0] for x in drivers]==tuples
    # Actual target modules, excluding long top-level metadata.
    blocks = re.split(r'(?m)^  hw.module @',target)[1:]
    modules = {re.match(r'([\w]+)', b).group(1):b for b in blocks}
    assert set(modules)=={ROOT,ROOT+'__family_0',ROOT+'__family_1','generate_0_outer_generate_1_inner_child'}
    root = modules[ROOT]; middle=modules[ROOT+'__family_0']; leaf=modules[ROOT+'__family_1']
    assert 'in %capture_1 : i2 ' in middle and 'in %capture_1 : i1 ' in leaf
    leaf_result = one(r'(?m)^    ('+SSA+r') = hw.instance "'+instance_name+r'" sym @'+instance_name+r' @'+child_name+r'\(input: %capture_1: i1\) -> \(output: i1\)', leaf)
    assert one(r'(?m)^    hw.output ('+SSA+r') : i1$', leaf) == leaf_result
    child = modules[child_name]
    assert one(r'(?m)^    hw.output ('+SSA+r') : i1$', child) == '%input'
    assert 'in %input : i1 ' in child and 'out output : i1 ' in child
    assert re.findall(r'out (output\d\d) : i1', root.split('\n',1)[0])==['output00','output01','output10','output11']
    concat = dict((result,[x.strip() for x in args.split(',')]) for result,args in re.findall(r'('+SSA+r') = comb.concat ([^:]+) :',root))
    assert len(concat)==3
    def flatten(value):
        return sum((flatten(x) for x in concat[value]),[]) if value in concat else [value]
    locals_by_block = lambda block: {name:int(value) for name,value in re.findall(r'('+SSA+r') = sv.localparam .*?value = (-?\d+) : i32',block)}
    loop_count=0; root_read=None
    for block, axis, lane, width, slice_width in [(root,axes[0],'0',4,2),(middle,axes[1],'1',2,1)]:
        params=locals_by_block(block)
        loops=re.findall(r'sv.generate.for (%__nodal_lane_\d) : i32 = (-?\d+) : i32 to (-?\d+) : i32 step (\d+) : i32',block)
        assert loops==[('%__nodal_lane_'+lane,*map(str,axis))];loop_count+=len(loops)
        slices=[]
        for result, expression, operands, types, outtype in re.findall(r'('+SSA+r') = sv.verbatim.expr "([^"]+)"\(([^)]+)\) : \(([^)]+)\) -> ([^ {]+)',block):
            args=[x.strip() for x in operands.split(',')]
            if len(args)<2 or args[1]!='%__nodal_lane_'+lane:continue
            assert params[args[2]]==slice_width
            if mode=='nested-nonunit':
                assert expression=='{{0}}[(({{1}} - {{3}}) / {{4}}) * {{2}} +: {{2}}]'
                assert len(args)==5 and params[args[3]]==axis[0] and params[args[4]]==axis[2]
            else:assert expression=='{{0}}[{{1}} * {{2}} +: {{2}}]' and len(args)==3
            slices.append((result,args[0],types,outtype))
        assert len(slices)==2
        data=[x for x in slices if x[2].startswith('i'+str(width)+',')]
        wiring=[x for x in slices if x[2].startswith('!hw.inout<i'+str(width)+'>,')]
        assert len(data)==len(wiring)==1
        data,wiring=data[0],wiring[0]
        assert data[3]=='i'+str(slice_width) and wiring[3]=='!hw.inout<i'+str(slice_width)+'>'
        callee = ROOT + ('__family_0' if lane == '0' else '__family_1')
        instance = one(r'(?m)^        (%lane.result_0 = hw.instance "lane" @[^\n]+)$', block)
        assert 'hw.instance "lane" @'+callee+'<' in instance
        assert 'capture_1: '+data[0]+': i'+str(slice_width)+') -> (result_0: i'+str(slice_width)+')' in instance
        assert 'sv.assign '+wiring[0]+', %lane.result_0 ' in block
        read=one(r'('+SSA+r') = sv.read_inout '+re.escape(wiring[1])+r' ',block)
        if lane=='0':
            assert flatten(data[1])==['%fourth','%third','%second','%first'];root_read=read
        else:
            assert data[1]=='%capture_1' and 'hw.output '+read+' : i2' in block
    assert loop_count==2 and target.count('sv.generate.for ')==2
    # Selected output proof metadata must agree with executable offsets and final output order.
    params=locals_by_block(root); selected=[]
    for line in root.splitlines():
        if 'nodal.generated_selection = ' not in line:continue
        result, operands=one(r'('+SSA+r') = sv.verbatim.expr "\{\{0\}\}\[\{\{1\}\} \+: \{\{2\}\}\]"\(([^)]+)\)',line)
        args=[x.strip() for x in operands.split(',')]
        assert args[0]==root_read and params[args[2]]==1
        proof=re.findall(r'coordinate = (-?\d+) : i32, family = "([^"]+)", ordinal = (\d+) : i32, stride_bits = (\d+) : i32',line)
        assert len(proof)==2
        ordinal=tuple(int(x[2]) for x in proof);assert ordinal in tuples
        for entry,axis,family,stride in zip(proof,axes,[ROOT+'.generate_0',ROOT+'.generate_0.generate_1'],[2,1]):
            assert entry[1]==family and int(entry[3])==stride and int(entry[0])==axis[0]+int(entry[2])*axis[2]
        assert params[args[1]]==ordinal[0]*2+ordinal[1]
        selected.append((ordinal,result))
    assert [x[0] for x in selected]==tuples
    actual_out=one(r'(?m)^    hw.output ([^:]+) : i1, i1, i1, i1$',root)
    assert [x.strip() for x in actual_out.split(',')]==[x[1] for x in selected]
    return dict(mode=mode,passed=True,source_sha256=sha(source_path),target_sha256=sha(target_path),
        tuples=[list(x) for x in tuples],rhs=['first','second','third','fourth'],axes=axes,
        transport=[4,2,1],helper_loops=2,output_offsets=[0,1,2,3],scope='Fixture-specific actual source/target wiring only; native execution and acceptance records remain separate')
if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['nested-two', 'nested-reverse', 'nested-nonunit', 'nested-matching'])
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--target', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(check(args.source, args.target, args.mode), sort_keys=True))
