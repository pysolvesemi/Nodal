# Increment 36 — Accepted implementation evidence

**Status:** Validated implementation; evidence-closure PR remains subject to Core CI.
**Implementation:** PR #118
**Accepted head:** `cf0d4504b5463eaad574edd08bd32ccd1ec74e78`
**Implementation merge:** `aa93bc7e9eb6df51162a486452b185025b77207a`
**Post-merge Core CI:** `33951187187`
**Exact post-merge Increment 36 validation:** `33951187157`

The exact accepted implementation head passed all 26 pull-request workflows,
including Core CI `33949981981` and the dedicated Increment 36 run `33949981997`.
The merged implementation then passed both post-merge workflows on its exact
merge commit. The full run inventory is recorded in
[`increment36-accepted-evidence.json`](increment36-accepted-evidence.json).
The repository checker pins that record's SHA-256 and requires the manifest to
match it exactly. The separate evidence-closure PR must itself pass Core CI
before merge; this record does not claim its own future validation.

## Executable coverage

The dedicated gate compiles the Scala source and native compiler, runs the
Scala construction and serialization suites and native CTest suite, and lowers
the actual public Scala witness through MLIR to Verilog-A. Its waveform matrix
covers 27 negative cases on two verifier paths, ten optional-argument forms,
repeatable optimization, unchanged before/after emission, single evaluation of
shared state, retention of unused states and effects, and naming collisions.

## CI provenance reliability

The first Core CI pull-request attempt on the accepted implementation encountered
HTTP 403 rate limits while checking the locked CIRCT/LLVM identities. That failure
was not waived: acceptance requires the successful retry of the same workflow.
The closure also supplies the existing workflow token to the online provenance
step, using authentication already supported by the toolchain checker. The
provenance checks stay enabled, and workflow permissions are unchanged. A
regression rejects missing authentication, the wrong environment variable, and
an incorrect token expression.

## Predecessor compatibility

Validated Increment 33-35 checkers now treat their roadmap revision as a lower
bound instead of an upper bound. Immutable accepted implementation evidence
remains checked. Open and closure-candidate states retain their exact historical
revision requirements. New mutation tests reject missing, duplicate, malformed,
and regressed revisions. Historical transition tests derive the current revision
instead of silently becoming no-ops after a later increment closes.

## Deferred boundary

Numerical solver execution, event-held continuity, general procedural waveform
composition, residual DAE lowering, analysis-specific AC/noise lowering, and full
Verilog-AMS lowering remain deferred. No numerical simulation, analog formal
proof, or simulator-equivalence result is inferred from source/native validation.
Increment 37 remains unchecked. The accepted HVL roadmap refinement is preserved.
