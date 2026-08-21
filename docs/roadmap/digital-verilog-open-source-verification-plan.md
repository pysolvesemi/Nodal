# Pure-digital Verilog and open-source verification plan

**Status:** Normative roadmap target
**Architecture:** [ADR 0010](../architecture/0010-digital-verilog-open-source-verification.md)
**Public API freeze:** Increment 15 unified v0.3 gate

## Goal

Allow a Nodal design containing only digital constructs to emit portable synthesizable Verilog and be linted, simulated, synthesized, equivalence-checked, and formally verified without a commercial or Verilog-AMS simulator.

The design rule is:

> **Infer the narrowest compatible backend, verify generated HDL through independent open-source tools, and retain evidence.**

External simulators, synthesis/formal tools, FPGA flows, waveform tools, and reporters use the versioned tool-adapter plugin protocol from [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md). Built-in adapters remain explicit, locked, and semantically equivalent to third-party adapters.

## Backend surface candidate

```scala
Backend.Auto
Backend.Verilog
Backend.VerilogA
Backend.VerilogAMS
```

`Backend.Auto` classifies the complete transitive design and selects:

| Design kind | Selected backend |
| --- | --- |
| Digital-only | `Backend.Verilog` |
| Analog-only | `Backend.VerilogA` |
| Mixed-signal | `Backend.VerilogAMS` |

The selected backend and classification evidence are included in the emission result and build manifest.

`Backend.Auto` does not discretize or approximate analog/mixed-signal content. The explicit AMS-to-FPGA transformation is planned separately in [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md); its resulting digital artifact may then use this portable Verilog flow.

An explicit `Backend.Verilog` request rejects analog or mixed-signal constructs before translation. Backend selection never depends on which tools happen to be installed.

A future `Backend.SystemVerilog` profile may be added separately. Portable Verilog is the initial required profile because the native Yosys frontend supports a broad Verilog-2005 subset and a narrower SystemVerilog subset.

## Digital-only classification

A design is digital-only when its entire reachable hierarchy contains only constructs supported by the digital profile.

Legal categories include:

- digital scalar/vector/aggregate ports and signals;
- parameters, symbolic widths, target generate, and hierarchy;
- implicit clock/reset domains;
- registers, memories, state machines, and assertions;
- `Valid` and `Stream` protocols;
- CDC/RDC primitives;
- automatic fixed-rate, valid-only, and elastic pipelines;
- supported external digital black boxes;
- supported simulation/formal-only digital constructs under an explicit profile.

Digital-only classification rejects or promotes away from Verilog when the hierarchy contains:

- natures, disciplines, analog nodes, branches, or contributions;
- analog blocks or continuous-time operators;
- analog events or sampled/drive conversion operations;
- wreal/real-number modeling outside the digital capability profile;
- connect modules/rules requiring AMS semantics;
- backend-specific analog escapes.

The classifier produces a deterministic construct inventory and the first source location that prevents a narrower backend.

## Portable Verilog profile

The required output is a conservative synthesizable IEEE 1364-2005-style subset.

### Required constructs

- modules, ports, parameters, local parameters, and named overrides;
- deterministic generated hierarchy and parameterized ranges;
- wires, registers, memories, continuous assignments, and procedural logic;
- explicit inferred clock/reset ports and reset behavior;
- CDC/RDC structures, FIFOs, synchronizers, gates/mux wrappers, and pipeline control;
- flattened aggregate and protocol fields with stable names;
- black-box declarations and attributes supported by the profile;
- portable formal hooks or sidecar harnesses;
- source-map and metadata sidecars.

### Portability restrictions

The required profile does not depend on:

- SystemVerilog interfaces or modports;
- packages;
- packed structs/unions at public boundaries;
- classes, dynamic arrays, queues, or DPI;
- broad SVA syntax;
- implicit backend-specific initialization;
- unsupported real-number or AMS constructs.

High-level `Bundle`, `Valid`, and `Stream` values are flattened deterministically for Verilog output while their structural metadata remains in sidecar manifests.

## Capability profiles

The backend publishes separate feature sets for:

