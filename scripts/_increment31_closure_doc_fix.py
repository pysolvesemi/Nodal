#!/usr/bin/env python3
"""Finalize Increment 31 closure documentation and state-aware validation."""

from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BRANCH = "increment/31-evidence-closure"


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one replacement target, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def update_implementation_note() -> None:
    path = ROOT / "docs/implementation/increment31-potential-flow-access.md"
    content = """# Increment 31 — Potential and flow access functions

**Status:** Validated
**Baseline:** fully validated Increment 30 closure at `f33bcff3285f17d228bab4c7577bafd35ab32a65`
**Implementation PR:** [#88](https://github.com/pysolvesemi/Nodal/pull/88), replacing draft [#87](https://github.com/pysolvesemi/Nodal/pull/87) without changing the validated source head
**Merge commit:** `1662b79f5f99686de4af2ed8a016fe8acf5c784e`
**Public API:** unchanged at v0.3
**Roadmap state:** Increment 31 is checked in roadmap revision 1.41

## Implemented

- Canonical nature declarations may carry a physical `dimension`; typed access
  requires that dimension and produces
  `!nodal.quantity<\"real\", canonical-nature-dimension>`.
- `resolvePotentialFlowAccessNature` resolves continuous disciplines and their
  potential or flow natures through the existing canonical import machinery.
- `nodal.access` retains legacy branch compatibility while new typed branch
  access carries the authored `function` identity.
- `nodal.terminal_access` preserves one-terminal and oriented two-terminal
  source forms. One-terminal access deterministically records the canonical
  discipline-global reference.
- Named branches remain distinct from endpoint-only two-terminal access during
  probe grouping; only implicit branches may coalesce by endpoint pair.
- Named branch access emits a deterministic Verilog-A/Verilog-AMS branch
  declaration and renders `function(branch-name)`; implicit branch access keeps
  the oriented endpoint form.
- `nodal.port_flow_access` represents local angle-delimited total-port flow and
  remains distinct from branch-probe construction.
- `nodal.probe` records compiler-owned source-free potential or flow probe
  intent, including zero-flow or zero-potential constraint intent and canonical
  source provenance.
- `normalizePotentialFlowAccess` and
  `nodal-normalize-potential-flow-access` provide deterministic, idempotent
  reference and probe normalization.
- The transactional Fast, Default, and Release semantic gates run access
  normalization after conservative-connectivity materialization and before
  mandatory verification.
- The verifier rejects invalid forms, disciplines, natures, access-function
  identities, result dimensions, references, local-port use, mixed source-free
  probe kinds, and forged probe provenance with the nine stable Increment 31
  diagnostics.
- Verilog-A and Verilog-AMS rendering uses the verified authored access
  function for new typed access, including branch, one-terminal, two-terminal,
  and `function(<port>)` forms. Conventional `V`/`I` fallback remains only for
  the explicitly preserved legacy branch-access form.
- Native fixtures cover positive normalization, repeated-pass idempotence,
  canonical reference materialization, every stable rejection family, generic
  and discipline-specific spelling, and backend port rendering.

## Validation

The implementation materialization run `33233642892` compiled the native
dialect and backend, passed all 83 CTest cases twice (direct CTest and the
`check-nodal-native` target), and completed the locked clang-tidy gate across
25 translation units. The materialized native implementation commit is
`06365bc383064c1412db1f2580f876b21b621035`.

The accepted implementation head
`647c79d1d78848c492b3128bd79502b6ef8664de` passed the corrected 20/20
exact-head workflow matrix. Dedicated Increment 31 run `33244625475` passed
repository contracts, mutation tests, the native compiler/backend suite,
diagnostics, normalization, idempotence, and artifact hygiene. Core CI run
`33244625490` passed contracts, Scala, native, and the aggregate required gate.

PR [#88](https://github.com/pysolvesemi/Nodal/pull/88) merged the implementation
into `dev` at `1662b79f5f99686de4af2ed8a016fe8acf5c784e`. Draft PR
[#87](https://github.com/pysolvesemi/Nodal/pull/87) is retained as the original
implementation discussion; #88 used the identical source head because the
connector could not transition #87 out of draft state.

The separate evidence closure records the accepted run set in the Increment 31
manifest, checks the roadmap item, and keeps predecessor/successor state checks
valid in both pre-evidence and validated repository states. Temporary closure
scripts and writable workflows are absent from the permanent diff.

## Compatibility and remaining ownership

The public Scala API remains v0.3. Existing unqualified legacy
`nodal.access` remains accepted; no new typed producer relies on that form.
Increment 32 retains first-class equation construction, additive contribution
semantics, and residual formation. Later continuous-time increments retain
state, event, analysis, noise, and solver semantics.
"""
    path.write_text(content, encoding="utf-8")


