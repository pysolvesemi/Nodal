"""Integrity controls for the F-160 differential experiment, not compiler execution receipts."""
from __future__ import annotations

import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "tests/compiler/fixtures/increment160/run_parity.py"
SPEC = importlib.util.spec_from_file_location("increment160_parity", PATH)
assert SPEC is not None and SPEC.loader is not None
PARITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARITY)


class ExperimentIntegrityTests(unittest.TestCase):
    def test_predeclared_cases_budgets_and_trial_protocol(self):
        experiment = PARITY.read_experiment()
        self.assertEqual(len(experiment["cases"]), 55)
        self.assertEqual(len([case for case in experiment["cases"] if case["size"]]), 10)
        self.assertTrue(any(case["id"] == "detached-endpoint" for case in experiment["cases"]))

    def mutated_experiment_rejects(self, change):
        experiment = PARITY.read_experiment()
        change(experiment)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "experiment.json"
            path.write_text(json.dumps(experiment))
            with self.assertRaises(PARITY.EvidenceError):
                PARITY.read_experiment(path)

    def test_missing_or_duplicate_cases_cannot_receive_pass_credit(self):
        self.mutated_experiment_rejects(lambda value: value["cases"].pop())
        self.mutated_experiment_rejects(lambda value: value["cases"].__setitem__(1, value["cases"][0]))

    def test_reduced_scale_and_trial_counts_are_rejected(self):
        self.mutated_experiment_rejects(lambda value: value["cases"][-1].update(size=12))
        self.mutated_experiment_rejects(lambda value: value["protocol"].update(warm_iterations=1))
        self.mutated_experiment_rejects(lambda value: value["protocol"].update(max_noisy_repeats=100))

    def test_changed_budget_baseline_or_overlay_is_rejected(self):
        self.mutated_experiment_rejects(lambda value: value["budgets"]["warm_wall_nanos"].update(relative=1))
        self.mutated_experiment_rejects(lambda value: value.update(baseline_commit="0" * 40))
        self.mutated_experiment_rejects(lambda value: value["overlay_files"].append(
            "core/scala/api/src/nodal/CandidateApi.scala"))

    def test_artifact_source_toolchain_and_entrypoint_boundaries_are_pinned(self):
        for field in ("required_artifacts", "fixture_source_roots", "invariant_toolchain_files",
                      "native_source_roots", "jvm_options"):
            with self.subTest(field=field):
                self.mutated_experiment_rejects(lambda value: value[field].pop())
        for field in ("main_class", "scala_version", "mill_version", "jdk_major"):
            with self.subTest(field=field):
                self.mutated_experiment_rejects(lambda value: value.pop(field))

    def test_no_measurements_and_unavailable_counters_are_incomplete(self):
        budget = {"relative": 0.25, "absolute": 10}
        for first, second in (([], []), ([1], []), ([None], [1]), ([1], [float("nan")]), ([-1], [1])):
            self.assertEqual(PARITY.assess_budget(first, second, budget)["status"], "incomplete")

    def test_absolute_floor_and_material_regression(self):
        budget = {"relative": 0.25, "absolute": 10}
        self.assertEqual(PARITY.assess_budget([1, 1, 1], [10, 10, 10], budget)["status"], "pass")
        self.assertEqual(PARITY.assess_budget([100, 100, 100], [150, 150, 150], budget)["status"],
                         "regression")

    def test_noisy_excess_is_not_automatically_passed(self):
        result = PARITY.assess_budget([90, 100, 110], [110, 130, 150],
                                     {"relative": 0.25, "absolute": 10})
        self.assertEqual(result["status"], "noisy")
        self.assertEqual(result["baseline_samples"], [90, 100, 110])

    def test_artifacts_preserve_order_paths_and_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            left.mkdir()
            right.mkdir()
            source = b'{"root":"a.scala:3:2","ordered":[1,2]}\n'
            (left / "record.json").write_bytes(source)
            (right / "record.json").write_bytes(source)
            self.assertEqual(PARITY.compare_artifacts(left, right, ["record.json"])[0]["bytes"], len(source))
            for changed in (source.replace(b"a.scala", b"b.scala"), source.replace(b"[1,2]", b"[2,1]"),
                            source.rstrip(), b""):
                (right / "record.json").write_bytes(changed)
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.compare_artifacts(left, right, ["record.json"])
            (right / "record.json").unlink()
            with self.assertRaises(PARITY.EvidenceError):
                PARITY.compare_artifacts(left, right, ["record.json"])

    def test_target_and_diagnostic_artifact_inventories_are_required(self):
        experiment = PARITY.read_experiment()
        target = next(case for case in experiment["cases"] if case["target"])
        rejected = next(case for case in experiment["cases"] if case["rejection"])
        self.assertIn("normalized.mlir", PARITY.artifact_names(target, experiment))
        self.assertIn("output.va", PARITY.artifact_names(target, experiment))
        self.assertIn("diagnostic.json", PARITY.artifact_names(rejected, experiment))

    def valid_probe(self):
        experiment = PARITY.read_experiment()
        case = experiment["cases"][0]
        metric = {"wallNanos": 10, "allocatedBytes": 20, "gcCollections": 0, "gcMillis": 0}
        samples = [{"kind": kind, "heapUsedBytes": 1048576, "heapPoolPeakBytes": 2097152,
                    **{phase: dict(metric) for phase in (*PARITY.PHASES, "bridge", "totalInspection")}}
                   for kind in ["cold", "warmup", "warmup", "warm", "warm", "warm", "warm", "warm"]]
        return experiment, case, {"schema": "nodal.increment160.probe.v1", "caseInfo": case,
                                  "factoryCalls": 8, "samples": samples, "javaVersion": "25.0.1"}

    def test_probe_rejects_missing_samples_factory_counts_and_phases(self):
        experiment, case, probe = self.valid_probe()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "measurements.json"
            path.write_text(json.dumps(probe))
            self.assertEqual(PARITY.load_probe(path, case, experiment)["factoryCalls"], 8)
            mutations = [lambda value: value["samples"].pop(),
                         lambda value: value.update(factoryCalls=7),
                         lambda value: value["samples"][0].pop("construction"),
                         lambda value: value["samples"][1]["bridge"].update(wallNanos=-1),
                         lambda value: value["samples"][0].pop("heapUsedBytes"),
                         lambda value: value["samples"][0].update(heapPoolPeakBytes=None),
                         lambda value: value["samples"][0]["bridge"].update(gcCollections=None),
                         lambda value: value.update(javaVersion="21.0.1")]
            for change in mutations:
                modified = copy.deepcopy(probe)
                change(modified)
                path.write_text(json.dumps(modified))
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.load_probe(path, case, experiment)

    def test_partial_trial_matrix_and_zero_job_green_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(PARITY.EvidenceError):
                PARITY.assess_trials([], PARITY.read_experiment(), Path(directory))

    def test_complete_synthetic_matrix_requires_six_compile_and_startup_measurements(self):
        experiment, _, probe = self.valid_probe()
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            artifacts = out / "synthetic-control"
            artifacts.mkdir()
            for name in {name for case in experiment["cases"]
                         for name in PARITY.artifact_names(case, experiment)}:
                (artifacts / name).write_text("synthetic comparer control; no compiler execution\n")
            trials = [{"pair": pair, "role": role, "case": case["id"],
                       "directory": "synthetic-control", "probe": probe,
                       "process_wall_nanos": 100, "peak_rss_bytes": 1024,
                       "native": {"verification_nanos": 100, "translation_nanos": 100}}
                      for pair in range(3) for role in ("baseline", "candidate")
                      for case in experiment["cases"]]
            stages = [{"pair": pair, "role": role, "wall_nanos": 100, "peak_rss_bytes": 1024}
                      for pair in range(3) for role in ("baseline", "candidate")]
            self.assertEqual(PARITY.assess_trials(trials, experiment, out, stages, stages)["status"], "pass")
            for compile_trials, startup_trials in ((stages[:-1], stages), (stages, stages[:-1])):
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.assess_trials(trials, experiment, out, compile_trials, startup_trials)
            incomplete = copy.deepcopy(stages)
            incomplete[0]["peak_rss_bytes"] = None
            self.assertEqual(PARITY.assess_trials(trials, experiment, out, incomplete, stages)["status"],
                             "incomplete")

    def test_nonzero_tool_retains_partial_output_but_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = PARITY.ExperimentRunner(Path(directory) / "evidence")
            with self.assertRaises(PARITY.EvidenceError):
                runner.command("failure", [sys.executable, "-c",
                    "import sys; print('partial target'); sys.exit(2)"], Path(directory))
            self.assertEqual(runner.report["commands"][0]["exit_code"], 2)
            self.assertEqual((runner.out / "logs/failure.stdout").read_text(), "partial target\n")

    def test_zero_exit_empty_target_is_not_execution_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = PARITY.ExperimentRunner(Path(directory) / "evidence")
            with self.assertRaises(PARITY.EvidenceError):
                runner.command("empty", [sys.executable, "-c", "pass"], Path(directory), nonempty=True)

    def test_existing_evidence_directory_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(PARITY.EvidenceError):
                PARITY.ExperimentRunner(Path(directory))

    def test_metadata_decode_does_not_touch_source_strings(self):
        value = {"$type": "Diagnostic", "path": {"$some": "../../source/A.scala:3:4"},
                 "missing": {"$none": True}}
        self.assertEqual(PARITY.metadata_value(value), {"path": "../../source/A.scala:3:4", "missing": None})

    def test_archive_tree_and_blob_inventory_retain_executable_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                for name, data, mode in (("a.txt", b"one\n", 0o644), ("bin/run", b"two\n", 0o755)):
                    member = tarfile.TarInfo(name)
                    member.size, member.mode = len(data), mode
                    bundle.addfile(member, io.BytesIO(data))
            inventory = PARITY.archive_inventory(archive)
            self.assertEqual(inventory["files"]["bin/run"]["mode"], "100755")
            self.assertEqual(inventory["files"]["a.txt"]["mode"], "100644")
            self.assertEqual(len(inventory["tree"]), 40)
            with tarfile.open(archive, "w:gz") as bundle:
                member = tarfile.TarInfo("../escaped")
                member.size = 1
                bundle.addfile(member, io.BytesIO(b"x"))
            with self.assertRaises(PARITY.EvidenceError):
                PARITY.archive_inventory(archive)

    def test_original_native_run_job_and_artifact_identity_are_complete(self):
        original = copy.deepcopy(PARITY.HISTORICAL_NATIVE_ARTIFACT)
        PARITY.validate_artifact_identity(original)
        for field in original:
            with self.subTest(field=field):
                changed = copy.deepcopy(original)
                changed.pop(field)
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.validate_artifact_identity(changed)
        changed = copy.deepcopy(original)
        changed["original_native_job"]["native_tools_packaging_step"]["conclusion"] = "failure"
        with self.assertRaises(PARITY.EvidenceError):
            PARITY.validate_artifact_identity(changed)

    def test_native_zip_members_are_bound_to_authenticated_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle_path = Path(directory) / "synthetic-native-tools.zip"
            receipt = copy.deepcopy(PARITY.HISTORICAL_NATIVE_ARTIFACT)
            members = {"source.tar.gz": b"synthetic source", "native-tools/nodalc": b"synthetic tool",
                       "native-tools/lib/dependency.so": b"synthetic library", "native.log": b"synthetic log"}
            with zipfile.ZipFile(bundle_path, "w") as bundle:
                for name, content in members.items():
                    bundle.writestr(name, content)
                bundle.writestr("source-identity.txt", receipt["actual_checkout_sha"] + "\n" +
                                receipt["actual_checkout_tree_sha"] + "\n")
            hashes = {name: PARITY.hashlib.sha256(content).hexdigest() for name, content in members.items()}
            receipt["artifact_zip_sha256"] = PARITY.sha256(bundle_path)
            # The fake envelope is local to this control; it supplies no native execution evidence.
            with mock.patch.dict(PARITY.HISTORICAL_NATIVE_ARTIFACT,
                                 {"artifact_zip_sha256": receipt["artifact_zip_sha256"]}):
                PARITY.validate_artifact_zip(bundle_path, receipt, hashes)
                for changed in ({**hashes, "native-tools/nodalc": "0" * 64},
                                {**hashes, "missing-tool": "0" * 64},
                                {key: value for key, value in hashes.items() if "/lib/" not in key}):
                    with self.assertRaises(PARITY.EvidenceError):
                        PARITY.validate_artifact_zip(bundle_path, receipt, changed)
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.validate_artifact_zip(None, receipt, hashes)
                with zipfile.ZipFile(bundle_path, "a") as bundle:
                    bundle.writestr("replacement", "different unauthenticated package")
                receipt["artifact_zip_sha256"] = PARITY.sha256(bundle_path)
                with self.assertRaises(PARITY.EvidenceError):
                    PARITY.validate_artifact_zip(bundle_path, receipt, hashes)

    def test_managed_java_compiler_plugin_and_runtime_hashes_remain_pinned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {key: root / key for key in ("java", "compiler", "runtime", "fixture", "plugin")}
            for key, path in paths.items():
                path.write_text(key)
            build = {"java": PARITY.path_digest(paths["java"]),
                     "compiler_classpath": [PARITY.path_digest(paths["compiler"])],
                     "fixture_classpath": [PARITY.path_digest(paths["fixture"])],
                     "classpath": [PARITY.path_digest(paths["runtime"])],
                     "plugin": PARITY.path_digest(paths["plugin"])}
            PARITY.validate_build_identity(build)
            for key, path in paths.items():
                with self.subTest(tool=key):
                    path.write_text("replacement")
                    with self.assertRaises(PARITY.EvidenceError):
                        PARITY.validate_build_identity(build)
                    path.write_text(key)

    def test_clean_compilation_uses_owner_compile_dependencies_and_new_classes_execute_first(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = PARITY.ExperimentRunner(root / "evidence")
            experiment = PARITY.read_experiment()
            build = {"java": {"path": "synthetic-java"}, "env": {},
                     "compiler_classpath": [{"path": "compiler-tools.jar"}],
                     "fixture_classpath": [{"path": "owner-compile-dependencies.jar"}],
                     "classpath": [{"path": "owner-runtime-dependencies.jar"}],
                     "plugin": {"path": "constructor-plugin.jar"}}

            def compile_control(label, argv, cwd, **options):
                self.assertEqual(argv[argv.index("-classpath") + 1], "owner-compile-dependencies.jar")
                self.assertIn("-Werror", argv)
                self.assertIn("-Xplugin-require:nodal-constructor", argv)
                destination = Path(argv[argv.index("-d") + 1])
                self.assertEqual(list(destination.iterdir()), [])
                classes = destination / "nodal/internal/testkit"
                classes.mkdir(parents=True)
                for filename in ("ConstructionParityProbe.class", "ConstructionParityProbe$.class",
                                 "ConstructionRecordJson$.class", "ParityDeepHierarchy.class"):
                    (classes / filename).write_bytes(b"synthetic command control; never executed")
                return {"wall_nanos": 100, "peak_rss_bytes": 1024}

            with mock.patch.object(runner, "command", side_effect=compile_control):
                prefix = runner.clean_fixture_compile(0, 0, "candidate", root, build, experiment)
            expected_first = runner.report["compile_trials"][0]["classes"]["path"]
            self.assertEqual(prefix[prefix.index("-cp") + 1],
                             expected_first + PARITY.os.pathsep + "owner-runtime-dependencies.jar")


if __name__ == "__main__":
    unittest.main()
