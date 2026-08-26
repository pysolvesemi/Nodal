# ADR 0025: Add capability-checked procedural HDL testbench projections to the unified Nodal verification architecture

- **Status:** Accepted
- **Date:** 2026-08-26
- **Extends:** [ADR 0023](0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)
- **Scope:** Nodal HVL, Verification Semantic IR, generated Verilog testbenches, generated Verilog-AMS testbenches, open-source simulator profiles, UVM/UVM-MS coexistence, deterministic replay, source maps, capability negotiation, and cross-backend parity

## Context

ADR 0023 established one canonical Nodal Verification Semantic IR with two sibling execution directions:

1. native Nodal simulation through simulator adapters; and
2. generated UVM or UVM-MS for standards-oriented commercial flows.

A useful third direction was still missing. A user may want the same Nodal HVL environment rendered as a standalone HDL testbench so generated RTL can be simulated without the Nodal runtime and without UVM. For pure digital designs this means a procedural Verilog testbench suitable for open-source tools. For analog/mixed-signal designs it may mean a standards-oriented Verilog-AMS testbench, provided the selected simulator actually supports the required language and solver capabilities.

The architecture must add this option without:

- creating a second verification source language;
- making plain Verilog limitations define Nodal HVL semantics;
- weakening native simulation or generated UVM/UVM-MS;
- claiming that current open-source tools provide a complete Verilog-AMS testbench simulator;
- silently dropping constrained randomization, coverage, concurrency, analog measurements, or other unsupported behavior.

## Feasibility findings

### Digital procedural Verilog testbenches are feasible

A Verilog testbench can instantiate generated Verilog RTL, generate clocks and resets, drive ports, wait for events, call tasks/functions, read vector files, check outputs, count failures, write waveforms, and terminate with a deterministic status. Icarus Verilog explicitly documents compiling simulation models together with Verilog testbenches and supports an IEEE 1364-2005 language mode.

Verilator supports most Verilog-2005 and, with timing enabled, supports delays, event controls, waits, and forks. It nevertheless has material semantic differences and incomplete verification-language support, including predominantly two-state execution and limited class/coverage behavior. Therefore Icarus is the required event-driven reference profile for the portable Verilog-testbench target; Verilator is a separately qualified high-performance subset, not an assumed drop-in semantic equivalent.

### Verilog-AMS testbench source is feasible

Verilog-AMS supports top-level mixed-signal structural and behavioral modeling, digital/event-driven behavior, analog behavior, disciplines, terminals, contributions, events, and mixed hierarchy. A top-level verification module that instantiates a DUT and supplies analog and digital stimulus is therefore a valid Verilog-AMS use.

This establishes language feasibility, not universal tool feasibility.

### General open-source execution of generated Verilog-AMS testbenches is not a current baseline

The practical open-source stack is narrower than the Verilog-AMS language:

- OpenVAF targets the analog Verilog-A subset and states that digital models cannot currently be parsed.
- OpenVAF compiles Verilog-A models to OSDI libraries, which ngspice loads from a SPICE/XSPICE simulation harness.
- ngspice can combine analog simulation with event-driven digital blocks and provides digital co-simulation paths, but that is not equivalent to accepting an arbitrary Verilog-AMS 2023 testbench as one source unit.
- Verilator documents only a very small Verilog-AMS parsing subset and is not an analog solver.

Consequently, Nodal may generate a standards-oriented Verilog-AMS testbench artifact, but an open-source run is enabled only after a selected adapter proves the exact required capabilities. The required open-source AMS path remains native Nodal HVL plus capability-checked Verilog-A/OSDI, SPICE/XSPICE, and digital co-simulation adapters.

### Feasibility matrix

| Nodal HVL projection | Generated artifact | Intended execution | Feasibility decision |
| --- | --- | --- | --- |
| Native digital | Nodal runtime plus simulator adapter | Verilator and Icarus | Required and independent of generated HDL testbenches |
| Direct digital HDL | Portable Verilog testbench plus optional vectors/files | Icarus required; Verilator qualified subset | Feasible |
| Digital methodology | SystemVerilog/UVM | Commercial SystemVerilog simulators | Feasible under ADR 0023 |
| Native AMS | Nodal runtime plus open-model/solver/digital adapters | OpenVAF/ngspice and qualified co-simulation paths | Feasible within declared capabilities |
| Direct AMS HDL | Verilog-AMS testbench | Commercial or future capable simulators | Language-feasible; tool-capability-gated |
| Open AMS harness | SPICE/XSPICE harness, OSDI models, digital Verilog/co-simulation collateral | ngspice/OpenVAF and qualified digital adapters | Feasible; not represented as full Verilog-AMS testbench support |
| AMS methodology | UVM-MS structural/class collateral | Qualified commercial AMS simulators | Feasible under ADR 0023 |

## Decision

Nodal adopts the binding rule:

> **Author verification once in Nodal HVL, preserve it in the canonical Verification Semantic IR, and select among native execution, capability-limited procedural HDL testbench generation, UVM generation, or UVM-MS generation without changing semantic ownership.**