def update_checker() -> None:
    path = ROOT / "scripts/check_increment31.py"
    old = '''    require(
        implementation,
        (
            "**Status:** Implemented — awaiting evidence",
            "Increment 31 remains unchecked",
            "resolvePotentialFlowAccessNature",
            "nodal-normalize-potential-flow-access",
            "all 83 CTest cases",
            "public Scala API remains v0.3",
            "transactional Fast, Default, and Release semantic gates",
            "Named branches remain distinct",
            "Named branch access emits",
        ),
        problems,
        "NODAL-INC31-004",
        "Increment 31 implementation note",
    )
'''
    new = '''    require(
        implementation,
        (
            "resolvePotentialFlowAccessNature",
            "nodal-normalize-potential-flow-access",
            "all 83 CTest cases",
            "public Scala API remains v0.3",
            "transactional Fast, Default, and Release semantic gates",
            "Named branches remain distinct",
            "Named branch access emits",
        ),
        problems,
        "NODAL-INC31-004",
        "Increment 31 implementation note",
    )
    if manifest.get("status") == "implemented-awaiting-evidence":
        require(
            implementation,
            (
                "**Status:** Implemented — awaiting evidence",
                "Increment 31 remains unchecked",
            ),
            problems,
            "NODAL-INC31-004",
            "pre-evidence Increment 31 implementation note",
        )
    elif manifest.get("status") == "validated-potential-flow-access":
        require(
            implementation,
            (
                "**Status:** Validated",
                "Increment 31 is checked",
                "PR [#88]",
                "1662b79f5f99686de4af2ed8a016fe8acf5c784e",
                "33244625475",
                "33244625490",
            ),
            problems,
            "NODAL-INC31-004",
            "validated Increment 31 implementation note",
        )
'''
    replace_once(path, old, new)


def update_mutation_tests() -> None:
    path = ROOT / "tests/compiler/test_increment31.py"
    marker = '\n\nif __name__ == "__main__":\n'
    test = '''
    def test_rejects_stale_implementation_status_after_evidence(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/implementation/increment31-potential-flow-access.md"
        text = path.read_text(encoding="utf-8").replace(
            "**Status:** Validated",
            "**Status:** Implemented — awaiting evidence",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-004", self.codes(root))
'''
    replace_once(path, marker, "\n" + test + marker)


def validate() -> None:
    for increment in range(18, 32):
        run("python3", f"scripts/check_increment{increment}.py")
    run("python3", "tests/compiler/test_increment30.py")
    run("python3", "tests/compiler/test_increment31.py")
    run(
        "python3",
        "-m",
        "unittest",
        "discover",
        "-s",
        "tests/compiler",
        "-p",
        "test_*.py",
    )
    run("python3", "scripts/check_markdown.py")
    run("python3", "scripts/check_package_visibility.py")
    run("git", "diff", "--check")


def commit_and_push() -> None:
    (ROOT / "scripts/_increment31_closure_doc_fix.py").unlink(missing_ok=True)
    run("git", "config", "user.name", "github-actions[bot]")
    run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    run("git", "add", "-A")
    run("git", "commit", "-m", "docs(increment31): finalize validated implementation note")
    run("git", "push", "origin", f"HEAD:{BRANCH}")


def main() -> None:
    update_implementation_note()
    update_checker()
    update_mutation_tests()
    validate()
    commit_and_push()


if __name__ == "__main__":
    main()
