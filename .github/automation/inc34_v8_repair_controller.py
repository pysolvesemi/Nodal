#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text and new not in text:
        raise SystemExit(f"missing repair anchor: {label}")
    return text.replace(old, new, 1)


def main() -> int:
    automation = Path(__file__).resolve().parent
    old_test = "core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala"
    new_test = (
        "core/scala/testkit/test/src/nodal/internal/testkit/"
        "ScalaToMlirBridgeTests.scala"
    )

    for path in automation.iterdir():
        if path.suffix not in {".py", ".sh"} or path.name == Path(__file__).name:
            continue
        text = path.read_text(encoding="utf-8")
        if old_test in text:
            path.write_text(text.replace(old_test, new_test), encoding="utf-8")

    scala_patch = automation / "inc34_patch_scala.py"
    text = scala_patch.read_text(encoding="utf-8")
    text = replace_required(
        text,
        '''        throw new IllegalArgumentException(
          s"structured analog operation '$identity' is missing '$role' payload"
        )''',
        '''        scala.util.Failure[AnalogProceduralRuntime.ControlExpressionRecord](
          new IllegalArgumentException(
            s"structured analog operation '$identity' is missing '$role' payload"
          )
        ).get''',
        "structured expression failure",
    )
    text = replace_required(
        text,
        '''            throw new IllegalArgumentException(
              s"structured declaration '${declaration.variable}' has no variable record"
            )''',
        '''            scala.util.Failure[AnalogProceduralRuntime.VariableRecord](
              new IllegalArgumentException(
                s"structured declaration '${declaration.variable}' has no variable record"
              )
            ).get''',
        "structured declaration failure",
    )
    text = replace_required(
        text,
        '''            throw new IllegalArgumentException(
              s"structured read '${read.variable}' has no variable record"
            )''',
        '''            scala.util.Failure[AnalogProceduralRuntime.VariableRecord](
              new IllegalArgumentException(
                s"structured read '${read.variable}' has no variable record"
              )
            ).get''',
        "structured read failure",
    )
    scala_patch.write_text(text, encoding="utf-8")

    post = automation / "inc34_post_repair.py"
    text = post.read_text(encoding="utf-8")
    text = replace_required(
        text,
        '        text = replace_once(text, old, new, "conditional arm owner check")',
        '''        if old not in text:
            raise SystemExit("conditional arm owner check marker was not found")
        text = text.replace(old, new, 1)''',
        "conditional arm deterministic replacement",
    )
    text = replace_required(
        text,
        '        text = replace_once(text, old, new, "case arm owner check")',
        '''        case_marker = "LogicalResult nodal::AnalogCaseOp::verify()"
        if case_marker not in text:
            raise SystemExit("case operation verifier was not found")
        prefix, case_body = text.split(case_marker, 1)
        if old not in case_body:
            raise SystemExit("case arm owner check marker was not found")
        text = prefix + case_marker + case_body.replace(old, new, 1)''',
        "case arm deterministic replacement",
    )
    post.write_text(text, encoding="utf-8")

    dataflow_patch = automation / "inc34_patch_native_dataflow.py"
    text = dataflow_patch.read_text(encoding="utf-8")
    text = replace_required(
        text,
        '''            "analog-control-flow-rejects-missing-else",
            "missing-default",
            "zero-trip",
            "continue-path",
            "NODAL-ANALOG-034-004",''',
        '''            "NAME nodal.native.analog-control-flow-rejects-${_fixture}",
            "foreach(_fixture IN ITEMS missing-else missing-default zero-trip continue-path)",
            "NODAL-ANALOG-034-004",''',
        "templated native dataflow CMake contract",
    )
    dataflow_patch.write_text(text, encoding="utf-8")

    contract = automation / "inc34_patch_native_dataflow_contract_repair.py"
    text = contract.read_text(encoding="utf-8")
    text = replace_required(
        text,
        "integration 'native_branch_sensitive_definite_assignment' is not complete",
        "completed integration 'native_branch_sensitive_definite_assignment' is not recorded",
        "native dataflow evidence assertion",
    )
    contract.write_text(text, encoding="utf-8")

    runner = automation / "inc34_run_native_dataflow_v6.sh"
    text = runner.read_text(encoding="utf-8")
    text = text.replace(
        "automation/inc34-native-dataflow-results-v6",
        "automation/inc34-native-dataflow-results-v8",
    )
    text = text.replace(
        "# Increment 34 native dataflow v6",
        "# Increment 34 native dataflow v8",
    )
    text = text.replace(
        "structured native dataflow v6",
        "structured native dataflow v8",
    )
    text = text.replace(
        "record native dataflow v6",
        "record native dataflow v8",
    )
    runner.write_text(text, encoding="utf-8")

    print("Increment 34 native-dataflow v8 controller repairs applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
