#!/usr/bin/env python3
"""Strengthen mutation-sensitive Increment 23 checker contracts."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()

    path = args.root.resolve() / "scripts/check_increment23.py"
    text = path.read_text(encoding="utf-8")
    anchor = "\n    return problems\n\n\ndef main("
    if text.count(anchor) != 1:
        raise RuntimeError("Increment 23 checker return anchor is not unique")

    checks = r'''
    if "  output << candidate;\n  return success();" not in backend:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "backend candidate must be published only after target verify and reparse hooks",
            )
        )
    for owned_attribute in (
        "nodal.backend.shaped_layout",
        "nodal.backend.materialization",
        "nodal.backend.naming",
    ):
        if owned_attribute not in backend:
            problems.append(
                Problem(
                    "NODAL-INC23-004",
                    f"backend profile ownership check lacks attribute: {owned_attribute}",
                )
            )
'''
    text = text.replace(anchor, "\n" + checks + anchor, 1)
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
