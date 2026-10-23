#!/usr/bin/env python3
"""Run focused scalar resolved/extern source replays through unchanged four-state HDL."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

PROFILES = ("read", "drive", "open-drain", "repeated", "independent", "local-alias", "interface-fixed")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def bench(profile, root, external):
    """Authored environment only; never modify or recreate the generated DUT."""
    model = ""
    if external:
        body = "reg enable=0; reg [3:0] value=0; assign pin=enable?value:4'bz;"
        if profile == "open-drain":
            body = "reg [3:0] low=0; for(genvar i=0;i<4;i=i+1) assign pin[i]=low[i]?1'b0:1'bz;"
        model = f"// Authored opaque external model, not Nodal-generated HDL.\nmodule {external}(inout wire [3:0] pin);\n{body}\nendmodule\n"
    header = ""
    actions = ""
    if profile in ("read", "drive", "interface-fixed"):
        data_port = "data" if profile == "interface-fixed" else "input_0"
        header = f"""
  tri [3:0] pad; wire [3:0] observed;
  reg enable=0; reg [3:0] data=0; reg bench_enable=0; reg [3:0] bench_value=0;
  assign pad=bench_enable?bench_value:4'bz;
  {root} dut(.core_clock(1'b0),.enable(enable),.{data_port}(data),.output_0(observed),.pad(pad));
  task expect_value(input [3:0] expected,input [511:0] reason);
    if(pad!==expected || observed!==expected) $fatal(1,"%0s pad=%b observed=%b expected=%b",reason,pad,observed,expected);
  endtask
"""
        actions = '    #1; expect_value(4\'bz,"all contributors released");\n'
        if profile != "interface-fixed":
            actions += """
    for(integer i=0;i<16;i=i+1) begin
      dut.child_instance.value=i; dut.child_instance.enable=1;
      #1; expect_value(i,"external inward read");
      dut.child_instance.enable=0;
      #1; expect_value(4'bz,"external release");
    end
"""
        if profile != "read":
            actions += """
    for(integer i=0;i<16;i=i+1) begin
      data=i;enable=1;
      #1;expect_value(i,"owned conditional drive outward");
      bench_value=~i;bench_enable=1;
      #1;expect_value(4'bx,"opposing actual drivers");
      enable=0;
      #1;expect_value((~i)&15,"external remains after owned release");
      bench_enable=0;
      #1;expect_value(4'bz,"both contributors released");
    end
"""
        actions += """
    bench_value=4'b1010;bench_enable=1;
    #1;expect_value(4'b1010,"top-level stimulus inward");
"""
        if profile == "read":
            actions += """
    dut.child_instance.value=4'b1001;dut.child_instance.enable=1;
    #1;expect_value(4'b10xx,"partial per-bit contention");
    dut.child_instance.enable=0;
"""
        else:
            actions += """
    data=4'b1001;enable=1;
    #1;expect_value(4'b10xx,"partial per-bit contention");
    enable=0;
"""
        actions += "    bench_enable=0; #1;expect_value(4'bz,\"final release\");\n"
    elif profile == "open-drain":
        header = f"""
  tri [3:0] pad;wire [3:0] observed;reg enable=0;
  for(genvar i=0;i<4;i=i+1) pullup(pad[i]);
  {root} dut(.core_clock(1'b0),.enable(enable),.observed(observed),.pad(pad));
  task expect_value(input [3:0] expected);
    if(pad!==expected || observed!==expected) $fatal(1,"open-drain pad=%b read=%b expected=%b",pad,observed,expected);
  endtask
"""
        actions = """
    #1;expect_value(4'hf);
    enable=1;#1;expect_value(4'h0);
    enable=0;#1;expect_value(4'hf);
    for(integer i=0;i<16;i=i+1) begin
      dut.child_instance.low=i;#1;expect_value((~i)&15);
      enable=1;#1;expect_value(4'h0);
      enable=0;#1;expect_value((~i)&15);
      dut.child_instance.low=0;#1;expect_value(4'hf);
    end
"""
    elif profile in ("independent", "local-alias"):
        ports = ".first_pad(first),.second_pad(second)" if profile == "independent" else ".first(first),.second(second)"
        header = f"""
  tri [3:0] first,second;wire [3:0] first_read,second_read;
  reg first_enable=0,second_enable=0;reg [3:0] first_value=0,second_value=0;
  assign first=first_enable?first_value:4'bz;
  assign second=second_enable?second_value:4'bz;
  {root} dut(.core_clock(1'b0),.firstRead(first_read),.secondRead(second_read),{ports});
  task expect_values(input [3:0] left,input [3:0] right);
    if(first!==left || second!==right || first_read!==left || second_read!==right)
      $fatal(1,"pair first=%b/%b second=%b/%b expected=%b,%b",first,first_read,second,second_read,left,right);
  endtask
"""
        actions = "    #1;expect_values(4'bz,4'bz);\n"
        if profile == "independent":
            actions += """
    for(integer i=0;i<16;i=i+1) begin
      dut.first_instance.value=i;dut.first_instance.enable=1;
      #1;expect_values(i,4'bz);
      dut.second_instance.value=~i;dut.second_instance.enable=1;
      #1;expect_values(i,(~i)&15);
      dut.first_instance.enable=0;
      #1;expect_values(4'bz,(~i)&15);
      dut.second_instance.enable=0;
      #1;expect_values(4'bz,4'bz);
    end
    first_value=4'h6;first_enable=1;second_value=4'h9;second_enable=1;
    #1;expect_values(4'h6,4'h9);
    dut.first_instance.value=4'h9;dut.first_instance.enable=1;
    #1;expect_values(4'bx,4'h9);
"""
        else:
            actions += """
    for(integer i=0;i<16;i=i+1) begin
      first_value=i;first_enable=1;#1;expect_values(i,i);
      first_enable=0;second_value=~i;second_enable=1;#1;expect_values((~i)&15,(~i)&15);
      first_enable=1;#1;expect_values(4'bx,4'bx);
      first_enable=0;second_enable=0;#1;expect_values(4'bz,4'bz);
    end
    first_value=4'b1010;first_enable=1;second_value=4'b1001;second_enable=1;
    #1;expect_values(4'b10xx,4'b10xx);
"""
    elif profile == "repeated":
        header = f"""
  {root} dut();
  task expect_value(input [3:0] expected);
    if(dut.first_instance.pin!==expected || dut.second_instance.pin!==expected)
      $fatal(1,"joined reused pins disagree with %b",expected);
  endtask
"""
        actions = """
    #1;expect_value(4'bz);
    dut.first_instance.value=4'ha;dut.first_instance.enable=1;#1;expect_value(4'ha);
    dut.second_instance.value=4'h5;dut.second_instance.enable=1;#1;expect_value(4'bx);
    dut.first_instance.enable=0;#1;expect_value(4'h5);
    dut.second_instance.enable=0;#1;expect_value(4'bz);
"""
    else:
        raise RuntimeError(f"Unsupported maintained profile {profile}")
    test = "// Authored environment, target.sv is used byte-for-byte.\nmodule tb;\n" + header
    test += "  initial begin\n" + actions
    test += f'    $display("RESOLVED_TARGET_{profile.upper()}_PASS");$finish;\n  end\nendmodule\n'
    return model, test


def inspect(profile, root, ir, sv):
    require(len(re.findall(r"^module " + re.escape(root) + r"\(", sv, re.M)) == 1, "missing exact root")
    external_names = re.findall(r'verilogName = "([^"]+)"', ir)
    expected_externs = 0 if profile in ("local-alias", "interface-fixed") else 1
    require(len(external_names) == expected_externs, "wrong complete external declaration inventory")
    for name in external_names:
        require(not re.search(r"^module " + re.escape(name) + r"\(", sv, re.M), "external declaration became a DUT body")
    require('!hw.inout<i4>' in ir, "missing exact typed physical carrier")
    if profile != "repeated":
        require('carrier = "true-inout"' in ir, "missing boundary carrier map")
    if profile in ("local-alias", "interface-fixed"):
        require(sv.count("tran ") == 1 and '[3:0]' in sv, "missing actual bounded typed tran alias")
        require('nodal.resolved_alias = "checked-local-component"' in ir, "unproved alias emission")
    if profile == "interface-fixed":
        require('connected__m_3_bus_3_pad' in sv and 'member = "bus.pad"' in ir, "nested logical pin identity lost")
        require('selected_role = "connect"' in ir, "canonical selected role lost")
    if profile == "independent":
        require('.pin (first_pad)' in sv and '.pin (second_pad)' in sv, "reused independent pins shorted")
    require('sv.alias' not in ir, "pinned consumer requires the checked tran alternative")
    return external_names[0] if external_names else ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=list(PROFILES))
    args = parser.parse_args()
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        setattr(args, name, getattr(args, name).resolve())
    args.out.mkdir(parents=True, exist_ok=True)
    receipt = {"simulation": "four-state", "dut_transformations": [], "profiles": {}, "tools": {}}
    receipt["runner_sha256"] = sha(Path(__file__))
    shutil.copyfile(__file__, args.out / "runner.py")
    if args.source:
        receipt["source"] = {"path": str(args.source.resolve()), "sha256": sha(args.source)}
        shutil.copyfile(args.source, args.out / args.source.name)
    for name in ("nodalc", "firtool", "iverilog", "vvp"):
        tool = getattr(args, name)
        version = subprocess.run([str(tool), "-V" if name in ("iverilog", "vvp") else "--version"], capture_output=True, text=True)
        receipt["tools"][name] = {"path": str(tool), "sha256": sha(tool), "version": version.stdout + version.stderr}

    def run(command, log, record):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
        log.write_text(result.stdout + result.stderr)
        record.setdefault("commands", []).append({"argv": list(map(str, command)), "exit": result.returncode, "log": str(log), "log_sha256": sha(log)})
        require(result.returncode == 0, f"{log}: exit {result.returncode}")

    try:
        for profile in args.profiles:
            directory = args.out / profile
            directory.mkdir(exist_ok=True)
            record = receipt["profiles"][profile] = {}
            source = args.input / (profile + ".mlir")
            if not source.exists():
                source = args.input / profile / "input.mlir"
            shutil.copyfile(source, directory / "input.mlir")
            record["input"] = {"path": str(source), "sha256": sha(source)}
            run([args.nodalc, directory / "input.mlir", "--nodal-gate-default", "-o", directory / "gated.mlir"], directory / "gate.log", record)
            run([args.nodalc, directory / "gated.mlir", "--nodal-sequential-to-circt", "-o", directory / "target.mlir"], directory / "lower.log", record)
            run([args.firtool, "--format=mlir", "--verilog", "--verify-each", "--disable-all-randomization", directory / "target.mlir", "-o", directory / "target.sv"], directory / "export.log", record)
            ir = (directory / "target.mlir").read_text()
            sv = (directory / "target.sv").read_text()
            root = re.search(r"nodal.root.module = @([A-Za-z_][A-Za-z_0-9]*)", ir).group(1)
            external = inspect(profile, root, ir, sv)
            record["physical"] = {"root": root, "external": external, "abi": "legacy", "width": 4}
            original = sha(directory / "target.sv")
            model, test = bench(profile, root, external)
            (directory / "external_model.sv").write_text(model)
            (directory / "tb.sv").write_text(test)
            run([args.iverilog, "-g2012", "-s", "tb", "-o", directory / "simulation", directory / "target.sv", directory / "external_model.sv", directory / "tb.sv"], directory / "compile.log", record)
            run([args.vvp, directory / "simulation"], directory / "simulate.log", record)
            require(sha(directory / "target.sv") == original, "consumer changed emitted SV")
            record["files"] = {file.name: sha(file) for file in directory.iterdir() if file.is_file()}
            record["status"] = "passed"
    finally:
        (args.out / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("RESOLVED_TARGET_PASS " + ",".join(args.profiles))


if __name__ == "__main__":
    main()
