"""Apply Increment 35 review corrections to the pinned, clean integration tree."""
from pathlib import Path
import re
import subprocess

BASE = "aa93bc7e9eb6df51162a486452b185025b77207a"
CLOSURE_HEAD = "39915b984707f0396777cc69030dfec29aa2befe"
CLOSURE_RUN = 33916159555
assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == BASE
assert not subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()


def replace(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    assert text.count(old) == 1, (path, "replacement count", text.count(old), old)
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def write(path, text):
    p = Path(path)
    assert not p.exists(), path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


numeric = "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
replace(numeric, '''  if (!contracted) {
    if (operation->getOperand(0).getType().isF64() && operation->getResult(0).getType().isF64())''', '''  if (!contracted) {
    // Legacy type compatibility must not authorize unverified simplification.
    if (hasAnyAttribute(operation, kContinuousSimplificationAttributes))
      return emitMappedFailure(operation, "NODAL-ANALOG-035-007",
                               "ddt simplification requires the Increment 35 operator contract");
    if (operation->getOperand(0).getType().isF64() && operation->getResult(0).getType().isF64())''')
replace(numeric, '''  const llvm::StringRef ownerIdentity = owner.getValue();
  if (!operatorIdentity.starts_with(ownerIdentity) ||
      !operatorIdentity.drop_front(ownerIdentity.size()).starts_with("."))''', '''  const llvm::StringRef ownerIdentity = owner.getValue();
  Operation *module = operation->getParentOp();
  while (module && !isNamed(module, "nodal.module"))
    module = module->getParentOp();
  llvm::StringRef actualOwner = module ? textAttr(module, "sym_name") : llvm::StringRef();
  if (module) {
    if (auto metadata = module->getAttrOfType<DictionaryAttr>("metadata")) {
      if (Attribute path = metadata.get("semantic_path")) {
        auto semanticPath = llvm::dyn_cast<StringAttr>(path);
        if (!semanticPath || semanticPath.getValue().trim().empty())
          return emitMappedFailure(operation, "NODAL-ANALOG-035-002",
                                   "continuous-time enclosing module has an invalid semantic path");
        actualOwner = semanticPath.getValue();
      }
    }
  }
  if (actualOwner.empty() || ownerIdentity != actualOwner)
    return emitMappedFailure(operation, "NODAL-ANALOG-035-002",
                             "continuous-time operator owner must match its enclosing module");
  if (!operatorIdentity.starts_with(ownerIdentity) ||
      !operatorIdentity.drop_front(ownerIdentity.size()).starts_with(".") ||
      operatorIdentity.size() <= ownerIdentity.size() + 1)''')
backend = "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
replace(backend, '''    auto simplified = operation->getAttrOfType<BoolAttr>("nodal.simplified");''', '''    auto contract = operation->getAttrOfType<StringAttr>("operator_contract");
    auto simplified = operation->getAttrOfType<BoolAttr>("nodal.simplified");''')
replace(backend, '''    if (simplified && simplified.getValue() && rule &&
        rule.getValue() == "ddt-time-invariant-zero" && provenance &&''', '''    if (contract && contract.getValue() == "increment35" && simplified &&
        simplified.getValue() && rule &&
        rule.getValue() == "ddt-time-invariant-zero" && provenance &&''')

# Retain historic acceptance evidence; only tighten its verification.
for number in (33, 34, 35):
    path = f"scripts/check_increment{number}.py"
    replace(path, "\n\nclass CheckFailure(RuntimeError):", f'''

# Immutable accepted Increment 35 closure, not values supplied by the manifest.
INCREMENT35_CLOSURE_HEAD = "{CLOSURE_HEAD}"
INCREMENT35_CLOSURE_RUN = {CLOSURE_RUN}


class CheckFailure(RuntimeError):''')
replace("scripts/check_increment33.py", '''                    and closure_run > 0
''', '''                    and closure_run > 0
                    and closure_head == INCREMENT35_CLOSURE_HEAD
                    and closure_run == INCREMENT35_CLOSURE_RUN
''')
replace("scripts/check_increment34.py", '''                and successor_validation.get("closure_validation_run") > 0
''', '''                and successor_validation.get("closure_validation_run") > 0
                and successor_validation.get("closure_validation_head") == INCREMENT35_CLOSURE_HEAD
                and successor_validation.get("closure_validation_run") == INCREMENT35_CLOSURE_RUN
''')
replace("scripts/check_increment35.py", '''            and accepted.get("closure_validation_run") > 0,''', '''            and accepted.get("closure_validation_run") > 0
            and accepted.get("closure_validation_head") == INCREMENT35_CLOSURE_HEAD
            and accepted.get("closure_validation_run") == INCREMENT35_CLOSURE_RUN,''')

p = Path("tests/compiler/test_increment35.py")
t = p.read_text(encoding="utf-8")
a = t.index("    def test_validated_state_remains_supported(")
b = t.index("    def test_closed_roadmap_regression_is_rejected(", a)
s = t[a:b]
s = s.replace('validation["closure_validation_head"] = "1" * 40', f'validation["closure_validation_head"] = "{CLOSURE_HEAD}"')
s = s.replace('validation["closure_validation_run"] = 1', f'validation["closure_validation_run"] = {CLOSURE_RUN}')
s = s.replace('f"**Closure validation head:** `{\'1\' * 40}`"', f'"**Closure validation head:** `{CLOSURE_HEAD}`"')
s = s.replace('"**Closure validation run:** `1`"', f'"**Closure validation run:** `{CLOSURE_RUN}`"')
assert s != t[a:b]
p.write_text(t[:a] + s + t[b:], encoding="utf-8")

write("tests/compiler/test_increment35_review_hardening.py", r'''"""Reject coordinated substitution of Increment 35 acceptance evidence."""
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
''')

write("tests/compiler/fixtures/increment35/run_review_matrix.py", r'''"""Exercise Increment 35 review fixes through the compiled parser and backend."""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]


def edit_operator(source: str, operator: str, transform) -> str:
    lines = source.splitlines(keepends=True)
    selected = [i for i, line in enumerate(lines) if f'"nodal.analog_{operator}"' in line]
    assert len(selected) == 1, (operator, selected)
    index = selected[0]
    lines[index] = transform(lines[index])
    return "".join(lines)


def forge_owner(source: str, operator: str, owner: str) -> str:
    def change(line):
        line = re.sub(r'owner = "[^"]+"', f'owner = "{owner}"', line)
        line = re.sub(r'operator_id = "[^"]+"', f'operator_id = "{owner}.operator"', line)
        return re.sub(r'state_id = "[^"]+"', f'state_id = "{owner}.operator.state"', line)
    return edit_operator(source, operator, change)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    args = parser.parse_args()
    source = (ROOT / "core/compiler/test/IR/analog-differential-integral-backend.mlir").read_text(encoding="utf-8")
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-inc35-review-") as temporary:
        directory = Path(temporary)

        def execute(label, text, diagnostic=None):
            nonlocal count
            fixture = directory / f"{label}.mlir"
            fixture.write_text(text, encoding="utf-8")
            commands = (
                [str(args.nodalc), str(fixture)],
                [str(args.nodalc), "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)", str(fixture)],
                [str(args.translate), "--nodal-to-verilog-a", str(fixture)],
            )
            rendered = ""
            for command in commands:
                result = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
                message = result.stdout + result.stderr
                if diagnostic:
                    assert result.returncode != 0, f"{label}: invalid fixture accepted by {command}\n{message}"
                    assert diagnostic in message, f"{label}: expected {diagnostic}\n{message}"
                    if command[0] == str(args.translate):
                        assert not result.stdout.strip(), f"{label}: backend published partial output"
                else:
                    assert result.returncode == 0, f"{label}: valid fixture rejected by {command}\n{message}"
                    if command[0] == str(args.translate):
                        rendered = result.stdout
                count += 1
            return rendered

        output = execute("contracted-baseline", source)
        assert "ddt(" in output and "idt(" in output
        semantic = source.replace('metadata = {}, sym_name = "DifferentialIntegralBackend"', 'metadata = {semantic_path = "Top.child"}, sym_name = "DifferentialIntegralBackend"')
        semantic = semantic.replace('owner = "DifferentialIntegralBackend"', 'owner = "Top.child"')
        semantic = semantic.replace('"DifferentialIntegralBackend.', '"Top.child.')
        execute("semantic-owner", semantic)
        for operator in ("ddt", "idt"):
            execute(f"wrong-module-{operator}", forge_owner(source, operator, "Other"), "NODAL-ANALOG-035-002")
            execute(f"wrong-semantic-{operator}", forge_owner(semantic, operator, "DifferentialIntegralBackend"), "NODAL-ANALOG-035-002")
            empty = edit_operator(source, operator, lambda line: re.sub(r'operator_id = "[^"]+"', 'operator_id = "DifferentialIntegralBackend."', line))
            execute(f"empty-identity-{operator}", empty, "NODAL-ANALOG-035-002")

        annotations = (
            'nodal.simplified = true, nodal.simplification_rule = "ddt-time-invariant-zero", '
            'nodal.simplification_provenance = "increment35", nodal.simplified_dimension = "1", '
            'nodal.simplified_value = 0.0 : f64'
        )
        for kind in ("typed", "legacy-f64"):
            text = source if kind == "typed" else re.sub(r'!nodal.quantity<"real", "[^"]+">', "f64", source)
            legacy = edit_operator(text, "ddt", lambda line: line.replace('operator_contract = "increment35", ', ""))
            rendered = execute(f"{kind}-uncontracted", legacy)
            assert "ddt(" in rendered, "legacy dynamic derivative was lost"
            for label, attrs in (("full", annotations), ("partial", "nodal.simplified = false")):
                forged = edit_operator(legacy, "ddt", lambda line: line.replace('}> :', '}> {' + attrs + '} :'))
                execute(f"{kind}-forged-{label}", forged, "NODAL-ANALOG-035-007")
            forged = edit_operator(text, "ddt", lambda line: line.replace('}> :', '}> {' + annotations + '} :'))
            execute(f"{kind}-contracted-dynamic-forgery", forged, "NODAL-ANALOG-035-007")

        result = subprocess.run(
            [str(args.nodalc), "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)",
             str(ROOT / "core/compiler/test/IR/analog-differential-integral.mlir")],
            text=True, capture_output=True, timeout=60, check=False,
        )
        assert result.returncode == 0, result.stdout + result.stderr
        for token in ("nodal.analog_ddt", "nodal.analog_idt", 'nodal.simplification_rule = "ddt-time-invariant-zero"'):
            assert token in result.stdout, token
        count += 1
    print(f"Increment 35 review matrix passed: {count} compiled parser/verifier/backend checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
''')
replace("core/compiler/test/CMakeLists.txt", "add_custom_target(check-nodal-native\n", '''add_test(
  NAME nodal.native.analog-differential-integral-review-hardening
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment35/run_review_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --translate "$<TARGET_FILE:nodal-translate>"
)

add_custom_target(check-nodal-native
''')
write("docs/implementation/increment35-review-hardening.md", '''# Increment 35 — Post-merge review hardening

## Scope

This follow-up addresses the three review findings from implementation PR #113
and evidence-closure PR #114. It preserves the accepted Increment 35 evidence
and the subsequent Increment 36 implementation; it does not add solver or DAE scope.

## Corrections

Legacy differential operators reject any continuous-simplification metadata
with `NODAL-ANALOG-035-007`. Plain legacy operators retain their existing typing
and diagnostics. Verilog-A zero rendering additionally requires the versioned
Increment 35 operator contract.

Differential and integral operator ownership is checked against the nearest
`nodal.module`: its canonical `metadata.semantic_path`, when present, otherwise
its symbol name. An owner-qualified operator ID alone is insufficient. Empty
operator suffixes and invalid canonical owner paths are rejected.

The Increment 33, 34 and 35 checkers pin the accepted Increment 35 closure to
head `39915b984707f0396777cc69030dfec29aa2befe` and run `33916159555`.
Coordinated replacement of the manifest and evidence documents cannot substitute
an unrelated SHA or workflow run. Historical acceptance identities are unchanged.

## Regression coverage and validation requirements

`test_increment35_review_hardening.py` checks the real acceptance pair and
coordinated head-only, run-only and paired mutations against all three checkers.

`run_review_matrix.py` is registered with CTest. It invokes the compiled parser,
numeric-verification pipeline and direct Verilog-A backend. Cases cover typed
and legacy-f64 dynamic derivatives with full or partial forged annotations,
valid uncontracted derivatives, incorrect component owners, canonical semantic
paths, empty operator suffixes, and preservation of the approved constant
simplification. Failed backend checks must publish no partial output.

Qualification uses the repository commands, including `./nodal core scala`,
`./nodal core native`, and `./nodal check --online-toolchain --base-ref origin/dev`.
The follow-up is not fully closed until its exact-head checks, review and
post-merge validation succeed. Existing green historical runs do not qualify
these new changes.
''')
replace("docs/roadmap/nodal-development-todo.md", "  - Implement `ddt`, `idt`, initial conditions, context restrictions, and semantics-preserving simplification.\n", "  - Implement `ddt`, `idt`, initial conditions, context restrictions, and semantics-preserving simplification.\n  - Post-closure correctness follow-up: [review hardening](../implementation/increment35-review-hardening.md) addresses legacy derivative simplification, enclosing-module ownership and immutable closure evidence; historical acceptance below is unchanged.\n")
