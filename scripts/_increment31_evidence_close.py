#!/usr/bin/env python3
"""Record accepted Increment 31 evidence and close the roadmap state."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BRANCH = "increment/31-evidence-closure"
IMPLEMENTATION_HEAD = "647c79d1d78848c492b3128bd79502b6ef8664de"
MERGE_COMMIT = "1662b79f5f99686de4af2ed8a016fe8acf5c784e"
PULL_REQUEST = 88
ORIGINAL_DRAFT_PULL_REQUEST = 87
DEDICATED_RUN = 33244625475
CORE_CI_RUN = 33244625490
INHERITED_RUNS = {
    "increment13": 33244625449,
    "increment14": 33244625477,
    "increment15": 33244625595,
    "increment16": 33244625435,
    "increment17": 33244625495,
    "increment18": 33244625546,
    "increment19": 33244625497,
    "increment20": 33244625473,
    "increment21": 33244625547,
    "increment22": 33244625471,
    "increment23": 33244625602,
    "increment24": 33244625557,
    "increment25": 33244625500,
    "increment26": 33244625488,
    "increment27": 33244625446,
    "increment28": 33244625504,
    "increment29": 33244625508,
    "increment30": 33244625441,
}
TEMPORARY_PATHS = (
    ROOT / "scripts/_increment31_evidence_close.py",
    ROOT / ".github/workflows/_increment31_evidence_close.yml",
)


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one replacement target, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{path}: start marker is absent")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{path}: end marker is absent")
    path.write_text(
        text[:start_index] + replacement + text[end_index:],
        encoding="utf-8",
    )


def update_manifest() -> None:
    path = ROOT / "tests/compiler/fixtures/increment31/manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("status") != "implemented-awaiting-evidence":
        raise RuntimeError("Increment 31 manifest is not awaiting evidence")
    evidence = dict(manifest.get("evidence", {}))
    evidence.update(
        {
            "pull_request": PULL_REQUEST,
            "original_draft_pull_request": ORIGINAL_DRAFT_PULL_REQUEST,
            "implementation_head": IMPLEMENTATION_HEAD,
            "merge_commit": MERGE_COMMIT,
            "dedicated_run": DEDICATED_RUN,
            "core_ci_run": CORE_CI_RUN,
            "inherited_runs": INHERITED_RUNS,
        }
    )
    manifest["status"] = "validated-potential-flow-access"
    manifest["evidence"] = evidence
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def update_roadmap() -> None:
    path = ROOT / "docs/roadmap/nodal-development-todo.md"
    replace_once(path, "**Revision:** 1.40", "**Revision:** 1.41")
    replace_once(path, "**Updated:** 2026-08-28", "**Updated:** 2026-08-29")
    old = (
        "- [ ] **Increment 31 — Potential and flow access functions**\n"
        "  - Implement `V`, `I`, discipline-specific access, one/two-node forms, "
        "branches, probes, and validation."
    )
    new = (
        "- [x] **Increment 31 — Potential and flow access functions**\n"
        "  - Implement `V`, `I`, discipline-specific access, one/two-node forms, "
        "branches, probes, and validation.\n"
        "  - Evidence: implementation PR [#88](https://github.com/pysolvesemi/Nodal/pull/88), "
        "original draft PR [#87](https://github.com/pysolvesemi/Nodal/pull/87), "
        "dedicated validation run [33244625475](https://github.com/pysolvesemi/Nodal/actions/runs/33244625475), "
        "merge commit [`1662b79f`](https://github.com/pysolvesemi/Nodal/commit/1662b79f5f99686de4af2ed8a016fe8acf5c784e), "
        "and Core CI run [33244625490](https://github.com/pysolvesemi/Nodal/actions/runs/33244625490)."
    )
    replace_once(path, old, new)


def update_increment30_checker() -> None:
    path = ROOT / "scripts/check_increment30.py"
    replace_once(
        path,
        '    "tests/compiler/fixtures/increment29/manifest.json",\n',
        '    "tests/compiler/fixtures/increment29/manifest.json",\n'
        '    "tests/compiler/fixtures/increment31/manifest.json",\n',
    )
    predecessor_load = (
        "    predecessor = load_json(\n"
        '        root / "tests/compiler/fixtures/increment29/manifest.json",\n'
        "        problems,\n"
        '        "NODAL-INC30-009",\n'
        "    )\n"
    )
    successor_load = predecessor_load + (
        "    successor = load_json(\n"
        '        root / "tests/compiler/fixtures/increment31/manifest.json",\n'
        "        problems,\n"
        '        "NODAL-INC30-006",\n'
        "    )\n"
    )
    replace_once(path, predecessor_load, successor_load)

    start = (
        '    increment31_open = "- [ ] **Increment 31 — Potential and flow access functions**" '
        "in roadmap\n"
    )
    end = "    return problems\n"
    replacement = """    increment31_open = "- [ ] **Increment 31 — Potential and flow access functions**" in roadmap
    increment31_done = "- [x] **Increment 31 — Potential and flow access functions**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    successor_status = successor.get("status")
    successor_evidence = successor.get("evidence", {})

    if not increment29_done or rev < (1, 37):
        problems.append(Problem("NODAL-INC30-006", "validated Increment 29 baseline is absent"))
    if status in {"implementation-started", "implemented-awaiting-evidence"}:
        if not increment30_open or increment30_done:
            problems.append(Problem("NODAL-INC30-006", "pre-evidence state must leave Increment 30 unchecked"))
    elif status == "validated-analog-numeric-typing":
        if not increment30_done or rev < (1, 38):
            problems.append(Problem("NODAL-INC30-006", "validated state must close Increment 30 at revision 1.38 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC30-006", f"validated manifest lacks integer evidence field: {field}"))

    if successor.get("increment") != 31 or successor.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC30-006", "Increment 31 successor identity mismatch"))
    if increment31_open == increment31_done:
        problems.append(Problem("NODAL-INC30-006", "Increment 31 roadmap state is missing or ambiguous"))
    elif successor_status in {"implementation-started", "implemented-awaiting-evidence"}:
        if not increment31_open:
            problems.append(Problem("NODAL-INC30-006", "Increment 31 pre-evidence state is inconsistent"))
    elif successor_status == "validated-potential-flow-access":
        if not increment31_done or rev < (1, 41):
            problems.append(Problem("NODAL-INC30-006", "validated Increment 31 state is inconsistent"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(successor_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC30-006",
                        f"validated Increment 31 lacks integer evidence field: {field}",
                    )
                )
        for field in ("implementation_head", "merge_commit"):
            value = successor_evidence.get(field)
            if not isinstance(value, str) or len(value) != 40:
                problems.append(
                    Problem(
                        "NODAL-INC30-006",
                        f"validated Increment 31 lacks commit evidence field: {field}",
                    )
                )
    else:
        problems.append(Problem("NODAL-INC30-006", "unsupported Increment 31 successor status"))

"""
    replace_between(path, start, end, replacement)


def update_increment30_tests() -> None:
    path = ROOT / "tests/compiler/test_increment30.py"
    marker = '\n\nif __name__ == "__main__":\n'
    test = r"""
    def test_rejects_inconsistent_increment31_successor_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment31/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap_path.read_text(encoding="utf-8")
        unchecked = "- [ ] **Increment 31 — Potential and flow access functions**"
        checked = "- [x] **Increment 31 — Potential and flow access functions**"
        if checked not in text:
            text = text.replace(unchecked, checked, 1)
        roadmap_path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC30-006", self.codes(root))
"""
    replace_once(path, marker, "\n" + test + marker)


def update_increment31_tests() -> None:
    path = ROOT / "tests/compiler/test_increment31.py"
    start = "    def test_rejects_closed_roadmap_item_before_evidence(self) -> None:\n"
    end = "    def test_rejects_scaffold_manifest_status(self) -> None:\n"
    replacement = r"""    def test_rejects_closed_roadmap_item_before_evidence(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment31/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap_path.read_text(encoding="utf-8")
        unchecked = "- [ ] **Increment 31 — Potential and flow access functions**"
        checked = "- [x] **Increment 31 — Potential and flow access functions**"
        if checked not in text:
            text = text.replace(unchecked, checked, 1)
        roadmap_path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-006", self.codes(root))

"""
    replace_between(path, start, end, replacement)


def update_increment30_workflow() -> None:
    path = ROOT / ".github/workflows/increment-30-analog-numeric-types.yml"
    old = """          manifest = json.loads(
              Path("tests/compiler/fixtures/increment30/manifest.json").read_text()
          )
          status = manifest["status"]
          assert status in {
              "implemented-awaiting-evidence",
              "validated-analog-numeric-typing",
          }
          assert manifest["implementation"]["verify_pass"] == "nodal-verify-analog-numeric"
          assert manifest["implementation"]["fold_pass"] == "nodal-fold-analog-constants"

          roadmap = Path("docs/roadmap/nodal-development-todo.md").read_text()
          increment30_open = (
              "- [ ] **Increment 30 — Analog numeric types and expression typing**"
          )
          increment30_closed = (
              "- [x] **Increment 30 — Analog numeric types and expression typing**"
          )
          assert "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
          assert (increment30_open in roadmap) != (increment30_closed in roadmap)

          if status == "implemented-awaiting-evidence":
              assert increment30_open in roadmap
          else:
              assert increment30_closed in roadmap
              assert "- [ ] **Increment 31 — Potential and flow access functions**" in roadmap
"""
    new = """          manifest = json.loads(
              Path("tests/compiler/fixtures/increment30/manifest.json").read_text()
          )
          successor = json.loads(
              Path("tests/compiler/fixtures/increment31/manifest.json").read_text()
          )
          status = manifest["status"]
          assert status in {
              "implemented-awaiting-evidence",
              "validated-analog-numeric-typing",
          }
          assert manifest["implementation"]["verify_pass"] == "nodal-verify-analog-numeric"
          assert manifest["implementation"]["fold_pass"] == "nodal-fold-analog-constants"

          roadmap = Path("docs/roadmap/nodal-development-todo.md").read_text()
          increment30_open = (
              "- [ ] **Increment 30 — Analog numeric types and expression typing**"
          )
          increment30_closed = (
              "- [x] **Increment 30 — Analog numeric types and expression typing**"
          )
          increment31_open = (
              "- [ ] **Increment 31 — Potential and flow access functions**"
          )
          increment31_closed = (
              "- [x] **Increment 31 — Potential and flow access functions**"
          )
          assert "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
          assert (increment30_open in roadmap) != (increment30_closed in roadmap)
          assert (increment31_open in roadmap) != (increment31_closed in roadmap)

          if status == "implemented-awaiting-evidence":
              assert increment30_open in roadmap
          else:
              assert increment30_closed in roadmap

          successor_status = successor["status"]
          if successor_status in {
              "implementation-started",
              "implemented-awaiting-evidence",
          }:
              assert increment31_open in roadmap
          else:
              assert successor_status == "validated-potential-flow-access"
              assert increment31_closed in roadmap
"""
    replace_once(path, old, new)


def remove_temporary_files() -> None:
    for path in TEMPORARY_PATHS:
        path.unlink(missing_ok=True)


def validate() -> None:
    for increment in range(18, 32):
        run("python3", f"scripts/check_increment{increment}.py")
    run("python3", "tests/compiler/test_increment30.py")
    run("python3", "tests/compiler/test_increment31.py")
    run(
        "python3",
        "-m",
        "unittest",
        "discover",
        "-s",
        "tests/compiler",
        "-p",
        "test_*.py",
    )
    run("python3", "scripts/check_markdown.py")
    run("python3", "scripts/check_package_visibility.py")
    run("git", "diff", "--check")


def commit_and_push() -> None:
    run("git", "config", "user.name", "github-actions[bot]")
    run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    run("git", "add", "-A")
    staged = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=ROOT,
    )
    if staged.returncode == 0:
        raise RuntimeError("evidence closure produced no staged changes")
    if staged.returncode != 1:
        raise RuntimeError("could not inspect staged closure changes")
    run("git", "commit", "-m", "docs(increment31): record accepted evidence")
    run("git", "push", "origin", f"HEAD:{BRANCH}")


def main() -> None:
    update_manifest()
    update_roadmap()
    update_increment30_checker()
    update_increment30_tests()
    update_increment31_tests()
    update_increment30_workflow()
    remove_temporary_files()
    validate()
    commit_and_push()


if __name__ == "__main__":
    main()
