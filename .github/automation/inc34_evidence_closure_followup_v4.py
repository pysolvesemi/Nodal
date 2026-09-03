#!/usr/bin/env python3
"""Remove stale open-tranche language from the Increment 34 closure records."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    readme_path = root / "tests/compiler/fixtures/increment34/README.md"
    readme = readme_path.read_text(encoding="utf-8")
    old = '''The Increment 34 checkpoint is pinned to the validated Increment 33 evidence
state and roadmap revision 1.44. Increment 34 remains open until its own
implementation merge, post-merge validation, and separate evidence closure.
'''
    new = '''The accepted Increment 34 implementation is pinned to the validated Increment 33
evidence state and roadmap revision 1.44. Implementation PR #109, the exact-head
matrix, the implementation merge, post-merge validation, and separate evidence
closure PR #111 are retained by roadmap revision 1.45 and the validated manifest.
'''
    readme = replace_once(readme, old, new, "Increment 34 fixture closure note")
    readme_path.write_text(readme, encoding="utf-8")

    gate_path = root / "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md"
    gate = gate_path.read_text(encoding="utf-8")
    old = '''The public-construction tranche supplies the explicit API, mutable construction
bridge, owner-remapped immutable snapshot, and executable branch-sensitive
analysis. Canonical `ConstructionSnapshot` integration, Scala-to-MLIR bridging,
first-class native operations, and target lowering remain later tranches of the
same increment.
'''
    new = '''The completed Increment 34 implementation retains the explicit public API,
owner-remapped canonical `ConstructionSnapshot`, deterministic Scala-to-MLIR
bridge, first-class native operations and regions, branch-sensitive verification,
stable diagnostics, and source maps. Target lowering remains assigned to later
roadmap increments.
'''
    gate = replace_once(gate, old, new, "Increment 34 design-gate closure state")
    gate_path.write_text(gate, encoding="utf-8")

    implementation_path = root / "docs/implementation/increment34-analog-control-flow.md"
    implementation = implementation_path.read_text(encoding="utf-8")
    marker = '''## Current boundaries
'''
    note = '''## Closure evidence

Implementation PR #109 was accepted at exact head
`207fd1b580e9428e9948cd4e4bd8f2060fde4b79`, squash-merged as
`a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49`, and revalidated by Core CI run
`33758905273` plus exact Increment 34 run `33759112770`. Separate evidence PR
#111 advances the validated manifest and roadmap only after its own exact-tree
validation.

'''
    if note.strip() not in implementation:
        implementation = replace_once(
            implementation,
            marker,
            note + marker,
            "Increment 34 implementation closure evidence",
        )
    implementation_path.write_text(implementation, encoding="utf-8")

    print("Increment 34 closure documentation synchronized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
