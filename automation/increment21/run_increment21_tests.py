#!/usr/bin/env python3
"""Run Increment 21 native gate-pipeline positive and negative fixtures."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

STAGES = (
    "construction",
    "hierarchy",
    "connectivity",
    "type-shape",
    "parameter-loop",
    "enum-fsm",
    "domain",
    "protocol-pipeline",
    "memory-effect",
    "analog-mixed",
    "target-capability",
)

NEGATIVE = (
    ("increment21-invalid-construction.mlir", "construction", "NODAL-VERIFY-CONSTRUCTION-001"),
    ("increment21-invalid-hierarchy.mlir", "hierarchy", "NODAL-VERIFY-HIERARCHY-002"),
    ("increment21-invalid-driver.mlir", "connectivity", "NODAL-VERIFY-DRIVER-001"),
    ("increment21-invalid-latch.mlir", "connectivity", "NODAL-VERIFY-LATCH-001"),
    ("increment21-invalid-cycle.mlir", "connectivity", "NODAL-VERIFY-CYCLE-001"),
    ("increment21-invalid-storage.mlir", "type-shape", "NODAL-VERIFY-STORAGE-001"),
    ("increment21-invalid-loop.mlir", "parameter-loop", "NODAL-VERIFY-LOOP-003"),
    ("increment21-invalid-domain.mlir", "domain", "NODAL-VERIFY-DOMAIN-001"),
    ("increment21-invalid-protocol.mlir", "protocol-pipeline", "NODAL-VERIFY-PROTOCOL-002"),
    ("increment21-invalid-memory.mlir", "memory-effect", "NODAL-VERIFY-MEMORY-002"),
    ("increment21-invalid-analog.mlir", "analog-mixed", "NODAL-VERIFY-ANALOG-002"),
    ("increment21-invalid-target.mlir", "target-capability", "NODAL-VERIFY-TARGET-002"),
)


def execute(arguments: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(arguments, text=True, capture_output=True, check=False)


def fail(message: str, result: subprocess.CompletedProcess[str] | None = None) -> int:
    print(f"NODAL-INC21-TEST: {message}", file=sys.stderr)
    if result is not None:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
    return 1


def main() -> int:
    if len(sys.argv) != 3:
        return fail("usage: run_increment21_tests.py <nodalc> <ir-directory>")
    nodalc = str(Path(sys.argv[1]).resolve())
    ir = Path(sys.argv[2]).resolve()
    valid = ir / "increment21-valid.mlir"

    checked = execute([nodalc, "--nodal-gate-check", str(valid)])
    if checked.returncode != 0:
        return fail("registered check pipeline rejected the valid fixture", checked)

    normalized = execute([nodalc, "--nodal-gate-normalize", str(valid)])
    if normalized.returncode != 0:
        return fail("transactional normalization rejected the valid fixture", normalized)
    for fragment in (
        "nodal.pipeline.normalized = true",
        "nodal.pipeline.version = 1",
        "nodal.pipeline.target = \"core\"",
        "nodal.pipeline.stages",
    ):
        if fragment not in normalized.stdout:
            return fail(f"normalized output lacks {fragment!r}", normalized)

    explicit = ",".join(
        f"nodal-verify-stage{{stage={stage} target=core}}" for stage in STAGES
    )
    staged = execute(
        [nodalc, f"--pass-pipeline=builtin.module({explicit})", str(valid)]
    )
    if staged.returncode != 0:
        return fail("explicit stage pipeline rejected the valid fixture", staged)

    for filename, stage, code in NEGATIVE:
        fixture = ir / filename
        result = execute(
            [
                nodalc,
                f"--pass-pipeline=builtin.module(nodal-verify-stage{{stage={stage} target=core}})",
                str(fixture),
            ]
        )
        if result.returncode == 0:
            return fail(f"{filename} unexpectedly passed {stage}", result)
        diagnostics = result.stdout + result.stderr
        if code not in diagnostics:
            return fail(f"{filename} lacks stable diagnostic {code}", result)

    recovery = execute([nodalc, "--nodal-gate-normalize", str(valid)])
    if recovery.returncode != 0 or recovery.stdout != normalized.stdout:
        return fail("valid compilation did not recover deterministically after failures", recovery)

    print("Increment 21 native staged verification tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
