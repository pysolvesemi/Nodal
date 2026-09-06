# Sequential scheduled hardware: Foundation readiness supplement v0.1

**Status:** Planned architecture-only Foundation extension; all checkpoints open  
**Created:** 2026-09-06  
**Foundation:** [`nodal-development-todo.md`](nodal-development-todo.md)  
**Dependent implementation:** [`sequential-scheduled-hardware-v0.1-plan.md`](sequential-scheduled-hardware-v0.1-plan.md)  
**Gate registry:** [`dependent-track-gate-v0.1.json`](dependent-track-gate-v0.1.json)

## Ownership and exit rule

This supplement records architecture seams needed by the separate Sequential
Scheduled Hardware track. It extends Foundation readiness, not the implementation
scope of that dependent track. Its local checkpoint IDs do not allocate, renumber,
or mark complete any existing global Foundation increment.

Complete every checkpoint below through the relevant Foundation owner or an
explicitly versioned architecture/API amendment. Historical accepted gates and
completion evidence remain unchanged. Planning approval is not API-gate approval.
The entire Foundation barrier, including this architecture-only supplement, must
close before any SQ implementation increment starts. Documentation and research
may proceed now. No SQ implementation checkbox is a Foundation exit criterion;
there is no dependency from Foundation completion back to SQ implementation.

Existing contracts to reuse, not replace:

- Core staging, arithmetic, effects and public API ownership: Foundation 13–15 and
  [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md).
- Pipeline transaction/protocol/schedule ownership: Foundation 59–64 and
  [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md).
- Explicit loop categories: [`signed-loop-api-v0.3-plan.md`](signed-loop-api-v0.3-plan.md).
- Shape and naming closure: [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md).
- Clock/reset/domain, enum/FSM, external-operation, interface and memory contracts
  remain owned by their existing Foundation plans. Preserve the distinction
  between `ExternalModule` and `ExternalOp` in
  [`external-module-integration-v0.1-plan.md`](external-module-integration-v0.1-plan.md).

## Architecture-only checkpoints

- [ ] **SQF-001 — Sequential value semantics and Scala capture boundary**
  - Specify transaction-local variables, immutable value snapshots, ordered
    reassignment, branch merges, aggregate updates, definite assignment and
    loop-carried values. Local assignment must not imply persistent state.
  - Define a typed, source-correlated construction-to-MLIR representation and
    translation obligations to existing numeric, shape and parameter semantics.
  - Compare compile-only Scala 3 capture candidates. An inline method alone does
    not make arbitrary `if`, mutation or `while` hardware. Hardware `Bool` must
    never be converted implicitly to host `Boolean`; dynamic host effects and
    captured host mutation must be diagnosed. Keep a small typed builder fallback.
  - Record how helpers/callback bodies are captured before host execution, or
    rejected. Do not reconstruct semantics from source-text positions or names.
  - Deliver an architecture decision/amendment and positive/negative compile
    contracts; do not implement the SQ frontend or its production lowering.

- [ ] **SQF-002 — Temporal and pipeline extension seam**
  - Reserve transaction, invocation, stage-frontier and latency provenance for
    sequential regions without defining a second pipeline engine.
  - Distinguish a hard value anchor from a whole-live-frontier cut. Specify input
    sampling, field-sensitive liveness, predicate/sideband alignment and nested
    scope composition against Foundation 59–64.
  - Separate fixed-rate, valid-only and elastic contracts; specify fixed/no-stall
    latency, initiation interval, capacity, readiness and actual stalled latency.
  - Reserve immutable scheduling-view and constraint-result interfaces, including
    hard-versus-soft conflicts, deterministic policy identities and mandatory
    legality verification. Never expose mutable MLIR as an ordinary callback API.
  - Deliver interface/schema and ownership decisions only; no new scheduler,
    ready/valid generator, register insertion or resource-sharing implementation.

- [ ] **SQF-003 — Iteration, state and effect extension seam**
  - Preserve existing elaboration/generate/bounded-hardware loop meanings. A
    future temporal loop is a separately selected execution contract, not a
    reinterpretation of `hwRange`, `Reg` or normal RTL assignment.
  - Reserve recurrence distance, iteration identity, resource occupancy, memory
    port/alias/order, external latency/throughput and effect-token information.
  - Specify the separation of transaction locals, iteration-carried storage,
    persistent algorithm state and externally visible effects. Define where
    architectural state becomes visible and which cancellation can still succeed.
  - Reserve bounded task/channel/fork/join/cardinality contracts without creating
    synthesizable equivalents of arbitrary HVL threads or host `Future`s.
  - Deliver capability and verifier seams only; loop scheduling, task execution,
    memories, sharing and side-effect controllers are SQ implementation work.

- [ ] **SQF-004 — Refinement, compatibility and readiness review**
  - Define unscheduled value/effect trace versus scheduled implementation
    refinement, including finite widths, parameter envelopes, stalls, reset,
    cancellation and declared effect-commit points.
  - Specify provenance-preserving reuse of Nodal MLIR and selective CIRCT
    infrastructure. Neither a Scala shadow IR nor one CIRCT dialect is a second
    source of semantics. Preserve ordinary RTL and analog semantics unchanged.
  - Define source-to-value-to-stage/register mapping, stable diagnostics,
    capability reporting and schedule hashes; distinguish planned from supported.
  - Confirm that all required architecture seams have an existing Foundation
    owner or an explicit versioned amendment; record evidence for SQF-001–004.
  - Confirm that no dependent implementation or full HVL runtime has accidentally
    become a Foundation exit requirement. Leave this checkbox open until review
    and actual architecture evidence exist.

## Change boundary

This document was added as roadmap-only work. It does not approve a final syntax,
add implementation, change an accepted API, close Foundation, or claim any test,
formal, synthesis, timing or simulator result. The user authorized a direct branch
update without CI for this documentation change; implementation changes retain
normal repository design-gate and validation requirements.
