# ADR 0025: Add generated procedural Verilog and Verilog-AMS testbench projections

- **Status:** Accepted
- **Date:** 2026-08-26
- **Scope:** Nodal HVL, Verification Semantic IR, generated Verilog testbenches, generated Verilog-AMS testbenches, native simulation, UVM, UVM-MS, simulator capability profiles, result manifests, and cross-backend parity

## Context

ADR 0023 makes Nodal HVL and the target-neutral Verification Semantic IR authoritative, with direct native execution and UVM/UVM-MS generation as sibling projections. That boundary is sound, but it does not reserve a source-generation path for users who need a conventional procedural HDL testbench rather than a JVM-side runtime or class-based UVM environment.

A generated digital Verilog testbench is valuable because it can compile with the generated portable Verilog DUT in lightweight open-source event-driven flows. A generated Verilog-AMS testbench is also a valid language artifact because Verilog-AMS extends Verilog with analog and mixed-signal structural and behavioral constructs suitable for top-level stimulus, observation, and checking.

The execution claims must nevertheless remain tool-specific. Icarus Verilog provides a practical open-source event-driven Verilog testbench baseline. Verilator supports many procedural timing constructs with `--timing`, but it remains capability-different from a four-state event-driven simulator. For mixed signal, OpenVAF and ngspice provide a strong Verilog-A model path, not a general full-language Verilog-AMS simulator. GNUCAP explicitly describes its Verilog-AMS implementation as partial, while Icarus and Verilator expose only supported or very small Verilog-AMS subsets. Generating standards-oriented Verilog-AMS therefore must not be confused with proving that every generated testbench can execute on an open-source simulator.

## Decision

Nodal adopts the following extension to ADR 0023:

> **Author verification once in Nodal HVL, preserve it in Verification Semantic IR, and allow capability-checked sibling projections to native execution, procedural Verilog testbenches, procedural Verilog-AMS testbenches, UVM, or UVM-MS. Artifact generation and simulator execution are separate acceptance gates.**

## Projection model

### Verification Semantic IR remains authoritative

Tests, scenarios, transactions, processes, timing, concurrency, stimulus, drivers, monitors, scoreboards, reference models, checks, properties, coverage intent, register bindings, analog quantities, tolerances, measurements, source locations, stable IDs, seeds, and replay streams remain target neutral.

No generated Verilog, Verilog-AMS, SystemVerilog, UVM, UVM-MS, simulator command line, or vendor macro defines Nodal verification semantics.

### Procedural Testbench IR

Foundation reserves a generated-language-neutral Procedural Testbench IR below Verification Semantic IR. It represents only the constructs needed by procedural HDL projections, including:

- testbench module hierarchy and deterministic DUT binding;
- ports, nets, variables, parameters, disciplines, terminals, and connect intent;
- `initial`/`always`-class processes, clocks, resets, delays, event controls, waits, forks/joins, bounded loops, tasks, and functions;
- typed drive, sample, monitor, check, scoreboard, timeout, termination, file, plusarg, waveform, and result operations;
- precomputed stimulus and expected-value streams when a target lacks constrained-random or class facilities;
- source maps, stable test/scenario/transaction/check IDs, capability requirements, and unsupported-feature diagnostics;
- analog sources/contributions, physical quantities, tolerances, measurements, crossings, timers, analyses, environment/PVT context, and mixed-signal bridge intent for the Verilog-AMS projection.

The Procedural Testbench IR is not a second semantic authority and is not required to represent UVM classes. Verification SystemVerilog IR remains the generated-language representation for UVM/UVM-MS.

## Digital Verilog testbench projection

The portable digital projection emits deterministic Verilog-2001 testbench source by default, together with a manifest containing DUT files, top, parameters, seed/replay identity, expected tool capabilities, compile/run commands, waveform/result paths, source maps, and unsupported features.

The initial open-source execution profiles are:

- **Icarus conformance profile:** the event-driven semantic baseline for generated Verilog testbenches;
- **Verilator fast profile:** an optional capability-checked profile using supported procedural timing, with explicit disclosure of two-state/four-state, event-ordering, timing-check, and unsupported-construct differences.

A backend may materialize deterministic stimulus or expected streams into files rather than requiring the generated Verilog testbench to implement Nodal's constrained-random solver, object model, or coverage database.

## Verilog-AMS testbench projection

