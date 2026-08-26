#!/usr/bin/env python3
"""Strengthen Increment 23 checker and deterministic Scala-test contracts."""

from __future__ import annotations

import argparse
from pathlib import Path


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment23.py"
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


def patch_scala_test_parallelism(root: Path) -> None:
    path = root / "build.mill"
    text = path.read_text(encoding="utf-8")
    old = '''      object test extends ScalaTests:
        def mvnDeps = Seq(mvn"com.lihaoyi::utest:0.9.1")
        def testFramework = "utest.runner.Framework"
'''
    new = '''      object test extends ScalaTests:
        def mvnDeps = Seq(mvn"com.lihaoyi::utest:0.9.1")
        def testFramework = "utest.runner.Framework"
        // Source-semantic naming tests observe stack/source ownership and must
        // not race other elaboration test classes in separate forked JVMs.
        def testParallelism = false
'''
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(
            f"Scala test-module anchor must occur once, found {text.count(old)}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_checker(root)
    patch_scala_test_parallelism(root)


if __name__ == "__main__":
    main()
