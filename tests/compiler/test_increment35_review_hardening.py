"""Reject coordinated substitution of Increment 35 acceptance evidence."""
from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEAD = "39915b984707f0396777cc69030dfec29aa2befe"
RUN = 33916159555


def load_suite(number: int):
    path = ROOT / f"tests/compiler/test_increment{number}.py"
    spec = importlib.util.spec_from_file_location(f"inc{number}_review_fixture", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Increment35ReviewHardeningTests(unittest.TestCase):
    def test_exact_historical_closure_is_accepted_by_all_three_checkers(self):
        for number in (33, 34, 35):
            with self.subTest(checker=number):
                suite = load_suite(number)
                suite.CHECKER.check_repository(ROOT)

    def test_coordinated_evidence_substitution_is_rejected(self):
        for number in (33, 34, 35):
            suite = load_suite(number)
            case = getattr(suite, f"Increment{number}ContractTests")()
            for fake_head, fake_run in (("a" * 40, RUN), (HEAD, 7), ("b" * 40, 9)):
                with self.subTest(checker=number, head=fake_head, run=fake_run):
                    temporary, root = case.fixture()
                    with temporary:
                        manifest = root / "tests/compiler/fixtures/increment35/manifest.json"
                        document = json.loads(manifest.read_text(encoding="utf-8"))
                        self.assertEqual(document["status"], "validated-differential-integral-operators")
                        self.assertEqual(document["validation"]["closure_validation_head"], HEAD)
                        self.assertEqual(document["validation"]["closure_validation_run"], RUN)
                        document["validation"]["closure_validation_head"] = fake_head
                        document["validation"]["closure_validation_run"] = fake_run
                        manifest.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
                        for relative in (
                            "docs/implementation/increment35-evidence-closure.md",
                            "docs/implementation/increment35-closure-candidate-validation.md",
                            "docs/roadmap/nodal-development-todo.md",
                        ):
                            target = root / relative
                            if not target.exists():
                                source = ROOT / relative
                                if not source.exists():
                                    continue
                                target.parent.mkdir(parents=True, exist_ok=True)
                                target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
                            text = target.read_text(encoding="utf-8")
                            target.write_text(text.replace(HEAD, fake_head).replace(str(RUN), str(fake_run)), encoding="utf-8")
                        with self.assertRaises(suite.CHECKER.CheckFailure) as caught:
                            suite.CHECKER.check_repository(root)
                        expected = {33: "NODAL-INC33-090", 34: "NODAL-INC34-056", 35: "NODAL-INC35-020"}
                        self.assertIn(expected[number], str(caught.exception))


if __name__ == "__main__":
    unittest.main()
