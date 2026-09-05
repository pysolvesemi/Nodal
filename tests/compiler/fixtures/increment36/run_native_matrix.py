#!/usr/bin/env python3
"""Execute native waveform verification, optimization, and emission contracts."""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
FIXTURE = ROOT / "core/compiler/test/IR/analog-time-waveform.mlir"
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"
OPERATORS = ("transition", "slew", "absdelay", "abstime", "bound_step")


def invoke(command: list[str], expected: str | None = None) -> str:
    result = subprocess.run(command, text=True, capture_output=True, timeout=120, check=False)
    output = result.stdout + result.stderr
    if expected is None:
        if result.returncode:
            raise AssertionError(f"command failed: {command}\n{output}")
        return result.stdout
    if result.returncode == 0 or expected not in output:
        raise AssertionError(f"expected rejection {expected}: {command}\n{output}")
    return output


def change(text: str, marker: str, old: str, new: str) -> str:
    lines = text.splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if marker in line]
    if len(matches) != 1 or old not in lines[matches[0]]:
        raise AssertionError(f"mutation target is absent or ambiguous: {marker}: {old}")
    index = matches[0]
    lines[index] = lines[index].replace(old, new, 1)
    return "".join(lines)


def mutations(text: str) -> list[tuple[str, str, str]]:
    cases: list[tuple[str, str, str]] = []

    def add(label: str, marker: str, old: str, new: str, code: int) -> None:
        cases.append((label, change(text, marker, old, new), f"NODAL-ANALOG-036-{code:03d}"))

    add("context", "%smoothed =", 'context = "legacy-analog"', 'context = "initial"', 1)
    add("contract", "%smoothed =", 'operator_contract = "increment36"', 'operator_contract = "increment35"', 2)
    add("missing-contract", "%smoothed =", 'operator_contract = "increment36", ', '', 2)
    add("owner", "%smoothed =", 'owner = "Waveform"', 'owner = "Other"', 2)
    add("identity", "%smoothed =", 'operator_id = "Waveform.smoothed"', 'operator_id = "smoothed"', 2)
    add("duplicate-identity", "%limited =", 'operator_id = "Waveform.limited"', 'operator_id = "Waveform.smoothed"', 2)
    add("dimension-inventory", "%smoothed =", '"voltage", "time", "time", "time", "time"', '"voltage", "voltage", "time", "time", "time"', 3)
    add("result-dimension", "%smoothed =", 'result_dimension = "voltage"', 'result_dimension = "current"', 3)
    add("negative-time", 'sym_name = "DELAY"', '1.0e-9', '-1.0e-9', 4)
    add("zero-positive-rate", 'sym_name = "RISE"', '1.0e9', '0.0', 4)
    add("positive-negative-rate", 'sym_name = "FALL"', '-1.0e9', '1.0e9', 4)
    add("zero-absdelay", "%delayed =", '%limited, %changing, %seconds', '%limited, %zero, %seconds', 4)
    add("input-continuity", "%smoothed =", 'input_continuity = "constant"', 'input_continuity = "piecewise-constant"', 5)
    add("output-continuity", "%delayed =", 'output_continuity = "unknown"', 'output_continuity = "continuous"', 5)
    continuous = change(text, "%smoothed =", '%level, %zero', '%input, %zero')
    continuous = change(continuous, "%smoothed =", 'input_continuity = "constant"', 'input_continuity = "unknown"')
    cases.append(("continuous-transition", continuous, "NODAL-ANALOG-036-005"))
    add("state", "%smoothed =", 'Waveform.smoothed.state', 'Waveform.other.state', 6)
    add("missing-state", "%smoothed =", 'state_id = "Waveform.smoothed.state", ', '', 6)
    add("folding", "%smoothed =", 'metadata = {}', 'nodal.folded = false, metadata = {}', 6)
    add("simplification", "%delayed =", 'metadata = {}', 'nodal.simplified = true, metadata = {}', 6)
    add("time-state", "%now =", 'metadata = {}', 'state_id = "Waveform.now.state", metadata = {}', 6)
    add("effect-state", '"nodal.analog_bound_step"', 'metadata = {}', 'state_id = "Waveform.step.state", metadata = {}', 6)
    add("effect-fold", '"nodal.analog_bound_step"', 'metadata = {}', 'nodal.folded = true, metadata = {}', 6)
    add("dynamic-maximum", "%delayed =", '%limited, %changing, %seconds', '%limited, %changing, %changing', 7)
    add("analyses", "%smoothed =", '"ac", "dc", "initialization", "noise", "operating-point", "transient"', '"transient"', 8)
    add("effect-analysis", '"nodal.analog_bound_step"', 'analyses = ["transient"]', 'analyses = ["dc"]', 8)
    return cases


