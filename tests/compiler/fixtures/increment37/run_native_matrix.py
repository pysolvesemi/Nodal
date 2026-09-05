#!/usr/bin/env python3
"""Execute native event semantic checks; no numerical or target-lowering claim."""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def invoke(tool: Path, source: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(tool), *args, str(source)], text=True, capture_output=True, timeout=90, check=False)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def expected_backend_diagnostic(text: str, target: str) -> str:
    """Check profile routing before the unsupported procedural-lowering boundary."""
    profiles = re.findall(r'\bnodal\.backend\.profile\s*=\s*"([^"]+)"', text)
    require(len(profiles) <= 1, "fixture contains multiple requested backend profiles")
    requested = profiles[0] if profiles else None
    targets = {"--nodal-to-verilog-a": "verilog-a", "--nodal-to-verilog-ams": "verilog-ams"}
    require(target in targets, f"unrecognized fixture translation: {target}")
    require(requested is None or requested in targets.values(), f"unrecognized fixture profile: {requested}")
    if requested is not None and requested != targets[target]:
        return "NODAL-BACKEND-PROFILE-002"
    return "NODAL-BACKEND-CAPABILITY-001"


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
        for input_path in [valid] + ([source] if source else []):
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
                require(backend.returncode != 0 and expected in backend.stderr,
                        f"{target}: expected {expected} without partial output: {backend.stderr}")
                require(not backend.stdout.strip(), "capability rejection published partial HDL")
            count += 5
        for label, old, new, diagnostic in cases:
            require(old in baseline, f"missing mutation anchor {label}")
            invalid = directory / f"{label}.mlir"
            invalid.write_text(baseline.replace(old, new, 1), encoding="utf-8")
            for pipeline in [(), (PIPELINE,)]:
                result = invoke(nodalc, invalid, *pipeline)
                require(result.returncode != 0 and f"NODAL-ANALOG-{diagnostic}" in result.stderr,
                        f"{label} failed to diagnose {diagnostic}: {result.stderr}")
                count += 1
    print(f"Increment 37 native matrix: {count} checks passed (target lowering remains gated)")
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source.resolve() if args.source else None)


if __name__ == "__main__":
    main()
