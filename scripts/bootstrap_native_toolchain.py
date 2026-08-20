#!/usr/bin/env python3
"""Discover or install the native LLVM/MLIR/CIRCT toolchain pinned by Nodal."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from toolchain_lock import LockError, load_lock
from native_toolchain.install import (
    check_source_host_tools,
    install,
    safe_extract,
)
from native_toolchain.state import (
    BootstrapError,
    candidate_prefixes,
    default_prefix,
    discover,
    manifest_payload,
    plan_install,
    source_commands,
    validate_install,
    write_manifest,
)

__all__ = [
    "BootstrapError",
    "candidate_prefixes",
    "check_source_host_tools",
    "default_prefix",
    "discover",
    "install",
    "manifest_payload",
    "plan_install",
    "safe_extract",
    "source_commands",
    "validate_install",
    "write_manifest",
]


def _print_plan(plan: dict[str, Any], *, as_json: bool) -> None:
    if as_json:
        print(json.dumps(plan, indent=2, sort_keys=True))
        return
    print(f"lock:   {plan['lock_id']}")
    print(f"host:   {plan['host']['os']}-{plan['host']['arch']}")
    print(f"mode:   {plan['mode']}")
    print(f"prefix: {plan['prefix']}")
    print(f"asset:  {plan['asset']['filename']}")
    print(f"sha256: {plan['asset']['sha256']}")
    for command in plan.get("commands", []):
        print("command:", " ".join(command))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    commands = parser.add_subparsers(dest="command", required=True)

    status = commands.add_parser("status", help="discover a validated installation")
    status.add_argument("--toolchain", type=Path)
    status.add_argument("--require", action="store_true")
    status.add_argument("--json", action="store_true")

    plan = commands.add_parser("plan", help="show the exact locked install plan")
    plan.add_argument("--mode", choices=("auto", "prebuilt", "source"), default="auto")
    plan.add_argument("--prefix", type=Path)
    plan.add_argument("--jobs", type=int)
    plan.add_argument("--json", action="store_true")

    install_parser = commands.add_parser("install", help="install the locked toolchain")
    install_parser.add_argument(
        "--mode", choices=("auto", "prebuilt", "source"), default="auto"
    )
    install_parser.add_argument("--prefix", type=Path)
    install_parser.add_argument("--jobs", type=int)
    install_parser.add_argument("--force", action="store_true")
    install_parser.add_argument("--dry-run", action="store_true")
    install_parser.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        lock = load_lock(root)
        if args.command == "status":
            found, rejected = discover(root, lock, explicit=args.toolchain)
            result = {
                "found": str(found) if found else None,
                "rejected": rejected,
                "fallback": plan_install(root, lock, mode="auto"),
            }
            if args.json:
                print(json.dumps(result, indent=2, sort_keys=True))
            elif found:
                print(found)
            else:
                print("no validated native toolchain installation was found")
                print(
                    "fallback:",
                    result["fallback"]["mode"],
                    result["fallback"]["asset"]["filename"],
                )
            return 1 if args.require and found is None else 0

        if args.command == "plan":
            result = plan_install(
                root,
                lock,
                mode=args.mode,
                prefix=args.prefix,
                jobs=args.jobs,
            )
        else:
            result = install(
                root,
                lock,
                mode=args.mode,
                prefix=args.prefix,
                jobs=args.jobs,
                force=args.force,
                dry_run=args.dry_run,
            )
        _print_plan(result, as_json=args.json)
        return 0
    except (BootstrapError, LockError, OSError, subprocess.CalledProcessError) as exc:
        print(f"NODAL-TOOLCHAIN-BOOTSTRAP: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
