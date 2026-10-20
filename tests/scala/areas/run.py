#!/usr/bin/env python3
"""Focused lexical Area source and construction checks using already-built artifacts."""
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
    run("java-version", [args.java, "-version"])

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
                command += ["-Ycheck:nodalAreaCapture", "-Ycheck:nodalDigitalCollectionCallbacks"]
        return target, run(label, command + list(map(str, sources)), 1 if negative else 0)

    def runtime(label: str, main_class: str, paths: list[Path], marker: str) -> None:
        output = run(label, [args.java, "-cp", os.pathsep.join([
            args.compiler_classpath, args.api_classpath, *map(str, paths)]), main_class])
        if marker not in output:
            raise RuntimeError(f"{label}: runtime witness did not complete")

    positive, _ = compile_sources("positive", [fixtures / "positive/AreaPositive.scala"])
    runtime("positive-runtime", "nodal.AreaPositive", [positive], "AREA_LEXICAL_CONSTRUCTION_PASS")
    expected = {
        "MutableMember": "NODAL-AREA-058-MEMBER",
        "LazyMember": "NODAL-AREA-058-MEMBER",
        "Forged": "NODAL-AREA-058-FORGED",
        "BehavioralAllocation": "NODAL-SEQUENTIAL-056-UNAVAILABLE",
        "StructuralAllocation": "NODAL-STRUCTURAL-055-UNAVAILABLE",
        "CapabilityCast": "NODAL-SEQUENTIAL-056-UNAVAILABLE",
        "HostMutation": "NODAL-SEQUENTIAL-056-MUTABLE",
    }
    for name, diagnostic in expected.items():
        source = fixtures / "negative" / (name + ".scala")
        _, output = compile_sources("negative-" + name, [source], negative=True)
        if f"/{source.name}:" not in output or diagnostic not in output:
            raise RuntimeError(f"{name}: missing source-located diagnostic {diagnostic}\n{output}")
        if name == "CapabilityCast" and output.count(diagnostic) != 2:
            raise RuntimeError("both readonly and incompatible datatype casts must reject")

    producer, _ = compile_sources("producer", [fixtures / "producer/ReusableArea.scala"])
    producer_jar = out / "producer.jar"
    run("producer-jar", [str(Path(args.java).with_name("jar")), "cf", str(producer_jar),
                         "-C", str(producer), "."])
    factory, _ = compile_sources("factory", [fixtures / "factory/AreaFactory.scala"],
                                 dependencies=[producer_jar])
    factory_jar = out / "factory.jar"
    run("factory-jar", [str(Path(args.java).with_name("jar")), "cf", str(factory_jar),
                        "-C", str(factory), "."])
    consumer, _ = compile_sources("consumer", [fixtures / "consumer/AreaConsumer.scala"],
                                  dependencies=[producer_jar, factory_jar])
    runtime("separate-runtime", "nodal.AreaConsumer", [producer_jar, factory_jar, consumer],
            "AREA_SEPARATE_LIBRARY_PASS")
    missing, _ = compile_sources("producer-no-plugin", [fixtures / "producer/ReusableArea.scala"],
                                 plugin=False)
    _, output = compile_sources("factory-missing-metadata", [fixtures / "factory/AreaFactory.scala"],
                                 dependencies=[missing], negative=True)
    if "NODAL-AREA-058-MISSING" not in output:
        raise RuntimeError("separately compiled producer without metadata was accepted")
    uncaptured, _ = compile_sources("no-plugin", [fixtures / "no-plugin/UncapturedArea.scala"],
                                    plugin=False)
    runtime("no-plugin-runtime", "nodal.UncapturedArea", [uncaptured], "AREA_NO_PLUGIN_REJECTED")
    generated = fixtures / "generated"
    generated_producer, _ = compile_sources(
        "generated-producer", [generated / "producer/GeneratedProducer.scala"])
    generated_producer_jar = out / "generated-producer.jar"
    run("generated-producer-jar", [str(Path(args.java).with_name("jar")), "cf",
                                   str(generated_producer_jar), "-C", str(generated_producer), "."])
    generated_factory, _ = compile_sources(
        "generated-factory", [generated / "factory/GeneratedFactory.scala"],
        dependencies=[generated_producer_jar])
    generated_factory_jar = out / "generated-factory.jar"
    run("generated-factory-jar", [str(Path(args.java).with_name("jar")), "cf",
                                  str(generated_factory_jar), "-C", str(generated_factory), "."])
    generated_consumer, _ = compile_sources(
        "generated-consumer", [generated / "consumer/GeneratedConsumer.scala"],
        dependencies=[generated_producer_jar, generated_factory_jar])
    runtime("generated-separate-runtime", "nodal.GeneratedReuseProbe",
            [generated_producer_jar, generated_factory_jar, generated_consumer],
            "GENERATED_AREA_SEPARATE_JAR_PASS")
    generated_negatives = {
        "MemberTypes": "NODAL-AREA-058-MEMBER",
        "CapabilityCast": "NODAL-SEQUENTIAL-056-UNAVAILABLE",
        "HostMutation": "NODAL-ITERATION-CALLBACK-MUTABLE",
        "MixedWhen": "NODAL-COLLECTION-CALLBACK-EFFECT",
    }
    for name, diagnostic in generated_negatives.items():
        source = generated / "negative" / (name + ".scala")
        _, output = compile_sources("generated-negative-" + name, [source], negative=True,
                                    dependencies=[generated_producer_jar])
        if f"/{source.name}:" not in output or diagnostic not in output:
            raise RuntimeError(f"{name}: missing source-located diagnostic {diagnostic}\n{output}")
        if name == "CapabilityCast" and output.count(diagnostic) != 2:
            raise RuntimeError("both generated readonly and datatype casts must reject")
        if name == "MemberTypes" and "9 errors found" not in output:
            raise RuntimeError("all nine generated member type/capability negatives must reject")
        if name == "MixedWhen" and (
                "NODAL-ITERATION-CALLBACK-EFFECT" not in output or "2 errors found" not in output):
            raise RuntimeError("both pure collection and procedural loop guards must reject")
    print(f"PASS lexical and generated Area source/construction, {len(expected)} lexical "
          f"and {len(generated_negatives)} generated negative fixtures, "
          f"separate producer/factory/consumer JARs, "
          f"missing/no-plugin fail-closed; logs: {out}")


if __name__ == "__main__":
    main()
