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

    def test_generation_is_deterministic(self):
        path = ROOT / "scripts/generate_analog_function_registry.py"
        spec = importlib.util.spec_from_file_location("registry_generator", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.outputs(), module.outputs())


if __name__ == "__main__":
    unittest.main()
