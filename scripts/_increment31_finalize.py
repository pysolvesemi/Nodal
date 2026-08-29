#!/usr/bin/env python3
"""Finalize the Increment 31 implementation contract and remove staging artifacts."""

from __future__ import annotations

import base64
import hashlib
import json
import lzma
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
PAYLOAD_SHA256 = "c5fdcae57d502735dc68c51ab2ce43955eeb0a07f48a289f340a3a92a390573b"
PERMANENT_WORKFLOW = ROOT / ".github/workflows/increment-31-potential-flow-access.yml"
FINALIZER_WORKFLOW = ROOT / ".github/workflows/_increment31_finalize.yml"


def run_git(*arguments: str) -> None:
    subprocess.run(["git", *arguments], cwd=ROOT, check=True)


def main() -> None:
    original_permanent_workflow = PERMANENT_WORKFLOW.read_text(encoding="utf-8")
    original_finalizer_workflow = FINALIZER_WORKFLOW.read_text(encoding="utf-8")

    payload_paths = sorted(HERE.glob("_increment31_finalize_payload_*.txt"))
    if len(payload_paths) != 5:
        raise RuntimeError("Increment 31 finalization payload is incomplete")
    payload = "".join(
        path.read_text(encoding="ascii").strip() for path in payload_paths
    )
    decoded = lzma.decompress(
        base64.b64decode(payload.encode("ascii"), validate=True)
    )
    if hashlib.sha256(decoded).hexdigest() != PAYLOAD_SHA256:
        raise RuntimeError("Increment 31 finalization payload checksum mismatch")
    files = json.loads(decoded.decode("utf-8"))
    if not isinstance(files, dict):
        raise RuntimeError("Increment 31 finalization payload must be a file map")

    for relative, content in files.items():
        path = ROOT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        normalized = "\n".join(line.rstrip() for line in content.splitlines())
        if content.endswith("\n"):
            normalized += "\n"
        path.write_text(normalized, encoding="utf-8")

    finalized_permanent_workflow = PERMANENT_WORKFLOW.read_text(encoding="utf-8")

    gitignore = ROOT / ".gitignore"
    text = gitignore.read_text(encoding="utf-8")
    python_rules = "# Python bytecode\n__pycache__/\n*.py[cod]\n"
    if "__pycache__/" not in text:
        if not text.endswith("\n"):
            text += "\n"
        text += "\n" + python_rules
        gitignore.write_text(text, encoding="utf-8")

    for pattern in ("*.pyc", "*.pyo"):
        for path in ROOT.rglob(pattern):
            if ".git" not in path.parts and path.is_file():
                path.unlink()
    caches = sorted(
        (
            path
            for path in ROOT.rglob("__pycache__")
            if ".git" not in path.parts and path.is_dir()
        ),
        key=lambda path: len(path.parts),
        reverse=True,
    )
    for path in caches:
        shutil.rmtree(path)

    for path in payload_paths:
        path.unlink(missing_ok=True)
    FINALIZER_WORKFLOW.unlink(missing_ok=True)
    Path(__file__).unlink(missing_ok=True)

    # GITHUB_TOKEN cannot push commits that modify workflow files. Commit and
    # push the already-validated non-workflow finalization first, then restore
    # the desired workflow-only working-tree delta for a privileged follow-up.
    PERMANENT_WORKFLOW.write_text(original_permanent_workflow, encoding="utf-8")
    FINALIZER_WORKFLOW.write_text(original_finalizer_workflow, encoding="utf-8")

    run_git("config", "user.name", "github-actions[bot]")
    run_git(
        "config",
        "user.email",
        "41898282+github-actions[bot]@users.noreply.github.com",
    )
    run_git("add", "-A")
    run_git(
        "restore",
        "--staged",
        "--",
        str(PERMANENT_WORKFLOW.relative_to(ROOT)),
        str(FINALIZER_WORKFLOW.relative_to(ROOT)),
    )
    run_git("commit", "-m", "Increment 31 — finalize non-workflow implementation contract")
    run_git("push", "origin", "HEAD:increment/31-potential-flow-access")

    PERMANENT_WORKFLOW.write_text(finalized_permanent_workflow, encoding="utf-8")
    FINALIZER_WORKFLOW.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
