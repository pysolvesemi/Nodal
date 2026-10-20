#!/usr/bin/env python3
"""Execute actual Scala-exported F58 hierarchy through Nodal, CIRCT and Icarus.

Only the testbench is handwritten. The DUT is always generated from the maintained
Increment58HierarchyTargetReplay public-source fixtures.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


PROFILES = ("shared", "nested", "material", "resetless", "inherited-release")
SYMBOLIC_PROFILES = ("scalar-early", "scalar-late", "vector-early", "vector-late")


def bench(profile: str, top: str) -> str:
    if profile in SYMBOLIC_PROFILES:
        vector = profile.startswith("vector")
        first_bits, second_bits = (21, 6) if vector else (7, 3)
        return f"""module tb;
reg [{first_bits - 1}:0] first = 0;
reg [{second_bits - 1}:0] second = 0;
wire [{first_bits - 1}:0] first_out;
wire [{second_bits - 1}:0] second_out;
{top} #(.width(7), .count(3)) wide(
  .core_clock(1'b0), .inputData(first), .outputData(first_out));
{top} #(.width(3), .count(2)) narrow(
  .core_clock(1'b0), .inputData(second), .outputData(second_out));
task check; begin
  #1;
  if(first_out !== first || second_out !== second)
    $fatal(1,"{profile}: generic parameter pass-through mismatch");
end endtask
initial begin
  if($bits(wide.inputData) != {first_bits} ||
     $bits(narrow.inputData) != {second_bits})
    $fatal(1,"{profile}: parameters collapsed to default widths");
  check;
  first='1; second='0; check;
  first='0; second='1; check;
  first='h155555; second='h2a; check;
  first='1; second='1; check;
  $display("HIERARCHY_TARGET_PASS {profile}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""
    if profile == "inherited-release":
        return f"""module tb;
reg root_clock=0, root_reset=1, inputValue=1;
wire observed;
{top} dut(.root_clock(root_clock), .root_reset(root_reset),
  .inputValue(inputValue), .output_0(observed));
task tick; begin #2; root_clock=1; #1; root_clock=0; #1; end endtask
task check(input expected); begin
  if(observed !== expected)
    $fatal(1,"inherited-release: expected %b got %b at %t",expected,observed,$time);
end endtask
initial begin
  #1; check(1'bx);
  root_reset=0; #1; check(0);
  root_reset=1; tick; check(0); tick; check(0); tick; check(1);
  // Both forwarding levels use the original release, not a new synchronizer.
  inputValue=0; #1; check(1); tick; check(0);
  inputValue=1; tick; check(1);
  root_reset=0; #1; check(0);
  root_reset=1; tick; check(0); tick; check(0); tick; check(1);
  $display("HIERARCHY_TARGET_PASS inherited-release"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""
    falling = profile == "material"
    resetless = profile == "resetless"
    second_idle, second_active = (1, 0) if falling else (0, 1)
    second_asserted, second_released = (0, 1) if falling else (1, 0)
    second_declaration = "" if resetless else f"reg second_reset={second_released};"
    second_connection = "" if resetless else ", .second_reset(second_reset)"
    second_reset = "" if resetless else f"second_reset={second_asserted};"
    second_release = "" if resetless else f"second_reset={second_released};"
    return f"""module tb;
reg first_clock=0, first_reset=0, firstData=0;
reg second_clock={second_idle}, secondData=0;
{second_declaration}
wire firstResult, secondResult;
{top} dut(.first_clock(first_clock), .first_reset(first_reset),
  .firstData(firstData), .firstResult(firstResult),
  .second_clock(second_clock){second_connection},
  .secondData(secondData), .secondResult(secondResult));
task first_tick; begin #2; first_clock=1; #1; first_clock=0; #1; end endtask
task second_tick; begin #2; second_clock={second_active}; #1;
  second_clock={second_idle}; #1; end endtask
task check(input a, input b); begin
  if(firstResult !== a || secondResult !== b)
    $fatal(1,"{profile}: expected %b/%b got %b/%b at %t",
      a,b,firstResult,secondResult,$time);
end endtask
initial begin
  #1; check(1'bx,1'bx);
  first_reset=1; {second_reset} #1; check(1'bx,1'bx);
  first_tick; check(0,1'bx);
  second_tick; check(0,0);
  first_reset=0; {second_release}
  firstData=1; #2; check(0,0);
  first_tick; check(1,0);
  secondData=1; #2; check(1,0);
  second_tick; check(1,1);
  firstData=0; second_tick; check(1,1);
  first_tick; check(0,1);
  firstData=1; secondData=0; #2; check(0,1);
  first_tick; check(1,1);
  second_tick; check(1,0);
  // Reset is an actual independent synchronous control, never elaborated init.
  first_reset=1; #1; check(1,0);
  secondData=1; second_tick; check(1,1);
  first_tick; check(0,1);
  first_reset=0; first_tick; check(1,1);
  $display("HIERARCHY_TARGET_PASS {profile}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--case", choices=PROFILES + SYMBOLIC_PROFILES, action="append")
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated artifacts must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest: dict = {"status": "running", "cases": []}
    manifest_path = args.out / "manifest.json"

    def save() -> None:
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str]) -> str:
        (args.out / f"{label}.command.json").write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / f"{label}.log").write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    save()
    for profile in args.case or PROFILES:
        source = args.input / f"{profile}.mlir"
        if not source.is_file():
            parser.error(f"missing actual Scala hierarchy replay: {source}")
        text = source.read_text()
        root = re.search(r'nodal\.root\.module\s*=\s*@(?:"([^"]+)"|([A-Za-z_]\w*))', text)
        if root is None or '"nodal.instance"' not in text or '"nodal.instance_port"' not in text:
            raise RuntimeError(f"{profile}: expected actual rooted Scala hierarchy")
        top = root.group(1) or root.group(2)
        if not re.fullmatch(r"[A-Za-z_]\w*", top):
            raise RuntimeError(f"{profile}: testbench requires a legal exported top identifier")
        gated = args.out / f"{profile}.gated.mlir"
        circt = args.out / f"{profile}.circt.mlir"
        sv = args.out / f"{profile}.sv"
        run(profile + "-gate", [str(args.nodalc), str(source), "--nodal-gate-default",
                               "--mlir-print-debuginfo", "-o", str(gated)])
        run(profile + "-lower", [str(args.nodalc), str(gated), "--nodal-sequential-to-circt",
                                "--mlir-print-debuginfo", "-o", str(circt)])
        run(profile + "-export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                                 "--verify-each", "--disable-all-randomization", "-o", str(sv)])
        design = sv.read_text()
        modules = re.findall(r"^module[ \t]+([A-Za-z_]\w*)\b", design, re.MULTILINE)
        expected_count = 2 if profile == "shared" or profile in SYMBOLIC_PROFILES else 3
        if top not in modules or len(modules) != expected_count:
            raise RuntimeError(f"{profile}: expected {expected_count} real reusable modules, "
                               f"including {top}; got {modules}")
        if not re.search(r"\bhw\.instance\b", circt.read_text()):
            raise RuntimeError(f"{profile}: no real hierarchical target instances")
        if profile not in SYMBOLIC_PROFILES and "__nodal_required_clock" not in design:
            raise RuntimeError(f"{profile}: inferred child requirement clock port missing")
        if profile in SYMBOLIC_PROFILES and not re.search(r"\bparameter\b", design):
            raise RuntimeError(f"{profile}: generic target parameters missing")
        tb = args.out / f"{profile}.tb.sv"
        tb.write_text(bench(profile, top))
        executable = args.out / f"{profile}.vvp"
        run(profile + "-compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o",
                                  str(executable), str(sv), str(tb)])
        result = run(profile + "-simulate", [str(args.vvp), str(executable)])
        if f"HIERARCHY_TARGET_PASS {profile}" not in result:
            raise RuntimeError(f"{profile}: simulation did not reach its success assertion")
        manifest["cases"].append({
            "name": profile,
            "top": top,
            "modules": modules,
            "input_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest(),
        })
        save()
        print(f"PASS {profile}", flush=True)
    manifest["status"] = "passed"
    save()


if __name__ == "__main__":
    main()
