#!/usr/bin/env python3
"""Focused generated-child callback boundary and producer checks with already-built artifacts."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

CASES = {
    "guarded-unused-local": "unused local definition",
    "nested-guard-child-effect": "NODAL-ITERATION-CALLBACK-",
    "nested-guard-helper-effect": "NODAL-ITERATION-CALLBACK-",
    "area-required-refusal": "NODAL-ITERATION-CALLBACK-UNAVAILABLE",
    "helper-required-refusal": "NODAL-ITERATION-CALLBACK-UNAVAILABLE",
    "foreign-instance-refusal": "generated domain binding requires an actual local child instance",
    "helper-binding-refusal": "generated domain binding requires an actual local child instance",
    "child-effect-refusal": "NODAL-ITERATION-CALLBACK-",
    "pure-required-refusal": "NODAL-COLLECTION-CALLBACK-",
    "pure-binding-refusal": "generated domain binding requires an actual local child instance",
    "struct-effectful-argument": "NODAL-ITERATION-CALLBACK-",
    "struct-nested-callback": "NODAL-ITERATION-CALLBACK-",
}
CRASH = re.compile(r"internal compiler error|crash(?:ed)? when compiling|unhandled exception|"
                   r"exception in thread|java\.lang\.(?:AssertionError|StackOverflowError|OutOfMemoryError)", re.I)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--api-classpath", required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if ".work" not in args.output.resolve().parts:
        parser.error("outputs must remain under ignored .work")
    for path in [args.java, args.plugin_jar, *map(Path,
                 (args.compiler_classpath + os.pathsep + args.api_classpath).split(os.pathsep))]:
        if not path.exists():
            parser.error(f"missing already-built input: {path}")
    fixtures = Path(__file__).resolve().parent / "fixtures" / "generated-occurrences"
    results = []
    for name in ("guarded-summary-library", "guarded-summary-consumer"):
        directory = args.output / name
        directory.mkdir(parents=True, exist_ok=True)
        classpath = args.compiler_classpath + os.pathsep + args.api_classpath
        if name.endswith("consumer"):
            classpath += os.pathsep + str(args.output / "guarded-summary-library")
        command = [str(args.java), "-XX:-UsePerfData", "-cp", args.compiler_classpath,
                   "dotty.tools.dotc.Main", "-classpath", classpath,
                   "-d", str(directory), "-color:never", "-Wunused:all", "-Werror",
                   f"-Xplugin:{args.plugin_jar}", "-Xplugin-require:nodal-constructor",
                   str(fixtures / f"{name}.scala")]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=240)
        (directory / "compile.log").write_text(result.stdout)
        (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        good = result.returncode == 0 and not CRASH.search(result.stdout)
        results.append({"case": name, "pass": bool(good), "exit": result.returncode})
        print(name, "PASS" if good else "FAIL", flush=True)
    for name, marker in CASES.items():
        directory = args.output / name
        directory.mkdir(parents=True, exist_ok=True)
        command = [str(args.java), "-XX:-UsePerfData", "-cp", args.compiler_classpath,
                   "dotty.tools.dotc.Main", "-classpath",
                   args.compiler_classpath + os.pathsep + args.api_classpath,
                   "-d", str(directory), "-color:never", "-Wunused:all", "-Werror",
                   f"-Xplugin:{args.plugin_jar}", "-Xplugin-require:nodal-constructor",
                   str(fixtures / f"{name}.scala")]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=240)
        (directory / "compile.log").write_text(result.stdout)
        (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        good = result.returncode == 1 and marker in result.stdout and not CRASH.search(result.stdout)
        results.append({"case": name, "pass": bool(good), "exit": result.returncode})
        print(name, "PASS" if good else "FAIL", flush=True)
    (args.output / "receipt.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"CASES {len(results)} PASS {sum(item['pass'] for item in results)}")
    if not all(item["pass"] for item in results):
        raise SystemExit(1)

if __name__ == "__main__":
    main()
