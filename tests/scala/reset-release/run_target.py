#!/usr/bin/env python3
"""Run seven real Scala-exported F57 reset witnesses through CIRCT and Icarus.

The input directory must contain output from Increment57ResetReplay, never
handwritten replacement designs. All generated artifacts stay under .work.
"""
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
    parser.add_argument("--include-shaped", action="store_true",
                        help="also run four fixed shaped-control source profiles")
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    cases = [(f"{edge}_{polarity}", edge == "Rising", polarity == "ActiveHigh", 2)
             for edge in ("Rising", "Falling") for polarity in ("ActiveHigh", "ActiveLow")]
    cases += [("cascade", True, True, 5), ("external", True, True, 0),
              ("mixed", True, False, 2)]
    if args.include_shaped:
        cases += [("shaped_" + mode, True, True, 2)
                  for mode in ("single", "combine", "matrix", "sync")]
    manifest = {"status": "running", "cases": []}

    def run(label: str, command: list[str]) -> str:
        (args.out / f"{label}.command.json").write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / f"{label}.log").write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    for name, rising, high, stages in cases:
        source = args.input / f"{name}.mlir"
        if not source.is_file():
            parser.error(f"missing actual Scala replay: {source}")
        circt = args.out / f"{name}.circt.mlir"
        sv = args.out / f"{name}.sv"
        run(name + "-lower", [str(args.nodalc), str(source), "--nodal-sequential-to-circt",
                             "--mlir-print-debuginfo", "-o", str(circt)])
        run(name + "-export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                              "--verify-each", "--disable-all-randomization", "-o", str(sv)])
        design = sv.read_text()
        match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", design)
        if match is None:
            raise RuntimeError(f"{name}: no generated hardware module")
        module = match.group(1)
        shaped = name.startswith("shaped_")
        expected = (["clocks", "resets", "done"] if shaped else
                    ["root_clock", "root_reset", "childClock", "done"])
        if name == "shaped_sync":
            expected = ["clocks", "resets", "doneA", "doneB"]
        if name == "mixed":
            expected += ["other_clock", "other_reset"]
        for port in expected:
            if not re.search(r"\b" + re.escape(port) + r"\b", design):
                raise RuntimeError(f"{name}: expected public source port {port} missing")
        if name == "external" and not re.search(
                r"// NODAL external reset release assumption: root rising physical clock root_clock;",
                design):
            raise RuntimeError("external provider release obligation was lost in target")
        idle, active = (0, 1) if rising else (1, 0)
        asserted, released = (1, 0) if high else (0, 1)
        clock = "root_clock" if name == "external" else "childClock"
        connections = ", ".join(f".{p}({p})" for p in expected)
        other_decl = "reg other_clock=0, other_reset=0;" if name == "mixed" else ""
        other_assert = "other_reset=1;" if name == "mixed" else ""
        other_release = "other_reset=0;" if name == "mixed" else ""
        mixed_hold = "tick; check(0); tick; check(0);" if name == "mixed" else ""
        bench = f'''module tb;
reg root_clock={idle}, childClock={idle}, root_reset={released};
{other_decl}
wire done;
{module} dut({connections});
task tick; begin #2; {clock}={active}; #1; {clock}={idle}; #1; end endtask
task check(input expected); begin
  if(done !== expected) $fatal(1,"{name}: expected %b got %b at %t",expected,done,$time);
end endtask
initial begin
  #1; if(done !== 1'bx) $fatal(1,"{name}: fabricated initialized startup");
  root_reset={asserted}; {other_assert} #1; check(0);
  root_reset={released}; {mixed_hold} {other_release}
  repeat({stages}) begin tick; check(0); end
  tick; check(1);
  #1; root_reset={asserted}; #1; check(0);
  root_reset={released};
  repeat({stages}) begin tick; check(0); end
  tick; check(1);
  $display("RESET_TARGET_PASS {name}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
'''
        if shaped:
            width, selected = (4, 2) if name == "shaped_matrix" else (2, 1)
            unused = 0
            other_reset = ("resets[0]=1; #1; check(0); resets[0]=0; "
                           "repeat(2) begin tick; check(0); end tick; check(1);"
                           if name == "shaped_combine" else
                           "resets[0]=1; #1; check(1); resets[0]=0; #1; check(1);")
            bench = f'''module tb;
reg [{width - 1}:0] clocks=0, resets=0;
wire done;
{module} dut({connections});
task tick; begin #2; clocks[{selected}]=1; #1; clocks[{selected}]=0; #1; end endtask
task other_tick; begin #2; clocks[{unused}]=1; #1; clocks[{unused}]=0; #1; end endtask
task check(input expected); begin
  if(done !== expected) $fatal(1,"{name}: expected %b got %b at %t",expected,done,$time);
end endtask
initial begin
  #1; if(done !== 1'bx) $fatal(1,"{name}: fabricated initialized startup");
  resets[{selected}]=1; #1; check(0);
  resets[{selected}]=0;
  repeat(4) begin other_tick; check(0); end
  repeat(2) begin tick; check(0); end
  tick; check(1);
  {other_reset}
  resets[{selected}]=1; #1; check(0); resets[{selected}]=0;
  repeat(2) begin tick; check(0); end
  tick; check(1);
  $display("RESET_TARGET_PASS {name}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
'''
        if name == "shaped_sync":
            bench = f'''module tb;
reg [1:0] clocks=2'b10, resets=0;
wire doneA, doneB;
{module} dut({connections});
task tickA; begin #2; clocks[0]=1; #1; clocks[0]=0; #1; end endtask
task tickB; begin #2; clocks[1]=0; #1; clocks[1]=1; #1; end endtask
task check(input a, input b); begin
  if(doneA !== a || doneB !== b)
    $fatal(1,"{name}: expected %b/%b got %b/%b",a,b,doneA,doneB);
end endtask
initial begin
  #1; check(1'bx,1'bx);
  resets=3; #1; check(1'bx,1'bx);
  tickA; check(0,1'bx); tickB; check(0,0);
  resets=0; tickA; check(1,0); tickB; check(1,1);
  resets[0]=1; #1; check(1,1);
  tickB; check(1,1); tickA; check(0,1);
  resets=2; tickA; check(1,1); tickB; check(1,0);
  resets=0; tickB; check(1,1);
  $display("RESET_TARGET_PASS {name}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
'''
        tb = args.out / f"{name}.tb.sv"
        tb.write_text(bench)
        executable = args.out / f"{name}.vvp"
        run(name + "-compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o",
                               str(executable), str(sv), str(tb)])
        result = run(name + "-simulate", [str(args.vvp), str(executable)])
        if f"RESET_TARGET_PASS {name}" not in result:
            raise RuntimeError(f"{name}: simulator did not reach the success assertion")
        manifest["cases"].append({"name": name, "input_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                                  "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest()})
        print(f"PASS {name}", flush=True)
    manifest["status"] = "passed"
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
