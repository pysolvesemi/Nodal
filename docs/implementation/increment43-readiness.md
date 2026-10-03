# Foundation 43 readiness and implementation checkpoint

**Date:** 2026-10-01
**Status:** In progress; no F-043 acceptance or completed child is claimed.
**Branch:** `increment/43-analog-arrays-generation`
**Integration target:** `dev` at `e34d17ba2c9adb9e765ee4f0c97699128c224340`
**Integration tree:** `57d402aada6561e1c51ef9b60c49fff193219d8e`

## Authority and predecessor verification

This is the next eligible Foundation increment, not another track. Follow
[AGENTS.md](../../AGENTS.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), the
[main checklist](../roadmap/nodal-development-todo.md), the
[roadmap index](../roadmap/README.md), and the new F-043 descendants in the
[lightweight iteration amendment](../roadmap/lightweight-hierarchy-iteration-v0.1-plan.md).
The main checklist and amendment remain the sole owners of their existing
checkboxes; this document is an applicability/decision record, not another ledger.

F-042 implementation and closure are accepted. F-160 implementation PR #137 and
closure PR #138 are merged. The actual F-160 closure is
`e16ee949f006e0fd44d8f5c8568718f9d0e2548a`, with qualified/merged tree
`1efa8f2184169f69ae6408d79c8ed597ac460a25`. The refreshed target is its direct
documentation-only child, adding the F-097 compact scalar declaration amendment.
Preserve that concurrent amendment; it adds no F-043 prerequisite. F-097 and
F-159 are not started here. There was no existing F-043 branch or open PR at
readiness review; reuse this branch and its draft PR for subsequent work.

## Inspected production owners and shared-layer decision

The review covered the accepted
[ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md),
the [approved iteration gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md),
F-042/F-160 records, and the actual construction, bridge, native and test paths.

`CoreSemanticsCandidateApi.scala` exposes `Dimension`, `Vec`, `generate` and
`loop`, but those particular candidates still use inert generic recording;
their presence is not implementation evidence. `hdlRange` is the approved
preferred future surface. Do not treat the older two-range-only proposal as
its specification or remove exposed compatibility APIs.

`AnalogProceduralConstruction`, `AnalogControlFlowConstruction` and
`AnalogControlFlowRuntime` already own retained procedural/static loops,
lexical scopes, definite-assignment checks and event placement. Their bridge
and native verifiers remain the trust boundaries. An existing `analogRepeat`
is procedural repetition, not topology replication or per-generated-instance
storage; it must not silently acquire either meaning.

The F-160 construction session/records and F-042 hierarchy own component,
parameter, terminal, connection and source identities. Reuse those owners.
A new parallel hierarchy registry, default-value specialization, raw HDL rewrite
or Scala-to-Rust migration is neither needed nor authorized by this increment.

Chosen design: one small neutral iteration-domain representation and arithmetic
owner, consumed by capture and the existing procedural adapter where semantics
match; explicit generated-region/induction ownership in construction records;
independently checked bridge/native contracts; and target-specific legal layout.
Shape remains semantic and structural, never an implicit memory or packed
analog scalar. Keep immutable records separate from session-owned mutable state.

Alternatives rejected: eager host unrolling of symbolic domains; a separate
range engine for analog and digital code; reusing enclosing lexical storage for
generated lanes; inferring staging from body contents; and treating the current
inert candidates as successful production capture. A bounded shared primitive
is preferable to an unneeded framework or wholesale frontend rewrite.

## Applicability of every existing obligation

