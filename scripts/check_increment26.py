#!/usr/bin/env python3
"""Validate Increment 26: deterministic output and reproducibility contract."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


EXPECTED_FILES = (
    "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/ReproducibilityContractTests.scala",
    "docs/design-gates/NodalReproducibilityContract-DG-v1.0.md",
    "docs/implementation/increment26-reproducibility-contract.md",
    "tests/compiler/fixtures/increment26/manifest.json",
    "tests/compiler/test_increment26.py",
    "scripts/check_increment26.py",
    ".github/workflows/increment-26-reproducibility-contract.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment26.py",
    "scripts/finalize_increment26.py",
    ".github/workflows/increment-26-materialize.yml",
    ".github/workflows/increment-26-finalize.yml",
    ".github/workflows/increment-26-supervisor.yml",
)

EXPECTED_ARTIFACTS = [
    "construction.json",
    "source.mlir",
    "normalized.mlir",
    "output.va",
]

EXPECTED_INVENTORIES = [
    "shape_layout_storage",
    "materialization",
    "semantic_names",
    "expression_source_map",
    "check_inventory",
    "waivers",
    "domain_manifest",
    "cdc_rdc_report",
]


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def roadmap_revision(text: str) -> tuple[int, ...]:
    matches = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(matches) != 1:
        return ()
    try:
        return tuple(int(part) for part in matches[0].split("."))
    except ValueError:
        return ()


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC26-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem(
                    "NODAL-INC26-002",
                    f"temporary Increment 26 file remains: {relative}",
                )
            )

    contract = read(
        root / "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala",
        problems,
        "NODAL-INC26-003",
    )
    tests = read(
        root
        / "core/scala/testkit/test/src/nodal/internal/testkit/ReproducibilityContractTests.scala",
        problems,
        "NODAL-INC26-004",
    )
    gate = read(
        root / "docs/design-gates/NodalReproducibilityContract-DG-v1.0.md",
        problems,
        "NODAL-INC26-005",
    )
    implementation = read(
        root / "docs/implementation/increment26-reproducibility-contract.md",
        problems,
        "NODAL-INC26-006",
    )
    workflow = read(
        root / ".github/workflows/increment-26-reproducibility-contract.yml",
        problems,
        "NODAL-INC26-007",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC26-008",
    )

    require(
        contract,
        (
            'val Schema: String = "nodal.reproducibility"',
            "final case class ReproducibilityArtifact",
            "final case class ReproducibilityManifest",
            "final case class ReproducibilityBundle",
            "captureSnapshot",
            "case exception: ConstructionException",
            "constructionFailure(exception)",
            "snapshot.waivers",
            "canonicalSnapshot",
            "construction.json",
            "source.mlir",
            "normalized.mlir",
            "output.va",
            "shape_layout_storage",
            "materialization",
            "semantic_names",
            "expression_source_map",
            "check_inventory",
            "waivers",
            "domain_manifest",
            "cdc_rdc_report",
            "SHA-256",
            "StandardCharsets.UTF_8",
            "expression.operands.map(string)",
        ),
        problems,
        "NODAL-INC26-003",
        "reproducibility contract",
    )
    forbidden = (
        "Instant.now",
        "System.nanoTime",
        "currentTimeMillis",
        "UUID.randomUUID",
        "expression.operands.sorted",
        "snapshot.analogRegions.sortBy",
        "region.expressions.sortBy",
        "region.contributions.sortBy",
    )
    for fragment in forbidden:
        if fragment in contract:
            problems.append(
                Problem(
                    "NODAL-INC26-003",
                    f"reproducibility contract contains nondeterministic or semantic-order-changing fragment: {fragment}",
                )
            )

    require(
        tests,
        (
            "canonical artifacts survive repeated construction and valid traversal orders",
            "manifest retains deterministic inventories and empty-or-explicit reports",
            "construction failures use declared result channel",
            "topLevelArrayObjectCount",
            "fixture-cdc-waiver",
            "verified MLIR HDL and manifest are byte-identical across work directories",
            "ReproducibilityContract.captureSnapshot",
            "NODAL_NODALC",
            "NODAL_TRANSLATE",
            "fixture-cdc-waiver",
            "permuted",
            "analogRegions = snapshot.analogRegions",
        ),
        problems,
        "NODAL-INC26-004",
        "reproducibility tests",
    )

    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-reproducibility",
            "**Scope:** public-api",
            "**Public API:** unchanged at 0.3",
            "Ordered expression operands",
            "An unavailable category is represented by a deterministic empty array",
            "publishes no accepted HDL or reproducibility manifest",
        ),
        problems,
        "NODAL-INC26-005",
        "design gate",
    )
    require(
        implementation,
        (
            "construction.json",
            "source.mlir",
            "normalized.mlir",
            "output.va",
            "semantically unordered collection",
            "Public API v0.3 is unchanged",
        ),
        problems,
        "NODAL-INC26-006",
        "implementation note",
    )
    require(
        workflow,
        (
            "increment-26/reproducibility-contract",
            "check_increment26.py",
            "NODAL_NODALC",
            "NODAL_TRANSLATE",
            "./nodal core native",
            "./nodal core scala",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC26-007",
        "permanent workflow",
    )
    if "contents: write" in workflow or "materialize_increment26" in workflow:
        problems.append(
            Problem(
                "NODAL-INC26-007",
                "permanent Increment 26 workflow must be read-only",
            )
        )

    manifest_path = root / "tests/compiler/fixtures/increment26/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC26-008"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC26-008", f"invalid manifest: {exc}"))
        manifest = {}

    if manifest.get("increment") != 26:
        problems.append(Problem("NODAL-INC26-008", "manifest increment must be 26"))
    if manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC26-008", "public API must remain 0.3"))
    if manifest.get("contract") != "nodal.reproducibility":
        problems.append(Problem("NODAL-INC26-008", "manifest contract identity mismatch"))
    if manifest.get("contract_version") != 1:
        problems.append(Problem("NODAL-INC26-008", "manifest contract version must be 1"))
    if manifest.get("artifacts") != EXPECTED_ARTIFACTS:
        problems.append(Problem("NODAL-INC26-008", "manifest artifact inventory mismatch"))
    if manifest.get("inventories") != EXPECTED_INVENTORIES:
        problems.append(Problem("NODAL-INC26-008", "manifest evidence inventory mismatch"))

    revision = roadmap_revision(roadmap)
    increment25_done = (
        "- [x] **Increment 25 — RC filter end-to-end vertical slice**" in roadmap
    )
    increment26_open = (
        "- [ ] **Increment 26 — Deterministic output and reproducibility contract**"
        in roadmap
    )
    increment26_done = (
        "- [x] **Increment 26 — Deterministic output and reproducibility contract**"
        in roadmap
    )
    increment27_open = "- [ ] **Increment 27 — Natures and disciplines**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})

    if not increment25_done:
        problems.append(Problem("NODAL-INC26-008", "Increment 25 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment26_open or revision < (1, 31):
            problems.append(
                Problem(
                    "NODAL-INC26-008",
                    "pre-evidence state must leave Increment 26 unchecked at revision 1.31 or later",
                )
            )
    elif status == "validated-reproducibility-contract":
        if not increment26_done or revision < (1, 32):
            problems.append(
                Problem(
                    "NODAL-INC26-008",
                    "validated state must close Increment 26 at revision 1.32 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC26-008",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(
            Problem("NODAL-INC26-008", f"unexpected manifest status: {status!r}")
        )
    if not increment27_open:
        problems.append(Problem("NODAL-INC26-008", "Increment 27 must remain unchecked"))

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            f"Increment 26 check failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    print("Increment 26 reproducibility contract check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
