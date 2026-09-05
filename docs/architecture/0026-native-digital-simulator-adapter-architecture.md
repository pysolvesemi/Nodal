# ADR 0026: Use compiled-model Verilator and VPI-process Icarus adapters beneath the native Nodal HVL runtime

- **Status:** Accepted
- **Date:** 2026-08-26
- **Extends:** [ADR 0023](0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)
- **Related:** [ADR 0025](0025-generated-procedural-hdl-testbench-projections.md)
- **Scope:** Native Nodal HVL execution, Verification Semantic IR runtime, Verilator, Icarus Verilog, generated C++ models, stable C ABI, JVM native binding, VVP, VPI, shared-memory/IPC transport, scheduling barriers, caching, capability negotiation, and cross-backend parity

## Capability clarification — 2026-09-05

[ADR 0027](0027-hvl-execution-projection-capability-contract.md) defines live versus captured execution and independent generated-profile eligibility. Live host-side Scala does not require complete static capture. Captured common semantics and typed profile extensions are callable in live execution only when their required operations have qualified live implementations. UVM-only and Verilog-TB-only extensions are not automatically supported by native adapters. The adapter architectures below remain unchanged; this clarification does not close Foundation 148 or implement a simulator adapter.

## Context

ADR 0023 established that Nodal HVL and the Verification Semantic IR remain canonical while simulator adapters execute the DUT. The existing roadmap selected Verilator as the primary fast native digital adapter and Icarus as an independent event-driven adapter, but it did not freeze how either simulator connects to the Nodal runtime.

That ambiguity could lead to incompatible implementations, such as:

- treating Icarus as though it translates the DUT into a C++ model;
- exposing Verilator-generated C++ classes directly as a public JVM ABI;
- using slow dynamic VPI lookup as the primary Verilator access path;
- letting a simulator scheduler redefine Nodal HVL process, randomization, transaction, or coverage semantics;
- confusing native Icarus execution with the separate generated standalone Verilog-testbench projection from ADR 0025;
- rebuilding identical Verilator models for every test instead of caching by semantic inputs.

## Decision

Nodal adopts the binding rule:

> **The Nodal native verification runtime executes live host-side HVL and qualified captured components, preserving common semantic identities without requiring all ordinary Scala to become static IR. Verilator supplies a compiled C++ DUT model behind a stable generated C ABI, while Icarus supplies an external event-driven VVP simulation behind a versioned VPI and shared-memory/IPC adapter. Neither simulator defines Nodal HVL semantics.**

The exact public HVL syntax and exact implementation APIs remain deferred to Foundation Increment 148 and the Digital Verification track.

## Common native-runtime ownership

The Nodal runtime owns:

- HVL process creation, suspension, cancellation, fork/join, timeout, and deterministic scheduling intent;
- clock and reset stimulus policy at the verification level;
- canonical random constraints, seed hierarchy, generated value streams, and replay;
- transactions, drivers, monitors, agents, scoreboards, reference-model calls, analysis streams, functional coverage, checks, and source-level failure identity;
- logical Interface/Register endpoint mapping and source correlation;
- capability selection, run manifests, normalized results, and cache orchestration.

A digital simulator adapter owns only the selected simulator execution boundary:

- DUT compilation/elaboration;
- signal read/write access;
- RTL delta/event evaluation and simulator-local scheduling;
- timing/event callbacks supported by the profile;
- waveform production and simulator diagnostics;
- orderly finalization and process/native-resource cleanup.

## Verilator native adapter

### Build model

The required primary fast path is:

```text
Nodal HDL
  -> generated portable Verilog RTL
  -> Verilator-generated C++ DUT model
  -> generated Nodal C ABI wrapper
  -> compiled native shared library
  -> JVM FFI/JNI binding
  -> Nodal HVL runtime
```

Only the DUT and the generated native wrapper are compiled to C/C++. Nodal HVL remains in the Nodal-owned JVM or other approved runtime.

### Stable boundary

Nodal must generate or provide a versioned C ABI covering at least:

- create/destroy/finalize;
- signal metadata and stable logical-to-native IDs;
- width-safe batched reads and writes;
- evaluation and settle barriers;
- simulation time where the selected profile requires it;
- pending/next timed event queries when timing mode is enabled;
- waveform and trace controls;
- normalized errors and capability reporting.

Verilator-generated C++ class names, fields, layouts, and internal APIs are implementation details and are never the public Nodal simulator ABI.

Direct generated-model access through the wrapper is the primary path. Verilator VPI may be offered only as a separately measured compatibility profile; it is not the default data path.

### Build caching

The native model cache key must include at least:

- normalized DUT RTL and wrapper hashes;
- elaborated parameter/configuration identity;
- Verilator version and executable hash;
- language, timing, trace, coverage, optimization, and warning options;
- generated C ABI version;
- compiler, linker, standard library, target platform, and relevant environment identity;
- selected plugins and adapter profile hashes.

Tests sharing one model key reuse the compiled model. A test seed or scenario that does not alter the compiled DUT must not force recompilation.

## Icarus native adapter

### Build and execution model

The required independent event-driven path is:

```text
Nodal HDL
  -> generated portable Verilog RTL
  -> iverilog compilation
  -> VVP simulation image
  -> external vvp process
  <-> versioned Nodal VPI module
  <-> shared-memory or versioned IPC command/event transport
  <-> Nodal HVL runtime
```

