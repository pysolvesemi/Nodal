#!/usr/bin/env python3
"""Run the Scala 3.8.4 constructor probes through the existing Mill toolchain owner.

No downloader, repository writer, CI dispatcher, or credential route exists here.
A successful run is compiler/runtime evidence for the isolated protocol and production ABI.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
SCALA_VERSION = "3.8.4"
BASE = "315905bde74f23997ce1c03fe5bc3a7569a00b0d"
MAIN = "nodal.prototype.fixtures.ConstructorProbe"
PRODUCTION_MAIN = "nodal.constructor.separate.SeparateConsumer"
PRODUCTION_BOUNDARY_MAIN = "nodal.constructor.separate.RawSeparateConsumer"
NEGATIVE_EXPECTATIONS = {
    "effectful-default": "NODAL-CTOR-PROTOTYPE-DEFAULT",
    "curried-constructor": "NODAL-CTOR-PROTOTYPE-SHAPE",
    "generic-module": "NODAL-CTOR-PROTOTYPE-SHAPE",
    "secondary-constructor": "NODAL-CTOR-PROTOTYPE-SHAPE",
    "indirect-module": "NODAL-CTOR-PROTOTYPE-SHAPE",
    "missing-metadata": "NODAL-CTOR-PROTOTYPE-MISSING",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def existing_jars_from_mill_json(raw: str) -> list[str]:
    """Decode existing jars from Mill PathRef JSON.

    Mill may use strings, {path:...}, or ref:hash:path. Ignore log lines before
    the JSON value and accept only absolute paths to existing jar files.
    """
    decoder = json.JSONDecoder()
    values = []
    for match in re.finditer(r'''[\[{\"]''', raw):
        try:
            value, _ = decoder.raw_decode(raw[match.start():])
        except json.JSONDecodeError:
            continue
        if isinstance(value, (list, dict, str)):
            values.append(value)

    def paths(value):
        if isinstance(value, dict):
            if "path" in value:
                yield from paths(value["path"])
            else:
                for child in value.values():
                    yield from paths(child)
        elif isinstance(value, list):
            for child in value:
                yield from paths(child)
        elif isinstance(value, str):
            # Mill's ref prefix is metadata, not part of the filesystem path.
            absolute = value[value.find("/"):] if "/" in value else value
            path = Path(absolute)
            if path.is_absolute() and path.suffix == ".jar" and path.is_file():
                yield str(path)

    for value in values:
        result = list(dict.fromkeys(paths(value)))
        if result:
            return result
    return []


def parse_mill_classpath(raw: str) -> list[str]:
    result = existing_jars_from_mill_json(raw)
    if any("scala3-compiler_3-3.8.4" in path for path in result):
        return result
    raise RuntimeError("Mill did not report an existing Scala 3.8.4 compiler classpath")


def parse_mill_jar(raw: str, label: str) -> str:
    result = existing_jars_from_mill_json(raw)
    if len(result) != 1:
        raise RuntimeError(f"Mill did not report exactly one existing {label} jar")
    return result[0]


def validate_negative_diagnostic(output: str, diagnostic: str) -> None:
    crash = re.search(
        r"internal compiler error|crash(?:ed)? when compiling|unhandled exception|"
        r"exception in thread|exception while compiling|"
        r"java\.lang\.(?:AssertionError|StackOverflowError|OutOfMemoryError)|"
        r"^\s*at dotty\.tools\.", output, flags=re.IGNORECASE | re.MULTILINE,
    )
    if crash:
        raise RuntimeError("negative fixture encountered a compiler crash/internal error")
    if not re.search(r"(?<![A-Za-z0-9_-])" + re.escape(diagnostic) + r":", output):
        raise RuntimeError(f"negative fixture did not report expected diagnostic {diagnostic}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, help="Nodal checkout with build.mill and mill")
    parser.add_argument("--compiler-classpath", help="explicit existing pinned compiler jars")
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument("--out", type=Path, help="empty exact evidence directory")
    destination.add_argument("--out-parent", type=Path, help="create a fresh run directory here")
    parser.add_argument("--inventory-only", action="store_true",
                        help="write source inventory; do not start Java/Mill or compile")
    args = parser.parse_args()
    if args.out_parent is not None:
        parent = args.out_parent.resolve()
        parent.mkdir(parents=True, exist_ok=True)
        out = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    else:
        out = (args.out or HERE / "out").resolve()
    if out.exists() and any(out.iterdir()):
        parser.error("--out must be absent or empty; evidence is never overwritten")
    out.mkdir(parents=True, exist_ok=True)
    (out / "logs").mkdir()
    sources = sorted([Path(__file__).resolve()] + [
        p for directory in ("runtime", "plugin", "fixtures")
        for p in (HERE / directory).rglob("*")
        if p.is_file() and p.suffix in {".scala", ".properties"}
    ])
    manifest = {
        "prototype": "literal-real-constructor-v1",
        "scala_version": SCALA_VERSION,
        "review_base": BASE,
        "status": "not-run",
        "source_sha256": {str(p.relative_to(HERE)): digest(p) for p in sources},
        "commands": [],
        "negative_expectations": NEGATIVE_EXPECTATIONS,
        "scope": (
            "Standalone lifecycle protocol plus separately compiled production constructor ABI; "
            "not full F-042 qualification"
        ),
    }
    evidence = out / "manifest.json"

    def save():
        evidence.write_text(json.dumps(manifest, indent=2) + "\n")

    def print_failure_excerpt(label: str, output: str) -> None:
        excerpt = "\n".join(output.splitlines()[-80:])[-12000:]
        print(f"{label}: last output lines (bounded):", file=sys.stderr, flush=True)
        print(excerpt or "[command produced no output]", file=sys.stderr, flush=True)

    def run(label: str, command: list[str], *, cwd=HERE, expect_failure=False) -> str:
        start = time.time()
        log = out / "logs" / f"{label}.log"
        entry = {"label": label, "argv": command, "cwd": str(cwd),
                 "status": "running", "exit_code": None,
                 "log": str(log.relative_to(out))}
        manifest["commands"].append(entry)
        save()  # Persist the attempt before execution, including timeout paths.
        print(f"CONSTRUCTOR_PROBE_STAGE {label}", flush=True)
        try:
            result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=240)
        except subprocess.TimeoutExpired as failure:
            partial = failure.stdout or ""
            if isinstance(partial, bytes):
                partial = partial.decode("utf-8", errors="replace")
            log.write_text(partial)
            entry.update({"status": "timed_out", "timeout_seconds": 240,
                          "seconds": round(time.time() - start, 3), "sha256": digest(log)})
            save()
            print_failure_excerpt(label, partial)
            raise RuntimeError(f"{label}: timed out; partial output retained at {log}") from failure
        log.write_text(result.stdout)
        entry.update({"status": "completed", "exit_code": result.returncode,
                      "seconds": round(time.time() - start, 3), "sha256": digest(log)})
        save()
        if expect_failure and result.returncode != 1:
            print_failure_excerpt(label, result.stdout)
            raise RuntimeError(f"{label}: negative fixture must return compiler exit 1, "
                               f"got {result.returncode}")
        if not expect_failure and result.returncode != 0:
            print_failure_excerpt(label, result.stdout)
            raise RuntimeError(f"{label}: failed; inspect {log}")
        return result.stdout

    save()
    if args.inventory_only:
        print(f"NOT COMPILED: source inventory written to {evidence}")
        return 0
    try:
        if args.repo is None:
            raise RuntimeError("provide --repo so execution evidence identifies the actual source checkout")
        repo = args.repo.resolve()
        if not (repo / "build.mill").is_file() or not (repo / "mill").is_file():
            raise RuntimeError("--repo must identify the Nodal checkout")
        head = run("source-head", ["git", "rev-parse", "HEAD"], cwd=repo).strip()
        tree = run("source-tree", ["git", "rev-parse", "HEAD^{tree}"], cwd=repo).strip()
        worktree = run("source-worktree", ["git", "status", "--porcelain=v1",
                                          "--untracked-files=all"], cwd=repo)
        if not re.fullmatch(r"[0-9a-f]{40}", head) or not re.fullmatch(r"[0-9a-f]{40}", tree):
            raise RuntimeError("source Git identity was not an exact commit/tree SHA")
        manifest["source_identity"] = {
            "head_sha": head, "tree_sha": tree,
            "worktree_status": worktree.splitlines(),
            "tracked_and_untracked_clean": not worktree.strip(),
        }
        manifest["repository_support_sha256"] = {
            name: digest(repo / name)
            for name in ("build.mill", "scripts/nodal.py", ".scalafmt.conf", ".scalafix.conf")
            if (repo / name).is_file()
        }
        save()
        if args.compiler_classpath:
            compiler_jars = args.compiler_classpath.split(os.pathsep)
            if not compiler_jars or any(not Path(p).is_file() for p in compiler_jars):
                raise RuntimeError("explicit compiler classpath contains a missing file")
            manifest["compiler_owner"] = "explicit-existing-classpath"
        else:
            raw = run("mill-compiler-classpath", [str(repo / "mill"), "-i", "show",
                       "core.scala.api.scalaCompilerClasspath"], cwd=repo)
            compiler_jars = parse_mill_classpath(raw)
            manifest["compiler_owner"] = "Nodal core.scala.api.scalaCompilerClasspath"
        compiler_cp = os.pathsep.join(compiler_jars)
        java = shutil.which("java")
        jar = shutil.which("jar")
        if java is None or jar is None:
            raise RuntimeError("existing JDK java and jar commands are required")
        version = run("compiler-version", [java, "-cp", compiler_cp,
                       "dotty.tools.dotc.Main", "-version"])
        if not re.search(r"version\s+3\.8\.4(?:\s|$)", version):
            raise RuntimeError("compiler version is not exactly Scala 3.8.4")
        manifest["compiler_jars"] = [{"path": p, "sha256": digest(Path(p))}
                                     for p in compiler_jars]
        save()
        stages = {}
        plugin_jar = out / "constructor-capture-plugin.jar"

        def compile_stage(name, source_dir, dependencies=(), plugin=False, negative=None):
            target = out / name
            target.mkdir()
            sources = sorted(str(p) for p in (HERE / source_dir).rglob("*.scala"))
            if not sources:
                raise RuntimeError(f"{name}: source list is empty")
            cp = os.pathsep.join([compiler_cp] + [str(p) for p in dependencies])
            command = [java, "-cp", compiler_cp, "dotty.tools.dotc.Main",
                       "-classpath", cp, "-d", str(target), "-deprecation", "-feature",
                       "-unchecked", "-color:never"]
            if plugin:
                command += [f"-Xplugin:{plugin_jar}", "-Ycheck:all",
                            "-Vprint:nodalConstructorCapture"]
            text = run(name, command + sources, expect_failure=negative is not None)
            if negative is not None:
                try:
                    validate_negative_diagnostic(text, negative)
                except RuntimeError:
                    print_failure_excerpt(name, text)
                    raise
                manifest.setdefault("negative_results", {})[name] = "expected-diagnostic"
            else:
                stages[name] = target
            save()
            return target

        def pack(name, directory, destination):
            run(name, [jar, "--create", "--file", str(destination), "-C", str(directory), "."])
            return destination

        runtime = compile_stage("runtime", "runtime/src")
        runtime_jar = pack("runtime-jar", runtime, out / "runtime.jar")
        plugin = compile_stage("plugin", "plugin/src")
        shutil.copy2(HERE / "plugin/resources/plugin.properties", plugin / "plugin.properties")
        pack("plugin-jar", plugin, plugin_jar)
        definitions = compile_stage("definitions", "fixtures/definitions/src", [runtime_jar], True)
        definitions_jar = pack("definitions-jar", definitions, out / "definitions.jar")
        factory = compile_stage("factory", "fixtures/factory/src", [runtime_jar, definitions_jar], True)
        factory_jar = pack("factory-jar", factory, out / "factory.jar")
        consumer = compile_stage("consumer", "fixtures/consumer/src",
                                 [runtime_jar, definitions_jar, factory_jar], True)
        runtime_cp = os.pathsep.join([compiler_cp, str(runtime_jar), str(definitions_jar),
                                     str(factory_jar), str(consumer)])
        result = run("runtime-assertions", [java, "-cp", runtime_cp, MAIN])
        sentinels = re.findall(r"^CONSTRUCTOR_PROBE_PASS cases=(\d+) assertions=(\d+)$",
                               result, flags=re.MULTILINE)
        if len(sentinels) != 1 or int(sentinels[0][0]) != 11 or int(sentinels[0][1]) <= 0:
            print_failure_excerpt("runtime-assertions", result)
            raise RuntimeError("runtime must report exactly 11 cases and a positive assertion count")
        manifest["runtime_cases"] = int(sentinels[0][0])
        manifest["runtime_assertions"] = int(sentinels[0][1])
        raw_definitions = compile_stage("uninstrumented-definitions",
                                        "fixtures/uninstrumented-definitions/src", [runtime_jar])
        raw_jar = pack("uninstrumented-definitions-jar", raw_definitions,
                       out / "uninstrumented-definitions.jar")
        raw_factory = compile_stage("uninstrumented-factory",
                                    "fixtures/uninstrumented-factory/src",
                                    [runtime_jar, definitions_jar])
        raw_factory_jar = pack("uninstrumented-factory-jar", raw_factory,
                              out / "uninstrumented-factory.jar")
        boundary = compile_stage("factory-boundary-consumer",
                                 "fixtures/factory-boundary-consumer/src",
                                 [runtime_jar, definitions_jar, raw_factory_jar], True)
        boundary_cp = os.pathsep.join([compiler_cp, str(runtime_jar), str(definitions_jar),
                                      str(raw_factory_jar), str(boundary)])
        boundary_result = run("uninstrumented-factory-runtime-rejection",
                              [java, "-cp", boundary_cp,
                               "nodal.prototype.fixtures.raw.RawFactoryBoundaryProbe"])
        if boundary_result.splitlines().count("UNINSTRUMENTED_FACTORY_REJECTED") != 1:
            raise RuntimeError("uninstrumented factory boundary did not reject")
        for name, diagnostic in NEGATIVE_EXPECTATIONS.items():
            compile_stage("negative-" + name, "fixtures/negative/" + name,
                          [runtime_jar, definitions_jar, raw_jar], True, diagnostic)

        api_raw = run("mill-production-api-jar", [str(repo / "mill"), "-i", "show",
                      "core.scala.api.jar"], cwd=repo)
        production_api_jar = Path(parse_mill_jar(api_raw, "production API"))
        plugin_raw = run("mill-production-plugin-jar", [str(repo / "mill"), "-i", "show",
                         "core.scala.constructorPlugin.jar"], cwd=repo)
        production_plugin_jar = Path(parse_mill_jar(plugin_raw, "production plugin"))
        manifest["production_owner_jars"] = {
            "api": {"path": str(production_api_jar), "sha256": digest(production_api_jar)},
            "plugin": {
                "path": str(production_plugin_jar),
                "sha256": digest(production_plugin_jar),
            },
        }
        save()

        def compile_production(name, source_dir, dependencies=(), plugin=True):
            target = out / name
            target.mkdir()
            source_paths = sorted(str(p) for p in (HERE / source_dir).rglob("*.scala"))
            if not source_paths:
                raise RuntimeError(f"{name}: source list is empty")
            classpath = os.pathsep.join([compiler_cp, str(production_api_jar)] + [
                str(path) for path in dependencies
            ])
            command = [java, "-cp", compiler_cp, "dotty.tools.dotc.Main",
                       "-classpath", classpath, "-d", str(target), "-deprecation", "-feature",
                       "-unchecked", "-Wunused:all", "-Werror", "-color:never"]
            if plugin:
                command += [f"-Xplugin:{production_plugin_jar}",
                            "-Xplugin-require:nodal-constructor", "-Ycheck:all",
                            "-Vprint:nodalConstructorCapture"]
            run(name, command + source_paths)
            return target

        production_definitions = compile_production(
            "production-definitions", "fixtures/production-definitions/src"
        )
        production_definitions_jar = pack(
            "production-definitions-jar", production_definitions,
            out / "production-definitions.jar"
        )
        production_factory = compile_production(
            "production-factory", "fixtures/production-factory/src",
            [production_definitions_jar]
        )
        production_factory_jar = pack(
            "production-factory-jar", production_factory, out / "production-factory.jar"
        )
        production_consumer = compile_production(
            "production-consumer", "fixtures/production-consumer/src",
            [production_definitions_jar, production_factory_jar]
        )
        production_cp = os.pathsep.join([
            compiler_cp, str(production_api_jar), str(production_definitions_jar),
            str(production_factory_jar), str(production_consumer)
        ])
        production_result = run(
            "production-runtime-assertions", [java, "-cp", production_cp, PRODUCTION_MAIN]
        )
        if production_result.splitlines().count("SEPARATE_COMPILE_CAPTURE_PASS") != 1:
            raise RuntimeError("production separate-compilation assertions did not pass")

        production_raw_factory = compile_production(
            "production-uninstrumented-factory",
            "fixtures/production-uninstrumented-factory/src",
            [production_definitions_jar],
            plugin=False,
        )
        production_raw_factory_jar = pack(
            "production-uninstrumented-factory-jar", production_raw_factory,
            out / "production-uninstrumented-factory.jar"
        )
        production_boundary = compile_production(
            "production-boundary-consumer", "fixtures/production-boundary-consumer/src",
            [production_definitions_jar, production_raw_factory_jar]
        )
        production_boundary_cp = os.pathsep.join([
            compiler_cp, str(production_api_jar), str(production_definitions_jar),
            str(production_raw_factory_jar), str(production_boundary)
        ])
        production_boundary_result = run(
            "production-uninstrumented-factory-runtime-rejection",
            [java, "-cp", production_boundary_cp, PRODUCTION_BOUNDARY_MAIN]
        )
        if production_boundary_result.splitlines().count(
            "UNINSTRUMENTED_FACTORY_REJECTION_PASS"
        ) != 1:
            raise RuntimeError("production uninstrumented factory boundary did not reject")
        manifest["production_abi"] = {
            "status": "passed",
            "separate_compilation_sentinel": "SEPARATE_COMPILE_CAPTURE_PASS",
            "uninstrumented_factory_sentinel": "UNINSTRUMENTED_FACTORY_REJECTION_PASS",
        }
        manifest["status"] = "passed"
        manifest["artifacts"] = {str(p.relative_to(out)): digest(p)
                                 for p in out.glob("*.jar")}
        save()
        print(f"CONSTRUCTOR_PROBE_PASS: {evidence}")
        return 0
    except Exception as failure:
        manifest["status"] = "failed"
        manifest["failure"] = str(failure)
        save()
        print(f"CONSTRUCTOR_PROBE_FAILED: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
