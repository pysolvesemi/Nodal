from __future__ import annotations

import json
from pathlib import Path

CANDIDATE_HEAD = "39915b984707f0396777cc69030dfec29aa2befe"
CANDIDATE_RUN = 33916159555
CANDIDATE_CORE_CI = 33916159534


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


manifest_path = Path("tests/compiler/fixtures/increment35/manifest.json")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("status") != "evidence-closure-candidate":
    raise SystemExit(f"unexpected Increment 35 manifest status: {manifest.get('status')}")
validation = manifest.get("validation")
if not isinstance(validation, dict):
    raise SystemExit("Increment 35 candidate validation object is unavailable")
if validation.get("closure_validation_head") is not None:
    raise SystemExit("Increment 35 candidate already records a validation head")
if validation.get("closure_validation_run") is not None:
    raise SystemExit("Increment 35 candidate already records a validation run")
manifest["status"] = "validated-differential-integral-operators"
validation["closure_validation_head"] = CANDIDATE_HEAD
validation["closure_validation_run"] = CANDIDATE_RUN
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

implementation = Path(
    "docs/implementation/increment35-differential-integral-operators.md"
)
replace_once(
    implementation,
    "**Status:** Closure candidate  \n",
    "**Status:** Validated  \n",
)
replace_once(
    implementation,
    (
        "Implementation and exact post-merge validation are complete. Draft closure PR #114 "
        "advances the roadmap and manifest through a separately checked closure-candidate "
        "state. The candidate deliberately leaves its own validation head and run unset until "
        "that exact pull-request head passes the complete workflow matrix.\n"
    ),
    (
        f"Implementation, exact post-merge validation, and closure-candidate validation are "
        f"complete. Closure PR #114 candidate `{CANDIDATE_HEAD}` passed the dedicated "
        f"Increment 35 validation run `{CANDIDATE_RUN}` and aggregate Core CI run "
        f"`{CANDIDATE_CORE_CI}`. The final recorded-evidence head remains subject to the "
        "complete exact-head workflow matrix before merge.\n"
    ),
)

evidence = Path("docs/implementation/increment35-evidence-closure.md")
replace_once(
    evidence,
    "**Status:** Closure candidate awaiting exact-head validation\n",
    "**Status:** Validated evidence closure\n",
)
replace_once(
    evidence,
    "**Closure validation head:** pending\n",
    f"**Closure validation head:** `{CANDIDATE_HEAD}`\n",
)
replace_once(
    evidence,
    "**Closure validation run:** pending\n",
    f"**Closure validation run:** `{CANDIDATE_RUN}`\n",
)
replace_once(
    evidence,
    (
        "This candidate advances the roadmap and manifest in draft PR #114. It\n"
        "deliberately leaves the closure validation head and run pending until this\n"
        "exact candidate passes its pull-request workflow matrix.\n"
    ),
    (
        f"Closure candidate `{CANDIDATE_HEAD}` passed the dedicated Increment 35\n"
        f"validation run `{CANDIDATE_RUN}` and aggregate Core CI run\n"
        f"`{CANDIDATE_CORE_CI}`. This record now carries that accepted candidate\n"
        "identity while the final recorded-evidence head proceeds through its own\n"
        "exact-head workflow matrix before merge.\n"
    ),
)

candidate = Path(
    "docs/implementation/increment35-closure-candidate-validation.md"
)
replace_once(
    candidate,
    "**Status:** Exact-head pull-request matrix requested  \n",
    "**Status:** Exact-head pull-request matrix passed  \n",
)
replace_once(
    candidate,
    "**Validation head:** pending  \n",
    f"**Validation head:** `{CANDIDATE_HEAD}`  \n",
)
replace_once(
    candidate,
    "**Validation workflow:** pending\n",
    (
        f"**Validation workflow:** `{CANDIDATE_RUN}`  \n"
        f"**Aggregate Core CI:** `{CANDIDATE_CORE_CI}`\n"
    ),
)
replace_once(
    candidate,
    (
        "This owner-authored record requests the authoritative exact-head workflow matrix "
        "for the Increment 35 closure candidate. The candidate closes the roadmap and "
        "manifest, records the accepted implementation and post-merge evidence, and "
        "deliberately leaves its own validation head and run unset.\n"
    ),
    (
        f"Owner-authored closure candidate `{CANDIDATE_HEAD}` passed the authoritative "
        f"exact-head workflow matrix, including dedicated Increment 35 run "
        f"`{CANDIDATE_RUN}` and aggregate Core CI run `{CANDIDATE_CORE_CI}`. The candidate "
        "closes the roadmap and manifest and records the accepted implementation and "
        "post-merge evidence.\n"
    ),
)
replace_once(
    candidate,
    (
        "The requested matrix must verify the Increment 33 and Increment 34 successor-aware "
        "predecessor contracts together with the Increment 35 open/candidate/validated "
        "transition model, including mutation tests for premature, incomplete, or forged "
        "closure evidence.\n"
    ),
    (
        "The completed matrix verified the Increment 33 and Increment 34 successor-aware "
        "predecessor contracts together with the Increment 35 open/candidate/validated "
        "transition model, including mutation tests for premature, incomplete, or forged "
        "closure evidence.\n"
    ),
)

roadmap = Path("docs/roadmap/nodal-development-todo.md")
replace_once(
    roadmap,
    (
        "and evidence-closure PR [#114](https://github.com/pysolvesemi/Nodal/pull/114) "
        "awaiting exact-head candidate validation."
    ),
    (
        "and evidence-closure PR [#114](https://github.com/pysolvesemi/Nodal/pull/114) "
        f"validated from [`{CANDIDATE_HEAD[:8]}`]"
        f"(https://github.com/pysolvesemi/Nodal/commit/{CANDIDATE_HEAD}) by dedicated run "
        f"[{CANDIDATE_RUN}](https://github.com/pysolvesemi/Nodal/actions/runs/{CANDIDATE_RUN}) "
        f"and Core CI run [{CANDIDATE_CORE_CI}]"
        f"(https://github.com/pysolvesemi/Nodal/actions/runs/{CANDIDATE_CORE_CI})."
    ),
)
