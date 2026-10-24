#!/usr/bin/env python3
"""Replay actual Scala bounded-stack machines through CIRCT and Icarus."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def trap_bench(top: str, pointer: str, frames: list[str], local: str) -> str:
    return f"""module tb;
reg clock=0,reset=1,restart=0,cancel=0,enable=1,call=0,recurse=0,ret=0,emptyReturn=0;
reg [7:0] data=0;
wire [2:0] state; wire [1:0] status; wire taken,enteringFault,exitingSub; wire [7:0] value;
{top} dut(.clock(clock),.reset(reset),.restart(restart),.cancel(cancel),.enable(enable),
 .call(call),.recurse(recurse),.ret(ret),.emptyReturn(emptyReturn),.input_0(data),
 .state(state),.status(status),.taken(taken),.enteringFault(enteringFault),.exitingSub(exitingSub),.value(value));
task tick; begin #2;clock=1;#1;clock=0;#1;end endtask
task check(input [2:0] s,input [1:0] life,depth,input [7:0] v);begin
 if(state !== s || status !== life || dut.{pointer} !== depth || value !== v)
 $fatal(1,"stack mismatch time=%0t state=%d life=%d depth=%d value=%d expected=%d,%d,%d,%d",$time,state,status,dut.{pointer},value,s,life,depth,v);
end endtask
task events(input t,ent,ext);begin #1;
 if(taken !== t || enteringFault !== ent || exitingSub !== ext)
 $fatal(1,"stack events mismatch time=%0t actual=%d,%d,%d expected=%d,%d,%d",$time,taken,enteringFault,exitingSub,t,ent,ext);
