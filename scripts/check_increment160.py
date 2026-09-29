#!/usr/bin/env python3
"""Guard Foundation 160 accepted-evidence closure and its sole checklist."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = "docs/implementation/increment160-accepted-evidence.json"
CLOSURE = "docs/implementation/increment160-evidence-closure.md"
READINESS = "docs/implementation/increment160-readiness.md"
PLAN = "docs/roadmap/construction-frontend-modularization-v0.1-plan.md"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
MANIFEST = "tests/compiler/fixtures/increment160/closure-manifest.json"
EXPERIMENT = "tests/compiler/fixtures/increment160/experiment.json"
SCALA_SOURCE = (
    "examples/continuousTimeApi/src/nodal/increment42fixture/"
    "Increment42PredecessorCombinations.scala"
)
FENCE = chr(96) * 3
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_sha(value: object, label: str, pattern: re.Pattern[str] = HEX64) -> str:
    require(isinstance(value, str) and pattern.fullmatch(value) is not None,
            f"{label} is not a canonical digest")
    return value


def check_repository(root: Path = ROOT) -> None:
    accepted = json.loads((root / EVIDENCE).read_text())
    manifest = json.loads((root / MANIFEST).read_text())
    closure = (root / CLOSURE).read_text()
    readiness = (root / READINESS).read_text()
    plan = (root / PLAN).read_text()
    roadmap = (root / ROADMAP).read_text()

    require(accepted["schema"] == 1 and accepted["increment"] == 160,
            "wrong accepted-evidence identity")
    require(accepted["status"] == "validated", "F-160 evidence is not validated")
    require(manifest["schema"] == 1 and manifest["increment"] == 160,
            "wrong closure-manifest identity")
    require(manifest["status"] == "validated", "F-160 manifest is not validated")

    for entry, path in (("accepted_evidence", EVIDENCE),
                        ("closure_report", CLOSURE)):
        record = manifest[entry]
        require(record["path"] == path, f"{entry} path changed")
        require(sha256(root / path) == check_sha(record["sha256"], entry),
                f"{entry} digest changed")

    immutable = manifest["immutable_experiment"]
    require(immutable["path"] == EXPERIMENT, "experiment path changed")
    require(sha256(root / EXPERIMENT) == check_sha(immutable["file_sha256"], "experiment file"),
            "immutable experiment bytes changed")
    require(immutable["definition_sha256"] ==
            "9a4604927592ab773c8930ac53012fbdb1c3da6365832792ad0d5a7f20026406",
            "immutable experiment definition changed")
    require(accepted["experiment"] == {
        "path": EXPERIMENT,
        "file_sha256": immutable["file_sha256"],
        "definition_sha256": immutable["definition_sha256"],
        "ordering": ["AB", "BA", "AB"],
        "cold_samples_per_case": 1,
        "warmups_per_case": 2,
        "warm_samples_per_case": 5,
        "bounded_noisy_repeat_mad_multiplier": 3,
    }, "accepted experiment contract changed")

    for key in ("accepted_head", "accepted_tree", "implementation_merge",
                "implementation_merge_tree", "preserved_dev_parent"):
        check_sha(accepted[key], key, HEX40)
    require(accepted["accepted_head"] ==
            "a792cf2616936b78efac2079cba1749e38e504a2",
            "accepted head changed")
    require(accepted["accepted_tree"] ==
            "62d425c5954feeb13e661d86c78f90adf5959b1a",
            "accepted tree changed")
    require(accepted["implementation_pr"] == 137, "implementation PR changed")
    require(accepted["implementation_merge"] ==
            "ee6e919510e95a8e267c676a9724ec1bae86f348",
            "implementation merge changed")
    require(accepted["implementation_merge_tree"] == accepted["accepted_tree"],
            "merge tree is not the qualified tree")
    require(accepted["preserved_dev_parent"] ==
            "60d56be8dfcd0835ef6ddfa85cf9189dfdb1bb68",
            "merge parent changed")

    targeted = accepted["qualification"]["targeted"]
    require(targeted["head"] ==
            "9b1b53987b23bf3c79d82ffc41481b86751ee4fd",
            "targeted head changed")
    require(targeted["tree"] == accepted["accepted_tree"],
            "targeted tree differs from accepted tree")
    require(targeted["core_run"] == 36561903660 and targeted["core_attempt"] == 2,
            "targeted Core identity changed")
    require(targeted["core_conclusion"] == "success",
            "targeted Core is not successful")
    require(targeted["specialized_workflow_count"] == 14 and
            targeted["specialized_conclusion"] == "success",
            "specialized targeting contract changed")
    require(len(targeted["specialized_runs"]) == 14 and
            len(set(targeted["specialized_runs"])) == 14,
            "specialized run inventory is not exact")
    require(targeted["artifact_id"] == 11040216375,
            "targeted parity artifact changed")
    check_sha(targeted["artifact_zip_sha256"], "targeted artifact")

    full = accepted["qualification"]["full"]
    require(full["head"] == accepted["accepted_head"] and
            full["tree"] == accepted["accepted_tree"],
            "full qualification is not on the accepted candidate")
    require(full["direct_parent"] == targeted["head"] and
            full["parent_child_changed_file_count"] == 0,
            "final child is not tree-identical to the reviewed target")
    require((full["pull_request_workflow_count"], full["total_run_count"],
             full["successful_run_count"], full["successful_check_run_count"]) ==
            (30, 31, 31, 37), "full-CI inventory changed")
    require(full["push_core_run"] == 36588957739 and
            full["push_core_attempt"] == 2 and
            full["push_native_job"] == 109506326985 and
            full["required_aggregate_job"] == 109529105560,
            "push Core receipt changed")
    require(full["pull_request_core_run"] == 36588963275,
            "PR Core receipt changed")
    require(full["required_check"] == "core-ci/required" and
            full["required_check_conclusion"] == "success",
            "required Core check is not successful")
    require(full["increment34_triggered"] is False,
            "non-applicable Increment 34 was relabeled")

    review = accepted["review"]
    require(review == {
        "substantive_head": targeted["head"],
        "comment_id": 5889249936,
        "conclusion": "no findings",
        "final_head_tree_identical": True,
    }, "independent review receipt changed")
    require(accepted["post_merge_ci"] == {
        "status": "skipped",
        "reason": "qualified-identical-tree-merge",
        "merge_sha": accepted["implementation_merge"],
        "run_count": 0,
    }, "post-merge CI must remain an explicit zero-run skip")

    parity = accepted["final_parity"]
    expected_counts = {
        "case_count": 55,
        "accepted_case_count": 41,
        "rejection_recovery_case_count": 14,
        "scale_case_count": 10,
        "source_mlir_case_count": 33,
        "native_verilog_a_case_count": 19,
        "mandatory_trial_count": 330,
        "compile_trial_count": 6,
        "startup_trial_count": 6,
        "command_count": 593,
        "measurement_count": 912,
        "nonpassing_measurement_count": 0,
        "artifact_record_count": 1500,
        "artifact_rehash_mismatch_count": 0,
    }
    require(parity["run_id"] == full["push_core_run"] and parity["attempt"] == 2,
            "final parity run changed")
    require(parity["artifact_id"] == 11049554512 and
            parity["artifact_size_bytes"] == 13528329,
            "final parity artifact identity changed")
    require(parity["artifact_zip_sha256"] ==
            "df7cb86a20e5207f8f5e416b349b99ab239b856e7cc464a0e40a1ffc9b799950",
            "final parity ZIP digest changed")
    require(parity["results_sha256"] ==
            "d4e88278041e33a60a4d767c6e90f9d255883b6e297465e9b4b8660e398ee774",
            "final parity result digest changed")
    require(parity["status"] == "passed", "final parity is not passed")
    for key, value in expected_counts.items():
        require(parity[key] == value, f"final parity {key} changed")

    contract = accepted["implementation_contract"]
    require(contract == {
        "production_file_count": 6,
        "construction_file_count": 5,
        "construction_session_count": 1,
        "canonical_registry_owners_preserved": True,
        "frozen_increment16_validator_byte_identical": True,
        "scala_test_count": 226,
        "scala_suite_count": 26,
    }, "implementation ownership/test contract changed")

    demo = accepted["demonstration"]
    require(demo["scala_source_path"] == SCALA_SOURCE,
            "demonstration Scala source changed")
    require(sha256(root / SCALA_SOURCE) ==
            check_sha(demo["scala_source_file_sha256"], "Scala source"),
            "public Scala source bytes changed")
    require(demo["verilog_a_artifact_sha256"] ==
            "a94fc9fb2904d73a28c433817c09c2bf1373ab2a8a6650dd1dc8867d685ca612",
            "retained Verilog-A digest changed")
    require((demo["baseline_copy_count"], demo["candidate_copy_count"],
             demo["all_copies_equal"]) == (3, 3, True),
            "paired demonstration equality changed")
    require(demo["required_tokens"] == ["initial_step", "transition", "<+"],
            "target demonstration token set changed")

    for language, key in (("scala", "scala_block_sha256"),
                          ("verilog", "verilog_a_block_sha256")):
        pattern = r"^" + re.escape(FENCE) + language + r"\n(.*?)^" + re.escape(FENCE)
        blocks = re.findall(pattern, closure, re.M | re.S)
        require(len(blocks) == 1, f"expected one {language} demonstration")
        observed = hashlib.sha256(blocks[0].strip().encode()).hexdigest()
        require(observed == check_sha(demo[key], f"{language} block"),
                f"{language} demonstration changed")
    scala_pattern = r"^" + re.escape(FENCE) + r"scala\n(.*?)^" + re.escape(FENCE)
    verilog_pattern = r"^" + re.escape(FENCE) + r"verilog\n(.*?)^" + re.escape(FENCE)
    scala_block = re.findall(scala_pattern, closure, re.M | re.S)[0]
    verilog_block = re.findall(verilog_pattern, closure, re.M | re.S)[0]
    for token in ("HierarchyEventTop", "on(initialStep)", "transition(", "<+"):
        require(token in scala_block, f"Scala demonstration omits {token}")
    for token in ("HierarchyEventTop", "@(initial_step)", "transition(", "<+"):
        require(token in verilog_block, f"Verilog-A demonstration omits {token}")

    require(all(value is False for value in accepted["capability_claims"].values()),
            "closure overclaims an unsupported capability")
    handoff = accepted["handoff"]
    require(handoff["f096_workloads_and_measurements"] is True,
            "F-096 handoff was dropped")
    require(handoff["f043_prerequisite_satisfied"] is True and
            handoff["f043_started"] is False,
            "F-043 eligibility boundary changed")
    require(handoff["equation_legalization_owner"] == "F-141" and
            handoff["equation_source_residual_owners"] == ["F-134", "F-135"],
            "equation handoff changed")

    checked = re.findall(
        r"^\s*- \[x\] \*\*(?:Foundation Increment 160|F-160)", plan, re.M)
    opened = re.findall(
        r"^\s*- \[ \] \*\*(?:Foundation Increment 160|F-160)", plan, re.M)
    require(len(checked) == manifest["sole_checklist"]["checked_box_count"] == 20,
            "F-160 checked-box count changed")
    require(len(opened) == manifest["sole_checklist"]["open_box_count"] == 0,
            "F-160 still has an open box")
    require("**Status:** Accepted 2026-09-29" in plan,
            "sole checklist is not accepted")
    require("F-043 remains" in plan and
            "open and no F-043 implementation is started" in plan,
            "plan starts or obscures F-043")

    for token in (accepted["accepted_head"], accepted["accepted_tree"],
                  accepted["implementation_merge"],
                  str(parity["artifact_id"]), parity["artifact_zip_sha256"],
                  "All 20 F-160 boxes", "F-043 remains open",
                  "F-096", "F-141", "F-134/F-135"):
        require(token in readiness, f"readiness omits {token}")

    require("- [ ] **Increment 43" in roadmap,
            "Increment 43 must remain open")
    require("Prerequisite evidence: accepted F-160" in roadmap and
            "no implementation work is started" in roadmap,
            "main roadmap omits the bounded F-043 eligibility record")

    for token in (accepted["accepted_head"], accepted["accepted_tree"],
                  accepted["implementation_merge"], str(parity["artifact_id"]),
                  parity["artifact_zip_sha256"], "qualified-identical-tree merge",
                  "55 fixed cases", "1,500 exact artifact records",
                  "F-043 remains open and unstarted", "F-096", "F-141"):
        require(token in closure, f"closure report omits {token}")


if __name__ == "__main__":
    check_repository()
    print("Increment 160 accepted-evidence contract: PASS")
