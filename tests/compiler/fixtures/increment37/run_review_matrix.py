#!/usr/bin/env python3
"""Independent event-lowering regressions; no numerical event-scheduler claim."""
from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


def variable(name: str, order: int, initial: str = "0.0") -> str:
    return f'''%{name} = "nodal.analog_variable"() <{{identity = "Review.{name}", owner = "Review", declaration_order = {order} : i64, initialized = true, initializer_value = "{initial}", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], metadata = {{}}}}> : () -> !nodal.variable<"real", "1">'''


def assignment(name: str, target: str, order: int, rhs: str,
               reads: tuple[str, ...] = ()) -> str:
    operands = ", ".join([f"%{target}"] + [f"%{read}" for read in reads])
    types = ", ".join(['!nodal.variable<"real", "1">'] +
                      ['!nodal.quantity<"real", "1">'] * len(reads))
    return f'''"nodal.analog_assign"({operands}) <{{statement_id = "Review.{name}", owner = "Review", authored_order = {order} : i64, value_kind = "real", value_dimension = "1", analyses = ["dc", "transient"], guard_present = false, guard_value = "", guard_kind = "", guard_dimension = "", guard_reads = [], metadata = {{value = "{rhs}"}}}}> : ({types}) -> ()'''


def initial(name: str = "initial") -> str:
    return f'''%{name} = "nodal.analog_initial_step"() <{{event_id = "Review.{name}", owner = "Review", contract = "increment37", name = "", arguments = [], analyses = [], event_reads = [], metadata = {{}}}}> : () -> !nodal.analog_event'''


def on(name: str, event: str, body: str = "") -> str:
    return f'''"nodal.analog_on"(%{event}) <{{statement_id = "Review.{name}", owner = "Review", metadata = {{}}}}> ({{
^bb0:
{body}
}}) : (!nodal.analog_event) -> ()'''


def loop(name: str, body: str, count: int, static: bool = True) -> str:
    return f'''"nodal.analog_loop"() <{{bound_dimension = "1", bound_kind = "integer", bound_reads = [], bound_value = "{count}", maximum_iterations = {count} : i64, minimum_iterations = {count} : i64, metadata = {{}}, owner = "Review", stage = "{'static' if static else 'runtime'}", statement_id = "Review.{name}", static_trip_count = {count if static else 0} : i64, static_trip_count_present = {'true' if static else 'false'}}}> ({{
^bb0:
{body}
}}) : () -> ()'''


