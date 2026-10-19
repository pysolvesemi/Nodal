#!/usr/bin/env python3
"""Finite memory callback admission witnesses; use already-built pinned API/plugin artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
CRASH = re.compile(r"internal compiler error|crash(?:ed)? when compiling|unhandled exception|"
                   r"exception in thread|exception while compiling|"
                   r"java\.lang\.(?:AssertionError|StackOverflowError|OutOfMemoryError)", re.I)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--api-classpath", required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--out-parent", type=Path, required=True)
    args = parser.parse_args()
    for entry in (args.compiler_classpath + os.pathsep + args.api_classpath).split(os.pathsep):
        if not Path(entry).exists():
            parser.error(f"missing classpath entry: {entry}")
    if not args.plugin_jar.is_file() or not Path(args.java).is_file():
        parser.error("existing plugin and Java executable required")
    if ".work" not in args.out_parent.resolve().parts:
        parser.error("outputs must stay under ignored .work")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))
    fixtures = HERE / "fixtures"
    manifest = {"plugin_sha256": hashlib.sha256(args.plugin_jar.read_bytes()).hexdigest(),
                "sources": {str(p.relative_to(HERE)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted(fixtures.rglob("*.scala"))}}

    def compile_sources(label: str, sources: list[Path], dependencies: list[Path] | None = None,
                        negative: bool = False) -> tuple[Path, str]:
        target = out / label
        target.mkdir()
        cp = os.pathsep.join([args.compiler_classpath, args.api_classpath]
                             + [str(p) for p in dependencies or []])
        command = [args.java, "-XX:-UsePerfData", "-Xmx2g", "-cp", args.compiler_classpath,
                   "dotty.tools.dotc.Main", "-classpath", cp, "-d", str(target), "-color:never",
                   "-deprecation", "-feature", "-unchecked", "-Wunused:all", "-Werror",
                   f"-Xplugin:{args.plugin_jar.resolve()}", "-Xplugin-require:nodal-constructor"]
        if not negative:
            command += ["-Ycheck:nodalDigitalCollectionCallbacks"]
        command += list(map(str, sources))
        (out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=240)
        (out / f"{label}.log").write_text(result.stdout)
        if result.returncode != (1 if negative else 0) or CRASH.search(result.stdout):
            raise RuntimeError(f"{label}: unexpected exit {result.returncode}\n{result.stdout}")
        return target, result.stdout

    compile_sources("positive", sorted((fixtures / "positive").glob("*.scala")))
    producer, _ = compile_sources("producer", sorted((fixtures / "producer").glob("*.scala")))
    compile_sources("consumer", sorted((fixtures / "consumer").glob("*.scala")), [producer])
    # Kernel-forgery diagnostics run before ordinary callback checks, so isolate those stages.
    negatives = sorted((fixtures / "negative").glob("*.scala"))
    for name, sources in [("negative", [p for p in negatives if not p.stem.startswith("Forged")]),
                          ("forged", [p for p in negatives if p.stem.startswith("Forged")])]:
        _, text = compile_sources(name, sources, [producer], negative=True)
        blocks = re.split(r"(?=^--(?: \[[^\]]+\])? .*Error: )", text, flags=re.M)
        for source in sources:
            expected = re.search(r"// Expected: (NODAL-[A-Z0-9-]+)", source.read_text())
            if expected is None or not any(f"/{source.name}:" in block and expected[1] + ":" in block
                                           for block in blocks):
                raise RuntimeError(f"missing source-located diagnostic for {source.name}\n{text}")
    manifest["status"] = "passed"
    manifest["negative_cases"] = len(negatives)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Memory callbacks: positives and separate helper passed; {len(negatives)} negatives rejected")
    print(out)


if __name__ == "__main__":
    main()
