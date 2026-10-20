#!/usr/bin/env python3
"""Execute actual Scala snapshot-handshake replay through CIRCT and Icarus."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def stimulus(high: bool, mode: str, enum: bool, synchronous: bool) -> str:
    if mode in ("source_epoch", "destination_epoch", "source_unknown_reset", "destination_unknown_reset"):
        tick = "src_tick" if mode.startswith("source") else "dst_tick"
        unknown = "reset=1'bx;" if mode.endswith("unknown_reset") else ""
        return f'{unknown} {tick}; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");'
    asserted, released = (1, 0) if high else (0, 1)
    if mode.startswith("control_"):
        role = mode.removeprefix("control_")
        tick = "src_tick" if role.startswith("source_") else "dst_tick"
        return f'''
  reset={asserted}; src_tick; dst_tick; reset={released}; inputValue=5;
  repeat(12) both_tick;
  force dut.__nodal_handshake_0_{role} = 1'bx;
  {tick}; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");
'''
    if synchronous:
        return f'''
  #1; if(outputValue !== {{WIDTH{{1'bx}}}}) $fatal(1,"fabricated synchronous payload initialization");
  reset={asserted}; src_tick; reset={released};
  inputValue=5; repeat(16) src_tick; check(5);
  inputValue=10; repeat(16) src_tick; check(10);
  reset={asserted}; src_tick; inputValue=3; reset={released};
  repeat(16) src_tick; check(3);
'''
    first, transient_a, transient_b, second, third, fourth, restart = (
        (1, 3, 2, 3, 2, 1, 3) if enum else (5, 7, 9, 10, 12, 14, 3))
    return f'''
  #1; if(outputValue !== {{WIDTH{{1'bx}}}}) $fatal(1,"fabricated payload initialization");
  reset={asserted}; src_tick; dst_tick; reset={released};
  inputValue={first}; repeat(3) src_tick;
  inputValue={transient_a}; src_tick;
  inputValue={transient_b}; src_tick;
  inputValue={second};
  repeat(4) begin dst_tick;
    if(outputValue !== {{WIDTH{{1'bx}}}}) $fatal(1,"early snapshot before synchronized request");
  end
  dst_tick; check({first});
  repeat(3) src_tick;
  inputValue={third};
  repeat(2) begin dst_tick; check({first}); end
  dst_tick; check({second});
  repeat(3) src_tick;
  repeat(2) begin dst_tick; check({second}); end
  dst_tick; check({third});
  // Both sides may edge together without bypassing request/ack synchronization.
  inputValue={fourth}; repeat(12) both_tick; check({fourth});
  // A stopped source must not manufacture new deliveries in the receiver.
  repeat(6) begin dst_tick; check({fourth}); end
  // Common reset abandons an in-flight snapshot, never initializes payload to zero.
  inputValue={first}; repeat(3) src_tick;
  reset={asserted}; src_tick; dst_tick; inputValue={restart}; reset={released};
  repeat(12) both_tick; check({restart});
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    fixtures = [(f"handshake_{edge}_{polarity}", edge == "Rising", polarity == "ActiveHigh", 4)
                for edge in ("Rising", "Falling")
                for polarity in ("ActiveHigh", "ActiveLow")]
    fixtures += [("handshake_vector", True, True, 4),
                 ("handshake_struct", True, True, 4),
                 ("handshake_enum", True, True, 3),
                 ("handshake_sync", True, True, 4)]
    manifest: dict = {"status": "running", "cases": []}

    def run(label: str, command: list[str], expected_failure: bool = False) -> str:
        (args.out / (label + ".command.json")).write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if expected_failure:
            if result.returncode == 0 or "NODAL handshake" not in result.stdout:
                raise RuntimeError(f"{label}: expected executed handshake assertion failure, "
                                   f"got exit {result.returncode}:\n{result.stdout[-8000:]}")
            if "MISSING_EXPECTED_ASSERTION" in result.stdout:
                raise RuntimeError(f"{label}: target startup checker did not execute")
        elif result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    for name, rising, high, bits in fixtures:
        source = args.input / (name + ".mlir")
        if not source.is_file():
            parser.error(f"missing actual Scala replay: {source}")
        circt = args.out / (name + ".circt.mlir")
        sv = args.out / (name + ".sv")
        run(name + "-lower", [str(args.nodalc), str(source), "--nodal-sequential-to-circt",
                              "--mlir-print-debuginfo", "-o", str(circt)])
        run(name + "-export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                               "--verify-each", "--disable-all-randomization", "-o", str(sv)])
        design = sv.read_text()
        match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", design)
        if not match:
            raise RuntimeError(f"{name}: no generated module")
        module = match.group(1)
        ports = ["srcClock", "dstClock", "reset", "sampled", "outputValue"]
        for port in ports:
            if not re.search(r"\b" + port + r"\b", design):
                raise RuntimeError(f"{name}: public source port {port} missing")
        for obligation in ("delivery=latest_snapshot_coalescing",
                           "bundled_data=settled_before_request_capture",
                           "reset_epoch=common_assertion"):
            if obligation not in design:
                raise RuntimeError(f"{name}: target lost obligation {obligation}")
        if name in ("handshake_vector", "handshake_struct"):
            for port in ("sampled", "outputValue"):
                marker = f"// NODAL packed ABI: {port}; layout=recursive-row-major-fields-lsb-first;"
                if marker not in design:
                    raise RuntimeError(f"{name}: packed ABI annotation missing for {port}")
        idle, active = (0, 1) if rising else (1, 0)
        modes = (("legal", "source_epoch", "source_unknown_reset") if name == "handshake_sync" else
                 ("legal", "source_epoch", "destination_epoch", "source_unknown_reset", "destination_unknown_reset"))
        if name == "handshake_Rising_ActiveHigh":
            roles = ("source_request", "source_acknowledgement_0", "source_acknowledgement_1",
                     "destination_request_0", "destination_request_1", "destination_acknowledgement")
            for role in roles:
                if "__nodal_handshake_0_" + role not in design:
                    raise RuntimeError(f"{name}: required physical control {role} missing")
            modes += tuple("control_" + role for role in roles)
        for mode in modes:
            label = name + "-" + mode
            body = stimulus(high, mode, name == "handshake_enum", name == "handshake_sync")
            bench = f'''module tb;
localparam WIDTH={bits};
reg srcClock={idle}, dstClock={idle}, reset={0 if high else 1};
reg [WIDTH-1:0] inputValue=0;
wire [WIDTH-1:0] outputValue;
{module} dut({", ".join(f".{p}({'inputValue' if p == 'sampled' else p})" for p in ports)});
task src_tick; begin #2; srcClock={active}; #1; srcClock={idle}; #1; end endtask
task dst_tick; begin #2; dstClock={active}; #1; dstClock={idle}; #1; end endtask
task both_tick; begin #2; srcClock={active}; dstClock={active}; #1; srcClock={idle}; dstClock={idle}; #1; end endtask
task check(input [WIDTH-1:0] expected); begin
  if(outputValue !== expected) $fatal(1,"{label}: expected %b got %b",expected,outputValue);
end endtask
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
