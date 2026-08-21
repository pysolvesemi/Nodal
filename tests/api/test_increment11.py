from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment11.py"
SPEC = importlib.util.spec_from_file_location("check_increment11", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment11ContractTests(unittest.TestCase):
    def copy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        relatives = set(CHECKER.EXPECTED_FILES) | {
            ".github/CODEOWNERS",
            "scripts/check_developer_commands.py",
            "scripts/nodal.py",
            "tests/developer/test_developer_commands.py",
        }
        for relative in relatives:
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return temporary, root

    def test_repository_satisfies_increment11_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_manifest_requires_native_symbolic_parameterization(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/public-api-v0.1.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["parameterization"]["native_parameterized_hdl_required"] = False
        payload["parameterization"]["module_specialization_by_value"] = True
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-020", codes)

    def test_backend_entry_point_is_frozen(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/src/nodal/CompilerApi.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace("def emit", "def generate"),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-019", codes)

    def test_rejected_input_output_helpers_cannot_return(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/src/nodal/CandidateApi.scala"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nprotected final def input[A <: Data](kind: DataType[A]): Signal[A] = ???\n",
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-018", codes)

    def test_candidate_gate_must_be_superseded(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "**Status:** Superseded",
                "**Status:** Approved",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-025", codes)


if __name__ == "__main__":
    unittest.main()
