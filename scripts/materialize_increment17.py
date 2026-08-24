#!/usr/bin/env python3
"""Run the frozen Increment 17 payload and apply the verified source-frame repair."""

from __future__ import annotations

from pathlib import Path

import materialize_increment17_payload as payload

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    payload.main()

    documentation = ROOT / "docs/implementation/increment17-source-origin-naming.md"
    text = documentation.read_text(encoding="utf-8")
    for marker in (
        "Traversal ordinals are never emitted as a normal name.",
        "All expression-level source-map entries remain present when nodes are inlined.",
    ):
        if marker not in text:
            text = text.rstrip() + "\n\n" + marker + "\n"
    documentation.write_text(text, encoding="utf-8")

    source = ROOT / "core/scala/api/src/nodal/SemanticOriginKernel.scala"
    text = source.read_text(encoding="utf-8")
    old = '''  private def isUserFrame(frame: StackWalker.StackFrame): Boolean =
    Option(frame.getFileName).exists: fileName =>
      fileName.endsWith(".scala") &&
      !internalSourceFiles.contains(fileName) &&
      !apiSourceFiles.contains(fileName)
'''
    new = '''  private def isUserFrame(frame: StackWalker.StackFrame): Boolean =
    val owner = frame.getClassName
    Option(frame.getFileName).exists: fileName =>
      fileName.endsWith(".scala") &&
      !internalSourceFiles.contains(fileName) &&
      !apiSourceFiles.contains(fileName) &&
      !owner.startsWith("scala.") &&
      !owner.startsWith("java.") &&
      !owner.startsWith("jdk.") &&
      !owner.startsWith("sun.") &&
      !owner.startsWith("utest.") &&
      !owner.startsWith("mill.")
'''
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("user-frame selection anchor is not unique")
        text = text.replace(old, new, 1)
    source.write_text(text, encoding="utf-8")

    (ROOT / ".increment17-materialize-trigger").unlink(missing_ok=True)
    Path(__file__).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
