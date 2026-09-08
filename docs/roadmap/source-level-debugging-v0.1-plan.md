# Source-Level and Transaction-Aware Debugging v0.1 plan

**Track ID:** `source-level-debugging`
**Increment prefix:** `DBG`
**Revision:** 0.1
**Created:** 2026-09-08
**Updated:** 2026-09-08
**Status:** Planned; implementation blocked by Foundation
**API status:** Candidate, not frozen or implemented
**Foundation:** [Nodal incremental roadmap](nodal-development-todo.md)
**Gate registry:** [dependent-track-gate-v0.1.json](dependent-track-gate-v0.1.json)
**Machine-readable plan:** [source-level-debugging-v0.1-surface.json](source-level-debugging-v0.1-surface.json)

## Purpose and delivery order

Debug simulated hardware while looking at the original Nodal Scala source, its
logical fields and its selected hardware instance. Start with reliable compiler
provenance and source-aware traces, not a new debugger application. Add live,
read-only digital debugging through a qualified existing runtime where practical.
Add transaction-aware pipeline debugging only after the corresponding Sequential
Scheduled Hardware capabilities are qualified.

This is simulation debugging, not ordinary JVM debugging of the Scala generator,
not sequential execution of concurrent RTL, and not an on-chip FPGA debugger.
Waveforms, assertions, formal checks and readable generated HDL remain useful;
this track connects their evidence to source rather than replacing them.

## Hard Foundation barrier

> No `DBG` implementation increment may start or merge until every Foundation
> checkbox is complete, including every normative Foundation architecture
> extension recorded by the dependent-track gate registry.

The barrier is the complete Foundation track, not only its naming or simulation
increments, not a historical milestone, and not a fixed highest increment number.
Every `DBG` increment inherits it even when its local dependency list is empty.
Research and roadmap refinement are allowed before the barrier opens; they do not
constitute implementation or close any `DBG` checkbox.

Foundation continues to own source locations, semantic identities, naming,
parameter/hierarchy contracts, MLIR transformation seams, simulator/plugin
contracts and verification correlation. `DBG` consumes those contracts and owns
their debugging implementation, adapters, user interfaces and qualification.
Existing Foundation source-map work is not moved into this track or duplicated.

A newly discovered missing architecture contract must return to Foundation as an
explicit architecture/readiness item before dependent implementation proceeds.
Do not smuggle a private workaround into an adapter. Conversely, a finished
runtime, hgdb integration, editor extension, transaction recorder or `DBG` release
is never a Foundation exit criterion. This change adds no new Foundation checkbox,
renumbers no existing increment and changes no existing acceptance evidence.

## Architecture and compatibility rules

1. **Nodal owns debug meaning.** Use a versioned, backend-neutral debug manifest
   produced from the authoritative Nodal MLIR pipeline. Adapters translate that
   manifest; hgdb, an editor protocol and generated signal names must not become
   the compiler's semantic model. Evaluate hgdb reuse before committing to a
   bespoke interactive runtime or a new editor extension.
2. **Preserve origins through transformations.** Retain stable value/operation
   identities, Scala source spans, lexical/helper paths, aliases, logical types,
   signedness, widths, enum encodings, aggregate coordinates, hierarchy and
   symbolic generate context. A source line may map to many operations and many
   instances; it is not a unique hardware program counter.
3. **Describe real visibility.** A requested value is observed, reconstructed,
   constant, optimized-away or unavailable, with a reason and observation point.
   Reconstruction uses a restricted typed expression over captured values and
   exact width/sign/four-state semantics. Never substitute a final value for an
   unavailable earlier intermediate. Do not execute arbitrary Scala to evaluate
   a watch expression or infer values from source spelling.
4. **Bind the actual run.** Resolve symbolic shapes, parameter overrides, generated
   instances and bit slices against the elaborated simulator instance. Record
   source/build/IR/HDL hashes, schema and tool versions, configuration and trace
   identity. Refuse stale or incompatible source-map/HDL/trace combinations.
5. **Keep production behavior unchanged.** Ordinary source APIs, RTL concurrency,
   nonblocking assignment behavior and analog equations do not change. Metadata
   generation must not require keeping every temporary signal. Extra visibility
   or transaction tags require an explicit debug profile, cost report and proof
   that functional behavior, latency and handshakes are preserved. A missing
   observation is not permission to modify production RTL silently.
6. **Reuse the simulator boundary.** Follow the qualified native adapter and
   scheduling contracts in [ADR 0026](../architecture/0026-native-digital-simulator-adapter-architecture.md)
   and the [native adapter plan](native-digital-simulator-adapters-v0.1-plan.md).
   The normal Verilator direct-wrapper/C-ABI access path remains the normal path;
   an hgdb/VPI route, if needed, is a separately qualified opt-in debug capability.
   Do not replace Nodal HVL scheduling, introduce a second scheduler, or make a
   full generated UVM/Verilog testbench a debugging prerequisite.
