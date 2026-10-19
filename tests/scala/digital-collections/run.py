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
import zipfile

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
    parser.add_argument("--profile", choices=("collections", "iterations"), default="collections",
                        help="reuse the typed callback harness for bounded iteration fixtures")
    args = parser.parse_args()
    iteration = args.profile == "iterations"
    fixtures = HERE.parent / "digital-iterations/fixtures" if iteration else HERE / "fixtures"
    prefix = "NODAL-ITERATION-CALLBACK-" if iteration else PREFIX
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

    def pack(label: str, directory: Path) -> Path:
        archive = out / (label + ".jar")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as jar:
            for entry in sorted(directory.rglob("*")):
                if entry.is_file():
                    jar.write(entry, entry.relative_to(directory))
        return archive

    separate, _ = compile_sources("separate", list((fixtures / "separate").glob("*.scala")),
                                  plugin=iteration, check_trees=iteration)
    dependencies = [separate]
    if iteration:
        separate = pack("definitions", separate)
        factory, _ = compile_sources("factory", sorted((fixtures / "factory").glob("*.scala")),
                                      dependencies=[separate], check_trees=True)
        factory = pack("factory", factory)
        raw, _ = compile_sources("raw-metadata", sorted((fixtures / "raw-metadata").glob("*.scala")),
                                  plugin=False)
        dependencies = [separate, factory, pack("raw-metadata", raw)]
    positive, _ = compile_sources("positive", sorted((fixtures / "positive").glob("*.scala")),
                                  dependencies=dependencies if iteration else None, check_trees=True)
    if iteration:
        pack("consumer", positive)
    negative_sources = sorted((fixtures / "negative").glob("*.scala"))
    negative_callers = sorted((fixtures / "negative-callers").glob("*.scala"))
    _, diagnostic_output = compile_sources("negative", negative_sources + negative_callers,
                                          dependencies=dependencies, negative=True)
    # Every negative must receive its own source-located contract diagnostic. A single unrelated
    # error, a crash, or merely failing compilation cannot make the other witnesses pass.
    diagnostics = re.split(r"(?=^--(?: \[[^\]]+\])? .*Error: )", diagnostic_output, flags=re.M)
    for source in negative_sources:
        expected = re.search(r"// Expected: (NODAL-[A-Z-]+)", source.read_text())
        if expected is None:
            raise RuntimeError(f"negative fixture lacks a diagnostic expectation: {source}")
        detail = re.search(r"// Expected detail: (.+)", source.read_text())
        if not any(f"/{source.name}:" in block and f"{expected[1]}:" in block
                   and (detail is None or detail[1] in block) for block in diagnostics):
            raise RuntimeError(f"missing source-located {expected[1]} for {source.name}\n"
                               + diagnostic_output)

    # Reuse the already compiled safe factory against different, same-signature definitions.
    # The consumer must revalidate dependency proofs, without recompiling the factory or seeing
    # producer source. One replacement retains only the old allocation ABI; the other was
    # genuinely compiled with the plugin but its effectful constructor received no summary.
    if iteration:
        for replacement in ("missing", "impure"):
            replaced, _ = compile_sources("dependency-" + replacement,
                sorted((fixtures / ("dependency-" + replacement)).glob("*.scala")),
                plugin=replacement == "impure", check_trees=True)
            replaced = pack("dependency-" + replacement, replaced)
            source = fixtures / "dependency-negative/SubstitutedDependency.scala"
            _, output = compile_sources("dependency-consumer-" + replacement, [source],
                                         dependencies=[replaced, factory], negative=True)
            if f"/{source.name}:" not in output or "NODAL-ITERATION-CALLBACK-UNAVAILABLE:" not in output:
                raise RuntimeError("substituted constructor dependency did not fail at the callback boundary\n"
                                   + output)

    # Metadata rejection runs in the earlier producer phase. Keep those diagnostics isolated
    # so they cannot prevent the callback phase from checking each ordinary negative fixture.
    metadata_sources = sorted((fixtures / "metadata-negative").glob("*.scala"))
    for source in metadata_sources:
        expected = re.search(r"// Expected: (NODAL-[A-Z-]+)", source.read_text())
        if expected is None:
            raise RuntimeError(f"metadata fixture lacks a diagnostic expectation: {source}")
        _, output = compile_sources("metadata-" + source.stem, [source],
                                     dependencies=dependencies, negative=True)
        if f"/{source.name}:" not in output or expected[1] + ":" not in output:
            raise RuntimeError(f"missing source-located metadata diagnostic for {source.name}\n"
                               + output)

    # Source capability privacy is checked separately so an unrelated callback failure cannot
    # hide an accidentally public constructor or writable computed expression.
    capability_sources = sorted((fixtures / "capability-negative").glob("*.scala"))
    for source in capability_sources:
        expected = re.search(r"// Expected detail: (.+)", source.read_text())
        if expected is None:
            raise RuntimeError(f"capability fixture lacks a diagnostic expectation: {source}")
        _, output = compile_sources("capability-" + source.stem, [source], negative=True)
        if f"/{source.name}:" not in output or expected[1] not in output:
            raise RuntimeError(f"missing source-located capability diagnostic for {source.name}\n"
                               + output)

    # An adversarially deep helper graph must fail at the explicit frontend bound, not recurse
    # until a JVM/compiler failure. Generated test source stays in the ignored output directory.
    bounded = out / "BoundedGraph.scala"
    methods = [f"  private def step{i}(x: Expr[UInt]): Expr[UInt] = step{i + 1}(x)"
               for i in range(160)]
    if iteration:
        methods = [f"  private def step{i}(x: LoopIndex): Unit = step{i + 1}(x)"
                   for i in range(160)]
        bounded.write_text("package nodal.iteration.fixture\nimport nodal.*\nobject BoundedGraph:\n"
                           + "\n".join(methods)
                           + "\n  private def step160(x: LoopIndex): Unit = { val _ = x; () }\n"
                           + "  def test(): Unit = loop(LoopBound.Static(0))(step0)\n")
    else:
        bounded.write_text("package nodal.callback.fixture\nimport nodal.*\nobject BoundedGraph:\n"
                           + "\n".join(methods)
                           + "\n  private def step160(x: Expr[UInt]): Expr[UInt] = x\n"
                           + "  def test(xs: Expr[Vec[UInt]]) = xs.map(step0)\n")
    _, limit_output = compile_sources("bounded", [bounded], negative=True)
    if prefix + "LIMIT:" not in limit_output:
        raise RuntimeError("bounded graph did not report the explicit callback traversal limit")

    uninstrumented, _ = compile_sources(
        "uninstrumented", list((fixtures / "uninstrumented").glob("*.scala")), plugin=False)
    runtime, _ = compile_sources("runtime", list((fixtures / "runtime").glob("*.scala")),
                                 dependencies=[uninstrumented] + (dependencies if iteration else []),
                                 check_trees=True)
    if iteration:
        runtime = pack("runtime-consumer", runtime)
    runtime_cp = os.pathsep.join([args.compiler_classpath, args.api_classpath,
                                 str(uninstrumented), str(runtime)]
                                + ([str(path) for path in dependencies] if iteration else []))
    result = run("runtime-probe", [args.java, "-cp", runtime_cp,
                                   ("nodal.iteration.fixture.RuntimeProbe" if iteration else
                                    "nodal.callback.fixture.RuntimeProbe")])
    sentinel = ("DIGITAL_ITERATION_CALLBACK_PASS checked-public-source empty-uninstrumented-rejection"
                if iteration else
                "DIGITAL_COLLECTION_CALLBACK_PASS checked-public-source uninstrumented-rejection")
    if sentinel not in result:
        raise RuntimeError("public-path runtime witness did not publish its success sentinel")
    print(f"PASS typed positives, {len(negative_sources) + len(metadata_sources)} source-located negatives, bounded traversal, "
          f"{len(capability_sources)} capability negatives, public runtime path, "
          f"and uninstrumented rejection; logs: {out}")


if __name__ == "__main__":
    main()
