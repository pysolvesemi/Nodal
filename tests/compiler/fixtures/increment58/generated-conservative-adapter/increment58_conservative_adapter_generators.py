"""Deterministic, named semantic mutations of preserved MLIR templates.

The selected-child recipe varies ordinal domains, instances, body incidence,
roles and selection identity. Extras add nested paths and lexical prefixes.
Child-summary isolates actual internal child coupling. Multi-leaf and nonunit
recipes exercise shared coupling and authored induction/ordinal distinction.
Every emitted accepted case is additionally pinned by its full input digest.
"""
from pathlib import Path

def selected_child_fixtures(root):
    p = root / 'selected-child'
    s = (p / 'selected-independent.mlir').read_text()

    def write(n, t):
        (p / (n + '.mlir')).write_text(t)

    def domain(t, lo, step, count):
        for name, old, new in [('0', 0, lo), ('1', 3, lo + step * count), ('2', 1, step)]:
            line = next((l for l in t.splitlines(True) if f'%shape_index_static_{name} =' in l))
            t = replace_exact(t, line, replace_exact(replace_exact(line, f'spelling = "{old}"', f'spelling = "{new}"', 1), f'value = {old} : i64', f'value = {new} : i64', 1), 1)
        t = t.replace('maximum_trip_count = 3 : i64', f'maximum_trip_count = {count} : i64')
        line = next((l for l in t.splitlines(True) if 'sym_name = "selected_child"' in l))
        rank = '    %selected_rank = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64\n'
        return replace_exact(t, line, rank + replace_exact(line, '%shape_index_static_0)', '%selected_rank)', 1), 1)
    assert s.count('sym_name = "selected_child"') == 1
    assert s.count('"nodal.generated_export"') == 1
    for name, lo, step, count in [('count1', 0, 1, 1), ('count3', 0, 1, 3), ('count1000', 0, 1, 1000), ('lower2-step2', 2, 2, 3), ('lower1-step3', 1, 3, 3), ('empty', 0, 1, 0)]:
        write(name, domain(s, lo, step, count))
    old = (p.parent / 'child-summary/child-short.mlir').read_text()
    ops = ''.join((l for l in old.splitlines(True) if '%child_p =' in l or '%child_n =' in l or '"nodal.connect"(%child_p' in l))
    pos = s.rfind('  }) : () -> ()')
    write('internal-short', s[:pos] + ops + s[pos:])
    lines = s.splitlines(True)
    body = ''.join((l for l in lines if '"nodal.instance"' in l or '"nodal.instance_domain_binding"' in l or '"nodal.interface_boundary"()' in l or ('"nodal.member_access"' in l and 'instance_0_interface_0' in l)))
    body2 = body.replace('generate_0_lanes_child_instance', 'second_child_instance').replace('instance_0_interface_0', 'second_interface').replace('%conservative_member_2', '%second_p').replace('%conservative_member_3', '%second_n').replace('.conservative_member_3', '.second_p').replace('.conservative_member_4', '.second_n')
    export = next((l for l in lines if '"nodal.generated_export"' in l))
    export2 = export.replace('generate_0_lanes_child_instance', 'second_child_instance').replace('member = "child"', 'member = "second"').replace('.export.child', '.export.second')
    boundary = next((l for l in lines if 'sym_name = "selected_child"' in l))
    second = boundary.replace('generate_0_lanes_child_instance', 'second_child_instance').replace('member = "child"', 'member = "second"').replace('selected_child', 'selected_second')
    write('two-instances', s.replace(export, export + body2 + export2).replace(boundary, boundary + second))
    for name, rank in [('repeated-view', 0), ('distinct-view', 1)]:
        literal = f'    %other_rank = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{rank}", value = {rank} : i64}}> : () -> i64\n'
        other = boundary.replace('%shape_index_static_0)', '%other_rank)').replace('selected_child', 'selected_other')
        write(name, s.replace(boundary, boundary + literal + other))
    shared = (p.parent / 'source-witness/selected-child-ingress.mlir').read_text()
    bodyjoins = ''.join((l for l in shared.splitlines(True) if '"nodal.connect"' in l))
    t = (p / 'distinct-view.mlir').read_text()
    write('shared-parent', t.replace(export, bodyjoins + export))
    write('wrong-export', s.replace('member = "child", instance = @"generate_0_lanes_child_instance"', 'member = "missing", instance = @"generate_0_lanes_child_instance"'))
    write('wrong-instance', s.replace(boundary, boundary.replace('@"generate_0_lanes_child_instance"', '@"other_instance"')))
    write('wrong-endpoint', s.replace(boundary, boundary.replace('endpoint = "pins"', 'endpoint = "missing"')))
    write('out-of-range', s.replace(boundary, boundary.replace('%shape_index_static_0)', '%shape_index_static_1)')))
    write('missing-body', ''.join((l for l in lines if not ('"nodal.interface_boundary"()' in l or ('"nodal.member_access"' in l and 'instance_0_interface_0' in l)))))
    collision = '      %collision = "nodal.node"() <{name = "nodal_i696e7374616e63655f305f696e746572666163655f30_70_child_port", source_path = "GeneratedConservativeHierarchy.collision", generated_owner = "GeneratedConservativeHierarchy.generate_0", generated_induction = "GeneratedConservativeHierarchy.map_source_l31_e7e85968", metadata = {allow_floating = true}}> : () -> !nodal.terminal<"electrical">\n'
    write('collision', s.replace(export, collision + export))

