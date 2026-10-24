#!/usr/bin/env python3
"""Run actual generated Area replays through unchanged SV and two-state arcilator.

Only the C++ stimulus harness is handwritten. Its offsets come from arcilator's
actual state map. No emitted DUT IR or SystemVerilog is patched by this runner.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import subprocess


@dataclass(frozen=True)
class TargetProfile:
    top: str
    fixture: str
    parameters: tuple[str, ...]
    configurations: tuple[tuple[int, ...], ...]
    families: int = 1
    selections: int = 1
    illegal: tuple[int, ...] = ()


DIRECT_FIXTURE = "core/scala/testkit/test/src/nodal_external/Increment58GeneratedLaneRegisters.scala"
PROFILES = {
    "lane-registers": TargetProfile("Increment58GeneratedLaneRegisters", DIRECT_FIXTURE,
                                    ("LANES",), ((1,), (3,), (8,)), illegal=(9,)),
    "concrete-lane-registers": TargetProfile("Increment58GeneratedConcreteLaneRegisters",
                                             DIRECT_FIXTURE, (), ((3,),)),
    "nested-lane-registers": TargetProfile("Increment58NestedLaneRegisters",
        "core/scala/testkit/test/src/nodal_external/Increment58NestedLaneRegisters.scala",
        ("ROWS", "COLS"), ((2, 3), (3, 2), (4, 4)), families=2, selections=4, illegal=(5, 3)),
}
DEFAULT_PROFILES = ("lane-registers", "concrete-lane-registers")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin_offsets(model: dict, expected: dict[str, tuple[str, int]]) -> tuple[str, dict[str, int]]:
    top = model["name"]
    if not re.fullmatch(r"[A-Za-z_]\w*", top):
        raise RuntimeError("arcilator model name is not a C identifier")
    if model["initialFnSym"] or model["finalFnSym"]:
        raise RuntimeError("this focused harness requires no additional initialization/finalizer hook")
    states = {entry["name"]: entry for entry in model["states"]}
    if len(states) != len(model["states"]) or set(states) != set(expected):
        raise RuntimeError(f"unexpected actual target pin inventory: {list(states)}")
    offsets = {}
    for name, (kind, bits) in expected.items():
        actual = states[name]
        if (actual["type"], actual["numBits"]) != (kind, bits):
            raise RuntimeError(f"{name}: actual target direction/width mismatch")
        offset = actual["offset"]
        if offset < 0 or offset + (bits + 7) // 8 > model["numStateBytes"]:
            raise RuntimeError(f"{name}: invalid actual state-map offset")
        offsets[name] = offset
    return top, offsets


def harness(model: dict, lanes: int) -> str:
    top, offsets = pin_offsets(model, {
        "clock_clock": ("input", 1), "clock_reset": ("input", 1),
        "enable": ("input", lanes), "dataIn": ("input", lanes * 8),
        "dataOut": ("output", lanes * 8), "firstLane": ("output", 8),
    })
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}};
  constexpr unsigned lanes = {lanes}, all = (1U << lanes) - 1;
  uint64_t expected = 0;
  unsigned ticks = 0;
  auto put = [&](unsigned off, uint64_t value, unsigned bytes = 1) {{
    for (unsigned i = 0; i < bytes; ++i) state[off + i] = uint8_t(value >> (8 * i));
  }};
  auto get = [&](unsigned off, unsigned bytes = 1) {{
    uint64_t value = 0;
    for (unsigned i = 0; i < bytes; ++i) value |= uint64_t(state[off + i]) << (8 * i);
    return value;
  }};
  auto check = [&] {{
    auto actual = get({offsets['dataOut']}, lanes);
    auto first = get({offsets['firstLane']});
    if (actual != expected || first != (expected & 255)) {{
      std::fprintf(stderr, "tick %u actual %016llx first %02llx expected %016llx\\n", ticks,
                   (unsigned long long)actual, (unsigned long long)first,
                   (unsigned long long)expected);
      std::exit(1);
    }}
  }};
  auto pattern = [&](unsigned seed) {{
    uint64_t data = 0;
    for (unsigned i = 0; i < lanes; ++i) data |= uint64_t((seed + 37 * i) & 255) << (8 * i);
    return data;
  }};
  auto step = [&](bool reset, unsigned enable, uint64_t data) {{
    put({offsets['clock_clock']}, 0);
    put({offsets['clock_reset']}, reset);
    put({offsets['enable']}, enable);
    put({offsets['dataIn']}, data, lanes);
    {top}_eval(state);
    if (ticks) check(); // Falling edge and input changes cannot update state.
    put({offsets['clock_clock']}, 1);
    {top}_eval(state);
    ++ticks;
    if (reset) expected = 0;
    else for (unsigned i = 0; i < lanes; ++i) if ((enable >> i) & 1) {{
      uint64_t byte = (data >> (8 * i)) & 255;
      if (i == 0) byte ^= 255;
      expected = (expected & ~(uint64_t(255) << (8 * i))) | (byte << (8 * i));
    }}
    check();
    put({offsets['dataIn']}, ~data, lanes);
    {top}_eval(state);
    check(); // No second clock edge, no second update.
  }};
  step(true, all, pattern(17));
  step(false, all, pattern(29));
  for (unsigned i = 0; i < lanes; ++i) step(false, 1U << i, pattern(63 + i));
  step(false, 0, pattern(127));
  step(true, 0, pattern(211));
  step(false, all, pattern(199));
  step(true, all, pattern(3));
  step(false, 1, pattern(23));
  std::printf("GENERATED_AREA_TARGET_PASS lanes=%u ticks=%u two-state\\n", lanes, ticks);
}}
'''


def nested_harness(model: dict, rows: int, cols: int) -> str:
    lanes = rows * cols
    top, offsets = pin_offsets(model, {
        "clock_clock": ("input", 1), "clock_reset": ("input", 1),
        "enables": ("input", lanes), "data": ("input", lanes * 8),
        "output_0": ("output", lanes * 8), "selected": ("output", 8),
        "savedSelected": ("output", 8),
    })
    return f'''#include <cstdint>
#include <cstdio>
#include <cstdlib>
extern "C" void {top}_eval(uint8_t *);
int main() {{
  alignas(16) uint8_t state[{model['numStateBytes']}]{{}};
  constexpr unsigned rows = {rows}, cols = {cols}, lanes = rows * cols;
  constexpr unsigned all = (1U << lanes) - 1;
  uint8_t expected[lanes]{{}}, data[lanes]{{}};
  unsigned ticks = 0;
  auto check = [&] {{
    for (unsigned row = 0; row < rows; ++row)
      for (unsigned col = 0; col < cols; ++col) {{
        unsigned lane = row * cols + col;
        unsigned actual = state[{offsets['output_0']} + lane];
        if (actual != expected[lane]) {{
          std::fprintf(stderr, "tick %u [%u,%u] actual %02x expected %02x\\n",
                       ticks, row, col, actual, expected[lane]);
          std::exit(1);
        }}
      }}
    if (state[{offsets['selected']}] != expected[cols + 1] ||
        state[{offsets['savedSelected']}] != expected[cols + 1]) {{
      std::fprintf(stderr, "tick %u selected %02x saved %02x expected[1,1] %02x\\n", ticks,
                   state[{offsets['selected']}], state[{offsets['savedSelected']}], expected[cols + 1]);
      std::exit(1);
    }}
  }};
  auto step = [&](bool reset, unsigned enable, unsigned seed) {{
    state[{offsets['clock_clock']}] = 0;
    state[{offsets['clock_reset']}] = reset;
    for (unsigned byte = 0; byte < (lanes + 7) / 8; ++byte)
      state[{offsets['enables']} + byte] = uint8_t(enable >> (8 * byte));
    for (unsigned row = 0; row < rows; ++row)
      for (unsigned col = 0; col < cols; ++col) {{
        unsigned lane = row * cols + col;
        data[lane] = uint8_t(seed + 53 * row + 17 * col);
        state[{offsets['data']} + lane] = data[lane];
      }}
    {top}_eval(state);
    if (ticks) check();
    state[{offsets['clock_clock']}] = 1;
    {top}_eval(state);
    ++ticks;
    for (unsigned lane = 0; lane < lanes; ++lane)
      if (reset) expected[lane] = 0;
      else if ((enable >> lane) & 1) expected[lane] = data[lane];
    check();
    for (unsigned lane = 0; lane < lanes; ++lane)
      state[{offsets['data']} + lane] ^= 255;
    {top}_eval(state);
    check();
  }};
  step(true, all, 17);
  step(false, all, 29);
  for (unsigned lane = 0; lane < lanes; ++lane) step(false, 1U << lane, 63 + lane);
  step(false, 0, 127);
  step(true, 0, 211);
  step(false, all, 199);
  step(true, all, 3);
  step(false, 1U << (cols + 1), 23);
  std::printf("GENERATED_AREA_TARGET_PASS rows=%u cols=%u ticks=%u two-state\\n", rows, cols, ticks);
}}
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "out", "nodalc", "firtool", "circt-verilog", "arcilator", "llc", "cxx",
                 "runtime-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--case", choices=tuple(PROFILES), action="append")
    args = parser.parse_args()
    args.out = args.out.resolve()
    if ".work" not in args.out.parts:
        parser.error("generated artifacts must remain under ignored .work")
    if args.out.exists() and any(args.out.iterdir()):
        parser.error("use an empty output directory to preserve prior receipts")
    args.out.mkdir(parents=True, exist_ok=True)
    tools = {name: getattr(args, name.replace("-", "_")).resolve()
             for name in ("nodalc", "firtool", "circt-verilog", "arcilator", "llc", "cxx")}
    runtime = args.runtime_dir.resolve() / "libCIRCTArcRuntime.so"
    for name, path in dict(tools, runtime=runtime).items():
        if not path.is_file():
            parser.error(f"missing {name}: {path}")
    manifest = {"status": "running", "simulation": "two-state, not startup-X qualification",
                "boundary_abi": "legacy", "preserve_constant_loops": True,
                "tools": {n: {"path": str(p), "sha256": digest(p)}
                          for n, p in dict(tools, runtime=runtime).items()},
                "commands": [], "cases": []}
    manifest_path = args.out / "manifest.json"

    def save() -> None:
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label: str, command: list[str | Path], expected: int | None = 0) -> subprocess.CompletedProcess:
        argv = [str(arg) for arg in command]
        result = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        log = args.out / (label + ".log")
        log.write_text(result.stdout)
        manifest["commands"].append({"label": label, "argv": argv, "exit": result.returncode,
                                      "log_sha256": digest(log)})
        save()
        if expected is not None and result.returncode != expected:
            raise RuntimeError(f"{label} exited {result.returncode}:\n{result.stdout[-8000:]}")
        return result

    save()
    repo = Path(__file__).resolve().parents[3]
    for profile in args.case or DEFAULT_PROFILES:
        spec = PROFILES[profile]
        top = spec.top
        source = args.input.resolve() / (profile + ".mlir")
        text = source.read_text()
        if spec.fixture not in text or f'nodal.root.module = @{top}' not in text:
            raise RuntimeError(f"{profile}: expected exact maintained Scala replay")
        if (text.count('"nodal.generate"') != spec.families or
                text.count('"nodal.generated_member"') != spec.selections):
            raise RuntimeError(f"{profile}: actual family/checked selection inventory mismatch")
        gated, circt, sv = (args.out / f"{profile}.{suffix}" for suffix in ("gated.mlir", "circt.mlir", "sv"))
        run(profile + "-gate", [tools["nodalc"], source, "--nodal-gate-default", "-o", gated])
        run(profile + "-lower", [tools["nodalc"], gated,
                                 "--nodal-sequential-to-circt=preserve-constant-loops=true", "-o", circt])
        lowered = circt.read_text()
        if (len(re.findall(r"\bhw\.module\b", lowered)) != spec.families + 1 or
                lowered.count('"sv.generate.for"') + len(re.findall(r"(?m)^\s*sv\.generate\.for\b", lowered)) != spec.families or
                len(re.findall(r"\bhw\.instance\b", lowered)) != spec.families or
                lowered.count("nodal.generated_template") != spec.families or
                len(re.findall(r"\bseq\.(?:compreg|firreg)\b", lowered)) != 1):
            raise RuntimeError(f"{profile}: helper/loop structure collapsed or cloned per lane")
        run(profile + "-export", [tools["firtool"], "--format=mlir", circt, "-o", sv])
        original_sv_hash = digest(sv)
        design = sv.read_text()
        if (len(re.findall(r"(?m)^module\s+", design)) != spec.families + 1 or
                design.count("for (genvar ") != spec.families):
            raise RuntimeError(f"{profile}: actual HDL loop or reusable helper missing")
        entry = {"name": profile, "source_fixture": spec.fixture,
                 "scala_sha256": digest(repo / spec.fixture), "input_sha256": digest(source),
                 "gated_sha256": digest(gated), "circt_sha256": digest(circt),
                 "sv_sha256": original_sv_hash, "configurations": []}
        manifest["cases"].append(entry)
        for dimensions in spec.configurations:
            label = profile + "-" + "x".join(str(value) for value in dimensions)
            imported, llvm, state, obj, driver, exe = (args.out / f"{label}.{suffix}"
                for suffix in ("import.mlir", "ll", "state.json", "o", "cpp", "simulate"))
            overrides = [arg for key, value in zip(spec.parameters, dimensions)
                         for arg in ("-G", f"{key}={value}")]
            run(label + "-import", [tools["circt-verilog"], "--ir-hw", "--top", top, *overrides, sv, "-o", imported])
            run(label + "-arc", [tools["arcilator"], imported, "--emit-llvm", f"--state-file={state}", "-o", llvm])
            models = json.loads(state.read_text())
            if len(models) != 1 or models[0]["name"] != top:
                raise RuntimeError("unexpected arcilator model identity")
            driver.write_text(nested_harness(models[0], *dimensions) if len(dimensions) == 2
                              else harness(models[0], dimensions[0]))
            run(label + "-object", [tools["llc"], "-filetype=obj", "-relocation-model=pic", llvm, "-o", obj])
            run(label + "-link", [tools["cxx"], "-std=c++17", driver, obj,
                                  f"-L{runtime.parent}", "-lCIRCTArcRuntime",
                                  f"-Wl,-rpath,{runtime.parent}", "-o", exe])
            result = run(label + "-simulate", [exe])
            marker = (f"rows={dimensions[0]} cols={dimensions[1]}" if len(dimensions) == 2
                      else f"lanes={dimensions[0]}")
            if "GENERATED_AREA_TARGET_PASS " + marker not in result.stdout:
                raise RuntimeError("simulation did not reach its semantic checks")
            entry["configurations"].append({"dimensions": dimensions,
                "parameters": dict(zip(spec.parameters, dimensions)),
                "artifacts": {p.name: digest(p) for p in (imported, llvm, state, obj, driver, exe)}})
            save()
        if spec.illegal:
            label = profile + "-illegal-" + "x".join(str(value) for value in spec.illegal)
            imported, llvm = args.out / (label + ".import.mlir"), args.out / (label + ".ll")
            run(label + "-import", [tools["circt-verilog"], "--ir-hw", "--top", top,
                                    *[arg for key, value in zip(spec.parameters, spec.illegal)
                                      for arg in ("-G", f"{key}={value}")], sv, "-o", imported])
            invalid = imported.read_text()
            if "NODAL generated family parameter outside verified range" not in invalid or "sim.terminate failure" not in invalid:
                raise RuntimeError("illegal override did not retain its exact reachable range failure")
            result = run(label + "-arc", [tools["arcilator"], imported, "--emit-llvm", "-o", llvm], expected=None)
            if result.returncode == 0 or "failed to legalize operation 'sim.terminate'" not in result.stdout:
                raise RuntimeError("illegal override was not refused for the retained fatal path")
            entry["illegal_override"] = {"parameters": dict(zip(spec.parameters, spec.illegal)),
                "import_sha256": digest(imported),
                "outcome": "reachable emitted range fatal retained; pinned arcilator rejects sim.terminate"}
        if digest(sv) != original_sv_hash:
            raise RuntimeError("emitted SV changed during consumer checks")
        save()
        print(f"PASS {profile}: unchanged SV, two-state semantics", flush=True)
    manifest["status"] = "passed"
    save()


if __name__ == "__main__":
    main()
