#!/usr/bin/env python3
"""Regression checks for Increment 14 review findings."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = ROOT / "core/scala/api/src/nodal/RoleAccessInversionCandidate.scala"
EXAMPLE = ROOT / "examples/interfacePipelineApi/src/PipelineInterfaceCandidates.scala"


class Increment14ReviewClosureTests(unittest.TestCase):
    def test_pipeline_transforms_do_not_capture_unrelated_live_signals(self) -> None:
        source = EXAMPLE.read_text(encoding="utf-8")
        self.assertNotIn("stage(payload + b)", source)
        self.assertNotIn("payload + c", source)
        self.assertIn("stage(payload + payload)", source)
        self.assertGreaterEqual(source.count("payload + payload"), 2)

    def test_role_inversion_preserves_interface_access(self) -> None:
        api = API.read_text(encoding="utf-8")
        source = EXAMPLE.read_text(encoding="utf-8")
        for fragment in (
            "type InverseRole",
            "endpoint.role.access.map(RoleAccessInversion.apply)",
            "case RoleAccess.Master(member) => RoleAccess.Slave(member)",
            "case RoleAccess.Slave(member) => RoleAccess.Master(member)",
            "case RoleAccess.Nested(member, role)",
        ):
            self.assertIn(fragment, api)
        self.assertIn("val invertedPixelAccess = invertedPixelSource.role.access", source)
        self.assertIn("invertedPixelAccess,", source)


if __name__ == "__main__":
    unittest.main()
