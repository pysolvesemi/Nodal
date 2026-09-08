# Increment 40 — Accepted transfer-operator evidence

**Status:** Validated compiler/Verilog-A profile

## Accepted implementation and merge

Implementation PR #130 was accepted at `14860e5cfbbaacdc866fdfc6f61f91ffb93d33a1` with source
tree `5cf363573d1df5a7ee8f45cdebaf7344217850bd`. Required Core CI `34185774643`
and dedicated transfer qualification `34185774644` passed before squash merge.
The tested PR merge was `5ae7594b314e5de4c3536bdc8e6bc96433ce49d0`;
its archived tree matches the accepted head.

The actual implementation merge is `e26971903ef808b17feafaaa21b940e4a6494583`, with the same tree
and preserved `dev` parent `ca255253b61bb5b2dae35d9f26b04197cbbbb4c4`.
Exact post-merge Core CI `34190895982` and Increment 40 `34190896012` passed.
Both archives were hash-checked and reconstructed to their recorded Git tree.
Their public MLIR and generated Verilog-A match byte-for-byte.

The non-required Increment 18 run `34185774673` passed its structure checks and
333 compiler tests, then failed its first attempt on GitHub HTTP 403 rate limiting
during online toolchain provenance checks. It was retried without source changes.
Its latest result at this record's creation is `in-progress`.
All-workflows-green-before-merge is not claimed. Required Core CI and dedicated
transfer qualification passed before merge; no protection or check was bypassed.

This separate closure records acceptance and guards it with regression tests;
it does not change production compiler behavior. The immutable
[machine-readable record](increment40-accepted-evidence.json) has SHA-256
`8de26c0bdfe4cba9e0aea79167454a7c298737d2e64a79d6738482d8448ce37f`. The checker pins this record and Increment 39's accepted evidence.

## Review and capability boundaries

The direct implementation-agent review `5137199327` covered the exact accepted
head and recorded no blocking findings or unresolved threads. This is
not independent automated review. The automated reviewer reported exhausted
quota in comment `5574804999`; that comment is not approval.

The accepted profile is `laplaceNd` and `ziNd` numerator/denominator forms;
nonempty immutable coefficient arrays in authored ascending order; analysis-static
real coefficients; scalar symbolic parameter retention; normalized physical units;
proven nonzero denominator d0; positive interval/explicit transition and nonnegative
start; unchanged omitted arguments; and owned state for shared, independent,
cascaded, zero and unused calls. Native verification, recursive false-fold
rejection, deterministic source maps, target name checks, and independent target
reparse reject invalid input without partial accepted HDL.

This is compiler/structural qualification, not numerical analog simulation.
Root/pole representations, symbolic array lengths and vector parameter carriers,
broader d0/timing envelope proofs, d0=0 closed-loop filters, explicit zero
transition, optional Laplace tolerance/initialization, general Verilog-AMS,
synthesis, filter stability and conditioning proofs remain outside v0.1.
Foundation 153–157 retains full Scala-local lexical naming: `transfer_N` denotes
current collision-avoiding state temporaries, not retained `shared`/`discrete`
Scala binders. The coefficient arrays have fixed lengths, not vector HDL parameters.

## Executed validation and artifact identities

Canonical qualification `34184843380` ran the complete pinned
`./nodal check --online-toolchain --base-ref origin/dev`, including style checks.
The accepted source passed 137 Scala tests, 333 Python compiler tests, 132 CTests,
and 182 public-source/native matrix cases. Counts overlap and are not additive.
Current Increment 37–39 source/target compatibility also passed there; historical
acceptance records are unchanged. An older sample/hold fixture intentionally uses
`reference` rather than the reserved declaration name `ground`.
Local replay of the accepted native binaries passed the same 182 cases and
reproduced `public.va` byte-for-byte. Derivative review (91 checks), waveform
optional-arity/negative checks, event (63 checks plus 19 lowering cases), math
(112 cases) and noise (63 cases) predecessor matrices also passed locally.
Local replay is not a separate compiler build; these counts overlap the native
suites and are not additive.

