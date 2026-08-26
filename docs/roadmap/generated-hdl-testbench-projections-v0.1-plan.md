# Generated HDL testbench projections v0.1 feasibility and roadmap plan

**Status:** Normative dependent-track extension
**Date:** 2026-08-26
**Canonical verification architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)
**Direct HDL projection architecture:** [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md)
**Roadmap owner:** [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md)

## Purpose

Reserve a minimal Foundation seam now so a future Nodal HVL environment can be used in all of these ways without testbench-source duplication:

1. execute directly through the Nodal native runtime and open-source simulator adapters;
2. generate a standalone portable Verilog testbench for generated Verilog RTL;
3. generate a standards-oriented Verilog-AMS testbench when the selected AMS simulator supports it;
4. generate digital UVM for commercial SystemVerilog simulators;
5. generate UVM-MS for qualified commercial AMS simulators.

This plan adds architecture and roadmap only. It does not implement a generator, runtime, simulator profile, or VIP.

## Feasibility decision

The overall plan is feasible, with one important qualification:

> **Generated procedural HDL testbenches are additional capability-limited projections of the canonical Verification Semantic IR. They are not replacements for native Nodal HVL, UVM, or UVM-MS.**

### Target matrix

| Nodal source | Generated or executed form | DUT artifact | Primary tool class | Decision |
| --- | --- | --- | --- | --- |
| Nodal HVL | Native runtime | Generated Verilog RTL | Verilator/Icarus | Required open digital path |
| Nodal HVL | Portable Verilog testbench | Generated Verilog RTL | Icarus; qualified Verilator subset | Add |
| Nodal HVL | UVM/SystemVerilog | Generated Verilog or SystemVerilog RTL | Commercial simulators | Retain |
| Nodal HVL | Native/open AMS harness | Generated Verilog-A plus digital Verilog and solver collateral | OpenVAF/ngspice/co-simulation adapters | Required open AMS path |
| Nodal HVL | Verilog-AMS testbench | Generated Verilog-AMS DUT | Capability-qualified AMS simulators | Add, but do not promise general open-source execution |
| Nodal HVL | UVM-MS | Generated Verilog-AMS/SystemVerilog/mixed DUT views | Commercial AMS simulators | Retain |

## Why portable Verilog testbench generation is practical

The required digital target is a conservative Verilog-2005 procedural subset. It can express clocks, resets, directed stimulus, timing/event waits, static tasks/functions, vector-file replay, output checks, error counters, waveforms, and deterministic termination.

Features outside that subset are handled explicitly:

- constrained-random values may be precomputed from the Nodal random model and stored in a replay file;
- expected results may be precomputed where the canonical reference model permits it;
- a target-specific companion runtime is allowed only in an explicitly selected non-standalone profile;
- an unsupported feature fails generation rather than being removed.

Icarus is the required event-driven reference profile. Verilator is qualified independently because timing, four-state, inout, scheduling, class, and coverage behavior does not exactly match an event-driven simulator.

## Why Verilog-AMS testbench generation is language-feasible

Verilog-AMS supports mixed structural hierarchy, behavioral analog content, digital event-driven content, terminals/disciplines, contributions, events, and top-level simulation composition. A generated mixed-signal testbench module is therefore valid language architecture.

Current open-source tools do not provide one general full-Verilog-AMS 2023 execution path:

- OpenVAF accepts the Verilog-A analog subset and not digital models;
- ngspice loads OpenVAF-generated OSDI models into SPICE/XSPICE harnesses;
- ngspice supports digital/mixed co-simulation paths, but those are not equivalent to compiling an arbitrary Verilog-AMS testbench;
- Verilator's Verilog-AMS support is only a very small parsing subset and provides no analog solver.

Therefore the roadmap keeps two distinct AMS outputs:

1. a standards-oriented Verilog-AMS testbench projection for capable simulators; and
2. an open AMS harness projection using supported Verilog-A/OSDI, SPICE/XSPICE, digital Verilog/co-simulation, and normalized result adapters.

A capability probe must succeed before Nodal labels a generated Verilog-AMS testbench executable with an open-source profile.

## Minimal Foundation reservation

### Foundation Increment 152 — Direct procedural HDL testbench projection architecture readiness

Foundation Increment 152 must freeze, but not implement:

- one Procedural HDL Testbench IR lowering seam beneath the canonical Verification Semantic IR;
- profile-independent identities for test, process, endpoint, transaction, check, coverage item, measurement, waveform, and termination;
- capability classification of each Verification IR operation as embedded, precomputed replay, companion-runtime-required, or unsupported;
- a portable Verilog-2005 testbench profile boundary;
- a standards-oriented Verilog-AMS 2023 testbench profile boundary;
- deterministic vector/expected-result sidecar formats and artifact hashes;
- source maps, logical Interface/Register bindings, run manifests, pass/fail protocol, and normalized results;
- Icarus-as-reference and Verilator-as-qualified-subset policy;
- the distinction between a full Verilog-AMS testbench and an open AMS harness;
- simulator-adapter/plugin conformance requirements and cross-backend parity identities.

Foundation Increment 152 explicitly does not implement:

- Verilog or Verilog-AMS rendering;
- replay-file generation;
- Icarus, Verilator, ngspice, or commercial runners;
- analog/digital co-simulation;
- UVM or UVM-MS generation;
- reusable verification libraries.

## Digital Verification roadmap changes

