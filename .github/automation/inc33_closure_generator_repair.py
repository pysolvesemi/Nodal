#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def main() -> int:
    path = Path(__file__).resolve().parent / "inc33_evidence_closure.py"
    text = path.read_text(encoding="utf-8")
    anchor = '''    text = read(path)
    marker = "\\n\\nif __name__ == \\\"__main__\\\":\\n"
'''
    replacement = '''    text = read(path)
    old_early = \'\'\'    def test_increment33_cannot_close_early(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            roadmap = clone / MODULE.ROADMAP
            text = roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                "- [x] **Increment 33 — Analog variables and procedural assignment**",
                1,
            )
            roadmap.write_text(text, encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-028", codes)
\'\'\'
    new_early = \'\'\'    def test_increment33_cannot_close_early(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.INCREMENT33
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "implementation-in-progress"
            manifest["validation"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\\\\n",
                encoding="utf-8",
            )
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-028", codes)
\'\'\'
    if old_early in text:
        text = text.replace(old_early, new_early, 1)
    elif "manifest[\\\"status\\\"] = \\\"implementation-in-progress\\\"" not in text:
        raise SystemExit("Increment 32 early-closure mutation anchor was not found")
    marker = "\\n\\nif __name__ == \\\"__main__\\\":\\n"
'''
    if replacement in text:
        print("Increment 33 closure mutation generator is already repaired.")
        return 0
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"expected one Increment 32 mutation-function anchor, found {count}")
    path.write_text(text.replace(anchor, replacement, 1), encoding="utf-8")
    print("Increment 33 closure mutation generator repaired.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