end endtask
task start;begin call=0;recurse=0;ret=0;emptyReturn=0;cancel=0;restart=1;enable=0;tick;restart=0;enable=1;end endtask
initial begin
 tick;check(0,0,0,0);reset=0;tick;check(0,0,0,0);
 start;check(0,1,0,0);call=1;events(1,0,0);tick;check(1,1,1,7);call=0;
 if(dut.{frames[0]} !== 3'd2) $fatal(1,"call did not save exact resume");
 enable=0;data=19;recurse=1;events(0,0,0);tick;check(1,1,1,7);enable=1;
 ret=1;events(1,0,1);tick;check(1,1,2,7);recurse=0;
 if(dut.{frames[0]} !== 3'd2 || dut.{frames[1]} !== 3'd1) $fatal(1,"nested call corrupted frames");
 events(1,0,1);tick;check(1,1,1,7);tick;check(2,1,0,3);ret=0;tick;check(4,2,0,3);
 data=88;tick;check(4,2,0,3);
 // Overflow traps are real entry/exit, clear depth, and restart the trap timer.
 start;call=1;tick;call=0;recurse=1;tick;check(1,1,2,7);
 events(1,1,1);tick;check(3,1,0,7);recurse=0;data=41;
 tick;check(3,1,0,41);tick;check(4,2,0,3);
 // Priority underflow wins over a simultaneously enabled call and completes canonically.
 start;emptyReturn=1;call=1;events(1,0,0);tick;check(4,2,0,3);emptyReturn=0;call=0;
 // A stale frame is not read by an empty return.
 start;force dut.{frames[0]}=3'bxxx;release dut.{frames[0]};emptyReturn=1;
 tick;check(4,2,0,3);emptyReturn=0;
 // Invalid pointer applies even without a candidate, and while Completed.
 force dut.{pointer}=2'd3;release dut.{pointer};events(1,1,0);tick;check(3,1,0,7);
 start;call=1;tick;call=0;force dut.{pointer}=2'd3;release dut.{pointer};
 events(1,1,1);tick;check(3,1,0,7);
 // Invalid popped local encoding traps rather than treating it as an arbitrary fixed edge.
 start;call=1;tick;call=0;force dut.{frames[0]}=3'd7;release dut.{frames[0]};ret=1;
 events(1,1,1);tick;check(3,1,0,7);ret=0;
 // Cancel wins over restart while running; later restart establishes empty depth while paused.
 start;call=1;tick;call=0;enable=0;cancel=1;restart=1;events(0,0,1);tick;check(1,3,0,3);
 cancel=0;tick;check(0,1,0,3);restart=0;enable=1;
 call=1;tick;call=0;force dut.{local}=3'd7;release dut.{local};events(0,0,0);tick;check(0,1,0,7);
 // Reset only resets the pointer; frames retain successfully authored continuations.
 call=1;tick;call=0;check(1,1,1,7);reset=1;events(0,0,0);tick;check(0,0,0,0);
 if(dut.{frames[0]} !== 3'd2) $fatal(1,"reset initialized a continuation frame");
 $display("STACK_TARGET_PASS trap nested priority overflow underflow invalid-pointer invalid-frame actions pause cancel restart recover reset frames");$finish;
end
initial begin #2000;$fatal(1,"timeout");end
endmodule
"""


def required_bench(top: str) -> str:
    return f"""module tb;
reg clockA=0,clockB=0,resetA=1,resetB=1;
reg restartA=0,cancelA=0,enableA=0,callA=0,retA=0;
reg restartB=0,cancelB=0,enableB=0,callB=0,retB=0;
wire [2:0] stateA,stateB;wire [1:0] statusA,statusB;
{top} dut(.*);
task tickA;begin #2;clockA=1;#1;clockA=0;#1;end endtask
task tickB;begin #2;clockB=1;#1;clockB=0;#1;end endtask
task check(input[2:0] a,b,input[1:0] sa,sb);begin
 if(stateA !== a || stateB !== b || statusA !== sa || statusB !== sb)
 $fatal(1,"required stack mismatch %d,%d status=%d,%d",stateA,stateB,statusA,statusB);
end endtask
initial begin
 tickA;resetA=0;restartA=1;tickA;restartA=0;restartB=1;tickB;restartB=0;check(0,0,1,1);
 enableA=1;enableB=1;callA=1;tickA;callA=0;check(1,0,1,1);
 callB=1;tickB;callB=0;check(1,1,1,1);retA=1;enableA=0;tickA;check(1,1,1,1);
 retB=1;tickB;retB=0;check(1,2,1,1);tickB;check(1,4,1,2);
 enableA=1;tickA;retA=0;check(2,4,1,2);tickA;check(4,4,2,2);
 resetA=1;tickB;check(4,4,2,2);tickA;check(0,4,0,2);
 resetB=0;tickB;check(0,4,0,2);resetB=1;tickB;check(0,4,0,2);
 restartB=1;enableB=0;tickB;restartB=0;check(0,0,0,1);
 enableB=1;callB=1;tickB;callB=0;check(0,1,0,1);
 cancelB=1;restartB=1;tickB;check(0,1,0,3);cancelB=0;tickB;check(0,0,0,1);
 $display("STACK_TARGET_PASS required independent clocks mixed reset restart pause cancel");$finish;
end
initial begin #1000;$fatal(1,"timeout");end
endmodule
"""


def composed_bench(top: str) -> str:
    return f"""module tb;
reg clock=0,reset=1,restart=0,cancel=0,enable=0,call=0,ret=0;
wire [1:0] status,childStatus;wire [2:0] childState;wire childActive;
{top} dut(.*);
task tick;begin #2;clock=1;#1;clock=0;#1;end endtask
task check(input[1:0] p,c,input[2:0] s,input a);begin
 if(status !== p || childStatus !== c || childState !== s || childActive !== a)
 $fatal(1,"composed stack mismatch %d,%d state=%d active=%d",status,childStatus,childState,childActive);
end endtask
initial begin
 tick;check(0,0,0,0);reset=0;restart=1;tick;check(1,1,0,1);restart=0;
 enable=1;call=1;tick;check(1,1,1,1);call=0;ret=1;enable=0;tick;check(1,1,1,1);
 enable=1;tick;check(1,1,2,1);ret=0;tick;check(1,2,4,1);tick;check(2,2,4,0);
 restart=1;enable=0;tick;check(1,1,0,1);restart=0;enable=1;call=1;tick;check(1,1,1,1);call=0;
 cancel=1;restart=1;tick;check(3,3,1,0);cancel=0;tick;check(1,1,0,1);restart=0;
 reset=1;tick;check(0,0,0,0);
 $display("STACK_TARGET_PASS composed registered completion join ownership pause cancel restart reset");$finish;
end
initial begin #1000;$fatal(1,"timeout");end
endmodule
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "snapshot", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--profile", choices=("trap", "required", "composed"), required=True)
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must stay under .work")
    for name in ("input", "snapshot", "nodalc", "firtool", "iverilog", "vvp"):
        path = getattr(args, name).resolve()
        if not path.is_file():
            parser.error(f"{name} does not exist: {path}")
        setattr(args, name, path)
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"status": "running", "profile": args.profile,
                "inputs": {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest()
                           for name in ("input", "snapshot")},
                "tools": {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest()
                          for name in ("nodalc", "firtool", "iverilog", "vvp")}}
    def save():
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    def run(label, command):
        (args.out / (label + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if result.returncode:
            manifest.update(status="failed", failed_step=label)
            save()
            raise RuntimeError(f"{label}: {result.returncode}\n{result.stdout[-8000:]}")
        return result.stdout
    save()
    gated, circt, sv = (args.out / name for name in ("stack.gated.mlir", "stack.circt.mlir", "stack.sv"))
    run("gate", [str(args.nodalc), str(args.input), "--nodal-gate-default", "-o", str(gated)])
    run("lower", [str(args.nodalc), str(gated), "--nodal-sequential-to-circt", "-o", str(circt)])
    run("export", [str(args.firtool), str(circt), "--format=mlir", "--verilog", "--verify-each",
                   "--disable-all-randomization", "--lowering-options=disallowLocalVariables", "-o", str(sv)])
    snapshot = json.loads(args.snapshot.read_text())
    top = snapshot["root"]
    design = sv.read_text()
    if not re.search(r"\bmodule\s+" + re.escape(top) + r"\s*\(", design):
        raise RuntimeError("actual root missing from generated design")
    if args.profile == "trap":
        machine, = snapshot["digital_graph"]["flat_fsms"]
        def symbol(path):
            matches = re.findall(r'%([A-Za-z_]\w*) = seq\.compreg[^\n]*nodal\.source_path = "' + re.escape(path) + '"', circt.read_text())
            if len(matches) != 1 or not re.search(r'\breg\s+(?:\[[^\]]+\]\s+)?'+re.escape(matches[0])+r'\s*;', design):
                raise RuntimeError("private role does not map to exactly one generated register: " + path)
            return matches[0]
        stack = machine["stack"]
        bench = trap_bench(top, symbol(stack["pointer_storage"]), [symbol(p) for p in stack["frame_storage"]], symbol(machine["storage"]))
    else:
        bench = required_bench(top) if args.profile == "required" else composed_bench(top)
    tb, executable = args.out / "tb.sv", args.out / "simulation"
    tb.write_text(bench)
    run("compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o", str(executable), str(sv), str(tb)])
    output = run("simulate", [str(args.vvp), str(executable)])
    if "STACK_TARGET_PASS " + args.profile not in output:
        raise RuntimeError("target did not report acceptance")
    manifest.update(status="pass", top=top, output=output.strip())
    save()
    print(output, end="")


if __name__ == "__main__":
    main()
