# ADR 0025: Add generated portable Verilog and Verilog-AMS testbench projections

- **Status:** Accepted
- **Date:** 2026-08-26
- **Extends:** [ADR 0023](0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)
- **Scope:** Nodal HVL, Verification Semantic IR, native simulation, generated portable Verilog testbenches, generated Verilog-AMS testbenches, UVM, UVM-MS, DUT/testbench ABI, artifact manifests, simulator capability profiles, open-source qualification, deterministic replay, source maps, and cross-projection parity

## Context

ADR 0023 established one Nodal HVL source and one target-neutral Verification Semantic IR with native-simulation and UVM/UVM-MS projections. That decision intentionally kept open-source execution independent of UVM, but it did not reserve self-contained procedural HDL testbench outputs.

Two additional artifacts are valuable:

1. a portable digital Verilog testbench that can be handed to an open-source simulator together with generated Verilog RTL; and
2. a standards-oriented Verilog-AMS testbench that can be handed to a capable mixed-signal simulator together with generated Verilog, Verilog-A, and Verilog-AMS design units.

The two requests have different present-day tool boundaries. Portable digital Verilog has mature open-source implementations. Verilog-AMS is a valid unified mixed-signal language and can express a top-level testbench containing structural connectivity, digital procedural behavior, and analog behavior, but current open-source implementations cover only subsets. A language parser, Verilog-A compact-model compiler, or isolated behavioral-model path is not evidence that a complete Verilog-AMS testbench can elaborate and simulate.

The architecture must therefore reserve both generated outputs without converting an uncertain open-source Verilog-AMS ecosystem into a product guarantee.

## Decision

Nodal adopts five sibling verification projections from one Verification Semantic IR:

| Projection identity | Output/execution form | Initial execution policy |
| --- | --- | --- |
| Native | Nodal-owned verification runtime controlling a compiled DUT through simulator adapters | Primary open-source digital and mixed-signal workflow where capabilities exist |
| Portable Verilog testbench | Deterministic IEEE 1364-2005-oriented procedural/structural testbench collateral | Pinned Icarus and Verilator profiles after conformance |
| Digital UVM | Standards-oriented SystemVerilog/UVM packages, interfaces, classes, top, and scripts | Commercial profiles first; any open profile requires independent conformance |
| Verilog-AMS testbench | Deterministic Verilog-AMS 2023-oriented structural, procedural, and analog testbench collateral | Commercial/capable tools; open profile disabled until subset conformance passes |
| UVM-MS | Standards-oriented UVM-MS class and structural bridge collateral | Qualified commercial mixed-signal profiles |

Exact public API spelling for selecting these targets is deferred to the Foundation design gate. The semantic identities and separation are binding.

No projection is canonical. Nodal HVL and Verification Semantic IR remain authoritative. Generated HDL text, UVM phases, simulator callback APIs, tool command lines, and vendor macros never define Nodal verification semantics.

## Projection pipeline

The canonical pipeline is:

```text
Nodal HVL
    |
    v
Verification Semantic IR
    |---------------- native runtime + simulator adapter
    |---------------- portable-Verilog testbench projection + digital profile
    |---------------- verification-SystemVerilog IR + UVM renderer/profile
    |---------------- Verilog-AMS testbench projection + AMS profile
    `---------------- verification-SystemVerilog/AMS bridge IR + UVM-MS profile