- `digital-verilog-synth`: portable synthesizable RTL;
- `digital-verilog-sim`: explicitly allowed simulation-only behavior;
- `digital-verilog-formal`: assertions/assumptions/covers and harness constructs supported by the open-source formal flow;
- black-box/technology extensions;
- unsupported features with stable diagnostics.

`Backend.Auto` selects the synthesizable profile unless the user explicitly requests a simulation/formal profile.

## Target-HDL optimization integration

Digital optimization passes use [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md) and [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md). Generated RTL optimization is explicit, locked, parameter/source-map preserving, and verified with the same Verilator, Icarus, Yosys equivalence, parameter-matrix, latency/protocol-aware, and SBY evidence required by the selected pass profile. Installing a pass does not alter the digital backend or `Backend.Auto`.

## Open-source toolchain lock

The repository pins tested versions and checksums for:

- Verilator;
- Icarus Verilog;
- Yosys;
- SBY and selected solvers;
- cocotb for optional Python interoperability;
- waveform tools only when needed for CI artifacts.

A version matrix records supported, tolerated, and known-broken combinations. Tool upgrades are proposed through dependency reports and validated before adoption.

## Verilator gate

Required uses:

- `--lint-only`/strong warning gate on generated RTL;
- compiled simulation for fast digital regressions;
- optional code/toggle coverage;
- VCD/FST tracing;
- normalized diagnostics and source-map correlation.

Warnings are classified as errors, expected limitations, or waived portability findings through checked-in policy. Unscoped warning suppression is prohibited.

## Icarus Verilog gate

Required uses:

- explicit Verilog-2005 language mode;
- independent parser and elaboration check;
- event-driven smoke and portability simulation;
- VCD/FST waveform capture where supported;
- differential comparison against Verilator for shared fixtures.

The Icarus adapter records unsupported-feature classifications rather than silently skipping tests.

## Scala simulation API

Nodal's Scala simulation API drives the generated Verilog design through external tools.

Required digital support:

- deterministic compilation cache keyed by HDL/tool/profile hash;
- typed scalar/vector/aggregate signal access through emitted metadata;
- clock/reset generation from `ClockDomain` contracts;
- multiple clocks, phase/ratio relationships, and asynchronous scenarios;
- randomized reset assertion/release and reset-order testing;
- `Valid`/`Stream` drivers, monitors, scoreboards, stalls, and bubbles;
- transaction IDs and latency-aware pipeline checking;
- deterministic random seeds;
- timeouts, normalized logs, waveforms, and coverage artifacts.

The testbench exercises generated HDL, not a separate Scala behavioral model.

## cocotb interoperability

An optional adapter emits the metadata and runner configuration needed to use cocotb with Icarus or Verilator.

This provides:

- Python testbench interoperability;
- reuse of existing BFMs;
- a second testbench implementation path;
- simulator-independent smoke tests.

cocotb is not required for Nodal's Scala-native test API and does not define Nodal semantics.

## Yosys synthesis gate

Each representative digital fixture runs a reproducible Yosys script that:

1. reads generated Verilog in the declared language profile;
2. elaborates the selected top and parameters;
3. checks hierarchy and unresolved references;
4. lowers processes, memories, and state machines;
5. runs structural `check` passes;
6. performs target-neutral synthesis;
7. records inferred latches, memories, cells, widths, and black boxes;
8. emits a normalized synthesized Verilog netlist;
9. optionally maps to selected FPGA/ASIC targets in later increments.

Failures include:

- unsynthesizable behavior in the synth profile;
- accidental latch inference;
- unsupported initialization/reset semantics;
- parameter elaboration failure;
- memory inference mismatch;
- combinational loops or multiply driven nets;
- unresolved external cells not declared as approved black boxes.

## Equivalence plan

Yosys equivalence flows compare:

- generated RTL before and after approved compiler optimizations;
- deterministic re-emission of normalized IR;
- supported parameter instantiations against the same parameterized source;
- post-synthesis generic netlists against source RTL;
- fixed-latency automatic pipelines against latency-aligned reference behavior.

