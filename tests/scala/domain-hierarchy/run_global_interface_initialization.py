#!/usr/bin/env python3
"""Check exact global Interface initializer ownership with already-built source artifacts."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

CRASH = re.compile(r"internal compiler error|crash(?:ed)? when compiling|unhandled exception|exception in thread|java\.lang\.(?:AssertionError|StackOverflowError|OutOfMemoryError)", re.I)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--api-classpath", required=True, help="Already-built API, bridge and bridge dependencies")
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if ".work" not in args.output.resolve().parts:
        parser.error("outputs must remain under ignored .work")
    fixtures = Path(__file__).resolve().parent / "fixtures" / "global-interface-initialization"
    inputs = [*sorted(fixtures.glob("*.scala")), args.plugin_jar, *map(Path, args.api_classpath.split(os.pathsep))]
    if any(not path.exists() for path in inputs + [args.java]):
        parser.error("missing already-built input")
    args.output.mkdir(parents=True, exist_ok=True)
    def manifest():
        return {str(path.resolve()): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs if path.is_file()}
    before = manifest()
    (args.output / "before.json").write_text(json.dumps(before, indent=2) + "\n")
    cases = [
        ("producer", ["Producer.scala"], None),
        ("consumer", ["Consumer.scala", "RuntimeChecks.scala", "Replay.scala"], None),
        ("effectful", ["Effectful.scala"], "NODAL-ITERATION-CALLBACK-UNAVAILABLE"),
        ("forged", ["Forged.scala"], "NODAL-COLLECTION-CALLBACK-FORGED"),
        ("forged-kernel", ["ForgedKernel.scala"], "NODAL-COLLECTION-CALLBACK-FORGED"),
    ]
    results = []
    base = args.api_classpath + os.pathsep + args.compiler_classpath
    for name, sources, marker in cases:
        out = args.output / name
        out.mkdir(exist_ok=True)
        classpath = base + (os.pathsep + str(args.output / "producer") if name != "producer" else "")
        command = [str(args.java), "-XX:-UsePerfData", "-cp", args.compiler_classpath, "dotty.tools.dotc.Main", "-classpath", classpath, "-d", str(out), "-color:never", "-Wunused:all", "-Werror", "-Ycheck:all", f"-Xplugin:{args.plugin_jar}", "-Xplugin-require:nodal-constructor", *[str(fixtures / source) for source in sources]]
        run = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
        (out / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        (out / "compile.log").write_text(run.stdout)
        good = not CRASH.search(run.stdout) and ((run.returncode == 0) if marker is None else (run.returncode != 0 and marker in run.stdout))
        results.append({"stage": name, "pass": bool(good), "exit": run.returncode})
        print(name, "PASS" if good else "FAIL", flush=True)
        if not good:
            (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
            raise SystemExit(1)
    command = [str(args.java), "-XX:-UsePerfData", "-cp", os.pathsep.join([str(args.output / "consumer"), str(args.output / "producer"), base]), "nodal.internal.testkit.GlobalInitializationReplay"]
    run = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    (args.output / "replay-command.json").write_text(json.dumps(command, indent=2) + "\n")
    (args.output / "replay.log").write_text(run.stdout)
    count = sum(line.startswith("PASS ") for line in run.stdout.splitlines())
    good = run.returncode == 0 and not CRASH.search(run.stdout) and count == 8
    results.append({"stage": "replay", "pass": bool(good), "exit": run.returncode, "outcomes": count})
    print("replay", "PASS" if good else "FAIL", count, flush=True)
    after = manifest()
    (args.output / "after.json").write_text(json.dumps(after, indent=2) + "\n")
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    if not good or before != after:
        raise SystemExit(1)

if __name__ == "__main__":
    main()
