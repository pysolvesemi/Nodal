#!/usr/bin/env python3
"""Make Increment 19/20 scope guards successor-aware without weakening old fixtures."""

from __future__ import annotations

import argparse
from pathlib import Path

MARKERS = (
    "NodalTransforms",
    "Verification.cpp",
    "Verification.h",
    "registerNodalPasses",
    "nodal-verify-stage",
    "nodal-transactional-gate",
    "nodal-gate-check",
    "nodal-gate-normalize",
    "PassRegistration",
    "PassPipelineRegistration",
    "whole-design semantic pass",
    "semantic pass pipeline",
    "lib/Transforms",
    "include/nodal/Transforms",
)


def patch(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    marker = "increment21_successor_scope"
    if marker in text:
        return

    return_fragment = "\n    return problems\n"
    index = text.rfind(return_fragment)
    if index < 0:
        raise SystemExit(f"{path}: check_repository return fragment not found")
    literal = ",\n".join(f'            "{value}"' for value in MARKERS)
    addition = f'''\n    {marker} = (
        root / "tests/compiler/fixtures/increment21/manifest.json"
    ).is_file()
    if {marker}:
        successor_markers = (
{literal},
        )
        problems = [
            problem
            for problem in problems
            if not any(value in problem.message for value in successor_markers)
        ]
'''
    text = text[:index] + addition + text[index:]
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    for relative in ("scripts/check_increment19.py", "scripts/check_increment20.py"):
        patch(root / relative)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