7. **Make time explicit.** Show simulator timestamp, delta/region or qualified
   observation phase, selected domain/edge, and pre-update versus settled values
   where available. Source-point stepping, clock stepping and transaction-event
   stepping are distinct commands. No single-step UI may imply software ordering
   of concurrent hardware. Clock-edge-only support must be labelled as such.
8. **Keep scope honest.** The first live profile is read-only digital simulation
   on one pinned simulator/backend combination. Source maps for an AMS source do
   not establish analog solver-state debugging. Unsupported capabilities, missing
   captures and two-state/four-state differences must be explicit.

All metadata paths should support project-relative source remapping. Do not embed
credentials or unnecessary absolute host paths in shared debug artifacts.

## Dependencies and ownership

| Work | Required before implementation/acceptance |
| --- | --- |
| Every `DBG` increment | Complete Foundation barrier plus its local `DBG` dependencies. |
| Source-aware recorded traces and failure inspection | Qualified producer metadata and matching recorded digital traces; no live debugger, full Digital Verification release or `SQ` release is required. |
| `DBG-007` through `DBG-009` | A qualified read-only digital simulator adapter with cooperative pause/resume, snapshot and observation-phase capabilities. Reuse the owning Digital Verification/native-adapter slice; its full track is not a prerequisite. The exact capability owner and pinned versions are frozen in `DBG-001` and checked in `DBG-007`. |
| `DBG-010` and `DBG-011` | Sequential Scheduled Hardware `SQ-010` Release A, including its qualified scheduling, stable-anchor and elastic-lifecycle prerequisites. No complete `SQ` track dependency for this release. |
| `DBG-012` advanced scheduled behavior | Sequential Scheduled Hardware `SQ-024`, plus `DBG-011`; each advanced behavior must retain the corresponding `SQ` capability and evidence. |
| `DBG-013` additional simulator/replay profiles | `DBG-009` and a qualified additional adapter or recorded-trace capability; never an initial-release prerequisite. |

The [Sequential Scheduled Hardware plan](sequential-scheduled-hardware-v0.1-plan.md)
owns execution, scheduling, storage insertion, loops, state and effects. `DBG`
only explains and observes them. Sequential compiler acceptance does not depend
on the debugging UI or recorder. Debugger support for ordinary RTL remains
independent of the sequential track.

## Release gates

| Release | Exit | User-visible result |
| --- | --- | --- |
| A — Source-aware evidence | `DBG-006` | Inspect logical fields and source locations in recorded traces and failure reports. This is the first useful release. |
| B — Live digital debugging | `DBG-009` | Read-only source breakpoints, watches and explicit stepping on one qualified simulator, using a thin console/editor client. |
| C — Pipeline transaction debugging | `DBG-011` | Follow one accepted transaction through qualified fixed/valid/elastic sequential pipelines, including stalls and reset epochs. |
| D — Broader qualification | `DBG-014` | Qualified advanced scheduled behavior, additional declared profiles and reproducible end-to-end evidence. |

Each release is independently useful. Releases B, C and D do not block Release A;
advanced loops, additional simulators and replay do not block Releases B or C.

## Independently numbered implementation TODO

All checkboxes below are open. Dependency names are prerequisites, not claims of
current implementation or qualification.

- [ ] **DBG-001 — Debug semantics, scope and reuse design gate**
  - Freeze source/value/instance/time identities, the manifest schema, visibility
    states, debug profiles, pure watch-expression rules and capability reporting.
  - Evaluate an hgdb adapter against the existing simulator/HVL boundary. Record
    exact proposed versions, integration/licensing/maintenance risks, simulator
    access requirements and a bounded fallback decision before runtime work.
  - Name the owning qualified native-adapter capability required by live work.
    Define Release A fixtures and success criteria without requiring an IDE.
  - **Depends on:** complete Foundation only.

- [ ] **DBG-002 — Compiler origin capture and versioned debug metadata**
  - Carry source spans, stable operation/value identities, lexical helper names,
    aliases, logical types, field paths and domain context through the Scala
    bridge into authoritative MLIR; reuse Foundation metadata.
  - Emit a deterministic manifest with schema/build identities and source path
    remapping. Cover helper calls, multiple instances and anonymous expressions.
  - **Acceptance:** repeat builds give equivalent manifests; semantic identities
    do not depend on traversal counters or flattened HDL spelling.
  - **Depends on:** `DBG-001`.

