# ADR 0010: Infer a portable digital backend and verify it with open-source tools

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Design classification, backend selection, pure-digital HDL generation, lint, simulation, synthesis, equivalence, formal verification, and CI

## Context

Nodal's first complete mixed-signal backend is Verilog-AMS, with Verilog-A for analog-only models. A design containing only digital constructs should not require a Verilog-AMS simulator or emit analog-oriented syntax.

Pure-digital output also enables a fully open-source validation path. Current open-source tools provide complementary strengths:

- Verilator provides strong linting and compiled simulation;
- Icarus Verilog provides event-driven Verilog simulation;
- Yosys reads a large Verilog-2005 subset, synthesizes RTL, checks hierarchy/processes, emits netlists, and supports equivalence flows;
- SBY drives Yosys-based bounded, unbounded, cover, and liveness formal tasks;
- cocotb can drive Icarus and Verilator through a common Python testbench interface.

Yosys' native frontend supports a large Verilog-2005 subset but only a smaller SystemVerilog subset. Therefore the first required open-source-compatible digital backend should be portable Verilog rather than depending on broad SystemVerilog support.

## Decision

Nodal classifies every elaborated design before backend translation as one of:

- **digital-only**;
- **analog-only**;
- **mixed-signal**;
- **unsupported/ambiguous**, with diagnostics.

The public backend architecture adds:

```scala
Backend.Auto
Backend.Verilog
Backend.VerilogA
Backend.VerilogAMS
```

`Backend.Auto` selects the narrowest compatible required profile:

- digital-only → portable synthesizable Verilog;
- analog-only → Verilog-A;
- mixed-signal → Verilog-AMS.

This selection performs no numerical or semantic approximation. In particular, `Backend.Auto` never converts analog or mixed-signal content into an FPGA model. AMS-to-FPGA validation is the explicit transformation defined by [ADR 0011](0011-ams-fpga-approximation-validation.md); only its resulting digital artifact may use the portable Verilog backend.

An explicit backend override remains available, but capability verification runs before translation and rejects incompatible constructs. Automatic selection is reported in deterministic build evidence; it is not silent or dependent on tool availability.

## Digital design classification

A design is digital-only only when its transitive hierarchy contains no:

- analog disciplines, natures, nodes, branches, or contributions;
- analog blocks or continuous-time operators;
- analog events such as `cross`, `above`, or `timer` used in analog semantics;
- real-number/wreal constructs outside the approved digital profile;
- connect modules/rules requiring AMS semantics;
- analog/digital conversion operations;
- backend-specific AMS escape constructs.

Digital clock/reset domains, CDC/RDC structures, memories, automatic pipelines, assertions, formal properties, and parameterized hierarchy remain legal in the digital-only profile when representable in portable Verilog.

Classification is a target-neutral analysis result stored in normalized IR and build manifests. One unsupported construct must not silently force a different backend without an explanatory report.

## Portable Verilog profile

The first required digital backend emits a conservative synthesizable IEEE 1364-2005-style subset suitable for Verilator, Icarus Verilog, and Yosys.

The profile includes:

- modules, ports, parameters, local parameters, and named overrides;
- scalar/vector nets and registers;
- combinational assignments and procedural logic;
- synchronous/asynchronous reset processes;
- generated loops and conditions when statically legal;
- memories and initialization only within declared profile support;
- hierarchy, CDC/RDC primitives, automatic pipeline state, and assertions lowered into supported forms;
- deterministic flattening of structured payloads and protocols at module boundaries;
- stable source mapping and metadata comments or sidecar manifests.

The backend must lower high-level aggregates and protocol types without requiring SystemVerilog interfaces, packages, packed structs, or broad SVA support.

A future explicit `Backend.SystemVerilog` profile may be added through a separate capability gate. It is not required for the first open-source digital path and must not weaken portable Verilog support.

## Synthesizable and simulation-only digital features

The digital backend publishes separate capability flags for:

- synthesizable portable RTL;
- simulation-only delays or initialization;
- assertions/assumptions/covers supported by the formal flow;
- black boxes and technology cells;
- unsupported event-driven constructs.

