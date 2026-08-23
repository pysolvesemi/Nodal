#!/usr/bin/env python3
"""Validate Increment 16 construction-kernel contracts and optional Scala execution."""

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


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        problems.append(Problem(code, f"cannot read {path.relative_to(ROOT)}: {error}"))
        return ""


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    label: str,
) -> None:
    missing = [fragment for fragment in fragments if fragment not in text]
    if missing:
        problems.append(Problem(code, f"{label} lacks: {', '.join(missing)}"))


def load_json(path: Path, problems: list[Problem], code: str) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        problems.append(Problem(code, f"cannot load {path.relative_to(ROOT)}: {error}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path.relative_to(ROOT)} is not a JSON object"))
        return {}
    return value


def validate_files(root: Path = ROOT) -> list[Problem]:
    problems: list[Problem] = []
    kernel = read(
        root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
        problems,
        "NODAL-INC16-001",
    )
    candidate = read(
        root / "core/scala/api/src/nodal/CandidateApi.scala",
        problems,
        "NODAL-INC16-002",
    )
    core = read(
        root / "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala",
        problems,
        "NODAL-INC16-003",
    )
    interface = read(
        root / "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala",
        problems,
        "NODAL-INC16-004",
    )
    compiler = read(
        root / "core/scala/api/src/nodal/CompilerApi.scala",
        problems,
        "NODAL-INC16-005",
    )
    tests = read(
        root / "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala",
        problems,
        "NODAL-INC16-006",
    )
    documentation = read(
        root / "docs/implementation/increment16-construction-kernel.md",
        problems,
        "NODAL-INC16-007",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC16-008",
    )
    predecessor = read(
        root / "scripts/check_increment15.py",
        problems,
        "NODAL-INC16-009",
    )
    command = read(root / "scripts/nodal.py", problems, "NODAL-INC16-010")
    manifest = load_json(
        root / "tests/api/fixtures/increment16/manifest.json",
        problems,
        "NODAL-INC16-011",
    )
    public_manifest = load_json(
        root / "core/scala/api/public-api-v0.3.json",
        problems,
        "NODAL-INC16-012",
    )

    require(
        kernel,
        (
            "java.lang.ScopedValue",
            "final class ConstructionSession",
            "JVM identity is used only for transient lookup",
            "def beginModule(module: Module)",
            "def attachInstance",
            "def resolveDomains",
            "NODAL-ROOT-DOMAIN-016",
            "NODAL-MULTI-DOMAIN-016",
            "NODAL-ROLE-COMPLETE-016",
            "def interfaceAbi",
            "def resolvedNetSnapshots",
            "def topologyEdges",
            "def finish(root: Module)",
            "def inspect(top: => Module",
        ),
        problems,
        "NODAL-INC16-013",
        "construction kernel",
    )
    for forbidden in (
        "ThreadLocal[",
        "new ThreadLocal",
        "DynamicVariable",
        "System.identityHashCode",
        ".hashCode()",
    ):
        if forbidden in kernel:
            problems.append(
                Problem(
                    "NODAL-INC16-014",
                    f"construction kernel contains prohibited context/identity mechanism: {forbidden}",
                )
            )

    require(
        candidate,
        (
            "CandidateRuntime.beginModule(this)",
            "CandidateRuntime.registerDomain(this, kind)",
            "CandidateRuntime.domainBlock(this, body)",
            "CandidateRuntime.attachInstance(this, module)",
            "CandidateRuntime.currentDomain",
            "KernelSignalKind.Register",
            "ConstructionKernel.operation",
        ),
        problems,
        "NODAL-INC16-015",
        "candidate API implementation hooks",
    )
    require(
        core,
        (
            'CandidateRuntime.dataType[SInt]("SInt", width)',
            'CandidateRuntime.dataType[Vec[A]]("Vec", element, dimensions.toSeq)',
            "KernelSignalKind.Memory",
            '"readLatency" -> readLatency',
        ),
        problems,
        "NODAL-INC16-016",
        "core semantic construction hooks",
    )
    require(
        interface,
        (
            'CandidateRuntime.dataType[Struct]("Struct", name, fields.toSeq)',
            "KernelSignalKind.InterfacePort",
            "KernelSignalKind.InterfaceArray",
            "KernelSignalKind.DigitalInout",
            "KernelSignalKind.ConservativeTerminal",
            'ConstructionKernel.operation("interface-connect"',
            'ConstructionKernel.operation("inout-pass-through"',
            'ConstructionKernel.operation("terminal-connect"',
        ),
        problems,
        "NODAL-INC16-017",
        "Interface/inout/topology construction hooks",
    )
    require(
        compiler,
        ("object Nodal:", "ConstructionKernel.emit(top, options)"),
        problems,
        "NODAL-INC16-018",
        "compiler entry point",
    )
    require(
        tests,
        (
            "deterministic hierarchy, domains, shapes, interfaces and topology",
            "unbound root requirement is rejected transactionally",
            "unqualified multi-domain state is rejected",
            "exported interface roles must be complete",
            "parallel elaborations do not share mutable construction state",
        ),
        problems,
        "NODAL-INC16-019",
        "Scala tests",
    )
    require(
        documentation,
        (
            "Each `Nodal.emit` or private test inspection allocates one construction transaction",
            "JVM identity values, hash codes, reflection order, and allocation addresses are never emitted",
            "Increment 16 does not implement source spans",
        ),
        problems,
        "NODAL-INC16-020",
        "implementation documentation",
    )
    require(
        predecessor,
        (
            'line.startswith(("- [ ] **Increment 16 — ", "- [x] **Increment 16 — "))',
            "roadmap does not retain one Increment 16 kernel",
        ),
        problems,
        "NODAL-INC16-021",
        "Increment 15 successor safety",
    )
    require(
        command,
        (
            '_python(root, "check_increment13.py", "--compile-negative")',
            '_python(root, "check_increment14.py", "--compile-negative")',
            '_python(root, "check_increment15.py", "--compile-negative")',
            '_python(root, "check_increment16.py")',
        ),
        problems,
        "NODAL-INC16-022",
        "developer command integration",
    )

    if public_manifest.get("api_version") != "0.3" or public_manifest.get("status") != "frozen":
        problems.append(Problem("NODAL-INC16-023", "public API v0.3 identity changed"))
    if manifest.get("increment") != 16 or manifest.get("public_api_version") != "0.3":
        problems.append(Problem("NODAL-INC16-024", "Increment 16 manifest identity is invalid"))
    if manifest.get("public_api_changed") is not False:
        problems.append(Problem("NODAL-INC16-025", "manifest does not preserve the frozen public API"))

    context = manifest.get("context_contract")
    expected_context = {
        "binding": "java.lang.ScopedValue",
        "mutable_global_state": False,
        "thread_local_state": False,
        "public_scala_implicit": False,
        "jvm_identity_in_output": False,
        "parallel_emit_isolation": True,
    }
    if context != expected_context:
        problems.append(Problem("NODAL-INC16-026", "context contract does not match the approved architecture"))

    source_codes = set(re.findall(r'"(NODAL-[A-Z0-9-]+-016)"', kernel))
    diagnostic_codes = manifest.get("diagnostics")
    if not isinstance(diagnostic_codes, list) or not set(diagnostic_codes).issubset(source_codes):
        problems.append(Problem("NODAL-INC16-027", "manifest diagnostics are not implemented by the kernel"))

    status = manifest.get("status")
    validation = manifest.get("validation")
    unchecked = (
        "- [ ] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**"
    )
    checked = unchecked.replace("[ ]", "[x]", 1)
    if status == "preflight-kernel":
        if validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
            "core_ci_run": None,
        }:
            problems.append(Problem("NODAL-INC16-028", "preflight validation evidence is malformed"))
        if unchecked not in roadmap or "**Revision:** 1.19" not in roadmap:
            problems.append(Problem("NODAL-INC16-029", "preflight roadmap state is invalid"))
    elif status == "validated-kernel":
        if not isinstance(validation, dict) or not all(
            isinstance(validation.get(key), int)
            for key in ("pull_request", "dedicated_workflow_run", "core_ci_run")
        ):
            problems.append(Problem("NODAL-INC16-030", "final validation evidence is incomplete"))
        if checked not in roadmap or "**Revision:** 1.20" not in roadmap:
            problems.append(Problem("NODAL-INC16-031", "final roadmap state is invalid"))
        if isinstance(validation, dict):
            pull_request = validation.get("pull_request")
            dedicated = validation.get("dedicated_workflow_run")
            core_ci = validation.get("core_ci_run")
            if f"PR [#{pull_request}]" not in roadmap:
                problems.append(Problem("NODAL-INC16-032", "roadmap lacks final pull-request evidence"))
            if f"[{dedicated}]" not in roadmap or f"[{core_ci}]" not in roadmap:
                problems.append(Problem("NODAL-INC16-033", "roadmap lacks final workflow evidence"))
    else:
        problems.append(Problem("NODAL-INC16-034", f"unknown Increment 16 status: {status!r}"))

    increment17 = [
        line
        for line in roadmap.splitlines()
        if line.startswith("- [ ] **Increment 17 — Source spans, semantic naming, and origin graph**")
    ]
    if len(increment17) != 1:
        problems.append(Problem("NODAL-INC16-035", "roadmap does not leave Increment 17 unchecked"))

    return problems


def run(root: Path, command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env={**os.environ, "NO_COLOR": "1"},
    )


def run_execution(root: Path, problems: list[Problem]) -> None:
    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    commands = (
        [str(wrapper), "mill.scalalib.scalafmt/checkFormatAll"],
        [str(wrapper), "scalafix.check"],
        [str(wrapper), "core.scala.testkit.test"],
    )
    for index, command in enumerate(commands, start=1):
        result = run(root, command)
        if result.returncode != 0:
            problems.append(
                Problem(
                    f"NODAL-INC16-{35 + index:03d}",
                    f"command failed: {' '.join(command)}\n{result.stdout}",
                )
            )
            return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile", action="store_true", help="run formatting, lint, and Scala tests")
    args = parser.parse_args()

    problems = validate_files(ROOT)
    if args.compile and not problems:
        run_execution(ROOT, problems)
    if problems:
        for problem in problems:
            print(f"{problem.code}: {problem.message}")
        print(f"Increment 16 check failed with {len(problems)} problem(s)")
        return 1
    print("Increment 16 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
