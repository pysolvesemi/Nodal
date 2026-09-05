# Increment 35 — Post-merge review hardening

## Scope

This follow-up addresses the three review findings from implementation PR #113
and evidence-closure PR #114. It preserves the accepted Increment 35 evidence
and the subsequent Increment 36 implementation; it does not add solver or DAE scope.

## Corrections

Legacy differential operators reject any continuous-simplification metadata
with `NODAL-ANALOG-035-007`. Plain legacy operators retain their existing typing
and diagnostics. Verilog-A zero rendering additionally requires the versioned
Increment 35 operator contract.

Differential and integral operator ownership is checked against the nearest
`nodal.module`: its canonical `metadata.semantic_path`, when present, otherwise
its symbol name. An owner-qualified operator ID alone is insufficient. Empty
operator suffixes and invalid canonical owner paths are rejected.

The Increment 33, 34 and 35 checkers pin the accepted Increment 35 closure to
head `39915b984707f0396777cc69030dfec29aa2befe` and run `33916159555`.
Coordinated replacement of the manifest and evidence documents cannot substitute
an unrelated SHA or workflow run. Historical acceptance identities are unchanged.

## Regression coverage and validation requirements

`test_increment35_review_hardening.py` checks the real acceptance pair and
coordinated head-only, run-only and paired mutations against all three checkers.

`run_review_matrix.py` is registered with CTest. It invokes the compiled parser,
numeric-verification pipeline and direct Verilog-A backend. Cases cover typed
and legacy-f64 dynamic derivatives with full or partial forged annotations,
valid uncontracted derivatives, incorrect component owners, canonical semantic
paths, empty operator suffixes, and preservation of the approved constant
simplification. Failed backend checks must publish no partial output.

Qualification uses the repository commands, including `./nodal core scala`,
`./nodal core native`, and `./nodal check --online-toolchain --base-ref origin/dev`.
The follow-up is not fully closed until its exact-head checks, review and
post-merge validation succeed. Existing green historical runs do not qualify
these new changes.

## Qualification record

Follow-up PR: [#122](https://github.com/pysolvesemi/Nodal/pull/122).

Source qualification run [33953597456](https://github.com/pysolvesemi/Nodal/actions/runs/33953597456)
passed the full locked-toolchain repository gate before publishing source head
`8c7aeb524fb3e427f43148e0acb8a00e2d0eac27`, tree
`612c1b042c8ca1d631869eb8beed1f973cbab2bb`. This included full Scala compilation
and tests, 270 compiler Python tests, 125 native CTest tests, 12 bridge tests
against the built compiler, and pinned formatting and clang-tidy checks.

Integration qualification run [33954466863](https://github.com/pysolvesemi/Nodal/actions/runs/33954466863)
preserved accepted `dev` commit `f6e11c5b3f92ee43b4a6d4fc6af21d478249b961`,
including Increment 36 closure and the latest HVL dependency guidance. It
resolved only the Increment 33/34 checker conflicts, retained the exact closure
pins in all three checkers, and passed 284 compiler Python tests, 42 HVL roadmap
tests, predecessor checks, Markdown and contribution-policy checks. Published
integration head: `dfaaf8c7f8135226fcc9eb836d0ea272619bd447`; tree:
`2107aacf22b2b155262278b93c0e4e90d02c0da0`.

The runtime/compiler corrections and new native matrix remained byte-identical
during integration. No publication helper or workflow is included in the PR.
The final recorded-evidence commit still requires its own PR checks and review;
its merge and post-merge results are tracked in PR #122 rather than claimed here
in advance.
