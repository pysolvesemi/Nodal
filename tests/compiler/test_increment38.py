"""Registry-generation contract checks; numerical/compiler cases run through native CTest."""
import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class RegistryTests(unittest.TestCase):
    def test_generated_tables_are_current(self):
        subprocess.run([sys.executable, str(ROOT / "scripts/generate_analog_function_registry.py"),
                        "--check"], check=True)

    def test_closed_registry(self):
        registry = json.loads((ROOT / "core/compiler/analog-functions-v1.json").read_text())
        self.assertEqual(registry["registry_version"], "1")
        self.assertEqual(len(registry["functions"]), 24)
        self.assertEqual(len(registry["analyses"]), 6)
        self.assertEqual(len({f["id"] for f in registry["functions"]}), 24)
        for entry in registry["functions"]:
            self.assertEqual(entry["argument_kind"], "real")
            self.assertEqual(entry["result_kind"], "real")
            self.assertEqual(entry["effect"], "pure")
            self.assertIn(entry["arity"], (1, 2))
            self.assertRegex(entry["verilog_a"], r"^[a-z][a-z0-9]*$")
        self.assertNotIn("limexp", {f["id"] for f in registry["functions"]})

    def test_context_registry_keeps_runtime_effects_and_capabilities_separate(self):
        registry = json.loads((ROOT / "core/compiler/analog-functions-v1.json").read_text())
        entries = {entry["id"]: entry for entry in registry["contexts"]}
        self.assertEqual(len(entries), 9)
        self.assertTrue(all(entry["effect"] == "simulator-read"
                            for entry in entries.values()))
        self.assertEqual(entries["temperature"]["dimension"], "temperature")
        self.assertEqual(entries["temperature"]["verilog_a"], "$temperature")
        self.assertEqual(entries["nominal_temperature"]["verilog_a"],
                         '($simparam("tnom") + 273.15)')
        self.assertEqual(entries["nominal_temperature"]["availability"],
                         "conditional-standard")
        self.assertEqual(entries["time"]["availability"], "existing-time")
        self.assertEqual(entries["analysis"]["availability"], "existing-analysis")
        for identity in ("frequency", "operating_condition", "corner",
                         "sweep_coordinate", "random_seed"):
            self.assertEqual(entries[identity]["availability"], "unavailable-provider")
            self.assertEqual(entries[identity]["verilog_a"], "")
        self.assertEqual(entries["random_seed"]["result_kind"], "integer")
        self.assertEqual(entries["frequency"]["phase"], "small-signal")
        profiles = json.loads((ROOT / "core/compiler/backend-profiles-v0.1.json").read_text())
        for profile in profiles["profiles"]:
            context = profile["simulator_context"]
            self.assertEqual(set(context["supported"]), {"analysis", "time", "temperature"})
            self.assertEqual(set(context["conditional_standard"]), {"nominal_temperature"})
            self.assertEqual(set(context["unavailable_provider"]),
                             {identity for identity, entry in entries.items()
                              if entry["availability"] == "unavailable-provider"})


    def test_repository_closure_contract(self):
        path = ROOT / "scripts/check_increment38.py"
        spec = importlib.util.spec_from_file_location("closure_contract38", path)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.check_repository(ROOT)

    def test_generation_is_deterministic(self):
        path = ROOT / "scripts/generate_analog_function_registry.py"
        spec = importlib.util.spec_from_file_location("registry_generator", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.outputs(), module.outputs())


if __name__ == "__main__":
    unittest.main()
