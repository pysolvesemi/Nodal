# Visualizer-Class Interactive Debug and Analysis v0.1 plan

**Track ID:** `visualizer-debug-extension`  
**Increment prefix:** `DBGX`  
**Revision:** 0.1  
**Created:** 2026-09-10  
**Updated:** 2026-09-10  
**Status:** Planned; implementation blocked by Foundation and named `DBG` gates  
**API status:** Candidate, not frozen or implemented  
**Core debugger:** [Source-Level and Transaction-Aware Debugging](source-level-debugging-v0.1-plan.md)  
**Foundation:** [Nodal incremental roadmap](nodal-development-todo.md)  
**Gate registry:** [dependent-track-gate-v0.1.json](dependent-track-gate-v0.1.json)  
**Machine-readable plan:** [visualizer-debug-extension-v0.1-surface.json](visualizer-debug-extension-v0.1-surface.json)

## Purpose

Extend Nodal's source-level debugger into an integrated hardware-debug workspace
with synchronized Nodal source, waveforms, hierarchy, watches, connectivity,
schematics, transactions and causal analysis. The target is Visualizer-class
debugging for Nodal-generated hardware, not a clone of a commercial product and
not a replacement for the core `DBG` track.

The extension should use Nodal's generator-level semantic knowledge instead of
forcing users to debug only flattened generated HDL. A source expression, logical
aggregate field, selected parameterized instance, generated signal, waveform
event and transaction should remain connected by stable identities.

This track owns optional interactive visualization and analysis. It does not own
basic source locations, compiler provenance, simulator scheduling, waveform
production, sequential scheduling or verification semantics. Those remain with
Foundation, `DBG`, Digital Verification and `SQ` as registered below.

## Dependency and non-blocking policy

Every `DBGX` implementation increment inherits the complete Foundation barrier
from the core `DBG` track. It also requires its named `DBG`, `SQ` or simulator
capabilities. Research, interface sketches and roadmap refinement are allowed
before those gates, but they do not close an implementation checkbox.

`DBGX` must not block:

- ordinary Nodal compiler or language releases;
- Foundation completion;
- `DBG-006` source-aware recorded evidence;
- `DBG-009` live source-level debugging;
- `DBG-011` core transaction debugging; or
- production HDL generation when the feature is disabled.

A missing capability discovered here returns to its owning track as an explicit
architecture/readiness item. Do not hide a compiler, simulator or scheduling gap
inside a UI adapter.

## Mandatory opt-in debug profile contract

Debugging is opt-in. The exact public spelling is frozen at `DBGX-001`, but the
candidate command-line model is:

```text
--debug=off       # default production compilation
--debug=source    # core source-level mappings and debugging
--debug=full      # Visualizer-class interactive analyses and metadata
```

Instrumentation that changes generated HDL is a separate explicit choice:

```text
--debug=full --debug-instrument=none
--debug=full --debug-instrument=visibility
--debug=full --debug-instrument=transactions
```

The following rules are mandatory regardless of final spelling:

1. **`off` is the default.** Keep compact source locations needed for normal
   diagnostics, but do not build a debug database, reverse source maps,
   debug-only connectivity graphs, transaction metadata or replay indexes.
2. **Debug passes are optional and lazy.** The normal compiler pipeline must not
   instantiate expensive debug analyses and then discard their results.
3. **No silent RTL instrumentation.** `source` and `full` without an explicit
   instrumentation option must emit byte-identical Verilog-* to `off`.
4. **Instrumented RTL is a debug artifact.** It must be clearly labelled, use a
   separate cache/artifact identity and preserve functional behavior, interfaces,
   latency, reset and handshake semantics.
5. **Measure each profile.** Qualification reports compile time, peak memory,
   debug metadata size, trace size and simulation slowdown. `DBGX-001` freezes
   budgets after representative baseline measurements rather than guessing them.
6. **Keep flags out of semantic code.** Use optional pass pipelines, analyses and
   adapters instead of scattered `if (debugEnabled)` behavior throughout the
   compiler.
