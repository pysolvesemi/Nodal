#!/usr/bin/env python3
"""Check actual Scala relationship targets through official CIRCT and Icarus."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[3]
FIXTURE = "core/scala/testkit/test/src/nodal_external/Increment57RelationshipSource.scala"


@dataclass(frozen=True)
class Profile:
    name: str
    kind: str = "same"
    rising: bool = True
    high: bool = True

    @property
    def structural(self) -> bool:
        return self.name == "structural"

    @property
    def nested(self) -> bool:
        return self.name == "nested"

    @property
    def shaped(self) -> bool:
        return self.name == "shaped"

    @property
    def module(self) -> str:
        if self.kind != "same":
            return "Increment57RelationshipCategorySource"
        return "Increment57ShapedRelationshipSource" if self.shaped else "Increment57RelationshipSource"


def profiles() -> list[Profile]:
    return ([Profile(f"external_{edge}_{polarity}", rising=edge == "Rising",
                     high=polarity == "ActiveHigh")
             for edge in ("Rising", "Falling") for polarity in ("ActiveHigh", "ActiveLow")]
            + [Profile(name) for name in ("structural", "nested", "shaped")]
            + [Profile(name, kind) for name, kind in
               (("ratio", "ratio"), ("synchronous", "synchronous"),
                ("exclusive", "mutually_exclusive"), ("asynchronous", "asynchronous"),
                ("unknown", "unknown"))])


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_target(profile: Profile, design: str) -> dict:
    """Inspect retained identities and state, never repair or rewrite the target."""
    if not re.search(r"\bmodule\s+" + profile.module + r"\s*\(", design):
        raise RuntimeError(f"{profile.name}: actual public Scala module absent")
    comments = [line.strip() for line in design.splitlines()
                if "// NODAL clock relationship " in line]
    expected_count = 2 if profile.nested else 1
    if len(comments) != expected_count:
        raise RuntimeError(f"{profile.name}: expected {expected_count} relationship comments")
    proof, obligations = {
        "same": ("structural" if profile.structural else "external_contract",
                 () if profile.structural else ("active_edges", '"identical_stream"')),
        "ratio": ("timing_contract", ("exact_relative_period_phase", "multiply=6", "divide=4",
                                        'phase="-270.0"')),
        "synchronous": ("timing_contract", ("synchronous_phase_knowledge_retained", "phase_known=true")),
        "mutually_exclusive": ("operating_mode_contract", ("never_simultaneously_enabled",)),
        "asynchronous": ("classification", ()),
        "unknown": ("classification", ()),
    }[profile.kind]
    for comment in comments:
        for required in (f"kind={profile.kind}", f"proof={proof}", "source=@", "destination=@",
                         ".relationship:", "obligations=", FIXTURE,
                         "retained environment assumptions, not runtime physical certification",
                         *obligations):
            if required not in comment:
                raise RuntimeError(f"{profile.name}: target lost relationship fact {required}")
    # Relationship declarations never synthesize a clock producer or an assertion
    # pretending to certify the environment's timing/exclusion promise.
    uncommented = re.sub(r"//[^\n]*", "", design)
    header = re.search(r"\bmodule\s+" + profile.module + r"\s*\((.*?)\);", uncommented, flags=re.S)
    ports = {re.findall(r"[A-Za-z_]\w*", field)[-1] for field in header[1].split(",")} if header else set()
    if profile.kind != "same":
        expected_ports = {"srcClock", "dstClock", "reset", "source", "ownBit"}
    elif profile.shaped:
        expected_ports = {"clocks", "resets", "rootOut", "childOut"}
    else:
        expected_ports = {"srcClock", "dstClock", "thirdClock", "reset", "rootOut", "childOut", "mixedOut"}
        if profile.nested:
            expected_ports.add("thirdOut")
    if ports != expected_ports:
        raise RuntimeError(f"{profile.name}: target physical interface differs from actual source: {ports}")
    if re.search(r"__nodal_clock_|\balways_latch\b|\bassert\s*\(", uncommented):
        raise RuntimeError(f"{profile.name}: relationship introduced clock hardware or a runtime checker")
    aliases = dict(re.findall(r"\bwire\s+(?:\[[^\]]+\]\s+)?(\w+)\s*=\s*([^;]+);", uncommented))
    aliases.update(re.findall(r"\bassign\s+(\w+)\s*=\s*([^;]+);", uncommented))

    def physical_signal(expression: str) -> tuple[str, bool]:
        # Follow only genuine target wire aliases/inverters. Never equate two
        # physical pins or accept an arbitrary expression as a physical clock.
        inverted, visited = False, set()
        while True:
            expression = expression.strip().strip("()").strip()
            if expression.startswith("~"):
                inverted = not inverted
                expression = expression[1:]
                continue
            if expression not in aliases:
                return expression, inverted
            if expression in visited:
                raise RuntimeError(f"{profile.name}: cyclic target wire alias {expression}")
            visited.add(expression)
            expression = aliases[expression]

    if profile.shaped:
        physical = ("clocks[0]", "clocks[1]")
    else:
        physical = ("srcClock", "srcClock" if profile.structural else "dstClock")
    expected_operands = [physical] + ([("dstClock", "thirdClock")] if profile.nested else [])
    for index, (comment, operands) in enumerate(zip(comments, expected_operands)):
        clock_fields = re.search(r"; actual clocks (\S+) -> (\S+)", comment)
        actual = [physical_signal(value) for value in clock_fields.groups()] if clock_fields else []
        if actual != [(clock, False) for clock in operands]:
            raise RuntimeError(f"{profile.name}: target lost actual physical relationship clocks: {actual}")
        source, destination = ("root", "child") if index == 0 else ("child", "third")
        if f"source=@{source};" not in comment or f"destination=@{destination};" not in comment:
            raise RuntimeError(f"{profile.name}: target lost exact relationship domain references")
    result = {"comments": comments, "physical_clocks": list(physical), "ports": sorted(ports)}
    if profile.kind != "same":
        return result

    domains = 3 if profile.nested else 2
    expected_regs = {f"__nodal_reset_{domain}_{stage}" for domain in range(domains) for stage in range(2)}
    # Scala's actual semantic names retain the outer captured-value aliases.
    expected_regs |= {"rootValue", "childState" if profile.shaped else "childValue"}
    if not profile.shaped:
        expected_regs.add("mixedState")
    if profile.nested:
        expected_regs.add("thirdState")
    actual_regs = set()
    for declaration in re.findall(r"\breg\s+(?:\[[^\]]+\]\s+)?([^;]+);", uncommented):
        actual_regs.update(name.strip() for name in declaration.split(","))
    if actual_regs != expected_regs:
        raise RuntimeError(f"{profile.name}: actual state differs from source/reset state: "
                           f"missing={sorted(expected_regs - actual_regs)}, "
                           f"extra={sorted(actual_regs - expected_regs)}")
    # Inspect the actual sequential blocks as well as observing their outputs.
    # Same permission must not replace a child's physical clock with its parent.
    blocks = re.findall(r"always(?:_ff)?\s*@\(([^)]*)\)(.*?)(?=\balways(?:_ff)?\s*@|`ifdef|\bassign\b|\bendmodule\b)",
                        uncommented, flags=re.S)
    clocks = [physical[0], physical[1]] + (["thirdClock"] if profile.nested else [])
    state_domains = {"rootValue": 0, "childState": 1, "childValue": 1, "mixedState": 1, "thirdState": 2}
    for register in sorted(expected_regs):
        domain = int(register.split("_")[-2]) if register.startswith("__nodal_reset_") else state_domains[register]
        owners = [sensitivity for sensitivity, body in blocks
                  if re.search(r"\b" + register + r"\s*<=", body)]
        events = re.findall(r"\b(posedge|negedge)\s+(\S+)", owners[0]) if len(owners) == 1 else []
        clock = physical_signal(events[0][1]) if events else ("", False)
        if len(events) != 2 or clock != (clocks[domain], (events[0][0] == "posedge") != profile.rising):
            raise RuntimeError(f"{profile.name}: {register} lost actual domain clock {clocks[domain]}: {owners}")
        reset = physical_signal(events[1][1])
        if register.startswith("__nodal_reset_"):
            expected_reset = "resets[1]" if profile.shaped else "reset"
            expected_edge = profile.high
        else:
            expected_reset, expected_edge = f"__nodal_reset_{domain}_1", True
        if reset != (expected_reset, (events[1][0] == "posedge") != expected_edge):
            raise RuntimeError(f"{profile.name}: {register} lost its own actual reset {expected_reset}: {owners}")
    result["registers"] = sorted(actual_regs)
    result["reset_chains"] = domains
    return result


def testbench(profile: Profile, vcd: Path) -> str:
    idle, active = (0, 1) if profile.rising else (1, 0)
    asserted, released = (1, 0) if profile.high else (0, 1)
    if profile.shaped:
        ports = ".clocks({dstClock,srcClock}), .resets({reset,unusedReset}), .rootOut(rootOut), .childOut(childOut)"
    else:
        names = ["srcClock", "dstClock", "thirdClock", "reset", "rootOut", "childOut", "mixedOut"]
        if profile.nested:
            names.append("thirdOut")
        ports = ", ".join(f".{name}({name})" for name in names)
    domains = 3 if profile.nested else 2
    checks = "\n".join(
        f'''  if(dut.__nodal_reset_{domain}_0 !== stage0 || dut.__nodal_reset_{domain}_1 !== stage1)
    $fatal(1,"RESET: domain {domain} independently retained release stages differ at edge %0d",edges);'''
        for domain in range(domains))
    if not profile.shaped:
        checks += '''
  if(mixedOut !== expectedMixed) $fatal(1,"SAMPLE: combined Same roots did not use old state");'''
    if profile.nested:
        checks += '''
  if(thirdOut !== expectedMixed) $fatal(1,"SAMPLE: transitive Same did not sample old root/child");'''
    return f'''`timescale 1ns/1ps
module tb;
localparam IDLE=1'b{idle}, ACTIVE=1'b{active}, ASSERTED=1'b{asserted}, RELEASED=1'b{released};
reg srcClock=IDLE, dstClock=IDLE, thirdClock=IDLE, reset=RELEASED, unusedReset=0;
wire rootOut, childOut, mixedOut, thirdOut;
{profile.module} dut({ports});
reg expectedRoot=0, expectedChild=0, expectedMixed=0, stage0=1, stage1=1;
reg oldRoot, oldChild, oldStage0, oldStage1;
integer edges=0, operatingEdges=0, assertions=0, observations=0, mixedHigh=0, mixedLow=0;
task check_observed; begin
  observations=observations+1;
  if(rootOut !== expectedRoot || childOut !== expectedChild)
    $fatal(1,"SAMPLE: edge %0d expected root/child %b%b, got %b%b",edges,
           expectedRoot,expectedChild,rootOut,childOut);
{checks}
end endtask
task assert_reset; begin
  reset=ASSERTED; expectedRoot=0; expectedChild=0; expectedMixed=0; stage0=1; stage1=1;
  assertions=assertions+1;
  #0.001; check_observed;
end endtask
task active_edge; begin
  // Every declared Same domain sees the same active-edge instant. This model
  // predicts observable source semantics, not another implementation of the DUT.
  oldRoot=expectedRoot; oldChild=expectedChild; oldStage0=stage0; oldStage1=stage1;
  srcClock=ACTIVE; dstClock=ACTIVE; thirdClock=ACTIVE; edges=edges+1;
  if(reset === ASSERTED) begin
    expectedRoot=0; expectedChild=0; expectedMixed=0; stage0=1; stage1=1;
  end else begin
    stage0=0; stage1=oldStage0;
    if(oldStage1) begin expectedRoot=0; expectedChild=0; expectedMixed=0; end
    else begin
      expectedRoot=!oldRoot; expectedChild=oldRoot; expectedMixed=oldRoot && !oldChild;
      operatingEdges=operatingEdges+1;
      if(expectedMixed) mixedHigh=mixedHigh+1; else mixedLow=mixedLow+1;
    end
  end
  #0.001; check_observed;
end endtask
task finish_period; begin
  // Same promises matching active edges, not equal inactive duty phases.
  // Account for settled observation delays so active edges remain exactly 10ns
  // apart, matching the source's 100MHz declaration rather than rounding it.
  #1.999; srcClock=IDLE; unusedReset=~unusedReset; #0.001; check_observed;
  #1.999; dstClock=IDLE; #0.001; check_observed;
  #1.999; thirdClock=IDLE; #0.001; check_observed;
  #3.999; check_observed;
end endtask
task tick; begin active_edge; finish_period; end endtask
initial begin #5; forever tick; end
initial begin
  $dumpfile("{vcd.resolve().as_posix()}"); $dumpvars(0,tb);
  // Establish a genuine assertion edge; no time-zero register initialization.
  #0.5;
  if(rootOut !== 1'bx || childOut !== 1'bx)
    $fatal(1,"STARTUP: invented initialized state before any reset or active edge");
  #0.5; assert_reset;
  #30; reset=RELEASED; #0.001; check_observed;
  repeat(12) @({"posedge" if profile.rising else "negedge"} srcClock);
  // Common asynchronous reset while clocks are active, away from any edge.
  #0.5; assert_reset;
  #20; reset=RELEASED; #0.001; check_observed;
  repeat(14) @({"posedge" if profile.rising else "negedge"} srcClock);
  #0.002; check_observed;
  if(operatingEdges < 20 || assertions != 2 || observations < 100{"" if profile.shaped else " || mixedHigh < 8 || mixedLow < 8"})
    $fatal(1,"insufficient active-sampling/reset witnesses");
  $display("PASS {profile.name}: edges=%0d operating=%0d assertions=%0d observations=%0d{"" if profile.shaped else " mixed_high/low=%0d/%0d"}",
           edges,operatingEdges,assertions,observations{"" if profile.shaped else ",mixedHigh,mixedLow"});
  $finish;
end
initial begin #2000; $fatal(1,"TESTBENCH_TIMEOUT"); end
endmodule
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "iverilog", "vvp"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--circt-opt", type=Path, help="Defaults to circt-opt beside firtool.")
    parser.add_argument("--profile", action="append", help="Select an actual replay profile (repeatable).")
    args = parser.parse_args()
    args.input, args.out = args.input.resolve(), args.out.resolve()
    if not args.out.is_relative_to(ROOT / ".work"):
        parser.error("generated outputs must remain under this repository's ignored .work")
    args.out.mkdir(parents=True, exist_ok=True)
    selected = profiles()
    if args.profile:
        unknown = set(args.profile) - {profile.name for profile in selected}
        if unknown:
            parser.error(f"unknown profiles: {sorted(unknown)}")
        selected = [profile for profile in selected if profile.name in args.profile]
    paths = {name: getattr(args, name).resolve() for name in ("nodalc", "firtool", "iverilog", "vvp")}
    paths["circt_opt"] = (args.circt_opt or args.firtool.with_name("circt-opt")).resolve()
    manifest = {"status": "running", "harness_sha256": sha256(Path(__file__)),
                "profiles": [profile.name for profile in selected], "cases": [],
                "scope": "focused local prototype; external obligations are assumptions, not runtime certification",
                "tools": {name: {"path": str(path), "sha256": sha256(path)} for name, path in paths.items()}}
    manifest["source_revision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    source_names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "core/compiler", "core/scala"],
        cwd=ROOT, text=True).splitlines()
    manifest["source_files_sha256"] = {name: sha256(ROOT / name) for name in sorted(set(source_names))
                                       if (ROOT / name).is_file()}
    manifest["worktree_status"] = subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True)

    def save_manifest() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str]) -> str:
        (args.out / (label + ".command.json")).write_text(json.dumps(command) + "\n")
        result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        (args.out / (label + ".log")).write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result.stdout

    save_manifest()
    try:
        for profile in selected:
            name = profile.name
            original = args.input / (name + ".mlir")
            source = args.out / (name + ".source.mlir")
            source.write_bytes(original.read_bytes())
            circt, sv_ir, sv = [args.out / (name + extension) for extension in (".circt.mlir", ".sv.mlir", ".sv")]
            run(name + "-lower", [str(paths["nodalc"]), str(source), "--nodal-sequential-to-circt",
                                  "--mlir-print-debuginfo", "-o", str(circt)])
            run(name + "-lower-seq", [str(paths["circt_opt"]), str(circt),
                                      "--lower-seq-to-sv=disable-reg-randomization=true",
                                      "--mlir-print-debuginfo", "-o", str(sv_ir)])
            run(name + "-export", [str(paths["firtool"]), str(sv_ir), "--format=mlir", "--verilog",
                                   "--verify-each", "--disable-all-randomization",
                                   "--lowering-options=disallowLocalVariables", "-o", str(sv)])
            case = {"name": name, "inspection": inspect_target(profile, sv.read_text()),
                    "original_source": str(original.resolve()), "source_sha256": sha256(source),
                    "circt_sha256": sha256(circt), "target_sha256": sha256(sv)}
            if profile.kind == "same":
                bench, vcd, executable = [args.out / (name + extension) for extension in ("-tb.sv", ".vcd", ".vvp")]
                bench.write_text(testbench(profile, vcd))
                run(name + "-compile", [str(paths["iverilog"]), "-g2012", "-s", "tb", "-o",
                                        str(executable), str(sv), str(bench)])
                output = run(name + "-simulate", [str(paths["vvp"]), str(executable)])
                if f"PASS {name}:" not in output or "TESTBENCH_TIMEOUT" in output:
                    raise RuntimeError(f"{name}: simulation did not reach its semantic success marker")
                case.update({"mode": "legal_same_waveform", "testbench_sha256": sha256(bench),
                             "waveform_sha256": sha256(vcd), "simulation": output.strip()})
            else:
                case["mode"] = "classification_target_only"
            case["status"] = "passed"
            manifest["cases"].append(case)
            save_manifest()
            print(f"PASS {name}: {case['mode']}", flush=True)
        for name, path in paths.items():
            if sha256(path) != manifest["tools"][name]["sha256"]:
                raise RuntimeError(f"tool changed during run: {name}; use an immutable compiler copy")
        manifest["status"] = "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        save_manifest()


if __name__ == "__main__":
    main()
