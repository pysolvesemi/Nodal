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
The direct-profile receipt above does not establish broader hierarchy profiles.
The bounded domain continuation below records its own distinct acceptance;
generated Instance/Interface and resolved/analog endpoints remain separate work.

## Positive-step, dependent and guarded domains

`run_domains.py` adds focused maintained source-to-unchanged-SystemVerilog checks
for nonzero lower bounds, positive nonunit steps, empty and maybe-empty families,
independent symbolic lower/upper/step, exact captured/dependent bounds, and real
structural guard scopes. Inputs are the actual replays produced by
`Increment58GeneratedDomainReplay` and `Increment58SelectedDependentAreaReplay`.
Both exporters may write their disjoint profile names into the same fresh
ignored `.work` replay directory. The runner checks exact maintained source path,
root class, authored family inventory, target helper/state/loop inventory,
physical pin map, parameter configurations and actual output waveforms. Only
stimulus is handwritten; emitted MLIR/SystemVerilog is never patched.

Use the same explicit tool arguments as `run_target.py`, replacing its filename
with `tests/scala/areas/run_domains.py`. Select individual profiles with repeated
`--case` arguments, or omit `--case` for this focused domain group. Output must be
a fresh ignored `.work` directory. The manifest retains source, compiler, tool,
unchanged-SV and simulation hashes plus every command.

The maintained profiles are `nonzero`, `nonunit`, `empty`, `maybe-empty`,
`guarded`, `captured`, `captured-nonunit`, `captured-selected`, `guarded-scalar`,
`guarded-symbolic`, `guarded-nested`, `nested-guard`, `guarded-outlined-selected`,
`nonunit-dependent-registers`, `symbolic-domain` and
`symbolic-bounds-fixed-step`. The selected-dependent dynamic test observes
coordinate(2,0) and coordinate(4,4), not ordinal1 mistaken for coordinate1.
Independent symbolic profiles observe `lower` and `lower+step`, with exact
parameter-expression bus cardinality rather than maximum-range allocation.
Nested guards exercise both outer arms and both inner arms at count0/1/4 and
mode0/1. The guarded nested-selection regression retains an external root-family
carrier inside ROWS>1, composes both ordinal prefixes and exercises ROWS1/2/4
with mode0/1. Per-lane enables/data, rising-edge updates, hold and reset are checked;
resetless symbolic state is observed only after its first explicit enabled edge.

One reusable lane helper and one actual loop remain per authored family. Pinned
CIRCT export requires an additional reusable helper for each actual structural
arm containing generated topology, because nested explicit generate wrappers
are not legal SystemVerilog. These are per-arm helpers, never per-lane copies.
Dependent/maybe-empty transport uses bounded internal ordinal carriers only;
canonical guard/prefix existence proof still governs every selected read and
write. Always-empty families do not manufacture an index, i0 bus or driver.
Existing public ABI and nonempty zero-based symbolic widths remain unchanged.

The symbolic operation extension is exact-type bitwise/alias/Bool select only;
new symbolic storage is uninitialized and resetless. This is not symbolic
arithmetic/resize/literal-reset support, generated child-instance/Interface
support, arbitrary negative/zero-step support, or four-state startup
certification. Existing explicit target footprint and arithmetic budgets remain
admission constraints.

Focused integration acceptance: 71 hierarchy target, 157 sequential target and
31 symbolic hierarchy target checks pass against one matched native snapshot.
All 16 maintained source profiles pass 47 legal configurations through the full
Nodal gate, CIRCT, unchanged SystemVerilog, Slang and arcilator. The outlined
nested-selection control retains the root family bus as a helper input and
composes both selected prefixes, rather than hoisting a scalar from its guard.
These are bounded prototype, two-state checks, not exhaustive parameter or
four-state startup certification. Symbolic resolved/array routes, generated
Instance/Interface transport and descending domains remain separate required
continuations.
