#!/usr/bin/env python3
"""Execute public Scala owned-clock replay through CIRCT and Icarus."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import subprocess


@dataclass(frozen=True)
class Profile:
    name: str
    gate: bool
    rising: bool = True
    high: bool = True
    resetless: bool = False
    nested: bool = False
    shaped: bool = False

    @property
    def prefix(self) -> str:
        return f"__nodal_clock_{2 if self.nested else 0}_"


def profiles() -> list[Profile]:
    result = [Profile(f"{kind}_{edge}_{polarity}", kind == "gate", edge == "Rising",
                      polarity == "ActiveHigh")
              for kind in ("gate", "mux") for edge in ("Rising", "Falling")
              for polarity in ("ActiveHigh", "ActiveLow")]
    result += [Profile(f"gate_resetless_{edge}", True, edge == "Rising", resetless=True)
               for edge in ("Rising", "Falling")]
    result += [Profile("mux_nested", False, nested=True),
               Profile("mux_shaped", False, shaped=True)]
    return result


def modes(profile: Profile) -> tuple[str, ...]:
    if profile.gate:
        result = ("legal", "enable_unknown")
        if not profile.resetless:
            result += ("test_unknown",)
        if profile.name == "gate_Rising_ActiveHigh":
            result += ("unknown_reset",)
        return result
    result = ("legal", "select_unknown")
    if profile.name == "mux_Rising_ActiveHigh":
        result += ("missing_epoch", "unknown_reset", "controller_unknown",
                   "branch0_unknown", "branch1_unknown",
                   "branch0_unknown_reset", "branch1_unknown_reset")
    return result


def monitor(profile: Profile) -> str:
    """Check physical edges, not a replacement HDL implementation of the DUT."""
    mux_wires = (f"wire branch0 = dut.{profile.prefix}branch0_enable;\n"
                 f"wire branch1 = dut.{profile.prefix}branch1_enable;" if not profile.gate else
                 "wire branch0 = 1'b1;\nwire branch1 = 1'b0;")
    return f'''
wire outputClock = dut.{profile.prefix}output;
{mux_wires}
reg watching=0, pulseOpen=0;
integer pulseOwner=-1, pulseCount0=0, pulseCount1=0, resetCuts=0;
time activeAt0=0, inactiveAt0=0, activeAt1=0, inactiveAt1=0;
time outputEvent=0, previousOutputEvent=0;
reg eventOutput=1'bx, haveOutputEvent=0;
// This separate, delay-free observer also catches active-idle-active delta glitches
// that could otherwise disappear before the settled-state monitor resumes.
always @(outputClock) begin
  if(watching) begin
    if(haveOutputEvent && previousOutputEvent==$time &&
       (IS_GATE || reset !== ASSERTED))
      $fatal(1,"WAVEFORM: multiple output transitions in one parent-edge instant");
    previousOutputEvent=$time; haveOutputEvent=1;
  end
end
// The public parent is also the actual mux parent for nested gates while enabled.
// Nested profiles keep their gate controls enabled throughout these measurements.
always @(srcClock) begin
  if(srcClock === ACTIVE) activeAt0=$time;
  else if(srcClock === IDLE) inactiveAt0=$time;
end
always @(alternateClock) begin
  if(alternateClock === ACTIVE) activeAt1=$time;
  else if(alternateClock === IDLE) inactiveAt1=$time;
end
always @(branch0 or branch1) begin
  if(watching && !IS_GATE && branch0 === 1'b1 && branch1 === 1'b1)
    $fatal(1,"WAVEFORM: transient actual mux branch enables overlap");
end
always @(branch0 or branch1 or srcClock or alternateClock) begin
  #0.001;
  if(watching && !IS_GATE) begin
    if(branch0 === 1'b1 && branch1 === 1'b1)
      $fatal(1,"WAVEFORM: actual mux branch enables overlap");
    if(reset !== ASSERTED) begin
      if((branch0 !== 0 && branch0 !== 1) || (branch1 !== 0 && branch1 !== 1))
        $fatal(1,"WAVEFORM: unknown actual enable outside coordinated reset");
      if(outputClock !== (((branch0 && srcClock==ACTIVE) ||
                          (branch1 && alternateClock==ACTIVE)) ? ACTIVE : IDLE))
        $fatal(1,"WAVEFORM: output lost an enabled parent clock phase");
    end
  end
end
always @(outputClock) begin
  outputEvent=$time; eventOutput=outputClock;
  #0.001; // Observe settled generated combinational/NBA state, not scheduler order.
  if(watching) begin
    if(!IS_GATE && reset === ASSERTED) begin
      // Only coordinated assertion can legally truncate an active output pulse.
      if(pulseOpen && outputClock === IDLE) resetCuts=resetCuts+1;
      pulseOpen=0;
    end else begin
      if(outputClock !== eventOutput)
        $fatal(1,"WAVEFORM: output pulse disappeared before settled observation");
      if(outputClock !== ACTIVE && outputClock !== IDLE)
        $fatal(1,"WAVEFORM: output clock became unknown outside coordinated reset");
      if(outputClock === ACTIVE) begin
        if(pulseOpen) $fatal(1,"WAVEFORM: duplicate active output edge");
        if(IS_GATE || branch0 === 1'b1) begin
          if(srcClock !== ACTIVE || activeAt0 != outputEvent)
            $fatal(1,"WAVEFORM: output active edge did not coincide with first parent");
          pulseOwner=0; pulseCount0=pulseCount0+1;
        end else if(branch1 === 1'b1) begin
          if(alternateClock !== ACTIVE || activeAt1 != outputEvent)
            $fatal(1,"WAVEFORM: output active edge did not coincide with second parent");
          pulseOwner=1; pulseCount1=pulseCount1+1;
        end else $fatal(1,"WAVEFORM: mux output pulse without an actual enabled branch");
        pulseOpen=1;
      end else if(pulseOpen) begin
        if((pulseOwner==0 && (srcClock !== IDLE || inactiveAt0 != outputEvent)) ||
           (pulseOwner==1 && (alternateClock !== IDLE || inactiveAt1 != outputEvent)))
          $fatal(1,"WAVEFORM: runt pulse ended before its parent inactive edge");
        pulseOpen=0;
      end
    end
  end
end
'''


def gate_stimulus(profile: Profile) -> str:
    start = ("#1; srcClock=IDLE; #2;" if profile.resetless else
             "#1; reset=ASSERTED; srcClock=IDLE; #2;")
    release = "" if profile.resetless else "reset=RELEASED;"
    reset_pulse = "" if profile.resetless else '''
  // A gate does not mask or truncate clocks on reset, unlike a mux's reset exception.
  enable=1; #2; srcClock=ACTIVE; #2; reset=ASSERTED; #2;
  check_clock(ACTIVE); if(done !== 0) $fatal(1,"gate consumer did not assert reset");
  srcClock=IDLE; #2; reset=RELEASED; repeat(6) src_tick;
  if(done !== 1) $fatal(1,"gate consumer failed independent output-clock recovery");
'''
    return f'''
  {start}
  check_clock(IDLE); watching=1; {release}
  // A complete inactive phase, with known controls, defines resetless startup too.
  enable=0; testEnable=0; #2; src_tick; check_clock(IDLE);
  enable=1; #2; srcClock=ACTIVE; #2; check_clock(ACTIVE);
  enable=0; #2; check_clock(ACTIVE); // Active-phase disable cannot cut this pulse.
  srcClock=IDLE; #2; src_tick; check_clock(IDLE);
  srcClock=ACTIVE; #2; enable=1; #2; check_clock(IDLE);
  srcClock=IDLE; #2; src_tick; // Active-phase enable waits for an inactive phase.
  enable=0; testEnable=1; #2; srcClock=ACTIVE; #2; check_clock(ACTIVE);
  testEnable=0; #2; check_clock(ACTIVE);
  srcClock=IDLE; #2; src_tick; check_clock(IDLE);
  srcClock=ACTIVE; #2; testEnable=1; #2; check_clock(IDLE);
  srcClock=IDLE; #2; src_tick;
  testEnable=0; enable=1; #2; repeat(6) src_tick;
  if(done !== 1) $fatal(1,"gate output consumer did not receive valid output edges");
  {reset_pulse}
  if(pulseCount0 < 10) $fatal(1,"insufficient gate pulse witnesses");
'''


def mux_stimulus() -> str:
    return '''
  #1; reset=ASSERTED; #2; run_for(12);
  check_clock(IDLE); if(done !== 0) $fatal(1,"mux consumer not reset");
  watching=1;
  // A stopped first parent cannot advance the select sampler/controller.
  park0(IDLE); reset=RELEASED; run_for(48);
  check_branches(0,0); check_clock(IDLE);
  if(done !== 0) $fatal(1,"output reset released without any output clock");
  run0=1; wait_branch(0); run_for(24);
  if(done !== 1) $fatal(1,"mux output-domain reset did not independently release");
  // Delay the candidate's inactive edges, then reverse select in WAIT_ON.
  // The issued request must complete before the controller may request another.
  park1(IDLE); select=1; wait_off(0); run_for(80);
  check_branches(0,0); check_clock(IDLE);
  select=0; run_for(80); check_branches(0,0);
  run1=1; wait_branch(1); wait_branch(0); run_for(24);
  // Both directions under unequal clocks include simultaneous input edges.
  select=1; wait_branch(1); run_for(36);
  // Stopping the controller while branch 1 runs must prevent a new handover.
  park0(IDLE); select=0; run_for(48); check_branches(0,1);
  run0=1; wait_branch(0); run_for(24);
  select=1; wait_branch(1); run_for(24);
  // A selected clock stopped active holds its full pulse, even after selection moves.
  park1(ACTIVE); select=0; run_for(120);
  check_branches(0,1); check_clock(ACTIVE);
  run1=1; wait_branch(0); run_for(24);
  // A real coordinated reset assertion is the sole permitted pulse truncation.
  park0(ACTIVE); check_clock(ACTIVE); reset=ASSERTED; #2;
  check_clock(IDLE); check_branches(0,0);
  if(done !== 0 || resetCuts != 1) $fatal(1,"missing protected reset-time pulse cut");
  run0=1; run_for(24); reset=RELEASED; wait_branch(0); run_for(24);
  if(done !== 1) $fatal(1,"mux output did not recover after common assertion");
  if(pulseCount0 < 10 || pulseCount1 < 10)
    $fatal(1,"insufficient mux pulse witnesses from both parents");
'''


def negative_stimulus(profile: Profile, mode: str) -> tuple[str, str]:
    checker = "gate enable/testEnable" if profile.gate else "mux controller active-edge controls"
    if mode == "missing_epoch":
        return '$display("INJECT_EXPECTED_ASSERTION"); src_tick; #2; $fatal(1,"MISSING_EXPECTED_ASSERTION");', checker
    warm = ("#1; enable=1; #2; repeat(6) src_tick;" if profile.resetless else
            "#1; reset=ASSERTED; #2; run_for(24); reset=RELEASED; run_for(160);")
    # Every mutation happens after a genuine good reset/recovery, and away from edges.
    warm += "\n  run0=0; run1=0; srcClock=IDLE; alternateClock=IDLE; #2;\n"
    if mode.startswith("branch"):
        branch = int(mode[6])
        clock = "srcClock" if branch == 0 else "alternateClock"
        checker = f"branch{branch} inactive-edge controls"
        mutation = f"force dut.{profile.prefix}branch{branch}_request_0 = 1'bx;"
        unknown = "reset=1'bx;" if mode.endswith("unknown_reset") else ""
        body = f"{clock}=ACTIVE; #2; {unknown} {mutation} #2; {clock}=IDLE; #2;"
    else:
        mutations = {"enable_unknown": "enable=1'bx;", "test_unknown": "enable=1; testEnable=1'bx;",
                     "select_unknown": "select=1'bx;",
                     "controller_unknown": f"force dut.{profile.prefix}controller = 3'bxxx;",
                     "unknown_reset": "reset=1'bx; " +
                     ("enable=1'bx;" if profile.gate else "select=1'bx;")}
        body = mutations[mode] + " #2; src_tick; #2;"
    return warm + '$display("INJECT_EXPECTED_ASSERTION");' + body + '$fatal(1,"MISSING_EXPECTED_ASSERTION");', checker


def testbench(profile: Profile, module: str, mode: str) -> tuple[str, str | None]:
    idle, active = (0, 1) if profile.rising else (1, 0)
    asserted, released = (1, 0) if profile.high else (0, 1)
    if profile.shaped:
        connections = ".clocks({alternateClock,srcClock}), .resets({reset,1'b0}), .select(select), .done(done)"
    else:
        ports = ("srcClock", "alternateClock", "reset", "enable", "testEnable", "select", "done")
        connections = ", ".join(f".{port}({port})" for port in ports)
    expected = None
    if mode == "legal":
        body = gate_stimulus(profile) if profile.gate else mux_stimulus()
    else:
        body, expected = negative_stimulus(profile, mode)
    return f'''`timescale 1ns/1ps
module tb;
localparam IDLE=1'b{idle}, ACTIVE=1'b{active}, ASSERTED=1'b{asserted}, RELEASED=1'b{released};
localparam IS_GATE={int(profile.gate)};
reg srcClock={"1'bx" if profile.gate and mode == "legal" else "IDLE"}, alternateClock=IDLE, reset=RELEASED;
reg enable={int(not profile.gate)}, testEnable=0, select=0;
wire done;
{module} dut({connections});
reg run0=1, run1=1;
integer tickCount=0;
{monitor(profile)}
task check_clock(input expected); begin
  if(outputClock !== expected) $fatal(1,"wrong physical clock level: expected %b got %b",expected,outputClock);
end endtask
task check_branches(input first, input second); begin
  if(branch0 !== first || branch1 !== second)
    $fatal(1,"wrong actual mux enables: expected %b%b got %b%b",first,second,branch0,branch1);
end endtask
task src_tick; begin
  #2; srcClock=ACTIVE; #2;
  if(watching && IS_GATE) check_clock((enable || testEnable) ? ACTIVE : IDLE);
  srcClock=IDLE; #2;
  if(watching && IS_GATE) check_clock(IDLE);
end endtask
task run_for(input integer ticks); integer n; begin
  for(n=0;n<ticks;n=n+1) begin
    #1; tickCount=tickCount+1;
    if(run0 && tickCount%2==0) srcClock=~srcClock;
    if(run1 && tickCount%3==0) alternateClock=~alternateClock;
    #1;
  end
end endtask
task park0(input level); integer n; begin
  n=0; while(srcClock !== level && n<12) begin run_for(1); n=n+1; end
  if(srcClock !== level) $fatal(1,"could not park first parent");
  run0=0;
end endtask
task park1(input level); integer n; begin
  n=0; while(alternateClock !== level && n<12) begin run_for(1); n=n+1; end
  if(alternateClock !== level) $fatal(1,"could not park second parent");
  run1=0;
end endtask
task wait_branch(input integer branch); integer n; begin
  n=0;
  while((branch==0 ? branch0 : branch1) !== 1'b1 && n<320) begin run_for(1); n=n+1; end
  if(n==320) $fatal(1,"bounded mux liveness failed for branch %0d",branch);
  check_branches(branch==0,branch==1);
end endtask
task wait_off(input integer branch); integer n; begin
  n=0;
  while((branch==0 ? branch0 : branch1) !== 1'b0 && n<320) begin run_for(1); n=n+1; end
  if(n==320) $fatal(1,"bounded mux turn-off failed for branch %0d",branch);
end endtask
initial begin
{body}
  $display("PASS {profile.name}-{mode}: pulses=%0d/%0d reset_cuts=%0d",pulseCount0,pulseCount1,resetCuts);
  $finish;
end
initial begin #20000; $fatal(1,"TESTBENCH_TIMEOUT"); end
endmodule
''', expected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--circt-opt", type=Path, help="Defaults to circt-opt beside firtool.")
    parser.add_argument("--profile", action="append", help="Run only this named actual replay profile (repeatable).")
    args = parser.parse_args()
    if ".work" not in args.out.resolve().parts:
        parser.error("generated outputs must remain under ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    circt_opt = args.circt_opt or args.firtool.with_name("circt-opt")
    selected = profiles()
    if args.profile:
        unknown = set(args.profile) - {profile.name for profile in selected}
        if unknown:
            parser.error(f"unknown profiles: {sorted(unknown)}")
        selected = [profile for profile in selected if profile.name in args.profile]
    manifest: dict = {"status": "running", "harness_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      "profiles": [profile.name for profile in selected], "cases": []}
    try:
        manifest["source_revision"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True).strip()
    except subprocess.CalledProcessError:
        manifest["source_revision"] = "unavailable"

    def save_manifest() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str], expected_assertion: str | None = None) -> str:
        (args.out / (label + ".command.json")).write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if expected_assertion:
            marker = "NODAL-CLOCK-057: " + expected_assertion
            if result.returncode == 0 or marker not in result.stdout:
                raise RuntimeError(f"{label}: expected executed '{marker}' assertion; "
                                   f"got exit {result.returncode}:\n{result.stdout[-8000:]}")
            if ("INJECT_EXPECTED_ASSERTION" not in result.stdout or
                    result.stdout.index(marker) < result.stdout.index("INJECT_EXPECTED_ASSERTION")):
                raise RuntimeError(f"{label}: checker failed before the intended mutation/startup witness")
            if "MISSING_EXPECTED_ASSERTION" in result.stdout or "TESTBENCH_TIMEOUT" in result.stdout:
                raise RuntimeError(f"{label}: fallback failure is not a generated assertion pass")
        elif result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    save_manifest()
    try:
        for profile in selected:
            name = profile.name
            source = args.input / (name + ".mlir")
            if not source.is_file():
                raise RuntimeError(f"missing actual Scala replay: {source}")
            circt = args.out / (name + ".circt.mlir")
            sv_ir = args.out / (name + ".sv.mlir")
            sv = args.out / (name + ".sv")
            run(name + "-lower", [str(args.nodalc), str(source), "--nodal-sequential-to-circt",
                                  "--mlir-print-debuginfo", "-o", str(circt)])
            # The standard Seq-to-SV pass emits a real latch. firtool's default
            # clock-gate specialization instead expects an external EICG_wrapper.
            run(name + "-lower-seq", [str(circt_opt), str(circt),
                                      "--lower-seq-to-sv=disable-reg-randomization=true",
                                      "--mlir-print-debuginfo", "-o", str(sv_ir)])
            run(name + "-export", [str(args.firtool), str(sv_ir), "--format=mlir", "--verilog",
                                   "--verify-each", "--disable-all-randomization",
                                   "--lowering-options=disallowLocalVariables", "-o", str(sv)])
            design = sv.read_text()
            module = "Increment57ShapedOwnedClockSource" if profile.shaped else "Increment57OwnedClockSource"
            if not re.search(r"\bmodule\s+" + module + r"\s*\(", design):
                raise RuntimeError(f"{name}: actual public Scala module {module} absent")
            required = [profile.prefix + "output"]
            if not profile.gate:
                required += [profile.prefix + "branch0_enable", profile.prefix + "branch1_enable"]
            for wire in required:
                if not re.search(r"\b" + wire + r"\b", design):
                    raise RuntimeError(f"{name}: required actual physical clock/control wire {wire} missing")
            obligations = (("gate_v1", "inactive_phase_aperture", "known_after_inactive_phase")
                           if profile.gate else ("glitchless_mux_v1", "level_coalescing",
                           "serialized_break_before_make", "common_assertion", "except_reset_assertion"))
            for obligation in ("// NODAL owned clock ", "parents=[@", "domain=@",
                               "edge=" + ("rising" if profile.rising else "falling"),
                               "core/scala/testkit/test/src/nodal_external/Increment57OwnedClockSource.scala",
                               *obligations):
                if obligation not in design:
                    raise RuntimeError(f"{name}: target lost source identity/obligation {obligation}")
            for mode in modes(profile):
                label = name + "-" + mode
                bench, expected = testbench(profile, module, mode)
                bench_path = args.out / (label + "-tb.sv")
                bench_path.write_text(bench)
                executable = args.out / (label + ".vvp")
                run(label + "-compile", [str(args.iverilog), "-g2012", "-s", "tb", "-o",
                                         str(executable), str(sv), str(bench_path)])
                output = run(label + "-simulate", [str(args.vvp), str(executable)], expected)
                if not expected and f"PASS {label}" not in output:
                    raise RuntimeError(f"{label}: simulation did not reach success")
                manifest["cases"].append({"name": label, "status": "passed",
                                          "expected_assertion": expected,
                                          "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                                          "target_sha256": hashlib.sha256(sv.read_bytes()).hexdigest(),
                                          "testbench_sha256": hashlib.sha256(bench.encode()).hexdigest()})
                save_manifest()
                print(f"PASS {label}", flush=True)
        manifest["status"] = "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        save_manifest()


if __name__ == "__main__":
    main()