ADR 0023 remains authoritative. This ADR adds direct procedural HDL testbench projections as siblings of the native and UVM/UVM-MS projections.

## Projection families

The Verification Semantic IR may feed four projection families:

1. **Native runtime projection**
   - Nodal owns scheduling, randomization, replay, checks, scoreboards, coverage, and source-level diagnostics.
   - Simulator adapters execute and expose the DUT.

2. **Procedural HDL testbench projection**
   - Produces a standalone, capability-declared Verilog or Verilog-AMS testbench and any deterministic sidecar data.
   - Does not require UVM.
   - Is intentionally less expressive than the complete Nodal HVL.

3. **Digital UVM projection**
   - Produces idiomatic standards-oriented SystemVerilog/UVM and reusable VIP.

4. **UVM-MS projection**
   - Produces UVM-MS class/structural bridges and mixed-signal verification collateral.

No generated projection is a semantic authority, and no projection may silently reinterpret or omit unsupported Verification IR behavior.

## Procedural HDL Testbench IR

A generated-language **Procedural HDL Testbench IR** is reserved between the canonical Verification Semantic IR and Verilog-family rendering. It is not public authoring semantics and is not a replacement for the verification-SystemVerilog IR used by UVM.

It records:

- DUT/interface/register bindings using stable logical identities;
- top-level wrapper and instance structure;
- clocks, resets, time units, event controls, waits, timeouts, and termination;
- deterministic processes and legal fork/join structure;
- procedural tasks/functions and static data;
- direct stimulus, monitors, checks, scoreboards representable in the selected profile;
- vector/file replay operations and expected-result data;
- waveform controls and normalized pass/fail reporting;
- analog terminals, sources, contributions, crossings, measurements, and tolerances for Verilog-AMS profiles;
- stable test/check/transaction IDs, source maps, generated names, and artifact hashes;
- required simulator capabilities and explicit exclusions.

Every Verification IR operation is classified for a selected procedural profile as one of:

- **embedded** — lowered directly into the generated HDL;
- **precomputed replay** — deterministic values or expected results are materialized into sidecar files;
- **companion-runtime required** — legal only for an explicitly selected non-standalone adapter profile;
- **unsupported** — generation fails before producing an accepted artifact.

Approximation by silent omission is forbidden.

## Portable digital testbench baseline

The required baseline is a conservative standalone IEEE 1364-2005-style procedural testbench that can exercise generated portable Verilog RTL.

The baseline may use:

- `module` hierarchy and a deterministic DUT wrapper;
- `initial`/`always`, delay and event controls, `wait`, bounded loops, and named blocks;
- `reg`, `wire`, integers, static memories, parameters/localparams, tasks, and functions;
- direct flattened Interface ABI access and generated helper tasks;
- `$readmemh`/supported file I/O for deterministic stimulus and expected-result replay;
- error counters, `$display`, and `$finish` for portable result reporting;
- VCD/FST controls where the selected tool profile supports them;
- generated protocol BFMs and scoreboards only when they fit the frozen subset.

The baseline does not promise native implementation of classes, virtual interfaces, constrained-random solvers, covergroups, mailboxes, semaphores, dynamic containers, factory/config databases, or UVM phases. Nodal may precompute deterministic stimuli or reject the target. A future separately qualified non-UVM SystemVerilog procedural profile may widen the envelope without replacing the portable Verilog target.

## Verilog-AMS testbench and open-source AMS policy

A generated Verilog-AMS testbench profile may contain:

- top-level mixed-signal hierarchy;
- discipline-qualified terminals and branches;
- analog stimulus contributions and controlled sources;
- digital clocks, resets, controls, and event-driven processes;
- analog events, crossings, measurements, tolerances, and termination;
- explicit connect modules/rules and bridge metadata where required;
- source maps and capability manifests.

The generator must not claim an executable open-source Verilog-AMS flow solely because source was rendered. Before a run, the adapter must prove support for every required digital, analog, event, analysis, connect, and solver capability.

For open-source AMS verification, Nodal may instead lower the same verification intent into an **open AMS harness bundle** consisting of supported Verilog-A model artifacts, OSDI libraries, SPICE/XSPICE stimulus and analyses, digital Verilog/co-simulation collateral, vector files, and normalized results. This is a separate execution profile with explicit parity evidence; it is not mislabeled as full Verilog-AMS testbench execution.

## Tool-profile rules

- **Icarus Verilog:** required event-driven reference for the portable Verilog-testbench profile.
- **Verilator:** qualified performance profile for the subset whose timing, four-state, event, inout, and verification behavior is proven by tests.
- **OpenVAF plus ngspice:** required open analog-model/harness seam where supported; not a general Verilog-AMS digital testbench parser.
- **Commercial digital simulators:** qualified for generated UVM and may also run generated procedural Verilog/SystemVerilog testbenches.
- **Commercial AMS simulators:** qualified independently for generated Verilog-AMS testbenches and UVM-MS.
- **Future open AMS adapters:** may enable generated Verilog-AMS testbench execution only through versioned capability conformance.

