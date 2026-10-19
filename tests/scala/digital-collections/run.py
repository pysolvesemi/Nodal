#!/usr/bin/env python3
"""Focused typed-callback compile/runtime witnesses using the existing Scala toolchain.

Supply already-built API/plugin artifacts and the repository's pinned compiler classpath.
This runner never downloads tools, invokes Mill/CI, or modifies a build output directory.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
PREFIX = "NODAL-COLLECTION-CALLBACK-"
CRASH = re.compile(
    r"internal compiler error|crash(?:ed)? when compiling|unhandled exception|"
    r"exception in thread|exception while compiling|"
    r"java\.lang\.(?:AssertionError|StackOverflowError|OutOfMemoryError)", re.I,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--api-classpath", required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--out-parent", type=Path, required=True,
                        help="ignored .work directory for outputs and logs")
    args = parser.parse_args()
    for entry in (args.compiler_classpath + os.pathsep + args.api_classpath).split(os.pathsep):
        if not Path(entry).exists():
            parser.error(f"classpath entry does not exist: {entry}")
    if not args.plugin_jar.is_file() or not args.java:
        parser.error("an existing plugin jar and Java executable are required")
    if ".work" not in args.out_parent.resolve().parts:
        parser.error("generated output must be inside the repository's ignored .work directory")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))

    def run(label: str, command: list[str], expected: int = 0) -> str:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=240)
        (out / f"{label}.log").write_text(result.stdout)
        if result.returncode != expected or CRASH.search(result.stdout):
            raise RuntimeError(f"{label}: expected exit {expected}, got {result.returncode}\n"
                               + result.stdout[-12000:])
        return result.stdout

    version = run("compiler-version", [args.java, "-cp", args.compiler_classpath,
                                      "dotty.tools.dotc.Main", "-version"])
    if not re.search(r"version\s+3\.8\.4(?:\s|$)", version):
        raise RuntimeError("existing compiler must be the pinned Scala 3.8.4")

    def compile_sources(label: str, sources: list[Path], *, plugin: bool = True,
                        dependencies: list[Path] | None = None, negative: bool = False,
                        check_trees: bool = False) -> tuple[Path, str]:
        target = out / label
        target.mkdir()
        classpath = os.pathsep.join([args.compiler_classpath, args.api_classpath]
                                   + [str(path) for path in dependencies or []])
        command = [args.java, "-cp", args.compiler_classpath, "dotty.tools.dotc.Main",
                   "-classpath", classpath, "-d", str(target), "-color:never",
                   "-deprecation", "-feature", "-unchecked", "-Wunused:all", "-Werror"]
        if plugin:
            command += [f"-Xplugin:{args.plugin_jar.resolve()}", "-Xplugin-require:nodal-constructor"]
        if check_trees:
            command += ["-Ycheck:all"]
        output = run(label, command + [str(source) for source in sources], 1 if negative else 0)
        return target, output

    separate, _ = compile_sources("separate", list((HERE / "fixtures/separate").glob("*.scala")),
                                  plugin=False)
    compile_sources("positive", sorted((HERE / "fixtures/positive").glob("*.scala")),
                    check_trees=True)
    negative_sources = sorted((HERE / "fixtures/negative").glob("*.scala"))
    negative_callers = sorted((HERE / "fixtures/negative-callers").glob("*.scala"))
    _, diagnostic_output = compile_sources("negative", negative_sources + negative_callers,
                                          dependencies=[separate], negative=True)
    # Every negative must receive its own source-located contract diagnostic. A single unrelated
    # error, a crash, or merely failing compilation cannot make the other witnesses pass.
    diagnostics = re.split(r"(?=^--(?: \[[^\]]+\])? .*Error: )", diagnostic_output, flags=re.M)
    for source in negative_sources:
        expected = re.search(r"// Expected: (NODAL-COLLECTION-CALLBACK-[A-Z]+)", source.read_text())
        if expected is None:
            raise RuntimeError(f"negative fixture lacks a diagnostic expectation: {source}")
        if not any(f"/{source.name}:" in block and f"{expected[1]}:" in block
                   for block in diagnostics):
            raise RuntimeError(f"missing source-located {expected[1]} for {source.name}\n"
                               + diagnostic_output)

    # An adversarially deep helper graph must fail at the explicit frontend bound, not recurse
    # until a JVM/compiler failure. Generated test source stays in the ignored output directory.
    bounded = out / "BoundedGraph.scala"
    methods = [f"  private def step{i}(x: Expr[UInt]): Expr[UInt] = step{i + 1}(x)"
               for i in range(160)]
    bounded.write_text("package nodal.callback.fixture\nimport nodal.*\nobject BoundedGraph:\n"
                       + "\n".join(methods)
                       + "\n  private def step160(x: Expr[UInt]): Expr[UInt] = x\n"
                       + "  def test(xs: Expr[Vec[UInt]]) = xs.map(step0)\n")
    _, limit_output = compile_sources("bounded", [bounded], negative=True)
    if PREFIX + "LIMIT:" not in limit_output:
        raise RuntimeError("bounded graph did not report the explicit callback traversal limit")

    uninstrumented, _ = compile_sources(
        "uninstrumented", list((HERE / "fixtures/uninstrumented").glob("*.scala")), plugin=False)
    runtime, _ = compile_sources("runtime", list((HERE / "fixtures/runtime").glob("*.scala")),
                                 dependencies=[uninstrumented], check_trees=True)
    runtime_cp = os.pathsep.join([args.compiler_classpath, args.api_classpath,
                                 str(uninstrumented), str(runtime)])
    result = run("runtime-probe", [args.java, "-cp", runtime_cp,
                                   "nodal.callback.fixture.RuntimeProbe"])
    if "DIGITAL_COLLECTION_CALLBACK_PASS checked-public-source uninstrumented-rejection" not in result:
        raise RuntimeError("public-path runtime witness did not publish its success sentinel")
    print(f"PASS typed positives, {len(negative_sources)} source-located negatives, bounded traversal, "
          f"public runtime path, and uninstrumented rejection; logs: {out}")


if __name__ == "__main__":
    main()
