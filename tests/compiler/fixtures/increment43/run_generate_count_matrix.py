#!/usr/bin/env python3
"""Check captured generate-count contracts using the real native verifier.

Hand-authored native fixtures are not public Scala or generated-Verilog evidence.
Every positive is checked twice; rejection requires exit 1 and the exact code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from collections import Counter
from pathlib import Path


CODE = "NODAL-ITERATION-043-001"
PIPELINE = "--pass-pipeline=builtin.module(nodal-verify-parameters)"


@dataclass(frozen=True)
class Case:
    name: str
    body: str
    code: str | None = None


def generate(lower: str, upper: str, step: str, count: str | None,
             *, maximum: str | None = None, captured: bool = True,
             identity: str = "Fixture.outer", body: str = "") -> str:
    fields = []
    if count is not None:
        fields.append(f"maximum_trip_count = {count}")
    if maximum is not None:
        fields.append(f"declared_maximum = {maximum}")
    ownership = (f', region_id = "{identity}", induction_path = "{identity}.index"'
                 if captured else "")
    metadata = ", ".join(fields)
    return (f'"nodal.generate"() <{{induction = "lane", lower = {lower}, '
            f'upper = {upper}, step = {step}{ownership}, metadata = {{{metadata}}}}}> '
            f'({{\n^bb0:\n{body}}}) : () -> ()\n')


def parameter(name: str, initial: int, lower: int, upper: int) -> str:
    # Explicit signed test parameters also exercise negative interval endpoints;
    # this does not change the Scala Integer bridge's existing i64 representation.
    return f'''"nodal.parameter"() <{{classification = "structural", default_value = {initial} : si64, metadata = {{}}, parameter_kind = "integer", sym_name = "{name}", type = si64, variability = "symbolic"}}> : () -> ()
%{name}_lower = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{lower}", value = {lower} : si64}}> : () -> si64
%{name}_upper = "nodal.const_literal"() <{{metadata = {{}}, spelling = "{upper}", value = {upper} : si64}}> : () -> si64
"nodal.parameter_constraint"(%{name}_lower, %{name}_upper) <{{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {{}}, parameter = @{name}}}> : (si64, si64) -> ()
"nodal.parameter_envelope"() <{{effects = ["topology"], metadata = {{}}, parameter = @{name}, policy = "static_generate"}}> : () -> ()
'''


def cases() -> list[Case]:
    result = []
    # Independent small-domain oracle: ordinary Python range enumeration, not a
    # copy of the compiler's unsigned ceiling-division implementation.
    for lower, upper, step in ((0, 0, 1), (0, 1, 1), (0, 3, 1), (0, 5, 2),
                               (-5, 5, 3), (-5, -2, 7)):
        count = len(list(range(lower, upper, step)))
        suffix = f"{lower}_{upper}_{step}".replace("-", "m")
        result.append(Case("literal_" + suffix, generate(
            f"{lower} : i64", f"{upper} : i64", f"{step} : i64", f"{count} : i64")))
    count = parameter("COUNT", 2, 1, 4)
    symbolic = lambda value, **kwargs: count + generate("0 : i64", "@COUNT", "1 : i64", value, **kwargs)
    result += [
        Case("symbolic_default_two_envelope_four", symbolic("4 : i64")),
        Case("exact_declared_maximum", symbolic("4 : i64", maximum="4 : i64")),
        Case("generous_declared_maximum", symbolic("4 : i64", maximum="10 : i64")),
        Case("empty_same_parameter", count + generate("@COUNT", "@COUNT", "1 : i64", "0 : i64", maximum="0 : i64")),
        Case("varying_positive_step", count + parameter("STEP", 2, 1, 3) + generate("0 : i64", "@COUNT", "@STEP", "4 : i64")),
        Case("all_symbolic_outer_interval", parameter("LOW", -2, -3, -1) + parameter("HIGH", 4, 2, 5) + parameter("STEP", 2, 2, 4) + generate("@LOW", "@HIGH", "@STEP", "4 : i64")),
        Case("largest_supported_count", generate("0 : i64", "2147483647 : i64", "1 : i64", "2147483647 : i64")),
        Case("wide_signed_span_small_count", generate("-9223372036854775808 : i64", "9223372036854775807 : i64", "9223372036854775807 : i64", "3 : i64")),
        Case("nested_counts", count + generate("0 : i64", "2 : i64", "1 : i64", "2 : i64", body=generate("0 : i64", "@COUNT", "1 : i64", "4 : i64", identity="Fixture.outer.inner"))),
        Case("legacy_literal_descending", generate("4 : i64", "0 : i64", "-1 : i64", None, captured=False)),
        Case("legacy_symbolic_descending", parameter("STEP", -1, -3, -1) + generate("8 : i64", "0 : i64", "@STEP", None, captured=False)),
        Case("legacy_symbolic_without_count", symbolic(None, captured=False)),
        Case("reject_default_only_count", symbolic("2 : i64"), CODE),
        Case("reject_overstated_count", symbolic("5 : i64"), CODE),
        Case("reject_missing_count", symbolic(None), CODE),
        Case("reject_negative_count", symbolic("-1 : i64"), CODE),
        Case("reject_narrow_count", symbolic("4 : i32"), CODE),
        Case("reject_unsigned_count", symbolic("4 : ui64"), CODE),
        Case("reject_explicit_signed_count", symbolic("4 : si64"), CODE),
        Case("reject_boolean_count", symbolic("true"), CODE),
        Case("reject_string_count", symbolic('"4"'), CODE),
        Case("reject_float_count", symbolic("4.0 : f64"), CODE),
        Case("reject_wide_count", symbolic("4 : i128"), CODE),
        Case("reject_count_outside_int", symbolic("2147483648 : i64"), CODE),
        Case("reject_insufficient_declared_maximum", symbolic("4 : i64", maximum="3 : i64"), CODE),
        Case("reject_negative_declared_maximum", symbolic("4 : i64", maximum="-1 : i64"), CODE),
        Case("reject_bad_declared_maximum_type", symbolic("4 : i64", maximum='"4"'), CODE),
        Case("reject_wide_declared_maximum", symbolic("4 : i64", maximum="4 : i128"), CODE),
        Case("reject_declared_maximum_outside_int", symbolic("4 : i64", maximum="2147483648 : i64"), CODE),
        Case("reject_detached_count_contract", symbolic("4 : i64", captured=False), CODE),
        Case("reject_detached_maximum_contract", symbolic(None, maximum="4 : i64", captured=False), CODE),
        Case("reject_missing_count_with_maximum", symbolic(None, maximum="4 : i64"), CODE),
        Case("reject_literal_understatement", generate("0 : i64", "3 : i64", "1 : i64", "2 : i64"), CODE),
        Case("reject_empty_nonzero_count", generate("4 : i64", "4 : i64", "1 : i64", "1 : i64"), CODE),
        Case("reject_empty_missing_count", generate("4 : i64", "4 : i64", "1 : i64", None), CODE),
        Case("reject_same_parameter_nonzero_count", count + generate("@COUNT", "@COUNT", "1 : i64", "4 : i64"), CODE),
        Case("reject_count_overflow", generate("0 : i64", "2147483648 : i64", "1 : i64", "2147483647 : i64"), CODE),
        Case("reject_wide_distance_overflow", generate("-9223372036854775808 : i64", "9223372036854775807 : i64", "1 : i64", "0 : i64"), CODE),
        Case("reject_captured_negative_step", generate("4 : i64", "0 : i64", "-1 : i64", "4 : i64"), "NODAL-ITERATION-043-002"),
        Case("reject_symbolic_zero_step_interval", count + parameter("STEP", 1, 0, 2) + generate("0 : i64", "@COUNT", "@STEP", "4 : i64"), "NODAL-ITERATION-043-002"),
        Case("reject_direction_before_count", parameter("LOW", 0, 0, 8) + parameter("HIGH", 5, 4, 6) + generate("@LOW", "@HIGH", "1 : i64", "6 : i64"), "NODAL-ITERATION-043-003"),
        Case("reject_nested_literal_understatement", generate("0 : i64", "2 : i64", "1 : i64", "2 : i64", body=generate("0 : i64", "3 : i64", "1 : i64", "2 : i64", identity="Fixture.outer.inner")), CODE),
        Case("reject_nested_symbolic_understatement", count + generate("0 : i64", "2 : i64", "1 : i64", "2 : i64", body=generate("0 : i64", "@COUNT", "1 : i64", "2 : i64", identity="Fixture.outer.inner")), CODE),
    ]
    return result


def source(case: Case) -> str:
    return ('module { "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> '
            '({\n^bb0:\n' + case.body + '}) : () -> ()\n}\n')


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def retained_contracts(data: bytes) -> Counter:
    numeric = re.findall(
        rb"\b(lower|upper|step|maximum_trip_count|declared_maximum)\s*=\s*"
        rb"(@[A-Za-z_][A-Za-z_0-9]*|-?[0-9]+\s*:\s*[us]?i[0-9]+)", data)
    ownership = re.findall(rb'\b(region_id|induction_path)\s*=\s*("[^"\n]+")', data)
    return Counter(numeric + ownership)


def valid_output(case: Case, data: bytes, exit_code: int, stdout: bytes, stderr: bytes) -> bool:
    codes = set(re.findall(rb"NODAL-[A-Z0-9]+(?:-[A-Z0-9]+)+", stderr))
    if case.code is not None:
        return exit_code == 1 and codes == {case.code.encode()}
    return (exit_code == 0 and not codes and
            stdout.count(b'"nodal.generate"') == case.body.count('"nodal.generate"') and
            retained_contracts(stdout) == retained_contracts(data))


def main(matrix_factory=cases, validator=valid_output,
         schema="nodal.f043.generate-count-matrix.v1") -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    compiler = args.nodalc.resolve(strict=True)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    matrix = matrix_factory()
    if len({case.name for case in matrix}) != len(matrix):
        raise ValueError("duplicate generate-count fixture identity")
    records = []
    failed = False
    for case in matrix:
        path = args.work_dir / f"{case.name}.mlir"
        data = source(case).encode()
        path.write_bytes(data)
        runs = []
        for attempt in range(2 if case.code is None else 1):
            try:
                result = subprocess.run([str(compiler), PIPELINE, str(path.resolve())],
                                        capture_output=True, timeout=10, check=False)
                stdout, stderr = result.stdout, result.stderr
                valid = validator(case, data, result.returncode, stdout, stderr)
                exit_code = result.returncode
                error = None
            except (subprocess.TimeoutExpired, OSError) as exception:
                stdout = b""
                stderr = str(exception).encode()
                exit_code = None
                error = type(exception).__name__
                valid = False
            stem = f"{case.name}.{attempt + 1}"
            (args.work_dir / f"{stem}.stdout").write_bytes(stdout)
            (args.work_dir / f"{stem}.stderr").write_bytes(stderr)
            runs.append({"exit_code": exit_code, "expected_code": case.code,
                         "valid": bool(valid), "process_error": error,
                         "stdout_sha256": digest(stdout), "stderr_sha256": digest(stderr)})
        deterministic = case.code is not None or runs[0]["stdout_sha256"] == runs[1]["stdout_sha256"]
        valid = all(run["valid"] for run in runs) and deterministic
        records.append({"name": case.name, "source_sha256": digest(data),
                        "valid": valid, "deterministic": deterministic, "runs": runs})
        failed |= not valid
        print(f"{'PASS' if valid else 'FAIL'} {case.name}", flush=True)
    report = {"schema": schema,
              "compiler_sha256": digest(compiler.read_bytes()), "cases": records,
              "passed": not failed}
    (args.work_dir / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
