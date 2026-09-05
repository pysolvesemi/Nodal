from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("check_increment37", ROOT / "scripts/check_increment37.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
MATRIX_SPEC = importlib.util.spec_from_file_location(
    "increment37_matrix", ROOT / "tests/compiler/fixtures/increment37/run_native_matrix.py")
assert MATRIX_SPEC and MATRIX_SPEC.loader
MATRIX = importlib.util.module_from_spec(MATRIX_SPEC)
MATRIX_SPEC.loader.exec_module(MATRIX)


class Increment37ContractTests(unittest.TestCase):
    def test_repository(self) -> None:
        CHECK.check_repository(ROOT)

    def test_profileless_fixture_checks_both_capability_boundaries(self) -> None:
        for target in ("--nodal-to-verilog-a", "--nodal-to-verilog-ams"):
            self.assertEqual(MATRIX.expected_backend_diagnostic("module {}", target),
                             "NODAL-BACKEND-CAPABILITY-001")

    def test_matching_profile_reaches_capability_boundary(self) -> None:
        for profile in ("verilog-a", "verilog-ams"):
            text = f'module attributes {{nodal.backend.profile = "{profile}"}} {{}}'
            self.assertEqual(MATRIX.expected_backend_diagnostic(text, f"--nodal-to-{profile}"),
                             "NODAL-BACKEND-CAPABILITY-001")

    def test_mismatched_profile_is_not_misreported_as_capability_failure(self) -> None:
        for profile, target in (("verilog-a", "--nodal-to-verilog-ams"),
                                ("verilog-ams", "--nodal-to-verilog-a")):
            text = f'module attributes {{nodal.backend.profile = "{profile}"}} {{}}'
            self.assertEqual(MATRIX.expected_backend_diagnostic(text, target),
                             "NODAL-BACKEND-PROFILE-002")

    def test_unknown_or_duplicate_profiles_are_not_accepted_by_matrix(self) -> None:
        for text in ('nodal.backend.profile = "unknown"',
                     'nodal.backend.profile = "verilog-a", nodal.backend.profile = "verilog-ams"'):
            with self.assertRaises(AssertionError):
                MATRIX.expected_backend_diagnostic(text, "--nodal-to-verilog-a")
        with self.assertRaises(AssertionError):
            MATRIX.expected_backend_diagnostic("module {}", "--unrecognized")

    def test_premature_closure_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in (*CHECK.REQUIRED, "tests/compiler/fixtures/increment37/manifest.json",
                         "docs/roadmap/nodal-development-todo.md"):
                destination = root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, destination)
            roadmap = root / "docs/roadmap/nodal-development-todo.md"
            roadmap.write_text(roadmap.read_text().replace("- [ ] **Increment 37", "- [x] **Increment 37"))
            with self.assertRaisesRegex(AssertionError, "premature"):
                CHECK.check_repository(root)


if __name__ == "__main__":
    unittest.main()