| Artifact | ID | Archive SHA-256 |
|---|---:|---|
| Accepted PR source/target | 10040656445 | `0e2e6b17c506941ed3d5b2a184bd4722fc16a5166bb79f272acc454fc1f98019` |
| Exact implementation merge | 10042500307 | `499d97078801c356746effcaff7247af017867b5a5377cb64050d3827e9189f1` |

Witness hashes, identical in the accepted PR and implementation-merge artifacts:

- `public.mlir`: `a1023e5eb837ec79c95cefe018ff64f65da535ae07b5aa190b950f867bd90927`
- `public.va`: `9e626a7d44bafc4c60a7c904911b42de019f5ab58c0bd4a73a449265fd17066a`

## Public Scala demonstration

Source: `examples/continuousTimeApi/src/nodal/increment40fixture/Increment40ConstructionCheck.scala`.
The excerpt is the complete public module, excluding its standalone runner.

```scala
import nodal.*

final class TransferFilters extends Module:
  val stimulus = inout(Electrical)
  val filtered = inout(Electrical)
  val sampled = inout(Electrical)
  val reference = inout(Electrical)
  val gain = param(2.0.real)
  val tau = param(1.0e-3.s)
  analog:
    val signal = V(stimulus, reference)
    val shared = laplaceNd(signal, Seq(gain), Seq(1.0.real, tau))
    V(filtered, reference) <+ shared + shared
    val discrete = ziNd(
      shared,
      Seq(0.5.real, 0.5.real),
      Seq(1.0.real),
      1.0e-3.s,
      1.0e-6.s,
      0.0.s
    )
    V(sampled, reference) <+ discrete
    // Equal calls own distinct states. Unused/zero filters must not be erased.
    val _ = laplaceNd(signal, Seq(gain), Seq(1.0.real, tau))
    val _ = laplaceNd(0.0.V, Seq(0.0.real), Seq(1.0.real, 1.0e-3.s))
    val _ = ziNd(signal, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s)
    val _ = ziNd(signal, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s, 1.0e-6.s)
```

## Actual generated Verilog-A

Output: `public.va` in the retained artifacts. Header comments alone are omitted
from this excerpt; declarations, identifiers, coefficients and statements are
unchanged.

```verilog
`include "constants.vams"
`include "disciplines.vams"

module TransferFilters(filtered, reference, sampled, stimulus);
  inout filtered, reference, sampled, stimulus;
  electrical filtered, reference, sampled, stimulus;
  parameter real gain = 2;
  parameter real tau = 0.001;
  real transfer_0;
  real transfer_1;
  real transfer_2;
  real transfer_3;
  real transfer_4;
  real transfer_5;

  analog begin
    transfer_0 = laplace_nd(V(stimulus, reference), '{gain}, '{1, tau});
    transfer_1 = zi_nd(transfer_0, '{0.5, 0.5}, '{1}, 0.001, 1e-06, 0);
    transfer_2 = laplace_nd(V(stimulus, reference), '{gain}, '{1, tau});
    transfer_3 = laplace_nd(0, '{0}, '{1, 0.001});
    transfer_4 = zi_nd(V(stimulus, reference), '{1}, '{1}, 0.001);
    transfer_5 = zi_nd(V(stimulus, reference), '{1}, '{1}, 0.001, 1e-06);
    V(filtered, reference) <+ (transfer_0 + transfer_0);
    V(sampled, reference) <+ transfer_1;
  end
endmodule
```

The first call is evaluated once and reused twice. The sampled filter consumes
that same materialized result. Equal independent calls and zero/unused filters
retain separate calls; `gain` and `tau` remain symbolic parameters. This output is
Verilog-A, not synthesizable digital Verilog.

## Reproduction

```sh
mkdir -p evidence
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment40fixture.Increment40ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment40MlirCheck "$PWD/evidence/public.mlir"
./nodal core native
out/native/release/bin/nodal-translate --nodal-to-verilog-a evidence/public.mlir > evidence/public.va
python3 tests/compiler/fixtures/increment40/run_native_matrix.py --nodalc out/native/release/bin/nodalc --translate out/native/release/bin/nodal-translate --source evidence/public.mlir
```

Use the repository-pinned native and lint toolchains. Accepted historical
source/target identities remain immutable when subsequent increments evolve
current fixtures or output. A later extension requires its own qualification.
