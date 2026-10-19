#!/usr/bin/env python3
"""Focused flat-FSM/action-switch compiler/runtime witnesses with existing pinned artifacts.

No downloads, CI, full qualification, publication, or shared build-output writes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

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
        parser.error("generated outputs must stay under the ignored .work directory")
    args.out_parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=args.out_parent.resolve()))
    fixtures = HERE / "fixtures"
    manifest = {
        "status": "running",
        "plugin_sha256": hashlib.sha256(args.plugin_jar.read_bytes()).hexdigest(),
        "source_sha256": {str(path.relative_to(HERE)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in [Path(__file__).resolve(), *sorted(fixtures.rglob("*.scala"))]},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

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
        if not negative and plugin:
            command += ["-Ycheck:nodalDigitalCollectionCallbacks"]
        return target, run(label, command + list(map(str, sources)), 1 if negative else 0)

    def pack(label: str, target: Path) -> Path:
        archive = out / (label + ".jar")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as jar:
            for entry in sorted(target.rglob("*")):
                if entry.is_file():
                    jar.write(entry, entry.relative_to(target))
        return archive

    def rejected(label: str, source: Path, code: str, **kwargs) -> str:
        _, output = compile_sources(label, [source], negative=True, **kwargs)
        if f"/{source.name}:" not in output or code + ":" not in output:
            raise RuntimeError(f"missing source-located {code} for {source}\n{output}")
        return output

    positive, _ = compile_sources("positive", sorted((fixtures / "positive").glob("*.scala")))
    bytecode = run("positive-bytecode", [str(Path(args.java).with_name("javap")),
        "-classpath", str(positive), "-c", "-p", "Positive$"])
    for name in ("action", "transitionAction", "switchCase", "switchEnum"):
        if "FlatFsmRuntime$." + name not in bytecode:
            raise RuntimeError("missing checked source lowering: " + name)
    if re.search(r"StateBuilder\.(?:entry|active|exit|onWithAction):|EnumSwitchBuilder\.is:", bytecode):
        raise RuntimeError("public action markers survived capture")
    negatives = sorted((fixtures / "negative").glob("*.scala"))
    _, output = compile_sources("negative", negatives, negative=True)
    blocks = re.split(r"(?=^--(?: \[[^\]]+\])? .*Error: )", output, flags=re.M)
    for source in negatives:
        expected = re.search(r"// Expected: (NODAL-[A-Z0-9-]+)", source.read_text())
        if expected is None or not any(f"/{source.name}:" in block and expected[1] + ":" in block
                                       for block in blocks):
            raise RuntimeError(f"missing exact source-located diagnostic for {source}\n{output}")
    if sum("/Forged.scala:" in block and "NODAL-SEQUENTIAL-056-FORGED:" in block
           for block in blocks) != 5:
        raise RuntimeError("every authored flat-FSM runtime/attestation call must reject")
    if sum("/KernelForged.scala:" in block and "NODAL-SEQUENTIAL-056-FORGED:" in block
           for block in blocks) != 11:
        raise RuntimeError("every authored low-level flat-FSM/switch entrypoint must reject")
    _, compatibility = compile_sources("custom-compatibility", [fixtures / "compatibility/OldEncodingMatch.scala"], negative=True)
    if "exhaustive" not in compatibility or "Custom" not in compatibility:
        raise RuntimeError("the approved appended Custom source-compatibility change disappeared")
    base, _ = compile_sources("base", [fixtures / "base/Base.scala"])
    base = pack("base", base)
    producer, _ = compile_sources("producer", [fixtures / "producer/Producer.scala"], dependencies=[base])
    producer = pack("producer", producer)
    consumer = fixtures / "consumer/Consumer.scala"
    compile_sources("consumer", [consumer], dependencies=[base, producer])
    rejected("profile-consumer", fixtures / "profile-consumer/Consumer.scala",
             "NODAL-SEQUENTIAL-056-SUMMARY", dependencies=[base, producer])
    for name, source, plugin in [("missing", "base/Base.scala", False),
                                 ("impure", "base-impure/Base.scala", True)]:
        replacement, _ = compile_sources("base-" + name, [fixtures / source], plugin=plugin)
        replacement = pack("base-" + name, replacement)
        rejected("consumer-" + name, consumer, "NODAL-SEQUENTIAL-056-UNAVAILABLE",
                 dependencies=[replacement, producer])
    enums, _ = compile_sources("enum-producer", [fixtures / "enum-producer/Enums.scala"])
    enums = pack("enum-producer", enums)
    enum_consumer = fixtures / "enum-consumer/Consumer.scala"
    compile_sources("enum-consumer", [enum_consumer], dependencies=[enums])
    for name, source, plugin in [("missing", "enum-producer/Enums.scala", False),
                                 ("impure", "enum-impure/Enums.scala", True)]:
        replacement, _ = compile_sources("enums-" + name, [fixtures / source], plugin=plugin)
        replacement = pack("enums-" + name, replacement)
        rejected("enum-consumer-" + name, enum_consumer, "NODAL-SEQUENTIAL-056-UNAVAILABLE",
                 dependencies=[replacement])
    missing = rejected("marker-no-plugin", fixtures / "no-plugin/Marker.scala",
             "NODAL-SEQUENTIAL-056-MISSING", plugin=False)
    if missing.count("NODAL-SEQUENTIAL-056-MISSING:") != 6:
        raise RuntimeError("every public action/switch marker must fail closed without capture")
    raw, _ = compile_sources("uninstrumented", [fixtures / "uninstrumented/Raw.scala"], plugin=False)
    raw = pack("uninstrumented", raw)
    runtime, _ = compile_sources("runtime", [fixtures / "runtime/RuntimeProbe.scala"], dependencies=[raw])
    output = run("runtime-probe", [args.java, "-cp", os.pathsep.join([
        args.compiler_classpath, args.api_classpath, str(raw), str(runtime)]),
        "nodal.flatfsm.fixture.RuntimeProbe"])
    if "FLAT_FSM_ACTION_RUNTIME_PASS" not in output:
        raise RuntimeError("flat-FSM runtime witness did not complete")
    final_sources = {str(path.relative_to(HERE)): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in [Path(__file__).resolve(), *sorted(fixtures.rglob("*.scala"))]}
    if final_sources != manifest["source_sha256"]:
        raise RuntimeError("test sources changed while the harness was running")
    if hashlib.sha256(args.plugin_jar.read_bytes()).hexdigest() != manifest["plugin_sha256"]:
        raise RuntimeError("the plugin artifact changed while the harness was running")
    manifest["status"] = "pass"
    manifest["negative_fixture_count"] = len(negatives)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"PASS flat-FSM/action-switch source positives, {len(negatives)} exact negatives, separate-JAR "
          f"proofs/substitutions, no-plugin boundary, runtime attestation; logs: {out}")


if __name__ == "__main__":
    main()
