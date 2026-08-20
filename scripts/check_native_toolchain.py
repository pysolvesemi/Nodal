#!/usr/bin/env python3
"""Validate Nodal's immutable LLVM/MLIR/CIRCT toolchain lock."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from toolchain_lock import Problem, load_lock, validate_lock


def _request(url: str, *, accept: str = "application/vnd.github+json") -> bytes:
    headers = {
        "Accept": accept,
        "User-Agent": "Nodal-native-toolchain-lock/1",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def _request_json(url: str) -> dict[str, Any]:
    value = json.loads(_request(url).decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object from {url}")
    return value


def validate_online(lock: dict[str, Any]) -> list[Problem]:
    problems: list[Problem] = []
    circt = lock["native"]["circt"]
    llvm = lock["native"]["llvm"]

    try:
        release_commit = _request_json(
            f"https://api.github.com/repos/llvm/circt/commits/{circt['release_tag']}"
        )
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError) as exc:
        problems.append(
            Problem("NODAL-TOOLCHAIN-101", f"cannot resolve CIRCT release tag: {exc}")
        )
    else:
        if release_commit.get("sha") != circt["commit"]:
            problems.append(
                Problem(
                    "NODAL-TOOLCHAIN-102",
                    "CIRCT release tag does not resolve to the locked commit",
                )
            )

    try:
        submodule = _request_json(
            "https://api.github.com/repos/llvm/circt/contents/llvm"
            f"?ref={circt['commit']}"
        )
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError) as exc:
        problems.append(
            Problem("NODAL-TOOLCHAIN-103", f"cannot resolve CIRCT LLVM submodule: {exc}")
        )
    else:
        if submodule.get("type") != "submodule":
            problems.append(
                Problem("NODAL-TOOLCHAIN-104", "CIRCT llvm entry is not reported as a submodule")
            )
        if submodule.get("sha") != llvm["commit"]:
            problems.append(
                Problem(
                    "NODAL-TOOLCHAIN-105",
                    "CIRCT's remote LLVM submodule does not match the locked LLVM commit",
                )
            )

    try:
        llvm_commit = _request_json(
            f"https://api.github.com/repos/llvm/llvm-project/commits/{llvm['commit']}"
        )
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError) as exc:
        problems.append(
            Problem("NODAL-TOOLCHAIN-106", f"cannot resolve locked LLVM commit: {exc}")
        )
    else:
        if llvm_commit.get("sha") != llvm["commit"]:
            problems.append(
                Problem("NODAL-TOOLCHAIN-107", "locked LLVM commit did not resolve exactly")
            )

    for asset in lock["assets"]:
        checksum_url = f"{asset['url']}.sha256"
        try:
            payload = _request(
                checksum_url,
                accept="application/octet-stream",
            ).decode("utf-8", errors="strict")
        except (OSError, urllib.error.URLError, UnicodeDecodeError) as exc:
            problems.append(
                Problem(
                    "NODAL-TOOLCHAIN-108",
                    f"cannot retrieve upstream checksum for {asset['filename']}: {exc}",
                )
            )
            continue
        fields = payload.strip().split()
        if not fields or fields[0].lower() != asset["sha256"]:
            problems.append(
                Problem(
                    "NODAL-TOOLCHAIN-109",
                    f"upstream checksum disagrees for {asset['filename']}",
                )
            )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root",
    )
    parser.add_argument(
        "--online",
        action="store_true",
        help="also verify the release tag, submodule pointer, commits, and upstream checksums",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable results")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    problems = validate_lock(root)
    lock: dict[str, Any] | None = None
    if not problems:
        try:
            lock = load_lock(root)
        except RuntimeError as exc:
            problems.append(Problem("NODAL-TOOLCHAIN-001", str(exc)))
    if args.online and lock is not None:
        problems.extend(validate_online(lock))

    if args.json:
        print(
            json.dumps(
                {
                    "ok": not problems,
                    "online": args.online,
                    "problems": [
                        {"code": problem.code, "message": problem.message}
                        for problem in problems
                    ],
                },
                indent=2,
                sort_keys=True,
            )
        )
    else:
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            print(
                f"native toolchain lock check failed with {len(problems)} problem(s)",
                file=sys.stderr,
            )
        else:
            mode = "offline + online" if args.online else "offline"
            print(f"native toolchain lock check passed ({mode})")

    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
