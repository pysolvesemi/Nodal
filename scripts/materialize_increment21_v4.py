#!/usr/bin/env python3
"""Materialize Increment 21 with pinned APIs and explicit ownership."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


OWNERSHIP = """# Increment 21 native staged semantic verification pipeline.
/core/compiler/include/nodal/Transforms/ @pysolvesemi
/core/compiler/lib/Transforms/ @pysolvesemi
/scripts/check_increment21.py @pysolvesemi
/tests/compiler/test_increment21.py @pysolvesemi
/tests/compiler/fixtures/increment21/ @pysolvesemi
/.github/workflows/increment-21-native-verification-pipeline.yml @pysolvesemi
/docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md @pysolvesemi
/docs/implementation/increment21-native-verification-pipeline.md @pysolvesemi
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("materialize_increment21_v3.py")), str(root)],
        check=True,
    )

    codeowners = root / ".github/CODEOWNERS"
    text = codeowners.read_text(encoding="utf-8")
    marker = "# Increment 21 native staged semantic verification pipeline."
    if marker not in text:
        codeowners.write_text(text.rstrip() + "\n\n" + OWNERSHIP, encoding="utf-8")


if __name__ == "__main__":
    main()
