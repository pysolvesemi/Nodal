"""Exercise Increment 35 review fixes through the compiled parser and backend."""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]


def edit_operator(source: str, operator: str, transform) -> str:
    lines = source.splitlines(keepends=True)
    selected = [i for i, line in enumerate(lines) if f'"nodal.analog_{operator}"' in line]
    assert len(selected) == 1, (operator, selected)
    index = selected[0]
    lines[index] = transform(lines[index])
    return "".join(lines)


def forge_owner(source: str, operator: str, owner: str) -> str:
    def change(line):
        line = re.sub(r'owner = "[^"]+"', f'owner = "{owner}"', line)
        line = re.sub(r'operator_id = "[^"]+"', f'operator_id = "{owner}.operator"', line)
        return re.sub(r'state_id = "[^"]+"', f'state_id = "{owner}.operator.state"', line)
    return edit_operator(source, operator, change)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    args = parser.parse_args()
    source = (ROOT / "core/compiler/test/IR/analog-differential-integral-backend.mlir").read_text(encoding="utf-8")
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-inc35-review-") as temporary:
        directory = Path(temporary)

        def execute(label, text, diagnostic=None):
            nonlocal count
            fixture = directory / f"{label}.mlir"
            fixture.write_text(text, encoding="utf-8")
            commands = (
                [str(args.nodalc), str(fixture)],
                [str(args.nodalc), "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)", str(fixture)],
                [str(args.translate), "--nodal-to-verilog-a", str(fixture)],
            )
            rendered = ""
            for command in commands:
                result = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
                message = result.stdout + result.stderr
                if diagnostic:
                    assert result.returncode != 0, f"{label}: invalid fixture accepted by {command}\n{message}"
                    assert diagnostic in message, f"{label}: expected {diagnostic}\n{message}"
                    if command[0] == str(args.translate):
                        assert not result.stdout.strip(), f"{label}: backend published partial output"
                else:
                    assert result.returncode == 0, f"{label}: valid fixture rejected by {command}\n{message}"
                    if command[0] == str(args.translate):
                        rendered = result.stdout
                count += 1
            return rendered

        output = execute("contracted-baseline", source)
        assert "ddt(" in output and "idt(" in output
        semantic = source.replace('metadata = {}, sym_name = "DifferentialIntegralBackend"', 'metadata = {semantic_path = "Top.child"}, sym_name = "DifferentialIntegralBackend"')
        semantic = semantic.replace('owner = "DifferentialIntegralBackend"', 'owner = "Top.child"')
        semantic = semantic.replace('"DifferentialIntegralBackend.', '"Top.child.')
        execute("semantic-owner", semantic)
        for operator in ("ddt", "idt"):
            execute(f"wrong-module-{operator}", forge_owner(source, operator, "Other"), "NODAL-ANALOG-035-002")
            execute(f"wrong-semantic-{operator}", forge_owner(semantic, operator, "DifferentialIntegralBackend"), "NODAL-ANALOG-035-002")
            empty = edit_operator(source, operator, lambda line: re.sub(r'operator_id = "[^"]+"', 'operator_id = "DifferentialIntegralBackend."', line))
            execute(f"empty-identity-{operator}", empty, "NODAL-ANALOG-035-002")

        malformed_paths = (
            ("leading-space", " Top.child"),
            ("trailing-space", "Top.child "),
            ("tab", r"Top.\09child"),
            ("newline", r"Top.\0Achild"),
            ("nul", r"Top.\00child"),
            ("del", r"Top.\7Fchild"),
        )
        for label, path in malformed_paths:
            malformed = semantic.replace('"Top.child"', f'"{path}"')
            malformed = malformed.replace('"Top.child.', f'"{path}.')
            execute(f"noncanonical-owner-{label}", malformed, "NODAL-ANALOG-035-002")
        for operator in ("ddt", "idt"):
            for label, suffix in (("padding", "value "), ("control", r"value\01")):
                identity = "DifferentialIntegralBackend." + suffix
                def bad_identity(line):
                    # A callable replacement preserves MLIR backslash escapes.
                    line = re.sub(r'operator_id = "[^"]+"', lambda _: f'operator_id = "{identity}"', line)
                    return re.sub(r'state_id = "[^"]+"', lambda _: f'state_id = "{identity}.state"', line)
                execute(f"noncanonical-id-{operator}-{label}", edit_operator(source, operator, bad_identity), "NODAL-ANALOG-035-002")
        for value in ('""', '" "', '7 : i64'):
            malformed = semantic.replace('semantic_path = "Top.child"', f'semantic_path = {value}')
            execute(f"invalid-path-{count}", malformed, "NODAL-ANALOG-035-002")
        unicode_owner = semantic.replace("Top.child", "Top.\u0394")
        execute("utf8-owner", unicode_owner)

        annotations = (
            'nodal.simplified = true, nodal.simplification_rule = "ddt-time-invariant-zero", '
            'nodal.simplification_provenance = "increment35", nodal.simplified_dimension = "1", '
            'nodal.simplified_value = 0.0 : f64'
        )
        for kind in ("typed", "legacy-f64"):
            text = source if kind == "typed" else re.sub(r'!nodal.quantity<"real", "[^"]+">', "f64", source)
            legacy = edit_operator(text, "ddt", lambda line: line.replace('operator_contract = "increment35", ', ""))
            rendered = execute(f"{kind}-uncontracted", legacy)
            assert "ddt(" in rendered, "legacy dynamic derivative was lost"
            for label, attrs in (("full", annotations), ("partial", "nodal.simplified = false")):
                forged = edit_operator(legacy, "ddt", lambda line: line.replace('}> :', '}> {' + attrs + '} :'))
                execute(f"{kind}-forged-{label}", forged, "NODAL-ANALOG-035-007")
            forged = edit_operator(text, "ddt", lambda line: line.replace('}> :', '}> {' + annotations + '} :'))
            execute(f"{kind}-contracted-dynamic-forgery", forged, "NODAL-ANALOG-035-007")

        result = subprocess.run(
            [str(args.nodalc), "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)",
             str(ROOT / "core/compiler/test/IR/analog-differential-integral.mlir")],
            text=True, capture_output=True, timeout=60, check=False,
        )
        assert result.returncode == 0, result.stdout + result.stderr
        for token in ("nodal.analog_ddt", "nodal.analog_idt", 'nodal.simplification_rule = "ddt-time-invariant-zero"'):
            assert token in result.stdout, token
        count += 1
    print(f"Increment 35 review matrix passed: {count} compiled parser/verifier/backend checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
