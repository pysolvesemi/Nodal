#!/usr/bin/env python3
"""Reconcile every GitHub Actions workflow run observed for one exact SHA."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

BAD_CONCLUSIONS = {
    "failure",
    "cancelled",
    "timed_out",
    "action_required",
    "startup_failure",
    "stale",
}
ACTIVE_STATES = {"queued", "in_progress", "waiting", "pending", "requested"}
REQUIRED_NAMES = {
    "Core CI",
    "Increment 32 Equation Contribution Semantics",
    "Increment 33 Analog Procedural Assignment",
    "Increment 34 Analog Control Flow",
    "Increment 133 Analog Semantic API",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--minimum-count", type=int, default=24)
    args = parser.parse_args()

    document = json.loads(args.input.read_text(encoding="utf-8"))
    latest: dict[tuple[str, str], dict] = {}
    for run in document.get("workflow_runs", []):
        name = str(run.get("name", ""))
        event = str(run.get("event", ""))
        key = (name, event)
        previous = latest.get(key)
        if previous is None or int(run.get("id", 0)) > int(previous.get("id", 0)):
            latest[key] = run

    runs = list(latest.values())
    active = sorted(
        f"{run.get('name')}:{run.get('status')}"
        for run in runs
        if run.get("status") in ACTIVE_STATES
    )
    bad = sorted(
        f"{run.get('name')}:{run.get('conclusion')}"
        for run in runs
        if run.get("conclusion") in BAD_CONCLUSIONS
    )
    names = {str(run.get("name")) for run in runs}
    missing = sorted(REQUIRED_NAMES - names)
    completed_success = sorted(
        str(run.get("name"))
        for run in runs
        if run.get("status") == "completed" and run.get("conclusion") == "success"
    )
    settled = bool(runs) and not active
    acceptable = (
        settled
        and not bad
        and not missing
        and len(runs) >= args.minimum_count
        and len(completed_success) == len(runs)
    )
    state = {
        "run_count": len(runs),
        "minimum_count": args.minimum_count,
        "active": active,
        "bad": bad,
        "missing_required": missing,
        "successful": completed_success,
        "settled": settled,
        "acceptable": acceptable,
    }
    print(json.dumps(state, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
