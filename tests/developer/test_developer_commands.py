from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Mapping, Sequence

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "nodal.py"
SPEC = importlib.util.spec_from_file_location("nodal_developer_commands", SCRIPT)
assert SPEC and SPEC.loader
COMMANDS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = COMMANDS
SPEC.loader.exec_module(COMMANDS)


class RecordingRunner:
    def __init__(self, root: Path, toolchain: Path | None = None) -> None:
        self.root = root
        self.toolchain = toolchain or root / ".toolchains/native/locked"
        self.calls: list[tuple[tuple[str, ...], dict[str, str], bool]] = []

    def run(
        self,
        command: Sequence[str],
        *,
        env: Mapping[str, str] | None = None,
        capture_output: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        normalized = tuple(str(part) for part in command)
        self.calls.append((normalized, dict(env or {}), capture_output))
        stdout = ""
        if "bootstrap_native_toolchain.py" in " ".join(normalized) and "status" in normalized:
            stdout = json.dumps(
                {"found": str(self.toolchain), "rejected": {}, "fallback": {}}
            )
        return subprocess.CompletedProcess(normalized, 0, stdout=stdout, stderr="")


class UnifiedDeveloperCommandTests(unittest.TestCase):
    def temporary_root(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "scripts").mkdir()
        (root / "mill").write_text("#!/bin/sh\n", encoding="utf-8")
        (root / "mill.bat").write_text("@echo off\n", encoding="utf-8")
        return temporary, root

    def test_bootstrap_forwards_locked_toolchain_options(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        runner = RecordingRunner(root)
        result = COMMANDS.main(
            [
                "bootstrap",
                "--mode",
                "source",
                "--prefix",
                str(root / "native"),
                "--jobs",
                "3",
                "--force",
                "--dry-run",
                "--json",
            ],
            root=root,
            runner=runner,
        )
        self.assertEqual(result, 0)
        command = runner.calls[0][0]
        self.assertIn("bootstrap_native_toolchain.py", " ".join(command))
        self.assertEqual(
            command[-9:],
            (
                "--mode",
                "source",
                "--prefix",
                str(root / "native"),
                "--jobs",
                "3",
                "--force",
                "--dry-run",
                "--json",
            ),
        )

    def test_core_scala_uses_repository_mill_wrapper(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(["core", "scala"], root=root, runner=runner),
            0,
        )
        wrapper = str(root / ("mill.bat" if os.name == "nt" else "mill"))
        self.assertEqual(runner.calls[0][0], (wrapper, "__.compile"))
        self.assertEqual(
            runner.calls[1][0],
            (wrapper, "core.scala.testkit.test"),
        )

    def test_core_native_uses_managed_toolchain(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        toolchain = root / "managed-native"
        runner = RecordingRunner(root, toolchain)
        self.assertEqual(
            COMMANDS.main(
                ["core", "native", "--toolchain", str(toolchain)],
                root=root,
                runner=runner,
            ),
            0,
        )
        commands = [call[0] for call in runner.calls]
        self.assertIn("--require", commands[0])
        self.assertEqual(
            commands[1],
            ("cmake", "--preset", "native-release"),
        )
        self.assertEqual(
            commands[2],
            ("cmake", "--build", "--preset", "native-release"),
        )
        self.assertEqual(commands[3], ("ctest", "--preset", "native-release"))
        self.assertEqual(
            commands[4][-2:],
            ("--target", "check-nodal-native"),
        )
        for _, env, _ in runner.calls[1:]:
            self.assertEqual(
                env["NODAL_NATIVE_TOOLCHAIN"],
                str(toolchain.resolve()),
            )

    def test_full_check_includes_all_contract_suites(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(
                ["check", "--online-toolchain"],
                root=root,
                runner=runner,
            ),
            0,
        )
        rendered = [" ".join(call[0]) for call in runner.calls]
        for script in (
            "check_architecture.py",
            "check_scala_bootstrap.py",
            "check_native_toolchain.py",
            "check_native_compiler_bootstrap.py",
            "check_developer_commands.py",
            "check_formatting_baseline.py",
            "check_ci_baseline.py",
        ):
            self.assertTrue(
                any(script in command for command in rendered),
                script,
            )
        for suite in (
            "architecture",
            "build",
            "toolchain",
            "compiler",
            "developer",
            "ci",
        ):
            self.assertTrue(
                any(f"tests/{suite}" in command for command in rendered),
                suite,
            )
        self.assertTrue(any("--online" in call[0] for call in runner.calls))
        self.assertTrue(
            any("core.scala.testkit.test" in call[0] for call in runner.calls)
        )
        self.assertTrue(
            any("check-nodal-native" in call[0] for call in runner.calls)
        )

    def test_contract_only_check_skips_builds(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(
                ["check", "--contracts-only"],
                root=root,
                runner=runner,
            ),
            0,
        )
        rendered = [" ".join(call[0]) for call in runner.calls]
        self.assertTrue(
            any("check_ci_baseline.py" in command for command in rendered)
        )
        self.assertTrue(any("tests/ci" in command for command in rendered))
        self.assertFalse(
            any("core.scala.testkit.test" in command for command in rendered)
        )
        self.assertFalse(any(command.startswith("cmake ") for command in rendered))
        self.assertFalse(any(command.startswith("ctest ") for command in rendered))

    def test_clean_preserves_toolchains_by_default(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "out").mkdir()
        (root / ".validation").mkdir()
        (root / ".toolchains").mkdir()
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(["clean"], root=root, runner=runner),
            0,
        )
        self.assertFalse((root / "out").exists())
        self.assertFalse((root / ".validation").exists())
        self.assertTrue((root / ".toolchains").exists())
        self.assertEqual(
            COMMANDS.main(
                ["clean", "--toolchains"],
                root=root,
                runner=runner,
            ),
            0,
        )
        self.assertFalse((root / ".toolchains").exists())

    def test_clean_refuses_symlink_escape(self) -> None:
        if os.name == "nt":
            self.skipTest(
                "symlink creation is not reliably available on Windows CI"
            )
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        outside = root.parent / f"{root.name}-outside"
        outside.mkdir(exist_ok=True)
        self.addCleanup(
            lambda: outside.rmdir() if outside.exists() else None
        )
        (root / "out").symlink_to(outside, target_is_directory=True)
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(["clean"], root=root, runner=runner),
            2,
        )
        self.assertTrue(outside.exists())

    def test_toolchain_doctor_checks_lock_host_and_install(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        toolchain = root / "managed-native"
        runner = RecordingRunner(root, toolchain)
        self.assertEqual(
            COMMANDS.main(
                [
                    "toolchain",
                    "doctor",
                    "--online",
                    "--require",
                    "--toolchain",
                    str(toolchain),
                ],
                root=root,
                runner=runner,
            ),
            0,
        )
        rendered = [" ".join(call[0]) for call in runner.calls]
        self.assertTrue(
            any(
                "check_native_toolchain.py --online" in command
                for command in rendered
            )
        )
        self.assertTrue(
            any(
                "bootstrap_native_toolchain.py status" in command
                for command in rendered
            )
        )
        self.assertTrue(
            any(command.startswith("cmake --version") for command in rendered)
        )
        self.assertTrue(
            any(command.startswith("ninja --version") for command in rendered)
        )

    def test_library_namespace_is_reserved(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        runner = RecordingRunner(root)
        self.assertEqual(
            COMMANDS.main(
                ["library", "check", "example"],
                root=root,
                runner=runner,
            ),
            2,
        )
        self.assertEqual(runner.calls, [])
        self.assertFalse((root / "libraries").exists())


if __name__ == "__main__":
    unittest.main()
