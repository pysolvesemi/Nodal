#!/usr/bin/env python3
"""Validate Increment 133 continuous-time public semantic contracts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = Path("core/scala/api/src/nodal/ContinuousTimeCandidateApi.scala")
INTERNAL = Path("examples/continuousTimeApi/src/ContinuousTimeCandidates.scala")
EXTERNAL = Path("examples/continuousTimeExternal/src/ReusablePhysicalComponents.scala")
CHECKPOINT_GATE = Path("docs/design-gates/NodalEquationComponentApi-DG-v0.1.md")
COMPLETE_GATE = Path("docs/design-gates/NodalContinuousTimeApi-DG-v0.1.md")
CHECKPOINT_SURFACE = Path("core/scala/api/equation-component-api-v0.1.json")
SURFACE = Path("core/scala/api/public-api-continuous-time-v0.1.json")
DIAGNOSTICS = Path("core/scala/api/public-api-continuous-time-diagnostics-v0.1.json")
MIGRATION = Path("docs/migrations/public-api-v0.3-to-continuous-time-v0.1.md")
MANIFEST = Path("tests/api/fixtures/increment133/manifest.json")
SEMANTIC = Path("tests/api/fixtures/increment133/semantic-contracts.json")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")

EXPECTED_DIAGNOSTICS = {
    *(f"NODAL-ANALOG-133-{index:03d}" for index in range(1, 21)),
    *(f"NODAL-ANALOG-133-TYPE-{index:03d}" for index in range(1, 5)),
}


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(text: str, fragments: tuple[str, ...], problems: list[Problem], code: str) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"missing required fragment: {fragment}"))


def load_json(path: Path, problems: list[Problem], code: str) -> object:
    text = read(path, problems, code)
    if not text:
        return {}
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}


def validate_files(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    api = read(root / API, problems, "NODAL-INC133-001")
    internal = read(root / INTERNAL, problems, "NODAL-INC133-002")
    external = read(root / EXTERNAL, problems, "NODAL-INC133-003")
    checkpoint = read(root / CHECKPOINT_GATE, problems, "NODAL-INC133-004")
    complete = read(root / COMPLETE_GATE, problems, "NODAL-INC133-005")
    migration = read(root / MIGRATION, problems, "NODAL-INC133-006")
    roadmap = read(root / ROADMAP, problems, "NODAL-INC133-007")
    manifest = load_json(root / MANIFEST, problems, "NODAL-INC133-008")
    checkpoint_surface = load_json(root / CHECKPOINT_SURFACE, problems, "NODAL-INC133-009")
    surface = load_json(root / SURFACE, problems, "NODAL-INC133-010")
    diagnostics = load_json(root / DIAGNOSTICS, problems, "NODAL-INC133-011")
    semantic = load_json(root / SEMANTIC, problems, "NODAL-INC133-012")
    if problems:
        return problems

    require(api, ("enum AnalysisKind:", "final case class EquationId", "final case class ContributionId", "def equations(", "def equation(", "def initialEquations(", "def initialEquation(", "def contributions(", "def contribution(", "def analogProcedure(", "final case class PhysicalComponentContract", "def physicalComponent(", "def localBalance(", "final class Branch", "def structuralParameter", "final class AnalogState", "def reinitialize(", "final case class EventTolerance", "def crossing(", "object AnalysisContext:", "object EnvironmentContext:", "final case class NoiseId", "def whiteNoise(", "def flickerNoise(", "def tableNoise(", "final case class ModelValidityEnvelope", "def modelValidity(", "sealed trait SolverHint", "def solverHints("), problems, "NODAL-INC133-013")
    require(internal, ("physicalComponent(", "localBalance(", "equations:", "equation(", "contributions:", "path.flow <+", "contribution(", "initialEquations:", "initialEquation(", "analogProcedure:", "analogState(", "crossing(", "reinitialize(", "AnalysisContext.active", "EnvironmentContext.temperature", "whiteNoise(", "flickerNoise(", "tableNoise(", "modelValidity(", "solverHints("), problems, "NODAL-INC133-014")
    require(external, ("package external.continuoustime", "import nodal.*", "abstract class PartialTwoTerminal", "final class Resistor", "final class Capacitor", "ComponentCompleteness.Partial", "ComponentCompleteness.Concrete", "equations:", "contributions:"), problems, "NODAL-INC133-015")
    for forbidden in ("nodal.internal", "nodal.frontend", "nodal.compiler", "CandidateRuntime", "ConstructionKernel"):
        if forbidden in external:
            problems.append(Problem("NODAL-INC133-016", f"external fixture uses forbidden implementation surface: {forbidden}"))

    for gate, title in ((checkpoint, "Nodal Equation and Physical-Component API"), (complete, "Nodal Continuous-Time Semantic API")):
        require(gate, (title, "**Status:** Approved", "**Scope:** public-api", "## Accepted alternatives", "## Rejected alternatives", "## Compatibility impact", "## Required tests", "## Approval evidence"), problems, "NODAL-INC133-017")

    require(migration, ("additive extension", "equations:", "contributions:", "Execution status"), problems, "NODAL-INC133-018")

    if not isinstance(manifest, dict) or manifest.get("increment") != 133:
        problems.append(Problem("NODAL-INC133-019", "manifest identity is invalid"))
        return problems
    status = manifest.get("status")
    if status not in {"approved-awaiting-evidence", "validated-analog-semantic-api"}:
        problems.append(Problem("NODAL-INC133-020", f"invalid manifest status: {status}"))
    for field in ("frontend_behavior_inert", "equation_normalization_inert", "residual_dae_inert", "solver_behavior_inert", "backend_behavior_inert"):
        if manifest.get(field) is not True:
            problems.append(Problem("NODAL-INC133-021", f"manifest must keep {field}=true"))
    checkpoint_data = manifest.get("equation_component_checkpoint")
    if not isinstance(checkpoint_data, dict) or checkpoint_data.get("approved") is not True:
        problems.append(Problem("NODAL-INC133-022", "equation/component checkpoint is not approved"))
    elif checkpoint_data.get("unblocks_increment") != 32:
        problems.append(Problem("NODAL-INC133-023", "checkpoint must unblock Increment 32"))
    if not isinstance(checkpoint_surface, dict) or checkpoint_surface.get("api_version") != "equation-component-v0.1" or checkpoint_surface.get("status") != "approved" or checkpoint_surface.get("unblocks_increment") != 32:
        problems.append(Problem("NODAL-INC133-024", "equation/component checkpoint surface is invalid"))
    if not isinstance(surface, dict) or surface.get("api_version") != "continuous-time-v0.1":
        problems.append(Problem("NODAL-INC133-025", "public surface identity is invalid"))
    elif surface.get("status") not in {"approved-awaiting-evidence", "validated-analog-semantic-api"}:
        problems.append(Problem("NODAL-INC133-026", "public surface status is invalid"))

    diagnostic_entries = diagnostics.get("diagnostics") if isinstance(diagnostics, dict) else None
    diagnostic_codes = {entry.get("code") for entry in diagnostic_entries or [] if isinstance(entry, dict)}
    missing_diagnostics = sorted(EXPECTED_DIAGNOSTICS - diagnostic_codes)
    if missing_diagnostics:
        problems.append(Problem("NODAL-INC133-027", "missing diagnostics: " + ", ".join(missing_diagnostics)))
    semantic_entries = semantic.get("cases") if isinstance(semantic, dict) else None
    semantic_codes = {entry.get("code") for entry in semantic_entries or [] if isinstance(entry, dict)}
    expected_semantic = {f"NODAL-ANALOG-133-{index:03d}" for index in range(1, 21)}
    if semantic_codes != expected_semantic:
        problems.append(Problem("NODAL-INC133-028", "semantic fixture inventory does not exactly cover diagnostics 001-020"))

    negatives = manifest.get("scala_type_negative")
    if not isinstance(negatives, list) or len(negatives) != 4:
        problems.append(Problem("NODAL-INC133-029", "exactly four type-negative fixtures are required"))
    else:
        for entry in negatives:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC133-030", "negative entry is not an object"))
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            source = read(path, problems, "NODAL-INC133-031")
            if source.count(f"diagnostic-anchor: {code}") != 1:
                problems.append(Problem("NODAL-INC133-032", f"negative fixture lacks one anchor for {code}: {path}"))

    for path in (root / ".github/workflows/_increment133_payload.yml", root / ".github/workflows/_increment133_recover_payload.yml"):
        if path.exists():
            problems.append(Problem("NODAL-INC133-033", f"temporary writable workflow remains in accepted tree: {path}"))

    increment32 = "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
    if increment32 not in roadmap:
        problems.append(Problem("NODAL-INC133-034", "Increment 32 must remain unchecked until Increment 133 evidence closure merges"))
    increment133_open = "- [ ] **Increment 133 — Analog semantic API and analysis contract design gate**"
    increment133_closed = "- [x] **Increment 133 — Analog semantic API and analysis contract design gate**"
    if status == "approved-awaiting-evidence" and increment133_open not in roadmap:
        problems.append(Problem("NODAL-INC133-035", "pre-evidence roadmap must leave Increment 133 open"))
    if status == "validated-analog-semantic-api" and increment133_closed not in roadmap:
        problems.append(Problem("NODAL-INC133-036", "validated roadmap must close Increment 133"))
    if status == "validated-analog-semantic-api":
        validation = manifest.get("validation")
        required = ("implementation_pull_request", "accepted_head", "dedicated_workflow_run", "core_ci_run", "merge_commit", "post_merge_core_ci_run")
        if not isinstance(validation, dict) or any(not validation.get(key) for key in required):
            problems.append(Problem("NODAL-INC133-037", "validated manifest lacks complete accepted evidence"))
    return problems


def run_mill(root: Path, *targets: str) -> subprocess.CompletedProcess[str]:
    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    return subprocess.run([str(wrapper), *targets], cwd=root, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def validate_compile_contracts(root: Path) -> list[Problem]:
    problems: list[Problem] = []
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    positive = run_mill(root, "examples.continuousTimeExternal.compile", "examples.continuousTimeApi.compile")
    if positive.returncode != 0:
        return [Problem("NODAL-INC133-038", "positive continuous-time compilation failed:\n" + positive.stdout[-8000:])]
    injected = root / "examples/continuousTimeApi/src/__Increment133Negative.scala"
    if injected.exists():
        return [Problem("NODAL-INC133-039", f"refusing to overwrite {injected}")]
    try:
        for entry in manifest["scala_type_negative"]:
            source = root / entry["path"]
            injected.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
            completed = run_mill(root, "examples.continuousTimeApi.compile")
            if completed.returncode == 0:
                problems.append(Problem("NODAL-INC133-040", f"negative fixture compiled successfully: {entry['path']}"))
            elif injected.name not in completed.stdout:
                problems.append(Problem("NODAL-INC133-041", f"failure did not identify injected fixture: {entry['path']}"))
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)
    restored = run_mill(root, "examples.continuousTimeExternal.compile", "examples.continuousTimeApi.compile")
    if restored.returncode != 0:
        problems.append(Problem("NODAL-INC133-042", "positive compilation did not recover after negatives:\n" + restored.stdout[-8000:]))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--compile-negative", action="store_true")
    args = parser.parse_args(argv)
    problems = validate_files(args.root)
    if not problems and args.compile_negative:
        problems.extend(validate_compile_contracts(args.root.resolve()))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 133 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 133 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
