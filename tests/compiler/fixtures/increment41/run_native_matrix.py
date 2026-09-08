#!/usr/bin/env python3
"""Independent typed-function compiler and target matrix; no numerical solver claims."""
from __future__ import annotations
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import tempfile

PREDECESSOR = Path(__file__).resolve().parents[1] / "increment40/run_native_matrix.py"
spec = importlib.util.spec_from_file_location("increment40_fixture", PREDECESSOR)
assert spec is not None and spec.loader is not None
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
PIPELINE = previous.PIPELINE
REAL = '!nodal.quantity<"real", "1">'
VOLTAGE = '!nodal.quantity<"real", "voltage">'
INTEGER = '!nodal.quantity<"integer", "1">'


def value(name, kind, result=REAL, operands=(), types=(), extra=""):
    return (f'      %{name} = "nodal.analog_function_value"({", ".join(operands)}) '
            f'<{{kind = "{kind}", metadata = {{}}{extra}}}> '
            f': ({", ".join(types)}) -> {result}\n')


def call(name, callee, operands, types, result):
    return (f'      %{name} = "nodal.analog_user_call"({", ".join(operands)}) '
            f'<{{callee = @{callee}, contract_version = "1", metadata = {{}}}}> '
            f': ({", ".join(types)}) -> {result}\n')


def returned(name, result=VOLTAGE):
    return f'      "nodal.analog_function_return"(%{name}) <{{metadata = {{}}}}> : ({result}) -> ()\n'


def definition(name="scaleSignal", body=None, result=VOLTAGE):
    if body is None:
        body = (value("x", "input", VOLTAGE, extra=', name = "signal", argument_index = 0 : i64') +
                value("gain", "input", REAL, extra=', name = "factor", argument_index = 1 : i64') +
                value("product", "mul", VOLTAGE, ("%x", "%gain"), (VOLTAGE, REAL)) +
                value("scaled", "local", VOLTAGE, ("%product",), (VOLTAGE,), ', name = "scaled"') +
                returned("scaled"))
    return (f'    "nodal.analog_user_function"() <{{sym_name = "{name}", return_type = {result}, '
            f'contract_version = "1", metadata = {{}}}}> ({{\n{body}'
            '    }) : () -> ()\n')


