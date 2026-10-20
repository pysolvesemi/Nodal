#!/usr/bin/env python3
"""Generate and simulate the actual two-instance reusable-FSM Scala witness."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def controlled_bench(top: str) -> str:
    return f"""module tb;
reg clock=0, reset=1, restartA=0, restartB=0, cancel=0, enable=1, go=0, finish=0;
reg [7:0] data=0;
wire [1:0] statusA,statusB,stateA,stateB;
wire doneA,runningA,cancelledA,enteringA,exitingA,takenA;
wire [7:0] valueA,valueB;
{top} dut(.clock(clock),.reset(reset),.restartA(restartA),.restartB(restartB),
 .cancel(cancel),.enable(enable),.go(go),.finish(finish),.input_0(data),
 .statusA(statusA),.statusB(statusB),.stateA(stateA),.stateB(stateB),
 .doneA(doneA),.runningA(runningA),.cancelledA(cancelledA),
 .enteringA(enteringA),.exitingA(exitingA),.takenA(takenA),.valueA(valueA),.valueB(valueB));
task tick; begin #2; clock=1; #1; clock=0; #1; end endtask
task check(input [1:0] a,b,sa,sb,input [7:0] x,y); begin
 if(stateA !== a || stateB !== b || statusA !== sa || statusB !== sb || valueA !== x || valueB !== y)
  $fatal(1,"controlled mismatch local=%d,%d status=%d,%d values=%d,%d",stateA,stateB,statusA,statusB,valueA,valueB);
 if(doneA !== (sa==2 && a==2) || runningA !== (sa==1) || cancelledA !== (sa==3))
  $fatal(1,"typed lifecycle observation mismatch");
end endtask
task events(input ent,ext,taken); begin #1;
 if(enteringA !== ent || exitingA !== ext || takenA !== taken)
  $fatal(1,"lifecycle event is not an ordinary transition: %d %d %d",enteringA,exitingA,takenA);
