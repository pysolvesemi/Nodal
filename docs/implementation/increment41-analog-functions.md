# Increment 41 — User-defined analog functions

**Status:** Validated compiler/Verilog-A profile

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

## Accepted evidence and qualification

Implementation PR #132 was accepted at
`3ef30a40e2f81c5938e37cfbad7ad38c9146c3c2`, tree
`37b88463606400f43e4090a9c631745cd2b88ad6`, after all 31 PR workflows,
required Core CI `35532859513`, and dedicated Increment 41 `35532859216`
passed. It squash-merged as `4d979879d9ee1edd2413068f33ea1a2e4b357e6f`
with the same tree. Exact post-merge Core CI `35538979449` and Increment 41
`35538979384` passed, along with the Increment 36–40 compatibility workflows.

The immutable acceptance record is
[`increment41-accepted-evidence.json`](increment41-accepted-evidence.json),
and the paired Scala/actual generated Verilog-A demonstration is in
[`increment41-evidence-closure.md`](increment41-evidence-closure.md). The
accepted profile remains compiler/structural qualification rather than numerical
simulation or synthesis.

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

The repaired implementation subsequently passed exact-head and exact post-merge
qualification. Its forged-call negatives remain part of the permanent accepted
contract and are pinned by the separate evidence closure.
