#!/usr/bin/env python3
"""Enforce branch, pull-request, and design-gate contribution policy."""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / ".github" / "change-policy.json"


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def _load_json(path: Path, problems: list[Problem], code: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return {}
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def _changed_files(root: Path, base_ref: str) -> tuple[list[str], Problem | None]:
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "--diff-filter=ACMR", f"{base_ref}...HEAD"],
            cwd=root,
            check=True,
            text=True,
            capture_output=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return [], Problem(
            "NODAL-POLICY-001",
            f"cannot compute changed files against {base_ref!r}: {exc}",
        )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()], None


def _matches(path: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def _gate_files(changed: list[str], directory: str, filename_pattern: str) -> list[str]:
    prefix = directory.rstrip("/") + "/"
    return [
        path
        for path in changed
        if path.startswith(prefix)
        and fnmatch.fnmatchcase(Path(path).name, filename_pattern)
    ]


def _check_design_gates(
    root: Path,
    policy: dict[str, Any],
    changed: list[str],
    problems: list[Problem],
) -> None:
    gate = policy.get("design_gate")
    protected = policy.get("protected_changes")
    if not isinstance(gate, dict) or not isinstance(protected, list):
        problems.append(Problem("NODAL-POLICY-002", "change-policy design-gate configuration is incomplete"))
        return
    directory = gate.get("directory")
    filename_pattern = gate.get("filename_pattern")
    approved_marker = gate.get("approved_marker")
    superseded_marker = gate.get("superseded_marker")
    superseded_by_prefix = gate.get("superseded_by_prefix")
    scope_prefix = gate.get("scope_marker_prefix")
    if not all(
        isinstance(value, str) and value
        for value in (
            directory,
            filename_pattern,
            approved_marker,
            superseded_marker,
            superseded_by_prefix,
            scope_prefix,
        )
    ):
        problems.append(Problem("NODAL-POLICY-002", "change-policy design-gate fields are invalid"))
        return

    required_scopes: set[str] = set()
    for item in protected:
        if not isinstance(item, dict):
            continue
        scope = item.get("scope")
        patterns = item.get("patterns")
        if isinstance(scope, str) and isinstance(patterns, list):
            text_patterns = [pattern for pattern in patterns if isinstance(pattern, str)]
            if any(_matches(path, text_patterns) for path in changed):
                required_scopes.add(scope)
    if not required_scopes:
        return

    candidates = _gate_files(changed, directory, filename_pattern)
    approved_scopes: set[str] = set()
    for relative in candidates:
        path = root / relative
        try:
            content = path.read_text(encoding="utf-8")
        except OSError as exc:
            problems.append(Problem("NODAL-POLICY-003", f"cannot read design gate {relative}: {exc}"))
            continue
        if superseded_marker in content:
            if superseded_by_prefix not in content:
                problems.append(
                    Problem(
                        "NODAL-POLICY-016",
                        f"superseded design gate lacks replacement reference: {relative}",
                    )
                )
            continue
        if approved_marker not in content:
            problems.append(Problem("NODAL-POLICY-004", f"design gate is not approved: {relative}"))
            continue
        for line in content.splitlines():
            if line.startswith(scope_prefix):
                approved_scopes.add(line.removeprefix(scope_prefix).strip())
    missing = sorted(required_scopes - approved_scopes)
    if missing:
        problems.append(
            Problem(
                "NODAL-POLICY-005",
                "protected change requires an approved design gate for scope(s): "
                + ", ".join(missing),
            )
        )


def _check_pull_request(policy: dict[str, Any], event: dict[str, Any], problems: list[Problem]) -> None:
    pull_request = event.get("pull_request")
    if not isinstance(pull_request, dict):
        problems.append(Problem("NODAL-POLICY-006", "pull_request event payload is missing"))
        return
    base = pull_request.get("base", {}).get("ref") if isinstance(pull_request.get("base"), dict) else None
    head = pull_request.get("head", {}).get("ref") if isinstance(pull_request.get("head"), dict) else None
    title = pull_request.get("title")
    body = pull_request.get("body") or ""
    integration = policy.get("integration_branch")
    release = policy.get("release_branch")
    branch_pattern = policy.get("increment_branch_pattern")
    title_pattern = policy.get("increment_title_pattern")
    sections = policy.get("required_pull_request_sections")

    if base == integration:
        if not isinstance(head, str) or not isinstance(branch_pattern, str) or re.fullmatch(branch_pattern, head) is None:
            problems.append(Problem("NODAL-POLICY-007", f"pull request into {integration} must use increment/<number>-<slug>"))
        if not isinstance(title, str) or not isinstance(title_pattern, str) or re.fullmatch(title_pattern, title) is None:
            problems.append(Problem("NODAL-POLICY-008", "increment pull-request title must be 'Increment <number> — <summary>'"))
    elif base == release:
        if head != integration:
            problems.append(Problem("NODAL-POLICY-009", f"only {integration} may be promoted into {release}"))
    else:
        problems.append(Problem("NODAL-POLICY-010", f"unsupported pull-request base branch: {base!r}"))

    if not isinstance(sections, list):
        problems.append(Problem("NODAL-POLICY-011", "required pull-request sections are missing from policy"))
    else:
        for section in sections:
            if isinstance(section, str) and section not in body:
                problems.append(Problem("NODAL-POLICY-012", f"pull-request body lacks required section: {section}"))


def _event_payload(path: Path, problems: list[Problem]) -> dict[str, Any]:
    return _load_json(path, problems, "NODAL-POLICY-013")


def check_repository(
    root: Path,
    *,
    base_ref: str | None = None,
    changed_files: list[str] | None = None,
    event_name: str | None = None,
    event_path: Path | None = None,
) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    policy = _load_json(root / POLICY_PATH.relative_to(ROOT), problems, "NODAL-POLICY-014")
    if policy.get("schema") != 1:
        problems.append(Problem("NODAL-POLICY-015", "unsupported change-policy schema"))
        return problems

    observed = list(changed_files or [])
    if changed_files is None and base_ref:
        observed, problem = _changed_files(root, base_ref)
        if problem:
            problems.append(problem)
    _check_design_gates(root, policy, observed, problems)

    active_event = event_name or os.environ.get("GITHUB_EVENT_NAME")
    active_path = event_path
    if active_path is None and os.environ.get("GITHUB_EVENT_PATH"):
        active_path = Path(os.environ["GITHUB_EVENT_PATH"])
    if active_event == "pull_request" and active_path is not None:
        _check_pull_request(policy, _event_payload(active_path, problems), problems)
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--base-ref")
    parser.add_argument("--changed-file", action="append", default=None)
    parser.add_argument("--event-name")
    parser.add_argument("--event-path", type=Path)
    args = parser.parse_args(argv)
    problems = check_repository(
        args.root,
        base_ref=args.base_ref,
        changed_files=args.changed_file,
        event_name=args.event_name,
        event_path=args.event_path,
    )
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"contribution policy check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("contribution policy check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