`Backend.Auto` defaults to the synthesizable portable profile for a digital-only design. Simulation-only features require an explicit profile or option so synthesis cannot accidentally ignore behavior.

## Enum and FSM representation

[ADR 0015](0015-native-scala-enum-and-hierarchical-fsm.md) defines semantic enums and typed reusable statecharts. Portable Verilog emits enum members as module-local `localparam`s, stores values in vectors/integers, and retains enum/FSM manifests and source maps. Local FSM recoding does not change canonical enum values at module boundaries.

The digital verification matrix checks legal enum encodings, safe decode, exhaustive selection, localparam/value parity, state reachability, allowed transitions, one-hot/Gray/custom encoding contracts, illegal-state behavior, hierarchy/parallel completion, bounded-stack safety, reset convergence, and equivalence across verified recoding or flattening passes.


## Open-source verification stack

### Frontend and lint gate

Every generated digital fixture is parsed by both Icarus Verilog and Verilator where the construct is within both tools' supported profile.

Verilator runs strong warnings and lint checks. Icarus runs the selected Verilog language version and elaborates the top module. Tool disagreements are retained as artifacts and classified as backend bug, tool limitation, or profile gap.

### Simulation

Nodal's Scala simulation API drives generated HDL through external simulators rather than simulating a separate frontend model.

Required adapters:

- Verilator for fast compiled cycle/timing simulation of synthesizable digital designs;
- Icarus Verilog for event-driven portability checks;
- optional cocotb interoperability so Python testbenches can run against the same generated HDL.

The harness supports:

- clock/reset generation from `ClockDomain` metadata;
- multiple related or asynchronous clocks;
- randomized reset release;
- typed signal and protocol access;
- transaction drivers/monitors for `Valid` and `Stream`;
- VCD/FST waveform capture;
- deterministic seeds and normalized logs;
- coverage artifacts where supported.

### Synthesis validation

Yosys validates the synthesizable profile with reproducible scripts that:

- read and elaborate generated Verilog;
- check hierarchy and unresolved black boxes;
- lower processes and memories;
- run `check` and target-neutral synthesis;
- report cells, memories, wires, and inferred latches;
- emit a normalized synthesized Verilog netlist;
- optionally map to a generic or selected FPGA/ASIC target later.

Synthesis is a semantic gate, not merely a syntax check. Unexpected latch inference, unsynthesizable constructs, and parameter elaboration failures are errors.

### Equivalence

Approved plug-and-play digital optimization passes follow [ADR 0013](0013-structured-hdl-optimization-pass-architecture.md): they operate on verified structured digital target IR or through locked reparse adapters, preserve symbolic parameters/domains/protocols/source maps, and carry explicit equivalence/formal obligations.

Yosys-based equivalence checks compare:

- deterministic generated RTL before and after approved optimization;
- parameter-normalized variants where legal;
- reference and transformed fixed-latency datapaths;
- post-synthesis netlists against source RTL for supported fixtures.

Automatic pipelines require latency-aware transaction equivalence rather than same-cycle combinational equality. The formal harness aligns transaction identity and published latency before comparing payloads and sidebands.

### Formal verification

SBY provides required smoke flows for:

- bounded safety checking;
- unbounded or induction-based safety where practical;
- cover trace generation;
- selected liveness properties;
- FIFO, handshake, pipeline, CDC/RDC wrapper, and reset-release properties.

Nodal may emit portable assertions/assumptions/covers or sidecar formal harnesses. The formal profile stays within the supported open-source frontend subset and reports unsupported property syntax explicitly.

### Deferred user-authored formal property architecture

[ADR 0014](0014-target-neutral-formal-verification.md) owns the future public property, clock/reset, sampled-history, symbolic-environment, harness/contract, task, vacuity, counterexample, and replay semantics. Formal properties remain target-neutral Nodal IR and may selectively lower to CIRCT `verif`/`ltl`, portable Yosys-compatible hooks, sidecar harnesses, or future capability-gated targets.

Increment 67 provides compiler-generated formal hooks, equivalence, core property suites, SBY/Yosys integration, source mapping, and evidence readiness only. It must not freeze a public formal DSL or make SVA/SBY syntax part of Nodal semantics. The deferred public formal phase is independent of the initial core and AMS milestones.


