#!/usr/bin/env python3
"""Check actual nested VA statements against correspondence; no expression evaluation.
Usage: python3 -I -B check_output.py nested-emit.stdout [anchor-emit.stdout]
"""
import json
from pathlib import Path
import re
import sys

PORTS = {"p": "nodal_i70696e73_70", "n": "nodal_i70696e73_6e"}

def require(condition, message):
    if not condition:
        raise AssertionError(message)

def records(text, kind):
    prefix = "// nodal." + kind + " "
    return [json.loads(line.split(prefix, 1)[1])
            for line in text.splitlines() if line.strip().startswith(prefix)]

def check(path, anchor=False):
    text = Path(path).read_text(encoding="utf-8")
    modules = re.findall(r"^module (\w+)[^\n]*\n(.*?)^endmodule$", text, re.M | re.S)
    owner = "NestedConservativeAnchorSource" if anchor else "NestedConservativeSource"
    require(len(modules) == 3 and len(dict(modules)) == 3, "wrong module inventory")
    definitions = dict(modules)
    require(owner in definitions and "nodal_alias_electrical" in definitions, "missing witness modules")
    top = definitions[owner]
    scopes = records(top, "structural_scope")
    require(len(scopes) == 2, "expected two actual structural scopes")
    for index, scope in enumerate(scopes):
        require(scope["source_scope"] == owner + (".generate_0" if index == 0 else ".generate_0.generate_1"),
                "wrong original scope ancestry")
        require((scope["lower"], scope["upper_exclusive"], scope["step"]) == ("0", str(index + 2), "1"),
                "wrong rectangular loop bounds")
    outer, inner = [scope["target_genvar"] for scope in scopes]
    require(outer != inner, "nested genvars must not alias")
    headers = [f'for ({s["target_genvar"]} = 0; {s["target_genvar"]} < {s["upper_exclusive"]}; '
               f'{s["target_genvar"]} = {s["target_genvar"]} + 1) begin : {s["target_label"]}' for s in scopes]
    lines = [line.strip() for line in top.splitlines() if not line.strip().startswith("//")]
    require(lines.count("generate") == 1 and lines.count("endgenerate") == 1, "wrong generate enclosure")
    require([line for line in lines if line.startswith("genvar ")] == [f"genvar {outer};", f"genvar {inner};"],
            "unexpected genvar declarations")
    depth, loops = 0, []
    carriers, constants, instances = {}, {}, {}
    for line in lines:
        if line.startswith("for ("):
            require(len(loops) < 2 and line == headers[len(loops)] and depth == len(loops), "loops are not exactly nested")
            loops.append(line)
            depth += 1
        elif line == "end":
            depth -= 1
            require(depth >= 0, "unbalanced generated scope")
        carrier = re.fullmatch(r"electrical \[0:5\] (\w+);", line)
        if carrier:
            require(depth == 0 and not loops, "carrier is not Module-owned")
            require(carrier[1] not in carriers, "duplicate carrier")
            carriers[carrier[1]] = True
        constant = re.fullmatch(r"localparam integer (\w+) = (.*);", line)
        if constant:
            require(constant[1] not in constants, "duplicate map constant")
            constants[constant[1]] = (constant[2], depth)
        instance = re.fullmatch(r"(\w+) #\(\.gain\((3|7)\)\) (\w+)\((.*)\);", line)
        if instance:
            require(depth == 2 and instance[3] not in instances, "child must occur once in inner loop")
            instances[instance[3]] = (instance[1], instance[2], instance[4])
    require(depth == 0 and loops == headers, "incomplete nested loops")
    require(len(carriers) == (2 if anchor else 4), "wrong six-element carrier inventory")
    require(len(instances) == (1 if anchor else 2), "wrong authored child count")
    require(sorted(value[1] for value in instances.values()) == (["3"] if anchor else ["3", "7"]), "literal gains changed")
    child_names = {value[0] for value in instances.values()}
    require(len(child_names) == 1, "children must share one definition")
    child = definitions[next(iter(child_names))]
    require(child.count("parameter real gain = 2;") == 1 and
            child.count("V(observed, reference) <+ (gain * 1);") == 1, "private symbolic gain equation changed")
    bindings = records(top, "array_binding")
    ports = [value for value in bindings if value["instance"]]
    require(len(ports) == len(carriers), "missing child-port routing records")
    seen, used = set(), set()
    expression = f"(({outer} * 3) + {inner})"

    def address(value, expected_expression, depth):
        match = re.fullmatch(r"(\w+)\[(\w+)\]", value["target_actual"])
        require(match is not None and match[1] in carriers, "lost Module carrier")
        require(value["root_shape"] == "2,3" and value["root"] == owner + "." + match[1], "wrong ordered carrier shape/root")
        require(constants.get(match[2]) == (expected_expression, depth), "wrong ordered localparam")
        return match[1]

    for value in ports:
        name = value["instance"]
        leaf = value["formal"].rsplit(".", 1)[-1]
        require(name in instances and leaf in PORTS and (name, leaf) not in seen, "duplicate or unknown child formal")
        seen.add((name, leaf))
        require(value.get("source_scope") == scopes[1]["source_scope"], "child binding left inner scope")
        actual = value["target_actual"]
        require(value["left"]["target_actual"] == actual == value["right"]["target_actual"], "child address mismatch")
        used.add(address(value["right"], expression, 2))
        require(instances[name][2].count(f'.{PORTS[leaf]}({actual})') == 1, "child port differs from record")
    require(used == set(carriers) and len(seen) == len(carriers), "unrouted carrier")

    joins = [value for value in bindings if not value["instance"]]
    def alias(value):
        suffix = f'(.lhs({value["left"]["target_actual"]}), .rhs({value["right"]["target_actual"]}));'
        require(value["alias_helper"] == "nodal_alias_electrical" and
                sum(suffix in line for line in lines) == 1, "alias record lacks exactly one statement")
    if not anchor:
        roots = {(instances[v["instance"]][1], v["formal"].rsplit(".", 1)[-1]):
                 v["right"]["root"].rsplit(".", 1)[-1] for v in ports}
        pairs = []
        for value in joins:
            require(value.get("source_scope") == scopes[1]["source_scope"], "join escaped inner scope")
            pairs.append(tuple(address(value[side], expression, 2) for side in ("left", "right")))
            alias(value)
        require(len(pairs) == 2 and set(pairs) == {(roots["3", leaf], roots["7", leaf]) for leaf in PORTS},
                "body joins must connect gain3 p/n to gain7 p/n without cross-leaf shorts")
    else:
        require("inout top;" in lines and "electrical top;" in lines, "Module anchor disappeared")
        require(len(joins) == 4, "expected two body and two repeated selected anchor connections")
        selected_roots, body_roots = [], set()
        for value in joins:
            selected = not value.get("source_scope")
            root = address(value["left"], "0" if selected else expression, 0 if selected else 2)
            require(value["right"]["target_actual"] == "top" and value["alias_helper"] == "nodal_alias_electrical", "anchor route must use the Module terminal")
            alias(value)
            if selected:
                selected_roots.append(root)
            else:
                require(value["source_scope"] == scopes[1]["source_scope"], "body anchor escaped inner scope")
                body_roots.add(root)
        p_root = next(value["right"]["root"].rsplit(".", 1)[-1] for value in ports if value["formal"].endswith(".p"))
        require(selected_roots == [p_root, p_root] and body_roots == set(carriers), "anchor routing changed")
    print("PASS " + owner + ": actual nested loops, ordered carriers, child bindings and private gain")

if __name__ == "__main__":
    require(len(sys.argv) in (2, 3), "usage: check_output.py nested-emit.stdout [anchor-emit.stdout]")
    check(sys.argv[1])
    if len(sys.argv) == 3:
        check(sys.argv[2], anchor=True)
