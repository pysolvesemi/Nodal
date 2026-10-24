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

## Timed and transfer-wait witnesses

`Increment58TimedFsmTests` exercises reusable and live timers, positive threshold
boundaries, exact read-only Stream carrier bindings, separate private elapsed
storage, resetless state, contextual initialization and forged bridge rejection.
`Increment58TimedFsmReplay` exports actual public-source inputs for the focused
native and target checks. `required-mixed.mlir` retains one formal definition in
reset-bearing and resetless child occurrences.

The target runner accepts `timed`, `timed-one`, `timed-transfer`, and
`required-timed` for `timer.mlir`, `one.mlir`, `transfer.mlir`, and
`required-mixed.mlir`, respectively. Its handwritten observations check elapsed
cycles, pause, dormant and terminal hold, independent restart, cancel priority,
read-only ready observation, valid/ready stalls and occurrence-specific reset.
No resetless startup value is assumed. Maximum-threshold saturation is checked
in the focused native model rather than simulating billions of target cycles.
These timing witnesses do not establish finite recursion or bounded call-stack
acceptance, which remain separate required F58 work.

`Increment58ComposedTimedFsmTests` and its replay retain two timed child
occurrences under one immutable All-join parent. The `composed-timed` target
profile checks independent two/three-cycle completion, pause after the first
child completes, the registered join, cancellation/restart and reset. It retains
three machine executors and eight actual private registers, not a Cartesian
product state encoding.

## Checked finite graph unrolling (F58)

`FsmDef.unroll(levels, base)(step)` executes 0..127 checked immutable elaboration
steps, never runtime recursion. Direct, named and separately compiled helpers use
one strict callback profile. Read-only `guardSlots`, `actionSlots`, `transferSlots`
and `childSlots` retain exact authored identities for explicit runtime bindings;
transfer implementation guards are not ordinary guard slots.

Run the focused maintained frontend/runtime harness with the existing pinned
artifacts (the same argument meanings as `run.py`):

```sh
python tests/scala/reusable-fsm/run_unroll.py \
  --java "$JAVA_HOME/bin/java" --compiler-classpath "$COMPILER_CP" \
  --api-classpath "$API_CP" --plugin-jar "$PLUGIN_JAR" \
  --out-parent .work/f58-unroll-frontend
```

The maintained source is
`core/scala/testkit/test/src/nodal_external/Increment58UnrolledFsmSource.scala`.
`nodal.internal.testkit.Increment58UnrolledFsmReplay` exports `unrolled.mlir` and
`unrolled-transfer.mlir` to its single output-directory argument under `.work`.
Run `run_target.py --profile unrolled` with the exported `unrolled.mlir` and the
usual pinned target-tool arguments. The simulation checks three actual independent
machine stores, registered joins, pause, cancellation, restart and reset.

Focused production acceptance passed 34 groups (unroll 4, reusable 11, timers 9,
composition 10), 13 frontend/runtime checks including separate producer JARs and
missing-plugin rejection, actual unrolled native gate/CIRCT/SystemVerilog/Icarus
replay, and native gating of typed-transfer source. The compiler SHA-256 was
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
The reusable negative expected-diagnostic text was corrected to match the already
existing foreign guard/action/child rejection; the rejection remains tested.
Receipts are under `.work/f58-statecharts/unroll/promotion/` and
`.work/f58-unroll-shared/`. This is focused prototype evidence, not exhaustive
qualification. Runtime bounded call stacks remain a separate required F58 slice.

## Bounded same-enum runtime procedure stacks

`FsmDef.withCallStack(owner, initial, capacity, overflow, underflow, invalidFrame)`
admits capacity 1..128 and explicit `FsmStackPolicy.AssertHold` or typed `Trap`
policies. `FsmState.call(guard)(entry, resume)` and `returnOn(guard)` share normal
priority/exclusivity, timing, action and lifecycle owners. Return targets are
selected dynamically from private continuation frames, never fake graph edges.
Compact, one-hot and custom local encodings are supported; dynamic Gray returns
are explicitly unsupported. Frames are uninitialized and only the private depth
pointer has reset/conditional-reset initialization.

`Increment58StackFsmTests` provides nine focused source/bridge groups.
`Increment58StackFsmReplay <output-directory-under-.work>` exports trap, hold,
resetless, one, maximum, one-hot, required and composed actual source profiles.
For actual target replay, run:

```sh
python tests/scala/reusable-fsm/run_stack_target.py \
  --profile trap --input .work/stack-source/trap.mlir \
  --snapshot .work/stack-source/trap.construction.json \
  --out .work/stack-target/trap --nodalc "$NODAL_NODALC" \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

Use `required` or `composed` with matching source/snapshot files for the other
profiles. The Trap bench checks nested exact continuations, priority, overflow,
underflow, invalid pointer/frame, terminal and nonterminal traps, active/entry/exit
ordering, pause, cancel/restart, recovery, and pointer reset without frame reset.
The other benches check independent clocks/mixed reset and the registered child
completion join. Only the benches are handwritten; DUT source and output are real.

Native acceptance is 137 FlatFSM plus 27 composition groups. All three actual
waveform profiles pass on compiler
`5ec53036d0ccc2c9f14898e5d6e21c76693651b0dab564a75e0b19236d99375b`.
The AssertHold profile gates/lowers/exports its unchanged sampled assertion, which
pinned Icarus cannot compile. Its failed-operation hold, active actions, events
and lifecycle semantics are tested by the native executor, not claimed as an
Icarus pass. No DUT assertion is disabled. See
`docs/design-gates/NodalSequentialState-DG-v0.10.md`. Shared production Scala
integration passed 43 groups (stack 9 plus 34 directly affected predecessors),
then the integrated artifacts passed 17 frontend checks, eight source profiles
and all three target waveforms again. Receipts are under
`.work/f58-stack-shared/` and `.work/f58-statecharts/stack/shared-accept/`.
These results accept this runtime-stack slice, not the entire F58 parent.
