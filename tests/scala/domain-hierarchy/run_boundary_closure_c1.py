#!/usr/bin/env python3
"""Run trusted package-internal C1 construction controls from explicit inputs.

Invoke with python3 -I -B. This runner builds fixture classes only, never the API,
lookup tree, native targets, or frontend plugin. See BOUNDARY_CLOSURE_C1.md.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

MANIFEST = "boundary_closure_c1.fixtures.json"
RESERVE = 200 * 1024 * 1024
FLAGS = ("-color:never", "-deprecation", "-feature", "-unchecked",
         "-Wunused:all", "-Werror")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def relative_path(value):
    path = Path(value)
    require(not path.is_absolute() and ".." not in path.parts and path.parts,
            "manifest paths must be nonempty relative paths")
    return path


def files(values):
    require(values and all(values), "empty input/classpath entry")
    result = [Path(value).resolve(strict=True) for value in values]
    require(all(path.is_file() for path in result),
            "inputs must be files, not prebuilt class directories")
    return result


def main():
    require(sys.flags.isolated and sys.dont_write_bytecode,
            "invoke with python3 -I -B")
    maintained = Path(__file__).resolve().parent
    manifest_path = maintained / MANIFEST
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest["schema"] == 1, "unsupported fixture manifest schema")
    suites = manifest["suites"]
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repository-root", "source-root", "compiler-classpath",
                 "runtime-classpath", "api-jar", "java", "output-root"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--timeout", type=int, required=True,
                        help="positive timeout in seconds for each command")
    parser.add_argument("--api-support-jar", action="append", default=[],
                        help="explicit support JAR after the primary API; repeat in order")
    parser.add_argument("--suite", action="append", choices=sorted(suites) + ["all"],
                        help="repeat to select suites; default is the four core suites")
    args = parser.parse_args()
    require(args.timeout > 0, "timeout must be positive")
    repo, source, output = [Path(value).resolve() for value in
                            (args.repository_root, args.source_root, args.output_root)]
    require((repo / ".git").exists(), "repository root must contain .git")
    require(output != repo and output.is_relative_to(repo),
            "output must be strictly inside the repository")
    require(not output.exists(), "refusing an existing output directory")
    ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", str(output)],
                             timeout=args.timeout, check=False)
    require(ignored.returncode == 0, "output must be git-ignored")
    require(shutil.disk_usage(repo).free >= RESERVE, "need at least 200 MiB free")
    for marker in ("build.mill", "core/scala", "docs"):
        require((source / marker).exists(), "coherent source root missing " + marker)
    selected = args.suite or manifest["default_suites"]
    if "all" in selected:
        require(selected == ["all"], "--suite all cannot be mixed with individual suites")
        selected = list(suites)
    require(len(selected) == len(set(selected)), "duplicate suite selection")
    names = set(name for suite in selected for name in suites[suite]["fixtures"])
    entries = {entry["name"]: entry for entry in manifest["fixtures"]}
    require(len(entries) == len(manifest["fixtures"]), "duplicate fixture manifest entry")
    require(names <= entries.keys(), "suite references an unknown fixture")
    fixtures = []
    maintained_fixtures = []
    expected_sources = {}
    for name in sorted(names):
        entry = entries[name]
        canonical = source / relative_path(entry["source_path"])
        bundled = maintained / relative_path(entry["maintained_path"])
        canonical, bundled = files([canonical, bundled])
        require(canonical.is_relative_to(source), "fixture escapes coherent source root")
        require(bundled.is_relative_to(maintained), "fixture escapes maintained test tree")
        for path in (canonical, bundled):
            require(digest(path) == entry["sha256"], "fixture bytes differ: " + str(path))
            expected_sources[path] = entry["sha256"]
        fixtures.append(canonical)
        maintained_fixtures.append(bundled)
    compiler = files(args.compiler_classpath.split(os.pathsep))
    runtime = files(args.runtime_classpath.split(os.pathsep))
    api = files([args.api_jar] + args.api_support_jar)
    java = files([args.java])[0]
    require(os.access(java, os.X_OK), "java must be executable")
    require(all(path.suffix == ".jar" for path in compiler + runtime + api),
            "compiler, runtime and API inputs must be explicit JARs")
    require(all(not path.is_relative_to(output) for path in
                fixtures + maintained_fixtures + compiler + runtime + api + [java]),
            "output cannot contain an input")
    output.mkdir(parents=True)
    for name in ("classes", "tmp", "home", "cache"):
        (output / name).mkdir()
    env = {key: value for key, value in os.environ.items() if key not in (
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH",
        "PYTHONPATH", "PYTHONHOME", "NODAL_WORKSPACE", "GITHUB_WORKSPACE",
        "BUILD_WORKSPACE_DIRECTORY")}
    env.update(NODAL_WORKSPACE=str(source), HOME=str(output / "home"),
               TMPDIR=str(output / "tmp"), XDG_CACHE_HOME=str(output / "cache"))
    inputs = list(dict.fromkeys(fixtures + maintained_fixtures + compiler + runtime + api +
                                [java, Path(__file__).resolve(), manifest_path]))
    hashes = {str(path): digest(path) for path in inputs}
    write_json(output / "inputs.json", {"source_root": str(source), "sha256": hashes})
    rows = []

    def verify_sources():
        for path, expected in expected_sources.items():
            require(digest(path) == expected, "fixture changed during run: " + str(path))

    def run(name, command, success=()):
        verify_sources()
        require(shutil.disk_usage(repo).free >= RESERVE, "disk reserve exhausted")
        command = list(map(str, command))
        write_json(output / (name + ".command.json"), command)
        row = {"name": name, "command": command, "status": "running"}
        rows.append(row)
        write_json(output / "results.json", rows)
        try:
            with (output / (name + ".stdout")).open("w", encoding="utf-8") as stdout, \
                    (output / (name + ".stderr")).open("w", encoding="utf-8") as stderr:
                done = subprocess.run(command, cwd=source, env=env, stdout=stdout,
                                      stderr=stderr, timeout=args.timeout, check=False)
            row["exit"] = done.returncode
            require(done.returncode == 0,
                    f"{name}: unexpected exit {done.returncode}; inspect command/stdout/stderr")
            text = (output / (name + ".stdout")).read_text(encoding="utf-8")
            require(all(marker in text for marker in success),
                    f"{name}: missing expected completion marker")
            verify_sources()
            row["status"] = "passed"
        except Exception as failure:
            row["status"] = "failed"
            row["error"] = str(failure)
            raise
        finally:
            write_json(output / "results.json", rows)

    java_base = [java, "-XX:-UsePerfData", "-Djava.io.tmpdir=" + str(output / "tmp"),
                 "-Duser.home=" + str(output / "home")]
    cp = os.pathsep.join(map(str, api + runtime))
    run("compile", java_base + ["-cp", os.pathsep.join(map(str, compiler)),
        "dotty.tools.dotc.Main", *FLAGS, "-classpath", cp, "-d", output / "classes", *fixtures])
    for name in selected:
        suite = suites[name]
        run(name, java_base + ["-cp", str(output / "classes") + os.pathsep + cp,
                              suite["main"], *suite.get("arguments", [])], suite["success"])
    for path in inputs:
        require(digest(path) == hashes[str(path)], "input changed during run: " + str(path))
    write_json(output / "summary.json", {"passed": selected, "fixture_count": len(fixtures),
               "scope": "trusted package-internal construction controls; no public reduction or native claims"})
    print("PASS C1 construction suites: " + ", ".join(selected))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print("C1 controls failed: " + str(error), file=sys.stderr)
        sys.exit(1)
