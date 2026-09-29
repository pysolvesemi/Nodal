#!/usr/bin/env python3
"""Compare pinned before/after construction using ordinary Mill and native owners.

The immutable experiment is pinned before execution. A detached baseline receives
only the identical test-only overlay. No compiler source is rewritten, output is
normalized, missing lane is passed, or historical run relabeled as new execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tarfile
import time
import zipfile

HERE = Path(__file__).resolve().parent
EXPERIMENT = HERE / "experiment.json"
BASELINE = "cafd52e5b7ea0d63b1eb503d281cf801bedd6808"
BASELINE_TREE = "f14dc1e38e08c07243fda52e61378b95c7bd19d9"
SCHEMA = "nodal.increment160.parity-results.v1"
PHASES = ("construction", "lifecycleValidationSnapshot", "fullRecordSerialization",
          "canonicalSerialization")
# This optional local reuse route is bound to one independently authenticated
# artifact. Fresh CI always uses its just-completed candidate build receipt.
HISTORICAL_NATIVE_ARTIFACT = {
    "repository": "pysolvesemi/Nodal",
    "run_id": 36427871059,
    "run_attempt": 1,
    "workflow_id": 352989730,
    "run_head_sha": "80c03ad3902c67add552c79094c119df70ac7988",
    "actual_checkout_sha": "d1813c8579dfe6dc1ee5dfe71b6e4b133bb32df7",
    "actual_checkout_tree_sha": "a2ed2b081e9b515b9aabfb12c80d59b0305ec59a",
    "archive_reconstructed_tree_sha": "a2ed2b081e9b515b9aabfb12c80d59b0305ec59a",
    "artifact_id": 10972749656,
    "artifact_zip_sha256": "c9ab8269e4be20da148ad384b9bfa649471841636029a3b2ff12516e4cfcce34",
    "source_archive_sha256": "0705cf2e465c1f2d3a4ccb15e1d9001bd6b758ad6fa3bd3eb9663a77d8cdf41f",
    "original_native_log_sha256": "558db8cbec9aa6adece74034f97e59c25e5d852073641e8b521b72cb29de1859",
    "original_native_job": {
        "id": 108946220054,
        "conclusion": "success",
        "locked_native_build_and_tests_step": {"number": 8, "conclusion": "success"},
        "native_tools_packaging_step": {"number": 9, "conclusion": "success"},
        "source_target_gates_step": {"number": 10, "conclusion": "success"},
    },
}


class EvidenceError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".writing")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *arguments], check=False,
                            capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, f"Git identity check failed: {arguments}: {result.stderr}")
    return result.stdout.strip()


def source_identity(root: Path) -> dict:
    require(not git(root, "diff", "HEAD", "--name-only"), "tracked source worktree is dirty")
    return {"root": str(root), "commit": git(root, "rev-parse", "HEAD"),
            "tree": git(root, "rev-parse", "HEAD^{tree}"),
            "untracked": git(root, "ls-files", "--others", "--exclude-standard").splitlines()}


def read_experiment(path: Path = EXPERIMENT) -> dict:
    data = json.loads(path.read_text())
    require(data.get("schema") == "nodal.increment160.experiment.v1", "wrong experiment schema")
    require(data.get("baseline_commit") == BASELINE and data.get("baseline_tree") == BASELINE_TREE,
            "experiment baseline is not the accepted pinned source")
    require(data.get("protocol") == {
        "pair_order": [["baseline", "candidate"], ["candidate", "baseline"],
                       ["baseline", "candidate"]],
        "cold_iterations": 1, "warmup_iterations": 2, "warm_iterations": 5,
        "max_noisy_repeats": 1, "noise_mad_multiplier": 3,
    }, "experiment trial protocol changed")
    cases = data.get("cases", [])
    require(len(cases) == 55 and len({entry["id"] for entry in cases}) == 55,
            "missing, duplicate or unexpected case inventory")
    sizes = {family: [entry["size"] for entry in cases if entry["family"] == family]
             for family in ("deep", "wide", "repeated", "shared-dag", "symbolic")}
    require(sizes == {"deep": [4, 24], "wide": [8, 128], "repeated": [8, 256],
                      "shared-dag": [16, 256], "symbolic": [8, 128]}, "scale inventory changed")
    require(data.get("budgets") == {
        "warm_wall_nanos": {"relative": 0.25, "absolute": 10000000},
        "cold_wall_nanos": {"relative": 0.30, "absolute": 100000000},
        "allocated_bytes": {"relative": 0.15, "absolute": 65536},
        "peak_rss_bytes": {"relative": 0.20, "absolute": 16777216},
    }, "predeclared regression budgets changed")
    require(data.get("required_warm_metrics") == list(PHASES), "required measured phases changed")
    require(data.get("required_artifacts") == ["construction.full.json", "construction.canonical.json", "effects.json"],
            "required artifact inventory changed")
    require(data.get("scala_version") == "3.8.4" and data.get("mill_version") == "1.1.7" and
            data.get("jdk_major") == 25, "pinned experiment tools changed")
    require(data.get("jvm_options") == ["-Xms128m", "-Xmx2g", "-XX:+UseG1GC"],
            "pinned measurement JVM options changed")
    require(data.get("timeouts_seconds") == {"compile": 1200, "probe": 240, "native": 60},
            "bounded experiment timeouts changed")
    require(data.get("overlay_files") == [
        "core/scala/testkit/test/src/nodal/ConstructionParityProbe.scala",
        "core/scala/testkit/test/src/nodal/ConstructionParityProbeTests.scala",
    ], "test-only overlay allowlist changed")
    require(data.get("main_class") == "nodal.internal.testkit.ConstructionParityProbe",
            "required workload entrypoint changed")
    require(data.get("fixture_source_roots") == [
        "core/scala/testkit/test/src", "examples/continuousTimeApi/src",
    ], "accepted fixture source boundary changed")
    require(data.get("invariant_toolchain_files") == [
        "build.mill", ".mill-version", "mill", "toolchains/lock.json", "toolchains/lint-lock.json",
    ], "pinned toolchain source boundary changed")
    require(data.get("native_source_roots") == [
        "core/compiler", "cmake", "toolchains", "CMakeLists.txt", "CMakePresets.json",
    ], "native source equivalence boundary changed")
    require(all(not entry["target"] or entry["bridge"] for entry in cases),
            "a target case has no public source bridge")
    return data


def metadata_value(value):
    """Decode test result metadata only. Artifact bytes never pass this function."""
    if isinstance(value, list):
        return [metadata_value(item) for item in value]
    if isinstance(value, dict):
        if value == {"$none": True}:
            return None
        if set(value) == {"$some"}:
            return metadata_value(value["$some"])
        return {key: metadata_value(item) for key, item in value.items() if key != "$type"}
    return value


def decode_mill_paths(raw: str) -> list[Path]:
    """Read Mill PathRefs, retaining directories and jars rather than guessing a classpath."""
    decoder = json.JSONDecoder()

    def paths(value):
        if isinstance(value, str):
            absolute = value[value.find("/"):] if "/" in value else value
            path = Path(absolute)
            if path.is_absolute() and path.exists():
                yield path.resolve()
        elif isinstance(value, list):
            for item in value:
                yield from paths(item)
        elif isinstance(value, dict):
            if "path" in value:
                yield from paths(value["path"])
            else:
                for item in value.values():
                    yield from paths(item)

    for position in re.finditer(r"[\[{\"]", raw):
        try:
            value, _ = decoder.raw_decode(raw[position.start():])
        except json.JSONDecodeError:
            continue
        found = list(dict.fromkeys(paths(value)))
        if found:
            return found
    raise EvidenceError("Mill did not return an existing classpath")


def path_digest(path: Path) -> dict:
    if path.is_file():
        return {"path": str(path), "kind": "file", "sha256": sha256(path)}
    require(path.is_dir(), f"classpath entry disappeared: {path}")
    files = [{"path": str(child.relative_to(path)), "sha256": sha256(child)}
             for child in sorted(path.rglob("*")) if child.is_file()]
    require(bool(files), f"empty compiled classpath directory: {path}")
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"path": str(path), "kind": "directory", "sha256": hashlib.sha256(encoded).hexdigest(),
            "files": files}


def jar_content_digest(path: Path) -> str:
    with zipfile.ZipFile(path) as jar:
        names = sorted(entry.filename for entry in jar.infolist() if not entry.is_dir())
        require(len(names) == len(set(names)), "compiler plugin jar has duplicate entries")
        inventory = [(name, hashlib.sha256(jar.read(name)).hexdigest()) for name in names]
    return hashlib.sha256(json.dumps(inventory, separators=(",", ":")).encode()).hexdigest()


def validate_build_identity(build: dict) -> None:
    require(path_digest(Path(build["java"]["path"])) == build["java"],
            "managed Java changed during experiment")
    for key in ("classpath", "compiler_classpath", "fixture_classpath"):
        require([path_digest(Path(entry["path"])) for entry in build[key]] == build[key],
                f"pinned {key} changed during experiment")
    require(sha256(Path(build["plugin"]["path"])) == build["plugin"]["sha256"],
            "constructor plugin changed during experiment")


def artifact_names(case: dict, experiment: dict) -> list[str]:
    names = list(experiment["required_artifacts"])
    if case["rejection"] is not None:
        names.append("diagnostic.json")
    if case["bridge"]:
        names.append("source.mlir")
    if case["target"]:
        names += ["normalized.mlir", "output.va"]
    return names


def compare_artifacts(first: Path, second: Path, names: list[str]) -> list[dict]:
    evidence = []
    for name in names:
        left, right = first / name, second / name
        require(left.is_file() and right.is_file(), f"missing required artifact {name}")
        a, b = left.read_bytes(), right.read_bytes()
        require(bool(a) and bool(b), f"empty required artifact {name}")
        require(a == b, f"artifact parity differs: {left} versus {right}")
        evidence.append({"name": name, "bytes": len(a), "sha256": hashlib.sha256(a).hexdigest()})
    return evidence


def assess_budget(before: list[float], after: list[float], budget: dict,
                  noise_multiplier: int = 3) -> dict:
    if not before or not after or any(value is None or type(value) not in (int, float) or
                                     not math.isfinite(value) or value < 0
                                     for value in before + after):
        return {"status": "incomplete", "reason": "missing or invalid samples"}
    a, b = statistics.median(before), statistics.median(after)
    mad_a = statistics.median(abs(value - a) for value in before)
    mad_b = statistics.median(abs(value - b) for value in after)
    limit = a + max(a * budget["relative"], budget["absolute"])
    status = "pass"
    if b > limit:
        status = "noisy" if b - limit <= noise_multiplier * max(mad_a, mad_b) else "regression"
    return {"status": status, "baseline_median": a, "candidate_median": b,
            "baseline_mad": mad_a, "candidate_mad": mad_b, "limit": limit,
            "baseline_samples": before, "candidate_samples": after}


def load_probe(path: Path, case: dict, experiment: dict) -> dict:
    require(path.is_file(), "missing probe measurements")
    data = metadata_value(json.loads(path.read_text()))
    require(data.get("schema") == "nodal.increment160.probe.v1", "wrong probe schema")
    require(data.get("caseInfo") == case, "probe executed a different workload")
    samples = data.get("samples", [])
    expected = ["cold"] + ["warmup"] * 2 + ["warm"] * 5
    require([sample.get("kind") for sample in samples] == expected,
            "cold/warmup/warm sample inventory is incomplete")
    require(data.get("factoryCalls") == len(expected), "factory evaluation count is incomplete")
    require(str(data.get("javaVersion", "")).split(".")[0] == str(experiment["jdk_major"]),
            "probe did not use the pinned JDK major")
    for sample in samples:
        for counter in ("heapUsedBytes", "heapPoolPeakBytes"):
            require(type(sample.get(counter)) is int and sample[counter] >= 0,
                    f"missing or invalid expected G1 heap counter: {counter}")
        metrics = [sample.get("totalInspection"), sample.get("fullRecordSerialization"),
                   sample.get("canonicalSerialization")]
        if case["rejection"] is None:
            metrics += [sample.get("construction"), sample.get("lifecycleValidationSnapshot")]
        if case["bridge"]:
            metrics += [sample.get("bridge")]
        for metric in metrics:
            require(isinstance(metric, dict), "required phase measurement is missing")
            require(type(metric.get("wallNanos")) is int and metric["wallNanos"] >= 0,
                    "invalid measured wall time")
            for counter in ("allocatedBytes", "gcCollections", "gcMillis"):
                value = metric.get(counter)
                require(value is None or type(value) is int and value >= 0,
                        f"invalid measured {counter}")
                if counter != "allocatedBytes":
                    require(value is not None, f"expected JDK25/G1 counter unavailable: {counter}")
    return data


def tree_entries(root: Path, revision: str, paths: list[str]) -> dict:
    output = git(root, "ls-tree", "-r", revision, "--", *paths)
    entries = {}
    for line in output.splitlines():
        head, path = line.split("\t", 1)
        mode, kind, blob = head.split()
        require(kind == "blob", "unexpected native source tree entry")
        entries[path] = {"mode": mode, "git_blob": blob}
    return entries


def validate_native_receipt(receipt_path: Path, artifact: bool, candidate: Path,
                            baseline: Path, identities: dict, experiment: dict,
                            tools: dict[str, Path], artifact_zip: Path | None = None) -> tuple[dict, dict]:
    receipt = json.loads(receipt_path.read_text())
    entries = tree_entries(candidate, "HEAD", experiment["native_source_roots"])
    require(entries and entries == tree_entries(baseline, "HEAD", experiment["native_source_roots"]),
            "baseline/candidate native source or toolchain changed")
    native_tree = git(candidate, "rev-parse", "HEAD:core/compiler")
    lock = sha256(candidate / "toolchains/lock.json")
    environment = {}
    if not artifact:
        require(artifact_zip is None, "historical artifact ZIP cannot accompany a fresh build receipt")
        require(receipt.get("schema") == "nodal.increment160.native-build.v1", "wrong native build receipt")
        require(receipt.get("source_commit") == identities["candidate"]["commit"] and
                receipt.get("source_tree") == identities["candidate"]["tree"], "native build belongs to another source")
        require(receipt.get("command") == "./nodal core native", "missing native build owner")
        require(receipt.get("compiler_source_tree") == native_tree and receipt.get("lock_sha256") == lock,
                "native build source/lock mismatch")
        for name, path in tools.items():
            record = receipt.get("tools", {}).get(name, {})
            require(Path(record.get("path", "")).resolve() == path and record.get("sha256") == sha256(path),
                    f"native build tool mismatch: {name}")
        return {"mode": "fresh-candidate-build", "receipt": receipt,
                "receipt_sha256": sha256(receipt_path)}, environment
    validate_artifact_identity(receipt)
    require(receipt.get("baseline_sha") == BASELINE, "artifact receipt identifies a different baseline")
    require(receipt.get("native_tree_sha") == native_tree and receipt.get("toolchain_lock_sha256") == lock,
            "artifact native source/lock mismatch")
    require(receipt.get("actual_checkout_tree_sha") == receipt.get("archive_reconstructed_tree_sha"),
            "artifact source archive tree is unverified")
    archive = receipt_path.parent / "source.tar.gz"
    require(archive.is_file() and sha256(archive) == receipt.get("source_archive_sha256"),
            "original native source archive hash mismatch")
    archived = archive_inventory(archive)
    require(archived["tree"] == receipt.get("actual_checkout_tree_sha"),
            "native archive does not reconstruct its claimed Git tree")
    records = {entry["path"]: entry for entry in receipt.get("native_file_equivalence", [])}
    require(set(records) == set(entries) and len(records) == receipt.get("native_source_files_compared"),
            "native artifact equivalence inventory is incomplete")
    for path, entry in entries.items():
        record = records[path]
        require(all(record.get(key) == entry["git_blob"] for key in
                    ("baseline_git_blob", "archive_git_blob", "current_git_blob")) and
                record.get("mode") == entry["mode"], f"native source identity mismatch: {path}")
        require(archived["files"].get(path) == {**entry, "sha256": record["sha256"]} and
                sha256(candidate / path) == record["sha256"] and
                sha256(baseline / path) == record["sha256"], f"native source bytes mismatch: {path}")
    binaries = {entry["name"]: entry for entry in receipt.get("binaries", [])}
    for key, name in (("nodalc", "nodalc"), ("translate", "nodal-translate")):
        record = binaries.get(name, {})
        require(record.get("sha256") == sha256(tools[key]) and
                Path(record.get("path", "")).resolve() == tools[key], "artifact executable mismatch")
    libraries = receipt.get("bundled_libraries_match_locked_prebuilt", [])
    library_root = tools["nodalc"].parent / "lib"
    require(libraries and {entry["file"] for entry in libraries} ==
            {path.name for path in library_root.iterdir() if path.is_file()},
            "artifact shared-library inventory is incomplete")
    for entry in libraries:
        require(sha256(library_root / entry["file"]) == entry["sha256"], "artifact library changed")
    dependency = receipt.get("runtime_dependency", {})
    runtime_path = Path(dependency.get("library_path", ""))
    require(runtime_path.is_file() and sha256(runtime_path) == dependency.get("library_sha256"),
            "artifact runtime dependency changed")
    require(dependency.get("library_sha256") ==
            "730694c87a99a1e512562bd36d005c121610531c146e4317be0e1a8d53882b18",
            "artifact runtime differs from the verified libz3 dependency")
    members = {"source.tar.gz": sha256(archive),
               "native.log": receipt["original_native_log_sha256"],
               **{f"native-tools/{name}": sha256(tools[key]) for key, name in
                  (("nodalc", "nodalc"), ("translate", "nodal-translate"))},
               **{f"native-tools/lib/{entry['file']}": entry["sha256"] for entry in libraries}}
    zip_proof = validate_artifact_zip(artifact_zip, receipt, members)
    environment["LD_LIBRARY_PATH"] = os.pathsep.join((str(library_root), str(runtime_path.parent)))
    return {"mode": "original-artifact-unchanged-native-source", "receipt": receipt,
            "receipt_sha256": sha256(receipt_path), "artifact_zip": zip_proof,
            "limit": "Fresh input execution only; no candidate native rebuild or old CI relabeling."}, environment


def validate_artifact_identity(receipt: dict) -> None:
    for field, value in HISTORICAL_NATIVE_ARTIFACT.items():
        require(receipt.get(field) == value, f"authenticated original artifact identity differs: {field}")


def validate_artifact_zip(path: Path | None, receipt: dict, members: dict[str, str]) -> dict:
    require(path is not None and path.is_file(), "original authenticated artifact ZIP is required")
    digest = sha256(path)
    require(digest == HISTORICAL_NATIVE_ARTIFACT["artifact_zip_sha256"] == receipt["artifact_zip_sha256"],
            "original authenticated artifact ZIP hash differs")
    with zipfile.ZipFile(path) as bundle:
        names = [entry.filename for entry in bundle.infolist() if not entry.is_dir()]
        require(len(names) == len(set(names)), "original artifact has duplicate ZIP entries")
        libraries = {name for name in names if name.startswith("native-tools/lib/")}
        require(libraries == {name for name in members if name.startswith("native-tools/lib/")},
                "original artifact library membership differs")
        for name, expected in members.items():
            require(name in names, f"original artifact is missing {name}")
            with bundle.open(name) as handle:
                actual = hashlib.file_digest(handle, "sha256").hexdigest()
            require(actual == expected, f"executable/source does not match original artifact member: {name}")
        source_identity_text = (receipt["actual_checkout_sha"] + "\n" +
                                receipt["actual_checkout_tree_sha"] + "\n")
        require("source-identity.txt" in names and
                bundle.read("source-identity.txt").decode() == source_identity_text,
                "original artifact checkout identity differs")
    return {"path": str(path), "sha256": digest, "members_sha256": members}


def archive_inventory(archive: Path) -> dict:
    files, nodes = {}, {}
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle.getmembers():
            if member.isdir():
                continue
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and ".." not in path.parts and str(path) not in files,
                    "unsafe or duplicate archive source path")
            if member.issym():
                content, mode = member.linkname.encode(), "120000"
            else:
                require(member.isfile(), "unsupported source archive member")
                handle = bundle.extractfile(member)
                require(handle is not None, "unreadable archived source")
                content = handle.read()
                mode = "100755" if member.mode & 0o111 else "100644"
            blob = hashlib.sha1(f"blob {len(content)}\0".encode() + content).hexdigest()
            files[str(path)] = {"mode": mode, "git_blob": blob,
                                "sha256": hashlib.sha256(content).hexdigest()}
            cursor = nodes
            for part in path.parts[:-1]:
                cursor = cursor.setdefault(part, {})
                require(isinstance(cursor, dict), "conflicting archive source paths")
            cursor[path.name] = (mode, blob)

    def tree(directory):
        rows = []
        for name, entry in directory.items():
            mode, digest = ("40000", tree(entry)) if isinstance(entry, dict) else entry
            key = name + "/" if mode == "40000" else name
            rows.append((key.encode(), mode.encode() + b" " + name.encode() + b"\0" + bytes.fromhex(digest)))
        data = b"".join(row[1] for row in sorted(rows))
        return hashlib.sha1(f"tree {len(data)}\0".encode() + data).hexdigest()
    return {"tree": tree(nodes), "files": files}


class ExperimentRunner:
    def __init__(self, out: Path):
        require(not out.exists(), "experiment output directory must be new; prior evidence is immutable")
        out.mkdir(parents=True)
        self.out = out
        self.report = {"schema": SCHEMA, "status": "preparing", "commands": []}
        self.save()

    def save(self):
        write_json(self.out / "results.json", self.report)

    def command(self, label: str, argv: list[str], cwd: Path, *, env=None,
                timeout: int = 240, peak_rss: bool = False, nonempty: bool = False) -> dict:
        logs = self.out / "logs"
        logs.mkdir(exist_ok=True)
        stdout, stderr = logs / f"{label}.stdout", logs / f"{label}.stderr"
        command = list(argv)
        rss = logs / f"{label}.rss-kib"
        if peak_rss:
            require(Path("/usr/bin/time").is_file(), "per-process RSS measurement is unavailable")
            command = ["/usr/bin/time", "-f", "%M", "-o", str(rss), *command]
        entry = {"label": label, "argv": argv, "executed_argv": command, "cwd": str(cwd),
                 "environment": env or {}, "status": "running", "timeout_seconds": timeout,
                 "stdout": str(stdout.relative_to(self.out)), "stderr": str(stderr.relative_to(self.out))}
        self.report["commands"].append(entry)
        self.save()
        started = time.perf_counter_ns()
        process = subprocess.Popen(command, cwd=cwd, env={**os.environ, **(env or {})},
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            output, error = process.communicate(timeout=timeout)
            entry["status"] = "completed"
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            output, error = process.communicate()
            entry["status"] = "timed-out"
        entry.update({"wall_nanos": time.perf_counter_ns() - started, "exit_code": process.returncode})
        stdout.write_bytes(output)
        stderr.write_bytes(error)
        entry.update({"stdout_sha256": sha256(stdout), "stderr_sha256": sha256(stderr)})
        if rss.is_file() and re.fullmatch(r"\d+\s*", rss.read_text()):
            entry["peak_rss_bytes"] = int(rss.read_text()) * 1024
        self.save()
        require(entry["status"] == "completed" and process.returncode == 0,
                f"{label} failed; original output retained in {stdout} and {stderr}")
        require(not nonempty or bool(output.strip()), f"{label} returned empty required output")
        return {**entry, "output": output, "error": error}

    def prepare_build(self, role: str, root: Path, experiment: dict) -> dict:
        env = {"NODAL_WORKSPACE": str(root)}
        self.command(f"{role}-setup-compile", [str(root / "mill"), "-i", "core.scala.testkit.test.compile"],
                     root, env=env, timeout=experiment["timeouts_seconds"]["compile"])
        cp = self.command(f"{role}-classpath", [str(root / "mill"), "-i", "show",
                          "core.scala.testkit.test.runClasspath"], root, env=env)
        classpath = decode_mill_paths(cp["output"].decode())
        require(any(path.is_relative_to(root / "out") for path in classpath),
                "testkit classpath lacks this role's compiled classes")
        fixture_paths = []
        for target in ("compileClasspath", "localRunClasspath"):
            result = self.command(f"{role}-fixture-{target}", [str(root / "mill"), "-i", "show",
                                  f"core.scala.testkit.test.{target}"], root, env=env)
            fixture_paths.extend(decode_mill_paths(result["output"].decode()))
        fixture_paths = list(dict.fromkeys(fixture_paths))
        compiler = self.command(f"{role}-compiler-classpath", [str(root / "mill"), "-i", "show",
                                "core.scala.api.scalaCompilerClasspath"], root, env=env)
        compiler_paths = decode_mill_paths(compiler["output"].decode())
        require(any("scala3-compiler_3-3.8.4" in str(path) for path in compiler_paths),
                "wrong Scala compiler toolchain")
        plugin_result = self.command(f"{role}-plugin-jar", [str(root / "mill"), "-i", "show",
                                     "core.scala.constructorPlugin.jar"], root, env=env)
        plugin_paths = decode_mill_paths(plugin_result["output"].decode())
        require(len(plugin_paths) == 1 and plugin_paths[0].is_file(), "missing production constructor plugin")
        plugin = {**path_digest(plugin_paths[0]), "entry_content_sha256": jar_content_digest(plugin_paths[0]),
                  "source_tree": git(root, "rev-parse", "HEAD:core/scala/frontend/plugin")}
        settings = self.command(f"{role}-jdk-settings", [str(root / "mill"), "-i", "java",
                                "-XshowSettings:properties", "-version"], root, env=env)
        match = re.search(r"^\s*java.home = (.+)$", (settings["output"] + settings["error"]).decode(), re.M)
        require(match is not None, "Mill did not expose its managed JDK")
        java = Path(match.group(1).strip()) / "bin/java"
        version = self.command(f"{role}-jdk-version", [str(java), "-version"], root, env=env)
        require(re.search(rb'(?:openjdk|java) version "25(?:[.\"\s])',
                          version["output"] + version["error"]) is not None, "wrong managed JDK major")
        prefix = [str(java), *experiment["jvm_options"], "-cp", os.pathsep.join(map(str, classpath)),
                  experiment["main_class"]]
        inventory = self.command(f"{role}-probe-inventory", [*prefix, "--inventory"], root, env=env,
                                 nonempty=True)
        observed = metadata_value(json.loads(inventory["output"]))
        require(observed == {"cases": experiment["cases"], "warmups": 2, "warm_samples": 5},
                "compiled probe/workload manifest disagree")
        return {"prefix": prefix, "env": env, "java": path_digest(java),
                "classpath": [path_digest(path) for path in classpath],
                "fixture_classpath": [path_digest(path) for path in fixture_paths],
                "compiler_classpath": [path_digest(path) for path in compiler_paths], "plugin": plugin,
                "setup_limit": "Mill/dependency resolution timings are setup, not compared compile measurements."}

    def clean_fixture_compile(self, epoch: int, pair: int, role: str, root: Path,
                              build: dict, experiment: dict) -> list[str]:
        label = f"e{epoch}-p{pair}-{role}-fixture-compile"
        directory = self.out / "compiled-fixtures" / f"epoch-{epoch}" / f"pair-{pair}" / role
        require(not directory.exists(), "timed compilation output must be new and empty")
        directory.mkdir(parents=True)
        runtime_cp = os.pathsep.join(entry["path"] for entry in build["classpath"])
        fixture_cp = os.pathsep.join(entry["path"] for entry in build["fixture_classpath"])
        compiler_cp = os.pathsep.join(entry["path"] for entry in build["compiler_classpath"])
        argv = [build["java"]["path"], *experiment["jvm_options"], "-cp", compiler_cp,
                "dotty.tools.dotc.Main", "-classpath", fixture_cp, "-d", str(directory),
                "-deprecation", "-feature", "-unchecked", "-Wunused:all", "-Werror",
                f"-Xplugin:{build['plugin']['path']}", "-Xplugin-require:nodal-constructor",
                *[str(root / path) for path in experiment["overlay_files"]]]
        command = self.command(label, argv, root, env=build["env"], peak_rss=True,
                               timeout=experiment["timeouts_seconds"]["compile"])
        for filename in ("ConstructionParityProbe.class", "ConstructionParityProbe$.class",
                         "ConstructionRecordJson$.class", "ParityDeepHierarchy.class"):
            require((directory / "nodal/internal/testkit" / filename).is_file(),
                    f"clean fixture compile is missing required class {filename}")
        inventory = path_digest(directory)
        record = {"epoch": epoch, "pair": pair, "role": role, "wall_nanos": command["wall_nanos"],
                  "peak_rss_bytes": command.get("peak_rss_bytes"), "classes": inventory,
                  "boundary": "fresh JVM dotc; identical probe sources; empty destination; resolved dependencies"}
        self.report.setdefault("compile_trials", []).append(record)
        self.save()
        return [build["java"]["path"], *experiment["jvm_options"], "-cp",
                os.pathsep.join((str(directory), runtime_cp)), experiment["main_class"]]

    def execute_trials(self, epoch: int, roots: dict, builds: dict, native_env: dict,
                       tools: dict, experiment: dict) -> list[dict]:
        trials = []
        for pair, order in enumerate(experiment["protocol"]["pair_order"]):
            prefixes = {}
            for role in order:
                prefix = self.clean_fixture_compile(epoch, pair, role, roots[role], builds[role], experiment)
                prefixes[role] = prefix
                startup = self.command(f"e{epoch}-p{pair}-{role}-startup", [*prefix, "--startup"], roots[role],
                                       env=builds[role]["env"], peak_rss=True,
                                       timeout=experiment["timeouts_seconds"]["probe"])
                sentinel = re.fullmatch(rb"F160_STARTUP_PASS (\d+)\s*", startup["output"])
                require(sentinel is not None, "empty-workload startup probe did not execute")
                self.report.setdefault("startup_trials", []).append({
                    "epoch": epoch, "pair": pair, "role": role, "wall_nanos": startup["wall_nanos"],
                    "peak_rss_bytes": startup.get("peak_rss_bytes"), "jvm_uptime_millis": int(sentinel.group(1))})
                self.save()
            for case in experiment["cases"]:
                for role in order:
                    prefix = prefixes[role]
                    label = f"e{epoch}-p{pair}-{role}-{case['id']}"
                    directory = self.out / "trials" / f"epoch-{epoch}" / f"pair-{pair}" / role / case["id"]
                    print(f"F160_CASE {label}", flush=True)
                    command = self.command(label, [*prefix, case["id"], str(directory)],
                                           roots[role], env=builds[role]["env"], peak_rss=True,
                                           timeout=experiment["timeouts_seconds"]["probe"])
                    report = load_probe(directory / "measurements.json", case, experiment)
                    require(command["output"].decode().splitlines().count(
                        f"F160_PROBE_PASS {case['id']} 8") == 1, "probe success sentinel missing")
                    native = {}
                    if case["target"]:
                        normalized = self.command(label + "-native", [str(tools["nodalc"]),
                            "--pass-pipeline=builtin.module(nodal-gate-default)", str(directory / "source.mlir")],
                            roots[role], env=native_env, timeout=experiment["timeouts_seconds"]["native"], nonempty=True)
                        (directory / "normalized.mlir").write_bytes(normalized["output"])
                        emitted = self.command(label + "-target", [str(tools["translate"]),
                            "--nodal-to-verilog-a", str(directory / "normalized.mlir")], roots[role],
                            env=native_env, timeout=experiment["timeouts_seconds"]["native"], nonempty=True)
                        (directory / "output.va").write_bytes(emitted["output"])
                        native = {"verification_nanos": normalized["wall_nanos"],
                                  "translation_nanos": emitted["wall_nanos"]}
                    artifacts = compare_artifacts(directory, directory, artifact_names(case, experiment))
                    trials.append({"epoch": epoch, "pair": pair, "role": role, "case": case["id"],
                                   "directory": str(directory.relative_to(self.out)), "probe": report,
                                   "process_wall_nanos": command["wall_nanos"],
                                   "peak_rss_bytes": command.get("peak_rss_bytes"),
                                   "native": native, "artifacts": artifacts})
                    self.report["trials"] = self.report.get("trials", []) + [trials[-1]]
                    self.save()
        return trials


def assess_trials(trials: list[dict], experiment: dict, out: Path,
                  compile_trials: list[dict] | None = None, startup_trials: list[dict] | None = None) -> dict:
    expected = {(pair, role, case["id"]) for pair in range(3)
                for role in ("baseline", "candidate") for case in experiment["cases"]}
    observed = [(trial["pair"], trial["role"], trial["case"]) for trial in trials]
    require(set(observed) == expected and len(observed) == len(expected), "trial inventory is incomplete")
    comparisons, measurements = [], []
    for case in experiment["cases"]:
        group = {role: [trial for trial in trials if trial["case"] == case["id"] and trial["role"] == role]
                 for role in ("baseline", "candidate")}
        reference = out / group["baseline"][0]["directory"]
        names = artifact_names(case, experiment)
        for trial in group["baseline"] + group["candidate"]:
            comparisons.append({"case": case["id"], "pair": trial["pair"], "role": trial["role"],
                                "artifacts": compare_artifacts(reference, out / trial["directory"], names)})
        if case["rejection"] is not None:
            continue  # Failed construction is correctness evidence, not a successful throughput sample.
        phases = list(PHASES) + (["bridge"] if case["bridge"] else [])
        for kind in ("cold", "warm"):
            for phase in phases:
                for metric, budget in (("wallNanos", "cold_wall_nanos" if kind == "cold" else "warm_wall_nanos"),
                                       ("allocatedBytes", "allocated_bytes")):
                    samples = {}
                    for role, runs in group.items():
                        samples[role] = [sample[phase][metric] for trial in runs
                                         for sample in trial["probe"]["samples"] if sample["kind"] == kind]
                    result = assess_budget(samples["baseline"], samples["candidate"], experiment["budgets"][budget])
                    measurements.append({"case": case["id"], "kind": kind, "phase": phase,
                                         "metric": metric, **result})
        for metric, budget in (("peak_rss_bytes", "peak_rss_bytes"),
                               ("process_wall_nanos", "cold_wall_nanos")):
            result = assess_budget([trial.get(metric) for trial in group["baseline"]],
                                   [trial.get(metric) for trial in group["candidate"]], experiment["budgets"][budget])
            measurements.append({"case": case["id"], "phase": "process", "metric": metric, **result})
        if case["target"]:
            for metric in ("verification_nanos", "translation_nanos"):
                result = assess_budget([trial["native"].get(metric) for trial in group["baseline"]],
                                       [trial["native"].get(metric) for trial in group["candidate"]],
                                       experiment["budgets"]["cold_wall_nanos"])
                measurements.append({"case": case["id"], "phase": "native", "metric": metric, **result})
    expected_stages = {(pair, role) for pair in range(3) for role in ("baseline", "candidate")}
    for phase, records in (("clean-fixture-compilation", compile_trials), ("empty-jvm-startup", startup_trials)):
        records = records or []
        observed_stages = [(record["pair"], record["role"]) for record in records]
        require(set(observed_stages) == expected_stages and len(observed_stages) == len(expected_stages),
                f"{phase} measurement inventory is incomplete")
        for metric, budget in (("wall_nanos", "cold_wall_nanos"), ("peak_rss_bytes", "peak_rss_bytes")):
            result = assess_budget([record.get(metric) for record in records if record["role"] == "baseline"],
                                   [record.get(metric) for record in records if record["role"] == "candidate"],
                                   experiment["budgets"][budget])
            measurements.append({"case": "paired-setup", "phase": phase, "metric": metric, **result})
    statuses = {entry["status"] for entry in measurements}
    status = "incomplete" if "incomplete" in statuses else "regression" if "regression" in statuses else \
             "noisy" if "noisy" in statuses else "pass"
    require(bool(measurements), "no before/after measurements were assessed")
    return {"status": status, "comparisons": comparisons, "measurements": measurements}


def run(args) -> int:
    experiment = read_experiment()
    candidate, baseline, out = args.candidate_root.resolve(), args.baseline_root.resolve(), args.out.resolve()
    require(candidate != baseline and not baseline.is_relative_to(candidate) and not out.is_relative_to(candidate)
            and not out.is_relative_to(baseline), "baseline and evidence must be outside both compiler source trees")
    runner = ExperimentRunner(out)
    try:
        candidate_identity = source_identity(candidate)
        require(not candidate_identity["untracked"], "candidate source has untracked files")
        if not baseline.exists():
            runner.command("prepare-baseline", ["git", "worktree", "add", "--detach", str(baseline), BASELINE],
                           candidate)
        baseline_identity = source_identity(baseline)
        require(baseline_identity["commit"] == BASELINE and baseline_identity["tree"] == BASELINE_TREE,
                "retained baseline identity does not match the experiment")
        require(set(baseline_identity["untracked"]).issubset(set(experiment["overlay_files"])),
                "baseline contains unapproved untracked source")
        overlays = {}
        for relative in experiment["overlay_files"]:
            require(git(candidate, "ls-files", "--", relative) == relative, "candidate overlay must be committed")
            original, destination = candidate / relative, baseline / relative
            require(not destination.is_file() or destination.read_bytes() == original.read_bytes(),
                    "existing baseline overlay differs; preserve it and use a fresh baseline worktree")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, destination)
            overlays[relative] = sha256(original)
        invariant_hashes = {}
        for relative in experiment["invariant_toolchain_files"]:
            require((candidate / relative).read_bytes() == (baseline / relative).read_bytes(),
                    f"baseline/candidate pinned tool configuration differs: {relative}")
            invariant_hashes[relative] = sha256(candidate / relative)
        for relative in experiment["fixture_source_roots"]:
            require(not git(candidate, "diff", BASELINE, "HEAD", "--", relative,
                            *[f":(exclude){entry}" for entry in experiment["overlay_files"]]),
                    "accepted public fixture sources changed")
        roots = {"baseline": baseline, "candidate": candidate}
        identities = {role: source_identity(root) for role, root in roots.items()}
        rss_tool = Path("/usr/bin/time")
        require(rss_tool.is_file() and os.access(rss_tool, os.X_OK),
                "per-process RSS measurement is unavailable")
        inputs = {"experiment_sha256": sha256(EXPERIMENT), "runner_sha256": sha256(Path(__file__)),
                  "overlay_sha256": overlays, "invariant_toolchain_sha256": invariant_hashes,
                  "rss_measurement_tool": path_digest(rss_tool),
                  "sources": identities, "experiment": experiment}
        runner.report["inputs"] = inputs
        runner.report["environment"] = {"platform": sys.platform, "python": sys.version,
                                         "cpu_count": os.cpu_count(),
                                         "uname": list(os.uname()) if hasattr(os, "uname") else None}
        write_json(out / "input-manifest.json", inputs)  # Written before any compilation or trial.
        runner.save()
        tools = {"nodalc": args.nodalc.resolve(), "translate": args.translate.resolve()}
        require(all(path.is_file() and os.access(path, os.X_OK) for path in tools.values()),
                "required native compiler or translator is unavailable")
        receipt = args.native_build_receipt or args.native_artifact_receipt
        require(receipt is not None, "native executable provenance receipt is required")
        artifact_zip = args.native_artifact_zip.resolve() if args.native_artifact_zip is not None else None
        proof, native_env = validate_native_receipt(receipt.resolve(), args.native_artifact_receipt is not None,
                                                   candidate, baseline, identities, experiment, tools, artifact_zip)
        runner.report["native_provenance"] = proof
        builds = {role: runner.prepare_build(role, roots[role], experiment) for role in ("baseline", "candidate")}
        require(builds["baseline"]["java"] == builds["candidate"]["java"], "roles use different managed JDK binaries")
        require(builds["baseline"]["compiler_classpath"] == builds["candidate"]["compiler_classpath"],
                "roles use different Scala compiler tools")
        require(builds["baseline"]["plugin"]["source_tree"] == builds["candidate"]["plugin"]["source_tree"] and
                builds["baseline"]["plugin"]["entry_content_sha256"] == builds["candidate"]["plugin"]["entry_content_sha256"],
                "roles use different constructor-plugin sources or executable jar contents")
        runner.report["builds"] = builds
        runner.report["status"] = "running"
        runner.save()
        assessments = []
        for epoch in range(1 + experiment["protocol"]["max_noisy_repeats"]):
            trials = runner.execute_trials(epoch, roots, builds, native_env, tools, experiment)
            compile_trials = [record for record in runner.report["compile_trials"] if record["epoch"] == epoch]
            startup_trials = [record for record in runner.report["startup_trials"] if record["epoch"] == epoch]
            assessment = assess_trials(trials, experiment, out, compile_trials, startup_trials)
            assessments.append(assessment)
            runner.report["assessments"] = assessments
            runner.save()
            if assessment["status"] != "noisy":
                break
        for role, root in roots.items():
            require(source_identity(root) == identities[role], "source identity changed during experiment")
            require(all(sha256(root / name) == digest for name, digest in overlays.items()),
                    "harness overlay changed during experiment")
            validate_build_identity(builds[role])
        for record in runner.report["compile_trials"]:
            require(path_digest(Path(record["classes"]["path"])) == record["classes"],
                    "freshly compiled workload classes changed during experiment")
        require(sha256(EXPERIMENT) == inputs["experiment_sha256"] and sha256(Path(__file__)) == inputs["runner_sha256"],
                "experiment definition changed after pinning")
        require(path_digest(rss_tool) == inputs["rss_measurement_tool"],
                "RSS measurement instrument changed during experiment")
        final_proof, final_native_env = validate_native_receipt(
            receipt.resolve(), args.native_artifact_receipt is not None,
            candidate, baseline, identities, experiment, tools, artifact_zip)
        require(final_proof == proof and final_native_env == native_env,
                "native provenance or execution environment changed during experiment")
        status = assessments[-1]["status"]
        runner.report["status"] = "passed" if status == "pass" else "incomplete" if status == "noisy" else status
        runner.report["external_tools"] = {"status": "not-executed", "reason": experiment["external_tool_status"]}
        runner.report["qualification_limit"] = "Actual execution evidence; review, full CI, merge and accepted closure remain separate."
        runner.save()
        print(f"F160_EXPERIMENT_{runner.report['status'].upper()} {out / 'results.json'}", flush=True)
        return 0 if runner.report["status"] == "passed" else 1
    except (EvidenceError, OSError, ValueError, KeyError) as failure:
        runner.report.update({"status": "failed", "failure": str(failure)})
        runner.save()
        print(f"F160_EXPERIMENT_FAILED: {failure}", file=sys.stderr)
        return 1


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--candidate-root", type=Path, required=True)
    result.add_argument("--baseline-root", type=Path, required=True)
    result.add_argument("--out", type=Path, required=True)
    result.add_argument("--nodalc", type=Path, required=True)
    result.add_argument("--translate", type=Path, required=True)
    receipts = result.add_mutually_exclusive_group(required=True)
    receipts.add_argument("--native-build-receipt", type=Path)
    receipts.add_argument("--native-artifact-receipt", type=Path)
    result.add_argument("--native-artifact-zip", type=Path,
                        help="Required original authenticated ZIP when using --native-artifact-receipt")
    return result


if __name__ == "__main__":
    raise SystemExit(run(parser().parse_args()))
