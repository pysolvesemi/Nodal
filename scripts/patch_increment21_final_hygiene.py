#!/usr/bin/env python3
"""Apply final warning- and stream-safe Increment 21 native verifier rewrites."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    path = args.root.resolve() / "core/compiler/lib/Transforms/Verification.cpp"
    text = path.read_text(encoding="utf-8")
    text = text.replace(
        '<< "NODAL-VERIFY-PIPELINE-001: unknown stage \'" << stage\n'
        '                                 << "\'";',
        '<< "NODAL-VERIFY-PIPELINE-001: unknown stage \'" << stage.getValue()\n'
        '                                 << "\'";',
    )
    text = text.replace(
        "#include <initializer_list>\n",
        "#include <initializer_list>\n#include <memory>\n",
        1,
    ) if "#include <memory>" not in text else text
    path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