end endtask
initial begin
 tick; check(0,0,0,0,0,0); reset=0; go=1;
 tick; check(0,0,0,0,0,0);
 restartA=1; enable=0; events(1,0,0); tick; check(0,0,1,0,0,0);
 restartA=0; enable=1; events(0,0,1); tick; check(1,0,1,0,4,0);
 restartB=1; data=17; tick; check(1,0,1,1,17,0);
 restartB=0; enable=0; data=22; events(0,0,0); tick; check(1,0,1,1,17,0);
 cancel=1; restartA=1; events(0,1,0); tick; check(1,0,3,3,17,0);
 cancel=0; enable=1; events(1,0,0); tick; check(0,0,1,3,17,0);
 restartA=0; restartB=1; tick; check(1,0,1,1,4,0);
 restartB=0; finish=1; data=29; tick; check(2,1,2,1,29,14);
 go=0; data=31; tick; check(2,2,2,2,29,31);
 finish=0; data=44; tick; check(2,2,2,2,29,31);
 restartA=1; cancel=1; events(1,0,0); tick; check(0,2,1,2,29,31);
 restartA=0; cancel=0; go=1; tick; check(1,2,1,2,4,31);
 reset=1; #1; check(1,2,1,2,4,31); events(0,0,0); tick; check(0,0,0,0,0,0);
 $display("REUSABLE_FSM_TARGET_PASS"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def terminal_bench(top: str) -> str:
    return f"""module tb;
reg clock=0,reset=1,restart=0,cancel=0,enable=0;
wire [1:0] status;
wire done;
wire [7:0] value;
{top} dut(.clock(clock),.reset(reset),.restart(restart),.cancel(cancel),.enable(enable),
 .status(status),.done(done),.value(value));
task tick; begin #2; clock=1; #1; clock=0; #1; end endtask
task check(input [1:0] s,input d,input [7:0] v); begin
 if(status !== s || done !== d || value !== v) $fatal(1,"initial terminal lifecycle mismatch");
end endtask
initial begin
 tick; check(0,0,0); reset=0; enable=1; tick; check(0,0,0);
 restart=1; enable=0; tick; check(2,1,7);
 restart=0; cancel=1; enable=1; tick; check(2,1,7);
 restart=1; tick; check(2,1,7);
 reset=1; tick; check(0,0,0);
 $display("REUSABLE_FSM_TARGET_PASS"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def required_bench(top: str, profile: str) -> str:
    material = profile == "required-material"
    clock_b = 1 if material else 0
    active_b, inactive_b = (0, 1) if material else (1, 0)
    reset_b_check = "checkB(0,0,29);" if material else "checkB(2,2,29);"
    return f"""module tb;
reg clockA=0,resetA=1,clockB={clock_b},resetB=0;
reg restartA=0,cancelA=0,enableA=0,startA=0,finishA=0;
reg restartB=0,cancelB=0,enableB=0,startB=0,finishB=0;
reg [7:0] inputA=0,inputB=0;
wire [1:0] statusA,statusB,stateA,stateB;
wire doneA,doneB;
wire [7:0] valueA,valueB;
{top} dut(.clockA(clockA),.resetA(resetA),.clockB(clockB),.resetB(resetB),
 .restartA(restartA),.cancelA(cancelA),.enableA(enableA),.startA(startA),.finishA(finishA),
 .restartB(restartB),.cancelB(cancelB),.enableB(enableB),.startB(startB),.finishB(finishB),
 .inputA(inputA),.inputB(inputB),.statusA(statusA),.statusB(statusB),
 .stateA(stateA),.stateB(stateB),.doneA(doneA),.doneB(doneB),.valueA(valueA),.valueB(valueB));
task tickA; reg [12:0] saved; begin
 #2; clockA=1; #1; saved={{stateA,statusA,doneA,valueA}}; clockA=0; #1;
 if({{stateA,statusA,doneA,valueA}} !== saved) $fatal(1,"A sampled the wrong clock edge");
end endtask
task tickB; reg [12:0] saved; begin
 #2; clockB={active_b}; #1; saved={{stateB,statusB,doneB,valueB}}; clockB={inactive_b}; #1;
 if({{stateB,statusB,doneB,valueB}} !== saved) $fatal(1,"B sampled the wrong clock edge");
end endtask
task lifecycleA(input [1:0] state,status); begin
 if(stateA !== state || statusA !== status || doneA !== (status==2))
  $fatal(1,"required A lifecycle mismatch: %d %d %d",stateA,statusA,doneA);
end endtask
task lifecycleB(input [1:0] state,status); begin
 if(stateB !== state || statusB !== status || doneB !== (status==2))
  $fatal(1,"required B lifecycle mismatch: %d %d %d",stateB,statusB,doneB);
end endtask
task checkA(input [1:0] state,status,input [7:0] value); begin
 lifecycleA(state,status);
 if(valueA !== value) $fatal(1,"required A action value mismatch: %d",valueA);
end endtask
task checkB(input [1:0] state,status,input [7:0] value); begin
 lifecycleB(state,status);
 if(valueB !== value) $fatal(1,"required B action value mismatch: %d",valueB);
end endtask
initial begin
 // Conditional private state resets, but ordinary uninitialized user storage does not.
 tickA; lifecycleA(0,0); tickB;
 {"lifecycleB(0,0);" if material else "// Resetless B deliberately has no pre-restart value assertion."}
 resetA=0; resetB=1;
 // Explicit restart with cancel low establishes resetless state even while paused.
 restartB=1; tickB; checkB(0,1,7); lifecycleA(0,0);
 restartB=0; restartA=1; tickA; checkA(0,1,7); checkB(0,1,7);
 restartA=0; enableA=1; enableB=1; startA=1;
 tickA; checkA(1,1,7); checkB(0,1,7);
 startA=0; startB=1; tickB; checkA(1,1,7); checkB(1,1,7);
 startB=0; inputA=17; tickA; checkA(1,1,17); checkB(1,1,7);
 inputB=29; tickB; checkA(1,1,17); checkB(1,1,29);
 enableA=0; inputA=99; finishA=1; tickA; checkA(1,1,17);
 finishB=1; tickB; checkA(1,1,17); checkB(2,2,29);
 inputB=44; tickB; checkB(2,2,29);
 cancelA=1; restartA=1; tickA; checkA(1,3,17); checkB(2,2,29);
 cancelA=0; tickA; checkA(0,1,7);
 restartA=0; enableA=1; startA=1; tickA; checkA(1,1,7);
 startA=0; inputA=55; tickA; checkA(2,2,55);
 // Reset and clocks are occurrence-specific, not copied from the first binding.
 resetA=1; #1; checkA(2,2,55); tickB; checkA(2,2,55); checkB(2,2,29);
 tickA; checkA(0,0,55); checkB(2,2,29);
 resetB=0; #1; checkB(2,2,29); tickB; {reset_b_check} checkA(0,0,55);
 resetB=1; restartB=1; enableB=0; tickB; checkB(0,1,7); checkA(0,0,55);
 $display("REUSABLE_FSM_TARGET_PASS {profile}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def composed_bench(top: str, profile: str) -> str:
    early = profile in ("composed-any", "composed-priority")
    nested = profile == "composed-nested"
    completion = (
        "tick; check(1,2,2,3,0,0,11,22);" if early else
        "finishSecond=1; tick; check(0,1,2,2,1,1,11,22); " +
        ("tick; check(0,1,2,2,0,0,11,22); " if nested else "") +
        "tick; check(1,2,2,2,0,0,11,22);"
    )
    settle = "tick; tick; tick;" if nested else "tick; tick;"
    return f"""module tb;
reg clock=0,reset=1,restart=0,cancel=0,enable=0,finishFirst=0,finishSecond=0;
wire state,firstActive,secondActive;
wire [1:0] status,firstStatus,secondStatus;
wire [7:0] firstValue,secondValue;
{top} dut(.clock(clock),.reset(reset),.restart(restart),.cancel(cancel),.enable(enable),
 .finishFirst(finishFirst),.finishSecond(finishSecond),.status(status),.state(state),
 .firstStatus(firstStatus),.secondStatus(secondStatus),.firstActive(firstActive),
 .secondActive(secondActive),.firstValue(firstValue),.secondValue(secondValue));
task tick; reg [24:0] saved; begin
 #2; clock=1; #1; saved={{state,status,firstStatus,secondStatus,firstActive,secondActive,firstValue,secondValue}};
 clock=0; #1;
 if({{state,status,firstStatus,secondStatus,firstActive,secondActive,firstValue,secondValue}} !== saved)
  $fatal(1,"composition sampled the wrong edge");
end endtask
task check(input localState,input [1:0] root,a,b,input activeA,activeB,input [7:0] x,y); begin
 if(state !== localState || status !== root || firstStatus !== a || secondStatus !== b ||
    firstActive !== activeA || secondActive !== activeB || firstValue !== x || secondValue !== y)
  $fatal(1,"composition mismatch local=%d root=%d children=%d,%d active=%d,%d values=%d,%d",
   state,status,firstStatus,secondStatus,firstActive,secondActive,firstValue,secondValue);
end endtask
initial begin
 tick; check(0,0,0,0,0,0,0,0); reset=0;
 finishFirst=1; tick; check(0,0,0,0,0,0,0,0);
 restart=1; tick; check(0,1,1,1,1,1,11,22);
 restart=0; tick; check(0,1,1,1,1,1,11,22);
 enable=1; tick; check(0,1,2,1,1,1,11,22);
 {completion}
 finishFirst=0; finishSecond=0; tick;
 restart=1; cancel=1; enable=0; tick; check(0,1,1,1,1,1,11,22);
 tick; check(0,3,3,3,0,0,11,22);
 cancel=0; tick; check(0,1,1,1,1,1,11,22);
 restart=0; enable=1; finishFirst=1; finishSecond=1;
 {settle} check(1,2,2,2,0,0,11,22);
 reset=1; #1; check(1,2,2,2,0,0,11,22); tick; check(0,0,0,0,0,0,0,0);
 $display("REUSABLE_FSM_TARGET_PASS {profile}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def timed_bench(top: str, profile: str) -> str:
    transfer = profile == "timed-transfer"
    cycles = 1 if profile == "timed-one" else 3
    sequence = """
 inputValid=1; inputReady=0; tick; check(0,0,1,1);
 inputValid=0; inputReady=1; tick; check(0,0,1,1);
 inputValid=1; enable=0; tick; check(0,0,1,1);
 enable=1; tick; check(1,1,2,2);
""" if transfer else f"""
 repeat({cycles - 1}) begin tick; check(0,0,1,1); end
 tick; check(1,1,2,2);
"""
    return f"""module tb;
reg clock=0,reset=1,restartA=0,restartB=0,cancel=0,enable=0;
reg [7:0] inputData=0;
reg inputValid=0,inputReady=0;
wire stateA,stateB,observedReady;
wire [1:0] statusA,statusB;
{top} dut(.clock(clock),.reset(reset),.restartA(restartA),.restartB(restartB),
 .cancel(cancel),.enable(enable),.inputData(inputData),.inputValid(inputValid),
 .inputReady(inputReady),.stateA(stateA),.stateB(stateB),.statusA(statusA),
 .statusB(statusB),.observedReady(observedReady));
task tick; reg [5:0] saved; begin
 #2; clock=1; #1; saved={{stateA,stateB,statusA,statusB}}; clock=0; #1;
 if({{stateA,stateB,statusA,statusB}} !== saved) $fatal(1,"timing wrong edge");
 if(observedReady !== inputReady) $fatal(1,"wait acquired or changed ready ownership");
end endtask
task check(input a,b,input [1:0] sa,sb); begin
 if(stateA !== a || stateB !== b || statusA !== sa || statusB !== sb)
  $fatal(1,"timing mismatch state=%d,%d status=%d,%d",stateA,stateB,statusA,statusB);
end endtask
initial begin
 tick; check(0,0,0,0); reset=0;
 enable=1; repeat(4) begin tick; check(0,0,0,0); end
 restartA=1; restartB=1; enable=0; tick; check(0,0,1,1);
 restartA=0; restartB=0; repeat(4) begin tick; check(0,0,1,1); end
 enable=1;
 {sequence}
 repeat(3) begin tick; check(1,1,2,2); end
 restartA=1; enable=0; tick; check(0,1,1,2);
 restartA=0; tick; check(0,1,1,2);
 cancel=1; restartA=1; tick; check(0,1,3,2);
 cancel=0; tick; check(0,1,1,2);
 restartA=0; tick; check(0,1,1,2);
 enable=1;
 {"" if transfer else f"repeat({cycles - 1}) begin tick; check(0,1,1,2); end"}
 tick; check(1,1,2,2);
 reset=1; #1; check(1,1,2,2); tick; check(0,0,0,0);
 $display("REUSABLE_FSM_TARGET_PASS {profile}"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def required_timed_bench(top: str) -> str:
    return f"""module tb;
reg clockA=0,resetA=1,clockB=0,resetB=1;
reg restartA=0,restartB=0,cancelA=0,cancelB=0,enableA=0,enableB=0;
wire stateA,stateB;
wire [1:0] statusA,statusB;
{top} dut(.clockA(clockA),.resetA(resetA),.clockB(clockB),.resetB(resetB),
 .restartA(restartA),.restartB(restartB),.cancelA(cancelA),.cancelB(cancelB),
 .enableA(enableA),.enableB(enableB),.stateA(stateA),.stateB(stateB),
 .statusA(statusA),.statusB(statusB));
task tickA; begin #2; clockA=1; #1; clockA=0; #1; end endtask
task tickB; begin #2; clockB=1; #1; clockB=0; #1; end endtask
task checkA(input state,input [1:0] status); begin
 if(stateA !== state || statusA !== status) $fatal(1,"formal timer A mismatch");
end endtask
task checkB(input state,input [1:0] status); begin
 if(stateB !== state || statusB !== status) $fatal(1,"formal timer B mismatch");
end endtask
initial begin
 tickA; checkA(0,0); tickB;
 // No assertion about resetless B before explicit establishment.
 resetA=0; restartB=1; tickB; checkB(0,1); checkA(0,0);
 restartB=0; restartA=1; tickA; checkA(0,1); checkB(0,1);
 restartA=0; enableA=1; enableB=1;
 tickA; checkA(0,1); checkB(0,1);
 tickA; checkA(0,1); checkB(0,1);
 tickA; checkA(1,2); checkB(0,1);
 tickB; checkB(0,1); enableB=0;
 repeat(4) begin tickB; checkB(0,1); end
 enableB=1; tickB; checkB(0,1); tickB; checkB(1,2);
 resetA=1; #1; checkA(1,2); tickB; checkA(1,2); checkB(1,2);
 tickA; checkA(0,0); checkB(1,2);
 resetB=0; tickB; checkB(1,2); resetB=1; tickB; checkB(1,2);
 restartB=1; enableB=0; tickB; checkB(0,1); checkA(0,0);
 restartB=0; cancelB=1; tickB; checkB(0,3); checkA(0,0);
 cancelB=0; restartB=1; tickB; checkB(0,1);
 restartB=0; enableB=1; tickB; checkB(0,1); tickB; checkB(0,1); tickB; checkB(1,2);
 $display("REUSABLE_FSM_TARGET_PASS required-timed"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def composed_timed_bench(top: str) -> str:
    return f"""module tb;
reg clock=0,reset=1,restart=0,cancel=0,enable=0;
wire [1:0] status,firstStatus,secondStatus;
wire firstActive,secondActive;
{top} dut(.clock(clock),.reset(reset),.restart(restart),.cancel(cancel),.enable(enable),
 .status(status),.firstStatus(firstStatus),.secondStatus(secondStatus),
 .firstActive(firstActive),.secondActive(secondActive));
task tick; begin #2; clock=1; #1; clock=0; #1; end endtask
task check(input [1:0] root,a,b,input active); begin
 if(status !== root || firstStatus !== a || secondStatus !== b ||
    firstActive !== active || secondActive !== active)
  $fatal(1,"composed timer mismatch root=%d children=%d,%d active=%d,%d",
    status,firstStatus,secondStatus,firstActive,secondActive);
end endtask
initial begin
 tick; check(0,0,0,0); reset=0;
 restart=1; tick; check(1,1,1,1); restart=0;
 repeat(3) begin tick; check(1,1,1,1); end
 enable=1; tick; check(1,1,1,1); tick; check(1,2,1,1);
 enable=0; repeat(3) begin tick; check(1,2,1,1); end
 enable=1; tick; check(1,2,2,1); tick; check(2,2,2,0);
 repeat(2) begin tick; check(2,2,2,0); end
 restart=1; enable=0; tick; check(1,1,1,1);
 cancel=1; tick; check(3,3,3,0);
 cancel=0; tick; check(1,1,1,1); restart=0; enable=1;
 tick; check(1,1,1,1); tick; check(1,2,1,1);
 tick; check(1,2,2,1); tick; check(2,2,2,0);
 reset=1; #1; check(2,2,2,0); tick; check(0,0,0,0);
 $display("REUSABLE_FSM_TARGET_PASS composed-timed"); $finish;
end
initial begin #1000; $fatal(1,"timeout"); end
endmodule
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--profile", choices=(
        "legacy", "controlled", "terminal", "timed", "timed-one", "timed-transfer",
        "required", "required-material", "required-forwarded", "required-timed",
        "composed-all", "composed-any", "composed-priority", "composed-nested", "composed-timed",
    ), default="legacy")
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must stay under .work")
    for name in ("input", "nodalc", "firtool", "iverilog", "vvp"):
        path = getattr(args, name).resolve()
        if not path.is_file():
            parser.error(f"{name} does not exist: {path}")
        setattr(args, name, path)
    args.out.mkdir(parents=True, exist_ok=True)
    args.out = args.out.resolve()
    source = args.input.read_text()
    required = args.profile.startswith("required")
    composed = args.profile.startswith("composed-")
    expected_machines = (4 if args.profile == "composed-nested" else 3) if composed else (1 if args.profile == "terminal" or required else 2)
    if source.count('"nodal.flat_fsm"') != expected_machines:
        raise RuntimeError(f"expected {expected_machines} actual Scala machine definitions")
    if source.count('illegal_policy = "recover_to_initial"') != expected_machines:
        raise RuntimeError("Icarus replay requires the explicit recovery-policy Scala witness; "
                           "Error-policy assertions must not be disabled")
    manifest = {
        "status": "running",
        "profile": args.profile,
        "source_sha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
        "tools": {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest()
                  for name in ("nodalc", "firtool", "iverilog", "vvp")},
    }

    def save() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str]) -> str:
        (args.out / f"{label}.command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / f"{label}.log").write_text(result.stdout)
        if result.returncode:
            manifest["status"] = "failed"
            manifest["failed_step"] = label
            save()
            raise RuntimeError(f"{label} exited {result.returncode}\n{result.stdout[-8000:]}")
        return result.stdout

    save()
    gated = args.out / "reusable.gated.mlir"
    circt = args.out / "reusable.circt.mlir"
    sv = args.out / "reusable.sv"
    run("gate", [str(args.nodalc), str(args.input), "--nodal-gate-default",
                 "--mlir-print-debuginfo", "-o", str(gated)])
    run("lower", [str(args.nodalc), str(gated), "--nodal-sequential-to-circt",
                  "--mlir-print-debuginfo", "-o", str(circt)])
    run("export", [str(args.firtool), str(circt), "--format=mlir", "--verilog",
                   "--verify-each", "--disable-all-randomization",
                   "--lowering-options=disallowLocalVariables", "-o", str(sv)])
    design = sv.read_text()
    modules = re.findall(r"\bmodule\s+([A-Za-z_]\w*)\s*\(", design)
    if required:
        root = re.search(r'nodal\.root\.module\s*=\s*@(?:"([^"]+)"|([A-Za-z_]\w*))', source)
        if root is None:
            raise RuntimeError("expected an actual rooted required-domain Scala hierarchy")
        top = root.group(1) or root.group(2)
        expected_modules = 4 if args.profile == "required-forwarded" else 3
        if top not in modules or len(modules) != expected_modules:
            raise RuntimeError(f"expected {expected_modules} real reusable modules including "
                               f"{top}, got {modules}")
        if not re.search(r"\bhw\.instance\b", circt.read_text()):
            raise RuntimeError("required-domain replay must retain real hierarchical instances")
        if "__nodal_required_clock" not in design:
            raise RuntimeError("required-domain replay has no inferred child clock ports")
        bench = required_timed_bench(top) if args.profile == "required-timed" else required_bench(top, args.profile)
    elif len(modules) != 1:
        raise RuntimeError(f"expected one generated module, got {modules}")
    else:
        top = modules[0]
    manifest["top"] = top
    manifest["modules"] = modules
    if args.profile.startswith("timed"):
        bench = timed_bench(top, args.profile)
    elif args.profile == "composed-timed":
        bench = composed_timed_bench(top)
    elif composed:
        bench = composed_bench(top, args.profile)
    elif args.profile == "controlled":
        bench = controlled_bench(top)
    elif args.profile == "terminal":
        bench = terminal_bench(top)
    elif not required:
        state_registers = re.findall(r"\breg\s+\[1:0\]\s+(register_\w+)\s*;", design)
        if len(state_registers) != 1:
            raise RuntimeError("expected exactly one compact private state register")
        compact_state = state_registers[0]
        bench = f"""module tb;
    reg clock=0, reset=1, requestA=0, requestB=0, finish=0;
    reg [7:0] data=0;
    wire doneA, doneB;
    wire [1:0] stateA, stateB;
    wire [7:0] valueA, valueB;
    {top} dut(.clock(clock), .reset(reset), .requestA(requestA),
      .requestB(requestB), .finish(finish), .input_0(data), .doneA(doneA), .doneB(doneB),
      .stateA(stateA), .stateB(stateB), .valueA(valueA), .valueB(valueB));
    task tick; begin #2; clock=1; #1; clock=0; #1; end endtask
    task check(input [1:0] a, input [1:0] b, input [7:0] x, input [7:0] y); begin
      if(stateA !== a || stateB !== b || valueA !== x || valueB !== y)
        $fatal(1,"state/value mismatch: %d %d %d %d",stateA,stateB,valueA,valueB);
      if(doneA !== (a==2) || doneB !== (b==2))
        $fatal(1,"typed completion does not match terminal membership");
    end endtask
    initial begin
      tick; check(0,0,0,0); reset=0;
      requestA=1; tick; check(1,0,4,0);
      requestA=0; requestB=1; data=17; tick; check(1,1,17,4);
      requestB=0; finish=1; data=29; tick; check(2,2,29,29);
      finish=0; data=44; tick; check(2,2,29,29);
      force dut.{compact_state}=2'b11; #1;
      if(doneA !== 0 || stateA !== 0) $fatal(1,"invalid state must not report completion");
      release dut.{compact_state}; tick; check(0,2,29,29);
      reset=1; #1; check(0,2,29,29); tick; check(0,0,0,0);
      $display("REUSABLE_FSM_TARGET_PASS"); $finish;
    end
    initial begin #1000; $fatal(1,"timeout"); end
    endmodule
    """
    tb = args.out / "tb.sv"
    tb.write_text(bench)
    binary = args.out / "simulation"
    run("compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o", str(binary),
                    str(sv), str(tb)])
    output = run("simulate", [str(args.vvp), str(binary)])
    if "REUSABLE_FSM_TARGET_PASS" not in output or re.search(r"ERROR|FATAL", output):
        manifest["status"] = "failed"
        manifest["failed_step"] = "runtime-checks"
        save()
        raise RuntimeError("generated target did not satisfy the runtime checks\n" + output)
    manifest["status"] = "passed"
    manifest["target_sha256"] = hashlib.sha256(sv.read_bytes()).hexdigest()
    save()
    print(f"REUSABLE_FSM_TARGET_PASS {args.out}")


if __name__ == "__main__":
    main()
