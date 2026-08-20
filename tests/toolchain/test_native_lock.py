from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPOSITORY_ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import toolchain_lock as LOCK  # noqa: E402


class NativeToolchainLockTests(unittest.TestCase):
    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(REPOSITORY_ROOT / "toolchains", root / "toolchains")
        return temporary, root

    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in LOCK.validate_lock(root)}

    def test_current_repository_lock_passes(self) -> None:
        self.assertEqual(LOCK.validate_lock(REPOSITORY_ROOT), [])

    def test_lock_uses_exact_circt_llvm_pair(self) -> None:
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        self.assertEqual(
            lock["native"]["circt"]["commit"],
            "87898a876f730a2ebc607dc9b83da487cba49119",
        )
        self.assertEqual(
            lock["native"]["circt"]["llvm_submodule_commit"],
            "b1c56fb53a9c76d6b045ede49083b647ae049ffe",
        )
        self.assertEqual(
            lock["native"]["llvm"]["commit"],
            lock["native"]["circt"]["llvm_submodule_commit"],
        )

    def test_rejects_mutable_circt_ref(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "toolchains/lock.json"
        lock = json.loads(path.read_text(encoding="utf-8"))
        lock["native"]["circt"]["release_tag"] = "main"
        path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-TOOLCHAIN-009", self.problem_codes(root))

    def test_rejects_llvm_pair_drift(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "toolchains/lock.json"
        lock = json.loads(path.read_text(encoding="utf-8"))
        lock["native"]["llvm"]["commit"] = "0" * 40
        path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-TOOLCHAIN-011", self.problem_codes(root))

    def test_rejects_checksum_file_drift(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        checksum = root / "toolchains/checksums/circt-full-shared-linux-x64.tar.gz.sha256"
        checksum.write_text(
            "0" * 64 + "  circt-full-shared-linux-x64.tar.gz\n", encoding="utf-8"
        )
        self.assertIn("NODAL-TOOLCHAIN-021", self.problem_codes(root))

    def test_selects_supported_prebuilt_assets(self) -> None:
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        self.assertEqual(
            LOCK.prebuilt_asset(lock, ("linux", "x86_64"))["filename"],
            "circt-full-shared-linux-x64.tar.gz",
        )
        self.assertEqual(
            LOCK.prebuilt_asset(lock, ("macos", "aarch64"))["filename"],
            "circt-full-shared-macos-arm64.tar.gz",
        )
        self.assertIsNone(LOCK.prebuilt_asset(lock, ("windows", "x86_64")))

    def test_version_comparison(self) -> None:
        self.assertTrue(LOCK.version_at_least("3.31.0", "3.20.0"))
        self.assertTrue(LOCK.version_at_least("1.10", "1.10.0"))
        self.assertFalse(LOCK.version_at_least("1.9.9", "1.10.0"))


if __name__ == "__main__":
    unittest.main()