def assert_backend(text: str, source: bool = False) -> None:
    for function in ("transition", "slew", "absdelay"):
        if len(re.findall(rf"\b{function}\(", text)) != 1:
            raise AssertionError(f"{function} state duplicated or missing:\n{text}")
    for token in ("$abstime", "$bound_step(", "real waveform_"):
        if token not in text:
            raise AssertionError(f"missing backend token {token}:\n{text}")
    transition_assignment = re.search(r"(waveform_\d+) = transition\(", text)
    if transition_assignment is None:
        raise AssertionError("transition is not materialized")
    shared = transition_assignment.group(1)
    if f"({shared} + {shared})" not in text:
        raise AssertionError(f"repeated source uses did not share state: {text}")
    if not source and "$bound_step(ZERO);" not in text:
        raise AssertionError("zero step request was changed instead of retaining target semantics")


def run(nodalc: str, translate: str, source: Path | None = None) -> int:
    with tempfile.TemporaryDirectory(prefix="nodal-waveform-") as temporary:
        directory = Path(temporary)
        authored = FIXTURE.read_text(encoding="utf-8")
        optimized = invoke([nodalc, PIPELINE, str(FIXTURE)])
        for operator in OPERATORS:
            if optimized.count(f'"nodal.analog_{operator}"') != 1:
                raise AssertionError(f"optimization erased or duplicated {operator}")
        target = directory / "optimized.mlir"
        target.write_text(optimized, encoding="utf-8")
        repeated = invoke([nodalc, PIPELINE, str(target)])
        if optimized != repeated:
            raise AssertionError("waveform optimization is not deterministic/idempotent")
        before = invoke([translate, "--nodal-to-verilog-a", str(FIXTURE)])
        after = invoke([translate, "--nodal-to-verilog-a", str(target)])
        assert_backend(before)
        assert_backend(after)
        if before != after:
            raise AssertionError("pure-constant optimization altered waveform emission")
        cases = mutations(authored)
        for label, fixture, diagnostic in cases:
            invalid = directory / f"invalid-{label}.mlir"
            invalid.write_text(fixture, encoding="utf-8")
            invoke([nodalc, str(invalid)], diagnostic)
            invoke([nodalc, PIPELINE, str(invalid)], diagnostic)
        # Unused filter states and effect-only operations still execute.
        unused = directory / "unused.mlir"
        unused.write_text("".join(line for line in authored.splitlines(keepends=True)
                                  if '"nodal.contribute"' not in line), encoding="utf-8")
        unused_output = invoke([translate, "--nodal-to-verilog-a", str(unused)])
        for token in ("transition(", "slew(", "absdelay(", "$bound_step("):
            if unused_output.count(token) != 1:
                raise AssertionError(f"unused state/effect disappeared: {token}")
        # Generated names may not collide with authored module identifiers.
        collision = directory / "collision.mlir"
        collision.write_text(authored.replace('name = "p"', 'name = "waveform_0"'), encoding="utf-8")
        collision_output = invoke([translate, "--nodal-to-verilog-a", str(collision)])
        if "real waveform_0;" in collision_output or "real waveform_1;" not in collision_output:
            raise AssertionError("waveform temporary collides with authored name")
        if source is not None:
            invoke([nodalc, PIPELINE, str(source)])
            assert_backend(invoke([translate, "--nodal-to-verilog-a", str(source)]), source=True)
        print(f"Increment 36 native matrix: PASS ({len(cases)} negative cases, two verifier paths)")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", required=True)
    parser.add_argument("--translate", required=True)
    parser.add_argument("--source", type=Path)
    arguments = parser.parse_args()
    return run(arguments.nodalc, arguments.translate, arguments.source)


if __name__ == "__main__":
    raise SystemExit(main())
