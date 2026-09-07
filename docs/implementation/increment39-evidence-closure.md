# Increment 39 — Accepted noise-operator evidence

**Status:** Validated compiler/Verilog-A profile

## Accepted implementation and merge

Implementation PR #128 was accepted at `c22846da1812ef8b30ee8299ad89906bcd5171f2` with source
tree `2ec683ee9179a30cb690a9a4fdeb861ab5073870`. Required Core CI
`34119122192` and dedicated noise qualification
`34119122353` passed on this head. The tested PR merge is
`682d41a7ac316cc335e895e664f83890173a10c5`; its tree matches the accepted head.
All 29 associated PR workflows ultimately passed. Some non-required workflows
finished after the implementation merge; they are not represented as pre-merge
approvals. The required Core CI and dedicated noise qualification passed before merge.

The actual squash merge is `924fd125b7958fc9fadefefd8857f34f58815351`, with tree
`2ec683ee9179a30cb690a9a4fdeb861ab5073870` and preserved `dev` parent
`5e33881b86fcbb9fdd127c58d6582ebfcfa60fea`. Exact post-merge Core CI
`34122571681` and Increment 39
`34122571567` passed. This is the separate
accepted-evidence/roadmap closure; it does not change production compiler behavior.

The immutable [machine-readable record](increment39-accepted-evidence.json) has
SHA-256 `ee082e6f8a0bf2c9b13f48dfbbc77037f0438709b630682d8603f6aa110d9f15`. The manifest references this record, and the repository
checker independently pins its bytes and Increment 38's accepted evidence.

## Review and capability boundaries

The direct implementation-agent review `5131703104` covered the exact accepted
head without blocking findings; no unresolved threads remained. This is
not independent automated review. The automated request returned the account's
review-quota limit in comment `5569652053`. No quota or validation gate was bypassed.

The accepted profile includes white, flicker, and constant table noise; owned
source identity; source sharing; independent same-label calls; PSD units and
proven-domain checks; symbolic power; unchanged zero/unused effects; deterministic
native round trips; target reparse; and rejection without partial HDL. This is
compiler and structural target evidence, not numerical noise simulation.

Transient noise, correlation groups, symbolic/file/logarithmic tables,
event/procedural/equation-region source creation, and general Verilog-AMS remain
deferred. Foundation 153–157 and backend parity 65/72 retain full Scala-local
lexical naming: `noise_N` currently provides collision avoidance, not preservation
of `shared`, `density`, or other Scala binders. Safe expression inlining need not
materialize a separate HDL object, and full alias-metadata retention is not claimed.

## Executed validation and artifact identities

The accepted source passed 130 Scala tests, 130 native CTest tests (including
24 independent noise-call parser cases), 63 native/source matrix cases, 318 compiler
Python tests, and native lint for 32 translation units. The counts overlap and
are not additive. Local retained-tool replay passed all 63 matrix cases and
reproduced `public.va` byte-for-byte.

Canonical `./nodal style check --base-ref origin/dev` and
`./nodal check --online-toolchain --base-ref origin/dev` passed in qualification
`34115288472`. That historical helper's overall result remains failure: its later
packaging step attempted to strip a Python formatter launcher. The permanent PR
qualification `34119122353` passed independently, including artifact packaging.
No temporary helper workflow, payload, or toolchain is in the accepted tree.

| Artifact | ID | Archive SHA-256 |
|---|---:|---|
| Accepted PR source/target | 10018124897 | `1b733ece86215ea3a8ff3ada2a732dff2e23d2cec8d3ab74d90015a299d63920` |
| Exact implementation merge | 10019365081 | `db8f15857809e8324dfd9da694c390333a1997c8328a4b15e48f31924a86cbb2` |

Both downloaded source archives reconstruct to their exact recorded Git trees.
The source-derived MLIR and actual emitted Verilog-A match before and after merge:

| Artifact path | SHA-256 |
|---|---|
| `public.mlir` | `7119efa57a8a9d3e27672d3b8e17d776c4adc4dd666e7c25708e3df46ece8d7d` |
| `public.va` | `3a7bcc95a8fc2a0a6b4556cd87ed921e9cf7b733f1320a48b2f6686edb49a36e` |

## Reproduce the public witness

Backend: `verilog-a`; default checks and safe-inline materialization. Public source:
`examples/continuousTimeApi/src/nodal/increment39fixture/Increment39ConstructionCheck.scala`.
The source SHA-256 is `1f77566eb4cce79980f6a3309442f34e9a1e8b5c36604d8a397d9a472d473ab5`.
Output paths below match the qualified artifact's `public.mlir` and `public.va`.

```sh
./nodal bootstrap
./nodal style bootstrap
./nodal core scala
./nodal core native
mkdir -p evidence
./mill -i examples.continuousTimeApi.runMain nodal.increment39fixture.Increment39ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment39MlirCheck "$PWD/evidence/public.mlir"
out/native/release/bin/nodal-translate --nodal-to-verilog-a evidence/public.mlir > evidence/public.va
python3 tests/compiler/fixtures/increment39/run_native_matrix.py --nodalc out/native/release/bin/nodalc --translate out/native/release/bin/nodal-translate --source evidence/public.mlir
```

## Nodal Scala

This is the public fixture's import and module class, without its runner object.

```scala
import nodal.*

/** Public-only, separately compiled source. PSDs use A^2/Hz = A^2*s. */
final class AnalogNoiseSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val scale = param(2.0.real)
  analog:
    val density = 1.0e-18.A * 1.0.A * 1.0.s
    val shared = whiteNoise(NoiseId("thermal"), density * scale)
    val independent = whiteNoise(NoiseId("thermal"), density)
    val flicker = flickerNoise(NoiseId("flicker"), density, 1.0)
    // Input order is retained; the standard defines sorting and linear interpolation.
    val spectrum = tableNoise(
      NoiseId("spectrum"),
      Seq(
        NoisePoint(1000.0.real / 1.0.s, density),
        NoisePoint(1.0.real / 1.0.s, density * 4.0.real)
      )
    )
    I(positive, negative) <+ shared + shared + independent + flicker + spectrum
    val _ = whiteNoise(NoiseId("zero power"), 0.0.A * 1.0.A * 1.0.s)
```

## Actual generated Verilog-A

The complete emitted `public.va`, including its framework header and includes:

```verilog
/* Nodal backend framework v1
 * profile: verilog-a
 * check-profile: default
 * shaped-layout: scalar-or-flat
 * materialization: safe-inline
 * naming: semantic
 */
`include "constants.vams"
`include "disciplines.vams"

module AnalogNoiseSource(negative, positive);
  inout negative, positive;
  electrical negative, positive;
  parameter real scale = 2;
  real noise_0;
  real noise_1;
  real noise_2;
  real noise_3;
  real noise_4;

  analog begin
    noise_0 = white_noise((1e-18 * scale), "thermal");
    noise_1 = white_noise(1e-18, "thermal");
    noise_2 = flicker_noise(1e-18, 1, "flicker");
    noise_3 = noise_table('{1000, 1e-18, 1, 4e-18}, "spectrum");
    noise_4 = white_noise(0, "zero power");
    I(positive, negative) <+ ((((noise_0 + noise_0) + noise_1) + noise_2) + noise_3);
  end
endmodule
```

The shared expression is evaluated once and referenced twice; the independently
authored source remains distinct despite its matching reporting label. The
symbolic parameter, authored table order, and zero-power effect are preserved.
The header's generic naming policy does not certify Scala-local lexical retention.
