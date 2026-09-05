# Native digital simulator adapters v0.1 plan

**Status:** Normative refinement of the Foundation and Digital Verification roadmaps  
**Date:** 2026-08-26  
**Architecture:** [ADR 0026](../architecture/0026-native-digital-simulator-adapter-architecture.md)  
**Parent verification architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)  
**Parent roadmap:** [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md)

## Capability clarification — 2026-09-05

[ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md) and the [HVL capability plan](nodal-hvl-simulation-v0.1-plan.md) define independent live eligibility and generated-profile capabilities. Ordinary live Scala does not need complete static capture. Captured common semantics plus typed profile extensions execute live only when every required operation has a qualified live implementation. Generated-only UVM or VTB extensions are not automatically native-runtime operations.

Live, CAP, VTB and UVM have independent release gates. Parity covers the qualified common semantic intersection for each selected pair; aggregate parity or commercial-profile completion cannot block an independent live or VTB release. This clarification preserves all deferred implementation states and the adapter architectures below.

## Purpose

The existing roadmap correctly identifies Verilator as the primary fast native simulator and Icarus as an independent event-driven simulator. This plan freezes the missing implementation boundaries so later work does not confuse their fundamentally different execution models.

This plan does **not** add another Foundation increment. It refines unchecked **Foundation Increment 148** and the existing Digital Verification increments. It does not mark any runtime or adapter implementation complete.

## Binding architecture

```text
                         +-----------------------------+
                         | Nodal live HVL / qualified  |
                         | captured components         |
                         +--------------+--------------+
                                        |
                              Nodal native runtime
                                        |
                 +----------------------+----------------------+
                 |                                             |
       Verilator native adapter                    Icarus native adapter
                 |                                             |
 stable generated C ABI + JVM FFI/JNI       VPI + shared memory/versioned IPC
                 |                                             |
 Verilator-generated C++ shared library        external vvp event-driven process
                 |                                             |
        generated Verilog DUT                        generated Verilog DUT
```

A separate sibling path generates a standalone Verilog testbench and runs it entirely inside Icarus or another selected simulator. That path is not the native Icarus adapter.

## Foundation Increment 148 refinement

Foundation Increment 148 must freeze the following contracts before it can be marked complete:

- [ ] **Native runtime ownership**
  - Nodal owns HVL processes, waits, cancellation, timeouts, deterministic seed/replay, transactions, scoreboards, coverage, checks, logical endpoint identity, and normalized results.
  - A simulator adapter owns DUT compilation/elaboration, RTL evaluation, signal access, callbacks, waves, diagnostics, and cleanup only.

- [ ] **Common simulator-adapter contract**
  - Freeze versioned create/elaborate/run/advance/read/write/subscribe/finalize semantics independent of one simulator.
  - Freeze width-safe scalar/vector/four-state value transport, stable logical endpoint IDs, time-unit/precision representation, batched writes, event batches, error taxonomy, cancellation, crash recovery, and deterministic manifests.
  - Keep simulator generated classes, VPI handles, process IDs, sockets, shared-memory names, and native pointers private to adapters.

- [ ] **Verilator compiled-model boundary**
  - Freeze generated Verilog -> Verilator C++ model -> generated stable C ABI -> native shared library -> JVM FFI/JNI as the primary fast path.
  - Freeze create/destroy/finalize, batched reads/writes, evaluation/settle, time/event queries for timing-enabled profiles, trace controls, capabilities, and normalized errors.
  - State explicitly that Nodal HVL remains in the Nodal runtime and is not translated to C++.
  - State explicitly that Verilator-generated C++ class layout is not a public ABI and that VPI is not the default fast data path.

- [ ] **Icarus event-driven boundary**
  - Freeze generated Verilog -> `iverilog` -> VVP image -> external `vvp` process -> versioned VPI module -> shared-memory/IPC transport -> Nodal runtime.
  - State explicitly that Icarus does not require DUT-to-C++ translation; native C/C++ is limited to the VPI/transport adapter.
  - Freeze event/value callbacks, run-until/advance, four-state transport, time-slot barriers, timeout, process-crash detection, cleanup, logs, waves, and exit status.
  - Keep embedded `libvvp` optional and separately qualified rather than required by the initial contract.

- [ ] **Scheduler synchronization contract**
  - Freeze the observable sequence: resume Nodal processes, batch writes, apply writes, evaluate/settle simulator, return ordered event/value batch, resume Nodal monitors/checks, and select the next time/event barrier.
  - Define write visibility, combinational settle, delta completion, nonblocking-assignment observation, edge sampling, zero-time iteration, callback ordering, time conversion, timeout, cancellation, and finish precedence.
  - Require profile-specific classification where Verilator and Icarus cannot provide identical internal scheduling.

- [ ] **Native model and run cache identity**
  - Freeze Verilator model cache inputs: normalized RTL, parameters, wrapper/C-ABI version, Verilator binary/version/options, C/C++ compiler/linker/platform, plugins, traces, timing, coverage, and optimization settings.
  - Freeze Icarus VVP image and adapter cache inputs separately.
  - Prevent test seeds or scenarios that do not alter the compiled DUT from forcing Verilator recompilation.

- [ ] **Parity and evidence identity**
  - Preserve stable test/process/transaction/check/coverage/Interface/Register IDs across native Verilator, native Icarus, standalone Verilog-testbench, and generated UVM runs.
  - Record adapter version, executable/library hashes, commands, capabilities, cache key, source maps, event/value encoding, waves, logs, and normalized outcome.

