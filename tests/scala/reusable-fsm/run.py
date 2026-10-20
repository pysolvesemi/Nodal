#!/usr/bin/env python3
"""Focused immutable-FSM frontend and separate-library checks using existing pinned artifacts."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent


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
    for path in (args.compiler_classpath + os.pathsep + args.api_classpath).split(os.pathsep):
        if not Path(path).exists():
            parser.error(f"classpath entry does not exist: {path}")
    if not args.plugin_jar.is_file() or not args.java.is_file():
        parser.error("existing pinned Java and plugin artifacts are required")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))
    fixtures = HERE / "fixtures"
    results: list[str] = []

    def run(label: str, command: list[str], expected: int) -> str:
        (out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180)
        (out / f"{label}.log").write_text(result.stdout)
        if result.returncode != expected or re.search(
                r"internal compiler error|exception in thread|StackOverflowError|OutOfMemoryError",
                result.stdout, re.I):
            raise RuntimeError(f"{label}: expected {expected}, got {result.returncode}\n"
                               + result.stdout[-16000:])
        return result.stdout

    version = run("version", [str(args.java), "-cp", args.compiler_classpath,
                              "dotty.tools.dotc.Main", "-version"], 0)
    if not re.search(r"version\s+3\.8\.4(?:\s|$)", version):
        raise RuntimeError("Scala 3.8.4 is required")

    def compile_source(label: str, source: Path, *, plugin: bool = True,
                       dependencies: list[Path] | None = None, expected: int = 0) -> tuple[Path, str]:
        target = out / label
        target.mkdir()
        classpath = os.pathsep.join([args.compiler_classpath, args.api_classpath]
                                   + [str(path) for path in dependencies or []])
        command = [str(args.java), "-XX:-UsePerfData", "-cp", args.compiler_classpath,
                   "dotty.tools.dotc.Main", "-classpath", classpath, "-d", str(target),
                   "-color:never", "-deprecation", "-feature", "-unchecked", "-Wunused:all", "-Werror"]
        if plugin:
            command += [f"-Xplugin:{args.plugin_jar.resolve()}", "-Xplugin-require:nodal-constructor"]
        if plugin and expected == 0:
            command += ["-Ycheck:nodalDigitalCollectionCallbacks"]
        output = run(label, command + [str(source)], expected)
        results.append(label)
        return target, output

    def pack(label: str, directory: Path) -> Path:
        archive = out / (label + ".jar")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as jar:
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    jar.write(path, path.relative_to(directory))
        return archive

    producer, _ = compile_source("producer", fixtures / "producer/Library.scala")
    producer_jar = pack("producer", producer)
    consumer, _ = compile_source("consumer", fixtures / "consumer/Consumer.scala",
                                 dependencies=[producer_jar])
    bytecode = run("consumer-bytecode", [str(args.java.with_name("javap")), "-classpath", str(consumer),
                                        "-c", "-p", "reusable.fixture.Consumer$"], 0)
    if "FsmDefinitionRuntime$.actionBinding" not in bytecode or "FsmAction.bind:" in bytecode:
        raise RuntimeError("action binding did not lower to the checked runtime adapter")
    for source in sorted((fixtures / "negative").glob("*.scala")):
        _, output = compile_source(source.stem, source, expected=1)
        expected = re.search(r"// Expected: (.+)", source.read_text())[1]
        if expected == "type mismatch":
            if "Type Mismatch Error" not in output:
                raise RuntimeError(f"missing type rejection for {source}\n{output}")
        elif expected + ":" not in output or f"/{source.name}:" not in output:
            raise RuntimeError(f"missing source-located {expected}\n{output}")
    _, output = compile_source("no-plugin", fixtures / "no-plugin/Marker.scala",
                                plugin=False, expected=1)
    if "NODAL-SEQUENTIAL-056-MISSING:" not in output:
        raise RuntimeError("missing-plugin action binding was not rejected")
    replacement, _ = compile_source("producer-uninstrumented", fixtures / "producer/Library.scala",
                                    plugin=False)
    replacement_jar = pack("producer-uninstrumented", replacement)
    _, output = compile_source("consumer-uninstrumented", fixtures / "consumer/Consumer.scala",
                                dependencies=[replacement_jar], expected=1)
    if "NODAL-SEQUENTIAL-056-UNAVAILABLE:" not in output:
        raise RuntimeError("unchecked separate-library action helper was not rejected")
    (out / "manifest.json").write_text(json.dumps(
        {"status": "passed", "checks": results}, indent=2) + "\n")
    print(f"REUSABLE_FSM_FRONTEND_PASS {len(results)} checks: {out}")


if __name__ == "__main__":
    main()