- [ ] **DBG-003 — Transformation-aware mappings and honest value recovery**
  - Update origin relations for inlining, common-subexpression sharing, folding,
    elimination, aggregate lowering, reduction trees and register materialization.
    Preserve one-to-many and many-to-one mappings with explicit reasons.
  - Emit typed observed/reconstructed/constant/optimized-away/unavailable records;
    verify exact finite-width, signed, enum and unknown-bit interpretation.
  - **Acceptance:** source-position-specific intermediate values are correct or
    unavailable, never silently replaced by an unrelated final result. Debug-off
    emission and established compiler semantics remain unchanged.
  - **Depends on:** `DBG-002`.

- [ ] **DBG-004 — Parameterized hierarchy binding and artifact integrity**
  - Bind manifests to actual simulator parameter values, nested generate indices,
    logical aggregates, memories where observable, and selected hardware instances.
    Preserve Nodal's parameterized module policy without debug-driven cloning.
  - Resolve concrete widths/layouts and escaped names through typed mappings;
    reject stale build hashes, impossible slices and ambiguous instance bindings.
  - **Acceptance:** the same source is inspected correctly in differently
    parameterized instances, including minimum and non-power-of-two shapes.
  - **Depends on:** `DBG-003`.

- [ ] **DBG-005 — Source-aware recorded waveforms and value inspection**
  - Add one qualified recorded-trace format first, with format/version/time-unit
    metadata and an adapter boundary for others. Map logical field/index paths,
    enum values and source operations onto captured signals and bit ranges.
  - Show capture coverage and missing/optimized-away data explicitly; preserve
    unknown/high-impedance bits where the producer supports them. Expose a
    library/CLI inspection interface before adding a full graphical client.
  - **Acceptance:** known traces reproduce independently checked source-level
    values and timestamps; incomplete traces do not fabricate values.
  - **Depends on:** `DBG-004`.

- [ ] **DBG-006 — Source-linked failure reports and Release A qualification**
  - Connect existing assertion/property/failure IDs to source span, selected
    instance, run/seed/build identity and the matching trace window. Import only
    qualified existing failure formats; do not build a verification runtime here.
  - Provide source-to-generated-HDL navigation and a minimal inspection report.
    Qualify structured aggregates, helpers, parameter overrides, optimized values
    and wrong-build/missing-capture negative cases.
  - **Acceptance:** a user can investigate a failed recorded test without manual
    flattened-name hunting. Publish measured metadata/trace costs and a small
    reproducible Nodal-source/actual-HDL/recorded-value demonstration.
  - **Depends on:** `DBG-005`. **Release A exit.**

- [ ] **DBG-007 — Existing-runtime adapter and one-simulator feasibility gate**
  - Prototype the Nodal-manifest-to-hgdb route selected at `DBG-001`. Validate
    signal visibility, pause/resume, callbacks, safe observation phases and
    process lifecycle on the exact pinned digital simulator build.
  - Preserve the Nodal scheduler barrier. If an optional VPI path is required,
    isolate and qualify it rather than making it the production access path.
  - **Acceptance:** prove value/instance/source correlation end to end and record
    a go/no-go decision. No broad compatibility claim from an untested tool list;
    failure leaves Release A usable and does not justify a silent architecture
    replacement or unbounded custom debugger work.
  - **Depends on:** `DBG-006` and the qualified read-only digital adapter.

- [ ] **DBG-008 — Read-only live breakpoints, watches and hardware stepping**
  - Add instance-scoped source breakpoints and restricted pure conditional
    expressions, watches, continue/pause, source-debug-point stepping and
    selected-domain clock stepping. Present every stop's exact time/phase.
  - Distinguish path activation from source-line appearance; disambiguate multiple
    instances and multiple operations on one line. Unknown-valued conditions need
    a documented policy rather than accidental host-language conversion.
  - **Acceptance:** demonstrate concurrent blocks, old/new register values,
    nonblocking updates, same-timestamp stops, reset, simultaneous clocks and
    unsupported observation phases without implying sequential RTL execution.
  - **Depends on:** `DBG-007` with an accepted reuse/integration decision.

- [ ] **DBG-009 — Thin console/editor integration and Release B qualification**
  - Reuse the selected runtime's frontend/protocol where practical. Supply a
    minimal console and one supported editor path; keep mappings and simulation
    semantics out of editor-specific code.
  - Show instance, domain, time/phase, value visibility and backend capabilities.
    Qualify launch/attach where supported, disconnect/reconnect, cleanup and
    error reporting without allowing signal forcing or arbitrary evaluation.
  - **Acceptance:** reproduce a source-breakpoint debugging session on the pinned
    first simulator; document limitations and measured slowdown. An entire new
    IDE is not a completion requirement.
  - **Depends on:** `DBG-008`. **Release B exit.**

