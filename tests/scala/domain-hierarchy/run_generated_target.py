#!/usr/bin/env python3
"""Focused real generated-child source IR -> unchanged SV -> lane-distinct behavior.

Inputs are exported by Increment58GeneratedOccurrenceTargetReplay. All widths,
state offsets and pin identities are read from the actual emitted DUT. This is a
bounded two-state check, not startup-X, analog, CDC or resolved-pad acceptance.
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
    configurations: tuple[tuple[int, int, int], ...]
    families: int = 1
    rows: int = 1
    children: int = 1
    fields: str = "scalar"
    symbolic_width: bool = False
    stateful: bool = False


PROFILES = {
    "scalar": Profile("Scalar", ((1, 4, 1), (3, 8, 1), (8, 16, 1)), symbolic_width=True),
    "state": Profile("State", ((1, 8, 1), (3, 8, 1), (8, 8, 1)), stateful=True),
    "nested": Profile("Nested", ((3, 8, 1),), families=2, rows=2),
    "struct": Profile("Struct", ((1, 4, 1), (3, 8, 1), (8, 16, 1)),
                      fields="struct", symbolic_width=True),
    "multifield": Profile("Multifield", ((1, 4, 1), (3, 8, 1), (8, 16, 1)),
                          fields="multifield", symbolic_width=True),
    "guarded": Profile("Guarded", ((1, 8, 0), (3, 8, 1), (8, 8, 0), (8, 8, 1))),
    "nested-multifield": Profile("NestedMultifield", ((1, 4, 1), (3, 16, 1)),
                                 families=2, rows=2, fields="multifield", symbolic_width=True),
    "nonunit": Profile("Nonunit", ((8, 8, 1),)),
    "repeated": Profile("Repeated", ((3, 8, 1),), families=2, children=2, fields="repeated"),
    "empty": Profile("Empty", ((3, 8, 0),)),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def harness(model: dict, name: str, profile: Profile, lanes: int, width: int, enable: int) -> str:
    top = "Increment58GeneratedOccurrenceTarget" + profile.suffix
    fields = {"": width} if profile.fields == "scalar" else {"__f_4_data": width}
    if profile.fields == "multifield":
        fields.update({"__f_3_tag": 3, "__f_4_meta_5_flags": 2, "__f_4_meta_5_lanes": 8})
    if profile.fields == "repeated":
        fields = {"_a": 4, "_b": 8}
    count = lanes * profile.rows
    pins = {pin["name"]: pin for pin in model["states"]}
    expected = {"core_clock": ("input", 1), "core_reset": ("input", 1)}
    def pin(base: str, field: str) -> str:
        return base + field if field or base == "first" else base + "_0"
    for field, bits in fields.items():
        expected[pin("input", field)] = ("input", count * bits)
        if name != "nonunit":
            expected[pin("output", field)] = ("output", count * bits)
        expected[pin("first", field)] = ("output", bits)
    assert set(pins) == set(expected), (name, set(pins), set(expected))
    for key, proof in expected.items():
        assert (pins[key]["type"], pins[key]["numBits"]) == proof, (key, pins[key], proof)
    declarations, stimulus, updates, checks = [], [], [], []
    first = lanes if profile.rows == 2 else 4 if name == "nonunit" else 0
    for index, (field, bits) in enumerate(fields.items()):
        offset = pins[pin("input", field)]["offset"]
        value = f"((seed+37*i+{index}*19)&{(1 << bits) - 1})"
        declarations.append(f"unsigned expected{index}[{count}]{{}};")
        stimulus.append(f"for(unsigned i=0;i<{count};++i){{put({offset},i*{bits},{bits},{value});"
                        f"if(!{int(profile.stateful)})expected{index}[i]={value};}}")
        updates.append(f"if({int(profile.stateful)})for(unsigned i=0;i<{count};++i)"
                       f"expected{index}[i]=reset?0:{value};")
        if name != "nonunit":
            output = pins[pin("output", field)]["offset"]
            checks.append(f"for(unsigned i=0;i<{count};++i)if(get({output},i*{bits},{bits})"
                          f"!=expected{index}[i]){{std::fprintf(stderr,\"field {field} lane %u tick %u\\n\",i,ticks);std::exit(1);}}")
        selected = pins[pin("first", field)]["offset"]
        checks.append(f"if(get({selected},0,{bits})!=({enable}?expected{index}[{first}]:0))std::exit(2);")
    clock, reset = pins["core_clock"]["offset"], pins["core_reset"]["offset"]
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
  auto step=[&](bool reset,unsigned seed){{state[{clock}]=0;state[{reset}]=reset;
    {''.join(stimulus)}{top}_eval(state);if(ticks||!{int(profile.stateful)})check();
    state[{clock}]=1;{top}_eval(state);{''.join(updates)}++ticks;check();
    {top}_eval(state);check();}};
  step(true,7);step(false,39);step(false,217);step(true,91);step(false,65521);
  std::puts("GENERATED_OCCURRENCE_TARGET_PASS");
}}
'''


def negative_inputs(text: str) -> list[tuple[str, str, str]]:
    instance = next(line for line in text.splitlines() if '"nodal.instance"' in line)
    cases = []
    for name, key in [("forged-owner", "generated_owner"), ("forged-induction", "generated_induction")]:
        replacement = re.sub(key + r' = "[^"]+"', key + ' = "forged"', instance)
        assert replacement != instance
        cases.append((name, text.replace(instance, replacement, 1), "NODAL-ITERATION-043-004"))
    port = re.search(r'(%\w+) = "nodal.instance_port".*direction = "input"', text).group(1)
    write = next(line for line in text.splitlines() if f'"nodal.digital_assign"({port},' in line)
    cases.append(("duplicate-input-writer", text.replace(write, write + "\n" + write, 1),
                  "NODAL-DIGITAL-055-010"))
    binding = next(line for line in text.splitlines() if '"nodal.instance_domain_binding"' in line)
    cases.append(("missing-domain-binding", text.replace(binding, "", 1),
                  "reachable requirement lacks a unique concrete instance binding"))
    return cases


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
                "runtime_library_path": os.environ.get("LD_LIBRARY_PATH", ""),
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
        gate, lower, sv = (args.output / (name + suffix) for suffix in [".gated.mlir", ".circt.mlir", ".sv"])
        run(name + "-gate", [args.nodalc, source, "--nodal-gate-default", "-o", gate])
        run(name + "-lower", [args.nodalc, gate,
            "--nodal-sequential-to-circt=preserve-constant-loops=true boundary-abi=named-fields", "-o", lower])
        text = lower.read_text()
        assert text.count("nodal.generated_template") == profile.families
        assert len(re.findall(r"\bhw\.module\b", text)) == profile.families + 2 + (name == "guarded")
        assert len(re.findall(r"\bhw\.instance\b", text)) == profile.families + profile.children + (name == "guarded")
        run(name + "-export", [args.toolchain / "bin/firtool", "--format=mlir", "--verify-each", lower, "-o", sv])
        sv_hash = digest(sv)
        assert sv.read_text().count("for (genvar ") == profile.families
        case = {"profile": name, "source_sha256": digest(source), "sv_sha256": sv_hash, "configurations": []}
        manifest["cases"].append(case)
        for lanes, width, enable in profile.configurations:
            key = f"{name}-{lanes}-{width}-{enable}"
            imp, ll, state, obj, driver, exe = (args.output / (key + suffix) for suffix in
                                               [".import.mlir", ".ll", ".state.json", ".o", ".cpp", ".simulate"])
            top = "Increment58GeneratedOccurrenceTarget" + profile.suffix
            parameters = {} if name == "nonunit" else {"LANES": lanes}
            if profile.symbolic_width:
                parameters["WIDTH"] = width
            if name == "guarded":
                parameters["ENABLE"] = enable
            overrides = [token for key, value in parameters.items() for token in ["-G", f"{key}={value}"]]
            run(key + "-import", [args.toolchain / "bin/circt-verilog", "--ir-hw", "--top", top,
                                   *overrides, sv, "-o", imp])
            run(key + "-arc", [args.toolchain / "bin/arcilator", imp, "--emit-llvm", f"--state-file={state}", "-o", ll])
            models = json.loads(state.read_text())
            assert len(models) == 1 and models[0]["name"] == top
            assert not models[0]["initialFnSym"] and not models[0]["finalFnSym"]
            driver.write_text(harness(models[0], name, profile, lanes, width, enable))
            run(key + "-object", [args.toolchain / "bin/llc", "-filetype=obj", "-relocation-model=pic", ll, "-o", obj])
            run(key + "-link", [args.cxx, "-std=c++17", driver, obj, f"-L{args.toolchain}/lib",
                                "-lCIRCTArcRuntime", f"-Wl,-rpath,{args.toolchain}/lib", "-o", exe])
            assert "GENERATED_OCCURRENCE_TARGET_PASS" in run(key + "-simulate", [exe.resolve()])
            assert digest(sv) == sv_hash
            case["configurations"].append(parameters)
            save()
        print(name, "PASS", flush=True)
    for name, text, marker in negative_inputs((args.inputs / "scalar.mlir").read_text()):
        path = args.output / (name + ".mlir")
        path.write_text(text)
        run(name, [args.nodalc, path, "--nodal-gate-default", "-o", args.output / (name + ".out.mlir")], marker)
        manifest["negatives"].append({"case": name, "input_sha256": digest(path), "diagnostic": marker})
        print(name, "REJECTED", flush=True)
    manifest["status"] = "passed"
    save()


if __name__ == "__main__":
    main()
