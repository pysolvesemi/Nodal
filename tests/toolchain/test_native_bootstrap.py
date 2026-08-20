from __future__ import annotations

import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPOSITORY_ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import toolchain_lock as LOCK  # noqa: E402
import bootstrap_native_toolchain as BOOTSTRAP  # noqa: E402


class NativeToolchainBootstrapTests(unittest.TestCase):
    def isolated_discovery_environment(self, root: Path):
        """Keep discovery tests independent of developer and CI toolchain state."""
        return mock.patch.dict(
            os.environ,
            {"XDG_CACHE_HOME": str(root / "cache")},
            clear=True,
        )

    def test_source_plan_is_fully_pinned(self) -> None:
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        plan = BOOTSTRAP.plan_install(
            REPOSITORY_ROOT,
            lock,
            mode="source",
            prefix=Path("/tmp/nodal-toolchain"),
            host=("linux", "x86_64"),
            jobs=4,
        )
        self.assertEqual(plan["asset"]["filename"], "circt-full-sources.tar.gz")
        flattened = "\n".join(" ".join(command) for command in plan["commands"])
        self.assertIn("-DLLVM_EXTERNAL_PROJECTS=circt", flattened)
        self.assertIn("-DLLVM_ENABLE_PROJECTS=mlir", flattened)
        self.assertNotRegex(flattened.lower(), r"\b(main|master|head|latest)\b")

    def test_prebuilt_plan_uses_locked_checksum(self) -> None:
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        plan = BOOTSTRAP.plan_install(
            REPOSITORY_ROOT,
            lock,
            mode="prebuilt",
            prefix=Path("/tmp/nodal-toolchain"),
            host=("linux", "x86_64"),
        )
        self.assertEqual(
            plan["asset"]["sha256"],
            "2e3ed6051c43773b002c88c1172cb71f1430b71106835e6b4b5d5b631569fe28",
        )
        self.assertEqual(plan["commands"], [])

    def _create_fake_install(self, prefix: Path, lock: dict) -> None:
        for relative in LOCK.required_install_paths("linux"):
            path = prefix / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n", encoding="utf-8")
        BOOTSTRAP.write_manifest(
            prefix, lock, mode="prebuilt", host=("linux", "x86_64")
        )

    def test_discovery_accepts_matching_manifest(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        temporary_root = Path(temporary.name)
        prefix = temporary_root / "native"
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        self._create_fake_install(prefix, lock)
        with self.isolated_discovery_environment(temporary_root):
            found, rejected = BOOTSTRAP.discover(
                REPOSITORY_ROOT,
                lock,
                explicit=prefix,
                host=("linux", "x86_64"),
            )
        self.assertEqual(found, prefix.resolve())
        self.assertEqual(rejected, {})

    def test_discovery_rejects_mismatched_manifest(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        temporary_root = Path(temporary.name)
        prefix = temporary_root / "native"
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        self._create_fake_install(prefix, lock)
        manifest = prefix / LOCK.MANIFEST_NAME
        value = json.loads(manifest.read_text(encoding="utf-8"))
        value["llvm_commit"] = "0" * 40
        manifest.write_text(json.dumps(value), encoding="utf-8")
        with self.isolated_discovery_environment(temporary_root):
            found, rejected = BOOTSTRAP.discover(
                REPOSITORY_ROOT,
                lock,
                explicit=prefix,
                host=("linux", "x86_64"),
            )
        self.assertIsNone(found)
        self.assertIn(str(prefix.resolve()), rejected)

    def test_direct_environment_path_precedes_cache(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        temporary_root = Path(temporary.name)
        prefix = temporary_root / "explicit"
        lock = LOCK.load_lock(REPOSITORY_ROOT)
        with mock.patch.dict(
            os.environ,
            {
                "NODAL_NATIVE_TOOLCHAIN": str(prefix),
                "XDG_CACHE_HOME": str(temporary_root / "cache"),
            },
            clear=True,
        ):
            candidates = BOOTSTRAP.candidate_prefixes(
                REPOSITORY_ROOT, lock, host=("linux", "x86_64")
            )
        self.assertEqual(candidates[0], prefix.resolve())

    def test_safe_extract_rejects_path_traversal(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        archive_path = Path(temporary.name) / "bad.tar.gz"
        payload = b"escape"
        with tarfile.open(archive_path, "w:gz") as archive:
            info = tarfile.TarInfo("../escape.txt")
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
        with self.assertRaises(BOOTSTRAP.BootstrapError):
            BOOTSTRAP.safe_extract(archive_path, Path(temporary.name) / "out")


if __name__ == "__main__":
    unittest.main()