def selected_child_extras(root):
    p = root / 'selected-child'
    s = (p / 'selected-independent.mlir').read_text()
    a = s.index('    %generated_family_4 =')
    end = '    }) : (i64, i64, i64) -> !nodal.generated_family'
    z = s.index(end, a) + len(end)
    outer = '    %outer = "nodal.generate"(%shape_index_static_0, %shape_index_static_2, %shape_index_static_2) <{induction = "j", induction_path = "GeneratedConservativeHierarchy.outer.index", region_id = "GeneratedConservativeHierarchy.outer", metadata = {maximum_trip_count = 1 : i64, semantic_path = "GeneratedConservativeHierarchy.outer"}}> ({ ^bb0(%outer_i: i64):\n'
    export = '\n      "nodal.generated_export"(%generated_family_4) <{member = "inner", kind = "family", access = "read", metadata = {}}> : (!nodal.generated_family) -> ()\n    }) : (i64, i64, i64) -> !nodal.generated_family\n'
    view = '    %inner_view = "nodal.generated_member"(%outer, %shape_index_static_0) <{member = "inner", kind = "family", access = "read", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.generated_family\n'
    t = s[:a] + outer + s[a:z] + export + view + s[z:]
    t = t.replace('"nodal.interface_boundary"(%generated_family_4,', '"nodal.interface_boundary"(%inner_view,')
    (p / 'nested-prefix.mlir').write_text(t)
    (p / 'wrong-fullprefix.mlir').write_text(t.replace('"nodal.generated_member"(%outer, %shape_index_static_0)', '"nodal.generated_member"(%outer, %shape_index_static_2)'))
    n = (p.parent / 'child-summary/child-independent-nested.mlir').read_text()
    a = n.index('    "nodal.interface"')
    z = n.index('    "nodal.interface_instance"', a)
    defs = n[a:z]
    a = s.index('    "nodal.interface"')
    z = s.index('    "nodal.interface_instance"', a)
    t = s[:a] + defs + s[z:]
    abi = next((l for l in n.splitlines(True) if '"nodal.interface_abi"' in l))
    oldabi = next((l for l in t.splitlines(True) if '"nodal.interface_abi"' in l))
    t = t.replace(oldabi, abi).replace('definition = @"GeneratedConservativePins"', 'definition = @"OuterPins"')
    t = ''.join((l.replace('path = "p"', 'path = "pair.p"').replace('path = "n"', 'path = "pair.n"').replace('role = "connected"', 'role = "outer"') if '"nodal.member_access"' in l or '"nodal.interface_boundary"' in l or '"nodal.interface_instance"' in l else l for l in t.splitlines(True)))
    (p / 'nested-members.mlir').write_text(t)