| Existing obligation | Classification and required result |
| --- | --- |
| F-043.A | Required now: architecture, compatibility, ownership and capability boundary against ADR 0017 and accepted hierarchy. This readiness record alone is not final acceptance. |
| F-043.B / B.1 | Required now: production fixed/symbolic analog shapes, indexing/slicing, ordinary Scala replication and explicit target-visible generation. |
| F-043.B.1.1 | Required now: common literal/symbolic iteration domain and stable generated-object/index identities, independently reusable by F-159 without waiting for digital lowering. |
| F-043.B.2 | Required now: bridge/native shape, static-bound and generated-instance storage ownership checks, including forged input rejection. |
| F-043.B.2.1 | Required now: topology versus procedural effects, conservative terminal indexing, generated lexical state and analog operator-placement restrictions. |
| F-043.B.3 | Required now: legal supported target layouts/generation, symbolic bounds and source/index maps; explicit unsupported cases. |
| F-043.B.3.1 | Required now: actual scalar/generated target counterpart witnesses; no illegal flattening, shared state or accidental memory inference. |
| F-043.C | Required now: positive, negative, empty/singleton, invalid dimension/rank/bound, event-state and predecessor regressions. |
| F-043.D / D.1 | Required now: actual public Scala to normalized IR to Verilog-A compiler witnesses. Internal reparse is not independent OpenVAF compilation. |
| F-043.D.2 | Required now: a concrete handoff of cases, analyses, references and tolerances to F-048 compile and F-049/F-052 numerical owners. Their later executions are not claimed here. Any execution actually required by this increment remains a blocker until available. |
| F-043.E | Required now: proportionate expansion/traversal/output review, preserved symbolic bounds and instance-local state. Optional improvements require measured justification, not a new reverse prerequisite. |
| F-043.F | Required now: nested dimensions, repeated generation, parameter envelopes, deterministic source/index identities and compatibility. |
| F-043.G | Required now: retained evidence, documentation/demonstration, review, final-head qualification, verified integration and any separate closure. |

No existing requirement is removed, narrowed, waived, checked or reclassified
as non-applicable. No checklist correction is necessary for the initial work;
the linked amendment already resolves the preferred range spelling. Digital
mixed-effect lowering/fusion and synthesis/formal evidence remain with
F-055-F-058/F-159. General equation legalization remains F-141. Comprehensive
profiling and optional Rust evaluation remain F-096. These are ownership
boundaries, not excuses to omit analog work required by F-043.

## Implementation sequence and acceptance limits

Start with overflow-safe half-open static-domain arithmetic and integrate it
into an existing real production consumer without changing its staging. Cover
negative and extreme bounds, positive steps, empty and singleton domains,
finite count/last-value limits and failure before invoking an invalid body.
This is a partial implementation checkpoint, not all of F-043.B.1.1.

Next add symbolic bound DAGs and enforced envelopes through the canonical
parameter owner; do not evaluate defaults and discard variability. Capture
induction and generated scopes exactly once, retaining per-lane object/storage
ownership without manufacturing one host object per possible parameter value.
Connect shape/index/slice metadata and generated regions through bridge/native
verification and legal Verilog-A layouts before exposing an accepted profile.

Capture must distinguish an empty legal iteration domain from an illegal
zero-sized shape. Ordinary Scala ranges stay ordinary Scala ranges. Preserve
host evaluation count, deterministic identities and failed-construction cleanup.
Reject unsupported operator placement and dynamic allocation instead of silently
falling back to analog flattening, hidden memories, latency or another backend.

The exact scalar/array capability matrix and emitted target witnesses must be
completed as the implementation becomes coherent. No universal Verilog-AMS,
independent simulator, numerical accuracy, synthesis or speedup claim is made.

## Qualification and continuation

The initial documentation commit uses `[skip ci]`; it does not launch CI.
Publish source checkpoints with the same suppression, run proportional local
checks, and inventory the affected remote workflow IDs/definitions/jobs before
any targeted dispatch. Retain successful exact-head runs and never duplicate
active work. Full CI waits for coherent implementation, successful targeting
and the required completed review. The owner exception in PR #138 was scoped
to that closure; it is not a general review waiver for F-043.

Create exactly one hourly F-043 continuation after the durable branch and draft
PR exist. Reuse it through implementation, targeted repair, review, full CI,
merge and closure. Check the latest PR checkpoint for an active worker before
publishing. The live PR checkpoint owns current head/tree, validation/run IDs,
worker coordination and the next safe action; this file records the stable
readiness decision. Do not reopen or restart completed F-160 work.

All F-043 parent/child checkboxes remain open at this checkpoint. No generated
output or test receipt is fabricated. Later completion requires an actual
Scala/normalized-IR/generated-Verilog-A demonstration and verified acceptance.
