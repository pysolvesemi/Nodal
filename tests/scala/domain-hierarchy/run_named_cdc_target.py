#!/usr/bin/env python3
"""Check actual named Struct/Vec CDC replay through unchanged SV and four-state Icarus."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

SOURCE = Path("core/scala/testkit/test/src/nodal_external/Increment58NamedCdcSource.scala")
TOP = "Increment58NamedCdcSource"
LEAVES = [("f_4_rows_4_flag", 2), ("f_4_rows_7_samples", 12),
          ("f_4_rows_6_signed", 4), ("f_6_marker", 1)]
OBSERVATIONS = {"firstFlag": 1, "secondFlag": 1, "firstLow": 3,
                "firstHigh": 3, "secondLow": 3, "secondHigh": 3,
                "firstSigned": 2, "secondSigned": 2, "marker": 1}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ports(design: str) -> dict[str, tuple[str, int]]:
    match = re.search(r"\bmodule\s+" + TOP + r"\s*\((.*?)\);", design, re.S)
    if not match:
        raise RuntimeError("actual top signature missing")
    header = re.sub(r"//[^\n]*|/\*.*?\*/", "", match[1], flags=re.S)
    result = {}
    direction, width = None, None
    for token in header.split(","):
        declared = re.search(r"\b(input|output|inout)\b", token)
        if declared:
            direction, width = declared[1], 1
        extent = re.search(r"\[\s*(\d+)\s*:\s*(\d+)\s*\]", token)
        if extent:
            width = abs(int(extent[1]) - int(extent[2])) + 1
        name = re.search(r"([A-Za-z_][A-Za-z_0-9]*)\s*$", token)
        if not name or direction is None or name[1] in result:
            raise RuntimeError("unrecognized/duplicate actual pin declaration: " + token)
        result[name[1]] = (direction, width)
    return result


def bench(kind: str, mode: str) -> str:
    associations = [".srcClock(srcClock)", ".dstClock(dstClock)", ".reset(reset)"]
    inputs = ["{logicalInput[9],logicalInput[0]}",
              "{logicalInput[15:10],logicalInput[6:1]}",
              "{logicalInput[17:16],logicalInput[8:7]}", "logicalInput[18]"]
    outputs = ["flags", "samples", "signedValues", "outMarker"]
    for (name, _), value, output in zip(LEAVES, inputs, outputs):
        associations += [f".sampled__{name}({value})", f".outputValue__{name}({output})"]
    associations += [f".{name}({name})" for name in OBSERVATIONS]
    if kind == "fifo":
        associations += [".inputValid(inputValid)", ".ready(inputReady)",
                         ".outputValid(outputValid)", ".outputReady(outputReady)"]
    declaration = "\n".join(f"wire [{width - 1}:0] {name};" for name, width in OBSERVATIONS.items())
    body = '''
  #1; if(logicalOutput !== 19'bx) $fatal(1,"fabricated CDC payload initialization");
  reset=1; both_tick; reset=0;
'''
    if kind == "gray":
        body += '''
  logicalInput=19'h2abcd; repeat(4) src_tick; repeat(4) dst_tick; check(19'h2abcd);
  for(i=0;i<19;i=i+1) begin
    logicalInput=logicalInput ^ (19'b1 << i); src_tick;
    dst_tick; dst_tick; check(logicalInput);
  end
'''
        if mode == "illegal-gray":
            body += 'logicalInput=logicalInput ^ 19\'b11; src_tick; $fatal(1,"MISSING_EXPECTED_ASSERTION");\n'
    elif kind == "handshake":
        body += '''
  logicalInput=19'h2abcd; repeat(16) both_tick; check(19'h2abcd);
  logicalInput=19'h12345; src_tick; logicalInput=19'h13579; src_tick;
  logicalInput=19'h1a36d; repeat(20) both_tick; check(19'h1a36d);
  repeat(5) begin dst_tick; check(19'h1a36d); end
  reset=1; both_tick; logicalInput=19'h3216a; reset=0;
  repeat(16) both_tick; check(19'h3216a);
'''
    else:
        body += '''
  repeat(4) both_tick;
  if(inputReady !== 1 || outputValid !== 0) $fatal(1,"FIFO reset epoch failed");
  for(round=0;round<3;round=round+1) begin
    outputReady=0;
    for(i=0;i<4;i=i+1) begin
      logicalInput=(19'h2abcd ^ (nextValue*19'h15b3)); nextValue=nextValue+1;
      inputValid=1; #1; if(inputReady !== 1) $fatal(1,"FIFO capacity mismatch"); src_tick;
    end
    #1; if(inputReady !== 0) $fatal(1,"FIFO overcapacity");
    logicalInput=(19'h2abcd ^ (nextValue*19'h15b3)); nextValue=nextValue+1;
    src_tick; src_tick;
'''
        if mode == "illegal-stall":
            body += 'logicalInput=logicalInput ^ 19\'b1; src_tick; $fatal(1,"MISSING_EXPECTED_ASSERTION"); end\n'
        else:
            body += '''
    repeat(3) dst_tick;
    if(outputValid !== 1) $fatal(1,"FIFO missing synchronized data");
    repeat(3) begin check(expected[readCount]); dst_tick; end
    outputReady=1; repeat(4) dst_tick;
    if(outputValid !== 0) $fatal(1,"FIFO extra data");
    src_tick; if(inputReady !== 0) $fatal(1,"FIFO early returned credit");
    src_tick; if(inputReady !== 1) $fatal(1,"FIFO missing credit");
    src_tick; inputValid=0; repeat(3) dst_tick; repeat(3) src_tick;
    if(readCount != writeCount) $fatal(1,"FIFO loss or duplicate");
  end
  outputReady=0; inputValid=1; logicalInput=19'h3654a; src_tick; inputValid=0;
  reset=1; #1; readCount=writeCount; both_tick; reset=0; repeat(4) both_tick;
  if(outputValid !== 0) $fatal(1,"FIFO reset did not flush");
  inputValid=1; logicalInput=19'h12b7c; src_tick; inputValid=0;
  outputReady=1; repeat(3) dst_tick;
  if(readCount != writeCount) $fatal(1,"FIFO restart delivery failed");
'''
    scoreboard = '''
always @(posedge srcClock) if(!reset && inputValid && inputReady) begin
  expected[writeCount]=logicalInput; writeCount=writeCount+1;
end
always @(posedge dstClock) if(!reset && outputValid && outputReady) begin
  if(readCount >= writeCount) $fatal(1,"FIFO read without accepted write");
  check(expected[readCount]); readCount=readCount+1;
end
''' if kind == "fifo" else ""
    return f'''module tb;
reg srcClock=0,dstClock=0,reset=0;
reg [18:0] logicalInput=0;
wire [1:0] flags;
wire [11:0] samples;
wire [3:0] signedValues;
wire outMarker;
wire [18:0] logicalOutput={{outMarker,signedValues[3:2],samples[11:6],flags[1],signedValues[1:0],samples[5:0],flags[0]}};
{declaration}
reg inputValid=0,outputReady=0;
wire inputReady,outputValid;
integer i,round,nextValue=1,readCount=0,writeCount=0;
reg [18:0] expected [0:127];
{TOP} dut({', '.join(associations)});
task src_tick; begin #2;srcClock=1;#1;srcClock=0;#1;end endtask
task dst_tick; begin #2;dstClock=1;#1;dstClock=0;#1;end endtask
task both_tick; begin #2;srcClock=1;dstClock=1;#1;srcClock=0;dstClock=0;#1;end endtask
task check(input [18:0] value); begin
 if(logicalOutput !== value || firstFlag !== value[0] || secondFlag !== value[9] ||
    firstLow !== value[3:1] || firstHigh !== value[6:4] ||
    secondLow !== value[12:10] || secondHigh !== value[15:13] ||
    firstSigned !== value[8:7] || secondSigned !== value[17:16] || marker !== value[18])
    $fatal(1,"logical field mismatch expected=%h actual=%h",value,logicalOutput);
end endtask
{scoreboard}
initial begin
{body}
 $display("NAMED_CDC_PASS {kind} {mode}"); $finish;
end
endmodule
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--profile", choices=("gray", "handshake", "fifo"), action="append")
    args = parser.parse_args()
    args.out = args.out.resolve()
    if ".work" not in args.out.parts or (args.out.exists() and any(args.out.iterdir())):
        parser.error("use a new empty output directory beneath ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"status": "running", "boundary_abi": "named-fields", "simulation": "four-state Icarus",
                "firtool_lowering_options": "disallowPackedStructAssignments,disallowLocalVariables",
                "source": {"path": str(SOURCE), "sha256": digest(SOURCE)},
                "runner": {"path": str(Path(__file__).resolve()), "sha256": digest(Path(__file__))},
                "tools": {name: {"path": str(getattr(args, name).resolve()),
                                 "sha256": digest(getattr(args, name).resolve())}
                          for name in ("nodalc", "firtool", "iverilog", "vvp")},
                "commands": [], "profiles": []}

    def save() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str], expected: str | None = None) -> str:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        log = args.out / (label + ".log")
        log.write_text(result.stdout)
        manifest["commands"].append({"label": label, "argv": command, "exit": result.returncode,
                                     "log_sha256": digest(log), "expected_monitor": expected})
        save()
        if expected:
            if result.returncode == 0 or expected not in result.stdout or "MISSING_EXPECTED_ASSERTION" in result.stdout:
                raise RuntimeError(label + ": missing causal executed monitor failure")
        elif result.returncode:
            raise RuntimeError(f"{label} exit {result.returncode}: {result.stdout[-6000:]}")
        return result.stdout

    manifest["firtool_version"] = run("firtool-version", [str(args.firtool.resolve()), "--version"]).strip()

    for profile in args.profile or ("gray", "handshake", "fifo"):
        source = (args.input / (profile + ".mlir")).resolve()
        text = source.read_text()
        if str(SOURCE) not in text or f'sym_name = "{TOP}"' not in text:
            raise RuntimeError("not an actual maintained NamedCdc source replay")
        gated, circt, sv = [args.out / (profile + suffix) for suffix in (".gated.mlir", ".circt.mlir", ".sv")]
        run(profile + "-gate", [str(args.nodalc.resolve()), str(source), "--nodal-gate-default", "-o", str(gated)])
        run(profile + "-lower", [str(args.nodalc.resolve()), str(gated), "--nodal-sequential-to-circt=boundary-abi=named-fields", "-o", str(circt)])
        run(profile + "-export", [str(args.firtool.resolve()), str(circt), "--format=mlir", "--verilog", "--verify-each", "--disable-all-randomization", "--lowering-options=disallowPackedStructAssignments,disallowLocalVariables", "-o", str(sv)])
        original_sv = digest(sv)
        expected = {name: ("input", 1) for name in ("srcClock", "dstClock", "reset")}
        expected.update({name: ("output", width) for name, width in OBSERVATIONS.items()})
        for leaf, width in LEAVES:
            expected["sampled__" + leaf] = ("input", width)
            expected["outputValue__" + leaf] = ("output", width)
        if profile == "fifo":
            expected.update({"inputValid": ("input", 1), "ready": ("output", 1),
                             "outputValid": ("output", 1), "outputReady": ("input", 1)})
        observed = ports(sv.read_text())
        if observed != expected:
            raise RuntimeError(f"actual named pin ABI mismatch: {observed!r}")
        modes = [("legal", None)]
        if profile == "gray":
            modes.append(("illegal-gray", "Gray transition must change at most one bit"))
        elif profile == "fifo":
            modes.append(("illegal-stall", "FIFO source must hold payload and valid while stalled"))
        simulations = []
        for mode, expected_monitor in modes:
            label = profile + "-" + mode
            tb, exe = args.out / (label + ".tb.sv"), args.out / (label + ".vvp")
            tb.write_text(bench(profile, mode))
            run(label + "-compile", [str(args.iverilog.resolve()), "-g2012", "-s", "tb", "-o", str(exe), str(sv), str(tb)])
            output = run(label + "-simulate", [str(args.vvp.resolve()), str(exe)], expected_monitor)
            if not expected_monitor and f"NAMED_CDC_PASS {profile} legal" not in output:
                raise RuntimeError(label + ": missing checked completion marker")
            simulations.append({"mode": mode, "kind": "expected-monitor-failure" if expected_monitor else "transport-pass",
                                "bench_sha256": digest(tb), "executable_sha256": digest(exe)})
        if digest(sv) != original_sv:
            raise RuntimeError("emitted DUT SV changed during consumer execution")
        manifest["profiles"].append({"name": profile, "pins": observed, "simulations": simulations,
                                     "input_sha256": digest(source), "gated_sha256": digest(gated),
                                     "circt_sha256": digest(circt), "unchanged_sv_sha256": original_sv})
        save()
        print(f"PASS {profile}: unchanged SV, exact named19-bit CDC and independent field observations")
    manifest["status"] = "passed"
    save()


if __name__ == "__main__":
    main()
