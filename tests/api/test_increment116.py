from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment116.py"
SPEC = importlib.util.spec_from_file_location("check_increment116", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment116ContractTests(unittest.TestCase):
    def copy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in set(CHECKER.EXPECTED_FILES):
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return temporary, root

    def test_repository_satisfies_increment116_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_map_owned_field_handle_surface_is_required(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/src/nodal/RegisterFactoryApi.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "def value[A <: Data](field: map.Field[A])",
                "def removedValue[A <: Data](field: map.Field[A])",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC116-017", codes)

    def test_fixed_register_symbols_cannot_become_hdl_parameters(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / CHECKER.API_MANIFEST
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '"fixed_abi_symbols_are_parameters": false',
                '"fixed_abi_symbols_are_parameters": true',
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC116-019", codes)

    def test_negative_fixture_requires_one_stable_anchor(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        relative = CHECKER.NEGATIVE_FIXTURES[0][0]
        path = root / relative
        path.write_text(
            path.read_text(encoding="utf-8").replace("diagnostic-anchor:", "removed-anchor:"),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC116-033", codes)

    def test_design_gate_must_remain_approved(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/design-gates/NodalRegisterFactory-DG-v0.1.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "**Status:** Approved",
                "**Status:** Draft",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC116-041", codes)

    def test_transport_attachment_requires_contextual_type_class(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/src/nodal/RegisterFactoryApi.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "def attach[B](bus: B)(using transport: RegisterTransport[B])",
                "def attach[B](bus: B, transport: RegisterTransport[B])",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC116-017", codes)


if __name__ == "__main__":
    unittest.main()
