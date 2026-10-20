# Generated Area target witness

`run_target.py` consumes the actual `lane-registers.mlir` and
`concrete-lane-registers.mlir` outputs of
`nodal.internal.testkit.Increment58GeneratedAreaReplay`, from the maintained
`nodal_external.Increment58GeneratedLaneRegisters.scala` source. It does not
construct or patch a DUT. It runs the full default native gate, the CIRCT target
pass with `preserve-constant-loops=true`, and firtool SystemVerilog export.

The runner requires exactly two module bodies (root and one family helper), one
real `sv.generate.for`, one represented helper instance, and retained template
identity. The symbolic output remains parameterized, independently of the
concrete-loop preservation option. Symbolic `LANES=1,3,8` and concrete `3` are
elaborated by CIRCT-Slang from the **unchanged emitted SystemVerilog**. Arcilator
lowers those DUTs to LLVM. A small C++ stimulus harness derives all pin offsets,
widths and directions from the actual arcilator state map and checks independent
lane enables, old-state hold, lane-zero inversion, other-lane copy, reset priority,
flat output mapping and the selected first-lane alias.

This is a **two-state simulation**, not startup-X testing or four-state
certification. Pinned Icarus 12 rejects the legal named outer generate end label;
the runner never removes that label or rewrites emitted SV. Invalid `LANES=9`
retains the emitted range-check `$fatal` after Slang elaboration; pinned arcilator
refuses its reachable `sim.terminate failure` rather than generating a simulator.
That refusal is checked for its exact cause, not counted as a successful simulation.

Example, using already installed and explicitly selected tools:

```sh
python3 tests/scala/areas/run_target.py \
  --input .work/f58-generated-areas/shared-checkpoint/replay \
  --out .work/generated-area-target-run \
  --nodalc /path/to/frozen/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --circt-verilog .work/circt-toolchain/bin/circt-verilog \
  --arcilator .work/circt-toolchain/bin/arcilator \
  --llc .work/circt-toolchain/bin/llc --cxx /usr/bin/c++ \
  --runtime-dir .work/circt-toolchain/lib
```

Use a new empty `.work` output directory. The manifest records all commands,
tool/runtime hashes, actual replay and maintained Scala source hashes, target
configuration, emitted IR/SV hashes, state maps, harnesses and executables. Each
case rechecks that its emitted SV bytes did not change during consumer runs.
Nested families, generated instances/symbols, guarded external selection,
nonunit-stride adapters, symbolic scalar storage widths and resolved/analog
family endpoints remain separate required target work.
