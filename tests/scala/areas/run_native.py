#!/usr/bin/env python3
"""Replay three actual Scala Area snapshots through an explicitly selected native verifier."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--out-parent", type=Path, required=True)
    args = parser.parse_args()
    if not args.nodalc.is_file():
        parser.error("an existing explicitly selected native compiler is required")
    if ".work" not in args.out_parent.resolve().parts:
        parser.error("generated outputs must be under ignored .work")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))
    for name in ("lexical", "external-write", "duplicate-driver"):
        source = args.source_dir / (name + ".mlir")
        if not source.is_file():
            parser.error(f"missing actual Scala replay: {source}")
        command = [str(args.nodalc.resolve()), str(source.resolve()), "--nodal-gate-default",
                   "-o", str(out / (name + ".normalized.mlir"))]
        (out / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=90)
        (out / (name + ".log")).write_text(result.stdout)
        expected = 1 if name == "duplicate-driver" else 0
        if result.returncode != expected:
            raise RuntimeError(f"{name}: expected {expected}, got {result.returncode}\n"
                               + result.stdout)
        if expected and ("NODAL-DIGITAL-055-010" not in result.stdout or
                         "overlapping or unproved-disjoint drivers" not in result.stdout):
            raise RuntimeError("duplicate Area member driver was not rejected by its actual owner")
    sha = hashlib.sha256(args.nodalc.read_bytes()).hexdigest()
    print(f"PASS actual lexical/external-write snapshots and native duplicate-driver rejection; "
          f"nodalc sha256={sha}; logs: {out}")


if __name__ == "__main__":
    main()
