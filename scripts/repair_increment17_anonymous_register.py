#!/usr/bin/env python3
"""Ensure repeated inferred declaration bindings fall back to generated identity."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core/scala/api/src/nodal/SemanticOriginKernel.scala"


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    old = '''      val allocated = allocate(
        captures.toVector.map: capture =>
          val explicit = capture.explicitName.map(cleanIdentifier(_, capture.kind))
          val binding = declarationBinding(capture.site, capture.kind)
            .orElse(memberBinding(members, capture.value))
          val sink = Option(sinkHints.get(capture.value)).map(name => s"${name}_source")
'''
    new = '''      val claimedBindings = mutable.HashSet.empty[String]
      val allocated = allocate(
        captures.toVector.map: capture =>
          val explicit = capture.explicitName.map(cleanIdentifier(_, capture.kind))
          val binding = declarationBinding(capture.site, capture.kind)
            .orElse(memberBinding(members, capture.value))
            .filter(claimedBindings.add)
          val sink = Option(sinkHints.get(capture.value)).map(name => s"${name}_source")
'''
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("declaration binding claim anchor is not unique")
        text = text.replace(old, new, 1)
    SOURCE.write_text(text, encoding="utf-8")
    Path(__file__).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
