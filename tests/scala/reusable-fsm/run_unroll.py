#!/usr/bin/env python3
"""Focused finite-unroll frontend/runtime checks using existing pinned artifacts."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--api-classpath", required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--out-parent", type=Path, required=True)
    args = parser.parse_args()
    if ".work" not in args.out_parent.resolve().parts:
        parser.error("generated outputs must stay under .work")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="unroll-", dir=args.out_parent.resolve()))
    fixtures = Path(__file__).resolve().parent / "fixtures/unroll"
    results: list[str] = []

    def run(label: str, command: list[str], expected: int = 0) -> str:
        (out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180)
        (out / f"{label}.log").write_text(result.stdout)
        if result.returncode != expected or re.search(
                r"internal compiler error|exception in thread|StackOverflowError|OutOfMemoryError",
                result.stdout, re.I):
            raise RuntimeError(f"{label}: expected {expected}, got {result.returncode}\n"
                               + result.stdout[-16000:])
        results.append(label)
        return result.stdout

    compiler = [str(args.java), "-XX:-UsePerfData", "-cp", args.compiler_classpath,
                "dotty.tools.dotc.Main", "-color:never", "-deprecation", "-feature",
                "-unchecked", "-Wunused:all", "-Werror"]
    plugin = [f"-Xplugin:{args.plugin_jar.resolve()}", "-Xplugin-require:nodal-constructor",
              "-Ycheck:nodalDigitalCollectionCallbacks"]
    cp = os.pathsep.join([args.api_classpath, args.compiler_classpath])

    def compile_source(label: str, name: str, *, checked: bool = True,
                       dependency: Path | None = None, expected: int = 0) -> tuple[Path, str]:
        directory = out / label
        directory.mkdir()
        classpath = cp if dependency is None else str(dependency) + os.pathsep + cp
        text = run(label, compiler + ["-classpath", classpath, "-d", str(directory)]
                   + (plugin if checked else []) + [str(fixtures / f"{name}.scala")], expected)
        return directory, text

    def execute(label: str, directory: Path, main: str, dependency: Path | None = None) -> None:
        classpath = str(directory) + os.pathsep + cp
        if dependency is not None:
            classpath = str(dependency) + os.pathsep + classpath
        run(label, [str(args.java), "-cp", classpath, main])

    positive, _ = compile_source("positive", "Positive")
    execute("positive-run", positive, "unrollprobe.Positive")
    runtime, _ = compile_source("runtime", "Runtime", checked=False)
    execute("runtime-run", runtime, "nodal.RuntimeProbe")
    producer, _ = compile_source("producer", "Producer")
    archive = out / "producer.jar"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(producer.rglob("*")):
            if path.is_file():
                jar.write(path, path.relative_to(producer))
    consumer, _ = compile_source("consumer", "Consumer", dependency=archive)
    execute("consumer-run", consumer, "unrollconsumer.Consumer", archive)
    negatives = [("BroadConsumer", "SUMMARY"), ("RegNegative", "UNAVAILABLE"),
                 ("MutableNegative", "MUTABLE"), ("NestedNegative", "EFFECT"),
                 ("RecursiveNegative", "CYCLE")]
    for name, code in negatives:
        _, text = compile_source(name, name, dependency=archive, expected=1)
        if f"NODAL-FSM-058-UNROLL-{code}" not in text:
            raise RuntimeError(f"missing {code} rejection for {name}\n{text}")
    _, text = compile_source("missing-plugin", "Positive", checked=False, expected=1)
    if "NODAL-FSM-058-UNROLL-MISSING" not in text:
        raise RuntimeError("unchecked finite-unroll source was not rejected")
    stack, _ = compile_source("stack-positive", "StackPositive")
    execute("stack-positive-run", stack, "stackunrollprobe.StackPositive")
    for name in ("StackCallForeign", "StackTrapForeign"):
        _, text = compile_source(name, name, expected=1)
        if "Type Mismatch Error" not in text:
            raise RuntimeError(f"foreign enum did not fail at the typed boundary: {name}\n{text}")
    (out / "manifest.json").write_text(json.dumps(
        {"status": "passed", "checks": results}, indent=2) + "\n")
    print(f"FINITE_UNROLL_FRONTEND_PASS {len(results)} checks: {out}")


if __name__ == "__main__":
    main()
