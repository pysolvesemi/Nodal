#!/usr/bin/env python3
"""Validate Increment 16 construction-kernel contracts and optional execution."""

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


def object_json(root: Path, path: str, problems: list[Problem], code: str) -> dict[str, object]:
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


def validate_files(root: Path = ROOT) -> list[Problem]:
    problems: list[Problem] = []
    kernel = text(root, "core/scala/api/src/nodal/ElaborationConstructionKernel.scala", problems, "NODAL-INC16-001")
    candidate = text(root, "core/scala/api/src/nodal/CandidateApi.scala", problems, "NODAL-INC16-002")
    core = text(root, "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala", problems, "NODAL-INC16-003")
    interface = text(root, "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala", problems, "NODAL-INC16-004")
    compiler = text(root, "core/scala/api/src/nodal/CompilerApi.scala", problems, "NODAL-INC16-005")
    tests = text(root, "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala", problems, "NODAL-INC16-006")
    documentation = text(root, "docs/implementation/increment16-construction-kernel.md", problems, "NODAL-INC16-007")
    gate = text(root, "docs/design-gates/NodalConstructionKernel-DG-v1.0.md", problems, "NODAL-INC16-008")
    roadmap = text(root, "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC16-009")
    predecessor = text(root, "scripts/check_increment15.py", problems, "NODAL-INC16-010")
    command = text(root, "scripts/nodal.py", problems, "NODAL-INC16-011")
    manifest = object_json(root, "tests/api/fixtures/increment16/manifest.json", problems, "NODAL-INC16-012")
    public_manifest = object_json(root, "core/scala/api/public-api-v0.3.json", problems, "NODAL-INC16-013")

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
            "private def interfaceAbi",
            "private def resolvedNets",
            "private def topology",
            "def finish(root: Module)",
            "def inspect(top: => Module",
        ),
        problems,
        "NODAL-INC16-014",
        "construction kernel",
    )
    for forbidden in ("new ThreadLocal", "DynamicVariable", "System.identityHashCode", ".hashCode()"):
        if forbidden in kernel:
            problems.append(Problem("NODAL-INC16-015", f"prohibited mechanism: {forbidden}"))

    require(
        candidate,
        (
            "CandidateRuntime.beginModule(this)",
            "CandidateRuntime.registerDomain(this, kind)",
            "CandidateRuntime.domainBlock(this, body)",
            "CandidateRuntime.attachInstance(this, module)",
            "CandidateRuntime.currentDomain",
            "KernelSignalKind.Register",
        ),
        problems,
        "NODAL-INC16-016",
        "candidate hooks",
    )
    require(
        core,
        (
            'CandidateRuntime.dataType[SInt]("SInt", width)',
            'CandidateRuntime.dataType[Vec[A]]("Vec", element, dimensions.toSeq)',
            "KernelSignalKind.Memory",
        ),
        problems,
        "NODAL-INC16-017",
        "core hooks",
    )
    require(
        interface,
        (
            'CandidateRuntime.dataType[Struct]("Struct", name, fields.toSeq)',
            "KernelSignalKind.InterfacePort",
            "KernelSignalKind.InterfaceArray",
            "KernelSignalKind.DigitalInout",
            "KernelSignalKind.ConservativeTerminal",
            'ConstructionKernel.operation("inout-pass-through"',
            'ConstructionKernel.operation("terminal-connect"',
        ),
        problems,
        "NODAL-INC16-018",
        "interface hooks",
    )
    require(
        compiler,
        ("object Nodal:", "ConstructionKernel.emit(top, options)"),
        problems,
        "NODAL-INC16-019",
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
        "NODAL-INC16-020",
        "Scala tests",
    )
    require(
        documentation,
        (
            "Each `Nodal.emit` or private test inspection allocates one construction transaction",
            "identity values, hash codes, reflection order, and allocation addresses are never emitted",
            "Increment 16 does not implement source spans",
        ),
        problems,
        "NODAL-INC16-021",
        "implementation documentation",
    )
    require(
        gate,
        (
            "**Public API:** unchanged at 0.3",
            "no public implicit, given, mutable global, or thread-local",
            "Temporary identity maps locate live Scala objects",
        ),
        problems,
        "NODAL-INC16-022",
        "implementation gate",
    )
    require(
        predecessor,
        ("- [x] **Increment 16 — ", "roadmap does not retain one Increment 16 kernel"),
        problems,
        "NODAL-INC16-023",
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
        "NODAL-INC16-024",
        "developer command integration",
    )

    if public_manifest.get("api_version") != "0.3" or public_manifest.get("status") != "frozen":
        problems.append(Problem("NODAL-INC16-025", "public API v0.3 identity changed"))
    if manifest.get("increment") != 16 or manifest.get("public_api_changed") is not False:
        problems.append(Problem("NODAL-INC16-026", "Increment 16 manifest identity is invalid"))
    expected_context = {
        "binding": "java.lang.ScopedValue",
        "mutable_global_state": False,
        "thread_local_state": False,
        "public_scala_implicit": False,
        "jvm_identity_in_output": False,
        "parallel_emit_isolation": True,
    }
    if manifest.get("context_contract") != expected_context:
        problems.append(Problem("NODAL-INC16-027", "context contract changed"))

    implemented_codes = set(re.findall(r'"(NODAL-[A-Z0-9-]+-[0-9]{3})"', kernel))
    listed_codes = manifest.get("diagnostics")
    if not isinstance(listed_codes, list) or not set(listed_codes).issubset(implemented_codes):
        problems.append(Problem("NODAL-INC16-028", "manifest diagnostics are not implemented"))

    unchecked = "- [ ] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**"
    checked = unchecked.replace("[ ]", "[x]", 1)
    status = manifest.get("status")
    validation = manifest.get("validation")
    if status == "preflight-kernel":
        if validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
            "core_ci_run": None,
        }:
            problems.append(Problem("NODAL-INC16-029", "preflight evidence is malformed"))
        if unchecked not in roadmap or "**Revision:** 1.19" not in roadmap:
            problems.append(Problem("NODAL-INC16-030", "preflight roadmap state is invalid"))
    elif status == "validated-kernel":
        if not isinstance(validation, dict) or not all(
            isinstance(validation.get(key), int)
            for key in ("pull_request", "dedicated_workflow_run", "core_ci_run")
        ):
            problems.append(Problem("NODAL-INC16-031", "final evidence is incomplete"))
        if checked not in roadmap or "**Revision:** 1.20" not in roadmap:
            problems.append(Problem("NODAL-INC16-032", "final roadmap state is invalid"))
        if isinstance(validation, dict):
            values = tuple(validation.get(key) for key in ("pull_request", "dedicated_workflow_run", "core_ci_run"))
            if f"PR [#{values[0]}]" not in roadmap or f"[{values[1]}]" not in roadmap or f"[{values[2]}]" not in roadmap:
                problems.append(Problem("NODAL-INC16-033", "roadmap lacks final evidence"))
    else:
        problems.append(Problem("NODAL-INC16-034", f"unknown status: {status!r}"))

    if "- [ ] **Increment 17 — Source spans, semantic naming, and origin graph**" not in roadmap:
        problems.append(Problem("NODAL-INC16-035", "Increment 17 is not left unchecked"))
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
                    f"NODAL-INC16-{35 + index:03d}",
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
        print(f"Increment 16 check failed with {len(problems)} problem(s)")
        return 1
    print("Increment 16 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
