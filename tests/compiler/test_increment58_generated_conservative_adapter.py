"""Focused source generation and fail-closed expectation controls, no compiler build."""
import json
import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import importlib.util

FIXTURES = Path(__file__).resolve().parent / "fixtures/increment58/generated-conservative-adapter"
_spec = importlib.util.spec_from_file_location(
    "increment58_conservative_adapter_runner",
    FIXTURES / "increment58_conservative_adapter_runner.py",
)
assert _spec is not None and _spec.loader is not None
run = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(run)


def scratch_directory():
    # Works in the repository and in a candidate layout nested under .work.
    repository = next(parent for parent in Path(__file__).resolve().parents
                      if (parent / "AGENTS.md").is_file()
                      and (parent / "CONTRIBUTING.md").is_file())
    scratch = repository / ".work"
    scratch.mkdir(exist_ok=True)
    run.reserve(scratch, 8 * 1024 * 1024)
    return scratch


class AdapterControls(unittest.TestCase):
    def test_inventory(self):
        rows = run.cases()
        self.assertEqual(len(rows), 81)
        self.assertEqual(len({x['name'] for x in rows}), 81)
        self.assertEqual(sum(len(x['checks']) for x in rows), 125)
        self.assertEqual(sum(x['kind'] == 'native-adapter-fixture' for x in rows), 74)

    def test_complete_generation(self):
        with tempfile.TemporaryDirectory(prefix="f58-adapter-controls-", dir=scratch_directory()) as tmp:
            root = Path(tmp) / 'inputs'
            run.generate(root)
            self.assertFalse(list(root.rglob('budget-*.mlir')))
            for row in run.cases():
                if row['kind'] == 'budget-generator-and-base':
                    count = int(Path(row['name']).stem.split('-')[-1])
                    text = run.budget((root / 'selected-child/count3.mlir').read_text(), count)
                    self.assertEqual(run.digest(text.encode()), row['input_sha256'])

    def test_exact_mutation_rejects_absent_and_ambiguous_anchor(self):
        self.assertEqual(run.replace_exact('abc', 'b', 'x', 1), 'axc')
        for text in ('ac', 'abbc'):
            with self.assertRaises(ValueError):
                run.replace_exact(text, 'b', 'x', 1)

    def test_expectations_reject_wrong_exit_output_and_signal(self):
        row = next(x for x in run.cases() if x['name'] == 'selected-child/count3.mlir')
        check = next(x for x in row['checks'] if x['mode'] == 'verify')
        self.assertTrue(run.valid_result(check, 0, b'', b''))
        for status in (1, 3, -11, 124):
            self.assertFalse(run.valid_result(check, status, b'', b''))
        self.assertFalse(run.valid_result(check, 0, b'unexpected', b''))

    def test_budget_requires_diagnostic_and_transaction_evidence(self):
        check = {'group': 'budget-boundary', 'mode': 'emit', 'exit': 5,
                 'stdout_sha256': run.digest(b'')}
        self.assertTrue(run.valid_result(check, 5, b'', b'work budget exceeded'))
        self.assertFalse(run.valid_result(check, 5, b'', b''))
        check = {'group': 'budget-transaction', 'mode': 'transaction', 'exit': 0,
                 'stdout_sha256': run.digest(b'')}
        self.assertFalse(run.valid_result(check, 0, b'', b''))

    def test_structural_checker_rejects_lost_loop_port_and_correspondence(self):
        adapted = ('"nodal.instance"() ' + '"nodal.instance_terminal"() ' * 2 +
                   '"nodal.node_array"() ' * 2 + 'name = "target"')
        records = []
        for index in range(8):
            record = ('target = "target", discipline = "electrical", '
                      'selected_access = "connect", flow_orientation = "' +
                      ('into_component' if index < 2 else 'out_of_component') + '"')
            records.append('// nodal.interface_correspondence mlir-v1 ' +
                           base64.b64encode(record.encode()).decode())
        emitted = 'for (i = 0; i < 1; i++) electrical [0:0] a; electrical [0:0] b;\n' + '\n'.join(records)
        run.structural('count1', {'adapt': adapted, 'emit': emitted})
        variants = [
            (adapted, emitted.replace('for (', 'while (')),
            (adapted.replace('"nodal.instance_terminal"()', '', 1), emitted),
            (adapted, emitted[:emitted.rfind('// nodal.interface_correspondence')]),
        ]
        for a, e in variants:
            with self.assertRaises(AssertionError):
                run.structural('count1', {'adapt': a, 'emit': e})

    def test_probe_timeout_fails_and_removes_budget_input(self):
        # Run only the budget refusal case with a causally timed-out invocation.
        # No compiler process is launched; run() must propagate TimeoutExpired
        # and its finally block must still remove the padded input.
        case = next(row for row in run.cases() if row['name'] == 'budget/budget-49913.mlir')
        with tempfile.TemporaryDirectory(prefix="f58-adapter-timeout-", dir=scratch_directory()) as tmp:
            root = Path(tmp)
            output = root / "run"

            timeout = run.subprocess.TimeoutExpired(cmd=['probe'], timeout=60)
            with patch.object(run, 'cases', return_value=[case]), patch.object(run.subprocess, 'run', side_effect=timeout) as invoke:
                # generate() only validates selected inventory, while still
                # materializing its normal deterministic native input set.
                with self.assertRaises(run.subprocess.TimeoutExpired):
                    run.run(root / "unused-probe", root / "unused-public", output)
            self.assertEqual(invoke.call_count, 1)
            self.assertEqual(invoke.call_args.kwargs['timeout'], 60)
            self.assertFalse((output / 'budget-current.mlir').exists())
            self.assertFalse((output / 'summary.json').exists())

    def test_public_profiles_are_explicit_and_fail_closed(self):
        original = json.loads((run.SOURCE / 'cases.json').read_text())
        historical = run.cases('historical')
        coherent = run.cases('coherent-source53')
        self.assertEqual(original, historical)
        self.assertEqual(run.cases(), coherent)
        with self.assertRaises(ValueError):
            run.cases('unknown-profile')
        with self.assertRaises(ValueError):
            run.cases('')
        changes = 0
        for old, new in zip(historical, coherent):
            if old['kind'] != 'public-source-output':
                self.assertEqual(old, new)
                continue
            changes += 1
            self.assertNotEqual(old['input_sha256'], new['input_sha256'])
            self.assertNotEqual(old['checks'][1]['stdout_sha256'], new['checks'][1]['stdout_sha256'])
            self.assertEqual([x['exit'] for x in old['checks']], [x['exit'] for x in new['checks']])
            wrong_output = b'unpinned correspondence/comments'
            self.assertFalse(run.valid_result(new['checks'][1], 0, wrong_output, b''))
        self.assertEqual(changes, 5)

    def test_reserve_is_fail_closed(self):
        usage = type('Usage', (), {'free': run.MIN_FREE - 1})()
        with patch.object(run.shutil, 'disk_usage', return_value=usage):
            with self.assertRaises(RuntimeError):
                run.reserve(run.SOURCE)


if __name__ == '__main__':
    unittest.main()
