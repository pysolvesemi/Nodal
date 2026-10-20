# Owned reset release witnesses

The public Scala fixtures are `nodal_external.Increment57ResetSource` and
`Increment57MixedResetSource`. `nodal.internal.testkit.Increment57ResetReplay`
emits their actual bridge IR into a caller-selected ignored directory.

`run_target.py` consumes those seven files, lowers them with the current Nodal
compiler, exports SystemVerilog with the pinned CIRCT toolchain, and runs Icarus
assertions. It never substitutes handwritten RTL for generated design output.
The small handwritten testbench checks:

- rising/falling clock edges and active-high/active-low resets;
- unspecified startup before real assertion, immediate assertion/reassertion;
- N-stage release and the consumer's additional edge of old-reset visibility;
- independent child release while the parent clock is stopped;
- an intentional two-plus-three-stage serial cascade;
- distinct mixed-polarity physical roots, either of which holds reset asserted;
- the explicitly retained external provider release obligation.

All generated IR, SystemVerilog, benches, logs and result manifests belong under
ignored `.work/`. Example, after the replay has emitted its seven inputs:

```sh
python3 tests/scala/reset-release/run_target.py \
  --input .work/increment57/reset-live \
  --out .work/increment57/reset-target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

The fixture's existence is not a passing result. Current executed results and
remaining scope are recorded in `docs/implementation/increment57-readiness.md`.
Digital simulation is not physical metastability or silicon timing qualification.

For the required fixed shaped-control slice, the same replay additionally emits
`shaped_single.mlir`, `shaped_combine.mlir`, `shaped_matrix.mlir` and
`shaped_sync.mlir`, plus `shaped_sync_combine.mlir`. Pass
`--include-shaped` to require and run these profiles too. The public
`Increment57ShapedResetSource` uses actual Clock/Reset vector inputs, including a
nonzero scalar index and a matrix row/index chain. Tests drive the generated
packed ports, check that unselected clock edges cannot release state, that
unselected reset elements cannot reset single-root consumers, and that both
distinct composed reset elements can independently assert reset. Shaped files
are not silently skipped when this option is requested.

The two-domain Sync profile uses separate vector elements and opposite clock
edges. It checks edge-only reset assertion and release, and that each reset
element affects only its actual receiving domain.

The all-Sync composition profile checks that either input asserts the composed
reset only on its common receiving edge, without inserting asynchronous assertion
or an unrequested release pipeline. Different-clock Sync composition must reject.
