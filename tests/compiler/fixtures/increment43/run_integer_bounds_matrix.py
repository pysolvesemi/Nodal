#!/usr/bin/env python3
"""Exercise F-043 integer envelopes through the real parameter-verification pass."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Case:
    name: str
    body: str
    code: str | None = None


def literal(name: str, value: int, kind: str = "si64") -> str:
    return (f'%{name} = "nodal.const_literal"() <{{metadata = {{}}, '
            f'spelling = "{value}", value = {value} : {kind}}}> : () -> {kind}\n')


def parameter(
    name: str, initial: int, lower: int | str | None, upper: int | str | None,
    *, kind: str = "si64", structural: bool = True, fixed: bool = False,
    value: str | None = None,
) -> str:
    classification = "structural" if structural else "ordinary"
    variability = "fixed" if fixed else "symbolic"
    text = (f'"nodal.parameter"() <{{classification = "{classification}", '
            f'default_value = {initial} : {kind}, metadata = {{}}, parameter_kind = "integer", '
            f'sym_name = "{name}", type = {kind}, variability = "{variability}"}}> : () -> ()\n')
    if value is not None:
        text += (f'"nodal.parameter_value"(%{value}) <{{metadata = {{}}, parameter = @{name}}}>'
                 f' : ({kind}) -> ()\n')
    if lower is not None and upper is not None:
        operands = []
        for suffix, bound in (("lower", lower), ("upper", upper)):
            if isinstance(bound, int):
                identity = f"{name}_{suffix}"
                text += literal(identity, bound, kind)
            else:
                identity = bound
            operands.append(f"%{identity}")
        text += (f'"nodal.parameter_constraint"({", ".join(operands)}) '
                 f'<{{constraint_kind = "range", lower_inclusive = true, metadata = {{}}, '
                 f'parameter = @{name}, upper_inclusive = true}}> : ({kind}, {kind}) -> ()\n')
    if structural:
        text += (f'"nodal.parameter_envelope"() <{{effects = ["shape", "topology"], '
                 f'metadata = {{}}, parameter = @{name}, policy = "static_generate"}}> : () -> ()\n')
    return text


def reference(name: str, target: str, kind: str = "si64") -> str:
    return (f'%{name} = "nodal.const_parameter_ref"() <{{metadata = {{}}, '
            f'parameter = @{target}}}> : () -> {kind}\n')


def expression(name: str, op: str, left: str, right: str, kind: str = "si64") -> str:
    return (f'%{name} = "nodal.const_expr"(%{left}, %{right}) '
            f'<{{metadata = {{}}, operator_name = "{op}"}}> : ({kind}, {kind}) -> {kind}\n')


def generate(lower: str = "0 : i64", upper: str = "@COUNT", step: str = "1 : i64",
             body: str = "", induction: str = "lane") -> str:
    return (f'"nodal.generate"() <{{induction = "{induction}", lower = {lower}, '
            f'metadata = {{}}, step = {step}, upper = {upper}}}> ({{\n^bb0:\n{body}'
            f'}}) : () -> ()\n')


def cases() -> list[Case]:
    count = parameter("COUNT", 4, 1, 8)
    base = parameter("BASE", 2, 1, 8, structural=False) + reference("base", "BASE")
    total = literal("two", 2) + expression("sum", "add", "base", "two")
    total += parameter("TOTAL", 4, 1, 16, structural=False, fixed=True, value="sum")
    cycle = (parameter("N", 2, 1, "m") + parameter("M", 2, 1, "n"))
    # References precede constraints in SSA, while parameter symbols remain
    # module declarations. Symbol lookup is independent of declaration order.
    cycle = reference("m", "M") + reference("n", "N") + cycle
    maximum = 9223372036854775807
    overflow = (parameter("N", 1, 1, maximum, structural=False) + reference("n", "N") +
                literal("one", 1) + expression("sum", "add", "n", "one") +
                parameter("TOTAL", 2, 1, maximum, structural=False, fixed=True, value="sum"))
    narrow = (parameter("N", 1, 1, 127, kind="si8", structural=False) +
              reference("n", "N", "si8") + literal("one", 1, "si8") +
              expression("sum", "add", "n", "one", "si8") +
              parameter("TOTAL", 2, 1, 127, kind="si8", structural=False, fixed=True, value="sum"))
    varying_divisor = (parameter("N", 5, 0, 10, structural=False) + reference("n", "N") +
                       literal("ten", 10) + expression("quotient", "div", "ten", "n") +
                       parameter("TOTAL", 2, 0, 10, structural=False, fixed=True, value="quotient"))
    varying_remainder = (parameter("N", 3, 0, 5, structural=False) + reference("n", "N") +
                         literal("ten", 10) + expression("remainder", "mod", "ten", "n") +
                         parameter("TOTAL", 1, 0, 10, structural=False, fixed=True, value="remainder"))
    total += reference("total", "TOTAL", "si64") + parameter("COUNT", 1, 0, "total", kind="si64")
    overflow += reference("total", "TOTAL", "si64") + parameter("COUNT", 1, 0, "total", kind="si64")
    narrow += reference("total", "TOTAL", "si8") + parameter("COUNT", 1, 0, "total", kind="si8")
    varying_divisor += reference("total", "TOTAL", "si64") + parameter("COUNT", 1, 0, "total", kind="si64")
    varying_remainder += reference("total", "TOTAL", "si64") + parameter("COUNT", 1, 0, "total", kind="si64")
    enumless_bool = '''
"nodal.parameter"() <{classification = "structural", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "COUNT", type = i1, variability = "symbolic"}> : () -> ()
%false = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = false}> : () -> i1
%true = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = true}> : () -> i1
"nodal.parameter_constraint"(%false, %true) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i1, i1) -> ()
"nodal.parameter_envelope"() <{effects = ["shape"], metadata = {}, parameter = @COUNT, policy = "static_generate"}> : () -> ()
'''
    return [
        Case("literal_legacy_ascending", generate(upper="4 : i64")),
        Case("literal_legacy_descending", generate("4 : i64", "0 : i64", "-1 : i64")),
        Case("literal_empty", generate("4 : i64", "4 : i64")),
        Case("symbolic_positive", count + generate()),
        Case("symbolic_singleton_envelope", parameter("COUNT", 1, 1, 1) + generate()),
        Case("symbolic_empty_same_identity", count + generate("@COUNT", "@COUNT")),
        Case("symbolic_positive_step", count + parameter("STEP", 1, 1, 3) + generate(step="@STEP")),
        Case("unsigned_literal_step_keeps_sign", count + generate(step="255 : ui8")),
        Case("symbolic_negative_step", parameter("STEP", -1, -3, -1) +
             generate("8 : i64", "0 : i64", "@STEP")),
        Case("all_symbolic_monotone", parameter("LOW", 1, 0, 2) +
             parameter("HIGH", 7, 4, 8) + parameter("STEP", 1, 1, 2) +
             generate("@LOW", "@HIGH", "@STEP")),
        Case("fixed_expression_keeps_variability", base + total + generate()),
        Case("parameter_dependent_range", base + parameter("COUNT", 2, 1, "base") + generate()),
        Case("nested_symbolic_positive", count + generate(upper="2 : i64", body=generate(induction="inner"))),
        Case("step_can_be_zero_despite_default_one", count + parameter("STEP", 1, 0, 2) +
             generate(step="@STEP"), "NODAL-ITERATION-043-002"),
        Case("step_can_change_sign", count + parameter("STEP", 1, -2, 2) +
             generate(step="@STEP"), "NODAL-ITERATION-043-002"),
        Case("direction_changes_under_override", parameter("LOW", 0, 0, 8) +
             parameter("HIGH", 5, 4, 6) + generate("@LOW", "@HIGH"), "NODAL-ITERATION-043-003"),
        Case("opposed_negative_direction", count + parameter("STEP", -1, -2, -1) +
             generate(step="@STEP"), "NODAL-ITERATION-043-003"),
        Case("unbounded_dependent_parameter", parameter("BASE", 4, None, None, structural=False) +
             reference("base", "BASE") + parameter("COUNT", 2, 1, "base") +
             generate(), "NODAL-ITERATION-043-001"),
        Case("cyclic_constraints_not_default_folding", cycle + generate(upper="@N"), "NODAL-ITERATION-043-001"),
        Case("host_overflow_outside_default", overflow + generate(), "NODAL-ITERATION-043-001"),
        Case("storage_overflow_outside_default", narrow + generate(), "NODAL-ITERATION-043-001"),
        Case("division_by_zero_outside_default", varying_divisor + generate(), "NODAL-ITERATION-043-001"),
        Case("remainder_by_zero_outside_default", varying_remainder + generate(), "NODAL-ITERATION-043-001"),
        Case("boolean_is_not_integer_bound", enumless_bool + generate(), "NODAL-ITERATION-043-001"),
        Case("malformed_bound_is_not_dynamic_fallback", count + generate(lower='"runtime"'), "NODAL-ITERATION-043-001"),
        Case("unsigned_literal_not_sign_reinterpreted", count + generate(step="18446744073709551615 : ui64"), "NODAL-ITERATION-043-001"),
        Case("wide_literal_not_narrowed", count + generate(lower="18446744073709551616 : i129"), "NODAL-ITERATION-043-001"),
        Case("nested_unknown_symbol", generate(upper="2 : i64", body=generate(upper="@MISSING", induction="inner")),
             "NODAL-PARAMETER-STRUCTURAL-001"),
        Case("nested_ordinary_parameter", parameter("COUNT", 4, 1, 8, structural=False) +
             generate(upper="2 : i64", body=generate(induction="inner")), "NODAL-PARAMETER-STRUCTURAL-001"),
        Case("nested_invalid_step", count + parameter("STEP", 1, 0, 2) +
             generate(upper="2 : i64", body=generate(step="@STEP", induction="inner")), "NODAL-ITERATION-043-002"),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    compiler = args.nodalc.resolve(strict=True)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    records = []
    for case in cases():
        source = (f'module {{ "nodal.module"() <{{metadata = {{}}, sym_name = "Fixture"}}> '
                  f'({{\n{case.body}}}) : () -> () }}\n')
        path = args.work_dir / f"{case.name}.mlir"
        path.write_text(source, encoding="utf-8")
        command = [str(compiler), "--pass-pipeline=builtin.module(nodal-verify-parameters)", str(path)]
        execution_error = None
        try:
            run = subprocess.run(command, capture_output=True, text=True, timeout=10, check=False)
        except (subprocess.TimeoutExpired, OSError) as error:
            execution_error = type(error).__name__
            run = subprocess.CompletedProcess(command, -1, "", str(error))
        (args.work_dir / f"{case.name}.stdout").write_text(run.stdout, encoding="utf-8")
        (args.work_dir / f"{case.name}.stderr").write_text(run.stderr, encoding="utf-8")
        symbols = re.findall(r"(?:lower|upper|step) = @[A-Za-z0-9_]+", case.body)
        retained = (run.stdout.count('"nodal.generate"') == case.body.count('"nodal.generate"')
                    and all(symbol in run.stdout for symbol in symbols))
        accepted = run.returncode == 0 and retained
        matched = accepted if case.code is None else run.returncode == 1 and case.code in run.stderr
        repeat_record = None
        if case.code is None and accepted:
            try:
                repeat = subprocess.run(command, capture_output=True, text=True, timeout=10, check=False)
                (args.work_dir / f"{case.name}.repeat.stdout").write_text(repeat.stdout, encoding="utf-8")
                (args.work_dir / f"{case.name}.repeat.stderr").write_text(repeat.stderr, encoding="utf-8")
                repeat_record = {"returncode": repeat.returncode,
                                 "stdout_sha256": hashlib.sha256(repeat.stdout.encode()).hexdigest(),
                                 "stderr_sha256": hashlib.sha256(repeat.stderr.encode()).hexdigest()}
                matched = matched and repeat.returncode == 0 and repeat.stdout == run.stdout
            except (subprocess.TimeoutExpired, OSError) as error:
                execution_error = type(error).__name__
                repeat_record = {"execution_error": execution_error, "detail": str(error)}
                matched = False
        records.append({"case": case.name, "expected_code": case.code, "returncode": run.returncode,
                        "matched": matched, "execution_error": execution_error,
                        "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "stdout_sha256": hashlib.sha256(run.stdout.encode()).hexdigest(),
                        "stderr_sha256": hashlib.sha256(run.stderr.encode()).hexdigest(),
                        "repeat": repeat_record})
        print(f'{"PASS" if matched else "FAIL"} {case.name}: exit={run.returncode}', flush=True)
    summary = {"schema": 1, "compiler": str(compiler),
               "compiler_sha256": hashlib.sha256(compiler.read_bytes()).hexdigest(), "cases": records,
               "passed": sum(record["matched"] for record in records), "total": len(records)}
    (args.work_dir / "results.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f'INTEGER_BOUNDS_MATRIX passed={summary["passed"]} total={summary["total"]}', flush=True)
    return 0 if summary["passed"] == summary["total"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
