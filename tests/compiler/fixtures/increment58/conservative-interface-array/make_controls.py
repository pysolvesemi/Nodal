"""Deterministic derivatives of actual bridge output; expectations are not results.
Usage: python generate_native_controls.py EXTENT3_MLIR FRESH_OUTPUT_DIRECTORY
Resource admission is the caller's responsibility.
No compiler, native probe, build, or git operation is performed.
"""
from pathlib import Path
import hashlib, json, re, sys
sys.dont_write_bytecode = True
if len(sys.argv) != 3:
    raise SystemExit("usage: generate_native_controls.py EXTENT3_MLIR FRESH_OUTPUT_DIRECTORY")
source = Path(sys.argv[1])
out = Path(sys.argv[2])
assert source.is_file(), "Actual bridge MLIR input is required"
assert not out.exists(), "Refuse to overwrite an existing control set"
text = source.read_text()

def replace(s, old, new, count=1):
    assert s.count(old) == count, (old, s.count(old), count)
    return s.replace(old, new)

def lines(token, count):
    result = [line for line in text.splitlines(keepends=True) if token in line]
    assert len(result) == count, (token, len(result), count)
    return result

def remove(s, values):
    for value in values:
        s = replace(s, value, "")
    return s

relations = lines('"nodal.connect_array"', 2)
members = lines('"nodal.member_access"', 4)
boundaries = lines('"nodal.interface_boundary"', 1)
assert all('!nodal.shaped<"3",' in line for line in members)
local = [x for x in members if 'instance = @"pins"' in x]
child = [x for x in members if 'instance = @"instance_0_interface_0"' in x]
assert len(local) == len(child) == 2
assert all('path = "pair.' + leaf + '"' in line
           for group in (local, child) for line, leaf in zip(group, ('p', 'n')))
ssa = lambda line: re.search(r"(%[A-Za-z0-9_]+) =", line).group(1)
cases = {}

def add(name, body, verify, diagnostic, transaction=None):
    assert body != text and body.endswith("\n")
    cases[name] = (body, dict(verify_expected_exit=verify,
                            transaction_expected_exit=transaction,
                            diagnostic_substring=diagnostic,
                            execution_status="not-run"))

missing = remove(text, [relations[1], child[1]])
assert missing.count(ssa(child[1])) == 0
add("missing-unaccessed-leaf", missing, 3,
    "every child Interface array leaf requires exact whole coverage once")
duplicate = "".join(replace(line, "ConservativeArrayTop.digital_connectivity_" + str(i),
                           "ConservativeArrayTop.negative_duplicate_" + str(i), 3)
                    for i, line in enumerate(relations))
add("duplicate-whole-binding", replace(text, relations[1], relations[1] + duplicate), 3,
    "child array region is multiply covered")
projected = remove(text, members + relations)
add("projected-without-members", projected, 3,
    "every child Interface array leaf requires exact whole coverage once")
zero = remove(projected, boundaries)
assert zero.count('"nodal.instance"') == 1
assert zero.count('"nodal.interface_instance"') == 2
assert '"nodal.member_access"' not in zero
add("zero-parent-projection", zero, 0,
    "every child Interface array requires an exact physical incidence plan", 0)
# Swap the child member paths, not SSA order, avoiding a use-before-definition.
cross = text
for line, old, new in zip(child, ('p', 'n'), ('n', 'p')):
    cross = replace(cross, line, replace(line, 'pair.' + old, 'pair.' + new, 2))
for line, old, new in zip(relations, ('p', 'n'), ('n', 'p')):
    changed = replace(line, 'right_path = "ConservativeArrayTop.instance_0_interface_0.pair.' + old,
                      'right_path = "ConservativeArrayTop.instance_0_interface_0.pair.' + new)
    cross = replace(cross, line, changed)
add("cross-member-wiring", cross, 3,
    "logical Interface array requires exact same-role local-child member and domain")
local_only = text
for rel, c, l in zip(relations, child, local):
    changed = replace(rel, ssa(c), ssa(l))
    changed = replace(changed, 'right_path = "ConservativeArrayTop.instance_0_interface_0.',
                      'right_path = "ConservativeArrayTop.pins.')
    local_only = replace(local_only, rel, changed)
add("local-local-relation", local_only, 3,
    "logical Interface array requires exact same-role local-child member and domain")
assert text.count('count = "3"') == 3
assert text.count('dimensions = ["3"]') == 3
assert text.count('!nodal.shaped<"3",') == 8
add("signed32-overflow", replace(text, '"3"', '"2147483648"', 14), 0,
    "conservative Interface array exceeds signed32 target layout", 0)
# Historical no-use declaration admission remains broader than target lowering.
parent = [x for x in zero.splitlines(keepends=True)
          if '"nodal.interface_instance"' in x and
          'semantic_path = "ConservativeArrayTop.pins"' in x]
assert len(parent) == 1
rank2 = replace(zero, parent[0],
                replace(parent[0], 'dimensions = ["3"]', 'dimensions = ["3", "2"]'))
add("unused-local-rank2", rank2, 0,
    "conservative Interface array requires direct fixed rank-one Electrical connect leaves", 0)
manifest = {"source": str(source),
            "source_sha256": hashlib.sha256(text.encode()).hexdigest(),
            "note": "Expected outcomes only. Transaction mode must prove rejection leaves input unchanged; verify separately.",
            "cases": {name: dict(expect, sha256=hashlib.sha256(body.encode()).hexdigest())
                      for name, (body, expect) in cases.items()}}
files = {name + ".mlir": body.encode() for name, (body, _) in cases.items()}
files["expectations.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
allocation = 4096 + sum((len(raw) + 4095) // 4096 * 4096 for raw in files.values())
assert allocation <= 400 * 1024 and sum(map(len, files.values())) <= 400 * 1024
assert not out.exists(), "Refuse to overwrite an existing control set"
out.mkdir()
for name, raw in files.items():
    with (out / name).open("xb") as f:
        f.write(raw)
print(json.dumps(dict(cases=len(cases), allocated_bound=allocation,
                     content_bytes=sum(map(len, files.values())), execution_status="not-run")))
