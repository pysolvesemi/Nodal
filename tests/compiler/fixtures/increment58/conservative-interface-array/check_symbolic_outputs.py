#!/usr/bin/env python3
"""Check actual named-module symbolic Verilog-A, reusing literal receipt/ABI checks."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("fixed_array_checks", Path(__file__).with_name("check_outputs.py"))
fixed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixed)
need, pin, read = fixed.need, fixed.pin, fixed.read


def check(text, mode):
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    need(not re.search(r"\b(assign|generate|genvar|always|wire|reg)\b", code), "digital/lane expansion")
    modules = re.findall(r"\bmodule (\w+)\(([^;]*)\);(.*?)\bendmodule", code, re.S)
    top = "SymbolicConservativeArrayTop"
    need(len(modules) == 2 and {x[0] for x in modules} == {top, "child"}, "shared named definitions")
    twins = mode == "two-instances"
    default = 1 if mode == "default-one" else 65536
    occurrences = {}
    for name, signature, body in modules:
        endpoints = ["pins", "secondPins"] if twins and name == top else ["pins"]
        ports = {pin(endpoint, leaf) for endpoint in endpoints for leaf in "pn"}
        need(set(signature.split(", ")) == ports and len(signature.split(", ")) == len(ports), "exact nested ports")
        extent = "p" if name == top else "n"
        for kind in ("inout", "electrical"):
            declarations = re.findall(r"\b" + kind + r" \[0:([^\]]+)\] (\w+);", body)
            need(len(declarations) == len(ports), "one vector per nested leaf")
            need({(re.sub(r"\s+", "", size), port) for size, port in declarations} ==
                 {("(" + extent + "-1)", port) for port in ports}, "symbolic declaration geometry")
        need(re.search(r"parameter integer " + extent + " = " + str(default) +
                       r" from \[1:131072\];", body), "preserved symbolic parameter/default/constraint")
        instances = re.findall(r"^\s*(\w+) #\((.*)\) (\w+)\(([^;]*)\);", body, re.M)
        need(len(instances) == ((2 if twins else 1) if name == top else 0), "bounded instance count")
        for index, (callee, actual, occurrence, mapping) in enumerate(instances):
            need(callee == "child" and occurrence not in occurrences, "exact occurrence target")
            normalized = re.sub(r"\s+", "", actual)
            allowed = {".n(p)"}
            if mode == "add-zero":
                allowed.add(".n((p+0))")
            if mode == "multiply-one":
                allowed.add(".n((p*1))")
            need(normalized in allowed, "symbolic child actual, not sampled default")
            endpoint = "secondPins" if index else "pins"
            pairs = re.findall(r"\.(\w+)\((\w+)\)", mapping)
            expected = {(pin("pins", leaf), pin(endpoint, leaf)) for leaf in "pn"}
            need(len(pairs) == 2 and set(pairs) == expected, "exact whole nested leaf mapping")
            occurrences[occurrence] = expected
    bindings = [json.loads(value) for value in re.findall(r"// nodal.array_binding (.*)", text)]
    need(len(bindings) == 2 * len(occurrences), "one provenance edge per leaf")
    seen = set()
    for binding in bindings:
        key = (binding["instance"], binding["formal"].rsplit(".", 1)[-1], binding["target_actual"])
        need(key not in seen and key[0] in occurrences and key[1:] in occurrences[key[0]], "binding identity")
        need(binding["effective_shape"] == "p" and not binding["projections"] and
             not binding["stages"] and binding["instance_source"], "symbolic whole-array provenance")
        seen.add(key)
    rows = [base64.b64decode(value, validate=True).decode() for value in
            re.findall(r"// nodal.interface_correspondence mlir-v1 (\S+)", text)]
    need(len(rows) == len(set(rows)) == (10 if twins else 6), "exact correspondence count")
    for row in rows:
        for token in ('selected_access = "connect"', 'selected_role = "connected"',
                      'leaf_role = "connect"', 'discipline = "electrical"',
                      'path = "pair", role = "connect"'):
            need(token in row, "nested role/nature provenance")
        need(re.search(r'member = "pair\.[pn]"', row) and 'source = "' + top + '.' in row,
             "source member provenance")
    return code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    modes = ("early", "late", "add-zero", "multiply-one", "reverse", "two-instances", "default-one")
    actual = {mode: check(read(args.folder, mode + "-emit"), mode) for mode in modes}
    need(actual["early"] == actual["late"] == actual["reverse"], "binding/orientation parity")
    need(read(args.folder, "late-emit") == read(args.folder, "late-repeat"), "repeat parity")
    ir = read(args.folder, "late-adapt")
    for operation, count in (("terminal_array", 4), ("instance_terminal_array", 2),
                             ("connect_array", 2), ("instance", 1), ("module", 2)):
        need(ir.count('"nodal.' + operation + '"') == count, "bounded adapted " + operation)
    need('!nodal.shaped<"p",' in ir and '!nodal.shaped<"n",' in ir, "symbolic adapted types")
    need(not re.search(r'"nodal\.(interface\w*|member_access|shape_index|shape_view|generate|clock_domain)"', ir),
         "residual logical or per-lane operation")
    for name, extent, twins in (("fixed-extent1", 1, False), ("fixed-extent3", 3, False),
                                ("fixed-reverse", 3, False), ("fixed-two-instances", 3, True)):
        fixed.check_va(read(args.folder, name + "-emit"), extent, twins)
    print("PASS actual symbolic vectors, named child actuals, nested provenance and literal predecessors")


if __name__ == "__main__":
    main()
