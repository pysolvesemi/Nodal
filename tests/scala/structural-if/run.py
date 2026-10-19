#!/usr/bin/env python3
"""Focused structural-if typing/effect witnesses with already-built pinned artifacts.

No tool download, CI, broad qualification, publication, or build-output mutation.
"""
from __future__ import annotations

import argparse
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
            parser.error(f"classpath entry does not exist: {entry}")
    if not args.plugin_jar.is_file() or not Path(args.java).is_file():
        parser.error("an existing plugin jar and Java executable are required")
    if ".work" not in args.out_parent.resolve().parts:
        parser.error("generated outputs must be under the ignored .work directory")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))
    fixtures = HERE / "fixtures"

    def run(label: str, command: list[str], expected: int = 0) -> str:
        (out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=240)
        (out / f"{label}.log").write_text(result.stdout)
        if result.returncode != expected or CRASH.search(result.stdout):
            raise RuntimeError(f"{label}: expected exit {expected}, got {result.returncode}\n"
                               + result.stdout[-16000:])
        return result.stdout

    version = run("compiler-version", [args.java, "-cp", args.compiler_classpath,
                                      "dotty.tools.dotc.Main", "-version"])
    if not re.search(r"version\s+3\.8\.4(?:\s|$)", version):
        raise RuntimeError("the compiler must be pinned Scala 3.8.4")

    def compile_sources(label: str, sources: list[Path], *, plugin: bool = True,
                        dependencies: list[Path] | None = None,
                        negative: bool = False) -> tuple[Path, str]:
        target = out / label
        target.mkdir()
        classpath = os.pathsep.join([args.compiler_classpath, args.api_classpath]
                                   + [str(path) for path in dependencies or []])
        command = [args.java, "-XX:-UsePerfData", "-cp", args.compiler_classpath,
                   "dotty.tools.dotc.Main", "-classpath", classpath,
                   "-d", str(target), "-color:never", "-deprecation", "-feature",
                   "-unchecked", "-Wunused:all", "-Werror"]
        if plugin:
            command += [f"-Xplugin:{args.plugin_jar.resolve()}", "-Xplugin-require:nodal-constructor"]
        if not negative:
            command += ["-Ycheck:nodalDigitalCollectionCallbacks"] if plugin else []
        return target, run(label, command + list(map(str, sources)), 1 if negative else 0)

    positive, _ = compile_sources("positive", sorted((fixtures / "positive").glob("*.scala")))
    bytecode = run("positive-bytecode", [str(Path(args.java).with_name("javap")),
                                          "-classpath", str(positive), "-c", "-p", "StructuralPositive"])
    if re.search(r"BoxesRunTime\.equals|Expr\.\$eq\$eq|Expr\.\$bang\$eq|Param\.default", bytecode):
        raise RuntimeError("captured structural condition retained host equality/default evaluation")
    expected = {
        "Aliases": ["NODAL-STRUCTURAL-055-MISSING"],
        "AnalogStructuralContext": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "Outside": ["NODAL-STRUCTURAL-055-MISSING"],
        "MixedHost": ["NODAL-STRUCTURAL-055-CONDITION"],
        "CapabilityJoin": ["NODAL-STRUCTURAL-055-001"],
        "Effects": ["NODAL-STRUCTURAL-055-UNAVAILABLE", "NODAL-STRUCTURAL-055-MUTABLE",
                    "NODAL-STRUCTURAL-055-CYCLE", "must never initialize", "must never execute",
                    "state += 1", "callback cannot read or write mutable or lazy Scala state"],
        "EvidenceEffect": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "PredicateEffect": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "PredicateWrite": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "EvidenceWrite": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "BranchAllocation": ["NODAL-STRUCTURAL-055-UNAVAILABLE"],
        "Forged": ["NODAL-STRUCTURAL-055-FORGED"],
        "NonInteger": ["<:< nodal.Integer"],
        "Reverse": ["cannot be compared", "overloaded alternatives of method <"],
    }
    for name, diagnostics in expected.items():
        source = fixtures / "negative" / (name + ".scala")
        _, output = compile_sources("negative-" + name, [source], negative=True)
        if f"/{source.name}:" not in output or any(code not in output for code in diagnostics):
            raise RuntimeError(f"missing source-located expected diagnostic for {name}\n{output}")
        minimum_counts = {"CapabilityJoin": ("NODAL-STRUCTURAL-055-001", 2),
                          "Aliases": ("NODAL-STRUCTURAL-055-MISSING", 2),
                          "Forged": ("NODAL-STRUCTURAL-055-FORGED", 3)}
        if name in minimum_counts:
            code, count = minimum_counts[name]
            if output.count(code) < count:
                raise RuntimeError(f"not every authored negative in {name} was rejected\n{output}")

    bounded = out / "BoundedStructural.scala"
    steps = [f"  private def step{i}(x: Expr[Bits]): Expr[Bits] = step{i + 1}(x)"
             for i in range(160)]
    bounded.write_text("import nodal.*\nfinal class BoundedStructural extends Module:\n"
                       "  val MODE = param(0.integer, 0 to 1)\n"
                       "  val input = in(Bits(8))\n" + "\n".join(steps)
                       + "\n  private def step160(x: Expr[Bits]): Expr[Bits] = ~x\n"
                       "  val selected = if MODE == 0 then step0(input) else ~input\n")
    _, output = compile_sources("bounded", [bounded], negative=True)
    if "NODAL-STRUCTURAL-055-LIMIT" not in output:
        raise RuntimeError("deep helper graph did not report the shared bounded-validator limit")

    base, _ = compile_sources("base", [fixtures / "base/Base.scala"])
    producer, _ = compile_sources("producer", [fixtures / "producer/Producer.scala"],
                                   dependencies=[base])
    consumer = [fixtures / "consumer/Consumer.scala"]
    compile_sources("consumer", consumer, dependencies=[base, producer])
    producer_jar = out / "producer.jar"
    run("producer-jar", [str(Path(args.java).with_name("jar")), "cf", str(producer_jar),
                         "-C", str(producer), "."])
    _, output = compile_sources("predicate-consumer-write",
                                 [fixtures / "predicate-consumer/PredicateConsumer.scala"],
                                 dependencies=[base, producer_jar], negative=True)
    if "/PredicateConsumer.scala:" not in output or "NODAL-STRUCTURAL-055-SUMMARY" not in output:
        raise RuntimeError("predicate accepted a separately attested write helper\n" + output)
    # Reuse the actual checked producer, with its transitive proof dependencies, against
    # same-signature replacements. Missing or genuinely impure helper proofs must fail closed.
    for name, source, plugin in [
        ("missing", "base/Base.scala", False),
        ("impure", "base-impure/Base.scala", True),
    ]:
        replacement, _ = compile_sources("base-" + name, [fixtures / source], plugin=plugin)
        _, output = compile_sources("consumer-" + name, consumer,
                                     dependencies=[replacement, producer], negative=True)
        if "/Consumer.scala:" not in output or "NODAL-STRUCTURAL-055-UNAVAILABLE" not in output:
            raise RuntimeError("consumer accepted an unavailable transitive helper proof\n" + output)

    compile_sources("legacy-no-plugin", [fixtures / "no-plugin/Legacy.scala"], plugin=False)
    _, output = compile_sources("marker-no-plugin", [fixtures / "no-plugin/Marker.scala"],
                                 plugin=False, negative=True)
    if "NODAL-STRUCTURAL-055-MISSING" not in output:
        raise RuntimeError("the new Int-RHS marker did not fail closed without the plugin")
    host, _ = compile_sources("host", [fixtures / "runtime/HostControls.scala"])
    output = run("host-runtime", [args.java, "-cp", os.pathsep.join([
        args.compiler_classpath, args.api_classpath, str(host)]), "HostControls"])
    if "STRUCTURAL_HOST_CONTROL_PASS" not in output:
        raise RuntimeError("ordinary host control/reference equality witness did not complete")
    print(f"PASS structural source positives, {len(expected)} negative fixtures, separate-helper "
          f"proofs and substitutions, no-plugin boundary, host runtime; logs: {out}")


if __name__ == "__main__":
    main()