Tool versions, language generation flags, options, commands, capability decisions, and known semantic deviations are locked in the run manifest.

## Determinism and replay

The canonical seed hierarchy remains Nodal-owned.

A procedural HDL projection may use:

- deterministic algorithms expressible in the target subset;
- a precomputed value stream generated from the canonical Nodal random model;
- a precomputed expected-result stream;
- explicit runtime plusargs or data files whose hashes are retained.

The manifest states whether stimulus was algorithmic, precomputed, or companion-runtime-driven. Exact replay requires the same value stream and artifact hashes, not reliance on simulator-specific `$random` behavior.

## Cross-backend parity

Digital parity expands from native versus UVM to:

- native Nodal execution;
- generated portable Verilog testbench;
- generated UVM.

AMS parity expands to:

- native/open execution;
- open AMS harness where selected;
- generated Verilog-AMS testbench on capable tools;
- generated UVM-MS.

Parity compares transaction order, protocol behavior, deterministic stimulus streams, checks, scoreboard decisions, register behavior, coverage intent, termination, source-level failure IDs, and AMS measurements within declared tolerances. Unsupported scheduler, random-solver, four-state, or analog-solver differences are classified explicitly.

## Foundation boundary

Foundation adds only one minimal architecture-readiness increment:

- freeze the Procedural HDL Testbench IR seam;
- freeze capability classification and artifact/result manifests;
- freeze portable Verilog-testbench and Verilog-AMS-testbench profile boundaries;
- freeze the distinction between full Verilog-AMS testbench execution and the practical open AMS harness;
- reserve source-map, parity, plugin, and simulator-adapter contracts.

Foundation does **not** implement:

- a Verilog testbench generator;
- a Verilog-AMS testbench generator;
- vector/replay lowering;
- open-source or commercial testbench runners;
- UVM/UVM-MS generators;
- testbench VIP libraries.

Those implementations remain in the independently numbered Digital Verification and AMS Verification tracks after the Foundation barrier opens.

## Consequences

### Positive

- One Nodal HVL source can serve native, standalone HDL, UVM, and UVM-MS flows.
- Generated Verilog RTL can be delivered with a portable open-source-runnable testbench.
- Users can consume a standalone regression artifact without installing the Nodal runtime.
- Verilog-AMS testbench generation is architecturally available without overclaiming present open-source simulator support.
- Open-source AMS work follows the tools that exist while preserving a future standards-oriented Verilog-AMS path.
- Unsupported semantic features fail explicitly instead of disappearing from generated tests.

### Costs

- Nodal needs an additional generated-language IR and capability mapper.
- Standalone Verilog cannot express the complete HVL; replay files and explicit rejection are necessary.
- Icarus/Verilator semantic differences require independent qualification.
- Open AMS parity spans multiple artifact kinds and co-simulation boundaries.
- Verilog-AMS and UVM-MS commercial profiles require licensed qualification.

## Rejected alternatives

### Maintain separate Nodal, Verilog, and UVM testbench sources

Rejected because test intent, scoreboards, coverage, and protocol behavior would drift.

### Treat plain Verilog as the canonical verification model

Rejected because its language envelope is too small for Nodal HVL, UVM, and AMS semantics.

### Emit best-effort Verilog and silently drop unsupported features

Rejected because a generated testbench could pass without testing required behavior.

### Claim OpenVAF/ngspice is a complete Verilog-AMS testbench simulator

Rejected because the current open path is Verilog-A/OSDI plus SPICE/XSPICE and digital co-simulation, not arbitrary Verilog-AMS 2023 execution.

### Make generated UVM the intermediate form for procedural testbenches

Rejected because it would reintroduce a UVM dependency into open-source and standalone flows.

## References

- Accellera Verilog-AMS 2023 release: <https://www.accellera.org/news/press-releases/393-accellera-approves-verilog-ams-2023-standard-for-release>
- Accellera Verilog-AMS overview: <https://www.accellera.org/activities/working-groups/systemverilog-ams/verilog-ams/about>
- Icarus Verilog simulation and testbench documentation: <https://steveicarus.github.io/iverilog/usage/simulation.html>
- Icarus Verilog language-generation flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>
- Verilator input-language and timing support: <https://verilator.org/guide/latest/languages.html>
- OpenVAF Verilog-A compliance and limitations: <https://openvaf.semimod.de/docs/details/verilog-a-standard/>
- OpenVAF/ngspice usage: <https://openvaf.semimod.de/docs/getting-started/usage/>
- ngspice OSDI/OpenVAF integration: <https://ngspice.sourceforge.io/osdi.html>
- ngspice mixed-signal/digital co-simulation features: <https://ngspice.sourceforge.io/extras.html>
- Accellera UVM: <https://www.accellera.org/downloads/standards/uvm>
- Accellera UVM-MS: <https://www.accellera.org/downloads/standards/uvm-ms>
