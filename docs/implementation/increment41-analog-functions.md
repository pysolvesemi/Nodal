# Increment 41 — User-defined analog functions

**Status:** Implementation in progress; not evidence-closed or merged.

## Implemented path

The public factory records a detached pure scalar body, verifies its return, then
publishes the immutable definition transactionally to its owning elaboration.
The bridge serializes typed, isolated SSA definitions and explicit module-local
calls. Native verification independently checks types, dimensions, dominance,
locals, return coverage, constant domains, ownership, purity and recursion.
The Verilog-A backend emits real/integer native function declarations and calls,
then reparses the emitted subset before accepting output.

The exact supported profile and deliberate limits are in
[the design gate](../design-gates/NodalAnalogUserFunctions-DG-v0.1.md). This is a
compiler/Verilog-A feature, not evidence of numerical simulation or synthesis.
The machine-readable state is
[`manifest.json`](../../tests/compiler/fixtures/increment41/manifest.json).

## Source witness and reproduction

The public-only example is
`examples/continuousTimeApi/src/nodal/increment41fixture/Increment41ConstructionCheck.scala`.
Its six declarations cover a dimensioned affine amplifier, nested limiting call,
Integer arithmetic, mixed-kind argument signatures, real-only literal division,
and mathematical expressions. An ordinary transfer operator consumes a function
result without moving state into its definition.

```scala
val affine = AnalogFunction("affineSignal", Real, PhysicalDimension.Voltage): f =>
  val signal = f.input("signal", Real, PhysicalDimension.Voltage)
  val factor = f.input("factor", Real)
  val offset = f.input("offset", Real, PhysicalDimension.Voltage)
  val scaled = f.local("scaled", signal * factor)
  scaled + offset

analog:
  V(amplified, reference) <+ affine(V(stimulus, reference), gain, 0.0.V)
```

The excerpt illustrates the public API; generated target output must come from
qualification, not a handwritten expected example. Reproduce with pinned tools:

```sh
mkdir -p evidence
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment41fixture.Increment41ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment41MlirCheck "$PWD/evidence/public.mlir"
./nodal core native
out/native/release/bin/nodal-translate --nodal-to-verilog-a evidence/public.mlir > evidence/public.va
python3 tests/compiler/fixtures/increment41/run_native_matrix.py \
  --nodalc out/native/release/bin/nodalc \
  --translate out/native/release/bin/nodal-translate \
  --source evidence/public.mlir
```

## Validation and outstanding acceptance

Scala tests, Python contract/mutation tests, native compiler cases, and an
independent C++ function-parser suite are registered in permanent test targets.
The dedicated read-only workflow retains exact source/tree identities, source
archive, source-map witness, generated `public.va`, native tools and logs.

The roadmap remains unchecked. Required exact-head Core CI and dedicated
qualification, implementation review, merge, exact post-merge validation, and a
separate accepted-evidence record remain outstanding until demonstrated. This
implementation note makes no assertion that an unexecuted test has passed.

## Compiler-boundary review hardening

Qualification of tree `5e18ffa1f2586b026589dfa45f16647b6a52b47d` passed the
original 45-case native/public-source matrix, 134 CTest targets and 146 Scala
tests. A subsequent direct review found a missing negative case: discardable
`kind = "literal", value = 7.0 : f64` attributes on a nested user call were
accepted, and the target renderer replaced that call with `7.0`. Successful
syntax reparse did not detect this semantic substitution.

The repair rejects body-value discriminator/payload attributes on calls using
`NODAL-ANALOG-041-002`. Constant analysis and target rendering also dispatch on
the actual operation identity, never a call's arbitrary `kind` attribute. The
matrix adds 16 negative cases for nested and ordinary calls, with each case run
through native verification, the optimization pipeline, and target emission.
The tests require diagnostic rejection without publishing partial HDL.

This repair requires fresh exact-head qualification. The earlier successful run
is regression evidence, not acceptance of the repaired implementation. The
roadmap and accepted-evidence manifest remain open.
