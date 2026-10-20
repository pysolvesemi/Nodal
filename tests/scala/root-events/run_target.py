#!/usr/bin/env python3
"""Lower actual Scala-exported root-event witnesses and simulate generated HDL.

Inputs are emitted by Increment57EventSourceExport. Only the testbench is handwritten;
the DUT always comes from the current Nodal -> CIRCT -> SystemVerilog path.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def bench(name: str, module: str, payload_port: str = "") -> str:
    rising = name in ("frozen", "rising")
    idle, active = (0, 1) if rising else (1, 0)
    if name == "frozen":
        declarations = "wire observed;"
        connections = ".trigger(trigger), .observed(observed)"
        checks = """
  #1; if(observed !== 1'bx) $fatal(1,"fabricated initialized startup");
  #2; if(observed !== 1'bx) $fatal(1,"updated without an event");
  active_edge; if(observed !== 1'b1) $fatal(1,"frozen fixture did not update");
  inactive_edge; #3;
  if(observed !== 1'b1) $fatal(1,"frozen output did not hold");
  active_edge; if(observed !== 1'b1) $fatal(1,"second event changed true");
"""
        tasks = ""
    elif name in ("rising", "falling"):
        declarations = ("reg seed=1; reg [7:0] first=8'h13, second=8'ha6;\n"
                        "wire [7:0] left, right;")
        connections = (".trigger(trigger), .seed(seed), .first(first), .second(second), "
                       ".left(left), .right(right)")
        tasks = """
task check(input [7:0] a, input [7:0] b); begin
  if(left !== a || right !== b)
    $fatal(1,"pair expected %h/%h got %h/%h at %t",a,b,left,right,$time);
end endtask
"""
        checks = """
  #1; check(8'hxx,8'hxx);
  // The first legal event seeds actual state through a pure hardware select.
  // No hierarchical assignment, force, reset or initialization touches the DUT.
  active_edge; check(8'h13,8'ha6);
  first=8'h3c; second=8'h5d; #2; check(8'h13,8'ha6);
  inactive_edge; check(8'h13,8'ha6);
  seed=0; #2; check(8'h13,8'ha6);
  active_edge; check(8'ha6,8'h13);
  first=0; second=255; #2; check(8'ha6,8'h13);
  inactive_edge; check(8'ha6,8'h13);
  active_edge; check(8'h13,8'ha6);
  inactive_edge; check(8'h13,8'ha6);
  active_edge; check(8'ha6,8'h13);
"""
    else:
        declarations = "reg [3:0] payload=4'ha; wire [3:0] result;"
        connections = f".trigger(trigger), .{payload_port}(payload), .result(result)"
        tasks = """
task check(input [3:0] expected); begin
  if(result !== expected)
    $fatal(1,"aggregate expected %h got %h at %t",expected,result,$time);
end endtask
"""
        checks = """
  #1; check(4'hx);
  active_edge; check(4'ha);
  payload=4'h5; #2; check(4'ha);
  inactive_edge; check(4'ha);
  active_edge; check(4'h5);
  payload=4'hf; #2; check(4'h5);
  inactive_edge; check(4'h5);
  active_edge; check(4'hf);
"""
    return f"""module tb;
reg trigger={idle};
{declarations}
{module} dut({connections});
task active_edge; begin #2; trigger={active}; #1; end endtask
task inactive_edge; begin #2; trigger={idle}; #1; end endtask
{tasks}
initial begin
{checks}
  $display("EVENT_TARGET_PASS {name}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated artifacts must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"status": "running", "cases": []}
    manifest_path = args.out / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str]) -> str:
        (args.out / f"{label}.command.json").write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / f"{label}.log").write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    for name in ("frozen", "rising", "falling", "aggregate"):
        source = args.input / f"{name}.mlir"
        if not source.is_file() or '"nodal.event_process"' not in source.read_text():
            parser.error(f"missing actual Scala event replay: {source}")
        gated = args.out / f"{name}.gated.mlir"
        circt = args.out / f"{name}.circt.mlir"
        sv = args.out / f"{name}.sv"
        # Gate the actual source replay before target normalization can remove
        # declarations or operands. This exercises ordinary semantic admission,
        # including the independently verified root-event @none boundaries.
        run(name + "-gate", [str(args.nodalc), str(source), "--nodal-gate-default",
                            "--mlir-print-debuginfo", "-o", str(gated)])
        run(name + "-lower", [str(args.nodalc), str(gated), "--nodal-sequential-to-circt",
                             "--mlir-print-debuginfo", "-o", str(circt)])
        run(name + "-export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                              "--verify-each", "--disable-all-randomization", "-o", str(sv)])
        design = sv.read_text()
        match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", design)
        if match is None:
            raise RuntimeError(f"{name}: no actual generated hardware module")
        for required in ("NODAL root event", "known_levels_definite_edges",
                         "external_sampling=user_setup_hold", "startup=unspecified",
                         "updates=simultaneous_pre_event"):
            if required not in design:
                raise RuntimeError(f"{name}: missing retained event obligation {required}")
        if name == "aggregate" and "NODAL packed ABI" not in design:
            raise RuntimeError("aggregate: existing finite packed ABI was not retained")
        expected_edge = "posedge" if name in ("frozen", "rising") else "negedge"
        selected_edge = bool(re.search(r"\b" + expected_edge + r"\s+trigger\b", design))
        if expected_edge == "negedge":
            # seq.clock_inv may export as a private inverter followed by a
            # positive-edge register. Check its actual connection, not a
            # presumed spelling; simulation still proves both edge/hold cases.
            inverted = re.findall(
                r"\bwire\s+([A-Za-z_][A-Za-z_0-9]*)\s*=\s*~trigger\s*;", design)
            selected_edge |= any(re.search(r"\bposedge\s+" + re.escape(clock) + r"\b", design)
                                 for clock in inverted)
        if not selected_edge:
            raise RuntimeError(f"{name}: selected {expected_edge} trigger was not generated")
        payload_port = ""
        if name == "aggregate":
            # CIRCT may legalize the Scala name "input", a Verilog keyword.
            # Read the actual sole four-bit input, never guess its legal name.
            inputs = re.findall(r"\binput\s+\[3:0\]\s+([A-Za-z_][A-Za-z_0-9]*)", design)
            if len(inputs) != 1:
                raise RuntimeError("aggregate: expected one actual four-bit payload input")
            payload_port = inputs[0]
        tb = args.out / f"{name}.tb.sv"
        tb.write_text(bench(name, match.group(1), payload_port))
        executable = args.out / f"{name}.vvp"
        run(name + "-compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o",
                               str(executable), str(sv), str(tb)])
        result = run(name + "-simulate", [str(args.vvp), str(executable)])
        if f"EVENT_TARGET_PASS {name}" not in result:
            raise RuntimeError(f"{name}: simulation did not reach the success assertion")
        manifest["cases"].append({
            "name": name,
            "input_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest(),
        })
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"PASS {name}", flush=True)
    manifest["status"] = "passed"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
