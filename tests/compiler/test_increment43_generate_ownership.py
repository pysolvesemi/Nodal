"""Preservation and rejection controls for the native ownership matrix."""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_generate_ownership_matrix as ownership


class GeneratedOwnershipCheckerTests(unittest.TestCase):
    def test_generated_continuous_snapshots_resolve_finalized_induction_identity(self):
        construction = (
            ROOT / "core/scala/api/src/nodal/ConstructionSession.scala"
        ).read_text()
        for start, end in (
            ("private def continuousOperatorSnapshots(", "private def transferOperatorSnapshots("),
            ("private def analogSnapshots()", "private def parameterExpressionSnapshots()"),
        ):
            with self.subTest(snapshot=start):
                section = construction[construction.index(start):construction.index(end)]
                self.assertIn("map(canonicalGeneratedOwnership)", section)

    def test_generated_event_operation_schemas_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        for start, end in (
            ("class Nodal_AnalogEventOp", "def Nodal_AnalogCrossOp"),
            ("def Nodal_AnalogEventOrOp", "def Nodal_AnalogOnOp"),
            ("def Nodal_AnalogOnOp", "def Nodal_AnalogVariableOp"),
        ):
            with self.subTest(operation=start):
                section = operations[operations.index(start):operations.index(end)]
                self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
                self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

    def test_generated_continuous_operator_schemas_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        for start, end in (
            ("def Nodal_AnalogDdtOp", "def Nodal_AnalogIdtOp"),
            ("def Nodal_AnalogIdtOp", "def Nodal_AnalogTransitionOp"),
        ):
            with self.subTest(operation=start):
                section = operations[operations.index(start):operations.index(end)]
                self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
                self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

        verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        ).read_text()
        for start, end in (
            (
                "LogicalResult nodal::AnalogDdtOp::verify()",
                "LogicalResult nodal::AnalogIdtOp::verify()",
            ),
            (
                "LogicalResult nodal::AnalogIdtOp::verify()",
                "LogicalResult nodal::ContributeOp::verify()",
            ),
        ):
            with self.subTest(verifier=start):
                section = verifier[verifier.index(start):verifier.index(end)]
                self.assertIn("verifyGeneratedAnalogOwnership", section)

    def test_generated_waveform_schemas_and_snapshots_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        for start, end in (
            ("def Nodal_AnalogTransitionOp", "def Nodal_AnalogSlewOp"),
            ("def Nodal_AnalogSlewOp", "def Nodal_AnalogAbsdelayOp"),
            ("def Nodal_AnalogAbsdelayOp", "def Nodal_AnalogAbstimeOp"),
        ):
            with self.subTest(operation=start):
                section = operations[operations.index(start):operations.index(end)]
                self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
                self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

        construction = (
            ROOT / "core/scala/api/src/nodal/ConstructionSession.scala"
        ).read_text()
        section = construction[
            construction.index("private def waveformOperatorSnapshots("):
            construction.index("private def analogSnapshots()")
        ]
        self.assertIn("map(canonicalGeneratedOwnership)", section)

        verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/TimeWaveform.cpp"
        ).read_text()
        self.assertIn("generated waveform ownership requires paired owner", verifier)
        self.assertIn("generated waveform ownership does not match", verifier)

    def test_generated_transfer_schema_snapshot_and_verifier_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        section = operations[
            operations.index("def Nodal_AnalogTransferOp"):
            operations.index("def Nodal_AnalogNoiseOp")
        ]
        self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
        self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

        construction = (
            ROOT / "core/scala/api/src/nodal/ConstructionSession.scala"
        ).read_text()
        section = construction[
            construction.index("private def transferOperatorSnapshots("):
            construction.index("private def noiseOperatorSnapshots(")
        ]
        self.assertIn("map(canonicalGeneratedOwnership)", section)

        verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        ).read_text()
        section = verifier[
            verifier.index("LogicalResult nodal::AnalogTransferOp::verify()"):
            verifier.index("LogicalResult nodal::AnalogNoiseOp::verify()")
        ]
        self.assertIn("verifyGeneratedAnalogOwnership", section)

        transfer_verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/AnalogTransfer.cpp"
        ).read_text()
        self.assertIn("module->walk([&](nodal::AnalogOp region)", transfer_verifier)
        self.assertIn("transfer identity must be unique", transfer_verifier)

    def test_generated_noise_schema_snapshot_and_verifier_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        section = operations[
            operations.index("def Nodal_AnalogNoiseOp"):
            operations.index("def Nodal_AnalogAnalysisOp")
        ]
        self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
        self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

        construction = (
            ROOT / "core/scala/api/src/nodal/ConstructionSession.scala"
        ).read_text()
        section = construction[
            construction.index("private def noiseOperatorSnapshots("):
            construction.index("private def waveformOperatorSnapshots(")
        ]
        self.assertIn("map(canonicalGeneratedOwnership)", section)
        generated = construction[
            construction.index("val generatedNoise ="):
            construction.index("val changedAnalog =")
        ]
        self.assertIn("generatedNoiseReferences", generated)
        self.assertIn('Set("white", "flicker", "table")', generated)

        bridge = (
            ROOT / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
        ).read_text()
        attributes = bridge[
            bridge.index("private def noiseOperatorAttributes("):
            bridge.index("private def waveformOperatorAttributes(")
        ]
        self.assertIn('"generated_owner" -> quoted(owner)', attributes)
        self.assertIn('"generated_induction" -> quoted(induction)', attributes)
        self.assertIn("noise operator ownership differs from its analog region", bridge)

        verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        ).read_text()
        section = verifier[
            verifier.index("LogicalResult nodal::AnalogNoiseOp::verify()"):
            verifier.index("LogicalResult nodal::AnalogFunctionOp::verify()")
        ]
        self.assertIn("verifyGeneratedAnalogOwnership", section)

        noise_verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/AnalogNoise.cpp"
        ).read_text()
        self.assertIn("module->walk([&](nodal::AnalogOp region)", noise_verifier)
        self.assertIn("noise source identity must be unique", noise_verifier)

    def test_generated_contribution_schema_snapshot_and_verifier_retain_paired_ownership(self):
        operations = (
            ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).read_text()
        section = operations[
            operations.index("def Nodal_ContributeOp"):
            operations.index("def Nodal_CrossingOp")
        ]
        self.assertIn("OptionalAttr<StrAttr>:$generated_owner", section)
        self.assertIn("OptionalAttr<StrAttr>:$generated_induction", section)

        construction = (
            ROOT / "core/scala/api/src/nodal/ConstructionSession.scala"
        ).read_text()
        section = construction[
            construction.index("private def analogSnapshots()"):
            construction.index("private def parameterExpressionSnapshots()")
        ]
        self.assertIn("generatedOwnership.map(_._1)", section)
        self.assertIn("generatedOwnership.map(_._2)", section)

        bridge = (
            ROOT / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
        ).read_text()
        self.assertIn("contribution ownership differs from its analog region", bridge)

        verifier = (
            ROOT / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        ).read_text()
        section = verifier[
            verifier.index("LogicalResult nodal::ContributeOp::verify()"):
            verifier.index("LogicalResult nodal::BridgeOp::verify()")
        ]
        self.assertIn("verifyGeneratedAnalogOwnership", section)

    def test_symbolic_generated_target_reuses_structural_parameter_bounds(self):
        backend = (
            ROOT / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        ).read_text()
        section = backend[
            backend.index("FailureOr<GeneratedTargetBound> generatedTargetBound"):
            backend.index("LogicalResult renderGeneratedContribution")
        ]
        self.assertIn("findDirectParameter", section)
        self.assertIn("nodal::isStructuralParameter", section)
        self.assertIn("nodal::inferParameterIntegerBounds", section)
        self.assertNotIn("default_value", section)

        compound = backend[
            backend.index("LogicalResult verifyGeneratedTargetBoundDependency"):
            backend.index("LogicalResult renderGeneratedContribution")
        ]
        self.assertIn("same-module structural Integer dependencies", compound)
        self.assertIn("nodal::inferParameterIntegerBounds(value)", compound)
        self.assertIn("nodal::renderParameterConstantExpression(value)", compound)
        self.assertIn('operatorName.getValue() == "div"', compound)
        self.assertNotIn("default_value", compound)

        reparse = backend[
            backend.index("if (line.starts_with(\"for (\"))"):
            backend.index("if (line.starts_with(\"begin : event_\"))")
        ]
        self.assertIn("validGeneratedBoundExpression", reparse)
        self.assertIn("!validBound(initial.second)", reparse)
        self.assertIn("!validBound(condition.second)", reparse)

    def test_inventory_and_positive_preservation(self):
        cases = ownership.cases()
        self.assertEqual(len(cases), 35)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(ownership.valid_output(case, data, 0, data, b""), case.name)

    def test_node_and_module_loss_or_changed_identity_reject(self):
        case = next(case for case in ownership.cases() if case.name == "ownership_node")
        data = counts.source(case).encode()
        for old, new in [(b'"nodal.node"', b'"nodal.other"'),
                         (b'"nodal.module"', b'"nodal.other"'),
                         (b'generated_owner = "Fixture.outer"',
                          b'generated_owner = "Fixture.other"'),
                         (b'generated_induction = "Fixture.outer.index"',
                          b'generated_induction = "different"'),
                         (b'name = "tap"', b'name = "different"')]:
            with self.subTest(old=old):
                self.assertNotEqual(data.replace(old, new), data)
                self.assertFalse(ownership.valid_output(case, data, 0, data.replace(old, new), b""))

    def test_generated_contribution_loss_or_changed_identity_reject(self):
        case = next(
            case for case in ownership.cases()
            if case.name == "ownership_generated_contribution"
        )
        data = counts.source(case).encode()
        for old, new in [
            (b'generated_owner = "Fixture.outer"',
             b'generated_owner = "Fixture.other"'),
            (b'generated_induction = "Fixture.outer.index"',
             b'generated_induction = "different"'),
            (b'kind = "potential"', b'kind = "flow"'),
            (b'"nodal.contribute"', b'"nodal.other"'),
        ]:
            with self.subTest(old=old):
                contribution_offset = data.index(b'"nodal.contribute"')
                prefix = data[:contribution_offset]
                suffix = data[contribution_offset:].replace(old, new, 1)
                mutated = prefix + suffix
                self.assertNotEqual(mutated, data)
                self.assertFalse(ownership.valid_output(case, data, 0, mutated, b""))

    def test_rejection_requires_real_exit_and_exact_diagnostic(self):
        case = next(case for case in ownership.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(ownership.valid_output(case, data, 1, b"", ownership.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(ownership.valid_output(case, data, code, b"", ownership.CODE.encode()))
        self.assertFalse(ownership.valid_output(case, data, 1, b"", b"NODAL-WRONG-001"))


if __name__ == "__main__":
    unittest.main()
