#!/usr/bin/env python3
"""Remove Increment 33 bridge-only -Werror warnings without changing semantics."""

from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace_once(
    "core/scala/bridge/src/nodal/bridge/AnalogProceduralEvidence.scala",
    '  private def option(value: Option[String]): String = value.map(quoted).getOrElse("null")\n\n',
    "",
)

path = Path("core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala")
text = path.read_text(encoding="utf-8")
signature = """      operandTypes: Vector[String] = Vector.empty,
      resultTypes: Vector[String] = Vector.empty,
      semanticPath: String,
      source: Option[AnalogProceduralRuntime.Source]
"""
replacement = """      operandTypes: Vector[String] = Vector.empty,
      resultTypes: Vector[String] = Vector.empty,
      source: Option[AnalogProceduralRuntime.Source]
"""
if text.count(signature) != 1:
    raise SystemExit(
        f"AnalogProceduralMlir.scala: expected one operation signature, found {text.count(signature)}"
    )
text = text.replace(signature, replacement)
pattern = re.compile(r"(?m)^\s+semanticPath = [^\n]+,\n")
text, count = pattern.subn("", text)
if count != 6:
    raise SystemExit(
        f"AnalogProceduralMlir.scala: expected six operation semanticPath arguments, found {count}"
    )
path.write_text(text, encoding="utf-8")
