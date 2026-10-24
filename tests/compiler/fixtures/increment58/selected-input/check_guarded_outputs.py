"""Fixture-specific actual Scala-capture/target checker, adapted from accepted85.
Not a general MLIR parser or native verifier. Successful native receipts are a
prerequisite. Preparation alone, or hand-authored MLIR, is not acceptance.
"""
from pathlib import Path
import hashlib
import json
import re
import sys

sys.dont_write_bytecode = True
SSA = r'%[\w.$]+'


def one(pattern, text):
    found = re.findall(pattern, text)
    assert len(found) == 1, (pattern, found)
    return found[0]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def braced(text, start):
    """Read one brace-delimited region, ignoring quoted strings/escapes."""
    assert text[start] == '{'
    depth = 0
    quoted = escaped = False
    for end in range(start, len(text)):
        c = text[end]
        if quoted:
            if escaped:
                escaped = False
            elif c == '\\':
                escaped = True
            elif c == '"':
                quoted = False
            continue
        if c == '"':
            quoted = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[start + 1:end], end + 1
    raise AssertionError('unterminated fixture region')


def instance(block):
    line = one(r'(?m)^.*\bhw.instance .*$', block)
    callee, args, results = one(r'@([\w]+)(?:<.*>)?\(([^()]*)\) -> \(([^)]*)\)', line)
    pairs = re.findall(r'([\w]+): (' + SSA + r'): ([^,]+)', args)
    assert len(pairs) == (len(args.split(',')) if args.strip() else 0)
    return callee, {pin: value for pin, value, _ in pairs}, results, line


