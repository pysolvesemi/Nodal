from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def load(name: str, relative: str):
    path = REPOSITORY_ROOT / relative
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


INCREMENT = load("check_increment9", "scripts/check_increment9.py")
MARKDOWN = load("check_markdown", "scripts/check_markdown.py")
PACKAGES = load("check_package_visibility", "scripts/check_package_visibility.py")
POLICY = load("check_contribution_policy", "scripts/check_contribution_policy.py")
LINT_TOOLS = load("bootstrap_lint_toolchain", "scripts/bootstrap_lint_toolchain.py")


class Increment9Tests(unittest.TestCase):
    def test_current_repository_contract_passes(self) -> None:
        self.assertEqual(INCREMENT.check_repository(REPOSITORY_ROOT), [])

    def test_rejects_unclosed_markdown_fence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bad.md").write_text("# Bad\n\n```scala\nobject Bad\n", encoding="utf-8")
            codes = {problem.code for problem in MARKDOWN.check_repository(root)}
            self.assertIn("NODAL-MD-006", codes)

    def test_rejects_missing_markdown_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bad.md").write_text("# Bad\n\n[missing](missing.md)\n", encoding="utf-8")
            codes = {problem.code for problem in MARKDOWN.check_repository(root)}
            self.assertIn("NODAL-MD-005", codes)

    def test_rejects_package_outside_module_owner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "core/scala/frontend/src/Bad.scala"
            path.parent.mkdir(parents=True)
            path.write_text("package nodal.api\n\nobject Bad\n", encoding="utf-8")
            codes = {problem.code for problem in PACKAGES.check_repository(root)}
            self.assertIn("NODAL-PKG-003", codes)

    def policy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        destination = root / ".github/change-policy.json"
        destination.parent.mkdir(parents=True)
        shutil.copy2(REPOSITORY_ROOT / ".github/change-policy.json", destination)
        return temporary, root

    def test_protected_api_change_requires_design_gate(self) -> None:
        temporary, root = self.policy_fixture()
        self.addCleanup(temporary.cleanup)
        problems = POLICY.check_repository(
            root,
            changed_files=["core/scala/api/src/nodal/Module.scala"],
            event_name="unit_test",
        )
        self.assertIn("NODAL-POLICY-005", {problem.code for problem in problems})

    def test_approved_design_gate_authorizes_scope(self) -> None:
        temporary, root = self.policy_fixture()
        self.addCleanup(temporary.cleanup)
        gate = root / "docs/design-gates/NodalPublicApi-DG-v0.1.md"
        gate.parent.mkdir(parents=True)
        gate.write_text(
            "# Gate\n\n**Status:** Approved\n**Scope:** public-api\n",
            encoding="utf-8",
        )
        problems = POLICY.check_repository(
            root,
            changed_files=[
                "core/scala/api/src/nodal/Module.scala",
                "docs/design-gates/NodalPublicApi-DG-v0.1.md",
            ],
            event_name="unit_test",
        )
        self.assertEqual(problems, [])

    def test_superseded_gate_is_accepted_with_approved_replacement(self) -> None:
        temporary, root = self.policy_fixture()
        self.addCleanup(temporary.cleanup)
        gate_dir = root / "docs/design-gates"
        gate_dir.mkdir(parents=True)
        old_gate = gate_dir / "NodalPublicApiCandidates-DG-v0.1.md"
        old_gate.write_text(
            "# Old gate\n\n**Status:** Superseded\n**Scope:** public-api\n"
            "**Superseded by:** NodalPublicApi-DG-v0.1.md\n",
            encoding="utf-8",
        )
        replacement = gate_dir / "NodalPublicApi-DG-v0.1.md"
        replacement.write_text(
            "# Replacement\n\n**Status:** Approved\n**Scope:** public-api\n",
            encoding="utf-8",
        )
        problems = POLICY.check_repository(
            root,
            changed_files=[
                "core/scala/api/src/nodal/Module.scala",
                "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
                "docs/design-gates/NodalPublicApi-DG-v0.1.md",
            ],
            event_name="unit_test",
        )
        self.assertEqual(problems, [])

    def test_superseded_gate_requires_replacement_reference(self) -> None:
        temporary, root = self.policy_fixture()
        self.addCleanup(temporary.cleanup)
        gate_dir = root / "docs/design-gates"
        gate_dir.mkdir(parents=True)
        old_gate = gate_dir / "NodalPublicApiCandidates-DG-v0.1.md"
        old_gate.write_text(
            "# Old gate\n\n**Status:** Superseded\n**Scope:** public-api\n",
            encoding="utf-8",
        )
        replacement = gate_dir / "NodalPublicApi-DG-v0.1.md"
        replacement.write_text(
            "# Replacement\n\n**Status:** Approved\n**Scope:** public-api\n",
            encoding="utf-8",
        )
        problems = POLICY.check_repository(
            root,
            changed_files=[
                "core/scala/api/src/nodal/Module.scala",
                "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
                "docs/design-gates/NodalPublicApi-DG-v0.1.md",
            ],
            event_name="unit_test",
        )
        self.assertIn("NODAL-POLICY-016", {problem.code for problem in problems})

    def test_pull_request_metadata_is_checked(self) -> None:
        temporary, root = self.policy_fixture()
        self.addCleanup(temporary.cleanup)
        event = root / "event.json"
        event.write_text(
            json.dumps(
                {
                    "pull_request": {
                        "base": {"ref": "dev"},
                        "head": {"ref": "feature/bad"},
                        "title": "Bad title",
                        "body": "",
                    }
                }
            ),
            encoding="utf-8",
        )
        problems = POLICY.check_repository(
            root,
            changed_files=[],
            event_name="pull_request",
            event_path=event,
        )
        codes = {problem.code for problem in problems}
        self.assertIn("NODAL-POLICY-007", codes)
        self.assertIn("NODAL-POLICY-008", codes)
        self.assertIn("NODAL-POLICY-012", codes)

    def test_lint_lock_dry_run_is_deterministic(self) -> None:
        payload = LINT_TOOLS.install_toolchain(
            REPOSITORY_ROOT / ".toolchains/lint-test",
            force=False,
            dry_run=True,
        )
        self.assertEqual(payload["lock_id"], "nodal-lint-2026.08.21")
        self.assertEqual(
            payload["packages"],
            ["clang-format==22.1.8", "clang-tidy==22.1.8"],
        )

    def test_rejects_changed_scalafmt_pin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in INCREMENT.EXPECTED_FILES:
                source = REPOSITORY_ROOT / relative
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
            for relative in (
                "build.mill",
                "scripts/nodal.py",
                ".github/workflows/ci.yml",
                ".github/CODEOWNERS",
            ):
                source = REPOSITORY_ROOT / relative
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
            path = root / "toolchains/lint-lock.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["scala"]["scalafmt_version"] = "0.0.0"
            path.write_text(json.dumps(value), encoding="utf-8")
            codes = {problem.code for problem in INCREMENT.check_repository(root)}
            self.assertIn("NODAL-INC9-014", codes)


if __name__ == "__main__":
    unittest.main()
