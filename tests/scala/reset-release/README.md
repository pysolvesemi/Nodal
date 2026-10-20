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