def check(mode, output_directory):
    # Explicit attempt directory: never mix corrected execution with old receipts.
    O = Path(output_directory).resolve(strict=True)
    assert mode in ('guarded-two', 'guarded-default-one', 'guarded-else')
    source_path = O / 'source' / (mode + '.mlir')
    target_path = O / (mode + '-native.stdout')
    receipt = json.loads((O / (mode + '.result.json')).read_text())
    assert receipt['action'] == mode and receipt['accepted'] and receipt['native_accepted']
    assert receipt['exit'] == receipt['native_exit'] == 0
    assert receipt['source_sha256'] == sha(source_path)
    assert receipt['target_sha256'] == sha(target_path)
    assert 'SELECTED_INPUT_FILE_CONVERSION_OK' in (O / (mode + '-native.stderr')).read_text()
    source, target = source_path.read_text(), target_path.read_text()
    root_name = 'Increment58GuardedSelectedInput' + ('Else' if mode == 'guarded-else' else 'Two')
    expected_arm = 1 if mode == 'guarded-else' else 0
    default = 1 if mode == 'guarded-default-one' else 0
    # Ignore snapshot metadata and never merge child-local SSA with root SSA.
    source_modules = re.split(r'(?m)^  "nodal.module"\(\)', source)[1:]
    assert len(source_modules) == 2
    root = next(b for b in source_modules if 'sym_name = "' + root_name + '"' in b.split('\n', 1)[0])
    assert root.count('"nodal.structural_if"(') == root.count('"nodal.generate"(') == 1
    parameter_line = one(r'(?m)^.*"nodal.parameter"\(\).*sym_name = "choice".*$', root)
    assert 'default_value = ' + str(default) + ' : i64' in parameter_line
    assert 'classification = "structural"' in parameter_line and 'variability = "symbolic"' in parameter_line
    constants = {v: int(n) for v, n in re.findall(
        r'(' + SSA + r') = "nodal.const_literal"\(\).*?value = (-?\d+) : i64', root)}
    refs = dict(re.findall(r'(' + SSA + r') = "nodal.const_parameter_ref"\(\).*?parameter = @([\w]+)', root))
    named = dict(re.findall(r'(' + SSA + r') = "nodal.port_value"\(\).*?port = @([\w]+)', root))
    predicate, lhs, rhs, comparison = one(
        r'(' + SSA + r') = "nodal.static_compare"\((' + SSA + r'), (' + SSA +
        r')\).*?comparison = "(eq|ne)"', root)
    # Authored source is choice == 0; do not silently accept an inverted predicate.
    assert comparison == 'eq' and refs.get(lhs) == 'choice' and constants.get(rhs) == 0
    guard_line = one(r'(?m)^.*"nodal.structural_if"\(.*$', root)
    guard_operands = one(r'"nodal.structural_if"\(([^)]*)\)', guard_line).split(', ')
    assert guard_operands[0] == predicate
    guard_id = one(r'region_id = "([^"]+)"', guard_line)
    segments = tuple(map(int, one(r'operandSegmentSizes = array<i32: (\d+), (\d+), (\d+)>', guard_line)))
    assert segments[0] == 1 and segments[1 + (1 - expected_arm)] == 0
    start = root.index('}> ({', root.index('"nodal.structural_if"')) + len('}> (')
    then, end = braced(root, start)
    other_start = root.index('{', end)
    otherwise, end = braced(root, other_start)
    arms = [then, otherwise]
    active, inactive = arms[expected_arm], arms[1 - expected_arm]
    assert '"nodal.generate"' not in inactive and '"nodal.digital_assign"' not in inactive
    assert '"nodal.instance"' not in inactive and '"nodal.digital_expr"' not in inactive
    assert inactive.count('"nodal.structural_yield"') == 1
    arguments = re.findall(r'(' + SSA + r'):', one(r'\A\s*\^bb\d+\(([^\n]*)\):', active))
    offset = 1 + (segments[1] if expected_arm else 0)
    actuals = guard_operands[offset:offset + segments[expected_arm + 1]]
    assert len(arguments) == len(actuals) and len(set(arguments)) == len(arguments)
    captures = dict(zip(arguments, actuals))
    family_value, bounds, family_id = one(
        r'(' + SSA + r') = "nodal.generate"\(([^)]*)\).*?region_id = "([^"]+)"', active)
    assert family_id.startswith(guard_id + ('.else.' if expected_arm else '.then.'))
    def constant(value):
        return constants[captures.get(value, value)]
    assert tuple(constant(x.strip()) for x in bounds.split(',')) == (0, 2, 1)
    selected = {}
    for value, family, ordinal in re.findall(
            r'(' + SSA + r') = "nodal.instance_port"\((' + SSA + r'), (' + SSA +
            r')\).*?direction = "input"', active):
        assert family == family_value and value not in selected
        selected[value] = constant(ordinal)
    assert sorted(selected.values()) == [0, 1]
    writes = re.findall(r'"nodal.digital_assign"\((' + SSA + r'), (' + SSA + r')\)', active)
    assert len(writes) == 2
    routes = []
    for destination, value in writes:
        assert destination in selected and value in captures
        routes.append((selected[destination], named[captures[value]]))
    assert routes == [(0, 'first'), (1, 'second')]
    source_instance = one(r'(?m)^.*"nodal.instance"\(\).*$', active)
    original_child = one(r'module = @([\w]+)', source_instance)
    assert 'generated_owner = "' + family_id + '"' in source_instance
    # Exactly the original child plus family/arm helpers; no flattened family.
    blocks = re.split(r'(?m)^  hw.module @', target)[1:]
    modules = {re.match(r'([\w]+)', b).group(1): b for b in blocks}
    assert len(modules) == len(blocks) == 4 and root_name in modules and original_child in modules
    family_names = [n for n, b in modules.items() if 'nodal.generated_template = ' in b.split('\n', 1)[0]]
    arm_names = [n for n, b in modules.items() if 'nodal.generated_guard_scope = ' in b.split('\n', 1)[0]]
    assert len(family_names) == len(arm_names) == 1
    family_name, arm_name = family_names[0], arm_names[0]
    top, helper, family, child = modules[root_name], modules[arm_name], modules[family_name], modules[original_child]
    for b in (top, helper, family):
        assert 'choice: i32 = ' + str(default) in b.split('\n', 1)[0]
    assert 'nodal.generated_template = "' + family_id + '"' in family.split('\n', 1)[0]
    assert 'nodal.generated_guard_scope = "' + guard_id + '"' in helper.split('\n', 1)[0]
    assert 'nodal.generated_guard_arm = ' + str(expected_arm) + ' : i32' in helper.split('\n', 1)[0]
    assert sum(b.count('hw.instance ') for b in modules.values()) == 3
    assert sum(b.count('sv.generate.for ') for b in modules.values()) == 1
    assert sum(b.count('sv.generate.case ') for b in modules.values()) == 1
    assert not re.search(r'\bnodal\.[\w]+["(]', '\n'.join(blocks))
    assert 'sv.generate.case #hw.param.decl.ref<"choice"> : i32' in top
    labels = list(re.finditer(r'case \((0 : i32|unit), "(matched|other)"\) \{', top))
    assert [(m[1], m[2]) for m in labels] == [('0 : i32', 'matched'), ('unit', 'other')]
    target_arms = [braced(top, m.end() - 1)[0] for m in labels]
    assert not re.search(r'hw.instance|sv.generate.for', target_arms[1 - expected_arm])
    callee, scope_args, results, scope_line = instance(top)
    assert callee == arm_name and not results.strip() and scope_line.strip() in target_arms[expected_arm]
    assert 'choice: i32 = #hw.param.decl.ref<"choice">' in scope_line
    # concat is MSB-first: ordinal0 must remain first, ordinal1 second.
    bus, high, low = one(r'(' + SSA + r') = comb.concat (' + SSA + r'), (' + SSA + r') : i1, i1', helper)
    assert scope_args[low.removeprefix('%')] == '%first'
    assert scope_args[high.removeprefix('%')] == '%second' and high != low
    for value in (high, low):
        assert 'in ' + value + ' : i1 ' in helper.split('\n', 1)[0]
    induction = one(r'sv.generate.for (' + SSA + r') : i32 = 0 : i32 to 2 : i32 step 1 : i32', helper)
    loop_at = helper.index('sv.generate.for ')
    loop_body, _ = braced(helper, helper.index('{', loop_at))
    params = {v: int(n) for v, n in re.findall(r'(' + SSA + r') = sv.localparam .*?value = (-?\d+) : i32', helper)}
    sliced, operands = one(r'(' + SSA + r') = sv.verbatim.expr "\{\{0\}\}\[\{\{1\}\} \* \{\{2\}\} \+: \{\{2\}\}\]"\(([^)]+)\) : \(i2, i32, i32\) -> i1', loop_body)
    bus_arg, index_arg, width_arg = operands.split(', ')
    assert bus_arg == bus and index_arg == induction and params[width_arg] == 1
    callee, lane_args, results, lane_line = instance(helper)
    assert callee == family_name and not results.strip() and lane_line.strip() in loop_body
    assert 'choice: i32 = #hw.param.decl.ref<"choice">' in lane_line
    assert '__nodal_index: i32 = #hw.param.verbatim<"' + induction[1:] + '">' in lane_line
    data_pins = [name for name, value in lane_args.items() if value == sliced]
    assert len(data_pins) == 1
    data_pin = data_pins[0]
    assert 'in %' + data_pin + ' : i1 ' in family.split('\n', 1)[0]
    callee, child_args, results, _ = instance(family)
    assert callee == original_child and child_args.get('input') == '%' + data_pin
    assert re.search(r'\bhw.output %input : i1', child)
    assert not re.search(r'\bout \w+ :', helper.split('\n', 1)[0])
    assert not re.search(r'\bout \w+ :', family.split('\n', 1)[0])
    return dict(mode=mode, passed=True, source_sha256=sha(source_path), target_sha256=sha(target_path),
                parameter='choice', default=default, comparison='eq', pattern=0,
                authored_arm=expected_arm, target_arm=expected_arm,
                family=family_id, original_child=original_child,
                rhs=['first', 'second'], ordinals=[0, 1], transport=[2, 1],
                scope='Actual authored Scala capture and native target SSA/control wiring; not exporter acceptance')


if __name__ == '__main__':
    assert len(sys.argv) == 3, "usage: check_guarded_outputs.py MODE OUTPUT_DIRECTORY"
    print(json.dumps(check(sys.argv[1], sys.argv[2]), sort_keys=True))
