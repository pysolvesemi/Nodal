# Immutable reusable FSM focused witnesses

`run.py` uses existing Scala 3.8.4/API/plugin artifacts. It checks a separately
compiled reusable graph and checked action helper, actual binding bytecode,
typed dynamic-capture rejection, mismatched enum tokens, mutable/nested actions,
forged private ingress, missing-plugin action markers and a substituted
uninstrumented producer. It does not download dependencies or launch CI.

`nodal.internal.testkit.Increment58ReusableFsmTests` exercises immutable graph
reuse, distinct actual machine state/guards, graph and binding rejection,
cross-construction ownership, bounded lazy inputs, source/bridge determinism,
custom local encoding and configured native normalization/negative replay.
Set `NODAL_NODALC` to the actual built native executable. An absent native tool
prints a skip and is not native acceptance.

The configured native test exports the actual maintained public source
`core/scala/testkit/test/src/nodal_external/Increment58ReusableFsmSource.scala`
to `.work/f58-statecharts/native/reusable.source.mlir` and the explicit
`RecoverToInitial` variant `reusable-recovery.source.mlir`. `run_target.py` gates
and lowers that unchanged recovery-policy witness through Nodal/CIRCT, exports
SystemVerilog and runs Icarus. Only its testbench is handwritten. It checks
independent start guards, canonical state values despite compact/one-hot local
encodings, transition-before-entry write ordering, active writes, completion,
terminal hold, actual illegal-code recovery and synchronous reset. The default
`Error` witness retains actual native assertions and can be exported to
SystemVerilog, but Icarus does not support its concurrent assertion syntax;
this runner rejects that input rather than disabling those checks. For example:

```sh
python tests/scala/reusable-fsm/run_target.py \
  --input .work/f58-statecharts/native/reusable-recovery.source.mlir \
  --out .work/f58-statecharts/target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

Every generated file and command/result receipt stays under `.work/`. These
focused witnesses cover the first immutable graph/binding slice, not the
remaining F58 nested/parallel/timed/recursive statechart requirements.
