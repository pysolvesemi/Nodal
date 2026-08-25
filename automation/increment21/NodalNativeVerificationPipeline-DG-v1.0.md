# Nodal native verification and pass-pipeline design gate v1.0

**Revision:** v1.0  
**Status:** Approved  
**Scope:** compiler-verification  
**Public API:** unchanged at 0.3  
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 21 establishes the mandatory native semantic gate between parsed Nodal
MLIR and every later lowering, optimization, scheduling, or backend pipeline.
Parsing success is not design acceptance. A candidate design becomes accepted
only after all mandatory stages pass, normalization metadata is committed, and
the complete stage set passes again.

The gate operates on the target-neutral `nodal` dialect introduced by Increments
18 and 19 and on the deterministic Scala bridge inventories introduced by
Increment 20. It does not lower to CIRCT, choose an HDL backend, schedule logic,
or emit HDL.

## Binding staged pipeline

The following stages are always registered and run in this order:

1. `construction` — closed regions, legal semantic roots, unique symbols, and
   supported bridge schema/version.
2. `hierarchy` — module resolution, instance targets, and recursive hierarchy
   rejection.
3. `connectivity` — driver coverage, ordinary multiple-driver rejection,
   explicit latch evidence rejection, and combinational origin-cycle detection.
4. `type-shape` — finite widths, signed/type identity, symbolic shape syntax,
   explicit layout, and `Vec` versus memory storage consistency.
5. `parameter-loop` — parameter binding resolution, nonzero and reachable loop
   steps, bounded hardware iteration, and separation of structural generation
   from hardware iteration.
6. `enum-fsm` — enum resolution, FSM endpoint validity, one reset state, and
   reset-reachability of every state.
7. `domain` — clock/reset domain identity, requirements and bindings, state
   ownership, and explicit CDC/RDC crossing endpoints.
8. `protocol-pipeline` — Interface definition and role resolution, logical ABI
   uniqueness, member-access ownership, and schedule provenance.
9. `memory-effect` — memory type/domain/depth/latency/ordering contracts and
   effect provenance available in the current canonical model.
10. `analog-mixed` — conservative topology category safety and explicit
    mixed-signal bridge provenance.
11. `target-capability` — explicit `core`, `digital`, `analog`, or `mixed`
    capability checks without approximation or silent rewriting.

A stage may use both canonical operations and versioned bridge inventories. A
hand-written MLIR fixture that omits a bridge inventory is still checked by all
operation-level rules; inventory-dependent checks become applicable whenever
that inventory is present. No stage may silently manufacture missing source
facts.

## Pass and analysis contract

- `nodal-verify-stage` runs one named stage or the complete stage list.
- `nodal-gate-check` is the registered read-only all-stage pipeline.
- `nodal-transactional-gate` runs all stages, commits normalized acceptance
  metadata, invalidates prior analyses, and reruns every stage.
- `nodal-gate-normalize` is the registered transactional pipeline.
- Stable diagnostic identifiers begin with `NODAL-VERIFY-` and identify the
  rejecting stage and rule.
- Explicit textual pass pipelines remain suitable for lit/FileCheck use.
- A later pass must declare which analyses it preserves. Any semantic mutation
  after acceptance requires mandatory reverification before another accepted
  artifact can be published.

## Transaction boundary

The gate must not expose a partially normalized or partially verified design.
The transactional pass saves the pre-acceptance attributes and restores them if
post-normalization reverification fails. The native `VerificationSession`
retains the last accepted normalized textual state only after a candidate
passes; a failed candidate cannot replace it. Recovery with the same valid
candidate must reproduce the same accepted text.

## Initial capability boundary

Increment 21 provides meaningful whole-design checks for the semantic facts
already represented by Increments 19 and 20. It does not claim full behavioral
coverage for constructs whose canonical operations or control-flow evidence do
not yet exist. Such facts must either be represented explicitly by a later IR
increment or be rejected as unavailable when a stage requires them. A verifier
must never infer safety from backend output or simulator behavior.

## Required evidence

- Native build, CTest, and unit-test success with the pinned toolchain.
- Positive registered-pipeline and explicit-stage fixtures.
- Negative construction, hierarchy, driver, latch, cycle, storage, loop,
  domain, protocol, memory, analog-category, and target-capability fixtures.
- Stable diagnostic matching and deterministic recovery after failures.
- A transactional session test proving that rejected candidates preserve the
  last accepted state.
- Permanent read-only CI; no source-writing finalizer or repair workflow.

## Explicitly deferred

- CIRCT conversion and target-specific legalization
- Scheduling and optimization passes
- HDL backend selection or emission
- Full behavioral CFG/latch proof beyond represented assignment coverage
- Formal proof, analog equation solving, simulator execution, or synthesis
- Public API changes
