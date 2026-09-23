#!/usr/bin/env python3
"""Exercise the production hierarchy pass, never a Python replacement verifier.

The small reference graph routines only select expected outcomes. Every case is
parsed and verified by the specified nodalc executable. Large flat IR is used
so module-instantiation depth is not confused with parser nesting depth.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import time


PIPELINE = "--pass-pipeline=builtin.module(nodal-verify-hierarchy)"
DEPTH = 50000
STACK_BYTES = 1024 * 1024
TIMEOUT = 60


def instance(name: str, target: str, line: int = 30) -> str:
    return (
        f'"nodal.instance"() <{{sym_name = "{name}", module = @{target}, '
        'parameter_bindings = {}, domain_bindings = {}, metadata = {}}> '
        f': () -> () loc("Hierarchy42.scala":{line}:7)'
    )


def definition(name: str, statements: list[str], line: int = 10) -> str:
    return (
        f'"nodal.module"() <{{metadata = {{}}, sym_name = "{name}"}}> ({{\n'
        '^bb0:\n' + "\n".join(statements) + "\n}) : () -> () "
        f'loc("Hierarchy42.scala":{line}:1)'
    )


def source(definitions: list[str], guard: bool = True) -> str:
    closed = "true" if guard else "false"
    return (
        f"module attributes {{nodal.verify.hierarchy_closed = {closed}}} {{\n"
        + "\n".join(definitions) + "\n}\n"
    )


def graph_source(edges: list[list[int]], order: list[int] | None = None) -> str:
    width = max(1, len(str(max(0, len(edges) - 1))))
    names = [f"M{index:0{width}d}" for index in range(len(edges))]
    modules = []
    for owner in range(len(edges)) if order is None else order:
        statements = [instance(f"i{index:06d}", names[target])
                      for index, target in enumerate(edges[owner])]
        modules.append(definition(names[owner], statements))
    return source(modules)


def acyclic(edges: list[list[int]]) -> bool:
    """Independent Kahn reference: intentionally not the production DFS."""
    incoming = [0] * len(edges)
    for children in edges:
        for child in children:
            incoming[child] += 1
    pending = [index for index, count in enumerate(incoming) if count == 0]
    visited = 0
    while pending:
        owner = pending.pop()
        visited += 1
        for child in edges[owner]:
            incoming[child] -= 1
            if incoming[child] == 0:
                pending.append(child)
    return visited == len(edges)


def bounded_stack() -> None:
    # The toolchain's supported Unix hosts provide resource. This limit applies
    # only to the child compiler, never to the caller or other CI jobs. Failure
    # to apply it is a failed test, not an unreported skip.
    import resource
    _, hard = resource.getrlimit(resource.RLIMIT_STACK)
    if hard != resource.RLIM_INFINITY and hard < STACK_BYTES:
        raise RuntimeError("host hard stack limit is below the declared test limit")
    resource.setrlimit(resource.RLIMIT_STACK, (STACK_BYTES, hard))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


class Matrix:
    def __init__(self, nodalc: Path, work: Path) -> None:
        self.nodalc = nodalc
        self.work = work
        self.records: list[dict[str, object]] = []

    def check(self, name: str, text: str, *, code: str | None = None,
              location: str | None = None, detail: str | None = None,
              pipeline: str | None = PIPELINE, stack: bool = False,
              expected_modules: int | None = None,
              same_output: bytes | None = None) -> bytes:
        if expected_modules is None:
            expected_modules = text.count('"nodal.module"')
        path = self.work / f"{name}.mlir"
        path.write_text(text, encoding="utf-8")
        command = [str(self.nodalc)] + ([] if pipeline is None else [pipeline]) + [str(path)]
        start = time.monotonic()
        record: dict[str, object] = {
            "case": name, "command": command,
            "input_sha256": hashlib.sha256(text.encode()).hexdigest(),
            "expected_code": code, "stack_bytes": STACK_BYTES if stack else None,
        }
        try:
            completed = subprocess.run(
                command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                check=False, timeout=TIMEOUT,
                preexec_fn=bounded_stack if stack else None,
            )
            output = completed.stdout
            diagnostics = completed.stderr.decode("utf-8", errors="replace")
            (self.work / f"{name}.stdout").write_bytes(output)
            (self.work / f"{name}.stderr").write_bytes(completed.stderr)
            errors = []
            if code is None:
                if completed.returncode != 0:
                    errors.append(f"expected success, got {completed.returncode}")
                if not output.startswith(b"module"):
                    errors.append("successful compiler did not publish normalized MLIR")
                if output.count(b'"nodal.module"') != expected_modules:
                    errors.append("definition count changed")
            else:
                # A signal, crash or unrelated parser error is not a rejection.
                if completed.returncode != 1:
                    errors.append(f"expected diagnostic rejection, got {completed.returncode}")
                if code not in diagnostics:
                    errors.append("required diagnostic absent")
                if output:
                    errors.append("rejected candidate published partial normalized IR")
            if location is not None and not diagnostics.startswith(location + ": error:"):
                errors.append("diagnostic did not identify the closing instance source")
            if detail is not None and detail not in diagnostics:
                errors.append("wrong deterministic diagnostic target")
            if same_output is not None and output != same_output:
                errors.append("repeated parse/pass output changed")
            record.update(returncode=completed.returncode, errors=errors,
                          diagnostic_excerpt=diagnostics[:4096] if errors else None,
                          output_sha256=hashlib.sha256(output).hexdigest(), passed=not errors)
        except (subprocess.TimeoutExpired, subprocess.SubprocessError, OSError) as error:
            output = b""
            record.update(passed=False, errors=[str(error)])
        record["seconds"] = round(time.monotonic() - start, 6)
        self.records.append(record)
        print(f'{"PASS" if record["passed"] else "FAIL"} {name}', flush=True)
        return output

    def finish(self) -> int:
        failures = [record for record in self.records if not record["passed"]]
        report = {"schema": "nodal.increment42.native-hierarchy-matrix.v1",
                  "compiler": str(self.nodalc),
                  "compiler_sha256": hashlib.sha256(self.nodalc.read_bytes()).hexdigest(),
                  "case_count": len(self.records), "failure_count": len(failures),
                  "cases": self.records}
        (self.work / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        for record in failures:
            print(f'{record["case"]}: {record["errors"]}', file=sys.stderr)
            if record.get("diagnostic_excerpt"):
                print(record["diagnostic_excerpt"], file=sys.stderr)
        print(f'{len(self.records) - len(failures)}/{len(self.records)} native hierarchy cases passed')
        return 1 if failures else 0


def run(nodalc: Path, work: Path) -> int:
    matrix = Matrix(nodalc, work)
    matrix.check("empty", source([]), expected_modules=0)
    matrix.check("leaf", source([definition("Leaf", [])]), expected_modules=1)
    diamond = graph_source([[1, 2], [3], [3], []])
    first = matrix.check("diamond", diamond, expected_modules=4)
    matrix.check("diamond-repeat", diamond, expected_modules=4, same_output=first)
    matrix.check("diamond-reparse", first.decode(), expected_modules=4, same_output=first)
    matrix.check("duplicate-edges", graph_source([[1, 1, 1], []]), expected_modules=2)
    for profile in ("fast", "default", "release"):
        matrix.check(f"pipeline-{profile}", diamond, expected_modules=4,
                     pipeline=f"--pass-pipeline=builtin.module(nodal-gate-{profile})")
        matrix.check(f"pipeline-{profile}-cycle", graph_source([[1], [0]]),
                     code="NODAL-VERIFY-HIERARCHY-005",
                     pipeline=f"--pass-pipeline=builtin.module(nodal-gate-{profile})")
    matrix.check("closure-guard", source([definition("Leaf", [])], guard=False),
                 code="NODAL-VERIFY-HIERARCHY-003")
    matrix.check("unresolved", source([definition("Top", [instance("child", "Missing", 42)])]),
                 code="NODAL-VERIFY-HIERARCHY-004", location="Hierarchy42.scala:42:7",
                 detail="unknown module 'Missing'")
    matrix.check("self-cycle", source([definition("A", [instance("again", "A", 45)])]),
                 code="NODAL-VERIFY-HIERARCHY-005", location="Hierarchy42.scala:45:7")
    matrix.check("disconnected-cycle", graph_source([[], [2], [1]]),
                 code="NODAL-VERIFY-HIERARCHY-005")
    cycle = [definition("A", [instance("ab", "B", 31)]),
             definition("B", [instance("ba", "A", 80)]),
             definition("Unrelated", [])]
    for number, permutation in enumerate(itertools.permutations(cycle)):
        matrix.check(f"cycle-order-{number}", source(list(permutation)),
                     code="NODAL-VERIFY-HIERARCHY-005", location="Hierarchy42.scala:80:7",
                     detail="recursive module hierarchy includes 'A'")
    for number, permutation in enumerate(itertools.permutations([
        instance("z_bad", "MissingZ", 99), instance("a_bad", "MissingA", 55),
        instance("ok", "Leaf", 60),
    ])):
        matrix.check(f"binding-order-{number}", source([
            definition("Top", list(permutation)), definition("Leaf", [])]),
            code="NODAL-VERIFY-HIERARCHY-004", location="Hierarchy42.scala:55:7",
            detail="unknown module 'MissingA'")
    # Exhaustive three-definition directed graphs, including all self-edges.
    # The external compiler sees both acyclic and cyclic reference graphs.
    arcs = [(owner, child) for owner in range(3) for child in range(3)]
    for bits in range(1 << len(arcs)):
        edges: list[list[int]] = [[], [], []]
        for index, (owner, child) in enumerate(arcs):
            if bits & (1 << index):
                edges[owner].append(child)
        matrix.check(f"exhaustive-{bits:03d}", graph_source(edges),
                     code=None if acyclic(edges) else "NODAL-VERIFY-HIERARCHY-005")
    randomizer = random.Random(42)
    for number in range(16):
        edges = [[] for _ in range(32)]
        for owner in range(32):
            edges[owner] = [child for child in range(owner + 1, 32)
                            if randomizer.random() < 0.125]
        order = list(range(32))
        randomizer.shuffle(order)
        matrix.check(f"seeded-dag-{number:02d}", graph_source(edges, order), expected_modules=32)
    deep = [[index + 1] if index + 1 < DEPTH else [] for index in range(DEPTH)]
    text = graph_source(deep)
    # The parse-only control separates parser/printing limits from graph-stack
    # exhaustion. Neither command may crash, timeout or drop definitions.
    matrix.check("deep-parse-control", text, pipeline=None, stack=True, expected_modules=DEPTH)
    matrix.check("deep-verify", text, stack=True, expected_modules=DEPTH)
    deep[-1].append(DEPTH // 2)
    matrix.check("deep-back-edge", graph_source(deep), stack=True,
                 code="NODAL-VERIFY-HIERARCHY-005")
    return matrix.finish()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", required=True, type=Path)
    parser.add_argument("--work-dir", type=Path)
    args = parser.parse_args()
    nodalc = args.nodalc.resolve()
    if not nodalc.is_file() or not os.access(nodalc, os.X_OK):
        parser.error("--nodalc must name an executable native compiler")
    if os.name != "posix":
        parser.error("the declared bounded-stack test requires a supported Unix toolchain host")
    if args.work_dir is not None:
        args.work_dir.mkdir(parents=True, exist_ok=True)
        return run(nodalc, args.work_dir.resolve())
    with tempfile.TemporaryDirectory(prefix="nodal42-hierarchy-") as temporary:
        return run(nodalc, Path(temporary))


if __name__ == "__main__":
    raise SystemExit(main())
