"""Resolve only the reviewed successor-checker edits during a normal dev merge."""
from pathlib import Path
import subprocess

HEAD = "8c7aeb524fb3e427f43148e0acb8a00e2d0eac27"
BASE = "f6e11c5b3f92ee43b4a6d4fc6af21d478249b961"
CHECKERS = {f"scripts/check_increment{n}.py" for n in (33, 34, 35)}
ALLOWED = CHECKERS | {
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/test/CMakeLists.txt",
    "tests/compiler/test_increment35.py",
    "tests/compiler/test_increment35_review_hardening.py",
    "tests/compiler/fixtures/increment35/run_review_matrix.py",
    "docs/implementation/increment35-review-hardening.md",
    "docs/roadmap/nodal-development-todo.md",
}


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def replace(text, old, new):
    assert text.count(old) == 1, (old, text.count(old))
    return text.replace(old, new, 1)


assert git("rev-parse", "HEAD") == HEAD
assert not git("status", "--porcelain")
subprocess.run(["git", "merge", "--no-commit", "--no-ff", BASE], check=False)
conflicts = set(filter(None, git("diff", "--name-only", "--diff-filter=U").splitlines()))
print("conflicts:", sorted(conflicts), flush=True)
assert conflicts <= CHECKERS, conflicts
assert git("rev-parse", "MERGE_HEAD") == BASE
for n in (33, 34, 35):
    path = f"scripts/check_increment{n}.py"
    text = subprocess.check_output(["git", "show", f"{BASE}:{path}"], text=True)
    constants = '\n\n# Immutable accepted Increment 35 closure, not values supplied by the manifest.\nINCREMENT35_CLOSURE_HEAD = "39915b984707f0396777cc69030dfec29aa2befe"\nINCREMENT35_CLOSURE_RUN = 33916159555\n\n\nclass CheckFailure(RuntimeError):'
    text = replace(text, "\n\nclass CheckFailure(RuntimeError):", constants)
    if n == 33:
        text = replace(text, '                    and closure_run > 0\n', '                    and closure_run > 0\n                    and closure_head == INCREMENT35_CLOSURE_HEAD\n                    and closure_run == INCREMENT35_CLOSURE_RUN\n')
    elif n == 34:
        text = replace(text, '                and successor_validation.get("closure_validation_run") > 0\n', '                and successor_validation.get("closure_validation_run") > 0\n                and successor_validation.get("closure_validation_head") == INCREMENT35_CLOSURE_HEAD\n                and successor_validation.get("closure_validation_run") == INCREMENT35_CLOSURE_RUN\n')
    else:
        text = replace(text, '            and accepted.get("closure_validation_run") > 0,', '            and accepted.get("closure_validation_run") > 0\n            and accepted.get("closure_validation_head") == INCREMENT35_CLOSURE_HEAD\n            and accepted.get("closure_validation_run") == INCREMENT35_CLOSURE_RUN,')
    Path(path).write_text(text, encoding="utf-8")
subprocess.run(["git", "add", *sorted(CHECKERS)], check=True)
assert not git("diff", "--name-only", "--diff-filter=U")
assert not git("diff", "--name-only")
changed = set(filter(None, git("diff", "--cached", "--name-only", BASE).splitlines()))
assert changed == ALLOWED, (changed, ALLOWED)
for path in ALLOWED - CHECKERS - {"docs/roadmap/nodal-development-todo.md", "tests/compiler/test_increment35.py"}:
    assert git("hash-object", path) == git("rev-parse", f"{HEAD}:{path}"), path
roadmap = Path("docs/roadmap/nodal-development-todo.md").read_text(encoding="utf-8")
assert "**Revision:** 1.47" in roadmap
assert "- [x] **Increment 36" in roadmap
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
for n in (24, 33, 34, 35, 36):
    subprocess.run(["python3", f"scripts/check_increment{n}.py"], check=True)
subprocess.run(["python3", "-m", "unittest", "discover", "-s", "tests/compiler", "-p", "test_*.py"], check=True)
for name in ("check_hvl_roadmap.py", "test_hvl_roadmap.py", "check_markdown.py"):
    subprocess.run(["python3", f"scripts/{name}"], check=True)
subprocess.run(["python3", "scripts/check_contribution_policy.py", "--base-ref", BASE], check=True)
assert not Path("_inc35_review_integrate.py").exists()
assert not Path("_inc35_review_apply.py").exists()
assert not Path(".github/workflows/inc35-review-publication.yml").exists()
print("Reviewed integration and successor tests passed; exact-head PR qualification is still required.", flush=True)
