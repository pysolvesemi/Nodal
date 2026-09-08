# Increment 40 - Laplace and discrete transfer operators

**Status:** Implementation in progress; not accepted, not merged, roadmap unchecked.

## Implemented path

The public `laplaceNd` and `ziNd` APIs record typed, versioned transfer states in
the construction snapshot. The Scala bridge emits first-class
`nodal.analog_transfer` operations and a source/state inventory. The native
verifier independently reconstructs dimensions, static coefficient requirements,
nonzero-d0 proof, timing requirements, and definition ownership. The backend
materializes each state once and emits native Verilog-A ND calls. An independent
reparser validates the emitted array/call grammar without accepting arbitrary text.

The exact scope, units, rejection policy, state invariants, and deferred forms
are frozen in [the design gate](../design-gates/NodalTransferOperators-DG-v0.1.md).
The [machine-readable manifest](../../tests/compiler/fixtures/increment40/manifest.json)
is deliberately in-progress. No numerical simulation or stability claim is made.

## Public example and reproduction

The public-only source is
`examples/continuousTimeApi/src/nodal/increment40fixture/Increment40ConstructionCheck.scala`.
It contains a parameterized low-pass filter, shared and independent states, a
cascaded sampled filter, zero/unused states, and omitted/explicit timing arguments.
A representative source excerpt is:

```scala
val gain = param(2.0.real)
val tau = param(1.0e-3.s)
analog:
  val shared = laplaceNd(V(stimulus, reference), Seq(gain), Seq(1.0.real, tau))
  V(filtered, reference) <+ shared + shared
  val discrete = ziNd(shared, Seq(0.5.real, 0.5.real), Seq(1.0.real),
    1.0e-3.s, 1.0e-6.s, 0.0.s)
  V(sampled, reference) <+ discrete
```

Generate the actual source witness and target after building the pinned tools:

```sh
mkdir -p evidence
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment40fixture.Increment40ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment40MlirCheck "$PWD/evidence/public.mlir"
./nodal core native
out/native/release/bin/nodal-translate --nodal-to-verilog-a evidence/public.mlir > evidence/public.va
python3 tests/compiler/fixtures/increment40/run_native_matrix.py \
  --nodalc out/native/release/bin/nodalc \
  --translate out/native/release/bin/nodal-translate \
  --source evidence/public.mlir
```

The dedicated workflow retains `public.mlir`, actual `public.va`, exact source
commit/tree identities, source archive, native tools, and logs. Acceptance closure
must quote the actual emitted output from a successful exact-source run, not a
handwritten expected Verilog-A illustration.

## Review hardening

The first validation exposed an out-of-session degree-eight coefficient fixture
and forged folding metadata on arithmetic above transfer state. The fixture is
constructed inside elaboration, and recursive state-dependency checks now reject
both folding and simplification claims on enclosing expressions.

The source witness also exposed a backend naming gap: `input` is a reserved HDL
keyword. The witness uses `stimulus`, while generic backend checks independently
reject reserved declaration names and cross-kind target namespace collisions
before publishing output. This is rejection, not automatic escaping or lexical
binder retention. Native transfer identities reject padding/control characters
and collisions with other source/state operation kinds.

## Validation and remaining work

Tests are registered in Scala testkit, Python contracts, and CTest, including an
independent native target-parser executable. The dedicated Increment 40 workflow
runs public-source construction, Scala tests, pinned native compilation, native
matrix, target emission, and evidence retention. Core CI remains mandatory.

Remaining: execute and repair the complete CI matrix; review the final patch;
retain exact accepted-head evidence; merge only after required checks; rerun at
the exact merge commit; and perform a separate reviewed evidence closure before
changing the roadmap checkbox. Root/pole representations and numerical simulation
are outside the explicitly documented compiler profile, not silently passing tests.

The stricter shared target-naming check also exposed an older sample-and-hold
fixture that named an unescaped terminal `ground`. Its public source, binding
assertion and independent expected target now use `reference`. This is a
correctness repair to the current regression fixture, not a rewrite of accepted
Increment 37 evidence or a change to event behavior. The historical evidence
records and checksums are unchanged.