Foundation remains architecture-only. It does not implement the runtime, native wrappers, VPI module, IPC transport, simulator runners, generated testbench backend, UVM backend, or VIP.

## Digital Verification Increment 1 refinement

### Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice

Implementation must include both native adapters under one Nodal-owned live runtime, with shared semantic identities and qualified support for captured components:

- [ ] **Nodal native runtime vertical slice**
  - Implement deterministic processes/time, clocks/resets, waits, cancellation, timeout, typed endpoint access, failures, waves, seed/replay, and normalized results.
  - Implement logical Interface ABI binding rather than user-visible generated hierarchy strings.

- [ ] **Primary Verilator adapter**
  - Generate portable Verilog for the DUT, invoke pinned Verilator, generate the stable C ABI wrapper, compile/link a native shared library, and bind it through the approved JVM native mechanism.
  - Use direct wrapper access for batched reads/writes and evaluation; do not make runtime VPI lookup the primary path.
  - Implement deterministic model caching and reuse across tests.
  - Support a conservative no-delay cycle/event profile first; add timing-enabled behavior only through a declared capability profile.

- [ ] **Independent Icarus adapter**
  - Compile DUT plus required bridge collateral into a VVP image, launch an external `vvp` process, load the versioned Nodal VPI module, and communicate over shared memory or versioned IPC.
  - Implement width-safe four-state reads/writes, callbacks, run-until/time advance, delta/time-slot synchronization, waves, timeout, crash handling, and cleanup.
  - Keep the Nodal runtime active; do not generate a standalone Verilog testbench for this native path.

- [ ] **Native differential smoke suite**
  - Run the same HVL smoke tests on both adapters for clocks/resets, combinational settle, sequential updates, nonblocking assignments, waits, multiple clocks, inout/high-Z where supported, timeout, finish, waves, and failure source mapping.
  - Classify two-state/four-state and scheduler differences explicitly.

## Digital Verification Increment 7 refinement

Rename the interpretation, not necessarily the stored title, to **standalone open-source Verilog-testbench execution and qualification**.

- [ ] Compile generated DUT Verilog plus generated `tb.v` and deterministic sidecar data with Icarus as the required standalone event-driven reference.
- [ ] Qualify a separate standalone Verilator timing/testbench subset only where supported.
- [ ] Do not route this mode through the native JVM-to-simulator adapters and do not require a live Nodal runtime.
- [ ] Use separate profile IDs, cache keys, manifests, and result classifications from native Verilator and native Icarus.

## Digital Verification Increment 10 refinement

### Native, standalone Verilog-testbench, and UVM semantic parity

Parity compares the explicit common semantic intersection for each qualified pair among these modes. Capturability alone does not require one environment to support all four:

1. native Verilator;
2. native Icarus;
3. generated standalone portable Verilog testbench;
4. generated digital UVM.

Compare:

- canonical deterministic stimulus/value streams;
- transaction and protocol ordering;
- reset, stall, backpressure, error, and register behavior;
- checks, scoreboards, reference models, and source-level failure IDs;
- coverage intent and normalized samples where representable;
- timeout, cancellation, termination, wave, and replay evidence;
- declared two-state/four-state, timing, scheduling, and random-solver differences.

A backend unsupported result must never be reported as parity success.

## Digital Verification Increment 12 refinement

The independent release gates must benchmark and publish separately:

- Verilator code-generation, C++ compilation/link, cold-cache build, warm-cache reuse, native-call batching, evaluation throughput, and memory usage;
- Icarus compile, VVP startup, VPI/shared-memory or IPC latency, event throughput, four-state transport volume, process cleanup, and memory usage;
- standalone Verilog-testbench compile/runtime and vector-file volume;
- UVM compile/runtime and coverage volume;
- many-test reuse of one Verilator model and many concurrent simulator processes;
- supported host OS, CPU architecture, JDK/native-binding, C/C++ compiler, Verilator, Icarus, trace, timing, coverage, and adapter versions.

Publish an explicit capability and limitations matrix rather than one generic “open-source simulator” claim.

## Required negative contracts

The architecture/design gate and later conformance tests must reject:

- exposing Verilator generated class fields as stable public API;
- interpreting Icarus as a DUT-to-C++ compiler;
- silently switching between native Icarus and standalone generated Verilog-testbench execution;
- omitting required four-state behavior under a two-state profile;
- allowing simulator callbacks to execute Nodal HVL semantics outside the Nodal scheduler contract;
- using unversioned shared-memory or socket messages;
- accepting partial results after simulator crash, timeout, malformed message, or native-library failure;
- cache reuse when any semantic build input differs;
- parity success when stimulus streams, checks, or required capabilities differ without an explicit classification.

## Completion rule

This roadmap clarification is complete when ADR 0026, this normative plan, the dependent-track machine-readable gate, and the human verification roadmap all agree on:

- Verilator compiled-model/C-ABI/JVM-native architecture;
- Icarus external-VVP/VPI/shared-memory-or-IPC architecture;
- Nodal-versus-simulator scheduler ownership;
- the distinction between native Icarus and standalone Verilog-testbench modes;
- cache, manifest, failure, and common-subset parity contracts;
- independent live eligibility and generated-profile release gates from ADR 0027.

No implementation checkbox is completed by this documentation change.