def module(body: str, continuous: str = "") -> str:
    return f'''module attributes {{nodal.target.profile = "analog"}} {{
"nodal.module"() <{{sym_name = "Review", metadata = {{}}}}> ({{
"nodal.analog"() <{{metadata = {{}}}}> ({{
"nodal.analog_procedure"() <{{owner = "Review", metadata = {{}}}}> ({{
^bb0:
{body}
}}) : () -> ()
{continuous}
}}) : () -> ()
}}) : () -> ()
}}'''


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-event-review-") as temporary:
        root = Path(temporary)

        def invoke(tool: Path, path: Path, *args: str) -> subprocess.CompletedProcess[str]:
            return subprocess.run([str(tool), *args, str(path)], capture_output=True,
                                  text=True, timeout=90, check=False)

        def accepted(label: str, text: str) -> str:
            nonlocal count
            path = root / f"{label}.mlir"
            path.write_text(text)
            result = invoke(nodalc, path)
            require(result.returncode == 0, f"{label}: {result.stderr}")
            optimized = invoke(nodalc, path, PIPELINE)
            require(optimized.returncode == 0, f"{label}: {optimized.stderr}")
            normalized = root / f"{label}-normalized.mlir"
            normalized.write_text(optimized.stdout)
            before = invoke(translate, path, "--nodal-to-verilog-a")
            after = invoke(translate, normalized, "--nodal-to-verilog-a")
            require(before.returncode == 0, f"{label}: {before.stderr}")
            require(after.returncode == 0 and after.stdout == before.stdout,
                    f"{label}: target changed after optimization: {after.stderr}")
            count += 1
            return before.stdout

        def rejected(label: str, text: str, code: str) -> None:
            nonlocal count
            path = root / f"{label}.mlir"
            path.write_text(text)
            for args in [(), (PIPELINE,)]:
                result = invoke(nodalc, path, *args)
                require(result.returncode != 0 and code in result.stderr,
                        f"{label}: expected {code}: {result.stderr}")
            for target in ["--nodal-to-verilog-a", "--nodal-to-verilog-ams"]:
                result = invoke(translate, path, target)
                require(result.returncode != 0 and not result.stdout.strip(),
                        f"{label}: rejected input published target output")
            count += 1

        # An explicitly captured read is a value, not a late reference to storage.
        read = '''%saved = "nodal.analog_variable_read"(%held) <{owner = "Review", read_id = "Review.saved", metadata = {}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">'''
        body = "\n".join([variable("held", 0, "1.0"), variable("sink", 1), initial(),
                           on("sample", "initial", "\n".join([
                               read, assignment("update", "held", 0, "2.0"),
                               assignment("restore", "sink", 1, "Review.held", ("saved",))]))])
        rendered = accepted("ordered-capture", module(body))
        expected = ["event_Review_saved = event_Review_held;",
                    "event_Review_held = 2.0;", "event_Review_sink = event_Review_saved;"]
        require(all(item in rendered for item in expected), "captured read target is missing")
        require([rendered.index(item) for item in expected] ==
                sorted(rendered.index(item) for item in expected), "captured read moved past a write")
        require("event_Review_sink = event_Review_held;" not in rendered,
                "captured assignment was incorrectly replaced by a live read")

        cross = '''%cross = "nodal.analog_cross"() <{event_id = "Review.cross", owner = "Review", contract = "increment37", name = "", arguments = [{slot = 0 : i64, value = "1.0", kind = "real", dimension = "1", reads = []}], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event'''

        # Static monitored loops must give each elaborated occurrence its own history.
        for trips in (0, 1, 3):
            body = loop("repeat", cross + "\n" + on("sample", "cross"), trips)
            rendered = accepted(f"static-monitor-{trips}", module(body))
            require("genvar event_Review_repeat_index;" in rendered,
                    "static monitor loop lost its genvar identity")
            require(f"event_Review_repeat_index < {trips};" in rendered,
                    "static monitor loop changed its trip count")
        nested = loop("outer", loop("inner", cross + "\n" + on("sample", "cross"), 3), 2)
        rendered = accepted("nested-static-monitors", module(nested))
        require(rendered.count("  genvar ") == 2, "nested monitor loops share a genvar")

        # A real crossing monitor cannot be evaluated in a runtime-dependent loop.
        rejected("runtime-monitor", module(loop("repeat", cross + "\n" + on("sample", "cross"), 3, False)),
                 "NODAL-ANALOG-037-001")
        # A monitor expression cannot be hoisted/delayed across a write to observed storage.
        crossing = cross.replace('value = "1.0"', 'value = "Review.held"').replace(
            'reads = []', 'reads = ["Review.held"]')
        rejected("monitor-write-order", module("\n".join([
            variable("held", 0), crossing, assignment("change", "held", 0, "1.0"),
            on("sample", "cross")])), "NODAL-ANALOG-037-005")

        # Recheck held storage from declarations and writes, not frontend continuity metadata.
        held = (ROOT / "core/compiler/test/IR/analog-events-held.mlir").read_text()
        mutations = [
            ("held-no-initializer", 'initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1"',
             'initialized = false, initializer_value = "", initializer_kind = "", initializer_dimension = ""'),
            ("held-dynamic-initializer", 'initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = []',
             'initializer_value = "EventTop.held", initializer_kind = "real", initializer_dimension = "1", initializer_reads = ["EventTop.held"]'),
        ]
        for label, old, new in mutations:
            require(old in held, f"missing held mutation anchor: {label}")
            path = root / f"{label}.mlir"
            path.write_text(held.replace(old, new, 1))
            for args in [(), (PIPELINE,)]:
                result = invoke(nodalc, path, *args)
                require(result.returncode != 0, f"{label}: unsafe held storage accepted")
            count += 1
        assignment_line = next(line for line in held.splitlines() if '"nodal.analog_assign"' in line)
        on_line = next(line for line in held.splitlines() if '"nodal.analog_on"(%either)' in line)
        no_writes = held.replace(assignment_line + "\n", "        ^bb0:\n")
        rejected("held-no-writes", no_writes, "NODAL-ANALOG-037-009")
        outside = no_writes.replace(on_line, assignment_line + "\n" + on_line)
        rejected("held-continuous-write", outside, "NODAL-ANALOG-037-009")

        # The sample/hold target has an independently written exact behavioral golden.
        if source:
            sample = Path(str(source) + ".held.mlir")
            rendered = accepted("source-sample-hold", sample.read_text())
            expected = '''module AnalogSampleHoldSource(ground, sampleIn, sampleOut);
  inout ground, sampleIn, sampleOut;
  electrical ground, sampleIn, sampleOut;
  parameter real initialVoltage = 0.25;
  parameter real samplePeriod = 2e-09;
  real event_AnalogSampleHoldSource_held = initialVoltage;
  real waveform_0;

  analog begin
    begin : event_AnalogSampleHoldSource_procedure
      @(initial_step or timer(0.0, samplePeriod)) begin
        event_AnalogSampleHoldSource_held = V(sampleIn, ground);
      end
    end
    waveform_0 = transition(event_AnalogSampleHoldSource_held, 0, 5e-10);
    V(sampleOut, ground) <+ waveform_0;
  end
endmodule
'''
            require(rendered[rendered.index("module AnalogSampleHoldSource("):] == expected,
                    "sample/hold target differs from independent expected behavior")

    print(f"Increment 37 lowering review matrix: {count} cases passed (no numerical solver claim)")
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source.resolve() if args.source else None)


if __name__ == "__main__":
    main()
