#!/usr/bin/env python3
"""Executable noise-source contracts, independent native boundaries and source replay.

Structural Verilog-A acceptance is not simulator execution or a PSD measurement.
"""
from __future__ import annotations
import argparse
import re
import subprocess
import tempfile
from pathlib import Path

PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def fixture(body: str) -> str:
    return '''module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "NoiseTop", metadata = {}}> ({
    "nodal.parameter"() <{sym_name = "P", type = f64, default_value = -2.0 : f64, variability = "symbolic", metadata = {}}> : () -> ()
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
      %a = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "A"}}> : () -> f64
      %s = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "s"}}> : () -> f64
      %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
      %two = "nodal.real_literal"() <{value = 2.0 : f64, metadata = {}}> : () -> f64
      %squared = "nodal.analog_mul"(%a, %a) <{metadata = {}}> : (f64, f64) -> f64
      %density = "nodal.analog_mul"(%squared, %s) <{metadata = {}}> : (f64, f64) -> f64
      %f1 = "nodal.analog_div"(%one, %s) <{metadata = {}}> : (f64, f64) -> f64
      %f2 = "nodal.analog_div"(%two, %s) <{metadata = {}}> : (f64, f64) -> f64
      %param = "nodal.parameter_ref"() <{parameter = @P, metadata = {}}> : () -> f64
      %symbolic = "nodal.analog_mul"(%density, %param) <{metadata = {}}> : (f64, f64) -> f64
''' + body + '''
    }) : () -> ()
  }) : () -> ()
}
'''


def noise(kind="white", operands=None, result="source", label="thermal", dimension="current"):
    if operands is None:
        operands = {"white": ["%density"], "flicker": ["%density", "%one"],
                    "table": ["%f2", "%density", "%f1", "%density"]}[kind]
    return (f'      %{result} = "nodal.analog_noise"({", ".join(operands)}) '
            f'<{{noise_kind = "{kind}", contract_version = "1", noise_name = "{label}", '
            f'source_id = "NoiseTop.{result}", owner = "NoiseTop", correlation = "independent", '
            f'analyses = ["noise"], result_dimension = "{dimension}", '
            f'metadata = {{semantic_path = "NoiseTop.{result}"}}}}> '
            f': ({", ".join("f64" for _ in operands)}) -> f64\n')


