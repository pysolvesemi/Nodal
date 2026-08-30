#!/usr/bin/env python3
"""Validate Increment 32 equation and contribution semantics."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("tests/compiler/fixtures/increment32/manifest.json")
DESIGN_GATE = Path("docs/design-gates/NodalAnalogEquationContribution-DG-v1.0.md")
IMPLEMENTATION = Path("docs/implementation/increment32-equation-contribution-semantics.md")
SCALA_RUNTIME = Path("core/scala/api/src/nodal/AnalogEquationRuntime.scala")
NATIVE_RUNTIME = Path("core/native/include/nodal/AnalogEquationRuntime.h")
SCALA_WITNESS = Path(
    "examples/continuousTimeApi/src/nodal/increment32fixture/Increment32RuntimeCheck.scala"
)
NATIVE_WITNESS = Path(
    "tests/compiler/fixtures/increment32/analog_equation_runtime_test.cpp"
)
WORKFLOW = Path(".github/workflows/increment-32-equation-contribution-semantics.yml")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")
INCREMENT31 = Path("tests/compiler/fixtures/increment31/manifest.json")
INCREMENT133 = Path("tests/api/fixtures/increment133/manifest.json")


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def read(root: Path, path: Path, problems: list[Problem], code: str) -> str:
    candidate = root / path
    if not candidate.is_file():
        problems.append(Problem(code, f"missing file: {path}"))
        return ""
    return candidate.read_text(encoding="utf-8")


def load_json(
    root: Path, path: Path, problems: list[Problem], code: str
) -> dict[str, object]:
    text = read(root, path, problems, code)
    if not text:
        return {}
    try:
        value = json.loads(text)
    except json.JSONDecodeError as error:
        problems.append(Problem(code, f"invalid JSON in {path}: {error}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"JSON root must be an object: {path}"))
        return {}
    return value


def require_tokens(
    text: str,
    tokens: tuple[str, ...],
    problems: list[Problem],
    code: str,
    label: str,
) -> None:
    missing = [token for token in tokens if token not in text]
    if missing:
        problems.append(
            Problem(code, f"{label} is missing required tokens: {', '.join(missing)}")
        )


def validate_files(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    manifest = load_json(root, MANIFEST, problems, "NODAL-INC32-001")
    gate = read(root, DESIGN_GATE, problems, "NODAL-INC32-002")
    implementation = read(root, IMPLEMENTATION, problems, "NODAL-INC32-003")
    scala_runtime = read(root, SCALA_RUNTIME, problems, "NODAL-INC32-004")
    native_runtime = read(root, NATIVE_RUNTIME, problems, "NODAL-INC32-005")
    scala_witness = read(root, SCALA_WITNESS, problems, "NODAL-INC32-006")
    native_witness = read(root, NATIVE_WITNESS, problems, "NODAL-INC32-007")
    workflow = read(root, WORKFLOW, problems, "NODAL-INC32-008")
    roadmap = read(root, ROADMAP, problems, "NODAL-INC32-009")
    increment31 = load_json(root, INCREMENT31, problems, "NODAL-INC32-010")
    increment133 = load_json(root, INCREMENT133, problems, "NODAL-INC32-011")
    if problems:
        return problems

    if manifest.get("schema") != 1 or manifest.get("increment") != 32:
        problems.append(Problem("NODAL-INC32-012", "manifest identity is invalid"))
    status = manifest.get("status")
    if status not in {
        "implemented-awaiting-evidence",
        "validated-equation-contribution-semantics",
    }:
        problems.append(Problem("NODAL-INC32-013", f"invalid manifest status: {status}"))

    semantics = manifest.get("semantics")
    required_true = (
        "analog_regions",
        "unordered_equations",
        "initial_equations_distinct",
        "authored_sides_preserved",
        "stable_equation_identity",
        "physical_dimensions_preserved",
        "guards_preserved",
        "analysis_applicability_preserved",
        "continuity_preserved",
        "potential_flow_contributions",
        "additive_accumulation",
        "source_order_independent",
        "procedural_assignment_distinct",
    )
    if not isinstance(semantics, dict):
        problems.append(Problem("NODAL-INC32-014", "manifest semantics must be an object"))
    else:
        for field in required_true:
            if semantics.get(field) is not True:
                problems.append(
                    Problem("NODAL-INC32-015", f"semantic contract must keep {field}=true")
                )
        for field in ("causal_orientation", "unsafe_division", "last_writer_wins"):
            if semantics.get(field) is not False:
                problems.append(
                    Problem("NODAL-INC32-016", f"semantic contract must keep {field}=false")
                )
        if semantics.get("canonical_residual_intent") != "lhs-minus-rhs-equals-zero":
            problems.append(
                Problem("NODAL-INC32-017", "canonical residual intent is invalid")
            )

    if increment31.get("status") != "validated-potential-flow-access":
        problems.append(Problem("NODAL-INC32-018", "Increment 31 is not fully validated"))
    checkpoint = increment133.get("equation_component_checkpoint")
    if (
        increment133.get("status") != "validated-analog-semantic-api"
        or not isinstance(checkpoint, dict)
        or checkpoint.get("approved") is not True
        or checkpoint.get("unblocks_increment") != 32
    ):
        problems.append(
            Problem(
                "NODAL-INC32-019",
                "Increment 133 equation/component checkpoint is not fully closed",
            )
        )

    require_tokens(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-ir",
            "unordered simultaneous constraint",
            "initialization-only",
            "last-writer-wins",
            "NODAL-ANALOG-032-*",
            "Increment 33 owns variables and ordered procedural assignment",
        ),
        problems,
        "NODAL-INC32-020",
        "design gate",
    )
    require_tokens(
        implementation,
        (
            "Implemented on the increment branch",
            "authored expression sides",
            "source-order permutations",
            "Increment 33 owns local analog variables",
        ),
        problems,
        "NODAL-INC32-021",
        "implementation note",
    )
    require_tokens(
        scala_runtime,
        (
            "private[nodal] object AnalogEquationRuntime",
            "enum RegionKind",
            "case Equation, InitialEquation, Contribution, Procedural",
            "final case class ResidualIntent",
            "causallyOriented: Boolean = false",
            "divided: Boolean = false",
            "final case class ContributionBucket",
            "def recordEquation(",
            "def recordContribution(",
            "NODAL-ANALOG-032-012",
            "sortBy(_.identity.value)",
        ),
        problems,
        "NODAL-INC32-022",
        "Scala runtime",
    )
    require_tokens(
        native_runtime,
        (
            "namespace nodal::analog",
            "enum class RegionKind",
            "struct ResidualIntent",
            "bool causallyOriented = false",
            "bool divided = false",
            "class Recorder final",
            "recordEquation(",
            "recordContribution(",
            "NODAL-ANALOG-032-012",
            "std::map<ContributionTarget",
        ),
        problems,
        "NODAL-INC32-023",
        "native runtime",
    )
    require_tokens(
        scala_witness,
        (
            "source order must not affect the canonical snapshot",
            "authoredLeft.rendered",
            "authoredRight.rendered",
            "!first.equations.head.residual.causallyOriented",
            "NODAL-ANALOG-032-004",
            "NODAL-ANALOG-032-012",
            "NODAL_INC32_SCALA_WITNESS_PASS",
        ),
        problems,
        "NODAL-INC32-024",
        "Scala witness",
    )
    require_tokens(
        native_witness,
        (
            "build({\"source-a\", \"source-b\"})",
            "build({\"source-b\", \"source-a\"})",
            "authoredLeft.rendered",
            "authoredRight.rendered",
            "NODAL-ANALOG-032-012",
        ),
        problems,
        "NODAL-INC32-025",
        "native witness",
    )

    open32 = "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
    closed32 = "- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
    open33 = "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    if status == "implemented-awaiting-evidence" and open32 not in roadmap:
        problems.append(
            Problem("NODAL-INC32-026", "pre-evidence roadmap must leave Increment 32 open")
        )
    if status == "validated-equation-contribution-semantics" and closed32 not in roadmap:
        problems.append(
            Problem("NODAL-INC32-027", "validated roadmap must close Increment 32")
        )
    if open33 not in roadmap:
        problems.append(
            Problem("NODAL-INC32-028", "Increment 33 must remain unchecked during closure")
        )
    if status == "validated-equation-contribution-semantics":
        validation = manifest.get("validation")
        required_evidence = (
            "implementation_pull_request",
            "accepted_head",
            "dedicated_workflow_run",
            "core_ci_run",
            "implementation_merge",
            "post_merge_core_ci_run",
            "closure_pull_request",
            "closure_validation_head",
            "closure_core_ci_run",
        )
        if not isinstance(validation, dict) or any(
            not validation.get(field) for field in required_evidence
        ):
            problems.append(
                Problem("NODAL-INC32-029", "validated manifest lacks complete evidence")
            )

    require_tokens(
        workflow,
        (
            "name: Increment 32 Equation and Contribution Semantics",
            "contents: read",
            "python3 scripts/check_increment32.py --compile",
            "python3 -m unittest discover -s tests/compiler -p 'test_increment32.py'",
        ),
        problems,
        "NODAL-INC32-030",
        "workflow",
    )
    if "contents: write" in workflow or "git push" in workflow:
        problems.append(
            Problem("NODAL-INC32-031", "permanent Increment 32 workflow must be read-only")
        )

    workflows = root / ".github/workflows"
    forbidden = sorted(
        path.relative_to(root).as_posix()
        for path in workflows.glob("*increment32*")
        if path.name != WORKFLOW.name
        and (
            path.name.startswith("_")
            or "payload" in path.name
            or "material" in path.name
            or "source_bundle" in path.name
            or "finaliz" in path.name
        )
    )
    if forbidden:
        problems.append(
            Problem(
                "NODAL-INC32-032",
                "temporary Increment 32 workflows remain: " + ", ".join(forbidden),
            )
        )

    return problems


def run(command: list[str], root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env={**os.environ, "LC_ALL": "C.UTF-8"},
    )


def validate_compile(root: Path) -> list[Problem]:
    problems: list[Problem] = []
    mill = root / ("mill.bat" if os.name == "nt" else "mill")
    compiled = run([str(mill), "examples.continuousTimeApi.compile"], root)
    if compiled.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC32-033",
                "Scala fixture did not compile:\n" + compiled.stdout[-12000:],
            )
        )
    else:
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "scala-witness-report.txt"
            executed = run(
                [
                    str(mill),
                    "examples.continuousTimeApi.runMain",
                    "nodal.increment32fixture.Increment32RuntimeCheck",
                    str(report),
                ],
                root,
            )
            if executed.returncode != 0:
                problems.append(
                    Problem(
                        "NODAL-INC32-034",
                        "Scala witness failed:\n" + executed.stdout[-12000:],
                    )
                )
            elif not report.is_file():
                problems.append(
                    Problem(
                        "NODAL-INC32-034",
                        "Scala witness did not publish its semantic report:\n"
                        + executed.stdout[-12000:],
                    )
                )
            else:
                result = report.read_text(encoding="utf-8").strip()
                if result != "NODAL_INC32_SCALA_WITNESS_PASS":
                    problems.append(
                        Problem(
                            "NODAL-INC32-034",
                            "Scala witness reported semantic failures:\n"
                            + result
                            + "\n"
                            + executed.stdout[-12000:],
                        )
                    )

    compiler = os.environ.get("CXX", "c++")
    with tempfile.TemporaryDirectory() as temporary:
        executable = Path(temporary) / "increment32-native"
        native = run(
            [
                compiler,
                "-std=c++20",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-pedantic",
                "-Icore/native/include",
                NATIVE_WITNESS.as_posix(),
                "-o",
                str(executable),
            ],
            root,
        )
        if native.returncode != 0:
            problems.append(
                Problem(
                    "NODAL-INC32-035",
                    "native witness did not compile:\n" + native.stdout[-12000:],
                )
            )
        else:
            executed = run([str(executable)], root)
            if executed.returncode != 0:
                problems.append(
                    Problem(
                        "NODAL-INC32-036",
                        "native witness failed:\n" + executed.stdout[-12000:],
                    )
                )
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--compile", action="store_true")
    arguments = parser.parse_args(argv)

    root = arguments.root.resolve()
    problems = validate_files(root)
    if not problems and arguments.compile:
        problems.extend(validate_compile(root))
    for problem in problems:
        print(problem, file=os.sys.stderr)
    if problems:
        print(
            f"Increment 32 check failed with {len(problems)} problem(s)",
            file=os.sys.stderr,
        )
        return 1
    print("Increment 32 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
