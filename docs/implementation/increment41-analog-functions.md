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
