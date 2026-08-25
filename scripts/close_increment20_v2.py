#!/usr/bin/env python3
"""Close and squash-merge Increment 20 after accepted GitHub Actions evidence."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


def run(*args: str, capture: bool = True) -> str:
    completed = subprocess.run(
        args,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=None,
    )
    return completed.stdout.strip() if capture else ""


def gh_json(path: str) -> Any:
    return json.loads(run("gh", "api", path))


def workflow_run(repo: str, sha: str, name: str, timeout_seconds: int = 3600) -> int:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        payload = gh_json(f"/repos/{repo}/actions/runs?head_sha={sha}&per_page=100")
        matches = [item for item in payload.get("workflow_runs", []) if item.get("name") == name]
        matches.sort(key=lambda item: item.get("created_at", ""))
        if matches:
            latest = matches[-1]
            status = latest.get("status")
            conclusion = latest.get("conclusion")
            if status == "completed":
                if conclusion != "success":
                    raise SystemExit(
                        f"{name} run {latest.get('id')} concluded {conclusion} on {sha}"
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
            "increment/20-scala-mlir-bridge",
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
        "increment/20-scala-mlir-bridge",
        "--base",
        "dev",
        "--title",
        "Increment 20 — Scala-to-MLIR bridge",
        "--body",
        (
            "Add deterministic source-correlated Scala-to-MLIR serialization, "
            "an argv-safe bounded nodalc process protocol, tests, and accepted "
            "evidence while preserving public API v0.3."
        ),
    )
    return int(url.rstrip("/").rsplit("/", 1)[-1])


def update_evidence(repo: str, pr_number: int, dedicated: int, core: int) -> None:
    manifest_path = Path("tests/compiler/fixtures/increment20/manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["status"] = "validated-scala-mlir-bridge"
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
        "**Revision:** 1.24",
        roadmap,
        count=1,
        flags=re.MULTILINE,
    )
    if revision_count != 1:
        raise SystemExit("roadmap revision replacement failed")
    unchecked = "- [ ] **Increment 20 — Scala-to-MLIR bridge**"
    checked = "- [x] **Increment 20 — Scala-to-MLIR bridge**"
    if checked not in roadmap:
        if roadmap.count(unchecked) != 1:
            raise SystemExit("Increment 20 roadmap item mismatch")
        roadmap = roadmap.replace(unchecked, checked, 1)
    scope = (
        "  - Lower deterministic construction state to versioned textual MLIR "
        "with source locations and invoke `nodalc` through a clear process protocol."
    )
    evidence = (
        f"  - Evidence: PR [#{pr_number}](https://github.com/{repo}/pull/{pr_number}), "
        f"dedicated validation run [{dedicated}]"
        f"(https://github.com/{repo}/actions/runs/{dedicated}), and Core CI run "
        f"[{core}](https://github.com/{repo}/actions/runs/{core})."
    )
    if evidence not in roadmap:
        if roadmap.count(scope) != 1:
            raise SystemExit("Increment 20 scope line mismatch")
        roadmap = roadmap.replace(scope, scope + "\n" + evidence, 1)
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


def main() -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    pr_number = find_or_create_pr(repo)
    implementation_sha = run("git", "rev-parse", "HEAD")
    dedicated = workflow_run(repo, implementation_sha, "Increment 20 Scala-to-MLIR Bridge")
    core = workflow_run(repo, implementation_sha, "Core CI")

    update_evidence(repo, pr_number, dedicated, core)
    run("python3", "scripts/check_increment20.py", capture=False)
    run("python3", "tests/compiler/test_increment20.py", capture=False)
    run("git", "diff", "--check", capture=False)
    run("git", "config", "user.name", "pysolvesemi", capture=False)
    run("git", "config", "user.email", "pysolvesemi@gmail.com", capture=False)
    run(
        "git",
        "add",
        "docs/roadmap/nodal-development-todo.md",
        "tests/compiler/fixtures/increment20/manifest.json",
        capture=False,
    )
    run(
        "git",
        "commit",
        "-m",
        "Increment 20 — record accepted evidence and close roadmap item",
        capture=False,
    )
    run("git", "push", "origin", "HEAD:increment/20-scala-mlir-bridge", capture=False)

    final_sha = run("git", "rev-parse", "HEAD")
    workflow_run(repo, final_sha, "Increment 20 Scala-to-MLIR Bridge")
    workflow_run(repo, final_sha, "Core CI")
    if unresolved_threads(repo, pr_number) != 0:
        raise SystemExit("Increment 20 PR has unresolved review threads")
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
        "Increment 20 — Scala-to-MLIR bridge",
        "--body",
        (
            "Add deterministic source-correlated Scala-to-MLIR serialization, "
            "bounded nodalc process invocation, accepted evidence, and roadmap closure."
        ),
        capture=False,
    )
    print(
        json.dumps(
            {
                "pull_request": pr_number,
                "implementation_sha": implementation_sha,
                "final_sha": final_sha,
                "dedicated_run": dedicated,
                "core_ci_run": core,
                "status": "merged",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
