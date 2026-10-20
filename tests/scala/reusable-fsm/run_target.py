#!/usr/bin/env python3
"""Generate and simulate the actual two-instance reusable-FSM Scala witness."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must stay under .work")
    for name in ("input", "nodalc", "firtool", "iverilog", "vvp"):
        path = getattr(args, name).resolve()
        if not path.is_file():
            parser.error(f"{name} does not exist: {path}")
        setattr(args, name, path)
    args.out.mkdir(parents=True, exist_ok=True)
    args.out = args.out.resolve()
    source = args.input.read_text()
    if source.count('"nodal.flat_fsm"') != 2:
        raise RuntimeError("expected the actual two-instance Scala witness")
    if source.count('illegal_policy = "recover_to_initial"') != 2:
        raise RuntimeError("Icarus replay requires the explicit recovery-policy Scala witness; "
                           "Error-policy assertions must not be disabled")
    manifest = {
        "status": "running",
        "source_sha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
        "tools": {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest()
                  for name in ("nodalc", "firtool", "iverilog", "vvp")},
    }

    def save() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str]) -> str:
        (args.out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / f"{label}.log").write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}\n{result.stdout[-8000:]}")
        return result.stdout

    save()
    gated = args.out / "reusable.gated.mlir"
    circt = args.out / "reusable.circt.mlir"
    sv = args.out / "reusable.sv"
    run("gate", [str(args.nodalc), str(args.input), "--nodal-gate-default",
                 "--mlir-print-debuginfo", "-o", str(gated)])
    run("lower", [str(args.nodalc), str(gated), "--nodal-sequential-to-circt",
                  "--mlir-print-debuginfo", "-o", str(circt)])
    run("export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                   "--verify-each", "--disable-all-randomization",
                   "--lowering-options=disallowLocalVariables", "-o", str(sv)])
    design = sv.read_text()
    modules = re.findall(r"\bmodule\s+([A-Za-z_]\w*)\s*\(", design)
    if len(modules) != 1:
        raise RuntimeError(f"expected one generated module, got {modules}")
    top = modules[0]
    state_registers = re.findall(r"\breg\s+\[1:0\]\s+(register_\w+)\s*;", design)
    if len(state_registers) != 1:
        raise RuntimeError("expected exactly one compact private state register")
    compact_state = state_registers[0]
    bench = f"""module tb;
reg clock=0, reset=1, requestA=0, requestB=0, finish=0;
reg [7:0] data=0;
wire doneA, doneB;
wire [1:0] stateA, stateB;
wire [7:0] valueA, valueB;
{top} dut(.clock(clock), .reset(reset), .requestA(requestA),
  .requestB(requestB), .finish(finish), .input_0(data), .doneA(doneA), .doneB(doneB),
  .stateA(stateA), .stateB(stateB), .valueA(valueA), .valueB(valueB));
task tick; begin #2; clock=1; #1; clock=0; #1; end endtask
task check(input [1:0] a, input [1:0] b, input [7:0] x, input [7:0] y); begin
  if(stateA !== a || stateB !== b || valueA !== x || valueB !== y)
    $fatal(1,"state/value mismatch: %d %d %d %d",stateA,stateB,valueA,valueB);
  if(doneA !== (a==2) || doneB !== (b==2))
    $fatal(1,"typed completion does not match terminal membership");
end endtask
initial begin
  tick; check(0,0,0,0); reset=0;
  requestA=1; tick; check(1,0,4,0);
  requestA=0; requestB=1; data=17; tick; check(1,1,17,4);
  requestB=0; finish=1; data=29; tick; check(2,2,29,29);
  finish=0; data=44; tick; check(2,2,29,29);
  force dut.{compact_state}=2'b11; #1;
  if(doneA !== 0 || stateA !== 0) $fatal(1,"invalid state must not report completion");
  release dut.{compact_state}; tick; check(0,2,29,29);
  reset=1; #1; check(0,2,29,29); tick; check(0,0,0,0);
  $display("REUSABLE_FSM_TARGET_PASS"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""
    tb = args.out / "tb.sv"
    tb.write_text(bench)
    binary = args.out / "simulation"
    run("compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o", str(binary),
                    str(sv), str(tb)])
    output = run("simulate", [str(args.vvp), str(binary)])
    if "REUSABLE_FSM_TARGET_PASS" not in output or re.search(r"ERROR|FATAL", output):
        raise RuntimeError("generated target did not satisfy the runtime checks\n" + output)
    manifest["status"] = "passed"
    save()
    print(f"REUSABLE_FSM_TARGET_PASS {args.out}")


if __name__ == "__main__":
    main()
