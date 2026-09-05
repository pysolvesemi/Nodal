#!/usr/bin/env python3
"""Execute native event semantic checks; including event-target emission; no numerical simulation claim."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def invoke(tool: Path, source: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(tool), *args, str(source)], text=True, capture_output=True, timeout=90, check=False)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def expected_backend_diagnostic(text: str, target: str) -> str | None:
    """Check profile routing before event-target lowering."""
    profiles = re.findall(r'\bnodal\.backend\.profile\s*=\s*"([^"]+)"', text)
    require(len(profiles) <= 1, "fixture contains multiple requested backend profiles")
    requested = profiles[0] if profiles else None
    targets = {"--nodal-to-verilog-a": "verilog-a", "--nodal-to-verilog-ams": "verilog-ams"}
    require(target in targets, f"unrecognized fixture translation: {target}")
    require(requested is None or requested in targets.values(), f"unrecognized fixture profile: {requested}")
    if requested is not None and requested != targets[target]:
        return "NODAL-BACKEND-PROFILE-002"
    return None


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    baseline = (ROOT / "core/compiler/test/IR/analog-events.mlir").read_text(encoding="utf-8")
    cases = [
        ("boolean-enable", 'type = i64, default_value = 0 : i64',
         'type = i1, default_value = false', "037-003"),
        ("contract", 'contract = "increment37"', 'contract = "forged"', "037-002"),
        ("arity", 'slot = 0 : i64', 'slot = 1 : i64', "037-002"),
        ("ownership", 'owner = "EventTop", contract = "increment37"',
         'owner = "Other", contract = "increment37"', "037-005"),
        ("nonfinite", 'value = "1.0 V"', 'value = "1.0e309 V"', "037-004"),
        ("zero-divisor", 'value = "1.0 V"', 'value = "analog_div(1.0 V,0.0)"', "037-004"),
        ("dimension", 'value = "1.0 V", kind = "real", dimension = "voltage"',
         'value = "1.0 V", kind = "real", dimension = "time"', "037-003"),
        ("negative-tolerance", 'value = "0.0 s"', 'value = "-1.0 s"', "037-004"),
        ("forged-reads", 'event_reads = []', 'event_reads = ["EventTop.held"]', "037-005"),
        ("analysis", 'analyses = ["dc", "tran"]', 'analyses = ["tran", "tran"]', "037-008"),
        ("grammar", 'value = "1.0 V"', 'value = "foreign_expression()"', "037-009"),
    ]
    cases += [
        ("rhs-injection", 'metadata = {value = "1.0"}',
         'metadata = {value = "foreign_call()"}', "037-010"),
        ("rhs-dimension-forgery", 'metadata = {value = "1.0"}',
         'metadata = {value = "1.0 V"}', "037-010"),
        ("rhs-read-inventory", 'metadata = {value = "1.0"}',
         'metadata = {value = "EventTop.held"}', "037-010"),
    ]
    alternatives = baseline.splitlines()
    or_line = next(line for line in alternatives if '"nodal.analog_event_or"' in line)
    cases.append(("single-operand-or", or_line,
                  or_line.replace('(%cross, %timer)', '(%cross)').replace(
                      '(!nodal.analog_event, !nodal.analog_event)', '(!nodal.analog_event)'), "037-006"))
    initial_line = next(line for line in alternatives if '%initial =' in line)
    nested = baseline.replace(initial_line + "\n", "", 1).replace(
        '          "nodal.analog_assign"', initial_line + "\n          \"nodal.analog_assign\"", 1)
    cases.append(("nested-event", baseline, nested, "037-007"))
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-increment37-") as temp:
        directory = Path(temp)
        valid = directory / "valid.mlir"
        valid.write_text(baseline, encoding="utf-8")
        inputs = [valid, ROOT / "core/compiler/test/IR/analog-events-held.mlir"]
        if source:
            inputs += [source, Path(str(source) + ".held.mlir"), Path(str(source) + ".controlled.mlir")]
            require(all(path.is_file() for path in inputs), "missing public-source companion witness")
        for input_path in inputs:
            plain = invoke(nodalc, input_path)
            require(plain.returncode == 0, plain.stderr)
            optimized = invoke(nodalc, input_path, PIPELINE)
            require(optimized.returncode == 0, optimized.stderr)
            normalized = directory / "normalized.mlir"
            normalized.write_text(optimized.stdout, encoding="utf-8")
            repeated = invoke(nodalc, normalized, PIPELINE)
            require(repeated.returncode == 0, repeated.stderr)
            require(optimized.stdout == repeated.stdout, "event optimization is not idempotent")
            for operation in ["cross", "above", "timer", "initial_step", "final_step", "event_or", "on"]:
                spelling = f'"nodal.analog_{operation}"'
                require(plain.stdout.count(spelling) == optimized.stdout.count(spelling), f"lost or duplicated {operation}")
            for target in ("--nodal-to-verilog-a", "--nodal-to-verilog-ams"):
                backend = invoke(translate, input_path, target)
                expected = expected_backend_diagnostic(input_path.read_text(encoding="utf-8"), target)
                if expected:
                    require(backend.returncode != 0 and expected in backend.stderr,
                            f"{target}: expected {expected}: {backend.stderr}")
                    require(not backend.stdout.strip(), "rejection published partial HDL")
                else:
                    require(backend.returncode == 0, backend.stderr)
                    require("@(" in backend.stdout, "event statements were lost in target emission")
                    if input_path == valid:
                        require("@(cross(" in backend.stdout and "@(final_step" in backend.stdout,
                                "primitive event statements were lost")
                    if '"nodal.analog_held_read"' in plain.stdout:
                        require("transition(event_" in backend.stdout, "held read did not feed the continuous filter")
                        require(backend.stdout.count("  analog begin\n") == 1,
                                "held updates and continuous evaluation have multiple target processes")
                        require(backend.stdout.index("@(") < backend.stdout.index(" = transition("),
                                "continuous filter evaluation precedes event updates")
                    after = invoke(translate, normalized, target)
                    require(after.returncode == 0, after.stderr)
                    require(backend.stdout == after.stdout,
                            "optimization changed the accepted event target")
                    require(" or " in backend.stdout and "begin : event_" in backend.stdout,
                            "composition or controlled statements were not emitted")
            count += 5
        held_text = (ROOT / "core/compiler/test/IR/analog-events-held.mlir").read_text()
        for label, old, new in [
            ("held-owner", 'variable = "EventTop.held", owner = "EventTop"', 'variable = "EventTop.held", owner = "Other"'),
            ("held-binding", 'variable = "EventTop.held"', 'variable = "EventTop.missing"'),
        ]:
            invalid = directory / f"{label}.mlir"
            require(old in held_text, f"missing held proof mutation anchor: {label}")
            invalid.write_text(held_text.replace(old, new, 1))
            for pipeline in [(), (PIPELINE,)]:
                result = invoke(nodalc, invalid, *pipeline)
                require(result.returncode != 0 and "NODAL-ANALOG-037-009" in result.stderr,
                        "forged held-storage proof accepted: " + result.stderr)
                count += 1
        ordinary = directory / "ordinary.mlir"
        ordinary.write_text((ROOT / "core/compiler/test/IR/analog-procedural.mlir").read_text().replace(
            "module {", 'module attributes {nodal.target.profile = "analog"} {', 1))
        for target in ("--nodal-to-verilog-a", "--nodal-to-verilog-ams"):
            result = invoke(translate, ordinary, target)
            require(result.returncode != 0 and "NODAL-BACKEND-CAPABILITY-001" in result.stderr,
                    "ordinary procedures accidentally bypassed the capability gate: " + result.stderr)
            require(not result.stdout.strip(), "unsupported procedure published partial HDL")
            count += 1
        for label, old, new, diagnostic in cases:
            require(old in baseline, f"missing mutation anchor {label}")
            invalid = directory / f"{label}.mlir"
            invalid.write_text(baseline.replace(old, new, 1), encoding="utf-8")
            for pipeline in [(), (PIPELINE,)]:
                result = invoke(nodalc, invalid, *pipeline)
                require(result.returncode != 0 and f"NODAL-ANALOG-{diagnostic}" in result.stderr,
                        f"{label} failed to diagnose {diagnostic}: {result.stderr}")
                count += 1
    print(f"Increment 37 native matrix: {count} checks passed (event target structural acceptance)")
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source.resolve() if args.source else None)
    review = [sys.executable, str(Path(__file__).with_name("run_review_matrix.py")),
              "--nodalc", str(args.nodalc.resolve()), "--translate", str(args.translate.resolve())]
    if args.source:
        review += ["--source", str(args.source.resolve())]
    subprocess.run(review, check=True, timeout=300)


if __name__ == "__main__":
    main()
