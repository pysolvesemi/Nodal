"""Mutation coverage for current construction sources and immutable predicates."""

from __future__ import annotations

import ast
import hashlib
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType


ROOT = Path(__file__).resolve().parents[2]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import check_increment16 as INC16
import check_increment17 as INC17
import check_increment25 as INC25
import check_increment32 as INC32
import check_increment33 as INC33
import check_increment35 as INC35
import check_increment36 as INC36
from construction_source_inventory import CONSTRUCTION_SOURCES, read_construction_sources


CHECKERS = (INC16, INC17, INC25, INC32, INC33, INC35, INC36)
SESSION = "core/scala/api/src/nodal/ConstructionSession.scala"
RECORDS = "core/scala/api/src/nodal/ConstructionRecords.scala"
FACTS = "core/scala/api/src/nodal/ConstructionExpressionFacts.scala"
ORIGIN = "core/scala/api/src/nodal/SemanticOriginKernel.scala"
CANDIDATE = "core/scala/api/src/nodal/CandidateApi.scala"


class Increment160SourceContractTests(unittest.TestCase):
    def fixture(self, *checkers: ModuleType):
        """Copy concrete checker inputs without copying caches/build outputs.

        Literal repository paths come from the checkers themselves.  The moved
        construction inputs always come from their one production inventory.
        """
        files = set(CONSTRUCTION_SOURCES)
        for checker in (*checkers, INC16.frozen):
            source = Path(checker.__file__)
            files.add(source.relative_to(ROOT).as_posix())
            for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
                if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
                    continue
                relative = node.value
                if (
                    not relative
                    or len(relative) > 240
                    or "\n" in relative
                    or "\x00" in relative
                    or Path(relative).is_absolute()
                ):
                    continue
                candidate = ROOT / relative
                if candidate.resolve().is_relative_to(ROOT) and candidate.is_file():
                    files.add(relative)
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        self.addCleanup(temporary.cleanup)
        for relative in sorted(files):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        return root

    def replace(self, root: Path, relative: str, before: str, after: str) -> None:
        path = root / relative
        original = path.read_text(encoding="utf-8")
        self.assertEqual(original.count(before), 1, (relative, before))
        path.write_text(original.replace(before, after, 1), encoding="utf-8")

    def problems(self, checker: ModuleType, root: Path) -> list[str]:
        if hasattr(checker, "validate_files"):
            return [problem.code for problem in checker.validate_files(root)]
        if checker is INC25:
            return [problem.code for problem in checker.check_repository(root)]
        try:
            checker.check_repository(root)
        except checker.CheckFailure as error:
            return [str(error).split(":", 1)[0]]
        return []

    def test_current_isolated_inputs_preserve_all_predecessor_contracts(self):
        root = self.fixture(*CHECKERS)
        for checker in CHECKERS:
            with self.subTest(checker=checker.__name__):
                self.assertEqual(self.problems(checker, root), [])

    def test_every_missing_component_fails_all_current_readers(self):
        expected = {
            INC16: "NODAL-INC16-001",
            INC17: "NODAL-INC17-001",
            INC25: "NODAL-INC25-001",
            INC32: "NODAL-INC32-039",
            INC33: "NODAL-INC33-001",
            INC35: "NODAL-INC35-001",
            INC36: "NODAL-INC36",
        }
        for relative in CONSTRUCTION_SOURCES:
            root = self.fixture(*CHECKERS)
            (root / relative).unlink()
            with self.subTest(source=relative, reader="inventory"):
                with self.assertRaises(FileNotFoundError):
                    read_construction_sources(root)
            for checker, code in expected.items():
                with self.subTest(source=relative, checker=checker.__name__):
                    self.assertIn(code, self.problems(checker, root))

    def test_forbidden_mechanisms_are_rejected_in_every_component(self):
        mutations = (
            "private val badContext = new ThreadLocal[AnyRef]",
            "private val badContext = new scala.util.DynamicVariable[AnyRef](null)",
            "private val badIdentity = System.identityHashCode(new Object)",
            "private val badIdentity = new Object().hashCode()",
        )
        for relative in CONSTRUCTION_SOURCES:
            for mutation in mutations:
                with self.subTest(source=relative, mutation=mutation):
                    root = self.fixture(INC16)
                    path = root / relative
                    path.write_text(path.read_text() + "\n" + mutation + "\n")
                    self.assertIn("NODAL-INC16-015", self.problems(INC16, root))

    def test_canonical_instance_adapter_requires_each_real_attachment_path(self):
        mutations = (
            (CANDIDATE, "CandidateRuntime.instance(module)", "CandidateRuntime.statement(module)"),
            (SESSION, "attachInstance(created, module, captured)", "missingAttachment(created, module, captured)"),
            (SESSION, "this.instance(module, captured = true)", "this.instance(module, captured = false)"),
        )
        for relative, before, after in mutations:
            with self.subTest(source=relative, before=before):
                root = self.fixture(INC16)
                self.replace(root, relative, before, after)
                self.assertIn("NODAL-INC16-016", self.problems(INC16, root))

    def test_moved_semantic_and_snapshot_guards_still_reject(self):
        mutations = (
            (INC17, SESSION, "semanticOrigin.captureModule", "semanticOrigin.missingCapture", "NODAL-INC17-015"),
            (INC25, RECORDS, "analogRegions: Vector[KernelAnalogRegionSnapshot]", "lostAnalogRegions: Vector[KernelAnalogRegionSnapshot]", "NODAL-INC25-004"),
            (INC32, RECORDS, "analogSemantics: AnalogEquationRuntime.Snapshot", "lostAnalogSemantics: AnalogEquationRuntime.Snapshot", "NODAL-INC32-046"),
            (INC35, SESSION, "continuousOperators = continuousOperatorSnapshots", "continuousOperators = missingOperatorSnapshots", "NODAL-INC35-004"),
        )
        for checker, relative, before, after, code in mutations:
            with self.subTest(checker=checker.__name__, source=relative):
                root = self.fixture(checker)
                self.replace(root, relative, before, after)
                self.assertIn(code, self.problems(checker, root))

    def test_moved_dimension_guards_still_reject(self):
        mutations = (
            ("def compatibleAdd(other: AnalogDimension): AnalogDimension =\n    if isUnknown || other.isUnknown then AnalogDimension.Unknown", "def compatibleAdd(other: AnalogDimension): AnalogDimension =\n    if isUnknown && other.isUnknown then AnalogDimension.Unknown", "NODAL-INC33-067"),
            ('expression.resultType.exists(_.kind == "Bool")', 'expression.resultType.exists(_.kind == "Real")', "NODAL-INC33-071"),
            ("dimensions.forall(isDimensionlessBoolean)", "dimensions.exists(isDimensionlessBoolean)", "NODAL-INC33-075"),
        )
        for before, after, code in mutations:
            with self.subTest(guard=before):
                root = self.fixture(INC33)
                self.replace(root, FACTS, before, after)
                self.assertIn(code, self.problems(INC33, root))

    def test_each_construction_frame_exclusion_is_required(self):
        for relative in CONSTRUCTION_SOURCES:
            name = Path(relative).name
            with self.subTest(source=name):
                root = self.fixture(INC17)
                self.replace(root, ORIGIN, f'"{name}"', '"MissingConstructionSource.scala"')
                self.assertIn("NODAL-INC17-041", self.problems(INC17, root))

    def test_changed_or_missing_frozen_predicates_are_rejected(self):
        for missing in (False, True):
            with self.subTest(missing=missing):
                root = self.fixture(INC16)
                path = root / INC16.FROZEN_CHECKER_PATH
                if missing:
                    path.unlink()
                else:
                    path.write_text(path.read_text() + "\n# changed frozen predicates\n")
                self.assertIn("NODAL-INC16-039", self.problems(INC16, root))

    def test_adapter_does_not_modify_historical_checker_or_global_reader(self):
        original_reader = INC16.frozen.text
        original_function = INC16.frozen.validate_files
        root = self.fixture(INC16)
        self.assertEqual(self.problems(INC16, root), [])
        self.assertIs(INC16.frozen.text, original_reader)
        self.assertIs(INC16.frozen.validate_files, original_function)
        self.assertIs(original_function.__globals__["text"], original_reader)
        # Calling the historical validator directly still inspects its original
        # single-file layout.  The current adapter never relabels that result.
        historical = INC16.frozen.validate_files(root)
        self.assertIn("NODAL-INC16-014", [problem.code for problem in historical])
        self.assertEqual(
            hashlib.sha256((ROOT / INC16.FROZEN_CHECKER_PATH).read_bytes()).hexdigest(),
            INC16.FROZEN_CHECKER_SHA256,
        )


if __name__ == "__main__":
    unittest.main()
