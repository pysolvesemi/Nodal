#!/usr/bin/env python3
"""Apply successor-aware predecessor compatibility and explicit native includes."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

PASS_TOKENS = (
    "PassRegistration",
    "PassPipelineRegistration",
    "registerNodalPasses",
    "NodalTransforms",
    "nodal-verify-stage",
    "nodal-transactional-gate",
    "nodal-gate-check",
    "nodal-gate-normalize",
    "add_subdirectory(Transforms)",
    "add_mlir_library",
)


def patch_predecessor(root: Path) -> None:
    path = root / "scripts/check_native_compiler_bootstrap.py"
    text = path.read_text(encoding="utf-8")

    tuple_match = re.search(
        r"^(FORBIDDEN_SEMANTICS\s*=\s*\(\n)(.*?^\))",
        text,
        re.MULTILINE | re.DOTALL,
    )
    if not tuple_match:
        raise SystemExit("native bootstrap checker lacks FORBIDDEN_SEMANTICS tuple")
    body = tuple_match.group(2)
    additions = "".join(f'    "{token}",\n' for token in PASS_TOKENS if f'"{token}"' not in body)
    if additions:
        replacement = tuple_match.group(1) + additions + body
        text = text[: tuple_match.start()] + replacement + text[tuple_match.end() :]

    if "increment21_successor" not in text:
        loop = re.search(
            r"^(?P<indent>\s*)for (?P<name>[A-Za-z_][A-Za-z0-9_]*) in FORBIDDEN_SEMANTICS:\s*$",
            text,
            re.MULTILINE,
        )
        if not loop:
            raise SystemExit("native bootstrap checker lacks FORBIDDEN_SEMANTICS loop")
        indent = loop.group("indent")
        name = loop.group("name")
        token_literal = ",\n".join(f'{indent}        "{token}"' for token in PASS_TOKENS)
        replacement = (
            f"{indent}increment21_successor = (\n"
            f"{indent}    root / 'tests/compiler/fixtures/increment21/manifest.json'\n"
            f"{indent}).is_file()\n"
            f"{loop.group(0)}\n"
            f"{indent}    if increment21_successor and {name} in (\n"
            f"{token_literal},\n"
            f"{indent}    ):\n"
            f"{indent}        continue"
        )
        text = text[: loop.start()] + replacement + text[loop.end() :]
    path.write_text(text, encoding="utf-8")


def patch_includes(root: Path) -> None:
    path = root / "core/compiler/lib/Transforms/Verification.cpp"
    text = path.read_text(encoding="utf-8")
    additions = (
        '#include "llvm/ADT/STLExtras.h"\n',
        '#include "llvm/ADT/Twine.h"\n',
        '#include "llvm/Support/ErrorHandling.h"\n',
    )
    anchor = '#include "llvm/ADT/SmallVector.h"\n'
    if anchor not in text:
        raise SystemExit("Verification.cpp lacks SmallVector include anchor")
    insertion = "".join(item for item in additions if item not in text)
    if insertion:
        text = text.replace(anchor, anchor + insertion, 1)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_predecessor(root)
    patch_includes(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