7. **Cache safely.** Debug profile, instrumentation profile, manifest schema,
   simulator adapter and source/build hashes participate in artifact identity.
8. **Fail honestly.** Unsupported visibility, replay, four-state, transaction or
   causal-analysis capabilities are reported; the UI must not invent values.

## Architecture direction

```text
Nodal Scala
    |
    v
authoritative Nodal MLIR ----------------------+
    |                                          |
    v                                          v
generated Verilog-*                    versioned Nodal debug DB
    |                                  source/value/instance/time
    v                                  hierarchy/types/origins
qualified simulator                    connectivity/transactions
    |                                          |
    +--------------------+---------------------+
                         v
                 Nodal debug service
                  /       |        \
                 v        v         v
              hgdb     waveform   graph/causal
              adapter    adapter     analysis
                         |
                         v
                integrated debug workspace
       source | hierarchy | watches | waves | schematic
```

Nodal's MLIR and versioned debug database remain authoritative. External tools are
replaceable adapters:

- evaluate hgdb reuse for source-level runtime control;
- evaluate Surfer for embedded waveform and transaction display;
- evaluate CXXRTL/CDSP for optional live/replay transport;
- evaluate Yosys and UHDM only for imported RTL/SystemVerilog structures that
  lack Nodal IR;
- do not make any candidate tool a mandatory compiler dependency before its
  design gate and qualification.

The UI must talk through a versioned Nodal debug service rather than directly
encoding compiler semantics or simulator-specific signal names. Batch and CLI
inspection remain supported even when a graphical workspace is unavailable.

## Capability boundaries

| Capability | Owning prerequisite |
| --- | --- |
| Source/value/instance/time mappings | `DBG-002` through `DBG-006` |
| Live pause, watches, breakpoints and hardware stepping | `DBG-009` |
| Scheduled source-to-stage mapping | `DBG-010` and `SQ-010` |
| Transaction lifecycle identity | `DBG-011` and `SQ-010` |
| Bounded recorded replay | `DBG-013` |
| Advanced scheduled loops/tasks/effects | `DBG-012` and `SQ-024` |
| Simulator execution and observation phases | Qualified native simulator adapter |
| Imported RTL/SV structural model | Independently qualified Yosys/UHDM-style adapter |
| Four-state X causal claims | A qualified four-state simulator/trace profile |

A source-level graph is not automatically a synthesized gate-level schematic.
The workspace must label whether a view represents Nodal operations, lowered RTL,
an imported structural model or a post-synthesis netlist.

## Release gates

| Release | Exit | User-visible result |
| --- | --- | --- |
| A — Integrated source/wave/connectivity workspace | `DBGX-006` | Source, waveforms, hierarchy, watches, drivers/loads and schematics are synchronized for one qualified live digital profile. |
| B — Replay and transaction visualization | `DBGX-008` | Bounded backward navigation and pipeline/transaction timelines are available where the core debugger records sufficient identity. |
| C — Temporal and X root-cause analysis | `DBGX-010` | Explain selected value changes through qualified combinational and sequential history; trace X origins only on declared four-state profiles. |
| D — Broader structural support and qualification | `DBGX-012` | Imported RTL adapters, profile cost limits, capability tables and reproducible end-to-end demonstrations are complete. |

Later releases do not invalidate or block earlier release acceptance.

## Independently numbered implementation TODO

All checkboxes are open. Every increment inherits the complete Foundation barrier
and the local dependencies written below.

- [ ] **DBGX-001 — Extension semantics, profiles and adapter design gate**
  - Freeze the user model for synchronized source/wave/schematic views, selected
    instance and time, graph layer labels, availability states and capability
    reporting.
  - Freeze the semantic debug profiles and final public flag/config spelling.
    Keep `off` as default; separate metadata generation from RTL instrumentation.
  - Evaluate hgdb, Surfer, CXXRTL/CDSP, Yosys and UHDM as replaceable adapters.
    Record exact versions, licenses, maintenance risks and bounded fallbacks;
    candidate listing is not a compatibility claim.
  - Establish representative compile-time, memory, artifact and runtime baselines,
    then approve profile budgets and regression methodology.
  - **Depends on:** complete Foundation and `DBG-001`.