Pipeline equivalence tracks transaction identity, valid/bubble state, sidebands, reset, and published latency. Elastic pipelines require protocol-level safety/equivalence properties rather than same-cycle output equality.

## SBY formal plan

Required formal tasks include:

- bounded safety proofs;
- induction/unbounded safety where feasible;
- cover trace generation;
- selected liveness proofs;
- proof harnesses for `Valid`, `Stream`, FIFOs, handshakes, synchronizers, reset release, and automatic pipelines.

Initial reusable property suites prove:

- no transaction loss, duplication, or reordering;
- payload stability while stalled;
- FIFO occupancy bounds;
- handshake completion under declared fairness;
- reset convergence and legal post-reset output validity;
- fixed pipeline latency and sideband alignment;
- CDC/RDC wrapper assumptions and guarantees;
- no output before a valid input transaction.

Formal syntax remains within the open-source frontend capability profile. Unsupported SVA is lowered to simpler assertions or a sidecar harness, or rejected with a stable diagnostic.

## Differential regression

Representative designs run through a tool matrix:

| Check | Verilator | Icarus | Yosys | SBY |
| --- | --- | --- | --- | --- |
| Parse/lint | Required | Required | Required | Via Yosys |
| Simulation | Required | Required smoke | No | Counterexample/cover |
| Synthesis | No | No | Required | Preparation |
| Formal | No | No | Engine | Required |
| Waveforms | VCD/FST | VCD/FST | Netlist | VCD/trace |

Expected tool limitations are versioned and source-located. A test is not silently skipped because one tool fails.

## CI tiers

### Pull-request required

- design classification and `Backend.Auto` selection;
- deterministic portable Verilog emission;
- Verilator lint and representative simulation;
- Icarus parse/elaboration and smoke simulation;
- Yosys synthesis/check/stat;
- short SBY proofs for core register, stream, FIFO, pipeline, and reset primitives.

### Scheduled/extended

- randomized differential simulation;
- deep pipeline stalls, bubbles, flushes, and reset races;
- multi-clock CDC/RDC scenarios;
- parameter-envelope matrices;
- deeper proofs and liveness;
- RTL-to-netlist equivalence;
- coverage and performance trends;
- multiple tool versions where practical.

## Incremental delivery

### Unified v0.3 API gate

Freeze:

- `Backend.Auto` and `Backend.Verilog`;
- design-kind reporting;
- explicit synth/sim/formal digital profiles;
- deterministic classification evidence;
- diagnostics for incompatible backend requests.

### Digital backend implementation

Implement:

- digital-only analysis;
- portable Verilog translation;
- aggregate/protocol flattening;
- parameterized hierarchy and generate;
- source maps and construct manifests;
- deterministic output and golden tests.

### Open-source lint and simulation

Implement:

- Verilator and Icarus adapters;
- Scala digital simulation API;
- waveform/coverage capture;
- differential smoke suite;
- optional cocotb runner.

### Synthesis, equivalence, and formal

Implement:

- Yosys synth scripts and reports;
- RTL/netlist equivalence;
- SBY harness generation and property libraries;
- CI artifacts and failure triage.

## Exit criteria

The pure-digital path is considered complete only when:

1. `Backend.Auto` deterministically emits Verilog for a digital-only hierarchy;
2. analog-only and mixed-signal designs select the correct narrower/broader profiles;
3. explicit incompatible backend requests fail before translation;
4. portable Verilog compiles in Verilator, Icarus, and Yosys;
5. generated clock/reset, CDC/RDC, memory, hierarchy, and automatic-pipeline examples synthesize;
6. Scala simulation drives generated HDL under Verilator and Icarus;
7. representative SBY proofs and Yosys equivalence checks pass;
8. parameterized digital modules remain one symbolic module definition;
9. tool versions, commands, hashes, logs, waveforms, reports, and counterexamples are retained reproducibly.

## References

- Verilator guide: <https://verilator.org/guide/latest/>
- Icarus Verilog flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>
- Yosys Verilog frontend: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>
- SBY: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
- cocotb simulator support: <https://docs.cocotb.org/en/stable/simulator_support.html>
