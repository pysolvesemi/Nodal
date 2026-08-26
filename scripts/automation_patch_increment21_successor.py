#!/usr/bin/env python3
"""Make the Increment 21 checker accept the completed Increment 22 successor state."""

from __future__ import annotations

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    root = Path(__file__).resolve().parents[1]

    checker_path = root / "scripts/check_increment21.py"
    checker = checker_path.read_text(encoding="utf-8")
    checker = replace_once(
        checker,
        '''    increment22_unchecked = "- [ ] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
''',
        '''    increment22_unchecked = "- [ ] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
    increment22_checked = "- [x] **Increment 22 — Cross-layer diagnostic mapping**" in roadmap
''',
        "declare Increment 22 successor states",
    )
    checker = replace_once(
        checker,
        '''    if not increment22_unchecked:
        problems.append(Problem("NODAL-INC21-011", "Increment 22 must remain unchecked"))
''',
        '''    if revision < (1, 26):
        if not increment22_unchecked:
            problems.append(
                Problem(
                    "NODAL-INC21-011",
                    "Increment 22 must remain unchecked before roadmap revision 1.26",
                )
            )
    elif not increment22_checked:
        problems.append(
            Problem(
                "NODAL-INC21-011",
                "Increment 22 must be checked at roadmap revision 1.26 or later",
            )
        )
''',
        "make Increment 22 successor state revision-aware",
    )
    checker_path.write_text(checker, encoding="utf-8")

    tests_path = root / "tests/compiler/test_increment21.py"
    tests = tests_path.read_text(encoding="utf-8")
    tests = replace_once(
        tests,
        '''            .replace(
                "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                1,
            ),
''',
        '''            .replace(
                "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                1,
            )
            .replace(
                "- [ ] **Increment 22 — Cross-layer diagnostic mapping**",
                "- [x] **Increment 22 — Cross-layer diagnostic mapping**",
                1,
            ),
''',
        "make successor-state test close Increment 22",
    )
    anchor = '''

if __name__ == "__main__":
    unittest.main()
'''
    method = '''
    def test_rejects_unchecked_increment22_at_revision_126(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "**Revision:** 1.25",
                "**Revision:** 1.26",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-011", self.codes(root))
'''
    tests = replace_once(
        tests,
        anchor,
        method + anchor,
        "add revision 1.26 mutation test",
    )
    tests_path.write_text(tests, encoding="utf-8")


if __name__ == "__main__":
    main()