- [ ] **DBGX-002 — Versioned debug service and workspace protocol**
  - Define a backend-neutral query/event protocol for builds, manifests, source
    spans, hierarchy, logical values, simulator time/phases, waveform cursors,
    graph nodes/edges, capabilities and errors.
  - Support local CLI/batch clients and an embedded graphical client without
    moving semantic interpretation into either client.
  - Bind every session to source/build/IR/HDL/trace hashes and reject stale or
    incompatible artifacts.
  - **Depends on:** `DBGX-001` and `DBG-006`.

- [ ] **DBGX-003 — Embedded waveform integration and bidirectional navigation**
  - Integrate one qualified waveform viewer/adapter, with Surfer evaluated first,
    for recorded traces and later live windows.
  - Support source or logical-value selection to waveform signals, and waveform
    signal/event selection back to Nodal source, logical field and instance.
  - Preserve time units, four-state values and capture coverage; unavailable data
    stays explicit.
  - **Depends on:** `DBGX-002`, `DBG-005` and `DBG-006`.

- [ ] **DBGX-004 — Driver/load graph and selected-instance connectivity**
  - Build a lazy debug graph from authoritative Nodal IR and transformation maps.
    Represent drivers, consumers, aliases, ports, instances, registers, memories,
    operations and qualified cross-domain boundaries.
  - Keep parameterized/generate instance bindings concrete while retaining shared
    semantic identities. Provide fan-in/fan-out queries without flattening the
    whole design eagerly.
  - Distinguish Nodal-operation, lowered-RTL and imported-structure graph layers.
  - **Depends on:** `DBGX-002`, `DBG-004` and `DBG-006`.

- [ ] **DBGX-005 — Interactive schematic and logic-cone exploration**
  - Render bounded, expandable schematics from `DBGX-004`; avoid unreadable
    whole-chip graphs by default.
  - Add upstream/downstream cone, boundary collapse/expand, hierarchy navigation,
    source annotations, live/recorded values and selected-time overlays.
  - Preserve one-to-many and many-to-one origin relations; do not draw a false
    one-source-line/one-net model.
  - **Depends on:** `DBGX-004`.

- [ ] **DBGX-006 — Live synchronized workspace and Release A qualification**
  - Combine source, hierarchy, watches, waveform, connectivity and schematic views
    with the read-only live controls qualified by `DBG-009`.
  - Keep time/phase, selected clock domain and pre-update/settled value semantics
    visible during run, pause, continue and hardware stepping.
  - Qualify disconnect/reconnect, simulator termination, large hierarchy browsing,
    multiple instances and parameter overrides. Publish measured `off`, `source`
    and `full` costs and prove non-instrumented HDL identity.
  - **Depends on:** `DBGX-003`, `DBGX-005` and `DBG-009`.
    **Release A exit.**

- [ ] **DBGX-007 — Bounded reverse and replay navigation**
  - Add backward/forward movement through recorded source points, clock edges and
    events inside an explicit capture window. Evaluate CXXRTL/CDSP or the qualified
    `DBG-013` replay transport without making either semantic authority.
  - Synchronize source, watches, waves and schematics at the selected historical
    point. Label replay as recorded navigation, not reversal of arbitrary live
    simulator state.
  - Show history truncation and unrecorded values as unavailable.
  - **Depends on:** `DBGX-006` and `DBG-013`.

