#!/usr/bin/env python3
"""Execute Increment 38 registry, IR, optimization, and target-boundary tests."""
from __future__ import annotations
import argparse
import json
import math
import re
import struct
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
REGISTRY = json.loads((ROOT / "core/compiler/analog-functions-v1.json").read_text())
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def fixture(body: str) -> str:
    return '''module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "MathTop", metadata = {}}> ({
    "nodal.parameter"() <{sym_name = "P", type = f64, default_value = -2.0 : f64, variability = "symbolic", metadata = {}}> : () -> ()
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
      %a = "nodal.real_literal"() <{value = 0.5 : f64, metadata = {}}> : () -> f64
      %b = "nodal.real_literal"() <{value = 2.0 : f64, metadata = {}}> : () -> f64
      %v = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "V"}}> : () -> f64
      %param = "nodal.parameter_ref"() <{parameter = @P, metadata = {}}> : () -> f64
''' + body + '''
    }) : () -> ()
  }) : () -> ()
}
'''


def call(identifier: str, arguments: list[str], result: str = "f64", version: str = "1") -> str:
    types = ", ".join("f64" for _ in arguments)
    return (f'      %result = "nodal.analog_function"({", ".join(arguments)}) '
            f'<{{function_id = "{identifier}", registry_version = "{version}", metadata = {{}}}}> '
            f': ({types}) -> {result}')


def output() -> str:
    return '''
      %scaled = "nodal.analog_mul"(%result, %v) <{metadata = {}}> : (f64, f64) -> f64
      "nodal.contribute"(%branch, %scaled) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()'''


def query(identifier: str, suffix: str = "") -> str:
    return (f'      %query{suffix} = "nodal.analog_analysis"() '
            f'<{{analysis_kind = "{identifier}", registry_version = "1", metadata = {{}}}}> : () -> i1')


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-increment38-") as temporary:
        directory = Path(temporary)

        def invoke(tool, text, *args):
            path = directory / "case.mlir"
            path.write_text(text)
            return subprocess.run([str(tool), *args, str(path)], text=True, capture_output=True,
                                  timeout=90, check=False)

        def positive(text):
            nonlocal count
            raw = invoke(nodalc, text)
            assert raw.returncode == 0, raw.stderr
            folded = invoke(nodalc, text, PIPELINE)
            assert folded.returncode == 0, folded.stderr
            repeated = invoke(nodalc, folded.stdout, PIPELINE)
            assert repeated.returncode == 0 and repeated.stdout == folded.stdout, repeated.stderr
            va = invoke(translate, raw.stdout, "--nodal-to-verilog-a")
            assert va.returncode == 0, va.stderr
            after = invoke(translate, folded.stdout, "--nodal-to-verilog-a")
            assert after.returncode == 0, after.stderr
            count += 1
            return folded.stdout, va.stdout, after.stdout

        def negative(text, code):
            nonlocal count
            for tool, args in [(nodalc, ()), (nodalc, (PIPELINE,)),
                               (translate, ("--nodal-to-verilog-a",))]:
                result = invoke(tool, text, *args)
                assert result.returncode != 0 and code in result.stderr, result.stdout + result.stderr
                assert "endmodule" not in result.stdout, "partial HDL escaped a failed boundary"
            count += 1

        for entry in REGISTRY["functions"]:
            identifier = entry["id"]
            first = "%b" if identifier == "acosh" else "%a"
            arguments = [first] + (["%b"] if entry["arity"] == 2 else [])
            folded, va, _ = positive(fixture(call(identifier, arguments) + output()))
            assert entry["verilog_a"] + "(" in va, va
            assert "increment38-registry1" in folded, folded
            function_line = next(line for line in folded.splitlines() if '"nodal.analog_function"' in line)
            folded_match = re.search(r'nodal.folded_value = ([^ ]+) : f64', function_line)
            assert folded_match, function_line
            token = folded_match[1]
            value = (struct.unpack(">d", bytes.fromhex(token[2:]))[0]
                     if token.startswith("0x") else float(token))
            oracle = {"abs": abs, "min": min, "max": max, "ln": math.log}.get(identifier)
            if oracle is None:
                oracle = getattr(math, identifier)
            samples = [2.0 if identifier == "acosh" else 0.5] + ([2.0] if entry["arity"] == 2 else [])
            expected = oracle(*samples)
            assert math.isclose(value, expected, rel_tol=2e-14, abs_tol=1e-15), (identifier, value, expected)
            symbolic = ["%param"] * entry["arity"]
            folded, va, _ = positive(fixture(call(identifier, symbolic) + output()))
            function = next(line for line in folded.splitlines() if '"nodal.analog_function"' in line)
            assert "nodal.folded" not in function and "P" in va, function
            negative(fixture(call(identifier, [])), "038-002")
        for entry in REGISTRY["analyses"]:
            text = fixture(query(entry["id"]) + '''
      %result = "nodal.analog_select"(%query, %a, %b) <{metadata = {}}> : (i1, f64, f64) -> f64''' + output())
            folded, va, after = positive(text)
            assert f'analysis("{entry["verilog_a"]}")' in va and va == after, va
            line = next(line for line in folded.splitlines() if '"nodal.analog_analysis"' in line)
            assert "nodal.folded" not in line, line
            # Neither forged annotations nor a zero-operand node may manufacture constness.
            forged = text.replace('analysis_kind =', 'nodal.folded = true, nodal.folded_value = true, analysis_kind =', 1)
            folded, _, after = positive(forged)
            assert f'analysis("{entry["verilog_a"]}")' in after
            assert "nodal.folded_value = true" not in folded
        negative(fixture(call("foreign_call", ["%a"])), "038-001")
        negative(fixture(call("sin", ["%a"], version="2")), "038-001")
        negative(fixture(call("sin", ["%a"], result="i1")), "038-003")
        negative(fixture(call("sin", ["%v"])), "038-003")
        negative(fixture(call("max", ["%v", "%a"])), "038-003")
        negative(fixture(call("sqrt", ["%v"])), "038-003")
        for identifier, value in [("sqrt", -1.0), ("ln", 0.0), ("log10", -1.0),
                                  ("asin", 2.0), ("acos", -2.0), ("acosh", 0.0),
                                  ("atanh", 1.0), ("exp", 1000.0)]:
            text = fixture(call(identifier, ["%a"])).replace("value = 0.5 : f64", f"value = {value} : f64")
            negative(text, "038-004")
        negative(fixture(call("pow", ["%a", "%a"])).replace("value = 0.5 : f64", "value = 0.0 : f64"), "038-004")
        negative(fixture(query("foreign")), "038-001")
        negative(fixture(query("dc")).replace('-> i1', '-> f64'), "038-003")
        positive(fixture('''
      %squared = "nodal.analog_mul"(%v, %v) <{metadata = {}}> : (f64, f64) -> f64
''' + call("sqrt", ["%squared"])))
        if source:
            for path in [source, Path(str(source) + ".events.mlir")]:
                folded, va, after = positive(path.read_text())
                if path == source:
                    # This public witness has intentionally unused queries: retain them in IR,
                    # but do not require dead expressions to become Verilog statements.
                    assert '"nodal.analog_analysis"' in folded
                    assert "tanh(" in va and "tanh(" in after
                else:
                    assert 'analysis("tran")' in va and 'analysis("tran")' in after
                assert "@event_reference_" not in folded
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", required=True, type=Path)
    parser.add_argument("--translate", required=True, type=Path)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    print(f"Increment 38 native matrix: {run(args.nodalc, args.translate, args.source)} cases passed")


if __name__ == "__main__":
    main()