def child_summary_fixtures(root):
    p = root / 'child-summary'
    s = (root / 'scala-predecessor/first/outputs/child.mlir').read_text()
    line = next((l for l in s.splitlines(True) if '"nodal.connect"(%conservative_member_3,' in l))
    node = '      %separate = "nodal.node"() <{name = "separate", source_path = "GeneratedConservativeHierarchy.separate", generated_owner = "GeneratedConservativeHierarchy.generate_0", generated_induction = "GeneratedConservativeHierarchy.map_source_l31_e7e85968", metadata = {allow_floating = true}}> : () -> !nodal.terminal<"electrical">\n'
    s = s.replace(line, node + line.replace('%conservative_member_2)', '%separate)'), 1)
    child = '    %child_p = "nodal.member_access"() <{instance = @pins, path = "p", metadata = {interface_abi_version = 1 : i64, role = "connected", semantic_path = "GeneratedConservativeHierarchy.generate_0_lanes_child.p"}}> : () -> !nodal.terminal<"electrical">\n    %child_n = "nodal.member_access"() <{instance = @pins, path = "n", metadata = {interface_abi_version = 1 : i64, role = "connected", semantic_path = "GeneratedConservativeHierarchy.generate_0_lanes_child.n"}}> : () -> !nodal.terminal<"electrical">\n    "nodal.connect"(%child_p, %child_n) <{connection_id = "child_short", source_path = "GeneratedConservativeHierarchy.generate_0_lanes_child.child_short", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()\n'
    marker = '  }) : () -> () loc("Source.scala":22:1)'
    assert marker in s
    (p / 'child-short.mlir').write_text(replace_exact(s, marker, child + marker, 1))
    (p / 'child-independent.mlir').write_text(s)

def nonunit_target_fixtures(root):
    import re
    p = root / 'nonunit-target'
    template = (p.parent / 'multi-leaf/count3.mlir').read_text()

    def fixture(name, lower, upper, step, count, rank=0):
        s = template
        for symbol, value in [('lower', lower), ('upper', upper), ('step', step)]:
            s = re.sub('%' + symbol + ' = "nodal.const_literal"\\(\\) <\\{value = -?\\d+ : i64, spelling = "-?\\d+"', f'%{symbol} = "nodal.const_literal"() <{{value = {value} : i64, spelling = "{value}"', s)
        s = s.replace('maximum_trip_count = 3 : i64', f'maximum_trip_count = {count} : i64').replace('"nodal.interface_boundary"(%family, %lower)', f'%selected_rank = "nodal.const_literal"() <{{value = {rank} : i64, spelling = "{rank}", metadata = {{}}}}> : () -> i64\n"nodal.interface_boundary"(%family, %selected_rank)')
        (p / (name + '.mlir')).write_text(s)
        return s
    for count in [1, 3, 1000]:
        fixture('lower2-step2-count' + str(count), 2, 2 + count * 2, 2, count)
    fixture('lower1-step3', 1, 8, 3, 3)
    fixture('lower2-step2-last', 2, 8, 2, 3, 2)
    fixture('domain-overflow', 2147483647, 2147483648, 1, 1)
    fixture('update-overflow', 2147483646, 2147483647, 2, 1)
    fixture('index-intermediate-overflow', -2147483648, 1, 1073741824, 3)
    s = (p / 'lower2-step2-count3.mlir').read_text()
    anchor = '^bb0(%i: i64):'
    assert anchor in s
    s = s.replace(anchor, anchor + '\n%collision = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {semantic_path = "Increment58ConservativeLocal.gen.normalized_index.user"}}> : () -> i64')
    (p / 'prefix-collision.mlir').write_text(s)
    s = fixture('outside-ordinal', 2, 8, 2, 3, 3)
    s = (p.parent / 'multi-leaf/shared-coupling.mlir').read_text()
    for symbol, value in [('lower', 2), ('upper', 8), ('step', 2)]:
        s = re.sub('%' + symbol + ' = "nodal.const_literal"\\(\\) <\\{value = -?\\d+ : i64, spelling = "-?\\d+"', f'%{symbol} = "nodal.const_literal"() <{{value = {value} : i64, spelling = "{value}"', s)
    s = s.replace('"nodal.interface_boundary"(%family, %lower)', '%selected_rank = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64\n"nodal.interface_boundary"(%family, %selected_rank)')
    (p / 'shared-coupling.mlir').write_text(s)

