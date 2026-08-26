#!/usr/bin/env python3
"""Make source-sensitive Scala test classes execute serially."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()

    path = args.root.resolve() / "build.mill"
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


if __name__ == "__main__":
    main()