- [ ] **DBGX-008 — Pipeline and transaction timeline with Release B qualification**
  - Visualize accepted transactions, stages, bubbles, stalls, backpressure,
    reset/flush epochs and output association using `DBG-010`/`DBG-011` identities.
  - Support source-operation, stage and transaction selection across source,
    timeline, wave and schematic views. Never infer identity from queue position
    when overlapping transactions make that unsafe.
  - Qualify fixed, valid-only and elastic pipelines, including a stall, bubble,
    reset under load and bounded replay.
  - **Depends on:** `DBGX-006`, `DBGX-007`, `DBG-011` and `SQ-010`.
    **Release B exit.**

- [ ] **DBGX-009 — Temporal causal graph and “why this value” analysis**
  - Combine static dependencies with qualified waveform history, register update
    rules, clock/reset domains, predicates and transaction identity.
  - Explain a selected value/event through bounded combinational and sequential
    predecessors across cycles. Separate proven causes, possible contributors and
    missing evidence.
  - Do not claim causality from correlation alone, cross an unqualified CDC or
    reconstruct history outside capture coverage.
  - **Depends on:** `DBGX-005`, `DBGX-007`, `DBG-010` and `DBG-011`.

- [ ] **DBGX-010 — Four-state X root-cause tracing and Release C qualification**
  - Trace X/Z introduction and propagation through supported operations, resets,
    uninitialized storage, mux controls and declared cross-domain boundaries.
  - Require a qualified four-state simulator/trace profile. A two-state backend
    must report this capability unavailable rather than approximate it.
  - Show first known introduction candidates, propagation path, time/phase and
    source/instance context with bounded evidence.
  - **Depends on:** `DBGX-009` and a qualified four-state profile.
    **Release C exit.**

- [ ] **DBGX-011 — Imported RTL/SystemVerilog structural adapters**
  - Add optional Yosys/UHDM-style adapters for hierarchy/connectivity when Nodal IR
    is unavailable. Keep imported nodes clearly separated from Nodal semantic
    nodes and preserve source/file/module identities where the adapter provides
    them.
  - Merge imported and Nodal regions only through explicit instance/port
    boundaries. Report unsupported language, black boxes and lost provenance.
  - Qualify one bounded mixed Nodal/imported-RTL example; do not require this for
    Releases A through C.
  - **Depends on:** `DBGX-005` and an independently qualified structural adapter.

- [ ] **DBGX-012 — Full-track evidence, budgets and Release D documentation**
  - Qualify every declared profile and adapter with reproducible Nodal source,
    actual generated Verilog-*, debug manifests, traces and workspace evidence.
  - Enforce approved `off` overhead, non-instrumented HDL identity, instrumented
    semantic equivalence and per-profile compile/memory/artifact/runtime budgets.
  - Publish capability/compatibility tables, architecture diagrams, troubleshooting,
    privacy/path guidance and limits. Later adapter failures must not retract
    valid earlier release evidence.
  - **Depends on:** `DBGX-006`, `DBGX-008`, `DBGX-010` and `DBGX-011`.
    **Release D exit.**

## Deferred scope

Analog solver-state introspection, analog numerical causality, physical-layout
cross-probing, gate-level timing back-annotation, FPGA/on-chip probes, unrestricted
signal force/deposit, arbitrary host-language evaluation and unbounded live rewind
need separately approved tracks or capability extensions. UVM class/object debug
is not promised by this roadmap.

## Completion and evidence rules

A `DBGX` checkbox closes only when prerequisites, implementation, supported
profile qualification and measured costs are present. Research prototypes and
this roadmap update close no checkbox.

Follow the repository's
[standing completion demonstration rule](../../CONTRIBUTING.md#increment-completion-demonstrations):
show actual Nodal Scala and corresponding generated Verilog-* where applicable,
plus matching debug manifests, traces, screenshots or protocol evidence. When an
increment does not affect generated Verilog-*, state that explicitly. Hypothetical
illustrations are not generated evidence.

**This update is documentation only.** No debugger, UI, compiler flag, adapter or
public API is implemented or frozen; no existing checkbox is marked complete; no
CI, simulation, formal or synthesis result is claimed. The current update does
not affect generated Verilog-*.
