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

## Controlled lifecycle substrate

`Increment58ControlledFsmTests` additionally captures two independent controlled
instances, validates real private lifecycle storage and canonical `FsmStatus`,
rejects escaped/foreign controls and forged bridge state/event identities, and
requires a configured native executable. It exports `valid.source.mlir`,
`resetless.source.mlir` and `terminal.source.mlir` under
`.work/f58-statecharts/lifecycle/source-native/`.

The same target runner accepts `--profile controlled` for the two-instance
witness and `--profile terminal` for the single initially-terminal witness. For
example, use the command above with the controlled `valid.source.mlir`, an output
under `.work/f58-statecharts/lifecycle/target`, and `--profile controlled`.
These profiles check actual SystemVerilog dormant behavior, pause, explicit
restart, cancel/restart/reset priority, lifecycle events versus ordinary taken
edges, independent state, latched completion, and initially-terminal startup.
They do not claim nested/parallel/timed/recursive statechart completion.

## Reusable required-domain lifecycle

`Increment58RequiredFsmTests` checks one immutable definition in independent
reset-bearing and resetless child occurrences, retained conditional private
initialization, forwarded requirements, material edge/polarity differences,
ordinary-register reset requirements and forged bridge policy rejection.
`Increment58RequiredFsmNativeTests` requires a configured native compiler with
the formal conditional-initialization schema and exports the actual public
source as `mixed.source.mlir`, `material.source.mlir` and
`forwarded.source.mlir` under `.work/f58-statecharts/required/source-native/`.
The full default gate and standalone normalization are separate requests on
that same source, not normalization after attaching a materialization report.

Use the same target command with `--profile required`,
`--profile required-material`, or `--profile required-forwarded`, respectively.
These profiles require real reusable target modules and instances, not a
flattened or manually specialized fixture. The handwritten testbench checks
independent clocks, action storage, paused restart, cancel/restart priority,
terminal hold, synchronous reset, and resetless establishment with restart
asserted and cancel clear. The material profile also uses an actual falling
edge and active-low reset on the second occurrence. No value is asserted for
resetless private state before its accepted restart, and ordinary uninitialized
action storage is not expected to acquire an implicit reset.
