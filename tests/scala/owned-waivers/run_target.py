#!/usr/bin/env python3
"""Focused stateless-waiver identity witnesses from actual emitted Nodal IR.

The caller first runs Increment57WaiverSourceReplay (or WaiverCIRCTTest for
native trust-boundary fixtures). This script never synthesizes or repairs HDL.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def run(command: list[str], log: Path) -> None:
    with log.open("w") as stream:
        subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)


def ports(design: str) -> tuple[str, list[tuple[str, str, int]]]:
    clean = re.sub(r"//[^\n]*", "", design)
    match = re.search(r"\bmodule\s+(\w+)\s*\((.*?)\);", clean, re.S)
    if not match:
        raise RuntimeError("missing generated flat ANSI module")
    result = []
    direction, width = "", 1
    for field in match[2].split(","):
        entry = re.fullmatch(r"\s*(?:(input|output)\s+)?(?:wire\s+|logic\s+)?(?:\[(\d+):0\]\s+)?(\w+)\s*", field)
        if not entry:
            raise RuntimeError(f"unexpected generated port: {field!r}")
        if entry[1]:
            direction = entry[1]
            width = int(entry[2]) + 1 if entry[2] else 1
        elif entry[2]:
            width = int(entry[2]) + 1
        result.append((entry[3], direction, width))
    return match[1], result


def check_target(source: str, design: str) -> tuple[str, list[tuple[str, str, int]]]:
    comments = [line for line in design.splitlines() if "// NODAL stateless waiver:" in line]
    if len(comments) != source.count('"nodal.waiver"'):
        raise RuntimeError("target lost or invented a waiver obligation")
    for comment in comments:
        if any(term not in comment for term in ("physical_safety", "user_responsibility", "not_guaranteed", "not_exempt", "semantic_path", "reason", "id")):
            raise RuntimeError("target lost typed waiver facts")
        # Source locations appended by CIRCT are not authored reason text.
        authored = comment.split("; user physical responsibility", 1)[0]
        if "*/" in authored or "{{" in authored or any(ord(c) >= 127 for c in authored):
            raise RuntimeError("authored text escaped the ASCII comment envelope")
    clean = re.sub(r"//[^\n]*", "", design)
    if re.search(r"\b(reg|always|always_ff|always_latch|initial|assert)\b", clean):
        raise RuntimeError("stateless waiver invented storage, reset or runtime certification")
    return ports(design)


def testbench(module: str, declarations: list[tuple[str, str, int]]) -> str:
    data = [(name, width) for name, direction, width in declarations
            if direction == "input" and "clock" not in name.lower()]
    outputs = [(name, width) for name, direction, width in declarations if direction == "output"]
    if len(data) != 1 or len(outputs) != 1 or data[0][1] != outputs[0][1]:
        raise RuntimeError("identity witness requires one unambiguous payload input/output")
    input_name, width = data[0]
    output_name = outputs[0][0]
    connections = []
    for name, direction, _ in declarations:
        value = "payload" if name == input_name else "result" if name == output_name else "1'b0"
        if direction != "input" and name != output_name:
            raise RuntimeError("unexpected output")
        connections.append(f".{name}({value})")
    return f"""module tb;
  reg [{width - 1}:0] payload;
  wire [{width - 1}:0] result;
  integer i;
  {module} dut({', '.join(connections)});
  initial begin
    payload = '0; #1; if (result !== payload) $fatal(1, "zero identity");
    payload = '1; #1; if (result !== payload) $fatal(1, "ones identity");
    for (i = 0; i < 32; i = i + 1) begin
      payload = $random; #1;
      if (result !== payload) $fatal(1, "clock-independent identity");
    end
    payload = 'x; #1; if (result !== payload) $fatal(1, "X identity");
    payload = 'z; #1; if (result !== payload) $fatal(1, "Z identity");
    $display("WAIVER_IDENTITY_PASS width={width} samples=36 clocks=held");
    $finish;
  end
endmodule
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cases", nargs="+", default=["scalar", "bool", "aggregate", "nested", "escaped"])
    parser.add_argument("--nodalc", type=Path, default=ROOT / ".work/native-build/bin/nodalc")
    parser.add_argument("--circt-opt", type=Path, default=ROOT / ".work/circt-toolchain/bin/circt-opt")
    parser.add_argument("--firtool", type=Path, default=ROOT / ".work/circt-toolchain/bin/firtool")
    parser.add_argument("--iverilog", type=Path, default=ROOT / ".work/tools/iverilog/bin/iverilog")
    parser.add_argument("--vvp", type=Path, default=ROOT / ".work/tools/iverilog/bin/vvp")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for name in args.cases:
        source = args.source_dir.resolve() / f"{name}.mlir"
        circt = output / f"{name}.circt.mlir"
        lowered = output / f"{name}.sv.mlir"
        design = output / f"{name}.sv"
        run([str(args.nodalc), str(source), "--nodal-sequential-to-circt", "-o", str(circt)], output / f"{name}-nodalc.log")
        run([str(args.circt_opt), str(circt), "--lower-seq-to-sv", "-o", str(lowered)], output / f"{name}-lower.log")
        run([str(args.firtool), str(lowered), "--format=mlir", "--verilog", "--verify-each", "--disable-all-randomization", "-o", str(design)], output / f"{name}-export.log")
        module, declarations = check_target(source.read_text(), design.read_text())
        bench = output / f"{name}-tb.sv"
        bench.write_text(testbench(module, declarations))
        binary = output / f"{name}.vvp"
        run([str(args.iverilog), "-g2012", "-s", "tb", "-o", str(binary), str(design), str(bench)], output / f"{name}-compile.log")
        run([str(args.vvp), str(binary)], output / f"{name}-simulate.log")
        result = (output / f"{name}-simulate.log").read_text().strip()
        if "WAIVER_IDENTITY_PASS" not in result:
            raise RuntimeError(f"{name}: identity witness did not complete")
        print(name, result)


if __name__ == "__main__":
    main()
