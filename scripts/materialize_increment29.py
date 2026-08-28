#!/usr/bin/env python3
"""One-shot Increment 29 repository materializer."""
from __future__ import annotations
import base64
import zlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def payload(kind: str) -> bytes:
    parts = sorted((ROOT / "scripts").glob(f".increment29_{kind}_*.b85"))
    if not parts:
        raise RuntimeError(f"missing Increment 29 {kind} payload")
    encoded = "".join(path.read_text(encoding="utf-8") for path in parts)
    return zlib.decompress(base64.b85decode(encoded))
exec(compile(payload("core"), "materialize_increment29_core.py", "exec"), globals())
exec(compile(payload("contract"), "materialize_increment29_contract.py", "exec"), globals())

# The materialized mutation must remove the helper contract itself, not only
# one formatted call site, so the checker proves the structural-envelope guard.
test_path = ROOT / "tests/compiler/test_increment29.py"
test_text = test_path.read_text(encoding="utf-8")
old = 'replace("hasBoundedRange(&operation)", "acceptUnboundedRange(&operation)", 1)'
new = 'replace("hasBoundedRange", "acceptUnboundedRange")'
if test_text.count(old) != 1:
    raise RuntimeError("unexpected Increment 29 structural-envelope mutation anchor")
test_path.write_text(test_text.replace(old, new, 1), encoding="utf-8")
