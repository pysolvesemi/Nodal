#!/usr/bin/env python3
"""Execute real Scala Gray/pulse replay through CIRCT and Icarus, including failures."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def gray_body(stages: int, reset: bool, high: bool, invalid: bool, bits: int, enum: bool) -> str:
    asserted, released = (1, 0) if high else (0, 1)
    setup = f"reset={asserted}; src_tick; dst_tick; reset={released};" if reset else ""
    prime = "repeat(4) src_tick;" if reset else "src_tick;"
    first, second, third = (1, 3, 2) if enum else ((1, 0, 1) if bits == 1 else (5, 4, 6))
    body = f"""
  #1; if(outputValue !== {bits}'b{'x' * bits}) $fatal(1,"fabricated Gray payload initialization");
  {setup}
  inputValue={bits}'d{first}; {prime}
  repeat({stages}) dst_tick;
  check({bits}'d{first});
  inputValue={bits}'d{second}; src_tick;
  repeat({stages - 1}) begin dst_tick; check({bits}'d{first}); end
  dst_tick; check({bits}'d{second});
"""
    if invalid:
        if enum:
            body += "inputValue=3'd2; src_tick;"
        return body + f"inputValue={bits}'d{1 if enum else 11}; src_tick; #1; $fatal(1,\"MISSING_EXPECTED_ASSERTION\");"
    return body + f"""
  inputValue={bits}'d{third}; src_tick;
  repeat({stages}) dst_tick;
  check({bits}'d{third});
"""


def pulse_body(mode: str, high: bool) -> str:
    if mode == "epoch":
        return 'inputValue=1; src_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");'
    asserted, released = (1, 0) if high else (0, 1)
    body = f"""
  reset={asserted}; src_tick; dst_tick; reset={released};
  repeat(4) begin src_tick; dst_tick; end
  check(0);
  inputValue=1; src_tick;
"""
    if mode == "width":
        return body + 'src_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");'
    body += "inputValue=0; src_tick;\n"
    if mode == "busy":
        return body + 'inputValue=1; src_tick; #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");'
    if mode == "simultaneous":
        return body + '''
  dst_tick; check(0); dst_tick; check(1);
  src_tick; inputValue=1; both_tick;
  #1; $fatal(1,"MISSING_EXPECTED_ASSERTION");
'''
    body += """
  dst_tick; check(0);
  dst_tick; check(1);
  dst_tick; check(0);
  src_tick; src_tick;
  inputValue=1; src_tick; inputValue=0; src_tick;
  dst_tick; check(0);
  dst_tick; check(1);
  dst_tick; check(0);
  src_tick; src_tick;
  repeat(3) begin dst_tick; check(0); end
"""
    body += f"""
  inputValue=1; src_tick; inputValue=0;
  reset={asserted}; #1; check(0);
  src_tick; dst_tick; reset={released};
  repeat(5) begin src_tick; dst_tick; check(0); end
"""
    return body


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--include-aggregate", action="store_true")
    parser.add_argument("--kind", choices=("gray", "pulse"))
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    # The replay defines these fixtures; no handwritten substitute design is accepted.
    fixtures = [(f"{kind}_{edge}_{polarity}", kind, edge == "Rising", 2, True,
                 polarity == "ActiveHigh")
                for kind in ("gray", "pulse")
                for edge in ("Rising", "Falling")
                for polarity in ("ActiveHigh", "ActiveLow")]
    fixtures += [("gray_resetless", "gray", True, 2, False, True),
                 ("gray_32_stages", "gray", True, 32, True, True)]
    fixtures += [(name, "gray", True, stages, True, True) for name, stages in
                 (("gray_three_stages", 3), ("gray_signed", 2), ("gray_bits", 2),
                  ("gray_bool", 2), ("gray_enum", 2))]
    if args.include_aggregate:
        fixtures += [(name, "gray", True, 2, True, True)
                     for name in ("gray_vector", "gray_struct")]
    manifest: dict = {"status": "running", "cases": []}

    def run(label: str, command: list[str], expected_failure: str | None = None) -> str:
        (args.out / (label + ".command.json")).write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if expected_failure:
            if result.returncode == 0 or expected_failure not in result.stdout:
                raise RuntimeError(f"{label}: expected executed {expected_failure!r} failure, "
                                   f"got exit {result.returncode}:\n{result.stdout[-8000:]}")
            if "MISSING_EXPECTED_ASSERTION" in result.stdout:
                raise RuntimeError(f"{label}: target assumption checker did not fire")
        elif result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    for name, kind, rising, stages, reset, high in fixtures:
        if args.kind and kind != args.kind:
            continue
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
        module_match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", design)
        if not module_match:
            raise RuntimeError(f"{name}: generated hardware module missing")
        module = module_match.group(1)
        obligations = (["transition=one_bit_per_source_sample", "skew=less_than_source_period",
                        "stability=stable_between_source_samples"] if kind == "gray" else
                       ["spacing=returned_acknowledgement", "width=single_source_cycle",
                        "reset_epoch=common_assertion"])
        for obligation in obligations:
            if obligation not in design:
                raise RuntimeError(f"{name}: target lost obligation {obligation}")
        if "// NODAL crossing " not in design:
            raise RuntimeError(f"{name}: source-located crossing annotation missing")
        if name in ("gray_vector", "gray_struct"):
            for port in ("sampled", "outputValue"):
                marker = f"// NODAL packed ABI: {port}; layout=recursive-row-major-fields-lsb-first;"
                if marker not in design:
                    raise RuntimeError(f"{name}: complete packed ABI annotation missing for {port}")
            if "source_type" not in design or "bit_offset" not in design and name == "gray_struct":
                raise RuntimeError(f"{name}: target lost canonical type/layout details")
        ports = ["srcClock", "dstClock", "sampled", "outputValue"]
        ports.append("reset")
        for port in ports:
            if not re.search(r"\b" + port + r"\b", design):
                raise RuntimeError(f"{name}: public source port {port} missing")
        modes = ["legal", "transition"] if kind == "gray" else ["legal", "busy", "width", "epoch", "simultaneous"]
        bits = 1 if kind == "pulse" or name == "gray_bool" else (3 if name == "gray_enum" else 4)
        if name == "gray_bool":
            modes = ["legal"]
        width = f"[{bits - 1}:0] " if bits > 1 else ""
        idle, active = (0, 1) if rising else (1, 0)
        for mode in modes:
            label = name + "-" + mode
            body = gray_body(stages, reset, high, mode != "legal", bits, name == "gray_enum") if kind == "gray" else pulse_body(mode, high)
            bench = f'''module tb;
reg srcClock={idle}, dstClock={idle};
reg reset={0 if high else 1};
reg {width}inputValue=0;
wire {width}outputValue;
{module} dut({", ".join(f".{p}({'inputValue' if p == 'sampled' else p})" for p in ports)});
task src_tick; begin #2; srcClock={active}; #1; srcClock={idle}; #1; end endtask
task dst_tick; begin #2; dstClock={active}; #1; dstClock={idle}; #1; end endtask
task both_tick; begin #2; srcClock={active}; dstClock={active}; #1; srcClock={idle}; dstClock={idle}; #1; end endtask
task check(input {width}expected); begin
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
            marker = None if mode == "legal" else ("NODAL Gray transition" if kind == "gray" else "NODAL pulse")
            output = run(label + "-simulate", [str(args.vvp), str(executable)], marker)
            if mode == "legal" and f"PASS {label}" not in output:
                raise RuntimeError(f"{label}: simulation did not reach success")
            manifest["cases"].append({"name": label, "status": "passed",
                                      "expected_failure": marker,
                                      "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                                      "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest()})
            (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"PASS {label}", flush=True)
    manifest["status"] = "passed"
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
