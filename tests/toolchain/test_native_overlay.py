from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import toolchain_lock as LOCK
from native_toolchain import overlay as OVERLAY, state as STATE, install as INSTALL
import bootstrap_native_toolchain as CLI

NAME = "circt-inout-parameters-v1"


class NativeOverlayTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repo"
        self.root.mkdir()
        shutil.copytree(ROOT / "toolchains", self.root / "toolchains")
        self.lock = LOCK.load_lock(self.root)
        self.manifest = self.root / "toolchains/patches" / NAME / "manifest.json"
        self.prefix = self.root / "native"

    def change_manifest(self, fn):
        value = json.loads(self.manifest.read_text())
        fn(value)
        self.manifest.write_text(json.dumps(value))

    def profile(self):
        return OVERLAY.load_overlay(self.root, self.lock, NAME)

    def fake_base(self, prefix=None):
        prefix = prefix or self.prefix
        for relative in LOCK.required_install_paths("linux"):
            path = prefix / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
        (prefix / "lib/libCIRCTHW.so").write_bytes(b"base")
        STATE.write_manifest(prefix, self.lock, mode="prebuilt", host=("linux", "x86_64"))

    def fake_overlay(self):
        self.fake_base()
        profile = self.profile()
        (self.prefix / OVERLAY.OUTPUT).write_bytes(b"patched")
        receipt = self.prefix / "share/nodal/overlays" / NAME / "build.json"
        receipt.parent.mkdir(parents=True)
        receipt.write_text("{}")
        value = OVERLAY.identity(profile) | {"output_sha256": OVERLAY.sha256(self.prefix / OVERLAY.OUTPUT), "receipt_sha256": OVERLAY.sha256(receipt)}
        STATE.write_manifest(self.prefix, self.lock, mode="prebuilt", host=("linux", "x86_64"), overlay=value)

    def errors(self, required=None):
        return STATE.validate_install(self.prefix, self.lock, root=self.root, host=("linux", "x86_64"), required_overlay=required)

    def test_profile_preserves_pin_and_base_plan(self):
        base = STATE.plan_install(self.root, self.lock, mode="prebuilt", host=("linux", "x86_64"))
        repaired = STATE.plan_install(self.root, self.lock, mode="prebuilt", host=("linux", "x86_64"), overlay=NAME)
        self.assertNotIn("overlay", base)
        self.assertEqual(base["asset"], repaired["asset"])
        self.assertEqual(base["lock_id"], repaired["lock_id"])
        self.assertEqual(repaired["prefix"], base["prefix"] + "-" + NAME)
        self.assertEqual(repaired["overlay"]["id"], NAME)

    def test_wrong_revision_rejected(self):
        self.change_manifest(lambda v: v.update(circt_commit="0" * 40))
        with self.assertRaisesRegex(LOCK.LockError, "exact locked"):
            self.profile()

    def test_wrong_lock_digest_rejected(self):
        self.change_manifest(lambda v: v.update(base_lock_sha256="0" * 64))
        with self.assertRaisesRegex(LOCK.LockError, "exact locked"):
            self.profile()

    def test_patch_tamper_rejected(self):
        path = self.manifest.parent / "inout-parameter.patch"
        path.write_text(path.read_text() + "\n")
        with self.assertRaisesRegex(LOCK.LockError, "checksum"):
            self.profile()

    def test_patch_path_escape_rejected(self):
        self.change_manifest(lambda v: v["patch"].update(file="../outside.patch"))
        with self.assertRaisesRegex(LOCK.LockError, "relative"):
            self.profile()

    def test_manifest_has_no_command_language(self):
        self.change_manifest(lambda v: v.update(commands=["anything"]))
        with self.assertRaisesRegex(LOCK.LockError, "closed"):
            self.profile()

    def test_duplicate_source_rejected(self):
        self.change_manifest(lambda v: v["sources"].append(v["sources"][0]))
        with self.assertRaisesRegex(LOCK.LockError, "duplicate"):
            self.profile()

    def test_source_outside_component_rejected(self):
        self.change_manifest(lambda v: v["sources"][0].update(path="lib/Dialect/SV/Hack.cpp"))
        with self.assertRaisesRegex(LOCK.LockError, "outside"):
            self.profile()

    def test_other_host_rejected(self):
        with self.assertRaisesRegex(STATE.BootstrapError, "Linux x86_64"):
            STATE.plan_install(self.root, self.lock, mode="auto", host=("darwin", "arm64"), overlay=NAME)

    def test_source_mode_rejected(self):
        with self.assertRaisesRegex(STATE.BootstrapError, "prebuilt"):
            STATE.plan_install(self.root, self.lock, mode="source", overlay=NAME)

    def test_valid_overlay_identity(self):
        self.fake_overlay()
        self.assertEqual(self.errors(NAME), [])

    def test_output_tamper_rejected(self):
        self.fake_overlay()
        (self.prefix / OVERLAY.OUTPUT).write_bytes(b"wrong")
        self.assertIn("library checksum", " ".join(self.errors(NAME)))

    def test_receipt_tamper_rejected(self):
        self.fake_overlay()
        (self.prefix / "share/nodal/overlays" / NAME / "build.json").write_text("wrong")
        self.assertIn("receipt checksum", " ".join(self.errors(NAME)))

    def test_removed_overlay_identity_not_mislabeled_base(self):
        self.fake_overlay()
        STATE.write_manifest(self.prefix, self.lock, mode="prebuilt", host=("linux", "x86_64"))
        self.assertIn("without installed overlay", " ".join(self.errors()))

    def test_missing_required_profile_rejected(self):
        self.fake_base()
        self.assertIn("required managed overlay", " ".join(self.errors(NAME)))

    def test_force_cannot_replace_base(self):
        self.fake_base()
        before = (self.prefix / OVERLAY.OUTPUT).read_bytes()
        with self.assertRaisesRegex(STATE.BootstrapError, "fresh destination"):
            INSTALL.install(self.root, self.lock, mode="prebuilt", prefix=self.prefix, overlay=NAME, force=True)
        self.assertEqual((self.prefix / OVERLAY.OUTPUT).read_bytes(), before)

    def test_repeat_exact_overlay_is_noop(self):
        self.fake_overlay()
        with mock.patch.object(INSTALL, "download", side_effect=AssertionError("no download")):
            result = INSTALL.install(self.root, self.lock, mode="prebuilt", prefix=self.prefix, overlay=NAME)
        self.assertEqual(result["status"], "already-installed")

    def test_verify_never_falls_back_to_valid_prefix(self):
        self.fake_base()
        with mock.patch.dict(os.environ, {"NODAL_NATIVE_TOOLCHAIN": str(self.prefix)}):
            with mock.patch("sys.stderr"):
                self.assertEqual(CLI.main(["--root", str(self.root), "verify", "--toolchain", str(self.root / "missing")]), 1)

    def test_failed_build_never_creates_or_mutates_destination(self):
        cached = self.root / "cache/nodal/downloads" / self.lock["lock_id"] / "circt-full-shared-linux-x64.tar.gz"
        cached.parent.mkdir(parents=True)
        cached.write_bytes(b"archive fixture")
        asset = LOCK.prebuilt_asset(self.lock, ("linux", "x86_64"))
        def extract(_archive, directory):
            self.fake_base(directory)
        with mock.patch.dict(os.environ, {"XDG_CACHE_HOME": str(self.root / "cache")}):
            with mock.patch.object(INSTALL, "sha256", return_value=asset["sha256"]), mock.patch.object(INSTALL, "safe_extract", side_effect=extract), mock.patch.object(INSTALL, "check_source_host_tools"), mock.patch.object(INSTALL, "apply_overlay", side_effect=STATE.BootstrapError("compile failed")):
                with self.assertRaisesRegex(STATE.BootstrapError, "compile failed"):
                    INSTALL.install(self.root, self.lock, mode="prebuilt", prefix=self.prefix, overlay=NAME)
        self.assertFalse(self.prefix.exists())
        self.assertEqual(list(self.root.glob(self.lock["lock_id"] + "-*")), [])

    def test_wrong_base_library_rejected_before_fetch(self):
        self.fake_base()
        with mock.patch.object(OVERLAY, "_compile_component") as compile:
            with self.assertRaisesRegex(LOCK.LockError, "exact unmodified"):
                OVERLAY.apply_overlay(self.prefix, self.profile(), self.root, self.root / "cache", mock.Mock(), 1)
            compile.assert_not_called()

    def test_downloaded_source_checksum_is_rechecked(self):
        self.fake_base()
        profile = self.profile()
        profile["base_library_sha256"] = OVERLAY.sha256(self.prefix / OVERLAY.OUTPUT)
        def wrong_download(_asset, path):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"wrong source")
        with self.assertRaisesRegex(LOCK.LockError, "source checksum mismatch"):
            OVERLAY.apply_overlay(self.prefix, profile, self.root, self.root / "cache", wrong_download, 1)

    def test_patched_source_checksum_is_rechecked(self):
        self.fake_base()
        profile = self.profile()
        profile["base_library_sha256"] = OVERLAY.sha256(self.prefix / OVERLAY.OUTPUT)
        profile["sources"] = [{"path": "lib/Dialect/HW/Example.cpp", "before": hashlib.sha256(b"original").hexdigest(), "after": "0" * 64}]
        def source_download(_asset, path):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"original")
        with mock.patch.object(OVERLAY.subprocess, "run"):
            with self.assertRaisesRegex(LOCK.LockError, "patched source checksum mismatch"):
                OVERLAY.apply_overlay(self.prefix, profile, self.root, self.root / "cache", source_download, 1)

    def test_cmake_source_paths_are_escaped(self):
        self.assertEqual(OVERLAY._cmake_string('a"${x};\\b'), 'a\\"\\${x}\\;\\\\b')


if __name__ == "__main__":
    unittest.main()