The Digital Verification track expands from 10 to 12 increments.

### Digital Verification Increment 6 — Portable Verilog testbench generation

Implement the Procedural HDL Testbench IR lowering and portable Verilog renderer for self-contained directed tests and deterministic vector/replay tests. Generate DUT wrappers, clocks/resets, flattened Interface helpers, tasks/functions, monitors/checks, error counters, wave controls, source maps, manifests, and reproducible run inputs.

### Digital Verification Increment 7 — Open-source Verilog testbench execution and qualification

Make Icarus the required event-driven execution profile. Qualify a declared Verilator subset with timing, four-state, inout, event, waveform, file-I/O, timeout, and termination differential tests. Reject unsupported profile combinations before run.

### Digital Verification Increment 8 — Verification SystemVerilog and digital UVM generation

Retain the current UVM generation scope, renumbered after direct Verilog support.

### Digital Verification Increment 9 — Commercial simulator profiles

Retain the current VCS/Questa/Xcelium-family qualification scope.

### Digital Verification Increment 10 — Native, Verilog-testbench, and UVM semantic parity

Compare the same Nodal tests across all three projections, including deterministic replay, transaction ordering, checks, scoreboards, register behavior, coverage intent, termination, and source-level failure identities.

### Digital Verification Increment 11 — Reusable digital VIP qualification

Generate native BFM/agent behavior, portable Verilog testbench collateral where expressible, and UVM VIP from one Nodal VIP source.

### Digital Verification Increment 12 — Scale, performance, compatibility, and release gate

Add procedural-testbench generation/runtime scale and publish an HVL/native/Verilog-testbench/UVM/tool capability matrix and conformance kit.

## AMS Verification roadmap changes

The AMS Verification track expands from 10 to 12 increments.

### AMS Verification Increment 6 — Standards-oriented Verilog-AMS testbench generation

Generate a capability-declared Verilog-AMS top-level testbench from the same Verification IR: hierarchy, analog sources/contributions, digital control, bridge/connect metadata, events, measurements, tolerances, checks, termination, source maps, and manifests.

### AMS Verification Increment 7 — Open-source AMS harness generation and capability-qualified execution

Generate the practical open harness bundle using supported Verilog-A/OSDI models, SPICE/XSPICE stimulus and analyses, digital Verilog/co-simulation collateral, replay files, and normalized results. Separately permit generated Verilog-AMS testbench execution only for an adapter that passes the required capability probe.

### AMS Verification Increment 8 — UVM-MS generation from Verification IR

Retain the current UVM-MS 1.0 scope, renumbered after direct HDL/open harness support.

### AMS Verification Increment 9 — Commercial mixed-signal simulator profiles

Retain the current VCS/Questa/Xcelium-family AMS/UVM-MS scope and also qualify generated Verilog-AMS testbenches where supported.

### AMS Verification Increment 10 — Native, open-harness, Verilog-AMS-testbench, and UVM-MS parity

Compare transactions, analog stimulus, event timing within tolerance, measurements, scoreboards, register/control behavior, coverage intent, termination, and source-level failure IDs across every selected capable projection.

### AMS Verification Increment 11 — Reusable mixed-signal VIP qualification

Generate native/open harness behavior, Verilog-AMS collateral where supported, and UVM-MS VIP from one Nodal mixed-signal VIP source.

### AMS Verification Increment 12 — Scale, portability, and release gate

Exercise multiple analog islands, mixed digital agents, PVT/Monte Carlo matrices, waveform/result volume, generated HDL/UVM-MS compile scale, and publish a full capability/limitations matrix.

## Required semantic safeguards

- Nodal HVL and Verification Semantic IR remain canonical.
- Generated HDL is accepted only after target reparse/compile and profile-specific validation.
- Unsupported behavior is an error; no test operation is silently omitted.
- Deterministic replay streams are content-addressed and source-correlated.
- A passing result from one projection does not erase a classified parity difference in another.
- Open-source capability claims name exact tools, versions, options, and supported feature sets.
- Verilog-AMS source generation and Verilog-AMS open-source execution are reported as separate capabilities.
- Plain Verilog, SystemVerilog/UVM, Verilog-AMS, and UVM-MS artifacts share logical Interface/Register/test/check identities.

## Implementation dependencies

Foundation Increment 152 depends on the existing Foundation verification, interface, register, property, AMS, source-map, plugin, and tool-adapter contracts. The implementation-track increments remain blocked until every Foundation checkbox is complete.

Within the dependent tracks:

- Digital Verification Increment 6 depends on Digital Verification Increments 1-5 and Foundation 152.
- Digital Verification Increment 7 depends on Digital Verification Increment 6.
- Digital Verification Increment 8 may reuse Increments 1-5 but parity closure waits for Increment 7.
- AMS Verification Increment 6 depends on AMS Verification Increments 1-5, Foundation 152, and the required Foundation AMS/backend work.
- AMS Verification Increment 7 depends on AMS Verification Increment 6 only for shared identities; its open harness path may be implemented independently where the same frozen Verification IR operations are available.
- UVM/UVM-MS generation remains independent of procedural HDL execution, except for shared canonical identities and parity evidence.

## Completion claim

This roadmap update establishes feasibility and reserves architecture only. No generated Verilog testbench, generated Verilog-AMS testbench, open AMS harness, UVM, or UVM-MS implementation is claimed complete.
