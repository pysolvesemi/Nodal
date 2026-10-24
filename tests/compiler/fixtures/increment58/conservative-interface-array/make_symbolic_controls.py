#!/usr/bin/env python3
"""Bounded native coverage derivatives of actual late-bound symbolic bridge output.

Expected diagnostics are not execution results. Caller owns resource admission;
this script performs no compilation, native execution, cleanup, or Git writes.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
sys.dont_write_bytecode = True


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_symbolic_controls.py ACTUAL_LATE_MLIR FRESH_DIRECTORY")
    source, output = map(Path, sys.argv[1:])
    assert source.is_file() and not output.exists()
    text = source.read_text()
    assert 'SymbolicConservativeArrayTop' in text
    assert '!nodal.shaped<"p",' in text
    assert any('"nodal.interface_instance"' in line and 'dimensions = ["n"]' in line
               for line in text.splitlines())
    assert '65536' in text and 'nodal.generate' not in text
    def lines(token, count):
        values = [line for line in text.splitlines(keepends=True) if token in line]
        assert len(values) == count, (token, len(values), count)
        return values
    def replace(body, old, new):
        assert body.count(old) == 1
        return body.replace(old, new)
    def remove(body, values):
        for value in values:
            body = replace(body, value, "")
        return body
    relations = lines('"nodal.connect_array"', 2)
    members = lines('"nodal.member_access"', 4)
    boundaries = lines('"nodal.interface_boundary"', 1)
    child = [line for line in members if 'instance = @"pins"' not in line]
    assert len(child) == 2 and all('!nodal.shaped<"p",' in line for line in members)
    assert 'path = "pair.p"' in child[0] and 'path = "pair.n"' in child[1]
    cases = {}
    def add(name, body, verify, diagnostic, transaction=None):
        assert body != text and body.endswith("\n")
        cases[name] = (body, dict(verify_expected_exit=verify,
            transaction_expected_exit=transaction, diagnostic_substring=diagnostic,
            execution_status="not-run"))
    missing = remove(text, [relations[1], child[1]])
    symbol = re.search(r"(%[A-Za-z0-9_]+) =", child[1]).group(1)
    assert symbol not in missing
    add("missing-unaccessed-leaf", missing, 3,
        "every child Interface array leaf requires exact whole coverage once")
    duplicate = ""
    for line in relations:
        identity = re.search(r'connection_id = "([^"]+)"', line).group(1)
        assert line.count(identity) == 3
        duplicate += line.replace(identity, identity + "_duplicate")
    add("duplicate-whole-binding", replace(text, relations[-1], relations[-1] + duplicate), 3,
        "child array region is multiply covered")
    projected = remove(text, members + relations)
    add("projected-without-members", projected, 3,
        "every child Interface array leaf requires exact whole coverage once")
    zero = remove(projected, boundaries)
    assert zero.count('"nodal.instance"') == 1 and zero.count('"nodal.interface_instance"') == 2
    add("zero-parent-projection", zero, 0,
        "every child Interface array requires an exact physical incidence plan", 0)
    # These mutations are anchored to the actual bridge syntax, not guessed SSA names.
    overrides = lines('"nodal.parameter_override"', 1)
    operand = re.search(r'"nodal.parameter_override"\((%[A-Za-z0-9_]+)\)', overrides[0]).group(1)
    references = [line for line in text.splitlines(keepends=True)
                  if line.lstrip().startswith(operand + ' = ')]
    assert len(references) == 1 and '"nodal.const_parameter_ref"' in references[0]
    reference = references[0]
    assert 'parameter = @p}' in reference
    assert 'semantic_path = "SymbolicConservativeArrayTop.p"' in reference
    other = [line for line in text.splitlines()
             if '"nodal.parameter"' in line and 'sym_name = "other"' in line]
    assert len(other) == 1 and 'default_value = 65536 : i64' in other[0]
    assert 'integer_range_lower = "1"' in other[0] and 'integer_range_upper = "131072"' in other[0]
    changed = reference.replace('parameter = @p}', 'parameter = @other}')
    changed = changed.replace('semantic_path = "SymbolicConservativeArrayTop.p"',
                              'semantic_path = "SymbolicConservativeArrayTop.other"')
    distinct = replace(text, reference, changed)
    distinct = replace(distinct, overrides[0], overrides[0].replace(
        'source_value = "SymbolicConservativeArrayTop.p"',
        'source_value = "SymbolicConservativeArrayTop.other"'))
    add("equal-default-distinct-identity", distinct, 3,
        "actual extent differs from the exact substituted child extent")
    constraints = [line for line in text.splitlines(keepends=True)
                   if '"nodal.parameter_constraint"' in line and 'parameter = @p,' in line]
    assert len(constraints) == 1
    lower = re.search(r'"nodal.parameter_constraint"\((%[A-Za-z0-9_]+),', constraints[0]).group(1)
    literals = [line for line in text.splitlines(keepends=True)
                if line.lstrip().startswith(lower + ' = ')]
    assert len(literals) == 1 and '"nodal.const_literal"' in literals[0]
    assert 'spelling = "1", value = 1 : i64' in literals[0]
    nonpositive = replace(text, literals[0], literals[0].replace(
        'spelling = "1", value = 1 : i64', 'spelling = "0", value = 0 : i64'))
    # Deliberately retain the positive-looking bridge metadata: canonical executable
    # constraint operands, not this producer annotation or the default, own the proof.
    add("nonpositive-authoritative-constraint", nonpositive, 3,
        "Interface array dimensions require owned finite structural proofs")
    manifest = dict(source=str(source), source_sha256=hashlib.sha256(text.encode()).hexdigest(),
                    note="Expectations only. Transaction must reject without modifying input or emitting output.",
                    cases={name: dict(expected, sha256=hashlib.sha256(body.encode()).hexdigest())
                           for name, (body, expected) in cases.items()})
    files = {name + ".mlir": body.encode() for name, (body, _) in cases.items()}
    files["expectations.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    allocation = 4096 + sum((len(raw) + 4095) // 4096 * 4096 for raw in files.values())
    assert allocation <= 512 * 1024
    output.mkdir()
    for name, raw in files.items():
        with (output / name).open("xb") as stream:
            stream.write(raw)
    print(json.dumps(dict(cases=len(cases), allocated_bound=allocation, execution_status="not-run")))


if __name__ == "__main__":
    main()
