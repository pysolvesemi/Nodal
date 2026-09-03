#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def main() -> int:
    path = Path(__file__).resolve().parent / "inc34_native_hardening_v9.py"
    text = path.read_text(encoding="utf-8")
    old = r'path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")'
    new = r'path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")'
    if new in text:
        print("Increment 34 v9 generator newline is already escaped.")
        return 0
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one generated-newline anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Increment 34 v9 generated mutation-test newline repaired.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