The Verilog-AMS projection emits deterministic Verilog-AMS 2023 testbench source and a separate execution manifest. Generation acceptance requires target-language legality and source-map/capability evidence; it does not require that a particular open-source simulator currently execute the full artifact.

Open-source execution is enabled only through named, version-pinned conformance profiles whose exact generated feature set has passed positive, negative, and semantic tests. The initial research candidates are:

- GNUCAP/modelgen-verilog for its explicitly partial Verilog-AMS behavioral and mixed-signal support;
- Icarus or Verilator only for their documented limited Verilog-AMS-compatible subsets;
- OpenVAF plus ngspice for analog-only Verilog-A model execution, not as evidence of full Verilog-AMS testbench support.

Where a test fits an explicitly approved split capability, Nodal may generate a digital Verilog testbench plus Verilog-A/SPICE analog harness and coordinate them through a declared adapter. That is a distinct split/co-simulation artifact and must never be labeled a full Verilog-AMS run or selected silently.

## UVM and UVM-MS projections

UVM remains a SystemVerilog class-library projection based on IEEE 1800.2 and the Accellera reference implementation. UVM-MS remains the mixed-signal methodology projection that extends UVM and connects class-based and structural mixed-signal environments. Their initial qualified execution profiles remain commercial simulator profiles.

The procedural testbench projections neither replace UVM/UVM-MS nor force native Nodal simulation to depend on generated HDL.

## Artifact and execution separation

Every generated verification target records two independent result states:

1. **artifact result:** semantic lowering, target-language verification, deterministic rendering, source maps, and manifest identity;
2. **execution result:** exact simulator/version/profile, compile/elaboration/run commands, adapter identity, capability decisions, waves/results, and pass/fail evidence.

A successful artifact result must not be reported as a successful simulation. A parser accepting a language switch must not be treated as proof of behavioral support.

## Cross-backend parity

For a shared supported subset, native execution, Verilog testbench, Verilog-AMS testbench, UVM, and UVM-MS projections retain comparable test/scenario/transaction/check/coverage identities. Parity reports compare stimulus streams, transaction ordering, result values, failures, termination, and analog measurements within declared tolerances while classifying known scheduler, random-solver, four-state, and analog-solver differences.

## Consequences

- Users can choose lightweight generated HDL testbenches without abandoning the native runtime or future UVM reuse.
- Foundation work remains architecture-only: it reserves IR, capability, manifest, and source-map seams without implementing generators or simulators.
- Digital open-source Verilog testbench execution is a normal planned product capability.
- Verilog-AMS testbench generation is planned, while open-source execution remains a bounded conformance capability until tools prove the required subset.
- Unsupported projection features fail explicitly; no target silently drops stimulus, checking, coverage, analog behavior, or scheduling semantics.

## Rejected alternatives

### Make generated Verilog testbenches the canonical verification model

Rejected because procedural Verilog cannot represent the full Nodal HVL semantic envelope and would constrain native, UVM, and mixed-signal execution.

### Treat `-gverilog-ams` or VAMS parsing as full mixed-signal simulation support

Rejected because documented parsing/subset support is not evidence of continuous-time solver behavior or full-language testbench execution.

### Treat OpenVAF/ngspice Verilog-A model support as full Verilog-AMS support

Rejected because that flow compiles and executes analog Verilog-A models through OSDI; it does not by itself execute arbitrary mixed-signal Verilog-AMS testbenches.

### Silently split unsupported Verilog-AMS tests into separate tools

Rejected because hidden partitioning changes scheduling and solver semantics. Split/co-simulation requires an explicit profile, manifest, and parity evidence.

## References

- Accellera Verilog-AMS 2023: <https://www.accellera.org/downloads/standards/v-ams>
- Icarus Verilog simulation documentation: <https://steveicarus.github.io/iverilog/usage/simulation.html>
- Icarus language-generation flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>
- Verilator input-language and timing support: <https://verilator.org/guide/latest/languages.html>
- OpenVAF Verilog-A/OSDI documentation: <https://openvaf.semimod.de/docs/getting-started/usage/>
- ngspice OSDI/OpenVAF integration: <https://ngspice.sourceforge.io/osdi.html>
- GNUCAP modelgen-verilog: <https://github.com/gnucap/gnucap-modelgen-verilog>
- Accellera UVM: <https://www.accellera.org/downloads/standards/uvm>
- Accellera UVM-MS 1.0: <https://www.accellera.org/downloads/standards/uvm-ms>
