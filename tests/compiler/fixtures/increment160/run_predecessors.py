#!/usr/bin/env python3
"""Execute affected non-dispatchable predecessor witnesses for F-160 targeting.

The original workflow owners and complete full-CI jobs remain in place. This
entry point makes their affected source/runtime checks callable through the
existing dispatchable Core job without inventing a passing legacy workflow.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[4]

# These are the exact required report lines from the retained predecessor
# workflows, not a substitute implementation of their semantic checks.
REPORTS = (
    (
        "increment33",
        "nodal.increment33fixture.Increment33RuntimeCheck",
        (
            "variables=4",
            "assignments=5",
            "ProceduralTop.capture,ProceduralTop.update,ProceduralTop.repeat-update",
        ),
    ),
    (
        "increment34-runtime",
        "nodal.increment34fixture.Increment34RuntimeCheck",
        (
            "conditional_definite=true",
            "case_definite=true",
            "loop_definite=true",
            "static_definite=true",
            "missing_else=NODAL-ANALOG-034-004",
            "zero_trip=NODAL-ANALOG-034-004",
            "duplicate_case=NODAL-ANALOG-034-006",
            "break_scope=NODAL-ANALOG-034-010",
            "continue_scope=NODAL-ANALOG-034-011",
        ),
    ),
    (
        "increment34-construction",
        "nodal.increment34fixture.Increment34ConstructionCheck",
        (
            "public_conditional_snapshots=1",
            "public_case_snapshots=1",
            "public_missing_else=NODAL-ANALOG-034-004",
            "flat_assignments=0",
        ),
    ),
    (
        "increment35",
        "nodal.increment35fixture.Increment35ConstructionCheck",
        (
            "operator_count=3",
            "ddt_result_dimension=time^-1*voltage",
            "idt_fixed_initialization=fixed",
            "idt_solver_initialization=solver-selected",
            "analysis_inventory_exact=true",
            "owner_qualified=true",
            "equation_context=true",
            "contribution_context=true",
            "typed_initial_dimension=current*time",
            "state_ids_unique=true",
            "state_ids_stable=true",
            "operator_paths_stable=true",
            "outside_context=NODAL-ANALOG-035-001",
            "initial_context=NODAL-ANALOG-035-001",
            "procedural_context=NODAL-ANALOG-035-001",
            "initial_mismatch=NODAL-ANALOG-035-004",
        ),
    ),
)


def verify_report(report: Path, required: tuple[str, ...]) -> None:
    if not report.is_file() or not report.stat().st_size:
        raise ValueError(f"missing or empty predecessor report: {report}")
    text = report.read_text(encoding="utf-8")
    missing = [value for value in required if value not in text]
    if missing:
        raise ValueError(f"{report.name} lacks required predecessor results: {missing}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    manifest = out / "commands.json"
    env = {**os.environ, "NODAL_WORKSPACE": str(ROOT)}

    def execute(name: str, command: list[str]) -> None:
        stdout, stderr = out / f"{name}.stdout.log", out / f"{name}.stderr.log"
        start = time.monotonic_ns()
        record: dict[str, object] = {"name": name, "argv": command, "cwd": str(ROOT)}
        records.append(record)
        manifest.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
        with stdout.open("w") as output, stderr.open("w") as errors:
            result = subprocess.run(
                command, cwd=ROOT, env=env, stdout=output, stderr=errors,
                timeout=1200, check=False,
            )
        record.update(exit_code=result.returncode, wall_nanos=time.monotonic_ns() - start)
        manifest.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
        if result.returncode:
            raise RuntimeError(f"{name} failed with exit {result.returncode}; see {out}")

    try:
        for increment in (32, 33):
            execute(
                f"increment{increment}-compile",
                [sys.executable, str(ROOT / f"scripts/check_increment{increment}.py"), "--compile"],
            )
        for name, main_class, required in REPORTS:
            report = out / f"{name}.txt"
            if report.exists():
                raise ValueError(f"refusing to reuse an existing predecessor report: {report}")
            execute(name, [str(ROOT / "mill"), "-i", "examples.continuousTimeApi.runMain", main_class, str(report)])
            verify_report(report, required)
        print("F-160 affected predecessor source/runtime witnesses: PASS")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"F-160 predecessor validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
