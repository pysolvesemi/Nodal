#!/usr/bin/env python3
"""Run actual Scala asynchronous FIFO replay through CIRCT and Icarus."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from special_profiles import stimulus as special_stimulus


def stimulus(mode: str, high: bool) -> str:
    if mode in ("source_epoch", "destination_epoch", "source_unknown_reset", "destination_unknown_reset"):
        tick = "src_tick" if mode.startswith("source") else "dst_tick"
        unknown = "reset=1'bx;" if mode.endswith("unknown_reset") else ""
        return f'{unknown} {tick}; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");'
    asserted, released = (1, 0) if high else (0, 1)
    if mode.startswith("control_"):
        role = mode.removeprefix("control_")
        tick = "src_tick" if role.startswith("source_") else "dst_tick"
        return f'''
  reset={asserted}; both_tick; reset={released}; repeat(3) both_tick;
  force dut.__nodal_fifo_0_{role} = 'x;
  {tick}; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");
'''
    body = f'''
  reset={asserted}; #1;
  if(inputReady !== 0 || outputValid !== 0) $fatal(1,"reset must mask ready and valid");
  both_tick; reset={released}; repeat(3) both_tick;
  if(inputReady !== 1 || outputValid !== 0) $fatal(1,"empty FIFO after reset recovery");
  for(round=0; round<3; round=round+1) begin
    outputReady=0;
    for(i=0; i<DEPTH; i=i+1) begin
      inputValid=1; inputPayload=encode(nextPayload); nextPayload=nextPayload+1; #1;
      if(inputReady !== 1) $fatal(1,"FIFO capacity smaller than requested depth");
      src_tick;
    end
    #1; if(inputReady !== 0) $fatal(1,"FIFO capacity exceeds requested depth");
    inputValid=1; inputPayload=encode(nextPayload); nextPayload=nextPayload+1;
    src_tick; src_tick;
'''
    if mode == "stall_payload":
        return body + 'inputPayload=encode(nextPayload); src_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION"); end'
    if mode == "stall_valid":
        return body + 'inputValid=0; src_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION"); end'
    if mode.startswith("destination_stall_"):
        mutation = "force dut.__nodal_fifo_0_bank_0 = 8'hfe;" if mode.endswith("payload") else "force dut.__nodal_fifo_0_destination_write_gray_1 = 3'b000;"
        return body + 'repeat(3) dst_tick; ' + mutation + ' dst_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION"); end'
    body += '''
    repeat(3) dst_tick;
    if(outputValid !== 1) $fatal(1,"full FIFO not visible after pointer synchronization");
    repeat(3) begin dst_tick;
      if(outputPayload !== expected[readCount]) $fatal(1,"output changed while stalled");
    end
    outputReady=1;
    for(i=0; i<DEPTH; i=i+1) begin
      if(outputValid !== 1) $fatal(1,"FIFO emptied before accepted entries drained");
      dst_tick;
    end
    if(outputValid !== 0) $fatal(1,"FIFO retained an extra entry after drain");
    // Source sees returned space only after two source edges, then accepts pending item.
    if(inputReady !== 0) $fatal(1,"read pointer bypassed return synchronizers");
    src_tick; if(inputReady !== 0) $fatal(1,"read pointer returned too early");
    src_tick; if(inputReady !== 1) $fatal(1,"returned space was not made visible");
    src_tick; inputValid=0;
    dst_tick; if(outputValid !== 0) $fatal(1,"write pointer bypassed synchronization");
    dst_tick; if(outputValid !== 1) $fatal(1,"accepted pending item not visible");
    dst_tick;
    repeat(3) src_tick;
    if(readCount != writeCount || outputValid !== 0) $fatal(1,"lost or duplicated accepted item");
  end
  // Invalid offers and empty reads cannot create transactions.
  inputValid=0; inputPayload=encode(85); outputReady=1;
  repeat(4) both_tick;
  if(readCount != writeCount || outputValid !== 0) $fatal(1,"transfer occurred without valid");
  // Queue data is discarded by a coordinated reset epoch.
  outputReady=0; inputValid=1; inputPayload=encode(31); src_tick;
  inputPayload=encode(47); src_tick; inputValid=0;
'''
    body += f'''
  reset={asserted}; #1; readCount=writeCount;
  if(inputReady !== 0 || outputValid !== 0) $fatal(1,"reset failed to flush visibility");
  both_tick; reset={released}; repeat(3) both_tick;
  outputReady=1; repeat(3) dst_tick;
  if(outputValid !== 0) $fatal(1,"pre-reset data survived queue flush");
  inputValid=1; inputPayload=encode(99); src_tick; inputValid=0;
  repeat(3) dst_tick;
  if(readCount != writeCount || outputValid !== 0) $fatal(1,"post-reset delivery failed");
'''
    return body


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    fixtures = [(f"fifo_{edge}_{polarity}", edge == "Rising", polarity == "ActiveHigh", 4)
                for edge in ("Rising", "Falling") for polarity in ("ActiveHigh", "ActiveLow")]
    fixtures += [(f"fifo_depth_{depth}", True, True, depth) for depth in (2, 8, 256)]
    fixtures += [("fifo_vector", True, True, 4), ("fifo_struct", True, True, 4), ("fifo_enum", True, True, 4)]
    special = ("fifo_sync", "fifo_unattached", "fifo_continuous", "fifo_chain")
    fixtures += [(name, True, True, 4) for name in special]
    manifest: dict = {"status": "running", "cases": []}

    def run(label: str, command: list[str], expected_failure: bool = False) -> str:
        (args.out / (label + ".command.json")).write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if expected_failure:
            if result.returncode == 0 or "NODAL FIFO" not in result.stdout:
                raise RuntimeError(f"{label}: expected executed FIFO assertion failure, "
                                   f"got exit {result.returncode}:\n{result.stdout[-8000:]}")
            if "MISSING_EXPECTED_ASSERTION" in result.stdout:
                raise RuntimeError(f"{label}: target protocol checker did not execute")
        elif result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    for name, rising, high, depth in fixtures:
        source = args.input / (name + ".mlir")
        if not source.is_file():
            parser.error(f"missing actual Scala replay: {source}")
        circt = args.out / (name + ".circt.mlir")
        sv = args.out / (name + ".sv")
        run(name + "-lower", [str(args.nodalc), str(source), "--nodal-sequential-to-circt",
                              "--mlir-print-debuginfo", "-o", str(circt)])
        run(name + "-export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                               "--verify-each", "--disable-all-randomization", "--lowering-options=disallowLocalVariables", "-o", str(sv)])
        design = sv.read_text()
        match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", design)
        if not match:
            raise RuntimeError(f"{name}: no generated module")
        module = match.group(1)
        ports = ["srcClock", "dstClock", "reset", "inputPayload", "inputValid", "inputReady",
                 "outputPayload", "outputValid", "outputReady"]
        if name == "fifo_chain":
            ports.append("midClock")
        for port in ports:
            if not re.search(r"\b" + ("upstream" if port == "inputReady" else port) + r"\b", design):
                raise RuntimeError(f"{name}: public source port {port} missing")
        for obligation in ("transfer=valid_and_ready", "stall=hold_payload_and_valid",
                           "reset_epoch=common_assertion", "pointer_skew=less_than_launch_period",
                           "bundled_data=settled_before_read_visibility"):
            if obligation not in design:
                raise RuntimeError(f"{name}: target lost obligation {obligation}")
        if name in ("fifo_vector", "fifo_struct"):
            for port in ("inputPayload", "outputPayload"):
                marker = f"// NODAL packed ABI: {port}; layout=recursive-row-major-fields-lsb-first;"
                if marker not in design:
                    raise RuntimeError(f"{name}: complete packed ABI annotation missing for {port}")
        width = 3 if name == "fifo_enum" else 8
        idle, active = (0, 1) if rising else (1, 0)
        modes = ("legal", "stall_payload", "stall_valid", "source_epoch", "destination_epoch",
                 "source_unknown_reset", "destination_unknown_reset")
        if name in special:
            modes = ("legal",)
        if name == "fifo_Rising_ActiveHigh":
            roles = ("source_binary", "source_gray", "source_read_gray_0", "source_read_gray_1",
                     "destination_binary", "destination_gray", "destination_write_gray_0",
                     "destination_write_gray_1")
            for role in roles:
                if "__nodal_fifo_0_" + role not in design:
                    raise RuntimeError(f"{name}: real pointer control {role} missing")
            modes += tuple("control_" + role for role in roles)
            modes += ("destination_stall_payload", "destination_stall_valid")
        for mode in modes:
            label = name + "-" + mode
            body = special_stimulus(name, high) if name in special and mode == "legal" else stimulus(mode, high)
            bench = f'''module tb;
localparam DEPTH={depth}, WIDTH={width}, IS_ENUM={1 if name == "fifo_enum" else 0};
reg midClock={idle};
reg srcClock={idle}, dstClock={idle}, reset={0 if high else 1};
reg [WIDTH-1:0] inputPayload=0;
reg inputValid=0, outputReady=0;
wire [WIDTH-1:0] outputPayload;
wire inputReady, outputValid;
integer writeCount=0, readCount=0, nextPayload=1, i, round;
reg [WIDTH-1:0] expected[0:4095];
{module} dut({", ".join(f".{'upstream' if p == 'inputReady' else p}({p})" for p in ports)});
function [WIDTH-1:0] encode(input integer value);
  begin
    if(IS_ENUM) case(value % 3) 0: encode=1; 1: encode=3; default: encode=2; endcase
    else encode=value;
  end
endfunction
task record_write; begin
  if(({1 if name == "fifo_continuous" else "inputValid"}) && inputReady) begin
    if(writeCount >= 4096) $fatal(1,"scoreboard bound");
    expected[writeCount]=inputPayload; writeCount=writeCount+1;
  end
end endtask
task record_read; begin
  if(outputValid && ({0 if name == "fifo_unattached" else "outputReady"})) begin
    if(readCount >= writeCount) $fatal(1,"unaccepted or duplicate output");
    if(outputPayload !== expected[readCount])
      $fatal(1,"{label}: expected %b got %b at item %d",expected[readCount],outputPayload,readCount);
    readCount=readCount+1;
  end
end endtask
task src_tick; begin #2; record_write; {"record_read;" if name == "fifo_sync" else ""} srcClock={active}; #1; srcClock={idle}; #1; end endtask
task dst_tick; begin #2; record_read; dstClock={active}; #1; dstClock={idle}; #1; end endtask
task both_tick; begin #2; record_write; record_read; srcClock={active}; midClock={active}; dstClock={active}; #1; srcClock={idle}; midClock={idle}; dstClock={idle}; #1; end endtask
initial begin
{body}
  $display("PASS {label}"); $finish;
end
endmodule
'''
            bench_path = args.out / (label + "-tb.sv")
            bench_path.write_text(bench)
            executable = args.out / (label + ".vvp")
            run(label + "-compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o",
                                     str(executable), str(sv), str(bench_path)])
            output = run(label + "-simulate", [str(args.vvp), str(executable)], mode != "legal")
            if mode != "legal":
                side = "destination" if mode.startswith("destination") or mode.startswith("control_destination") else "source"
                obligation = "must hold payload and valid while stalled" if "stall_" in mode else "controls require an established common reset epoch"
                if f"NODAL FIFO {side} {obligation}" not in output:
                    raise RuntimeError(f"{label}: wrong assertion fired")
            if mode == "legal" and f"PASS {label}" not in output:
                raise RuntimeError(f"{label}: simulation did not reach success")
            manifest["cases"].append({"name": label, "status": "passed",
                                      "expected_failure": mode != "legal",
                                      "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                                      "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest()})
            (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"PASS {label}", flush=True)
    manifest["status"] = "passed"
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