def contribution(value="source"):
    return (f'      "nodal.contribute"(%branch, %{value}) <{{kind = "flow", metadata = {{}}}}> '
            ': (!nodal.branch<"electrical">, f64) -> ()\n')


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-increment39-") as temporary:
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
            expected = text.count('"nodal.analog_noise"')
            assert optimized.stdout.count('"nodal.analog_noise"') == expected, optimized.stdout
            for line in optimized.stdout.splitlines():
                if '"nodal.analog_noise"' in line:
                    assert "nodal.folded" not in line, line
            outputs = []
            for ir in [parsed.stdout, optimized.stdout]:
                result = invoke(translate, ir, "--nodal-to-verilog-a")
                assert result.returncode == 0, result.stderr
                assert len(re.findall(r"(?:white_noise|flicker_noise|noise_table)\(", result.stdout)) == expected, result.stdout
                outputs.append(result.stdout)
            count += 1
            return optimized.stdout, outputs[0], outputs[1]

        def negative(text, diagnostic):
            nonlocal count
            for tool, args in [(nodalc, ()), (nodalc, (PIPELINE,)),
                               (translate, ("--nodal-to-verilog-a",))]:
                result = invoke(tool, text, *args)
                assert result.returncode != 0 and diagnostic in result.stderr, result.stdout + result.stderr
                assert "endmodule" not in result.stdout, "partial target output escaped failure"
            count += 1

        for kind in ["white", "flicker", "table"]:
            _, va, after = positive(fixture(noise(kind) + contribution()))
            target = {"white": "white_noise", "flicker": "flicker_noise", "table": "noise_table"}[kind]
            assert target + "(" in va and target + "(" in after
            assert '"thermal"' in va
            assert "I(p, n) <+ noise_0;" in va, va
            if kind == "table":
                assert "noise_table('{" in va, va
        # Reusing one SSA value shares a source. Same-name calls are not CSE candidates.
        addition = '      %sum = "nodal.analog_add"(%source, %source) <{metadata = {}}> : (f64, f64) -> f64\n'
        shared = fixture(noise() + addition + contribution("sum"))
        _, va, _ = positive(shared)
        assert "(noise_0 + noise_0)" in va, va
        _, va, _ = positive(fixture(noise() + noise(result="other") + contribution()))
        assert va.count('"thermal"') == 2 and "noise_1 = white_noise(" in va, va
        # Deliberately unused and zero-power effects must survive optimization/emission.
        for value in ["0.0", "1.0e-18"]:
            positive(fixture(noise()).replace("value = 1.0 : f64, metadata = {unit = \"A\"}",
                                             f"value = {value} : f64, metadata = {{unit = \"A\"}}"))
        _, va, _ = positive(fixture(noise(operands=["%symbolic"]) + contribution()))
        assert "P" in va and "white_noise(" in va, va
        for exponent in ["-1.0", "0.0", "0.5", "2.0"]:
            positive(fixture(noise("flicker") + contribution()).replace(
                '%one = "nodal.real_literal"() <{value = 1.0',
                f'%one = "nodal.real_literal"() <{{value = {exponent}'))
        for label in ["a space", "same<+report", "report//label", "id:one.two-3"]:
            positive(fixture(noise(label=label) + contribution()))
        # The LRM accepts unsorted unique frequencies and one-point constant spectra.
        positive(fixture(noise("table", ["%f1", "%density"]) + contribution()))
        positive(fixture(noise("table") + contribution()).replace(
            '%one = "nodal.real_literal"() <{value = 1.0', '%one = "nodal.real_literal"() <{value = 0.0'))
        baseline = fixture(noise() + contribution())
        for old, new, code in [
            ('noise_kind = "white"', 'noise_kind = "foreign"', "039-002"),
            ('contract_version = "1"', 'contract_version = "2"', "039-002"),
            ('owner = "NoiseTop"', 'owner = "Elsewhere"', "039-002"),
            ('source_id = "NoiseTop.source"', 'source_id = "other"', "039-002"),
            ('semantic_path = "NoiseTop.source"', 'semantic_path = "NoiseTop.fake"', "039-002"),
            ('noise_name = "thermal"', 'noise_name = ""', "039-005"),
            ('correlation = "independent"', 'correlation = "group"', "039-006"),
            ('analyses = ["noise"]', 'analyses = ["noise", "transient"]', "039-006"),
            ('analyses = ["noise"]', 'analyses = []', "039-006"),
            ('result_dimension = "current"', 'result_dimension = "voltage"', "039-003"),
        ]:
            negative(baseline.replace(old, new), code)
        for operands in [[], ["%density", "%one"]]:
            negative(fixture(noise(operands=operands)), "039-002")
        for operands in [["%density"], ["%density", "%one", "%two"]]:
            negative(fixture(noise("flicker", operands)), "039-002")
        for operands in [[], ["%f1"], ["%f1", "%density", "%f2"]]:
            negative(fixture(noise("table", operands)), "039-002")
        for operand in ["%a", "%one", "%squared"]:
            negative(fixture(noise(operands=[operand])), "039-003")
        negative(fixture(noise("flicker", ["%density", "%a"])), "039-003")
        negative(fixture(noise("flicker", ["%density", "%param"])), "039-006")
        for operands, code in [(["%one", "%density"], "039-003"),
                               (["%f1", "%a"], "039-003"),
                               (["%f1", "%symbolic"], "039-006"),
                               (["%symbolic", "%density"], "039-003"),
                               (["%f1", "%density", "%f1", "%density"], "039-004")]:
            negative(fixture(noise("table", operands)), code)
        negated = '      %negative = "nodal.analog_neg"(%density) <{metadata = {}}> : (f64) -> f64\n'
        for kind, operands in [("white", ["%negative"]), ("flicker", ["%negative", "%one"]),
                               ("table", ["%f1", "%negative"])]:
            negative(fixture(negated + noise(kind, operands)), "039-004")
        negative(fixture(noise("table")).replace(
            '%one = "nodal.real_literal"() <{value = 1.0', '%one = "nodal.real_literal"() <{value = -1.0'), "039-004")
        duplicate = fixture(noise() + noise(result="other"))
        negative(duplicate.replace("NoiseTop.other", "NoiseTop.source"), "039-002")
        modulated = noise(dimension="1", operands=["%s"]) + (
            '      %modulated = "nodal.analog_mul"(%density, %source) <{metadata = {}}> : (f64, f64) -> f64\n')
        negative(fixture(modulated + noise(result="other", operands=["%modulated"])), "039-006")
        for attribute in ['nodal.folded = true', 'nodal.folded_value = 0.0 : f64', 'nodal.simplified = true']:
            negative(baseline.replace('}}> : (f64) -> f64', f'}}}}> {{{attribute}}} : (f64) -> f64'), "NODAL-ANALOG-FOLD-001")
        parent = shared.replace('<{metadata = {}}> : (f64, f64) -> f64\n      "nodal.contribute"',
            '<{metadata = {}}> {nodal.folded = true, nodal.folded_value = 0.0 : f64, '
            'nodal.folded_dimension = "current", nodal.folded_kind = "real", '
            'nodal.folded_provenance = "increment30"} : (f64, f64) -> f64\n      "nodal.contribute"')
        assert parent != shared
        negative(parent, "NODAL-ANALOG-FOLD-001")
        if source:
            text = source.read_text()
            _, va, after = positive(text)
            assert text.count('"nodal.analog_noise"') == 5
            assert va.count('"thermal"') == 2 and after.count('"thermal"') == 2
            assert "scale" in va and "scale" in after
            assert "(noise_0 + noise_0)" in va and '"zero power"' in va
        print(f"Increment 39 native/source matrix: {count} cases passed")
        return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source)


if __name__ == "__main__":
    main()
