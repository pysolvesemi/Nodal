from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_backend_capability_registry.py"
SPEC = importlib.util.spec_from_file_location("backend_capability_registry", SCRIPT)
assert SPEC and SPEC.loader
REGISTRY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REGISTRY)

# Pre-F47 operation-name admission, frozen independently of the generated table.
DIRECT_OPERATIONS = {
    "nodal." + name for name in """
    unit module nature nature_import discipline discipline_import parameter
    const_literal const_parameter_ref const_expr parameter_value parameter_constraint
    parameter_envelope parameter_override terminal terminal_array instance instance_terminal
    instance_terminal_array node node_array connect connect_array branch generate analog
    real_literal analog_integer_literal parameter_ref access terminal_access port_flow_access
    analog_add analog_sub analog_mul analog_div analog_neg analog_noise analog_transfer
    analog_function analog_user_function analog_function_value analog_function_return
    analog_user_call analog_analysis analog_context analog_compare analog_logic analog_select
    analog_ddt analog_idt analog_transition analog_slew analog_absdelay analog_abstime
    analog_bound_step contribute analog_held_read
    """.split()
}

# F54 is an additive typed-IR boundary, not digital target admission.
F54_DIGITAL_OPERATIONS = {
    "nodal." + name for name in """
    enum_expr digital_expr digital_storage memory digital_assign
    interface_connect interface_drive interface_observe
    net_release net_drive_level net_pass_through net_split
    """.split()
}

# Legacy 0.1 fields remain byte-for-value compatible; the feature inventory is additive.
LEGACY_CONTEXT = {
    "registry": "analog-functions-v1.json#contexts",
    "supported": ["analysis", "time", "temperature"],
    "conditional_standard": {
        "nominal_temperature": "requires simulator tnom parameter; Celsius converted to kelvin; no fallback"
    },
    "unavailable_provider": [
        "frequency", "operating_condition", "corner", "sweep_coordinate", "random_seed"
    ],
    "effect": "simulator-read",
    "independent_tool_execution": "not-implied-by-language-profile",
}
LEGACY_PROFILES = [
    {
        "id": identity,
        "translation": "nodal-to-" + identity,
        "design_kinds": kinds,
        "shaped_value_layout": layout,
        "expression_materialization": expressions,
        "naming": "semantic",
        "default_check_profile": "default",
        "target_verification": "builtin-framework-v1",
        "target_reparse": "builtin-structural-v1",
        "simulator_context": LEGACY_CONTEXT,
    }
    for identity, kinds, layout, expressions in [
        ("verilog-a", ["analog"], "scalar-or-flat", "safe-inline"),
        ("verilog-ams", ["analog", "mixed_signal"], "flat-packed", "readable"),
    ]
]
LEGACY_CATALOG = {
    "schema": 1,
    "version": "0.1",
    "public_api": "0.3-unchanged",
    "selection": "explicit-translation",
    "profiles": LEGACY_PROFILES,
    "check_profiles": ["fast", "default", "release"],
    "transaction": "clone-gate-buffer-verify-reparse-publish",
    "unsupported_feature_policy": "reject-with-stable-diagnostic",
}


# F55/F56 additions are excluded only from the immutable F47-era digest, just
# as F54 additions already were. The frozen historical digest is unchanged.
F55_F56_DIGITAL_OPERATIONS = {
    "nodal." + name for name in """
    behavioral_if behavioral_predicate collection collection_yield enum_switch
    flat_fsm hardware_loop_end iteration_index local_domain_binding memory_read
    memory_write memory_write_effect reset_asserted sequential_assert sequential_select
    sequential_update static_compare static_logic structural_if structural_yield
    """.split()
}

# F57 source forms were already executable but omitted from the catalog. Their
# analog-target admission stays as conservative as existing digital assignments.
F57_DIGITAL_OPERATIONS = {
    "nodal." + name for name in """
    async_fifo clock_gate clock_mux clock_relationship event_process
    instance_domain_binding instance_port requirement_clock requirement_reset
    reset_boundary reset_combine reset_sync waiver
    """.split()
}
F58_INTERFACE_OPERATIONS = {"nodal.interface_boundary"}
F58_GENERATED_OPERATIONS = {"nodal.generated_export", "nodal.generated_member"}

class BackendCapabilityRegistryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        (ROOT / ".work").mkdir(exist_ok=True)

    def setUp(self) -> None:
        self.catalog = json.loads((ROOT / REGISTRY.CATALOG).read_text(encoding="utf-8"))
        self.inventory = self.catalog["feature_inventory"]
        self.operations = {entry["operation"]: entry for entry in self.inventory["operations"]}

    def invalid(self, message: str) -> None:
        with self.assertRaisesRegex(REGISTRY.CatalogError, message):
            REGISTRY.validate_catalog(self.catalog)

    def test_valid_catalog_and_fresh_generated_table(self) -> None:
        self.assertIs(REGISTRY.validate_catalog(self.catalog), self.inventory)
        for output, content in REGISTRY.outputs().items():
            self.assertEqual(content, (ROOT / output).read_text(encoding="utf-8"))

    def test_preserves_all_legacy_profile_fields(self) -> None:
        legacy = {key: value for key, value in self.catalog.items()
                  if key not in {"feature_inventory", "consumer_profiles", "simulator_profiles"}}
        self.assertEqual(legacy, LEGACY_CATALOG)

    def test_preserves_the_complete_historical_feature_inventory(self) -> None:
        # Frozen F47 value identity, including every historical observation field.
        historical = copy.deepcopy(self.inventory)
        historical["operations"] = [entry for entry in historical["operations"]
                                    if entry["operation"] not in (F54_DIGITAL_OPERATIONS | F55_F56_DIGITAL_OPERATIONS |
                                        F57_DIGITAL_OPERATIONS | F58_INTERFACE_OPERATIONS | F58_GENERATED_OPERATIONS)]
        serialized = json.dumps(historical, sort_keys=True, separators=(",", ":")).encode()
        self.assertEqual(hashlib.sha256(serialized).hexdigest(),
                         "aeb2b8c8246dbe6e06432de8ff543d2024e565cbe97824eec4a05260250048ca")

    def test_f57_forms_keep_conservative_analog_target_admission(self) -> None:
        for operation in F57_DIGITAL_OPERATIONS:
            self.assertEqual(self.operations[operation], {
                "operation": operation, "feature": "digital-behavior",
                "domain": "digital", "admission": "unsupported"})

    def test_interface_projection_does_not_claim_target_lowering(self) -> None:
        self.assertEqual(self.operations["nodal.interface_boundary"], {
            "operation": "nodal.interface_boundary", "feature": "unlowered-shared-forms",
            "domain": "neutral", "admission": "unsupported"})

    def test_generated_family_forms_do_not_claim_target_lowering(self) -> None:
        for operation in F58_GENERATED_OPERATIONS:
            self.assertEqual(self.operations[operation], {
                "operation": operation, "feature": "unlowered-shared-forms",
                "domain": "neutral", "admission": "unsupported"})

    def test_f54_typed_operations_do_not_claim_analog_target_support(self) -> None:
        for operation in F54_DIGITAL_OPERATIONS:
            self.assertEqual(self.operations[operation], {
                "operation": operation,
                "feature": "digital-behavior",
                "domain": "digital",
                "admission": "unsupported",
            })

    def generated(self, serialized: bytes | None = None) -> dict[Path, str]:
        with tempfile.TemporaryDirectory(dir=ROOT / ".work") as directory:
            root = Path(directory)
            for name in ("core", "docs", "examples"):
                (root / name).symlink_to(ROOT / name, target_is_directory=True)
            original_catalog = REGISTRY.CATALOG
            REGISTRY.CATALOG = Path("catalog.json")
            try:
                data = serialized if serialized is not None else json.dumps(self.catalog).encode()
                (root / REGISTRY.CATALOG).write_bytes(data)
                return REGISTRY.outputs(root)
            finally:
                REGISTRY.CATALOG = original_catalog

    def test_consumer_profiles_have_exact_independent_pins_and_arguments(self) -> None:
        self.assertEqual(self.catalog["consumer_profiles"]["schema_version"], 1)
        self.assertEqual(self.catalog["consumer_profiles"]["profile_version"], "1")
        profiles = {entry["id"]: entry for entry in self.catalog["consumer_profiles"]["profiles"]}
        pins = {
            "openvaf-23.5.0-linux-x86_64": (
                "openvaf 23.5.0",
                "6918195bc6cca54016095923bea190f7a1d96dd8b062104c602e8c28578cb5e3"),
            "openvaf-reloaded-20260616-linux-x86_64": (
                "OpenVAF-reloaded 20260616-3-g0e83f1ed",
                "b117284d8f871dbf54609ab009cdff819f443d8d33064111b8b0782af5c7e931"),
        }
        self.assertEqual(set(profiles), set(pins))
        for identity, (version_stdout, digest) in pins.items():
            with self.subTest(profile=identity):
                profile = profiles[identity]
                self.assertEqual(profile["version_stdout"], version_stdout)
                self.assertEqual(profile["executable_sha256"], digest)
                self.assertEqual(profile["platform"], "linux-x86_64")
                self.assertEqual(profile["compile_arguments"], [
                    "--target", "x86_64-unknown-linux", "--target_cpu", "generic",
                    "--interface", "OSDI", "-O", "3",
                ])

    def test_generated_consumer_table_is_immutable_and_records_exact_catalog_bytes(self) -> None:
        serialized = (ROOT / REGISTRY.CATALOG).read_bytes()
        original = REGISTRY.outputs()
        scala = original[REGISTRY.SCALA_OUTPUT]
        self.assertIn("private[nodal] object OpenVafProfiles:", scala)
        self.assertIn("val all: Vector[OpenVafProfile] = Vector(", scala)
        self.assertEqual(scala.count("catalogSha256 = \"" + hashlib.sha256(serialized).hexdigest()), 2)
        self.assertNotIn("java.nio", scala)
        modified = self.generated(serialized + b"\n")
        self.assertEqual(modified[REGISTRY.OUTPUT], original[REGISTRY.OUTPUT])
        self.assertNotEqual(modified[REGISTRY.SCALA_OUTPUT], scala)
        self.assertEqual(modified[REGISTRY.SCALA_OUTPUT].count(
            "catalogSha256 = \"" + hashlib.sha256(serialized + b"\n").hexdigest()), 2)

    def test_consumer_generation_is_deterministic_and_sorted(self) -> None:
        original = REGISTRY.outputs()[REGISTRY.SCALA_OUTPUT]
        self.assertEqual(REGISTRY.outputs()[REGISTRY.SCALA_OUTPUT], original)
        self.catalog["consumer_profiles"]["profiles"].reverse()
        self.inventory["consumer_observations"].reverse()
        reordered = self.generated()[REGISTRY.SCALA_OUTPUT]
        # Reordering changes the byte-level catalog identity, but not the generated facts.
        scrub_digest = lambda text: re.sub(r'catalogSha256 = "[0-9a-f]{64}"',
                                         'catalogSha256 = "CATALOG"', text)
        self.assertEqual(scrub_digest(reordered), scrub_digest(original))

    def test_generated_historical_observations_use_full_version_and_unknown_arguments(self) -> None:
        original = REGISTRY.outputs()
        scala = original[REGISTRY.SCALA_OUTPUT]
        self.assertEqual(scala.count("OpenVafObservation("), 2)
        self.assertEqual(scala.count('version = "OpenVAF-reloaded 20260616-3-g0e83f1ed"'), 3)
        for observation in self.inventory["consumer_observations"]:
            self.assertIn('modelSha256 = "' + observation["model_sha256"] + '"', scala)
            self.assertIn('outcome = "' + observation["result"] + '"', scala)
        # Both historical invocations predate the generic target flags. Omit the
        # optional field, retaining its None default rather than inventing identity.
        for block in scala.split("OpenVafObservation(")[1:]:
            self.assertNotIn("compileArguments", block.split(")", 1)[0])
        self.inventory["consumer_observations"] = []
        modified = self.generated()
        self.assertEqual(modified[REGISTRY.OUTPUT], original[REGISTRY.OUTPUT])
        self.assertNotIn("OpenVafObservation(", modified[REGISTRY.SCALA_OUTPUT])

    def test_consumer_profile_container_has_closed_fields_and_versions(self) -> None:
        profiles = self.catalog["consumer_profiles"]
        for field, value, message in (
            ("schema_version", True, "unsupported schema_version"),
            ("schema_version", 2, "unsupported schema_version"),
            ("profile_version", "2", "unsupported profile_version"),
            ("profiles", [], "expected list"),
            ("profiles", {}, "expected list"),
            ("profiles", [1], "expected objects"),
        ):
            original = profiles[field]
            with self.subTest(field=field, value=value):
                profiles[field] = value
                self.invalid(message)
            profiles[field] = original
        profiles["uncontrolled_flags"] = []
        self.invalid("unknown or missing fields")
        del profiles["uncontrolled_flags"]
        del profiles["profile_version"]
        self.invalid("unknown or missing fields")

    def test_consumer_profile_fields_are_validated(self) -> None:
        profile = self.catalog["consumer_profiles"]["profiles"][0]
        for field, value, message in (
            ("id", "invented", "invalid consumer profile id"),
            ("consumer", "invented", "unsupported consumer"),
            ("consumer", [], "unsupported consumer"),
            ("version", "23.5.0\n", "invalid consumer version"),
            ("version", None, "invalid consumer version"),
            ("version_stdout", "openvaf 23.5.0\n", "version stdout does not match"),
            ("version_stdout", "OpenVAF 23.5.0", "version stdout does not match"),
            ("version_stdout", None, "version stdout does not match"),
            ("platform", "linux-aarch64", "unsupported platform"),
            ("platform", None, "unsupported platform"),
            ("executable_sha256", "a" * 63, "invalid executable_sha256"),
            ("executable_sha256", "A" * 64, "invalid executable_sha256"),
            ("executable_sha256", [], "invalid executable_sha256"),
        ):
            original = profile[field]
            with self.subTest(field=field, value=value):
                profile[field] = value
                self.invalid(message)
            profile[field] = original
        profile["batch_cache"] = True
        self.invalid("unknown or missing consumer profile fields")
        del profile["batch_cache"]
        del profile["compile_arguments"]
        self.invalid("unknown or missing consumer profile fields")

    def test_consumer_compile_arguments_are_closed_and_ordered(self) -> None:
        profile = self.catalog["consumer_profiles"]["profiles"][0]
        original = profile["compile_arguments"]
        for arguments in (None, [], "--target x86_64-unknown-linux", original[:-1],
                          list(reversed(original)), original + ["--batch"],
                          original + ["-I", "/tmp/includes"], original + ["-D", "EXTRA=1"],
                          ["--target", "aarch64-unknown-linux"] + original[2:]):
            with self.subTest(arguments=arguments):
                profile["compile_arguments"] = arguments
                self.invalid("unsupported compile_arguments")
        profile["compile_arguments"] = original

    def test_duplicate_consumer_profiles_reject(self) -> None:
        profiles = self.catalog["consumer_profiles"]["profiles"]
        profiles.append(copy.deepcopy(profiles[0]))
        self.invalid("duplicate id")

    def test_observations_reference_an_exact_pinned_consumer_identity(self) -> None:
        observation = self.inventory["consumer_observations"][0]
        for field, value in (("consumer", "other-tool"), ("version", "different-version"),
                             ("tool_sha256", "0" * 64)):
            original = observation[field]
            with self.subTest(field=field):
                observation[field] = value
                self.invalid("unknown observed consumer tool identity")
            observation[field] = original
        observation["id"] = "invalid/observation"
        self.invalid("consumer_observations: invalid id")

    def test_direct_admission_exactly_preserves_previous_owner(self) -> None:
        self.assertEqual({name for name, entry in self.operations.items()
                          if entry["admission"] == "direct"}, DIRECT_OPERATIONS)
        self.assertEqual(len(DIRECT_OPERATIONS), 58)

    def test_every_current_tablegen_operation_is_cataloged(self) -> None:
        source = (ROOT / REGISTRY.OPERATIONS).read_text(encoding="utf-8")
        concrete = re.findall(r'def\s+Nodal_\w+Op\s*:\s*Nodal_\w*Op<"([^"]+)"', source)
        self.assertEqual(len(concrete), len(set(concrete)))
        self.assertEqual(set(self.operations), {"nodal." + name for name in concrete})
        self.assertIn("nodal.analog_cross", self.operations)
        self.assertIn("nodal.analog_add", self.operations)

    def test_guarded_operations_do_not_become_direct(self) -> None:
        for name in ("analog_procedure", "analog_cross", "analog_timer", "analog_on",
                     "analog_variable", "analog_assign", "analog_loop", "shape_index",
                     "shape_view", "shape_slice", "constant", "connection_set", "potential_equality"):
            with self.subTest(operation=name):
                self.assertEqual(self.operations["nodal." + name]["admission"], "guarded")

    def test_shared_operations_need_actual_type_classification(self) -> None:
        for name in ("port", "port_value", "constant", "shape_index", "shape_view", "shape_slice"):
            with self.subTest(operation=name):
                self.assertEqual(self.operations["nodal." + name]["domain"], "neutral")
        self.assertEqual(self.operations["nodal.bridge"]["domain"], "analog")
        self.assertEqual(self.operations["nodal.bridge"]["admission"], "unsupported")

    def test_digital_equation_and_unlowered_forms_remain_explicitly_unsupported(self) -> None:
        for name in ("net_driver", "net_drive", "clock_relation", "hardware_loop", "fsm",
                     "analog_unknown", "analog_equation", "shape_flatten", "alias", "reference"):
            with self.subTest(operation=name):
                self.assertEqual(self.operations["nodal." + name]["admission"], "unsupported")
        self.assertEqual(self.operations["nodal.clock_relation"]["domain"], "digital")
        self.assertEqual(self.operations["nodal.analog_equation"]["domain"], "analog")

    def test_generated_table_is_immutable_sorted_and_no_mlir_dependency(self) -> None:
        content = REGISTRY.outputs()[REGISTRY.OUTPUT]
        names = re.findall(r'\{"(nodal\.[^"]+)"', content)
        self.assertEqual(names, sorted(self.operations))
        self.assertIn("static constexpr BackendOperationDescriptor", content)
        for relative in ("include/nodal/Support/BackendCapabilityRegistry.h",
                         "lib/Support/BackendCapabilityRegistry.cpp"):
            self.assertNotIn('#include "mlir/', (ROOT / "core/compiler" / relative).read_text())
            self.assertNotIn('#include "nodal/Dialect/', (ROOT / "core/compiler" / relative).read_text())

    def test_generation_is_independent_of_catalog_order(self) -> None:
        original = REGISTRY.outputs()[REGISTRY.OUTPUT]
        self.inventory["operations"].reverse()
        self.inventory["features"].reverse()
        self.inventory["language_profiles"].reverse()
        self.inventory["consumer_observations"].reverse()
        for profile in self.inventory["language_profiles"]:
            profile["features"].reverse()
        with tempfile.TemporaryDirectory(dir=ROOT / ".work") as directory:
            root = Path(directory)
            (root / "core").symlink_to(ROOT / "core", target_is_directory=True)
            (root / "docs").symlink_to(ROOT / "docs", target_is_directory=True)
            (root / "examples").symlink_to(ROOT / "examples", target_is_directory=True)
            # Keep references local but replace just the catalog path through a mock.
            original_catalog = REGISTRY.CATALOG
            REGISTRY.CATALOG = Path("reordered.json")
            try:
                (root / REGISTRY.CATALOG).write_text(json.dumps(self.catalog), encoding="utf-8")
                self.assertEqual(REGISTRY.outputs(root)[REGISTRY.OUTPUT], original)
            finally:
                REGISTRY.CATALOG = original_catalog

    def test_duplicate_feature_id_rejects(self) -> None:
        self.inventory["features"].append(copy.deepcopy(self.inventory["features"][0]))
        self.invalid("duplicate id")

    def test_duplicate_operation_rejects(self) -> None:
        self.inventory["operations"].append(copy.deepcopy(self.inventory["operations"][0]))
        self.invalid("duplicate operation")

    def test_unknown_feature_reference_rejects(self) -> None:
        self.inventory["operations"][0]["feature"] = "invented-feature"
        self.invalid("unknown feature reference")

    def test_invalid_domain_and_admission_reject(self) -> None:
        operation = self.inventory["operations"][0]
        for field in ("domain", "admission"):
            original = operation[field]
            for value in ("invented", "", None, [], 1):
                with self.subTest(field=field, value=value):
                    operation[field] = value
                    self.invalid("invalid " + field)
            operation[field] = original

    def test_closed_domain_and_admission_values_are_recognized(self) -> None:
        operation = self.operations["nodal.shape_view"]
        for domain in REGISTRY.DOMAINS:
            operation["domain"] = domain
            REGISTRY.validate_catalog(self.catalog)
        for admission in ("direct", "guarded", "normalize"):
            operation["admission"] = admission
            REGISTRY.validate_catalog(self.catalog)

    def test_missing_existing_operation_rejects(self) -> None:
        self.inventory["operations"].remove(self.operations["nodal.analog_add"])
        self.invalid("TableGen inventory mismatch; missing=nodal.analog_add")

    def test_unknown_operation_rejects(self) -> None:
        operation = copy.deepcopy(self.inventory["operations"][0])
        operation["operation"] = "nodal.future_unimplemented"
        self.inventory["operations"].append(operation)
        self.invalid("unknown=nodal.future_unimplemented")

    def test_invalid_version_and_unknown_policy_reject(self) -> None:
        for field, value in (("schema_version", 2), ("schema_version", True),
                             ("inventory_version", "2"), ("unknown_operation_policy", "allow")):
            original = self.inventory[field]
            with self.subTest(field=field, value=value):
                self.inventory[field] = value
                self.invalid("unsupported|must reject")
            self.inventory[field] = original

    def test_missing_reference_and_unknown_validator_reject(self) -> None:
        feature = self.inventory["features"][0]
        for reference, message in (("missing/source.cpp", "missing reference"),
                                   ("core/compiler/lib/Backend/AnalogVerticalSlice.cpp#inventedValidator",
                                    "unknown validator")):
            with self.subTest(reference=reference):
                feature["validation_references"] = [reference]
                self.invalid(message)

    def test_unknown_analog_registry_reference_rejects(self) -> None:
        self.inventory["features"][0]["registry_references"] = ["analog-functions-v1.json#invented"]
        self.invalid("unsupported registry reference")

    def test_registry_features_reference_the_authoritative_functions_analyses_contexts(self) -> None:
        references = {ref for feature in self.inventory["features"]
                      for ref in feature.get("registry_references", [])}
        self.assertEqual(references, {"analog-functions-v1.json#" + name
                                      for name in ("functions", "analyses", "contexts")})

    def test_duplicate_profile_and_missing_feature_reference_reject(self) -> None:
        profile = self.inventory["language_profiles"][0]
        self.inventory["language_profiles"].append(copy.deepcopy(profile))
        self.invalid("duplicate id")
        self.inventory["language_profiles"].pop()
        profile["features"].pop()
        self.invalid("incomplete or unknown feature references")

    def test_unsupported_feature_cannot_be_advertised(self) -> None:
        for feature in self.inventory["language_profiles"][0]["features"]:
            if feature["feature"] == "typed-source-equations":
                feature["support"] = "supported"
        self.invalid("support disagrees with operation admission")

    def test_guarded_feature_cannot_be_advertised_as_unconditional(self) -> None:
        for feature in self.inventory["language_profiles"][0]["features"]:
            if feature["feature"] == "procedural-storage":
                feature["support"] = "supported"
        self.invalid("requires conditional support")

    def test_consumer_observations_remain_witness_specific_and_separate(self) -> None:
        observations = self.inventory["consumer_observations"]
        self.assertEqual(len(observations), 2)
        by_selection = {item["selection"]: item for item in observations}
        self.assertEqual(by_selection["AnalogCapabilityStorage"]["result"], "compiled")
        self.assertEqual(by_selection["AnalogCapabilityStorage"]["exit_code"], 0)
        self.assertTrue(by_selection["AnalogCapabilityStorage"]["output_produced"])
        self.assertEqual(by_selection["AnalogCapabilityHierarchy"]["result"], "rejected")
        self.assertEqual(by_selection["AnalogCapabilityHierarchy"]["exit_code"], 65)
        self.assertFalse(by_selection["AnalogCapabilityHierarchy"]["output_produced"])
        for profile in self.inventory["language_profiles"]:
            support = {entry["feature"]: entry["support"] for entry in profile["features"]}
            self.assertEqual(support["structural-hierarchy"], "supported")
            self.assertEqual(support["procedural-storage"], "conditional")
        # Observations cannot change emitted name-level admission facts.
        original = REGISTRY.outputs()[REGISTRY.OUTPUT]
        self.inventory["consumer_observations"] = []
        REGISTRY.validate_catalog(self.catalog)
        with tempfile.TemporaryDirectory(dir=ROOT / ".work") as directory:
            root = Path(directory)
            for name in ("core", "docs", "examples"):
                (root / name).symlink_to(ROOT / name, target_is_directory=True)
            original_catalog = REGISTRY.CATALOG
            REGISTRY.CATALOG = Path("without-observations.json")
            try:
                (root / REGISTRY.CATALOG).write_text(json.dumps(self.catalog), encoding="utf-8")
                self.assertEqual(REGISTRY.outputs(root)[REGISTRY.OUTPUT], original)
            finally:
                REGISTRY.CATALOG = original_catalog

    def test_consumer_observation_references_and_outcomes_are_closed(self) -> None:
        observation = self.inventory["consumer_observations"][0]
        for field, value, message in (
            ("profile", "invented", "unknown observed profile"),
            ("features", ["invented"], "unknown observed feature"),
            ("result", "universally-supported", "invalid observation result"),
            ("witness", "missing.scala", "missing reference"),
            ("selection", "InventedClass", "selection is not present"),
            ("evidence", ["missing.md"], "missing reference"),
        ):
            original = observation[field]
            with self.subTest(field=field):
                observation[field] = value
                self.invalid(message)
            observation[field] = original

    def test_consumer_provenance_fields_are_required_and_checked(self) -> None:
        observation = self.inventory["consumer_observations"][0]
        for field, value, message in (
            ("selection", None, "selection: expected nonempty string"),
            ("tool_sha256", "abc", "invalid tool_sha256"),
            ("model_sha256", "g" * 64, "invalid model_sha256"),
            ("exit_code", True, "exit_code must be an integer"),
            ("exit_code", "65", "exit_code must be an integer"),
            ("exit_code", 0, "result disagrees with exit_code"),
            ("output_produced", "false", "output_produced must be a boolean"),
        ):
            original = observation[field]
            with self.subTest(field=field, value=value):
                observation[field] = value
                self.invalid(message)
            observation[field] = original

    def test_simulator_profile_provenance_and_closed_configuration(self) -> None:
        profile = self.catalog["simulator_profiles"]["profiles"][0]
        for field, value, message in (
            ("executable_sha256", "bad", "invalid simulator executable digest"),
            ("version_stdout", "ngspice unknown", "invalid simulator version stdout"),
            ("platform", "other", "unsupported simulator profile"),
            ("build", {}, "incomplete simulator build provenance"),
        ):
            original = profile[field]
            with self.subTest(field=field):
                profile[field] = value
                self.invalid(message)
            profile[field] = original
        profile["build"]["source_modified"] = True
        self.invalid("unsupported simulator build kind")

    def test_simulator_preprocessing_override_is_identity_bearing(self) -> None:
        profile = self.catalog["simulator_profiles"]["profiles"][0]
        profile["build"]["object_override"]["cflags"] = "-O2"
        self.invalid("unsupported simulator build configuration")

    def test_simulator_registry_is_deterministic_and_catalog_bound(self) -> None:
        data = (ROOT / REGISTRY.CATALOG).read_bytes()
        generated = REGISTRY.simulator_output(self.catalog, data)
        self.assertEqual(generated, REGISTRY.simulator_output(copy.deepcopy(self.catalog), data))
        self.assertIn(hashlib.sha256(data).hexdigest(), generated)
        self.assertIn("ngspice-47-local-linux-x86_64", generated)
        self.assertNotIn("OpenVafProfile(", generated)

    def vacask(self) -> dict:
        return next(profile for profile in self.catalog["simulator_profiles"]["profiles"]
                    if profile["consumer"] == "VACASK")

    def test_vacask_is_independent_and_preserves_ngspice_pin(self) -> None:
        data = (ROOT / REGISTRY.CATALOG).read_bytes()
        ngspice = REGISTRY.simulator_output(self.catalog, data)
        vacask = REGISTRY.vacask_output(self.catalog, data)
        self.assertNotIn("vacask-", ngspice)
        self.assertNotIn("ngspice-", vacask)
        self.assertIn("bb606780f55d0b4f4b0e50503381d991cd2fd12298f85563bb4438b33c1abb13", vacask)
        self.assertIn("ba254f361c728f70cbb09293750d93f541f26c256d56286ddfb755d0d2793711", ngspice)
        self.assertIn(hashlib.sha256(data).hexdigest(), vacask)
        self.assertIn("runtimeLibraries = Vector(", vacask)
        original = copy.deepcopy(self.catalog)
        self.catalog["simulator_profiles"]["profiles"].reverse()
        self.vacask()["build"]["runtime_libraries"].reverse()
        self.assertEqual(REGISTRY.vacask_output(self.catalog, data), vacask)
        self.assertEqual(REGISTRY.simulator_output(self.catalog, data), ngspice)
        # Neither simulator changes the independently frozen admission facts.
        self.catalog = original
        self.catalog["simulator_profiles"]["profiles"].remove(self.vacask())
        self.assertEqual(self.generated()[REGISTRY.OUTPUT], REGISTRY.outputs()[REGISTRY.OUTPUT])
        self.assertNotIn("VacaskProfile(\n", REGISTRY.vacask_output(self.catalog, data))

    def test_simulator_pins_match_the_selected_independent_artifacts(self) -> None:
        ngspice = next(p for p in self.catalog["simulator_profiles"]["profiles"]
                       if p["consumer"] == "ngspice")
        encoded = json.dumps(ngspice, sort_keys=True, separators=(",", ":")).encode()
        self.assertEqual(hashlib.sha256(encoded).hexdigest(),
                         "06cb66b1b9f4303da903b0261b10a3e0a35fd662e99a7800533cc5350273cfc1")
        expected = {
            "libamd.so.3": "a4654cdedb47d7806a0c0ede323e40d152b2f1de6f52a22dfa763b8c9a7b4be8",
            "libbtf.so.2": "65e3168fc81f33fdfed4c5c966f945d984ce269b252593066943e1d8b417e5c6",
            "libcolamd.so.3": "35f564c522b6fb9b72e71014dd1e43da7f12b2d73fbd117ae5aaba635a8759d6",
            "libklu.so.2": "9c26bf11c72d86696b6104261d6213258388aa95ce53d38d57c44a7815ff3672",
            "libopenblas.so.0": "10d1c640ecccd21063a7d80f90e949b1d48202830b6ec0b52b2796c5d0fbce63",
            "libsuitesparseconfig.so.7": "133ec1f1a0655f5b2fc43b43605a0c2986ba00addad144bb3096a99045bfb155",
        }
        actual = {entry["soname"]: entry["sha256"]
                  for entry in self.vacask()["build"]["runtime_libraries"]}
        self.assertEqual(actual, expected)
        generated = REGISTRY.outputs()[REGISTRY.VACASK_OUTPUT]
        for soname, digest in expected.items():
            self.assertIn(json.dumps(soname), generated)
            self.assertIn(json.dumps(digest), generated)

    def test_vacask_release_identity_rejects_unrelated_or_modified_tools(self) -> None:
        profile = self.vacask()
        for field, value, message in (
            ("consumer", "Xyce", "unsupported simulator profile"),
            ("id", "vacask-latest", "unsupported VACASK profile"),
            ("version", "0.3.5", "unsupported VACASK profile"),
            ("platform", "linux-aarch64", "unsupported VACASK profile"),
            ("version_stdout", profile["version_stdout"] + "\n", "invalid VACASK help stdout"),
            ("version_stdout", None, "invalid VACASK help stdout"),
            ("executable_sha256", "0" * 64, "invalid VACASK executable digest"),
            ("build", {}, "incomplete VACASK build provenance"),
        ):
            original = profile[field]
            with self.subTest(field=field):
                profile[field] = value
                self.invalid(message)
            profile[field] = original
        build = profile["build"]
        for field, value, message in (
            ("kind", "locally-patched", "unsupported VACASK build kind"),
            ("source_modified", True, "unsupported VACASK build kind"),
            ("binary_modified", True, "unsupported VACASK build kind"),
            ("archive_url", "https://example.com/vacask.tar.gz", "invalid VACASK release provenance"),
            ("archive_sha256", "0" * 64, "invalid VACASK release provenance"),
            ("source_commit", "0" * 40, "invalid VACASK release provenance"),
        ):
            original = build[field]
            with self.subTest(field=field):
                build[field] = value
                self.invalid(message)
            build[field] = original
        build["compiler_flags"] = "-O3"
        self.invalid("incomplete VACASK build provenance")

    def test_vacask_runtime_manifest_rejects_missing_duplicate_or_unknown_libraries(self) -> None:
        build = self.vacask()["build"]
        libraries = copy.deepcopy(build["runtime_libraries"])
        for value, message in (
            (None, "expected list"), ([], "expected list"), ([1], "expected objects"),
            (libraries[:-1], "unsupported VACASK runtime libraries"),
            (libraries + [libraries[0]], "duplicate soname"),
            ([dict(libraries[0], soname="../lib.so")] + libraries[1:],
             "unsupported VACASK runtime libraries"),
        ):
            with self.subTest(value=value):
                build["runtime_libraries"] = value
                self.invalid(message)
        build["runtime_libraries"] = libraries

    def test_vacask_runtime_provenance_rejects_malformed_package_or_hash(self) -> None:
        library = self.vacask()["build"]["runtime_libraries"][0]
        for field, value, message in (
            ("package", "other", "unsupported runtime package provenance"),
            ("version", "latest", "unsupported runtime package provenance"),
            ("architecture", "arm64", "unsupported runtime package provenance"),
            ("archive_url", "https://example.com/lib.deb", "unsupported runtime package provenance"),
            ("sha256", "A" * 64, "invalid runtime sha256"),
            ("sha256", None, "invalid runtime sha256"),
            ("archive_sha256", "f" * 63, "invalid runtime archive_sha256"),
        ):
            original = library[field]
            with self.subTest(field=field):
                library[field] = value
                self.invalid(message)
            library[field] = original
        library["search_path"] = "/usr/lib"
        self.invalid("incomplete runtime provenance")

    def test_duplicate_json_keys_reject(self) -> None:
        with self.assertRaisesRegex(REGISTRY.CatalogError, "duplicate key"):
            json.loads('{"schema":1,"schema":2}', object_pairs_hook=REGISTRY.reject_duplicate_keys)

    def test_check_mode_does_not_write_and_rejects_stale_output(self) -> None:
        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        with tempfile.TemporaryDirectory(dir=ROOT / ".work") as directory:
            root = Path(directory)
            # A small repository mirror retains real validation references.
            (root / "core/compiler/include/nodal/Support").mkdir(parents=True)
            (root / "core/compiler/include/nodal/Dialect").symlink_to(
                ROOT / "core/compiler/include/nodal/Dialect", target_is_directory=True)
            (root / "core/compiler/lib").symlink_to(ROOT / "core/compiler/lib", target_is_directory=True)
            (root / "docs").symlink_to(ROOT / "docs", target_is_directory=True)
            (root / "examples").symlink_to(ROOT / "examples", target_is_directory=True)
            for name in ("backend-profiles-v0.1.json", "analog-functions-v1.json"):
                (root / "core/compiler" / name).symlink_to(ROOT / "core/compiler" / name)
            outputs = REGISTRY.outputs()
            for stale in outputs:
                with self.subTest(output=stale):
                    for output, content in outputs.items():
                        destination = root / output
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_text("stale\n" if output == stale else content,
                                               encoding="utf-8")
                    result = subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), "--check"],
                                            capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("stale backend capability registry: " + str(stale), result.stderr)
                    for output, content in outputs.items():
                        self.assertEqual((root / output).read_text(encoding="utf-8"),
                                         "stale\n" if output == stale else content)


if __name__ == "__main__":
    unittest.main()