Icarus does not need to translate the DUT into a C++ model. Any C/C++ code is limited to the VPI/transport adapter and support libraries.

The initial profile uses an external `vvp` process rather than making `libvvp` embedding a Foundation requirement. A future embedded profile may be qualified separately without changing canonical semantics.

### Adapter requirements

The Icarus adapter must support, according to its declared profile:

- deterministic logical endpoint discovery and binding;
- width-safe batched signal reads and writes;
- value-change, edge, timed, read/write-sync, end-of-time-slot, and finish callbacks as needed;
- four-state and resolved-net value preservation where Icarus supports it;
- explicit simulation advance/run-until commands;
- shared-memory or IPC protocol versioning, framing, cancellation, timeout, crash detection, and cleanup;
- normalized logs, waveforms, exit status, and failure classification.

A simulator crash or malformed adapter message cannot leave an accepted partial result.

## Scheduling and synchronization contract

Nodal and the simulator have separate schedulers. They synchronize only at declared barriers.

A typical barrier cycle is:

1. the Nodal runtime resumes runnable HVL processes;
2. drivers enqueue a deterministic batch of DUT writes;
3. the adapter applies the batch;
4. the simulator evaluates until the selected settle/event/time barrier;
5. the adapter returns changed values and subscribed events with simulator time and ordering metadata;
6. Nodal wakes monitors, scoreboards, checks, coverage, and waiting processes;
7. Nodal selects the next command, event subscription, or time advance.

The frozen contract must define:

- write visibility and batching;
- combinational settle and delta-cycle completion;
- nonblocking-assignment observation points;
- clock-edge sampling regions;
- callback ordering and duplicate coalescing;
- zero-time iteration bounds and diagnostics;
- time-unit/precision conversion;
- timeout, cancellation, reset, finish, and failure precedence;
- simulator-profile differences that cannot be normalized exactly.

Nodal may not claim identical internal scheduler implementation across Verilator and Icarus. It must preserve and compare observable semantic results.

## Distinction from generated Verilog testbenches

Native Icarus execution keeps the Nodal HVL runtime active and connects through VPI/transport.

The ADR 0025 standalone path instead generates a Verilog testbench and optional replay files, then runs DUT plus testbench entirely inside Icarus or another simulator. No live Nodal runtime is required in that mode.

These are separate projections with separate manifests, cache keys, capabilities, and parity evidence.

## Cross-backend parity

Compare the common semantic intersection of each explicitly qualified pair among these modes; no test is required to support every mode merely because it is capturable:

- native Verilator;
- native Icarus;
- generated standalone portable Verilog testbench;
- generated UVM on a qualified simulator.

Parity compares at least:

- deterministic stimulus/value streams;
- logical transaction values and ordering;
- protocol transfers, stalls, resets, and error behavior;
- scoreboard/reference-model decisions;
- checks and source-level failure IDs;
- register operations and predictor results;
- coverage intent and normalized samples where representable;
- termination, timeout, cancellation, and replay results.

Two-state/four-state behavior, event ordering, timing support, random solver choice, and unsupported profile features must be classified explicitly rather than hidden.

## Foundation boundary

Foundation Increment 148 freezes only:

- native runtime ownership;
- the common simulator-adapter contract;
- the Verilator compiled-model/C-ABI boundary;
- the Icarus VVP/VPI/transport boundary;
- scheduling barriers, capability descriptors, manifests, cache identities, normalized results, and parity identities.

Foundation does not implement the complete HVL runtime, Verilator wrapper, JVM native binding, Icarus VPI module, shared-memory transport, standalone testbench generator, UVM generator, or reusable VIP.

## Consequences

### Positive

- Verilator provides the fastest normal native regression path without forcing HVL into C++.
- Icarus provides an independent event-driven and four-state-oriented reference path.
- A stable C ABI shields the JVM runtime from Verilator-generated class changes.
- Shared-memory/VPI isolates Icarus as a simulator process and follows a proven integration model.
- Model caching amortizes Verilator compilation across many tests.
- Native and standalone Verilog-testbench flows remain clearly distinct.
- The same Nodal HVL environment can produce cross-backend evidence for its qualified common semantics.

### Costs

- Nodal must maintain two substantially different native adapters.
- Scheduler barriers and value encodings need careful conformance testing.
- Native C toolchain and platform compatibility become part of Verilator qualification.
- IPC/VPI protocol reliability and process cleanup become part of Icarus qualification.
- Cross-backend parity requires explicit handling of two-state/four-state and scheduling differences.

## Rejected alternatives

### Convert Nodal HVL itself to C++ for Verilator

Rejected because the canonical verification runtime and semantics belong to Nodal, not to generated C++ testbench code.

### Treat Icarus as a C++ compiled-model simulator

Rejected because its normal execution architecture is `iverilog` plus the event-driven VVP runtime.

### Expose Verilator-generated C++ classes directly to Scala

Rejected because generated layouts are not a stable versioned ABI.

### Use VPI as the only Verilator access path

Rejected as the default because direct wrapper access is faster and easier to make width-safe and cacheable. A compatibility profile may still be qualified.

### Embed Icarus through `libvvp` as the only initial profile

Rejected as a Foundation requirement because the external-process VPI boundary is more isolated and portable. Embedded execution may be added later behind the same adapter semantics.

### Merge native Icarus and standalone Verilog-testbench execution

Rejected because one retains a live Nodal runtime and the other is a generated self-contained artifact.
