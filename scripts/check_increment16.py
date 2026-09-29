#!/usr/bin/env python3
"""Run the frozen Increment 16 validator with successor-safe roadmap handling.

The historical Increment 16 contract is retained verbatim in
``check_increment16_frozen.py``. Revision 1.20 and the unchecked Increment 17
entry are the completed Increment 16 baseline, not permanent exact repository
state.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path
from types import FunctionType

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import check_increment16_frozen as frozen
from construction_source_inventory import CONSTRUCTION_FACADE, read_construction_sources

ROOT = frozen.ROOT
Problem = frozen.Problem

FROZEN_CHECKER_PATH = "scripts/check_increment16_frozen.py"
FROZEN_CHECKER_SHA256 = "5f50c98f1220f67f172adfd2cda8d257cf990ebdfbce9ff9380752c07cf39d8e"

SUCCESSOR_CONTRACT_ANCHORS = (
    "- [x] **Increment 17 — ",
    "roadmap does not retain one Increment 17 origin graph",
)

CANONICAL_INSTANCE_SUCCESSOR_ANCHORS = (
    "CandidateRuntime.instance(module)",
    "val (instance, attached) = ConstructionKernel.instance(module)",
)

CANONICAL_INSTANCE_KERNEL_ANCHORS = (
    "def instance[M <: Module](module: M, captured: Boolean): (Instance[M], Boolean) =",
    "attachInstance(created, module, captured)",
    "this.instance(module, captured = true)",
)


def has_canonical_instance_successor(candidate: str, kernel: str) -> bool:
    return all(anchor in candidate for anchor in CANONICAL_INSTANCE_SUCCESSOR_ANCHORS) and all(
        anchor in kernel for anchor in CANONICAL_INSTANCE_KERNEL_ANCHORS
    )


def roadmap_revision(root: Path) -> tuple[int, ...]:
    roadmap = root / "docs/roadmap/nodal-development-todo.md"
    try:
        lines = roadmap.read_text(encoding="utf-8").splitlines()
    except OSError:
        return ()
    revisions = [
        line.removeprefix("**Revision:** ")
        for line in lines
        if line.startswith("**Revision:** ")
    ]
    if len(revisions) != 1:
        return ()
    try:
        return tuple(int(part) for part in revisions[0].split("."))
    except ValueError:
        return ()


def validate_current_sources(root: Path) -> list[Problem]:
    """Apply the immutable predicates to the current logical construction unit.

    Only the construction source read is rebound.  The frozen function's code,
    all other readers and every required/forbidden predicate remain unchanged.
    A fresh globals dictionary avoids mutating the imported historical checker
    or leaking an adapter into another validation.  No source file is rewritten
    and no frozen diagnostic is discarded by this source-layout adaptation.
    """
    try:
        for path in (root / FROZEN_CHECKER_PATH, Path(frozen.__file__)):
            if hashlib.sha256(path.read_bytes()).hexdigest() != FROZEN_CHECKER_SHA256:
                return [Problem("NODAL-INC16-039", "frozen Increment 16 checker changed")]
    except OSError as error:
        return [Problem("NODAL-INC16-039", f"cannot read frozen Increment 16 checker: {error}")]

    def current_text(
        source_root: Path, path: str, problems: list[Problem], code: str
    ) -> str:
        if path == CONSTRUCTION_FACADE:
            return read_construction_sources(
                source_root,
                lambda relative: frozen.text(source_root, relative, problems, code),
            )
        return frozen.text(source_root, path, problems, code)

    bindings = dict(frozen.validate_files.__globals__)
    bindings["text"] = current_text
    validate = FunctionType(
        frozen.validate_files.__code__,
        bindings,
        frozen.validate_files.__name__,
        frozen.validate_files.__defaults__,
        frozen.validate_files.__closure__,
    )
    return validate(root)


def validate_files(root: Path = ROOT) -> list[Problem]:
    root = root.resolve()
    problems = validate_current_sources(root)
    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    try:
        roadmap = roadmap_path.read_text(encoding="utf-8")
    except OSError:
        roadmap = ""
    increment17 = [
        line
        for line in roadmap.splitlines()
        if line.startswith("- [") and "**Increment 17 — " in line
    ]
    if roadmap_revision(root) >= (1, 20) and len(increment17) == 1:
        problems = [
            problem
            for problem in problems
            if problem.code not in {"NODAL-INC16-032", "NODAL-INC16-035"}
        ]
    try:
        candidate = (root / "core/scala/api/src/nodal/CandidateApi.scala").read_text(
            encoding="utf-8"
        )
        kernel = read_construction_sources(root)
    except OSError:
        candidate = ""
        kernel = ""
    if has_canonical_instance_successor(candidate, kernel):
        problems = [
            problem
            for problem in problems
            if not (
                problem.code == "NODAL-INC16-016"
                and problem.message
                == "candidate hooks lacks: CandidateRuntime.attachInstance(this, module)"
            )
        ]
    return problems


def run_compile(root: Path, problems: list[Problem]) -> None:
    frozen.run_compile(root, problems)


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