```

Renderer-owned target representations may be introduced for formatting, compile units, or target-specific legalisation. They are not a second semantic model and must retain stable source and semantic IDs.

## Common DUT/testbench ABI and artifact manifest

Every generated or native projection uses one logical DUT/testbench ABI rather than guessing generated hierarchy strings. The ABI and manifest record, as applicable:

- design, test, component, transaction, Interface, Register, property, coverage, measurement, and failure IDs;
- DUT top, parameters, configuration hash, ports, terminals, roles, resolved-net identity, and hierarchy/source maps;
- clocks, resets, domains, time unit/precision, scheduling assumptions, timeout, and termination policy;
- files, packages, include paths, libraries, compile order, top-level units, generated wrappers, and artifact hashes;
- analog natures, disciplines, nodes, branches, connect rules, bridge identities, analyses, environment/PVT, tolerances, and model-validity requirements;
- random model, seed hierarchy, generated value stream, replay mode, and solver identity;
- requested Verification IR capabilities and the selected testbench/simulator profile decision for each capability;
- tool versions, options, commands, adapter hashes, wave/result locations, normalized results, and unsupported/experimental status.

A generated testbench must be reproducible from its source and locked manifest. A simulator profile may not silently insert vendor behavior that changes the common verification semantics.

## Portable digital Verilog testbench profile

The initial portable profile targets a deliberately bounded IEEE 1364-2005-oriented subset. It may include:

- top-level DUT instantiation and parameter overrides;
- clock/reset generation and deterministic timed stimulus;
- tasks/functions, memories, file-based replay streams, monitors, procedural checks, bounded scoreboards, timeout/termination, and waveform requests supported by the selected profile;
- deterministic names, source maps, comments, and normalized result markers.

The profile does not pretend that plain Verilog can represent arbitrary Nodal HVL behavior. SystemVerilog classes, UVM, covergroups, general constrained randomization, unsupported temporal properties, dynamic containers, foreign-language reference models, and concurrency with no portable lowering are capability errors unless an explicit generated value stream or other semantics-preserving representation is selected.

Icarus and Verilator are independent profiles. Passing one does not imply passing the other. Four-state behavior, event ordering, timing, file I/O, waveform controls, memories, termination, and diagnostics are part of conformance.

## Verilog-AMS testbench profile

Verilog-AMS is accepted as a generated testbench language because it unifies analog and digital semantics and permits analog and digital declarations and procedural regions in one module. A generated testbench may contain:

- a structural mixed-signal top and DUT bindings;
- disciplines, natures, conservative nodes, signal-flow values, branches, and connect-rule requirements;
- digital clocks, resets, controls, and event-driven stimulus;
- analog sources, contributions, transitions, crossings/timers, environment/PVT setup, measurements, checks, and analysis requests supported by the selected profile;
- bindings to generated Verilog, Verilog-A, and Verilog-AMS design units when the simulator profile proves that mixed-language composition.

The projection must preserve conservative analog semantics. It must not silently replace electrical nodes or contributions with `real`/`wreal`, fixed-step discretization, RNM, or an FPGA approximation. Such transformations remain separate, explicitly requested, and independently validated contracts.

## Open-source Verilog-AMS qualification

Open-source Verilog-AMS execution is a profile, not an architectural assumption.

A profile is enabled only after a pinned toolchain demonstrates actual compile, elaboration, and simulation for the approved minimum vertical slice. Parsing a construct is insufficient. A Verilog-A-to-OSDI compiler is a model-compilation path, not by itself a complete testbench engine.

Qualification is construct and analysis specific and records at least:

- disciplines/natures and conservative topology;
- contributions and analog procedural semantics;
- digital/analog scheduling and bridge behavior;
- events, crossings, timers, tolerances, initialization, and discontinuities;
- supported analyses, sources, measurements, waveforms, and failure behavior;
- parameterization, hierarchy, compile order, external models, and reproducibility.

Each feature is reported as supported, experimental, or unsupported. Unsupported behavior is rejected before execution. Native Nodal mixed-signal simulation remains the default open-source path until this gate is passed.

## UVM and UVM-MS profiles

ADR 0023 remains authoritative for UVM and UVM-MS projection semantics. Commercial simulator profiles are the initial qualification target.

Open-source SystemVerilog/UVM capability is evolving and may later become a profile. It is not forbidden, but it must pass the same pinned conformance and parity gates as every other UVM profile. Example compilation or partial library support is not a release claim.

Vendor differences remain in thin adapter/include units and locked manifests. Common generated UVM/UVM-MS source is vendor neutral wherever the selected standards permit.

## Fail-closed capability negotiation

Before rendering or running, Nodal computes the capabilities required by the Verification IR and compares them with both the selected testbench projection and simulator profile.

The result for every required capability is one of:

- supported exactly;
- supported through an explicit semantics-preserving representation such as a recorded replay-value stream;
- experimental and permitted only by an explicit non-release profile;
- unsupported and rejected.

Nodal does not silently omit coverage, checks, measurements, assertions, reference-model calls, analog effects, or termination behavior. It does not translate a UVM-only feature into weaker procedural behavior or an AMS feature into an unrelated digital approximation merely to make a tool accept the files.

## Cross-projection parity

Where capabilities overlap, parity is evaluated using stable semantic identities rather than identical scheduler implementation. Evidence includes:

- stimulus and replay values;
- transaction values, ordering, and protocol timing;
- monitor observations, checks, scoreboards, and register behavior;
- properties and coverage sampling intent;
- analog sources, events, measurements, analyses, and declared tolerances;
- timeout, termination, result state, and source-level failure identity;
- ABI, source-map, toolchain, option, and artifact provenance.

Scheduler, four-state, random-solver, analog-solver, tolerance, bridge, and unsupported-feature differences are classified explicitly.

## Foundation boundary

Foundation receives one additional minimal increment. It freezes projection identities, the projection seam, common ABI/manifest, capability negotiation, source/replay identity, and parity contracts. It does not implement renderers, runners, libraries, solver technology, or simulator adapters.

Portable Verilog, open-source digital profiles, Verilog-AMS testbench generation, open-source AMS subset qualification, UVM, UVM-MS, vendor profiles, reusable VIP, parity, and release qualification remain in the dependent verification tracks.

## Consequences

### Positive

- One Nodal HVL environment can produce a fast native run, a self-contained portable digital testbench, a standards-oriented mixed-signal testbench, digital UVM, or UVM-MS without duplicate authored verification logic.
- Generated portable Verilog can be integrated into open-source and legacy digital flows that do not embed the Nodal runtime.
- Generated Verilog-AMS collateral is preserved for capable simulators without overstating current open-source support.
- Common ABI, replay, source maps, and manifests make generated artifacts auditable and comparable.
- Future open-source UVM or Verilog-AMS improvements can be added as profiles without changing Nodal semantics.

### Costs

- Plain Verilog can represent only a bounded subset of the HVL, so capability diagnostics and replay-stream lowering are mandatory.
- A Verilog-AMS generator requires structural, analog, event, analysis, and compile-order semantics beyond the digital Verilog renderer.
- Cross-projection parity expands from two execution families to five.
- Open-source AMS qualification requires ongoing pinned tool research and may remain unavailable for a production profile.

## Rejected alternatives

### Make generated Verilog or Verilog-AMS the canonical testbench model

Rejected because target language limitations and simulator behavior would define Nodal semantics and cause drift from UVM/native execution.

### Treat a Verilog-A compiler as a complete Verilog-AMS simulator

Rejected because compiling compact or behavioral models does not supply mixed-signal top-level elaboration, digital scheduling, connect rules, analyses, or testbench execution.

### Promise open-source Verilog-AMS because a tool accepts a VAMS flag

Rejected because parsing and partial constructs are not proof of a working mixed-signal solver/testbench path.

### Generate separate hand-maintained testbenches per backend

Rejected because behavior, coverage, checks, and replay would drift and defeat the single-source objective.

### Silently downgrade unsupported verification behavior

Rejected because a passing weakened test is less trustworthy than an explicit capability error.

## References

- [Verilog-AMS 2023 standard downloads](https://www.accellera.org/downloads/standards/v-ams)
- [Accellera: About Verilog-AMS](https://www.accellera.org/activities/working-groups/systemverilog-ams/verilog-ams/about)
- [Accellera UVM downloads](https://www.accellera.org/downloads/standards/uvm)
- [Accellera UVM-MS 1.0 downloads](https://www.accellera.org/downloads/standards/uvm-ms)
- [Verilator input-language support](https://verilator.org/guide/latest/languages.html)
- [Verilator UVM support repository](https://github.com/verilator/uvm)
- [Icarus Verilog command-line language profiles](https://steveicarus.github.io/iverilog/usage/command_line_flags.html)
- [OpenVAF Verilog-A/OSDI compiler](https://github.com/pascalkuthe/OpenVAF)
- [Gnucap mixed-signal simulator](https://github.com/gnucap/gnucap)
- [Gnucap Verilog-AMS behavioural model generator](https://github.com/gnucap/gnucap-modelgen-verilog)
