#!/usr/bin/env python3
"""Close and squash-merge Increment 21 after accepted GitHub Actions evidence."""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any


def run(*args: str, capture: bool = True) -> str:
    completed = subprocess.run(
        args,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return completed.stdout.strip() if capture else ""


def gh_json(path: str) -> Any:
    return json.loads(run("gh", "api", path))


def workflow_run(repo: str, sha: str, name: str, timeout_seconds: int = 5400) -> int:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        payload = gh_json(f"/repos/{repo}/actions/runs?head_sha={sha}&per_page=100")
        matches = [item for item in payload.get("workflow_runs", []) if item.get("name") == name]
        matches.sort(key=lambda item: item.get("created_at", ""))
        if matches:
            latest = matches[-1]
            if latest.get("status") == "completed":
                if latest.get("conclusion") != "success":
                    raise SystemExit(
                        f"{name} run {latest.get('id')} concluded "
                        f"{latest.get('conclusion')} on {sha}"
                    )
                return int(latest["id"])
        time.sleep(20)
    raise SystemExit(f"timed out waiting for {name} on {sha}")


def find_or_create_pr(repo: str) -> int:
    listed = json.loads(
        run(
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--head",
            "increment/21-native-verifier-pipeline",
            "--base",
            "dev",
            "--state",
            "open",
            "--json",
            "number",
        )
    )
    if listed:
        return int(listed[0]["number"])
    url = run(
        "gh",
        "pr",
        "create",
        "--repo",
        repo,
        "--head",
        "increment/21-native-verifier-pipeline",
        "--base",
        "dev",
        "--title",
        "Increment 21 — native staged semantic verification pipeline",
        "--body",
        (
            "Add registered native parse/verification passes for construction, hierarchy, "
            "connectivity, type/shape, parameter/loop, enum/FSM, domain, protocol/pipeline, "
            "memory/effect, analog/mixed-signal, and target capability checks; add "
            "transactional acceptance/reverification and stable diagnostic evidence while "
            "preserving public API v0.3."
        ),
    )
    return int(url.rstrip("/").rsplit("/", 1)[-1])


def update_evidence(repo: str, pr_number: int, dedicated: int, core: int) -> None:
    manifest_path = Path("tests/compiler/fixtures/increment21/manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["status"] = "validated-native-verification-pipeline"
    manifest["evidence"] = {
        "pull_request": pr_number,
        "dedicated_run": dedicated,
        "core_ci_run": core,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    roadmap_path = Path("docs/roadmap/nodal-development-todo.md")
    roadmap = roadmap_path.read_text(encoding="utf-8")
    roadmap, revision_count = re.subn(
        r"^\*\*Revision:\*\* [0-9.]+$",
        "**Revision:** 1.25",
        roadmap,
        count=1,
        flags=re.MULTILINE,
    )
    if revision_count != 1:
        raise SystemExit("roadmap revision replacement failed")
    unchecked = (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, "
        "and pass pipeline**"
    )
    checked = unchecked.replace("- [ ]", "- [x]", 1)
    if checked not in roadmap:
        if roadmap.count(unchecked) != 1:
            raise SystemExit("Increment 21 roadmap item mismatch")
        roadmap = roadmap.replace(unchecked, checked, 1)

    scope = (
        "  - Parse Nodal MLIR, run staged whole-design semantic verification, "
        "normalize transactionally, and reverify before accepting compiler state."
    )
    evidence = (
        f"  - Evidence: PR [#{pr_number}](https://github.com/{repo}/pull/{pr_number}), "
        f"dedicated validation run [{dedicated}]"
        f"(https://github.com/{repo}/actions/runs/{dedicated}), and Core CI run "
        f"[{core}](https://github.com/{repo}/actions/runs/{core})."
    )
    if evidence not in roadmap:
        if roadmap.count(scope) == 1:
            roadmap = roadmap.replace(scope, scope + "\n" + evidence, 1)
        else:
            anchor = checked
            position = roadmap.index(anchor) + len(anchor)
            line_end = roadmap.find("\n", position)
            if line_end == -1:
                line_end = len(roadmap)
            roadmap = roadmap[:line_end] + "\n" + evidence + roadmap[line_end:]

    if "- [x] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**" in roadmap:
        raise SystemExit("roadmap closure would incorrectly advance Increment 22")
    roadmap_path.write_text(roadmap, encoding="utf-8")


def unresolved_threads(repo: str, pr_number: int) -> int:
    owner, name = repo.split("/", 1)
    query = (
        "query($owner:String!,$name:String!,$number:Int!){"
        "repository(owner:$owner,name:$name){pullRequest(number:$number){"
        "reviewThreads(first:100){nodes{isResolved}}}}}"
    )
    payload = json.loads(
        run(
            "gh",
            "api",
            "graphql",
            "-f",
            f"query={query}",
            "-F",
            f"owner={owner}",
            "-F",
            f"name={name}",
            "-F",
            f"number={pr_number}",
        )
    )
    nodes = payload["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"]
    return sum(1 for node in nodes if not node["isResolved"])


def wait_for_dev_merge(repo: str, pr_number: int, timeout_seconds: int = 900) -> str:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        payload = gh_json(f"/repos/{repo}/pulls/{pr_number}")
        if payload.get("merged"):
            return str(payload["merge_commit_sha"])
        time.sleep(10)
    raise SystemExit("timed out waiting for Increment 21 merge state")


def main() -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    pr_number = find_or_create_pr(repo)
    implementation_sha = run("git", "rev-parse", "HEAD")
    dedicated = workflow_run(repo, implementation_sha, "Increment 21 Native Verification Pipeline")
    core = workflow_run(repo, implementation_sha, "Core CI")

    update_evidence(repo, pr_number, dedicated, core)
    run("python3", "scripts/check_increment21.py", capture=False)
    run("python3", "tests/compiler/test_increment21.py", capture=False)
    run("git", "diff", "--check", capture=False)
    run("git", "config", "user.name", "pysolvesemi", capture=False)
    run("git", "config", "user.email", "pysolvesemi@gmail.com", capture=False)
    run(
        "git",
        "add",
        "docs/roadmap/nodal-development-todo.md",
        "tests/compiler/fixtures/increment21/manifest.json",
        capture=False,
    )
    run(
        "git",
        "commit",
        "-m",
        "Increment 21 — record accepted evidence and close roadmap item",
        capture=False,
    )
    run("git", "push", "origin", "HEAD:increment/21-native-verifier-pipeline", capture=False)

    final_sha = run("git", "rev-parse", "HEAD")
    final_dedicated = workflow_run(repo, final_sha, "Increment 21 Native Verification Pipeline")
    final_core = workflow_run(repo, final_sha, "Core CI")
    if unresolved_threads(repo, pr_number) != 0:
        raise SystemExit("Increment 21 PR has unresolved review threads")

    run(
        "gh",
        "pr",
        "merge",
        str(pr_number),
        "--repo",
        repo,
        "--squash",
        "--match-head-commit",
        final_sha,
        "--subject",
        "Increment 21 — native staged semantic verification pipeline",
        "--body",
        (
            "Add mandatory native staged whole-design verification, named and explicit pass "
            "pipelines, transactional normalization/reverification, stable diagnostics, "
            "negative fixtures, accepted evidence, and roadmap closure."
        ),
        capture=False,
    )
    merge_commit = wait_for_dev_merge(repo, pr_number)
    post_merge_core = workflow_run(repo, merge_commit, "Core CI")
    print(
        json.dumps(
            {
                "pull_request": pr_number,
                "implementation_sha": implementation_sha,
                "final_sha": final_sha,
                "merge_commit": merge_commit,
                "implementation_dedicated_run": dedicated,
                "implementation_core_ci_run": core,
                "final_dedicated_run": final_dedicated,
                "final_core_ci_run": final_core,
                "post_merge_core_ci_run": post_merge_core,
                "status": "merged",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
