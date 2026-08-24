#!/usr/bin/env python3
"""Validate Increment 17 source-span, semantic-naming, and origin-graph contracts."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Problem:
    code: str
    message: str


def text(root: Path, path: str, problems: list[Problem], code: str) -> str:
    try:
        return (root / path).read_text(encoding="utf-8")
    except OSError as error:
        problems.append(Problem(code, f"cannot read {path}: {error}"))
        return ""


def object_json(
    root: Path,
    path: str,
    problems: list[Problem],
    code: str,
) -> dict[str, object]:
    try:
        value = json.loads((root / path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        problems.append(Problem(code, f"cannot load {path}: {error}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} is not a JSON object"))
        return {}
    return value


def require(
    source: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    label: str,
) -> None:
    missing = [fragment for fragment in fragments if fragment not in source]
    if missing:
        problems.append(Problem(code, f"{label} lacks: {', '.join(missing)}"))


def roadmap_revision(roadmap: str) -> tuple[int, ...]:
    revisions = [
        line.removeprefix("**Revision:** ")
        for line in roadmap.splitlines()
        if line.startswith("**Revision:** ")
    ]
    if len(revisions) != 1:
        return ()
    try:
        return tuple(int(part) for part in revisions[0].split("."))
    except ValueError:
        return ()


def validate_files(root: Path = ROOT) -> list[Problem]:
    problems: list[Problem] = []
    kernel = text(
        root,
        "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
        problems,
        "NODAL-INC17-001",
    )
    semantic = text(
        root,
        "core/scala/api/src/nodal/SemanticOriginKernel.scala",
        problems,
        "NODAL-INC17-002",
    )
    compiler = text(
        root,
        "core/scala/api/src/nodal/CompilerApi.scala",
        problems,
        "NODAL-INC17-003",
    )
    construction_tests = text(
        root,
        "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala",
        problems,
        "NODAL-INC17-004",
    )
    tests = text(
        root,
        "core/scala/testkit/test/src/nodal/SemanticOriginTests.scala",
        problems,
        "NODAL-INC17-005",
    )
    documentation = text(
        root,
        "docs/implementation/increment17-source-origin-naming.md",
        problems,
        "NODAL-INC17-006",
    )
    gate = text(
        root,
        "docs/design-gates/NodalSemanticOriginNaming-DG-v1.0.md",
        problems,
        "NODAL-INC17-007",
    )
    roadmap = text(
        root,
        "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC17-008",
    )
    predecessor = text(
        root,
        "scripts/check_increment16.py",
        problems,
        "NODAL-INC17-009",
    )
    frozen_predecessor = text(
        root,
        "scripts/check_increment16_frozen.py",
        problems,
        "NODAL-INC17-010",
    )
    command = text(root, "scripts/nodal.py", problems, "NODAL-INC17-011")
    workflow = text(
        root,
        ".github/workflows/increment-17-source-origin-naming.yml",
        problems,
        "NODAL-INC17-012",
    )
    manifest = object_json(
        root,
        "tests/api/fixtures/increment17/manifest.json",
        problems,
        "NODAL-INC17-013",
    )
    public_manifest = object_json(
        root,
        "core/scala/api/public-api-v0.3.json",
        problems,
        "NODAL-INC17-014",
    )

    require(
        kernel,
        (
            "SemanticOriginBuilder",
            "semanticOrigin.captureModule",
            "semanticOrigin.captureDomain",
            "semanticOrigin.captureDeclaration",
            "semanticOrigin.captureExpression",
            "semanticOrigin.captureInstance",
            "semanticOrigin.captureOperation",
            "semanticOrigin.resolve()",
            "semantic.sourceMap",
            "private def expressionPath",
            "private def domainPath",
        ),
        problems,
        "NODAL-INC17-015",
        "construction-kernel integration",
    )
    require(
        semantic,
        (
            "java.lang.StackWalker",
            "KernelNameSnapshot",
            "KernelOriginSnapshot",
            "KernelGeneratedNameSnapshot",
            "SemanticOriginResult",
            "discoverMemberNames",
            "bindingNear",
            "sink-affinity",
            "shaped-view",
            "stableDigest",
            "SourceMapEntry",
            "inlined = true",
            "ownerPackage",
            "ownerTypeName",
            "sourceIdentityScore",
            "fileName -> ownerClass",
            "locateSource(fileName, ownerClass)",
            '"clock-port"',
            '"reset-port"',
            '"synchronizer"',
            '"fifo"',
            '"reset-controller"',
            '"crossing"',
            '"pipeline-state"',
            '"fsm-state"',
            '"anonymous-register"',
            '"temporary"',
        ),
        problems,
        "NODAL-INC17-016",
        "semantic-origin implementation",
    )
    for forbidden in (
        "new ThreadLocal",
        "DynamicVariable",
        "System.identityHashCode",
        ".hashCode()",
        's"expr_${',
        's"${declaration.kind.label}_${reference.index}"',
    ):
        if forbidden in semantic:
            problems.append(
                Problem("NODAL-INC17-017", f"prohibited semantic naming mechanism: {forbidden}")
            )

    require(
        compiler,
        (
            "final case class SourceSpan(",
            "final case class SourceMapEntry(",
            "sourceMap: Vector[SourceMapEntry]",
        ),
        problems,
        "NODAL-INC17-018",
        "frozen compiler report surface",
    )
    require(
        construction_tests,
        (
            'Vector("KernelTop", "KernelTop.leaf")',
            'find(_.name == "shaped")',
            "assert(emission.report.sourceMap.nonEmpty)",
        ),
        problems,
        "NODAL-INC17-019",
        "updated construction tests",
    )
    require(
        tests,
        (
            "semantic names replace traversal-counter-only names",
            "expression source maps survive inlined origins",
            "generated infrastructure names cover required categories",
            "origin graph records parents and sink affinity",
            "SemanticOriginTop.child",
            "pixel_sum",
            "same-basename source files use owner context",
            "duplicate/alpha/DuplicateSource.scala",
            "duplicate/beta/DuplicateSource.scala",
        ),
        problems,
        "NODAL-INC17-020",
        "semantic-origin Scala tests",
    )
    require(
        documentation,
        (
            "source binding",
            "sink affinity",
            "Traversal ordinals are never emitted as a normal name",
            "expression-level source-map entries remain present",
            "owner package and top-level type context",
            "Public API v0.3 remains unchanged",
        ),
        problems,
        "NODAL-INC17-021",
        "implementation documentation",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**Public API:** unchanged at 0.3",
            "No traversal-counter-only normal names",
            "source spans and origin edges",
        ),
        problems,
        "NODAL-INC17-022",
        "design gate",
    )
    require(
        predecessor,
        (
            "- [x] **Increment 17 — ",
            "roadmap does not retain one Increment 17 origin graph",
            "check_increment16_frozen",
        ),
        problems,
        "NODAL-INC17-023",
        "Increment 16 successor adapter",
    )
    require(
        frozen_predecessor,
        (
            "Validate Increment 16 construction-kernel contracts",
            "NODAL-INC16-035",
        ),
        problems,
        "NODAL-INC17-024",
        "frozen Increment 16 checker",
    )
    require(
        command,
        (
            '_python(root, "check_increment16.py")',
            '_python(root, "check_increment17.py")',
        ),
        problems,
        "NODAL-INC17-025",
        "developer command integration",
    )
    require(
        workflow,
        (
            "Increment 17 Source Origin Naming",
            "permissions:\n  contents: read",
            "python3 scripts/check_increment17.py --compile",
            "python3 tests/api/test_increment17.py",
        ),
        problems,
        "NODAL-INC17-026",
        "permanent workflow",
    )

    if public_manifest.get("api_version") != "0.3" or public_manifest.get("status") != "frozen":
        problems.append(Problem("NODAL-INC17-027", "public API v0.3 identity changed"))
    if manifest.get("increment") != 17 or manifest.get("public_api_changed") is not False:
        problems.append(Problem("NODAL-INC17-028", "Increment 17 manifest identity is invalid"))
    expected_contract = {
        "scala_declaration_names": True,
        "expression_spans": True,
        "stable_origin_graph": True,
        "sink_affinity": True,
        "counter_only_normal_names": False,
        "inlined_expression_source_maps": True,
        "mutable_global_state": False,
        "thread_local_state": False,
        "jvm_identity_in_output": False,
    }
    if manifest.get("semantic_identity_contract") != expected_contract:
        problems.append(Problem("NODAL-INC17-029", "semantic identity contract changed"))

    unchecked = "- [ ] **Increment 17 — Source spans, semantic naming, and origin graph**"
    checked = unchecked.replace("[ ]", "[x]", 1)
    status = manifest.get("status")
    validation = manifest.get("validation")
    revision = roadmap_revision(roadmap)
    if status == "preflight-origin-graph":
        if validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
            "core_ci_run": None,
        }:
            problems.append(Problem("NODAL-INC17-030", "preflight evidence is malformed"))
        if unchecked not in roadmap or revision != (1, 20):
            problems.append(Problem("NODAL-INC17-031", "preflight roadmap state is invalid"))
    elif status == "validated-origin-graph":
        if not isinstance(validation, dict) or not all(
            isinstance(validation.get(key), int)
            for key in ("pull_request", "dedicated_workflow_run", "core_ci_run")
        ):
            problems.append(Problem("NODAL-INC17-032", "final evidence is incomplete"))
        if checked not in roadmap or revision != (1, 21):
            problems.append(Problem("NODAL-INC17-033", "final roadmap state is invalid"))
        if isinstance(validation, dict):
            values = tuple(
                validation.get(key)
                for key in ("pull_request", "dedicated_workflow_run", "core_ci_run")
            )
            if (
                f"PR [#{values[0]}]" not in roadmap
                or f"[{values[1]}]" not in roadmap
                or f"[{values[2]}]" not in roadmap
            ):
                problems.append(Problem("NODAL-INC17-034", "roadmap lacks final evidence"))
    else:
        problems.append(Problem("NODAL-INC17-035", f"unknown status: {status!r}"))

    if "- [ ] **Increment 18 — Nodal MLIR dialect skeleton**" not in roadmap:
        problems.append(Problem("NODAL-INC17-036", "Increment 18 is not left unchecked"))
    if len(re.findall(r"^- \[[ x]\] \*\*Increment 17 — ", roadmap, re.MULTILINE)) != 1:
        problems.append(
            Problem("NODAL-INC17-037", "roadmap does not retain one Increment 17 origin graph")
        )
    return problems


def execute(root: Path, command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env={**os.environ, "NO_COLOR": "1"},
    )


def run_compile(root: Path, problems: list[Problem]) -> None:
    mill = root / ("mill.bat" if os.name == "nt" else "mill")
    for index, arguments in enumerate(
        (
            ["mill.scalalib.scalafmt/checkFormatAll"],
            ["scalafix.check"],
            ["core.scala.testkit.test"],
        ),
        start=1,
    ):
        result = execute(root, [str(mill), *arguments])
        if result.returncode != 0:
            problems.append(
                Problem(
                    f"NODAL-INC17-{37 + index:03d}",
                    f"command failed: {' '.join(arguments)}\n{result.stdout}",
                )
            )
            return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile", action="store_true")
    args = parser.parse_args()
    problems = validate_files(ROOT)
    if args.compile and not problems:
        run_compile(ROOT, problems)
    if problems:
        for problem in problems:
            print(f"{problem.code}: {problem.message}")
        print(f"Increment 17 check failed with {len(problems)} problem(s)")
        return 1
    print("Increment 17 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
