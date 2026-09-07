# Increment 37 — Accepted analog-event implementation evidence

**Status:** Validated implementation; this separate evidence change has its own PR/CI gate.
**Implementation:** PR #124
**Accepted head:** `5684be7a725495cbbbd330d23da4d6c35bf353cb`
**Accepted tree:** `f54a970b62e3f4a9c72d754306ce0739cc69875f`
**Implementation merge:** `2070bf5824b448aa23b917386ef1eccc089f28c7`
**Post-merge Core CI:** `34020356639`
**Post-merge Increment 37:** `34020356658`

The accepted implementation passed all 27 applicable pull-request workflows,
including Core CI `34016929155` and the dedicated event gate `34016929176`.
Independent final-head review reported no major issues for `5684be7a72` in
[review comment 5557516026](https://github.com/pysolvesemi/Nodal/pull/124#issuecomment-5557516026).
All three earlier review conversations were resolved against committed fixes.
The exact implementation merge then passed its own Core CI and Increment 37
workflows. The seven retained source, normalized-IR, and Verilog-A witness files
are byte-identical between the accepted PR run and the exact post-merge run.

The complete run identities, qualification tree, review reference, artifact
checksums, and per-file witness hashes are recorded in
[`increment37-accepted-evidence.json`](increment37-accepted-evidence.json).
This record describes the implemented source and its actual merge, not an
anticipated evidence-PR merge. The separate evidence PR must itself pass its
required checks before merge; no future validation is claimed here.

## Executable coverage

Locked-toolchain qualification `34016438961` validated the same source tree as
accepted head `5684be7a725495cbbbd330d23da4d6c35bf353cb`. Its archived tree was
independently reconstructed and matched exactly. The run passed 117 Scala tests,
127 native CTest tests, 12 separate Scala-to-native bridge tests, and 291 compiler
Python tests, together with the full repository gate and pinned style tools.
The event/source matrix passed 63 checks, the lowering review matrix 19 cases,
and the crossing-direction matrix 52 checks. These suites overlap; the counts
are not an additive total.

Coverage includes event arities and omission, physical dimensions, tolerances,
analysis filters, event composition, controlled statements, captured-read order,
initialized held values feeding continuous `transition`, static monitor histories,
bounded runtime loops, and deterministic before/after-optimization output.
Malformed IR and unsupported target capabilities reject without partial HDL.
The source-to-target witnesses are separately compiled through the public API.

## Review corrections retained

Digital `on(event)` and `lowlevel.process` retain their digital entry path and
waveform restrictions without changing the legacy conditional helper. Analog
controls retain their owned analog-procedure semantics. Both source and native
crossing directions require a proven -1, 0, or 1; parameter defaults and supplied
constant annotations are not accepted as proof.

The scalar target rejects declarations within generated event loops rather than
sharing one module scalar among distinct lexical instances. Root-shared storage
and ordinary loop-local storage within a handler remain supported. The dedicated
negative and positive controls are mandatory native tests.

## Closure and predecessor safeguards

The Increment 37 checker pins the accepted JSON record's SHA-256 and requires
exact agreement with the manifest, human evidence, implementation status, and
roadmap state. Mutation tests reject altered commit and run identities, witness
hashes, missing evidence, premature checkmarks, missing native tests, and changed
capability boundaries. Roadmap revision 1.48 is a lower bound, not an upper bound
that would block future increments.

The Increment 36 checker permits a checked Increment 37 only when the separately
pinned successor evidence and validated manifest match. It still rejects an
unsupported or prematurely checked successor. The accepted Increment 36 evidence
and every historical implementation identity remain unchanged. Historical state
mutation tests explicitly construct open states instead of becoming no-ops when
later increments close.

## Supported profile and deferred work

This closes the supported compiler/Verilog-A event profile. Per-generated-instance
lexical storage remains rejected until represented under Increment 43. Ordinary
event-free procedure lowering, full Verilog-AMS digital processes, numerical
analog scheduling and solver accuracy, and analog/digital co-simulation remain
separately scoped. Structural target acceptance is not a general Verilog-A parser,
numerical simulation, or a solver-equivalence proof. Increment 38 was still open at this historical acceptance; its later completion is recorded separately.