- [ ] **DBG-010 — Scheduled source-to-stage and state mapping**
  - Consume the qualified `SQ` SSA/value versions, schedule anchors, stage cuts,
    liveness, inserted storage, predicates and protocol metadata. Explain which
    hardware implements an operation and which local values are not registers.
  - Update mappings after qualified scheduling/retiming. Keep original value
    identity separate from stage occupancy and reused physical storage.
  - **Acceptance:** explain the same computation under multiple legal pipeline
    placements without inserting a second scheduler or inferring from net names.
  - **Depends on:** `DBG-006` and `SQ-010`. Live UI additionally needs `DBG-009`.

- [ ] **DBG-011 — Transaction lifecycle tracking and Release C qualification**
  - Follow accepted transactions through fixed, valid-only and elastic profiles;
    account for bubbles, stalls, ready/valid acceptance, backpressure, reset/flush
    and configuration/reset epochs supported by the qualified `SQ` profile.
  - Correlate captured input, intermediate and output values with one transaction.
    Use existing IDs or proven passive inference; otherwise require explicit
    debug-only instrumentation. Never assume queue position proves identity.
  - **Acceptance:** overlapping transactions cannot mix current inputs with an
    older output. Missing history is unavailable, not reconstructed from present
    registers. Demonstrate a stalled result, a bubble and reset under load, with
    a recorded trace; live transaction stepping requires the live capability.
  - **Depends on:** `DBG-010` and `SQ-010`. **Release C exit.**

- [ ] **DBG-012 — Advanced scheduled loops, tasks, memory and ordered effects**
  - Observe qualified iterations, partial unrolling, recurrences, memory accesses,
    shared operators, variable-latency external operations, task/channel joins,
    cancellation and committed effects using their authoritative `SQ` identities.
  - Keep iteration/transaction/epoch identities separate from reused resource IDs;
    explain pending versus committed effects and unsupported capture histories.
  - **Acceptance:** resource sharing, fork/join and variable-latency examples
    preserve identity and causal event ordering under stalls and cancellation.
    Each claimed feature must cite its corresponding qualified `SQ` capability.
  - **Depends on:** `DBG-011` and `SQ-024`.

- [ ] **DBG-013 — Additional simulator and bounded recorded replay profiles**
  - Qualify a second digital adapter only after the first live release; evaluate
    the independent event-driven Icarus profile with its own four-state and
    scheduling evidence. Keep other commercial backends optional.
  - Add backward navigation through captured source points/transactions with an
    explicit capture window. Recorded replay is not reversal of a live simulator.
    Unrecorded values and events remain unavailable; native checkpoint/rewind is
    not promised by this increment.
  - **Acceptance:** compare only the declared common semantic intersection;
    report unsupported capabilities separately from passes and missing evidence.
  - **Depends on:** `DBG-009` and qualified additional adapter/trace capabilities.

- [ ] **DBG-014 — Full-track evidence, cost limits and release documentation**
  - Qualify all declared profiles using source/actual generated HDL/run/trace
    manifests, deterministic mappings and mismatch/availability negative tests.
    Keep the initial digital boundary explicit; no analog solver, on-chip probe,
    force/deposit or unrestricted software-debugger claim.
  - Measure build time, metadata size, trace volume, memory and live slowdown on
    representative designs. Freeze budgets before acceptance and show both
    production/debug profiles with equivalence or scoped behavioral evidence.
  - Publish compatibility and capability tables, troubleshooting, privacy/path
    guidance and actual end-to-end demonstrations for supported releases. Retain
    earlier release gates; later extension failures do not retract valid evidence.
  - **Depends on:** `DBG-006`, `DBG-009`, `DBG-011`, `DBG-012`, `DBG-013`.
    **Release D exit.**

## Deferred scope

Analog/mixed-signal solver-state stepping, analog internal-state reconstruction,
FPGA/on-chip debug insertion, signal force/deposit, arbitrary host evaluation,
unbounded trace retention and true live simulator rewind need separately approved
capability extensions. They are not requirements for Releases A, B or C. The
current track does not change ordinary Scala elaboration debugging.

## Completion and evidence rules

A `DBG` checkbox closes only after its prerequisites, supported capability slice,
implementation and qualification evidence are present. Research prototypes and
this roadmap edit do not close implementation checkboxes. Follow the repository's
[standing completion demonstration rule](../../CONTRIBUTING.md#increment-completion-demonstrations):
show actual Nodal Scala and corresponding generated Verilog-* where applicable,
plus the matching debug artifacts/observations. Label hypothetical illustrations
as such, never as generated evidence.

**This update is documentation only.** No implementation or public API is added,
no existing checkbox is marked complete, and no CI, simulation, formal or synthesis
result is claimed. The current increment does not affect generated Verilog-*.
