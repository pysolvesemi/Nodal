#!/usr/bin/env python3
"""Actual selected generated Instance source IR -> unchanged optimized SV behavior.

Inputs come from Increment58SelectedInstanceTargetReplay, never hand-written SV.
This bounded two-state check does not establish startup-X or resolved/analog/CDC
semantics. Dependent payload shapes and selected Interface transport remain open.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


@dataclass(frozen=True)
class Profile:
    suffix: str
    lanes: int = 3
    selected: tuple[int, int] = (0, 1)
    families: int = 1
    child_sites: int = 1
    reset: bool = False
    stateful: bool = False
    fields: str = "scalar"
    widths: tuple[int, ...] = (8,)
    symbolic_width: bool = False
    guarded: bool = False


PROFILES = {
    "two": Profile("Two", reset=True),
    "state": Profile("Two", reset=True, stateful=True),
    "nonunit": Profile("Nonunit", lanes=8, selected=(2, 4)),
    "nested": Profile("Nested", lanes=6, selected=(1, 5), families=2),
    "bindings": Profile("Bindings", selected=(1, 2), families=2, child_sites=2, fields="bindings"),
    "struct": Profile("Struct", fields="struct"),
    "guarded": Profile("Guarded", guarded=True),
    "vec": Profile("Vec"),
    "symbolic": Profile("Symbolic", widths=(4, 8, 16), symbolic_width=True),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def harness(model: dict, top: str, profile: Profile, width: int, enable: int) -> str:
    fields = {"": width}
    if profile.fields == "struct":
        fields = {"__f_4_data": 8, "__f_3_tag": 3,
                  "__f_4_meta_5_flags": 2, "__f_4_meta_5_lanes": 8}
    # Each field owns an independent physical leaf vector. The small Vec inside
    # meta.lanes is one eight-bit leaf whose two nibbles retain row-major order.
    routes = []
    if profile.fields == "bindings":
        routes = [("narrowInput", 4, (("narrow", 1), ("narrowAgain", 1))),
                  ("wideInput", 8, (("wide", 2), ("wideAgain", 2)))]
    else:
        for field, bits in fields.items():
            routes.append(("input" + (field or "_0"), bits,
                           tuple((port + field, lane) for port, lane in
                                 [("left", profile.selected[0]),
                                  ("right", profile.selected[1]),
                                  ("again", profile.selected[0])])))
    expected = {"core_clock": ("input", 1)}
    if profile.reset:
        expected["core_reset"] = ("input", 1)
    for pin, bits, outputs in routes:
        expected[pin] = ("input", bits * profile.lanes)
        for output, _ in outputs:
            expected[output] = ("output", bits)
    pins = {pin["name"]: pin for pin in model["states"]}
    assert set(pins) == set(expected), (pins, expected)
    for pin, identity in expected.items():
        assert (pins[pin]["type"], pins[pin]["numBits"]) == identity, (pin, pins[pin], identity)
    declarations, stimulus, updates, checks = [], [], [], []
    for field, (pin, bits, outputs) in enumerate(routes):
        value = f"((seed+37*i+{field}*19)&{(1 << bits)-1})"
        declarations.append(f"unsigned expected{field}[{profile.lanes}]{{}};")
        stimulus.append(f"for(unsigned i=0;i<{profile.lanes};++i){{"
                        f"put({pins[pin]['offset']},i*{bits},{bits},{value});"
                        f"if(!{int(profile.stateful)})expected{field}[i]={value};}}")
        updates.append(f"if({int(profile.stateful)})for(unsigned i=0;i<{profile.lanes};++i)"
                       f"expected{field}[i]=reset?0:{value};")
        for output, lane in outputs:
            checks.append(f"if(get({pins[output]['offset']},0,{bits})!=({enable}?expected{field}[{lane}]:0))"
                          f"{{std::fprintf(stderr,\"{output} mismatch tick %u\\n\",ticks);std::exit(1);}}")
    reset = f"state[{pins['core_reset']['offset']}]=reset;" if profile.reset else ""
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}}; unsigned ticks=0;
  {''.join(declarations)}
  auto put=[&](unsigned off,unsigned bit,unsigned width,unsigned value){{
    for(unsigned b=0;b<width;++b){{unsigned at=bit+b,mask=1u<<(at%8);
      state[off+at/8]=(state[off+at/8]&~mask)|(((value>>b)&1u)<<(at%8));}}}};
  auto get=[&](unsigned off,unsigned bit,unsigned width){{unsigned value=0;
    for(unsigned b=0;b<width;++b){{unsigned at=bit+b;value|=((state[off+at/8]>>(at%8))&1u)<<b;}}return value;}};
  auto check=[&](){{{''.join(checks)}}};
  auto step=[&](bool reset,unsigned seed){{state[{pins['core_clock']['offset']}]=0;{reset}
    {''.join(stimulus)}{top}_eval(state);if(ticks||!{int(profile.stateful)})check();
    state[{pins['core_clock']['offset']}]=1;{top}_eval(state);{''.join(updates)}++ticks;check();
    {top}_eval(state);check();}};
  step(true,7);step(false,39);step(false,217);step(true,91);step(false,65521);
  std::puts("SELECTED_INSTANCE_TARGET_PASS");
}}
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--toolchain", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cxx", type=Path, required=True)
    parser.add_argument("--profile", action="append", choices=PROFILES)
    args = parser.parse_args()
    if ".work" not in args.output.resolve().parts:
        parser.error("outputs must remain under ignored .work")
    for path in [args.nodalc, args.cxx, *(args.toolchain / "bin" / name for name in
                 ["firtool", "circt-verilog", "arcilator", "llc"])]:
        if not path.is_file():
            parser.error(f"missing already-built tool: {path}")
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = {"status": "running", "compiler_sha256": digest(args.nodalc),
                "runner_sha256": digest(Path(__file__)),
                "runtime_library_path": os.environ.get("LD_LIBRARY_PATH", ""),
                "toolchain_descriptor_sha256": digest(args.toolchain / ".nodal-toolchain.json"),
                "commands": [], "cases": [], "negatives": []}
    def save() -> None:
        (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    def run(label: str, command: list, marker: str = "") -> str:
        command = list(map(str, command))
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=180)
        (args.output / (label + ".log")).write_text(result.stdout)
        manifest["commands"].append({"label": label, "argv": command, "exit": result.returncode})
        save()
        valid = result.returncode == 1 and marker in result.stdout if marker else result.returncode == 0
        if not valid:
            raise RuntimeError(label + ": " + result.stdout[-4000:])
        return result.stdout
    for name in args.profile or PROFILES:
        profile = PROFILES[name]
        source = args.inputs / (name + ".mlir")
        source_hash = digest(source)
        gate, lower, sv = (args.output / (name + suffix) for suffix in [".gated.mlir", ".circt.mlir", ".sv"])
        run(name + "-gate", [args.nodalc, source, "--nodal-gate-default", "-o", gate])
        run(name + "-lower", [args.nodalc, gate,
            "--nodal-sequential-to-circt=preserve-constant-loops=true boundary-abi=named-fields", "-o", lower])
        text = lower.read_text()
        assert text.count("nodal.generated_template") == profile.families
        if name == "vec":
            helper = next(line for line in text.splitlines() if "hw.module" in line and "nodal.generated_template" in line)
            assert 'dimension_groups = [[2 : i32]]' in helper and 'element_width = 4 : i32' in helper
        # Even different bindings share one original child definition.
        assert len(re.findall(r"\bhw\.module\b", text)) == profile.families + 2 + profile.guarded
        assert len(re.findall(r"\bhw\.instance\b", text)) == profile.families + profile.child_sites + profile.guarded
        run(name + "-export", [args.toolchain / "bin/firtool", "--format=mlir", "--verify-each", lower, "-o", sv])
        sv_hash = digest(sv)
        assert sv.read_text().count("for (genvar ") == profile.families
        case = {"profile": name, "source_sha256": source_hash, "sv_sha256": sv_hash,
                "gated_sha256": digest(gate), "circt_sha256": digest(lower),
                "families": profile.families, "child_sites": profile.child_sites,
                "original_child_definitions": 1, "configurations": []}
        manifest["cases"].append(case)
        configurations = [(width, enable) for width in profile.widths
                          for enable in ((0, 1) if profile.guarded else (1,))]
        for width, enable in configurations:
            key = f"{name}-{width}-{enable}"
            parameters = {"WIDTH": width} if profile.symbolic_width else {}
            if profile.guarded:
                parameters["mode"] = enable
            overrides = [token for parameter, value in parameters.items()
                         for token in ["-G", f"{parameter}={value}"]]
            imp, ll, state, obj, driver, exe = (args.output / (key + suffix) for suffix in
                                               [".import.mlir", ".ll", ".state.json", ".o", ".cpp", ".simulate"])
            top = "Increment58SelectedInstanceTarget" + profile.suffix
            run(key + "-import", [args.toolchain / "bin/circt-verilog", "--ir-hw", "--top", top, *overrides, sv, "-o", imp])
            run(key + "-arc", [args.toolchain / "bin/arcilator", imp, "--emit-llvm", f"--state-file={state}", "-o", ll])
            models = json.loads(state.read_text())
            assert len(models) == 1 and models[0]["name"] == top
            assert not models[0]["initialFnSym"] and not models[0]["finalFnSym"]
            driver.write_text(harness(models[0], top, profile, width, enable))
            run(key + "-object", [args.toolchain / "bin/llc", "-filetype=obj", "-relocation-model=pic", ll, "-o", obj])
            run(key + "-link", [args.cxx, "-std=c++17", driver, obj, f"-L{args.toolchain}/lib",
                                "-lCIRCTArcRuntime", f"-Wl,-rpath,{args.toolchain}/lib", "-o", exe])
            assert "SELECTED_INSTANCE_TARGET_PASS" in run(key + "-simulate", [exe.resolve()])
            assert digest(sv) == sv_hash
            assert digest(source) == source_hash
            case["configurations"].append({"parameters": parameters,
                                           "model_sha256": digest(state),
                                           "driver_sha256": digest(driver),
                                           "executable_sha256": digest(exe)})
            save()
        print(name, "PASS", flush=True)
    for name in ["varying", "varying-nonunit"]:
        source = args.inputs / (name + ".mlir")
        gate = args.output / (name + ".gated.mlir")
        run(name + "-gate", [args.nodalc, source, "--nodal-gate-default", "-o", gate])
        marker = "selected instance carrier requires invariant all-lane payload widths"
        run(name + "-refusal", [args.nodalc, gate,
            "--nodal-sequential-to-circt=preserve-constant-loops=true boundary-abi=named-fields",
            "-o", args.output / (name + ".out.mlir")], marker)
        manifest["negatives"].append({"profile": name, "source_sha256": digest(source),
                                      "native_gate": "passed", "target_refusal": marker})
        print(name, "REFUSED", flush=True)
    manifest["status"] = "passed"
    save()


if __name__ == "__main__":
    main()
