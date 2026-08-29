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
