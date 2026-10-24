#!/usr/bin/env python3
"""Inspect outputs: semantic provenance, not file/line locations."""
import argparse
import base64
import json
import re
from pathlib import Path
def need(ok, message):
    if not ok:
        raise ValueError(message)
def pin(ep, leaf):
    return 'nodal_i' + ep.encode().hex() + '_' + ('pair.' + leaf).encode().hex()
def read(folder, name):
    rec = json.loads((folder / (name + '.result.json')).read_text())
    need(rec['exit'] == 0 and rec['accepted_expected_exit'] is True
            and not rec['errors'], name + ': rec')
    raw = (folder / (name + '.stdout')).read_bytes()
    import hashlib
    need(hashlib.sha256(raw).hexdigest() == rec['stdout_sha256'], name + ': rec digest')
    need(not (folder / (name + '.stderr')).read_bytes(), name + ': diagnostics')
    return raw.decode()
def check_va(text, size, twins=False, levels=False):
    code = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
    need(not re.search(r'\b(assign|generate|genvar|always|wire|reg)\b', code), 'digital/lane')
    mods = re.findall(r'\bmodule (\w+)\(([^;]*)\);(.*?)\bendmodule', code, re.S)
    need(len({m[0] for m in mods}) == len(mods), 'duplicate module')
    sigs = {name: ports.split(', ') for name, ports, _ in mods}
    top = 'ConservativeArrayTop'
    graph = {top: 'middle', 'middle': 'leaf'} if levels else {top: 'child'}
    need(set(sigs) == set(graph) | set(graph.values()), 'definitions')
    actual = {}
    for name, _, body in mods:
        ports = {pin(ep, leaf) for ep in
                 (['pins', 'secondPins'] if twins and name == top else ['pins']) for leaf in 'pn'}
        need(set(sigs[name]) == ports and len(sigs[name]) == len(ports), 'exact ports')
        for kind in ['inout', 'electrical']:
            decl = re.findall(r'\b' + kind + r' \[0:(\d+)\] (\w+);', body)
            need(len(decl) == len(ports) and set(decl) ==
                    {(str(size - 1), p) for p in ports}, 'vector geometry')
        insts = re.findall(r'^\s*(\w+) (\w+)\(([^;]*)\);', body, re.M)
        expect = (2 if twins and name == top else 1) if name in graph else 0
        need(len(insts) == expect, 'instance count')
        for idx, (target, occ, mapping) in enumerate(insts):
            need(target == graph[name], 'callee')
            pairs = re.findall(r'\.(\w+)\((\w+)\)', mapping)
            ep = 'secondPins' if twins and idx == 1 else 'pins'
            wanted = {(pin('pins', leaf), pin(ep, leaf)) for leaf in 'pn'}
            need(len(pairs) == 2 and set(pairs) == wanted, 'leaf mapping')
            need(set(sigs[target]) == {p[0] for p in pairs}, 'formals')
            need(occ not in actual, 'occ identity')
            actual[occ] = set(pairs)
    binds = [json.loads(x) for x in re.findall(r'// nodal.array_binding (.*)', text)]
    need(len(binds) == 2 * len(actual), 'bind count')
    seen = set()
    for bind in binds:
        key = (bind['instance'], bind['formal'].rsplit('.', 1)[-1], bind['target_actual'])
        need(key not in seen and key[0] in actual and key[1:] in actual[key[0]], 'bind identity')
        need(bind['effective_shape'] == str(size) and not bind['projections']
                and not bind['stages'] and bind['instance_source'], 'array provenance')
        seen.add(key)
    rows = [base64.b64decode(x, validate=True).decode() for x in
            re.findall(r'// nodal.interface_correspondence mlir-v1 (\S+)', text)]
    need(len(rows) == len(set(rows)) == (10 if twins or levels else 6), 'correspondence')
    for row in rows:
        for part in ['selected_access = "connect"', 'selected_role = "connected"',
                         'leaf_role = "connect"', 'discipline = "electrical"']:
            need(part in row, 'contract')
        need(re.search(r'member = "pair\.[pn]"', row) and 'source = "ConservativeArrayTop.' in row,
                'member source')
    return code
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder', type=Path)
    ap.add_argument('--two-level', action='store_true')
    args = ap.parse_args()
    out = {}
    for name, size, twins in [('extent1', 1, False), ('extent3', 3, False),
                                   ('reverse', 3, False), ('two-instances', 3, True)]:
        text = read(args.folder, name + '-emit')
        out[name] = check_va(text, size, twins)
    need(out['extent3'] == out['reverse'], 'reverse parity')
    need(read(args.folder, 'extent3-emit') == read(args.folder, 'extent3-repeat'), 'repeat parity')
    ir = read(args.folder, 'extent3-adapt')
    counts = {'terminal_array': 4, 'instance_terminal_array': 2, 'connect_array': 2, 'instance': 1, 'module': 2}
    for op, count in counts.items():
        need(ir.count('"nodal.' + op + '"') == count, 'ir ' + op)
    need(not re.search(r'"nodal\.(interface\w*|member_access|shape_index|shape_view|clock_domain)"', ir),
            'residual op')
    if args.two_level:
        check_va(read(args.folder, 'two-level-emit'), 3, levels=True)
    print('PASS structure and semantic provenance')
if __name__ == '__main__':
    main()