def fixture(functions=None, calls=None):
    if functions is None:
        functions = definition()
    if calls is None:
        calls = call("answer", "scaleSignal", ("%signal", "%param"), ("f64", "f64"), "f64") + previous.contribution("answer")
    text = previous.fixture(calls)
    return text.replace('    "nodal.analog"()', functions + '    "nodal.analog"()', 1)


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-increment41-") as temporary:
        path = Path(temporary) / "case.mlir"

        def invoke(tool, text, *arguments):
            path.write_text(text)
            return subprocess.run([str(tool), *arguments, str(path)], capture_output=True,
                                  text=True, timeout=90, check=False)

        def positive(text):
            nonlocal count
            parsed = invoke(nodalc, text)
            assert parsed.returncode == 0, parsed.stderr
            optimized = invoke(nodalc, text, PIPELINE)
            assert optimized.returncode == 0, optimized.stderr
            replay = invoke(nodalc, optimized.stdout, PIPELINE)
            assert replay.returncode == 0 and replay.stdout == optimized.stdout, replay.stderr
            for operation in ("nodal.analog_user_function", "nodal.analog_user_call", "nodal.analog_function_return"):
                assert optimized.stdout.count(f'"{operation}"') == text.count(f'"{operation}"'), optimized.stdout
            outputs = []
            for ir in (parsed.stdout, optimized.stdout):
                target = invoke(translate, ir, "--nodal-to-verilog-a")
                assert target.returncode == 0, target.stderr
                assert target.stdout.count("analog function ") == text.count('"nodal.analog_user_function"')
                repeated = invoke(translate, ir, "--nodal-to-verilog-a")
                assert repeated.returncode == 0 and repeated.stdout == target.stdout
                outputs.append(target.stdout)
            count += 1
            return outputs[0]

        def negative(text, diagnostic="NODAL-ANALOG-041-"):
            nonlocal count
            for tool, args in ((nodalc, ()), (nodalc, (PIPELINE,)), (translate, ("--nodal-to-verilog-a",))):
                result = invoke(tool, text, *args)
                assert result.returncode != 0 and diagnostic in result.stderr, result.stdout + result.stderr
                assert "endmodule" not in result.stdout, "partial target escaped rejection"
            count += 1

        def target_negative(text):
            nonlocal count
            parsed = invoke(nodalc, text)
            assert parsed.returncode == 0, parsed.stderr
            target = invoke(translate, text, "--nodal-to-verilog-a")
            assert target.returncode != 0 and "NODAL-BACKEND-NAMING-" in target.stderr, target.stderr
            assert not target.stdout, "invalid function published partial HDL"
            count += 1

        text = fixture()
        target = positive(text)
        assert "analog function real scaleSignal;" in target
        assert "input signal, factor;" in target and "real scaled;" in target
        assert "scaled = (signal * factor);" in target and "scaleSignal = scaled;" in target
        assert "scaleSignal(V(p, n), P)" in target
        # Dependency sorting, not source order; explicit call survives and each body occurs once.
        nested = (value("x", "input", VOLTAGE, extra=', name = "signal", argument_index = 0 : i64') +
                  value("g", "literal", REAL, extra=', value = 0.5 : f64') +
                  call("nested", "scaleSignal", ("%x", "%g"), (VOLTAGE, REAL), VOLTAGE) + returned("nested"))
        nested_functions = definition("outerSignal", nested) + definition()
        nested_target = positive(fixture(nested_functions,
            call("answer", "outerSignal", ("%signal",), ("f64",), "f64") + previous.contribution("answer")))
        assert nested_target.index("analog function real scaleSignal;") < nested_target.index("analog function real outerSignal;")
        assert "outerSignal = scaleSignal(signal, 0.5);" in nested_target
        # Constant-only real division is emitted with real literals and not spuriously folded.
        real_body = (value("x", "input", REAL, extra=', name = "signal", argument_index = 0 : i64') +
                     value("one", "literal", REAL, extra=', value = 1.0 : f64') +
                     value("two", "literal", REAL, extra=', value = 2.0 : f64') +
                     value("half", "div", REAL, ("%one", "%two"), (REAL, REAL)) +
                     value("result", "mul", REAL, ("%x", "%half"), (REAL, REAL)) + returned("result", REAL))
        real_target = positive(fixture(definition("halfSignal", real_body, REAL),
            call("answer", "halfSignal", ("%dynamic",), ("f64",), "f64")))
        assert "(1.0 / 2.0)" in real_target, real_target
        # Integer result, named initialized local and relational expression as a selection guard.
        integer_body = (value("x", "input", INTEGER, extra=', name = "count", argument_index = 0 : i64') +
                        value("one", "literal", INTEGER, extra=', value = 1 : i32') +
                        value("next", "add", INTEGER, ("%x", "%one"), (INTEGER, INTEGER)) +
                        value("saved", "local", INTEGER, ("%next",), (INTEGER,), ', name = "nextCount"') + returned("saved", INTEGER))
        positive(fixture(definition("incrementCount", integer_body, INTEGER), ""))
        select_body = real_body.replace(returned("result", REAL),
            value("guard", "gt", "i1", ("%x", "%half"), (REAL, REAL)) +
            value("selected", "select", REAL, ("%guard", "%x", "%half"), ("i1", REAL, REAL)) + returned("selected", REAL))
        positive(fixture(definition("selectSignal", select_body, REAL), ""))
        math_body = real_body.replace(returned("result", REAL),
            value("root", "math", REAL, ("%two",), (REAL,), ', function_id = "sqrt"') + returned("root", REAL))
        positive(fixture(definition("rootSignal", math_body, REAL), ""))
        # Unknown/corrupt resolution, type/rank/arity, return and input order.
        negative(text.replace("callee = @scaleSignal", "callee = @missing"))
        negative(text.replace("contract_version = \"1\"", "contract_version = \"future\""))
        negative(text.replace('name = "factor", argument_index = 1', 'name = "signal", argument_index = 1'))
        negative(text.replace("argument_index = 1 : i64", "argument_index = 7 : i64"))
        negative(text.replace(returned("scaled"), ""))
        negative(text.replace(returned("scaled"), returned("scaled") + returned("scaled")))
        negative(text.replace('return_type = ' + VOLTAGE, 'return_type = ' + REAL))
        negative(text.replace('callee = @scaleSignal, contract_version', 'callee = @P, contract_version'))
        negative(fixture(calls=call("answer", "scaleSignal", ("%signal",), ("f64",), "f64")))
        negative(fixture(calls=call("answer", "scaleSignal", ("%signal", "%time"), ("f64", "f64"), "f64")))
        negative(fixture(calls=call("answer", "scaleSignal", ("%signal", "%param"), ("f64", "f64"), REAL)))
        negative(text.replace('name = "scaled"', 'name = "scaleSignal"'))
        # Direct recursion and two-function cycles are not source order ambiguities.
        recursive = definition(body=(value("x", "input", VOLTAGE, extra=', name = "signal", argument_index = 0 : i64') +
            value("g", "input", REAL, extra=', name = "factor", argument_index = 1 : i64') +
            call("recursive", "scaleSignal", ("%x", "%g"), (VOLTAGE, REAL), VOLTAGE) + returned("recursive")))
        negative(fixture(recursive), "NODAL-ANALOG-041-007")
        left = recursive.replace('sym_name = "scaleSignal"', 'sym_name = "firstSignal"').replace('callee = @scaleSignal', 'callee = @secondSignal')
        right = recursive.replace('sym_name = "scaleSignal"', 'sym_name = "secondSignal"').replace('callee = @scaleSignal', 'callee = @firstSignal')
        negative(fixture(left + right, ""), "NODAL-ANALOG-041-007")
        # Input captures violate the isolated source body even when visible to enclosing IR.
        captured = text.replace('"nodal.analog_function_return"(%scaled)', '"nodal.analog_function_return"(%branch)').replace(
            f': ({VOLTAGE}) -> ()\n    }})', ': (!nodal.branch<"electrical">) -> ()\n    })')
        negative(captured, "error:")
        # Unknown ops/effects cannot bypass the detached source recorder through native IR.
        forbidden = value("bad", "literal", REAL, extra=', value = 1.0 : f64').replace('"nodal.analog_function_value"', '"nodal.real_literal"').replace('kind = "literal", ', '').replace(REAL, "f64")
        negative(text.replace(returned("scaled"), forbidden + returned("scaled")), "NODAL-ANALOG-041-")
        # Opaque calls cannot acquire folded/simplified metadata, including through enclosing math.
        for annotation in ('nodal.folded_value = 1.0 : f64', 'nodal.simplified = true', 'constant_value = 1.0 : f64'):
            negative(text.replace('callee = @scaleSignal,', f'{annotation}, callee = @scaleSignal,'), "NODAL-ANALOG-FOLD-001")
            negative(text.replace('kind = "mul",', f'{annotation}, kind = "mul",'), "NODAL-ANALOG-FOLD-001")
        # Constants are recursively checked through initialized locals, not only direct literals.
        zero_body = real_body.replace('value = 2.0 : f64', 'value = 0.0 : f64')
        negative(fixture(definition("zeroSignal", zero_body, REAL), ""), "NODAL-ANALOG-041-008")
        local_zero = real_body.replace(value("half", "div", REAL, ("%one", "%two"), (REAL, REAL)),
            value("zero", "sub", REAL, ("%two", "%two"), (REAL, REAL)) +
            value("stored", "local", REAL, ("%zero",), (REAL,), ', name = "zeroValue"') +
            value("half", "div", REAL, ("%one", "%stored"), (REAL, REAL)))
        negative(fixture(definition("localZero", local_zero, REAL), ""), "NODAL-ANALOG-041-008")
        invalid_root = real_body.replace(returned("result", REAL),
            value("negative", "sub", REAL, ("%one", "%two"), (REAL, REAL)) +
            value("root", "math", REAL, ("%negative",), (REAL,), ', function_id = "sqrt"') + returned("root", REAL))
        negative(fixture(definition("invalidRoot", invalid_root, REAL), ""), "NODAL-ANALOG-041-008")
        # Unsupported target names reject without publishing partial output.
        for keyword in ("input", "real", "analog", "function", "sqrt", "laplace_nd"):
            target_negative(text.replace('sym_name = "scaleSignal"', f'sym_name = "{keyword}"').replace(
                'callee = @scaleSignal', f'callee = @{keyword}'))
            target_negative(text.replace('name = "scaled"', f'name = "{keyword}"'))
        target_negative(text.replace('sym_name = "scaleSignal"', 'sym_name = "p"').replace('callee = @scaleSignal', 'callee = @p'))
        if source is not None:
            public = positive(source.read_text())
            assert public.count("analog function ") == 6
            assert "analog function integer incrementCount;" in public
            assert "(1.0 / 2.0)" in public
            assert "scaleSignal" not in public  # Public witness has its own names, no hardcoded fixture.
            assert "laplace_nd" in public
            assert "affineSignal" in public and "curveSignal" in public
    print(f"Increment 41 native function matrix: {count} cases passed")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source)
