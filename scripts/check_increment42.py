#!/usr/bin/env python3
"""Guard Increment 42's accepted hierarchy profile and closure evidence."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "tests/compiler/fixtures/increment42/manifest.json"
EVIDENCE = "docs/implementation/increment42-accepted-evidence.json"
CLOSURE = "docs/implementation/increment42-evidence-closure.md"
IMPLEMENTATION = "docs/implementation/increment42-analog-hierarchy.md"
HANDOFF = "docs/implementation/increment42-later-tool-handoff.md"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
AMENDMENT = "docs/roadmap/lightweight-hierarchy-iteration-v0.1-plan.md"
PREDECESSOR = "docs/implementation/increment41-accepted-evidence.json"
PREDECESSOR_MANIFEST = "tests/compiler/fixtures/increment41/manifest.json"
WORKFLOW = ".github/workflows/increment-41-analog-functions.yml"
RUNNER = "core/scala/testkit/test/src/nodal/internal/testkit/Increment42MlirCheck.scala"
PUBLIC_SOURCE = (
    "examples/continuousTimeApi/src/nodal/increment42fixture/"
    "Increment42PredecessorCombinations.scala"
)
ACCEPTED_EVIDENCE_SHA256 = (
    "bca7844471f7298c128bc2d015dd4158edefc198627a99dd36468a1855b30b52"
)
PREDECESSOR_EVIDENCE_SHA256 = (
    "177082f5edac85be80541384d94529c6fd8f82fe1cdbc39cd87b771398d81f32"
)
SOURCE_SHA256 = {
    RUNNER: "ff89deaeb40609b1db513c360eeb177b25185f211658a9841ce824146281b6d4",
    PUBLIC_SOURCE: "f0100d687147208c3c84ccd2644813c3391750b97bdf84705ea654ec9e9e6e25",
    WORKFLOW: "0cfecfd99f61cdc5752be18bf3fb996a445b38d597c3ab5a93f96c43fef56b07",
}
# These closure-owned witness sources remain immutable in the live tree.  The
# shared Increment 41 workflow hash above is historical artifact identity: its
# current bytes may evolve independently while the evidence record stays pinned.
LIVE_SOURCE_SHA256 = {
    RUNNER: SOURCE_SHA256[RUNNER],
    PUBLIC_SOURCE: SOURCE_SHA256[PUBLIC_SOURCE],
}
OPERATIONS = [
    "nodal.instance",
    "nodal.instance_terminal",
    "nodal.connect",
    "nodal.parameter_override",
]
DEFERRED = [
    "non-default root actual export without an external top-binding adapter",
    "symbolic target generation and analog shaped values owned by Increment 43",
    "independent OpenVAF compilation owned by Increment 48",
    "numerical simulation owned by Increments 49 and 52",
    "general Verilog-AMS and synthesis",
]
SEMANTICS = (
    "canonical_construction_and_hierarchy_ownership",
    "constructor_default_and_actual_separation",
    "typed_child_parameter_overrides",
    "immediate_child_terminal_identity",
    "symmetric_conservative_connectivity",
    "source_located_hierarchy_rejection",
    "deterministic_reusable_module_definitions",
    "symbolic_override_retention",
    "bounded_stack_hierarchy_verification",
    "predecessor_equation_event_function_compatibility",
    "target_reparse",
    "no_partial_target_on_error",
)
REQUIRED = (
    MANIFEST,
    EVIDENCE,
    CLOSURE,
    IMPLEMENTATION,
    HANDOFF,
    ROADMAP,
    AMENDMENT,
    PREDECESSOR,
    PREDECESSOR_MANIFEST,
    WORKFLOW,
    RUNNER,
    PUBLIC_SOURCE,
)


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(f"NODAL-INC42: {message}")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_repository(root: Path = ROOT):
    for relative in REQUIRED:
        require((root / relative).is_file(), f"missing {relative}")

    raw = (root / EVIDENCE).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted-evidence checksum changed")
    accepted = json.loads(raw)
    require(accepted.get("schema") == 2 and accepted.get("increment") == 42,
            "invalid accepted-evidence identity")
    require(accepted.get("profile") ==
            "compiler-structural-scalar-analog-hierarchy-verilog-a-v0.1",
            "accepted profile changed")
    require(accepted.get("accepted_head") ==
            "fa921f95cc6aad9b1cb86e33edc7f09e53a7f4e4" and
            accepted.get("accepted_tree") ==
            "e9b74f0a8963ecec76b7e68406e104beda08bf04",
            "accepted implementation identity changed")
    require(accepted.get("implementation_pr") == 134 and
            accepted.get("implementation_merge") ==
            "98085f79aeaef3a5c7eeabfda462afa7299cbaf7" and
            accepted.get("implementation_merge_tree") == accepted["accepted_tree"] and
            accepted.get("preserved_dev_parent") ==
            "c3ea7cbf0432de7143a08b4431d51708c307d67e",
            "verified merge identity changed")

    qualification = accepted.get("qualification", {})
    require(qualification == {
        "pull_request_workflow_count": 31,
        "total_run_count": 32,
        "successful_job_count": 38,
        "attempt": 1,
        "push_core_run": 36396069440,
        "pull_request_core_run": 36396075706,
        "increment17_run": 36396075697,
        "increment33_run": 36396075748,
        "increment34_run": 36396075733,
        "core_required_status": "success",
        "all_latest_attempt_jobs_succeeded": True,
        "inventory_page_2_empty": True,
    }, "full qualification evidence changed")
    targeted = accepted.get("targeted_repair_qualification", {})
    require(targeted.get("head") ==
            "0befa54b8acf8261c3e71c6143694d66c337d061" and
            targeted.get("tree") == accepted["accepted_tree"] and
            targeted.get("runs") == {
                "core": 36393366474,
                "increment20": 36393399779,
                "increment23": 36393426608,
                "increment28": 36393452982,
                "increment29": 36393481325,
            } and targeted.get("constructor_artifact_id") == 10957640896 and
            targeted.get("constructor_artifact_sha256") ==
            "1da3fe76ede6ff3081e26ca0e288e974177fbf479cedb5e7b5cf01ee99e3f20c",
            "targeted repair evidence changed")
    require(accepted.get("review") == {
        "comment_id": 5866190034,
        "reviewed_commit": accepted["accepted_head"],
        "result": "completed-with-no-major-issues",
        "review_thread_count": 12,
        "unresolved_threads": 0,
    }, "review evidence changed")

    post_merge = accepted.get("post_merge_ci", {})
    require(post_merge == {
        "status": "skipped",
        "reason": "qualified-identical-tree-merge",
        "merge_commit": accepted["implementation_merge"],
        "merge_tree": accepted["accepted_tree"],
        "inventory_pages": [1, 2],
        "unexpected_runs": [],
    }, "post-merge skip evidence changed or fabricated")
    require("run_id" not in post_merge and
            not any(key.lower().endswith("_run") for key in post_merge),
            "post-merge skip must not carry a run identifier")

    witness = accepted.get("closure_witness", {})
    require(witness.get("pull_request") == 135 and
            witness.get("source_commit") ==
            "aa201f415a1d139948d24351c86862078cd101d2" and
            witness.get("source_tree") ==
            "e785dcb4d061642573cdcba6b00cf5680573140e" and
            witness.get("core_run") == 36403338628 and
            witness.get("dedicated_run") == 36403361696 and
            witness.get("artifact_id") == 10962330727 and
            witness.get("artifact_sha256") ==
            "e2e39ff57c79904ba6987baf369e7bda09cb6aa375bda1d5bd055eaafa886ea8",
            "closure witness identity changed")
    require(witness.get("source_sha256") == SOURCE_SHA256,
            "closure source identities changed")
    require(witness.get("output_sha256") == {
        "increment42-public.mlir":
            "eb09715fc24d2049c8f2e145c554efa0fa1a22fcf5d8b040ecb964d01acc21eb",
        "increment42-normalized.mlir":
            "5de89d136c6a30fae9e1b39b2b802281fad7d72265039a9a256a78871910a9de",
        "increment42-public.va":
            "d78386b05efc24318374a97cad60a78787dd88a1a96ad71ecb359e0d02fb86bd",
    }, "closure output identities changed")
    require(witness.get("native_matrix") == {
        "schema": "nodal.increment42.native-hierarchy-matrix.v1",
        "case_count": 572,
        "failure_count": 0,
        "compiler_sha256":
            "d6b17a9d9b5c923f896fdfd0730bceca9e3beb54e2653520d56890e0f6a8a9c2",
    }, "native hierarchy matrix evidence changed")
    for field in ("numerical_simulator_execution_claimed",
                  "independent_openvaf_execution_claimed",
                  "general_verilog_ams_claimed", "synthesis_claimed"):
        require(accepted.get(field) is False, f"unsupported claim enabled: {field}")

    require(digest(root / PREDECESSOR) == PREDECESSOR_EVIDENCE_SHA256,
            "accepted predecessor evidence changed")
    prior_manifest = json.loads((root / PREDECESSOR_MANIFEST).read_text())
    require(prior_manifest.get("status") == "validated-analog-functions" and
            prior_manifest.get("accepted_evidence") == {
                "path": PREDECESSOR,
                "sha256": PREDECESSOR_EVIDENCE_SHA256,
            }, "predecessor acceptance regressed")

    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 42 and
            manifest.get("contract_version") == "1",
            "invalid manifest identity")
    require(manifest.get("status") == "validated-analog-hierarchy" and
            manifest.get("profile") ==
            "compiler-structural-scalar-analog-hierarchy-verilog-a",
            "manifest profile or state changed")
    require(manifest.get("operations") == OPERATIONS and
            manifest.get("root_actual_policy") ==
            "default-equal-only-without-external-top-binding" and
            manifest.get("qualification") ==
            "compiler-structural-not-independent-tool-or-numerical-simulation",
            "manifest semantic boundary changed")
    require(manifest.get("accepted_evidence") == {
        "path": EVIDENCE,
        "sha256": ACCEPTED_EVIDENCE_SHA256,
    } and manifest.get("remaining") == [] and
            manifest.get("deferred") == DEFERRED and
            manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "manifest acceptance contract changed")

    for relative, expected in LIVE_SOURCE_SHA256.items():
        require(digest(root / relative) == expected,
                f"qualified closure source changed: {relative}")

    roadmap = (root / ROADMAP).read_text()
    open_parent = "- [ ] **Increment 42 — Analog hierarchy and parameterized instances**"
    closed_parent = "- [x] **Increment 42 — Analog hierarchy and parameterized instances**"
    require(roadmap.count(open_parent) == 0 and roadmap.count(closed_parent) == 1,
            "roadmap parent is not uniquely closed")
    require("- [x] **F-042.G — Evidence, documentation and acceptance.**" in roadmap,
            "F-042.G is not closed")
    revisions = re.findall(r"^\*\*Revision:\*\* (\d+)\.(\d+)$", roadmap, re.M)
    require(len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 56),
            "validated Increment 42 requires roadmap revision 1.56 or later")
    amendment = (root / AMENDMENT).read_text()
    require("Increment 42 compiler profile is accepted" in amendment and
            "complete G\nand the Increment 42 parent" in amendment,
            "roadmap amendment does not agree with acceptance")
    implementation = (root / IMPLEMENTATION).read_text()
    require("**Status:** Validated" in implementation and
            "increment42-evidence-closure.md" in implementation,
            "implementation record is not closed")
    require("Increment 42 still requires F-042.G" not in
            (root / HANDOFF).read_text(), "later-tool handoff still reports closure open")

    closure = (root / CLOSURE).read_text()
    for token in (accepted["accepted_head"], accepted["accepted_tree"],
                  accepted["implementation_merge"], str(qualification["push_core_run"]),
                  str(witness["artifact_id"]), witness["artifact_sha256"],
                  ACCEPTED_EVIDENCE_SHA256, "qualified-identical-tree merge",
                  "not independent OpenVAF", "not pseudocode"):
        require(token in closure, f"closure omits accepted identity or limit: {token}")
    for name, expected in witness["output_sha256"].items():
        require(name in closure and expected in closure,
                f"closure omits output witness: {name}")
    for language, hashes in accepted["demonstration_sha256"].items():
        blocks = re.findall(r"^```" + language + r"\n(.*?)^```",
                            closure, re.M | re.S)
        observed = [hashlib.sha256(block.strip().encode()).hexdigest()
                    for block in blocks]
        require(observed == hashes, f"accepted {language} demonstration changed")

    workflow = (root / WORKFLOW).read_text()
    for token in ("Increment42MlirCheck", "increment42-public.mlir",
                  "increment42-normalized.mlir", "increment42-public.va",
                  "nodal-gate-default", "--nodal-to-verilog-a", "sha256sum",
                  "actions/upload-artifact", "contents: read"):
        require(token in workflow, f"closure workflow omits: {token}")
    require("contents: write" not in workflow, "closure workflow must remain read-only")


if __name__ == "__main__":
    check_repository()
    print("Increment 42 accepted-evidence contract: PASS")
