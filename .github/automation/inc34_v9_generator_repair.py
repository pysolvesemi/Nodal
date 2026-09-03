#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    path = Path(__file__).resolve().parent / "inc34_native_hardening_v9.py"
    text = path.read_text(encoding="utf-8")
    text = replace_required(
        text,
        r'path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")',
        r'path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")',
        "generated mutation-test newline",
    )
    text = replace_required(
        text,
        '''                    "int64_t nextAssignmentOrder = 0;",
                    "int64_t removedNextAssignmentOrder = 0;",
                    1,
''',
        '''                    "structured assignment order must be contiguous and authored",
                    "removed structured assignment order validation",
                    1,
''',
        "structured assignment-order mutation",
    )
    path.write_text(text, encoding="utf-8")
    print("Increment 34 v9 generated mutations repaired.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
