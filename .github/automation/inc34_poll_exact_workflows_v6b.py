#!/usr/bin/env python3
"""Reconcile a complete exact-head workflow matrix without masking failures."""

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
NEUTRAL_CONCLUSIONS = {"success", "skipped", "neutral"}
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
        key = (str(run.get("name", "")), str(run.get("event", "")))
        previous = latest.get(key)
        if previous is None or int(run.get("id", 0)) > int(previous.get("id", 0)):
            latest[key] = run

    runs = list(latest.values())
    names = {str(run.get("name")) for run in runs}
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
    unsupported = sorted(
        f"{run.get('name')}:{run.get('conclusion')}"
        for run in runs
        if run.get("status") == "completed"
        and run.get("conclusion") not in NEUTRAL_CONCLUSIONS
    )
    missing = sorted(REQUIRED_NAMES - names)
    required_not_successful = sorted(
        name
        for name in REQUIRED_NAMES
        if not any(
            str(run.get("name")) == name
            and run.get("status") == "completed"
            and run.get("conclusion") == "success"
            for run in runs
        )
    )
    successful = sorted(
        str(run.get("name"))
        for run in runs
        if run.get("status") == "completed" and run.get("conclusion") == "success"
    )
    neutral = sorted(
        f"{run.get('name')}:{run.get('conclusion')}"
        for run in runs
        if run.get("status") == "completed"
        and run.get("conclusion") in {"skipped", "neutral"}
    )
    settled = bool(runs) and not active
    acceptable = (
        settled
        and not bad
        and not unsupported
        and not missing
        and not required_not_successful
        and len(runs) >= args.minimum_count
    )
    print(
        json.dumps(
            {
                "run_count": len(runs),
                "minimum_count": args.minimum_count,
                "active": active,
                "bad": bad,
                "unsupported": unsupported,
                "missing_required": missing,
                "required_not_successful": required_not_successful,
                "successful": successful,
                "neutral": neutral,
                "settled": settled,
                "acceptable": acceptable,
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
