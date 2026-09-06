#!/usr/bin/env python3
"""Verify Nodal's closed crossing-direction contract on authored native IR."""
from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"
DIRECTION = 'slot = 1 : i64, value = "1", kind = "integer", dimension = "1", reads = []'


def run(nodalc: Path, translate: Path) -> int:
    baseline = (ROOT / "core/compiler/test/IR/analog-events.mlir").read_text()
    assert baseline.count(DIRECTION) == 1
    # A direction may be omitted only when the later optional slots are omitted.
    cross_line = next(line for line in baseline.splitlines() if "%cross =" in line)
    first_arg = 'arguments = [{slot = 0 : i64, value = "1.0 V", kind = "real", dimension = "voltage", reads = []}]'
    omitted_line = cross_line[:cross_line.index("arguments = ")] + first_arg + cross_line[cross_line.index(", analyses = "):]
    cases = [("omitted", baseline.replace(cross_line, omitted_line), True)]
    for value in (-1, 0, 1, -2, 2, -9223372036854775808, 9223372036854775807):
        cases.append((f"literal-{value}", baseline.replace(DIRECTION, DIRECTION.replace('value = "1"', f'value = "{value}"')), value in (-1, 0, 1)))
    # Parameter defaults do not constrain overrides, even when the default is legal.
    parameter = baseline.replace("default_value = 0 : i64", "default_value = 1 : i64")
    cases.append(("parameter-default", parameter.replace(DIRECTION, DIRECTION.replace('value = "1"', 'value = "EventTop.enable"')), False))
    cases.append(("forged-constant", parameter.replace(DIRECTION, DIRECTION.replace('value = "1"', 'value = "EventTop.enable"') + ', constant = 1.0 : f64'), False))
    # A mutable integer initialized to one is not a constant direction either.
    variable = '%dir = "nodal.analog_variable"() <{identity = "EventTop.dir", owner = "EventTop", declaration_order = 1 : i64, initialized = true, initializer_value = "1", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], metadata = {}}> : () -> !nodal.variable<"integer", "1">'
    mutable = baseline.replace(cross_line, "        " + variable + "\n" + cross_line)
    mutable = mutable.replace(DIRECTION, DIRECTION.replace('value = "1"', 'value = "EventTop.dir"').replace('reads = []', 'reads = ["EventTop.dir"]'))
    mutable = mutable.replace('analyses = [], event_reads = [], metadata = {}', 'analyses = [], event_reads = ["EventTop.dir"], metadata = {}', 1)
    cases.append(("mutable-initializer", mutable, False))
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-event-direction-") as tmp:
        path = Path(tmp) / "direction.mlir"
        for label, text, accepted in cases:
            path.write_text(text)
            outputs = []
            for pipeline in ((), (PIPELINE,)):
                result = subprocess.run([str(nodalc), *pipeline, str(path)], text=True, capture_output=True, timeout=90)
                if accepted:
                    assert result.returncode == 0, (label, result.stderr)
                    outputs.append(result.stdout)
                else:
                    assert result.returncode != 0 and "NODAL-ANALOG-037-004" in result.stderr, (label, result.stderr)
                    assert not result.stdout.strip(), (label, "invalid IR was published")
                count += 1
            for target in ("--nodal-to-verilog-a", "--nodal-to-verilog-ams"):
                rendered = []
                for ir in outputs or [text]:
                    path.write_text(ir)
                    result = subprocess.run([str(translate), target, str(path)], text=True, capture_output=True, timeout=90)
                    if accepted:
                        assert result.returncode == 0 and "@(cross(" in result.stdout, (label, result.stderr)
                        rendered.append(result.stdout)
                    else:
                        assert result.returncode != 0 and "NODAL-ANALOG-037-004" in result.stderr, (label, target, result.stderr)
                        assert not result.stdout.strip(), (label, "partial HDL was published")
                    count += 1
                if accepted:
                    assert rendered[0] == rendered[1], (label, "optimization changed the crossing")
            path.write_text(text)
    print(f"Increment 37 crossing-direction review: {count} checks passed")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve())