### Differential and regression testing

Core digital fixtures run through more than one tool when practical:

- Verilator and Icarus simulation compare transaction results;
- Yosys synthesis checks the same generated RTL;
- selected fixtures run SBY proofs;
- waveform and counterexample traces are retained on failure.

This matrix reduces dependence on one parser or simulator implementation.

## Plugin adapter boundary

[ADR 0012](0012-versioned-capability-plugin-architecture.md) standardizes external simulator, synthesis, formal, FPGA, waveform, and reporting integrations as versioned out-of-process tool-adapter plugins. Built-in adapters use the same manifest, capability, process, evidence, trust, and provenance envelope as third-party adapters.

Adapter selection is explicit and locked. Installing an adapter must not change `Backend.Auto`, language semantics, or required verification profiles silently.

## CI tiers

### Required pull-request gate

A small representative digital suite runs:

- backend classification and deterministic emission;
- Verilator lint/compile;
- Icarus parse/elaboration and smoke simulation;
- Yosys synthesis/check/stat;
- short SBY bounded proofs for core primitives.

### Extended or scheduled gate

Larger suites run:

- randomized differential simulation;
- multi-clock and reset scenarios;
- pipeline stall/bubble/flush tests;
- deeper formal proofs and cover generation;
- RTL-to-netlist equivalence;
- parameter-envelope matrix checks;
- performance and coverage reports.

## Backend inference and user control

Backend inference is deterministic and reviewable:

```scala
Nodal.emit(
  new DigitalTop,
  EmitOptions(backend = Backend.Auto),
)
```

The emission result records:

- classified design kind;
- selected backend/profile;
- construct inventory that justified the selection;
- rejected narrower profiles, if any;
- tool-validation commands and versions;
- source and output hashes.

Users may request `Backend.Verilog` explicitly. A pure-digital design compiled as Verilog-AMS remains possible only if the explicit profile supports it and a later use case justifies it; `Backend.Auto` does not choose the broader backend.

## Consequences

### Positive

- Pure-digital Nodal designs need no AMS simulator.
- Generated RTL works with a broad open-source toolchain.
- Portable Verilog provides a stable interoperability format.
- Lint, simulation, synthesis, equivalence, and formal verification exercise the generated HDL rather than a separate model.
- Backend inference remains deterministic and explainable.
- High-level clock/reset and automatic-pipeline semantics receive concrete RTL and formal validation.

### Costs

- Structured Nodal types must be flattened or encoded conservatively for Verilog-2005.
- The backend must avoid unsupported SystemVerilog conveniences.
- Tool capability differences require versioned classification and expected-limitation metadata.
- Maintaining two simulation adapters and formal/synthesis scripts adds test infrastructure.

## Rejected alternatives

- **Always emit Verilog-AMS:** unnecessarily requires specialized tools for digital-only designs.
- **Emit broad SystemVerilog as the first open-source profile:** Yosys' native SystemVerilog support is narrower than its Verilog-2005 support.
- **Select a backend based on installed tools:** makes output environment-dependent and non-reproducible.
- **Use only one simulator:** allows parser/tool-specific bugs to escape.
- **Trust simulation without synthesis:** misses unsynthesizable behavior and inferred hardware surprises.
- **Trust synthesis without formal or differential simulation:** misses temporal and protocol errors.
- **Verify a frontend model instead of generated HDL:** can hide translation bugs.

## Follow-up increments

- The v0.3 public API gate freezes `Backend.Auto`, `Backend.Verilog`, design-kind reporting, and digital capability options.
- The compiler backend increment implements design classification and portable Verilog emission.
- Dedicated open-source verification increments add lint/simulation, then Yosys synthesis/equivalence and SBY formal flows.
- Later release and conformance increments publish exact tool/version matrices and supported feature coverage.

## References reviewed

- Verilator documentation: <https://verilator.org/guide/latest/>
- Icarus Verilog usage and language flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>
- Yosys Verilog frontend: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>
- SBY documentation: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
- cocotb simulator support: <https://docs.cocotb.org/en/stable/simulator_support.html>
