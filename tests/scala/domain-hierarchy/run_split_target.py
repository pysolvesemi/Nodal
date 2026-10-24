#!/usr/bin/env python3
"""Check fixed resolved split sidebands through unchanged four-state hierarchy HDL."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

PROFILES = ("down", "up", "sibling", "reused", "variants")
ROOTS = {p: "Increment58FixedSplit" + p.title() for p in PROFILES}
MODULES = {"down": 2, "up": 2, "sibling": 3, "reused": 2, "variants": 3}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def bench(profile, root):
    """Only stimulus and independent electrical expectations, never a replacement DUT."""
    if profile in ("down", "up", "sibling"):
        return f"""module tb;
  reg [3:0] data=0, externalData=0; reg enable=0, externalEnable=0;
  tri [3:0] pad, expected; wire [3:0] written, observed; wire enabled;
  assign pad=externalEnable?externalData:4'bz;
  assign expected=externalEnable?externalData:4'bz;
  assign expected=enable?data:4'bz;
  {root} dut(.core_clock(1'b0),.data(data),.enable(enable),.written(written),.enabled(enabled),.observed(observed),.pad(pad));
  task check;
    #1;
    if(written!==data || enabled!==enable) $fatal(1,"split sidebands changed with actual driver data=%b written=%b enable=%b enabled=%b",data,written,enable,enabled);
    if(observed!==expected || pad!==expected) $fatal(1,"resolved read differs from independent four-state net: pin=%b read=%b expected=%b",pad,observed,expected);
  endtask
  initial begin
    check;
    for(integer i=0;i<16;i=i+1) begin
      data=i;externalData=~i;
      enable=0;externalEnable=0;check;
      externalEnable=1;check;
      enable=1;check;
      externalEnable=0;check;
    end
    data=4'b1001;externalData=4'b1010;externalEnable=1;check;
    if(observed!==4'b10xx) $fatal(1,"missing partial contention");
    enable=0;externalData=4'bz;check;
    externalData=4'bx;check;
    $display("SPLIT_TARGET_PASS {profile}");$finish;
  end
endmodule
"""
    if profile == "reused":
        ports = [".core_clock(1'b0)"]
        declarations = ""
        checks = ""
        for prefix in ("first", "second"):
            declarations += f"""
  reg [3:0] {prefix}Data=0,{prefix}External=0;reg {prefix}Enable=0,{prefix}Interfere=0;
  tri [3:0] {prefix}Pad,{prefix}Expected;wire [3:0] {prefix}Written,{prefix}Observed;wire {prefix}Enabled;
  assign {prefix}Pad={prefix}Interfere?{prefix}External:4'bz;
  assign {prefix}Expected={prefix}Interfere?{prefix}External:4'bz;
  assign {prefix}Expected={prefix}Enable?{prefix}Data:4'bz;
"""
            ports += [f".{prefix}{field}({prefix}{field})" for field in ("Data", "Enable", "Written", "Enabled", "Observed", "Pad")]
            checks += f"""
    if({prefix}Written!=={prefix}Data || {prefix}Enabled!=={prefix}Enable) $fatal(1,"{prefix} reused sideband identity");
    if({prefix}Observed!=={prefix}Expected || {prefix}Pad!=={prefix}Expected) $fatal(1,"{prefix} reused physical identity");
"""
        return f"""module tb;
{declarations}
  {root} dut({','.join(ports)});
  task check;#1;{checks}endtask
  initial begin
    for(integer i=0;i<16;i=i+1) for(integer j=0;j<16;j=j+1) begin
      firstData=i;secondData=j;firstExternal=~i;secondExternal=~j;
      for(integer k=0;k<4;k=k+1) begin
        firstEnable=k[0];secondEnable=k[1];firstInterfere=0;secondInterfere=0;check;
        firstInterfere=1;check;secondInterfere=1;check;firstInterfere=0;check;
      end
    end
    $display("SPLIT_TARGET_PASS reused");$finish;
  end
endmodule
"""
    declarations = "reg [3:0] joinedData=0,leftData=0,rightData=0;reg joinedEnable=0,leftEnable=0,rightEnable=0;\n"
    ports = [".core_clock(1'b0)"] + [f".{p}{f}({p}{f})" for p in ("joined", "left", "right") for f in ("Data", "Enable")]
    checks = ""
    for prefix, driver in (("firstLeft", "joined"), ("firstRight", "joined"), ("secondLeft", "left"), ("secondRight", "right")):
        declarations += f"""
  reg [3:0] {prefix}External=0;reg {prefix}Interfere=0;
  tri [3:0] {prefix}Pad;wire [3:0] {prefix}Written,{prefix}Observed;wire {prefix}Enabled;
  assign {prefix}Pad={prefix}Interfere?{prefix}External:4'bz;
"""
        ports += [f".{prefix}{f}({prefix}{f})" for f in ("Written", "Enabled", "Observed", "Pad")]
        checks += f"""
    if({prefix}Written!=={driver}Data || {prefix}Enabled!=={driver}Enable) $fatal(1,"{prefix} wrong route ABI driver");
    if({prefix}Pad!=={driver}Expected || {prefix}Observed!=={driver}Expected) $fatal(1,"{prefix} physical alias differs");
"""
    declarations += "tri [3:0] joinedExpected,leftExpected,rightExpected;\n"
    for driver in ("joined", "left", "right"):
        declarations += f"assign {driver}Expected={driver}Enable?{driver}Data:4'bz;\n"
    for prefix, driver in (("firstLeft", "joined"), ("firstRight", "joined"), ("secondLeft", "left"), ("secondRight", "right")):
        declarations += f"assign {driver}Expected={prefix}Interfere?{prefix}External:4'bz;\n"
    return f"""module tb;
{declarations}
  {root} dut({','.join(ports)});
  task check;#1;{checks}endtask
  initial begin
    for(integer i=0;i<16;i=i+1) begin
      joinedData=i;leftData=i^4'h5;rightData=i^4'ha;
      firstLeftExternal=~i;firstRightExternal=i^4'h9;
      secondLeftExternal=~leftData;secondRightExternal=~rightData;
      for(integer k=0;k<8;k=k+1) begin
        joinedEnable=k[0];leftEnable=k[1];rightEnable=k[2];
        firstLeftInterfere=0;firstRightInterfere=0;secondLeftInterfere=0;secondRightInterfere=0;check;
        firstLeftInterfere=1;check;firstRightInterfere=1;check;
        secondLeftInterfere=1;check;secondRightInterfere=1;check;
      end
    end
    $display("SPLIT_TARGET_PASS variants");$finish;
  end
endmodule
"""


def replay(args, receipt):
    """Compile only maintained witnesses into private output using current shared API classes."""
    import fcntl
    import os

    repo = args.repo.resolve()
    metadata = repo / ".work/mill-out/core/scala"
    paths = {
        "run_classpath": metadata / "testkit/test/runClasspath.json",
        "compiler_classpath": metadata / "api/scalaCompilerClasspath.json",
        "plugin": metadata / "constructorPlugin/jar.dest/out.jar",
    }
    java = args.java
    if not java and os.environ.get("JAVA_HOME"):
        java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    require(java is not None and java.is_file(), "--replay requires --java or JAVA_HOME")
    java = java.resolve()
    for path in paths.values():
        require(path.is_file(), f"missing built shared Scala prerequisite: {path}")
    sources = [args.source, args.source.with_name("Increment58FixedSplitReplay.scala")]
    require(all(p.is_file() for p in sources), "source and maintained replay must be siblings")
    classes = args.out / "source-classes"
    classes.mkdir(exist_ok=False)
    inputs = args.out / "source-replay"

    def classpath(path):
        return [Path(v.split(":", 3)[3]) for v in json.loads(path.read_text())["value"]]

    with (repo / ".work/f58-mill.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        runtime, compiler = classpath(paths["run_classpath"]), classpath(paths["compiler_classpath"])
        # The test runtime can retain an older transitive scala3-library. Direct
        # dotc must use the pinned compiler's matching standard library, as the
        # shared build does, rather than loading two definitions of scala.caps.
        superseded = [p for p in runtime if "/org/scala-lang/" in str(p)]
        runtime = list(dict.fromkeys([p for p in runtime if p not in superseded] + compiler))
        roots = list(dict.fromkeys(runtime + [paths["plugin"]]))

        def inventory():
            return {str(p): sha(p) for root in roots for p in (sorted(root.rglob("*")) if root.is_dir() else [root]) if p.is_file()}

        before = inventory()
        record = receipt["source_replay"] = {"sources": {str(p): sha(p) for p in sources}, "java": str(java), "java_sha256": sha(java), "classpath_metadata": {k: str(v) for k, v in paths.items()}, "classes_before": before, "compiler_aligned_scala_jars": list(map(str, compiler)), "superseded_runtime_scala_jars": list(map(str, superseded)), "commands": []}
        cp = ":".join(map(str, runtime))
        commands = [
            [str(java), "-XX:-UsePerfData", "-cp", ":".join(map(str, compiler)), "dotty.tools.dotc.Main", "-color:never", "-deprecation", "-feature", "-unchecked", "-Wunused:all", "-Werror", "-classpath", cp, "-d", str(classes), "-Xplugin:" + str(paths["plugin"]), "-Xplugin-require:nodal-constructor", *map(str, sources)],
            [str(java), "-XX:-UsePerfData", "-cp", str(classes) + ":" + cp, "nodal.internal.testkit.Increment58FixedSplitReplay", str(inputs)],
        ]
        for name, command in zip(("compile", "replay"), commands):
            p = subprocess.run(command, cwd=repo, capture_output=True, text=True)
            log = args.out / ("source-" + name + ".log")
            log.write_text(p.stdout + p.stderr)
            record["commands"].append({"argv": command, "exit": p.returncode, "log": str(log), "log_sha256": sha(log)})
            require(p.returncode == 0, f"source {name}: {log}")
        after = inventory()
        record["classes_after"] = after
        require(before == after, "shared classpath changed during source replay")
        record["status"] = "passed"
    return inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("out", "source", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--input", type=Path, help="existing source replay directory when --replay is absent")
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=list(PROFILES))
    parser.add_argument("--replay", action="store_true", help="strictly compile standalone source/replay with the actual shared constructor plugin")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--java", type=Path)
    args = parser.parse_args()
    require(args.replay or args.input, "provide --input or --replay")
    for name in ("out", "source", "nodalc", "firtool", "iverilog", "vvp"):
        setattr(args, name, getattr(args, name).resolve())
    if args.input:
        args.input = args.input.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    receipt = {"simulation": "four-state", "dut_transformations": [], "profiles": {}, "tools": {}, "source": {"path": str(args.source), "sha256": sha(args.source)}, "runner_sha256": sha(Path(__file__))}
    shutil.copyfile(args.source, args.out / args.source.name)
    shutil.copyfile(__file__, args.out / "runner.py")
    for name in ("nodalc", "firtool", "iverilog", "vvp"):
        tool = getattr(args, name)
        p = subprocess.run([str(tool), "-V" if name in ("iverilog", "vvp") else "--version"], capture_output=True, text=True)
        receipt["tools"][name] = {"path": str(tool), "sha256": sha(tool), "version": p.stdout + p.stderr}

    def run(cmd, log, record):
        p = subprocess.run(list(map(str, cmd)), capture_output=True, text=True)
        log.write_text(p.stdout + p.stderr)
        record.setdefault("commands", []).append({"argv": list(map(str, cmd)), "exit": p.returncode, "log": str(log), "log_sha256": sha(log)})
        require(p.returncode == 0, f"{log}: exit {p.returncode}")

    try:
        if args.replay:
            args.input = replay(args, receipt)
        for profile in args.profiles:
            directory = args.out / profile
            directory.mkdir(exist_ok=True)
            record = receipt["profiles"][profile] = {}
            source = args.input / profile / "input.mlir"
            shutil.copyfile(source, directory / "input.mlir")
            record["input"] = {"path": str(source), "sha256": sha(source)}
            run([args.nodalc, directory / "input.mlir", "--nodal-gate-default", "-o", directory / "gated.mlir"], directory / "gate.log", record)
            run([args.nodalc, directory / "gated.mlir", "--nodal-sequential-to-circt", "-o", directory / "target.mlir"], directory / "lower.log", record)
            run([args.firtool, directory / "target.mlir", "--format=mlir", "--verilog", "--verify-each", "--disable-all-randomization", "-o", directory / "target.sv"], directory / "export.log", record)
            ir = (directory / "target.mlir").read_text()
            root = re.search(r"nodal.root.module = @([A-Za-z_][A-Za-z_0-9]*)", ir).group(1)
            require(root == ROOTS[profile], "input root does not match maintained fixture")
            require(len(re.findall(r"^  hw\.module @", ir, re.M)) == MODULES[profile], "wrong relative route reuse/variant module count")
            require("seq.compreg" not in ir and "sv.reg" not in ir, "split transport added state")
            require("nodal.split_sideband" in ir and "relative_route" in ir, "missing actual route signature")
            require("hw.module.extern" not in ir, "fixed split fixture unexpectedly has opaque contribution")
            if profile == "variants":
                require("nodal.resolved_alias" in ir, "variant fixture lost true physical alias")
            original = sha(directory / "target.sv")
            (directory / "tb.sv").write_text(bench(profile, root))
            run([args.iverilog, "-g2012", "-s", "tb", "-o", directory / "simulation", directory / "target.sv", directory / "tb.sv"], directory / "compile.log", record)
            run([args.vvp, directory / "simulation"], directory / "simulate.log", record)
            require(sha(directory / "target.sv") == original, "emitted SV changed")
            record["physical"] = {"root": root, "module_bodies": MODULES[profile], "width": 4, "abi": "legacy", "latency_added": 0}
            record["status"] = "passed"
            record["files"] = {p.name: sha(p) for p in directory.iterdir() if p.is_file()}
    finally:
        (args.out / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("SPLIT_TARGET_PASS " + ",".join(args.profiles))


if __name__ == "__main__":
    main()