def multi_leaf_fixtures(root):
    p = root / 'multi-leaf'
    s = (p / 'multi-leaf.mlir').read_text()
    (p / 'renamed.mlir').write_text(s.replace('"p"', '"positive"').replace('"n"', '"negative"'))
    (p / 'wrong-role.mlir').write_text(s.replace('role = "connected", semantic_path = "Increment58ConservativeLocal.selected_n"', 'role = "contributed", semantic_path = "Increment58ConservativeLocal.selected_n"'))
    (p / 'ordered-inventory.mlir').write_text(s.replace('actual_types = [{path = "p", type = !nodal.terminal<"electrical">}, {path = "n", type = !nodal.terminal<"electrical">}]', 'actual_types = [{path = "n", type = !nodal.terminal<"electrical">}, {path = "p", type = !nodal.terminal<"electrical">}]'))
    (p / 'missing-inventory.mlir').write_text(s.replace(', {path = "n", type = !nodal.terminal<"electrical">}]', ']'))
    (p / 'wrong-discipline.mlir').write_text(s.replace('actual_types = [{path = "p", type = !nodal.terminal<"electrical">}, {path = "n", type = !nodal.terminal<"electrical">}]', 'actual_types = [{path = "p", type = !nodal.terminal<"electrical">}, {path = "n", type = !nodal.terminal<"wrong">}]'))
    (p / 'wrong-nested-path.mlir').write_text(s.replace('instance = @selected, path = "n"', 'instance = @selected, path = "nested.n"'))
    join = next((l for l in s.splitlines() if 'connection_id = "join"' in l))
    nodes = []
    for name in ['a', 'b']:
        path = 'Increment58ConservativeLocal.gen.local_' + name
        nodes.append(f'%local_{name} = "nodal.node"() <{{name = "local_{name}", source_path = "{path}", generated_owner = "Increment58ConservativeLocal.gen", generated_induction = "Increment58ConservativeLocal.gen.index", metadata = {{allow_floating = true, semantic_path = "{path}"}}}}> : () -> !nodal.terminal<"electrical">')
    edges = [join.replace('(%terminal_0, %terminal_1)', '(%terminal_0, %local_a)'), join.replace('(%terminal_0, %terminal_1)', '(%terminal_1, %local_b)').replace('"join"', '"join_b"').replace('gen.join"', 'gen.join_b"')]
    (p / 'independent-leaves.mlir').write_text(s.replace(join, '\n'.join(nodes + edges)))
    lines = []
    for l in s.splitlines():
        if '"nodal.interface_role"' in l:
            access = 'in' if 'sym_name = "connected"' in l else 'out'
            role = 'connected' if access == 'in' else 'contributed'
            l = l.replace('}], interface_abi_version', f'}}, {{access = "{access}", leaf_role = "{role}", nested_interfaces = [], nested_roles = [], path = "d"}}], interface_abi_version')
        if '"nodal.interface_abi"' in l:
            l = l.replace('}], metadata', '}, {access = "in", nested_interfaces = [], nested_roles = [], path = "d", role = "connected", type = !nodal.bits<1>}], metadata')
        if '"nodal.interface_boundary"' in l:
            l = l.replace('}], dimensions', '}, {path = "d", type = !nodal.bits<1>}], dimensions')
        lines.append(l)
        if '"nodal.interface_member"' in l and 'sym_name = "n"' in l:
            lines.append('"nodal.interface_member"() <{metadata = {interface_abi_version = 1 : i64}, protocol = "plain", roles = ["connected", "contributed"], sym_name = "d", type = !nodal.bits<1>}> : () -> ()')
    a = '\n'.join(lines) + '\n'
    (p / 'mixed-selected.mlir').write_text(a)
    (p / 'mixed-unselected.mlir').write_text('\n'.join((l for l in lines if not ('"nodal.interface_boundary"' in l or '%selected_p =' in l or '%selected_n =' in l or ('connection_id = "selected_join"' in l)))) + '\n')
    shared = '%shared = "nodal.node"() <{name = "shared", source_path = "Increment58ConservativeLocal.shared", metadata = {allow_floating = true, semantic_path = "Increment58ConservativeLocal.shared"}}> : () -> !nodal.terminal<"electrical">'
    a = s.replace('%family =', shared + '\n%family =', 1).replace(join, join.replace('(%terminal_0, %terminal_1)', '(%terminal_0, %shared)') + '\n' + join.replace('(%terminal_0, %terminal_1)', '(%terminal_1, %shared)').replace('"join"', '"join_n"').replace('gen.join"', 'gen.join_n"'))
    boundary = next((l for l in a.splitlines() if '"nodal.interface_boundary"' in l))
    a = a.replace(boundary, boundary + '\n' + boundary.replace('(%family, %lower)', '(%family, %step)').replace('sym_name = "selected"', 'sym_name = "selected1"').replace('Local.selected"', 'Local.selected1"'))
    (p / 'shared-coupling.mlir').write_text(a)
GENERATORS = [child_summary_fixtures, selected_child_fixtures, selected_child_extras, multi_leaf_fixtures, nonunit_target_fixtures]

def replace_exact(text, old, new, count):
    actual = text.count(old)
    if actual != count:
        raise ValueError(f'Expected {count} occurrences of {old!r}, found {actual}')
    return text.replace(old, new)
