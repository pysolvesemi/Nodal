#!/usr/bin/env python3
"""Exercise maintained Generate domains through actual unchanged SV and arcilator.

Only stimulus is handwritten. State-map offsets and all observed outputs come
from the emitted DUT. This is focused two-state behavior, not startup-X proof.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
from run_target import digest, pin_offsets

FIXTURE = "core/scala/testkit/test/src/nodal_external/Increment58SelectedDependentAreaSource.scala"

@dataclass(frozen=True)
class Profile:
    top: str
    parameters: tuple[str, ...]
    configurations: tuple[tuple[int, ...], ...]
    families: int
    lanes: int
    outputs: tuple[str, ...]
    coordinates: tuple[int, ...] = ()
    fixture: str = FIXTURE
    strategy: str = "lanes"
    scopes: int = 0

PROFILES = {
    "nonunit-dependent-registers": Profile("Increment58NonunitDependentRegisters", (), ((),),
                                           2, 64, ("first", "last"), (16, 36)),
    "symbolic-domain": Profile("Increment58SymbolicDomainRegisters", ("lower", "upper", "step"),
                               ((1, 8, 1), (1, 8, 2), (3, 10, 3), (2, 9, 2)),
                               1, 16, ("first", "middle")),
    "symbolic-bounds-fixed-step": Profile("Increment58SymbolicBoundsFixedStepRegisters", ("lower", "upper"),
                                          ((1, 8), (2, 9), (3, 10)), 1, 16, ("first", "middle")),
}

DOMAIN_FIXTURE = "core/scala/testkit/test/src/nodal_external/Increment58GeneratedDomainSource.scala"
for key, cls, parameters, configurations, families, lanes, outputs, coordinates, strategy, scopes in (
    ("nonzero", "NonzeroBounds", (), ((),), 1, 8, ("first", "middle", "last"), (1, 2, 3), "lanes", 0),
    ("nonunit", "NonunitBounds", (), ((),), 1, 8, ("first", "middle", "last"), (1, 3, 5), "lanes", 0),
    ("empty", "EmptyBounds", (), ((),), 1, 1, ("output_0",), (0,), "empty", 0),
    ("maybe-empty", "MaybeEmptyBounds", ("count",), ((0,), (1,), (4,)), 1, 4, ("output_0",), (0,), "lanes", 0),
    ("guarded", "GuardedBounds", ("count",), ((0,), (1,), (4,)), 1, 4, ("output_0",), (0,), "lanes", 1),
    ("captured", "CapturedBounds", ("ROWS",), ((2,), (3,), (4,)), 2, 16, ("first", "last"), (0, 4), "lanes", 0),
    ("captured-nonunit", "CapturedNonunitBounds", (), ((),), 2, 64, ("first", "last"), (16, 32), "lanes", 0),
    ("captured-selected", "CapturedSelectedBounds", ("ROWS",), ((2,), (3,), (4,)), 2, 16, ("output_0",), (5,), "lanes", 0),
    ("guarded-scalar", "GuardedScalarExpression", ("count",), ((0,), (1,), (4,)), 1, 1, ("output_0",), (0,), "scalar", 1),
    ("guarded-symbolic", "GuardedSymbolicExpression", ("count", "width"), ((0, 2), (1, 2), (3, 4), (4, 8)), 1, 1, ("output_0",), (0,), "symbolic", 1),
    ("guarded-nested", "GuardedNestedExpression", ("count", "width"), ((0, 2), (1, 2), (3, 4), (4, 8)), 2, 1, ("output_0",), (0,), "symbolic-nested", 1),
):
    PROFILES[key] = Profile("Increment58Generated" + cls, parameters, configurations, families,
                            lanes, outputs, coordinates, DOMAIN_FIXTURE, strategy, scopes)

PROFILES["nested-guard"] = Profile("Increment58NestedGuardedRegisters", ("count", "mode"),
    ((0, 0), (0, 1), (1, 0), (1, 1), (4, 0), (4, 1)), 1, 1, ("output_0",), (0,),
    DOMAIN_FIXTURE, "nested-guard", 2)

PROFILES["guarded-outlined-selected"] = Profile("Increment58GuardedOutlinedNestedSelected", ("ROWS", "mode"),
    ((1, 0), (1, 1), (2, 0), (2, 1), (4, 0), (4, 1)), 2, 16, ("output_0",), (5,),
    DOMAIN_FIXTURE, "guarded-selection", 1)

def nested_guard_harness(model: dict, params: dict[str, int]) -> str:
    count, mode = params["count"], params["mode"]
    top, off = pin_offsets(model, {"clock_clock": ("input", 1), "clock_reset": ("input", 1),
        "data": ("input", 8), "enable": ("input", 1), "output_0": ("output", 8)})
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}}; uint8_t expected=0; unsigned ticks=0;
  auto check=[&](){{if(state[{off['output_0']}]!=expected){{
    std::fprintf(stderr,"tick %u actual %u expected %u\\n",ticks,state[{off['output_0']}],expected);std::exit(1);}}}};
  auto combinational=[&](){{if({count}==0)expected=0;else if({mode}==0)expected=state[{off['data']}];}};
  auto step=[&](bool reset,bool enable,unsigned data){{
    state[{off['clock_clock']}]=0;state[{off['clock_reset']}]=reset;
    state[{off['enable']}]=enable;state[{off['data']}]=uint8_t(data);combinational();
    {top}_eval(state);if(ticks||{count}==0||{mode}==0)check();
    state[{off['clock_clock']}]=1;{top}_eval(state);
    if({count}>0&&{mode}==1){{if(reset)expected=0;else if(enable)expected=uint8_t(data);}}
    ++ticks;check();state[{off['data']}]^=255;combinational();{top}_eval(state);check();
  }};
  step(true,true,17);step(false,true,49);step(false,false,173);step(false,true,251);
  step(true,false,151);step(false,true,37);step(true,true,225);
  std::printf("GENERATED_DOMAIN_TARGET_PASS ticks=%u\\n",ticks);
}}
'''

def scalar_harness(model: dict, spec: Profile, params: dict[str, int]) -> str:
    width, count = params.get("width", 8), params["count"]
    resetless = spec.strategy.startswith("symbolic")
    pins = {"clock_clock": ("input", 1), "enable": ("input", 1),
            "a": ("input", width), "b": ("input", width), "output_0": ("output", width)}
    if not resetless: pins["clock_reset"] = ("input", 1)
    top, off = pin_offsets(model, pins)
    update = "b" if spec.strategy == "symbolic-nested" else "(a^b)"
    reset_put = "" if resetless else f"state[{off['clock_reset']}]=reset;"
    false_value = "a" if resetless else "0"
    reset_update = "" if resetless else "else if(reset)expected=0;"
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}}; uint8_t expected=0; unsigned ticks=0;
  constexpr unsigned mask={(1<<width)-1};
  auto check=[&](){{if(state[{off['output_0']}]!=expected){{
    std::fprintf(stderr,"tick %u actual %u expected %u\\n",ticks,state[{off['output_0']}],expected);std::exit(1);}}}};
  auto step=[&](bool reset,bool enable,unsigned a,unsigned b){{
    a&=mask;b&=mask;state[{off['clock_clock']}]=0;state[{off['enable']}]=enable;
    state[{off['a']}]=a;state[{off['b']}]=b;{reset_put}
    if({count}==0)expected={false_value};{top}_eval(state);if(ticks||{count}==0)check();
    state[{off['clock_clock']}]=1;{top}_eval(state);
    if({count}==0)expected={false_value};{reset_update}else if(enable)expected={update};++ticks;check();
    {top}_eval(state);check();
  }};
  step(true,true,1,2);step(false,false,3,1);step(false,true,0x95,0x6a);
  step(false,false,0x71,0x35);step(false,true,0xff,0);step(true,false,0,0xff);
  step(false,true,0x37,0x18);step(true,true,0x91,0x73);
  std::printf("GENERATED_DOMAIN_TARGET_PASS ticks=%u\\n",ticks);
}}
'''

def harness(model: dict, spec: Profile, coordinates: tuple[int, ...], params: dict[str, int]) -> str:
    if spec.strategy == "nested-guard":
        return nested_guard_harness(model, params)
    if spec.strategy in ("scalar", "symbolic", "symbolic-nested"):
        return scalar_harness(model, spec, params)
    count = 0 if spec.strategy == "empty" else params.get("count", 1)
    if spec.strategy == "guarded-selection":
        count = int(params["ROWS"] > 1 and params["mode"] == 1)
    enable_name = "enable" if spec.strategy == "empty" else "enables"
    expected = {"clock_clock": ("input", 1), "clock_reset": ("input", 1),
                "data": ("input", spec.lanes * 8), enable_name: ("input", spec.lanes)}
    expected.update({name: ("output", 8) for name in spec.outputs})
    top, offsets = pin_offsets(model, expected)
    checks = "\n".join(f'''if(state[{offsets[name]}] != expected[{i}]) {{
      std::fprintf(stderr, "tick %u output {name} actual %u expected %u\\n", ticks,
                   state[{offsets[name]}], expected[{i}]); std::exit(1);
    }}''' for i, name in enumerate(spec.outputs))
    updates = "\n".join(f'''if ({count}==0 || reset) expected[{i}]=0;
    else if ((enable >> {coordinate}) & 1) expected[{i}]=uint8_t(seed + {coordinate} * 37);'''
                         for i, coordinate in enumerate(coordinates))
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}};
  uint8_t expected[{len(coordinates)}]{{}}; unsigned ticks=0;
  auto check=[&]() {{ {checks} }};
  auto step=[&](bool reset, uint64_t enable, unsigned seed) {{
    state[{offsets['clock_clock']}]=0; state[{offsets['clock_reset']}]=reset;
    for(unsigned i=0;i<{spec.lanes};++i) state[{offsets['data']}+i]=uint8_t(seed+i*37);
    for(unsigned i=0;i<{(spec.lanes+7)//8};++i) state[{offsets[enable_name]}+i]=uint8_t(enable>>(i*8));
    if ({spec.lanes}<8) state[{offsets[enable_name]}]&={(1<<min(spec.lanes,8))-1};
    {top}_eval(state); if(ticks) check();
    state[{offsets['clock_clock']}]=1; {top}_eval(state);
    {updates} ++ticks; check();
    state[{offsets['data']}+{coordinates[0]}]^=255;
    {top}_eval(state); check(); // No second edge, no second update.
  }};
  step(true,~0ULL,7); step(false,~0ULL,13);
  for(unsigned i=0;i<{spec.lanes};++i) step(false,1ULL<<i,31+i*11);
  step(false,0,171); step(true,0,99); step(false,~0ULL,189); step(true,~0ULL,3);
  std::printf("GENERATED_DOMAIN_TARGET_PASS ticks=%u\\n",ticks);
}}
'''

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "circt-verilog", "arcilator", "llc", "cxx", "runtime-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--case", choices=tuple(PROFILES), action="append")
    args = parser.parse_args()
    args.out = args.out.resolve()
    if ".work" not in args.out.parts or (args.out.exists() and any(args.out.iterdir())):
        parser.error("use a fresh ignored .work output directory")
    args.out.mkdir(parents=True, exist_ok=True)
    tools = {name: getattr(args, name.replace("-", "_")).resolve()
             for name in ("nodalc", "firtool", "circt-verilog", "arcilator", "llc", "cxx")}
    runtime = args.runtime_dir.resolve() / "libCIRCTArcRuntime.so"
    for name, path in dict(tools, runtime=runtime).items():
        if not path.is_file(): parser.error(f"missing {name}: {path}")
    manifest = {"status": "running", "simulation": "two-state, not startup-X qualification",
                "tools": {n: {"path": str(p), "sha256": digest(p)} for n, p in dict(tools, runtime=runtime).items()},
                "commands": [], "cases": []}
    def save() -> None:
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    def run(label: str, command: list[str | Path]) -> subprocess.CompletedProcess:
        argv = [str(arg) for arg in command]
        result = subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        log = args.out / (label + ".log"); log.write_text(result.stdout)
        manifest["commands"].append({"label": label, "argv": argv, "exit": result.returncode, "log_sha256": digest(log)})
        save()
        if result.returncode: raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result
    save()
    for profile in args.case or PROFILES:
        spec = PROFILES[profile]
        source = args.input.resolve() / (profile + ".mlir")
        original = source.read_text()
        if spec.fixture not in original or f'nodal.root.module = @{spec.top}' not in original:
            raise RuntimeError(f"{profile}: expected exact maintained Scala replay")
        if original.count('"nodal.generate"') != spec.families:
            raise RuntimeError(f"{profile}: actual authored family inventory mismatch")
        gate, circt, sv = (args.out / f"{profile}.{suffix}" for suffix in ("gated.mlir", "circt.mlir", "sv"))
        run(profile + "-gate", [tools["nodalc"], source, "--nodal-gate-default", "-o", gate])
        option = "--nodal-sequential-to-circt=preserve-constant-loops=true" if spec.coordinates else "--nodal-sequential-to-circt"
        run(profile + "-lower", [tools["nodalc"], gate, option, "-o", circt])
        lowered = circt.read_text()
        if (len(re.findall(r"\bhw\.module\b", lowered)) != spec.families + spec.scopes + 1 or
                lowered.count("nodal.generated_template") != spec.families or
                len(re.findall(r"\bhw\.instance\b", lowered)) != spec.families + spec.scopes or
                lowered.count("nodal.generated_guard_scope") != spec.scopes or
                len(re.findall(r"\bseq\.(?:compreg|firreg)\b", lowered)) != 1):
            raise RuntimeError(f"{profile}: reusable helper/state structure collapsed or cloned")
        if spec.strategy == "guarded-selection":
            # This regression must carry the root family bus into the actual
            # outlined arm, not hoist the selected scalar out of its guard.
            guard = next(line for line in lowered.splitlines()
                         if "hw.module " in line and "nodal.generated_guard_scope" in line)
            body = lowered[lowered.index(guard):].split("\n  hw.module ", 1)[0]
            selected = next(line for line in body.splitlines() if "nodal.generated_selection" in line)
            if ('in %capture_0 : !hw.int<#hw.param.expr.mul<#hw.param.decl.ref<"ROWS">, 32>>' not in guard or
                    selected.count("coordinate = 1 : i32") != 2 or selected.count("ordinal = 1 : i32") != 2 or
                    "stride_bits = 32 : i32" not in selected or "stride_bits = 8 : i32" not in selected or
                    "value = 40 : i32" not in body):
                raise RuntimeError(f"{profile}: exact nested carrier route or guard scope lost")
        if not spec.coordinates and "hw.param.expr.divs" not in lowered:
            raise RuntimeError(f"{profile}: exact parameter-expression cardinality was lost")
        run(profile + "-export", [tools["firtool"], "--format=mlir", circt, "-o", sv])
        sv_hash = digest(sv)
        if sv.read_text().count("for (genvar ") != spec.families:
            raise RuntimeError(f"{profile}: actual Generate loop missing")
        entry = {"name": profile, "source_fixture": spec.fixture, "scala_sha256": digest(args.repo / spec.fixture),
                 "input_sha256": digest(source), "gated_sha256": digest(gate), "circt_sha256": digest(circt),
                 "sv_sha256": sv_hash, "configurations": []}
        manifest["cases"].append(entry)
        for config in spec.configurations:
            params = dict(zip(spec.parameters, config))
            coordinates = spec.coordinates or (params["lower"], params["lower"] + params.get("step", 2))
            label = profile + ("-" + "x".join(map(str, config)) if config else "")
            imported, llvm, state, obj, driver, exe = (args.out / f"{label}.{suffix}"
                for suffix in ("import.mlir", "ll", "state.json", "o", "cpp", "simulate"))
            overrides = [arg for key, value in params.items() for arg in ("-G", f"{key}={value}")]
            run(label + "-import", [tools["circt-verilog"], "--ir-hw", "--top", spec.top, *overrides, sv, "-o", imported])
            run(label + "-arc", [tools["arcilator"], imported, "--emit-llvm", f"--state-file={state}", "-o", llvm])
            models = json.loads(state.read_text())
            if len(models) != 1 or models[0]["name"] != spec.top:
                raise RuntimeError("unexpected arcilator model identity")
            driver.write_text(harness(models[0], spec, coordinates, params))
            run(label + "-object", [tools["llc"], "-filetype=obj", "-relocation-model=pic", llvm, "-o", obj])
            run(label + "-link", [tools["cxx"], "-std=c++17", driver, obj, f"-L{runtime.parent}",
                                   "-lCIRCTArcRuntime", f"-Wl,-rpath,{runtime.parent}", "-o", exe])
            result = run(label + "-simulate", [exe])
            if "GENERATED_DOMAIN_TARGET_PASS" not in result.stdout:
                raise RuntimeError("simulation did not reach semantic checks")
            observation = {"parameters": params, "selected_flat_coordinates": coordinates,
                           "artifacts": {p.name: digest(p) for p in (imported, llvm, state, obj, driver, exe)}}
            if not spec.coordinates:
                step = params.get("step", 2)
                observation["actual_bus_bits"] = 8 * ((params["upper"] - params["lower"] + step - 1) // step)
            entry["configurations"].append(observation)
            if digest(sv) != sv_hash: raise RuntimeError("emitted SV changed during consumer checks")
            save()
        print(f"PASS {profile}: actual selected coordinates and unchanged SV", flush=True)
    manifest["status"] = "passed"; save()

if __name__ == "__main__":
    main()
