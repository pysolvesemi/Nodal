#!/usr/bin/env python3
from __future__ import annotations

import base64
import lzma
from pathlib import Path

HERE = Path(__file__).resolve().parent
payload = "".join(
    path.read_text(encoding="ascii").strip()
    for path in sorted(HERE.glob("_increment31_payload_*.txt"))
)
source = lzma.decompress(base64.b85decode(payload.encode("ascii"))).decode("utf-8")
exec(compile(source, __file__, "exec"))

fixture = HERE.parent / "core/compiler/test/IR/potential-flow-access.mlir"
text = fixture.read_text(encoding="utf-8")
profile = """module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
"""
if not text.startswith("module {\n"):
    raise RuntimeError("Increment 31 positive fixture has an unexpected module header")
fixture.write_text(profile + text.removeprefix("module {\n"), encoding="utf-8")
