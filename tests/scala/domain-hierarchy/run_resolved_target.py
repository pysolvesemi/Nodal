#!/usr/bin/env python3
"""Run focused scalar resolved/extern source replays through unchanged four-state HDL."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

PROFILES = ("read", "drive", "open-drain", "repeated", "independent", "local-alias", "interface-fixed")
SYMBOLIC_PROFILES = ("symbolic-read", "symbolic-drive", "symbolic-split-interface", "symbolic-split-child-driver", "symbolic-split-parent-driver")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def symbolic_bench(profile, root):
    """Independent electrical expectations at three exact parameter settings."""
    mode = profile.removeprefix("symbolic-")
    if mode in ("split-child-driver", "split-parent-driver"):
        return "", f"""module one #(parameter W=2)(output reg done=0);
  reg [W-1:0] data=0, externalData=0;reg enable=0,externalEnable=0;
  wire [W-1:0] written;wire enabled;tri [W-1:0] expected;
  {root} #(.width(W)) dut(.core_clock(1'b0),.data(data),.enable(enable),.written(written),.enabled(enabled));
  assign dut.child_instance.pad=externalEnable?externalData:{{W{{1'bz}}}};
  assign expected=enable?data:{{W{{1'bz}}}};
  assign expected=externalEnable?externalData:{{W{{1'bz}}}};
  task check;#1;
    if($bits(dut.child_instance.pad)!=W || $bits(written)!=W) $fatal(1,"exact split width substitution");
    if(written!==data || enabled!==enable) $fatal(1,"split sidebands changed with external contribution");
    if(dut.child_instance.pad!==expected) $fatal(1,"resolved electrical identity");
  endtask
  initial begin
    for(integer i=0;i<16;i=i+1) begin data=i;externalData=~i;
      enable=0;externalEnable=0;check;externalEnable=1;check;
      enable=1;check;externalEnable=0;check;
    end
    enable=0;externalEnable=1;externalData={{W{{1'bx}}}};check;
    externalData={{W{{1'bz}}}};check;done=1;
  end
endmodule
module tb;wire a,b,c;one #(2) narrow(a);one #(7) wide(b);one #(8) maximum(c);
  initial begin wait(a&&b&&c);$display("SYMBOLIC_RESOLVED_PASS {profile}");$finish;end
endmodule
"""
    expected_output = "data" if mode == "split-interface" else "expected"
    driven = "assign expectedDriven=enable?data:{W{1'bz}};" if mode == "drive" else ""
    return "", f"""module one #(parameter W=2,O=7)(output reg done=0);
  reg [W-1:0] data=0,extA=0,extB=0,extC=0;
  reg enable=0,ena=0,enb=0,enc=0;wire [W-1:0] observed;
  tri [W-1:0] expected,expectedDriven;
  {root} #(.actualWidth(W),.otherWidth(O)) dut(.core_clock(1'b0),.data(data),.other({{O{{1'b1}}}}),.enable(enable),.output_0(observed));
  assign dut.child_instance.physical=ena?extA:{{W{{1'bz}}}};
  assign dut.child_instance.connected__m_3_bus_3_pad=enb?extB:{{W{{1'bz}}}};
  assign dut.child_instance.driven__m_3_bus_3_pad=enc?extC:{{W{{1'bz}}}};
  assign expected=enable?data:{{W{{1'bz}}}};
  assign expected=ena?extA:{{W{{1'bz}}}};
  assign expected=enb?extB:{{W{{1'bz}}}};
  assign expectedDriven=enc?extC:{{W{{1'bz}}}};
  {driven}
  task check;#1;
    if($bits(dut.child_instance.physical)!=W || $bits(dut.child_instance.connected__m_3_bus_3_pad)!=W || $bits(dut.child_instance.driven__m_3_bus_3_pad)!=W || $bits(observed)!=W || $bits(dut.other)!=O)
      $fatal(1,"exact owner substitution and sibling width");
    if(dut.child_instance.physical!==expected || dut.child_instance.connected__m_3_bus_3_pad!==expected)
      $fatal(1,"symbolic bidirectional alias identity");
    if(dut.child_instance.driven__m_3_bus_3_pad!==expectedDriven) $fatal(1,"independent symbolic pin joined");
    if(observed!=={expected_output}) $fatal(1,"original source observation changed");
  endtask
  initial begin
    for(integer i=0;i<16;i=i+1) begin data=i;extA=~i;extB=i^7;extC=i^3;
      for(integer mask=0;mask<16;mask=mask+1) begin
        enable=mask[0];ena=mask[1];enb=mask[2];enc=mask[3];check;
      end
    end
    enable=0;ena=1;enb=0;enc=1;extA={{W{{1'bx}}}};extC={{W{{1'bz}}}};check;
    ena=0;enb=1;extB={{W{{1'bx}}}};extC={{W{{1'bx}}}};check;
    extB={{W{{1'bz}}}};check;done=1;
  end
endmodule
module tb;wire a,b,c;one #(2,7) narrow(a);one #(7,2) wide(b);one #(8,2) maximum(c);
  initial begin wait(a&&b&&c);$display("SYMBOLIC_RESOLVED_PASS {profile}");$finish;end
endmodule
"""


def inspect_symbolic(profile, root, ir, sv):
    require(re.search(r"^module " + re.escape(root) + r"\s*#", sv, re.M), "root parameterization lost")
    require('!hw.inout<int<' in ir, "symbolic physical carrier lost")
    require('sv.alias' not in ir and 'sv.verbatim' in ir, "closed symbolic carrier emission lost")
    if profile in ("symbolic-read", "symbolic-drive", "symbolic-split-interface"):
        require('actualWidth' in sv and '.width(actualWidth)' in sv, "exact differing-owner substitution lost")
        require('connected__m_3_bus_3_pad' in sv and 'member = "bus.pad"' in ir, "nested Interface member identity lost")
        require('tran ' in sv and 'nodal.resolved_alias = "checked-local-component"' in ir, "actual typed alias lost")
    else:
        require('__nodal_split_0_data' in sv and '__nodal_split_0_enable' in sv, "cross-occurrence split sidebands lost")
    return ""


def bench(profile, root, external):
    """Authored environment only; never modify or recreate the generated DUT."""
    if profile in SYMBOLIC_PROFILES:
        return symbolic_bench(profile, root)
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
    if profile in SYMBOLIC_PROFILES:
        return inspect_symbolic(profile, root, ir, sv)
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
    parser.add_argument("--profiles", nargs="+", choices=(*PROFILES, *SYMBOLIC_PROFILES), default=list(PROFILES))
    args = parser.parse_args()
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        setattr(args, name, getattr(args, name).resolve())
    args.out.mkdir(parents=True, exist_ok=True)
    receipt = {"simulation": "four-state", "dut_transformations": [], "profiles": {}, "tools": {}}
    receipt["runner_sha256"] = sha(Path(__file__))
    receipt["environment"] = {"LD_LIBRARY_PATH": os.environ.get("LD_LIBRARY_PATH", "")}
    manifest = args.firtool.parent.parent / ".nodal-toolchain.json"
    if manifest.is_file():
        receipt["toolchain"] = {"path": str(manifest), "sha256": sha(manifest), "identity": json.loads(manifest.read_text())}
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
            record["physical"] = {"root": root, "external": external, "abi": "legacy", "width": "symbolic" if profile in SYMBOLIC_PROFILES else 4}
            if profile in SYMBOLIC_PROFILES:
                record["physical"]["settings"] = [2, 7, 8]
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
