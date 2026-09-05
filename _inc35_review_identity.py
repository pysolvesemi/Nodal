"""Tighten canonical ownership text for PR #122's second review."""
from pathlib import Path
import subprocess

HEAD = "fabc3f96d357901a7612d5641c8c98f493946f2c"
assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == HEAD
assert not subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()


def replace(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    assert text.count(old) == 1, (path, text.count(old), old)
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


path = "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
replace(path, 'LogicalResult verifyContinuousContract(Operation *operation, bool stateful,', '''// Match the canonical source-path text rules used by potential/flow access.
// Retain UTF-8 identities; reject padding, ASCII controls, NUL and DEL.
bool isCanonicalContinuousIdentity(llvm::StringRef value) {
  return !value.empty() && value == value.trim() && llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

LogicalResult verifyContinuousContract(Operation *operation, bool stateful,''')
replace(path, '''  if (!operatorId || operatorId.getValue().trim().empty() || !owner ||
      owner.getValue().trim().empty())''', '''  if (!operatorId || !isCanonicalContinuousIdentity(operatorId.getValue()) || !owner ||
      !isCanonicalContinuousIdentity(owner.getValue()))''')
replace(path, '"continuous-time operator identity and owner must be non-empty"', '"continuous-time operator identity and owner must be canonical printable text"')
replace(path, 'if (!semanticPath || semanticPath.getValue().trim().empty())', 'if (!semanticPath || !isCanonicalContinuousIdentity(semanticPath.getValue()))')
replace(path, 'if (actualOwner.empty() || ownerIdentity != actualOwner)', 'if (!isCanonicalContinuousIdentity(actualOwner) || ownerIdentity != actualOwner)')

# Malformed module paths match the malformed owner and ID, so equality and
# prefix checks alone cannot reject them. Test individual IDs independently too.
replace("tests/compiler/fixtures/increment35/run_review_matrix.py", '        annotations = (\n', r'''        malformed_paths = (
            ("leading-space", " Top.child"),
            ("trailing-space", "Top.child "),
            ("tab", r"Top.\09child"),
            ("newline", r"Top.\0Achild"),
            ("nul", r"Top.\00child"),
            ("del", r"Top.\7Fchild"),
        )
        for label, path in malformed_paths:
            malformed = semantic.replace('"Top.child"', f'"{path}"')
            malformed = malformed.replace('"Top.child.', f'"{path}.')
            execute(f"noncanonical-owner-{label}", malformed, "NODAL-ANALOG-035-002")
        for operator in ("ddt", "idt"):
            for label, suffix in (("padding", "value "), ("control", r"value\01")):
                identity = "DifferentialIntegralBackend." + suffix
                def bad_identity(line):
                    # A callable replacement preserves MLIR backslash escapes.
                    line = re.sub(r'operator_id = "[^"]+"', lambda _: f'operator_id = "{identity}"', line)
                    return re.sub(r'state_id = "[^"]+"', lambda _: f'state_id = "{identity}.state"', line)
                execute(f"noncanonical-id-{operator}-{label}", edit_operator(source, operator, bad_identity), "NODAL-ANALOG-035-002")
        for value in ('""', '" "', '7 : i64'):
            malformed = semantic.replace('semantic_path = "Top.child"', f'semantic_path = {value}')
            execute(f"invalid-path-{count}", malformed, "NODAL-ANALOG-035-002")
        unicode_owner = semantic.replace("Top.child", "Top.\u0394")
        execute("utf8-owner", unicode_owner)

        annotations = (
''')
replace("docs/implementation/increment35-review-hardening.md", '''operator suffixes and invalid canonical owner paths are rejected.
''', '''operator suffixes and invalid canonical owner paths are rejected. The canonical
text rule matches the existing potential/flow source-path contract: no leading
or trailing whitespace, ASCII control characters, NUL or DEL. UTF-8 identities
remain supported. The rule applies to the module identity, declared owner and
operator ID, so coordinated malformed values cannot validate one another.
''')
replace("docs/implementation/increment35-review-hardening.md", '''simplification. Failed backend checks must publish no partial output.
''', '''simplification. Additional cases reject matching whitespace-padded and
control-containing owner/path pairs, malformed path attributes and noncanonical
operator IDs, while accepting a valid UTF-8 owner. Failed backend checks must
publish no partial output.
''')
subprocess.run(["python3", "-m", "py_compile", "tests/compiler/fixtures/increment35/run_review_matrix.py"], check=True)
for number in (24, 33, 34, 35, 36):
    subprocess.run(["python3", f"scripts/check_increment{number}.py"], check=True)
subprocess.run(["python3", "-m", "unittest", "discover", "-s", "tests/compiler", "-p", "test_increment35*.py"], check=True)
