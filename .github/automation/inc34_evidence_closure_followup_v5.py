#!/usr/bin/env python3
"""Normalize final Increment 34 closure wording for exact validation."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    path = root / "tests/compiler/fixtures/increment34/README.md"
    text = path.read_text(encoding="utf-8")
    old = '''implementation merge, post-merge validation, and separate evidence
closure PR #111 are retained by roadmap revision 1.45 and the validated manifest.
'''
    new = '''implementation merge, post-merge validation, and
separate evidence closure PR #111 are retained by roadmap revision 1.45 and the
validated manifest.
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "separate evidence closure PR #111 are retained" not in text:
        raise SystemExit("Increment 34 closure wording anchor was not found")
    path.write_text(text, encoding="utf-8")
    print("Increment 34 closure wording normalized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
