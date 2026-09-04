# Nodal HVL live simulation and projection capability v0.2 plan

**Status:** Normative dependent-track roadmap target  
**Revision:** 0.2  
**Updated:** 2026-09-05  
**Foundation:** the main Nodal incremental roadmap  
**Umbrella tracks:** Digital Verification and Analog/Mixed-Signal Verification  
**Architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md), [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md), and [ADR 0026](../architecture/0026-native-digital-simulator-adapter-architecture.md)

## Purpose

Nodal shall provide a simulation experience analogous in intent to SpinalSim: the verification testbench is authored in Scala/Nodal, the Nodal runtime controls an external open-source or commercial simulator through a typed adapter, and users do not need to write simulator-specific Verilog, Verilog-AMS, SPICE, VPI/DPI, Tcl, or shell testbench code for the ordinary flow.

Generated procedural Verilog, generated Verilog-AMS, generated UVM, and generated UVM-MS remain valuable delivery and interoperability outputs. They are not the native Nodal simulation foundation and must not restrict the normal live Nodal HVL experience.

This revision also freezes a second distinction:

> **Portable capture is one common semantic foundation, but generated Verilog testbenches and generated UVM are independent sibling capability profiles. They have separate public extensions, separate lowering IRs, separate implementation libraries, separate conformance suites, and separate release gates.**

UVM is not modeled as a subclass or superset of the Verilog-testbench profile. A universal library class that pretends a Verilog module/task BFM and a UVM object/component hierarchy are the same implementation object is prohibited.

## Global dependency rule

> **No implementation item in this plan may start until the complete Foundation barrier opens.**

Research, architecture review, compile-only prototypes, and tool capability probes may continue while blocked. Any missing language, semantic identity, verification IR, simulator-adapter, source-map, mixed-domain, or projection-profile contract discovered by that research belongs in Foundation rather than in a tool-specific workaround.

The minimal OpenVAF/ngspice compilation and smoke-simulation work already assigned to Foundation remains there because generated analog semantics must be validated with a real compiler and solver before Foundation can close. Full user-facing HVL runtime, agent, coverage, portable-capture, generated-testbench, UVM/UVM-MS, and commercial-adapter implementation remains in the dependent tracks.

## Binding execution model

The primary flow is:

```text
Nodal design source
        |
        v
Nodal design and compiler IR
        |
        +-----------------------------> generated DUT artifact
        |                               Verilog / Verilog-A / Verilog-AMS
        |                                           |
        v                                           v
Nodal HVL testbench -----------------> Nodal live runtime
                                                    |
                                                    v
                                      versioned simulator adapter
                                                    |
                                                    v
                                  open-source or commercial simulator
                                                    |
                                                    v
                              normalized events, values, waves and results
```

Nodal owns test scheduling, typed endpoint identity, transactions, drivers, monitors, scoreboards, reference-model calls, randomization policy, seeds, replay, checks, coverage intent, failures, and normalized results. A simulator adapter owns DUT compilation/elaboration, simulator startup, value transport, solver or event-engine callbacks, waveform access, diagnostics, cancellation, and cleanup. A mixed-signal simulator may own low-level analog/digital timestep and event iteration, but it does not define Nodal HVL semantics.

## Two independent capability axes

Nodal does not use one overloaded `portable = true` classification. Eligibility is determined on two independent axes.

### Axis A — execution capability class

#### 1. Live Nodal HVL

Live Nodal HVL is the default direct-simulation capability class.

It permits ordinary Scala and JVM behavior around typed Nodal simulation operations, including:

- ordinary Scala control flow, collections, functions, classes, and reusable libraries;
- software reference models and numerical packages;
- files and declared external data sources;
- host-side concurrency coordinated by the Nodal scheduler contract;
- dynamic generation of stimulus and expected results;
- calls into declared native or external reference-model adapters;
- runtime decisions based on DUT values, measurements, transactions, and prior results.

A live test does not need to be statically convertible into a complete verification control-flow graph before it runs. Simulator-visible operations still use stable Nodal semantic types and identities, and the runtime records sufficient commands, values, seeds, external-input provenance, checks, failures, coverage samples, and result metadata for diagnosis and supported replay.

Live-only host behavior may make exact replay dependent on recorded input streams or declared external artifacts. Undeclared nondeterministic host effects must be reported; they must never be falsely described as deterministic replay.

Live Nodal HVL shall support external open-source and commercial simulators through the same public testbench model. Selecting a commercial simulator must not require rewriting the Nodal testbench.

#### 2. Portable/capturable Nodal HVL

Portable/capturable Nodal HVL is an explicit structured subset of the same public HVL, not a second language.

Its selected behavior can be captured into the canonical Verification Semantic IR, including supported process structure, waits, transactions, stimulus, checks, scoreboards, coverage, measurements, configuration, seeds, and source identities.

For each requested projection, every operation is classified as:

- **embedded** — represented directly in the generated target;
- **precomputed replay** — evaluated by Nodal and retained in a deterministic sidecar;
- **companion-runtime required** — legal only with an explicitly selected runtime-assisted profile;
- **unsupported** — the requested projection fails before an accepted artifact is produced.

Silent omission, semantic weakening, or best-effort replacement is prohibited.

### Axis B — generated projection capability profile

Capturability alone does not imply eligibility for every generated target. A capturable package, component, scenario, sequence, or test declares and proves one or more projection profiles.

#### Portable Core profile

Portable Core contains only target-neutral verification meaning shared by applicable generated targets:

- transaction schemas and values;
- logical Interface and Register identities;
- protocol timing and ordering intent;
- deterministic process, wait, timeout, and termination intent;
- stimulus and monitor intent;
- target-neutral checks and properties;
- scoreboard comparison rules;
- reference-model input/output contracts;
- coverage intent and stable coverage identities;
- seed, replay, source-map, failure, and normalized-result identities.

Portable Core contains no UVM class names, phases, objections, factory calls, TLM implementation objects, Verilog modules, procedural tasks, plusargs, or target-language syntax.

#### Verilog-TB profile

The Verilog-TB profile is module/procedural oriented. It may support or expose explicit profile extensions for:

- generated testbench modules and wrapper modules;
- procedural tasks and functions;
- `initial`/`always`-style process realization;
- static memories and bounded static data;
- flattened Interface helpers;
- file/vector replay;
- plusargs;
- error counters, waveform controls, and deterministic termination;
- module/task/process BFM realization policies.

It does not claim support for UVM components or objects, class inheritance, virtual interfaces, factory overrides, UVM phases, objections, TLM connectivity, dynamic class-based sequences, or UVM RAL.

#### UVM profile

The UVM profile is SystemVerilog class/methodology oriented. It may support or expose explicit profile extensions for:

- component versus object identity;
- `uvm_test`, `uvm_env`, `uvm_agent`, `uvm_driver`, `uvm_monitor`, and scoreboards;
- sequence items, sequences, sequencers, and arbitration;
- build/connect/run/report lifecycle intent;
- phase participation and objections;
- TLM ports, exports, FIFOs, and analysis ports;
- virtual-interface binding;
- factory registration and explicit override intent;
- configuration/resource intent;
- covergroups, UVM reporting, transaction recording, and UVM RAL.

A UVM-specific extension may make a component UVM-only. It is not emulated in procedural Verilog merely to preserve a false universal profile.

#### AMS generated profiles

Generated Verilog-AMS testbench, open AMS harness, and UVM-MS are separate AMS projection profiles. They reuse Portable Core and relevant analog/mixed-signal semantic extensions, but each has its own capability set, lowering, adapter, conformance suite, and release evidence.

### Relationship between the axes

The binding rules are:

1. Live Nodal HVL remains the richer default experience.
2. Portable/capturable Nodal HVL is opt-in at a test, package, component, scenario, sequence, or explicit region boundary; exact source syntax is frozen by the Foundation public API gate.
3. A live test may call portable components.
4. A portable component may call only Portable Core operations, compatible profile extensions, deterministic precomputable helpers, or explicitly declared companion-runtime services.
5. Requesting a projection may reject a live-only or incompatible-profile test, but that rejection must not make the test invalid for live execution.
6. Generated backend limitations must never remove an operation from the live API or change live runtime semantics.
7. Projection eligibility is a capability set, not one universal Boolean and not an inheritance hierarchy.
8. A package may support Verilog-TB, UVM, both, or neither.
9. UVM does not inherit from Verilog-TB, and neither generated IR lowers through the other.
10. The compiler and runtime must explain the first incompatible operation, its source location, the required capability, and available legal execution or projection modes.

## Common library and profile-library boundary

Reusable verification assets shall be layered as follows:

```text
Common semantic VIP/library
        ^
        |
        +-------------------+
        |                   |
Verilog-TB projection   UVM projection
library                 library
```

The common semantic library may define transactions, protocol rules, endpoint bindings, stimulus intent, monitor intent, scoreboards, checks, coverage intent, replay contracts, and stable identities.

The Verilog-TB library owns module/task/process realization, static-buffer and file-replay policies, procedural BFM structure, standalone reporting, and Verilog-specific legality.

The UVM library owns object/component mapping, hierarchy, sequence/sequencer mapping, TLM, phases, objections, factory/configuration, virtual interfaces, coverage/reporting, and RAL realization.

The common library must not depend on either profile library. A profile library may depend on the common semantic library. No common base implementation class may contain target conditionals that switch between Verilog module behavior and UVM class behavior.

## Verification IR ownership

Nodal retains one common verification semantic foundation with explicit profile boundaries:

- Portable Core behavior is fully materialized as target-neutral Verification Semantic IR before projection.
- Live host control may be interpreted dynamically, while each Nodal-visible operation, transaction, check, measurement, coverage sample, and result uses common semantic schemas and stable identities and is recorded in the runtime trace.
- Arbitrary Scala control flow, object graphs, or external-library internals are not reconstructed as fake portable IR.
- A trace of one live execution is evidence or replay input, not proof that the original host program is generally portable.
- Explicit Verilog-TB or UVM authoring extensions, when permitted, are namespaced profile operations and make profile eligibility visible. They are not added to Portable Core.
- Target-specific profile operations do not create parity obligations against targets that cannot express them.

Separate generated-language IRs are mandatory below the canonical semantic layer:

```text
Portable Core Verification Semantic IR
          |                         |
          v                         v
Verilog-TB capability mapper    UVM capability mapper
          |                         |
          v                         v
Procedural HDL TB IR           Verification SystemVerilog IR
          |                         |
          v                         v
Verilog renderer              UVM generator
```

There is no supported lowering path from UVM IR to Procedural HDL Testbench IR or from Procedural HDL Testbench IR to UVM IR.

Solver-facing analog/equation forms, AMS harness plans, and compiled simulator execution plans remain separate lower-level representations. None becomes a second HVL authoring authority.

## Capability and provenance contract

Every simulator and projection profile declares machine-readable capabilities for at least:

- digital two-state/four-state values, inout, resolution, timing, and event ordering;
- Portable Core operation support;
- Verilog-TB module/task/process/file/replay/wave/report capabilities;
- UVM object/component/sequence/TLM/phase/factory/config/coverage/RAL capabilities;
- analog quantities, disciplines, analyses, solver controls, events, noise, tolerances, and result fidelity;
- mixed-signal bridges, connect behavior, real nets, analog/digital synchronization, and feedback iteration;
- direct value access, callbacks, breakpoints, run-until, cancellation, and waveform formats;
- sidecars, companion runtime, generated language versions, methodology versions, and reference-library versions;
- tool, adapter, compiler, native toolchain, and host-platform versions.

Every accepted run or generated artifact retains design, test, capability-set, profile, tool, adapter, source-map, command, option, seed, external-input, sidecar, waveform, and normalized-result identities. Unsupported, inconclusive, timeout, cancellation, license, tool-crash, adapter, compile, elaboration, generation, and simulation failures remain distinct result states.

## Detailed dependent workstreams

The workstreams below refine the existing Digital Verification and Analog/Mixed-Signal Verification umbrella increments. They do not bypass or weaken the Foundation barrier. An umbrella increment cannot close until its mapped detailed items and evidence are complete.

For compatibility with roadmap revision 0.1, the old label `PORT` is an umbrella alias for `CAP + VTB + UVM + AMSP + XPAR`. `PORT` is not a capability class, generated profile, implementation library, or release gate, and new closure evidence must use the refined workstream IDs.

### LIVE — Common live Nodal HVL runtime

- [ ] **LIVE-01 — Live versus portable capability API gate**
  - Freeze the default-live and explicit-portable execution classes, scope boundaries, nesting/call rules, compile diagnostics, profile queries, source locations, and compatibility policy.
  - Freeze the independent projection-profile capability-set model without requiring one generated target.
  - Prove that adding a generated backend limitation cannot remove or weaken a live API operation.

- [ ] **LIVE-02 — Host scheduler, time, and process runtime**
  - Implement deterministic simulation time, delta/event ordering, `fork`/join variants, waits, cancellation, timeout, finish precedence, exceptions, and failure propagation for live host execution.
  - Keep ordinary Scala control outside static IR while routing Nodal scheduling operations through stable semantic identities.

- [ ] **LIVE-03 — Typed DUT endpoint and hierarchy access**
  - Implement typed logical Interface, port, register, memory, hierarchy, and parameter handles independent of generated signal spelling.
  - Support width-safe digital values, physical quantities, aggregate/protocol access, and source-correlated access failures.

- [ ] **LIVE-04 — Common simulator-adapter lifecycle and transport**
  - Implement discovery, capability negotiation, compile, elaborate, start, batched read/write, run-until, event subscription, analog sampling/drive where supported, waveform collection, finish, cancellation, crash recovery, and cleanup.
  - Support shared-library, stable native ABI, VPI/DPI/PLI, shared-memory/IPC, and controlled-process transports behind one versioned SPI without exposing them in normal tests.

- [ ] **LIVE-05 — Seeds, external effects, trace, and replay**
  - Implement canonical seed hierarchy, value-stream capture, declared host/external inputs, artifact hashes, runtime command/event traces, and exact replay where evidence is sufficient.
  - Diagnose undeclared nondeterminism instead of claiming reproducibility.

- [ ] **LIVE-06 — Checks, failures, waves, and normalized results**
  - Implement immediate and deferred checks, tolerance-aware expectations, timeouts, source-level failures, transaction recording, waveform requests, result manifests, and test-framework integration.

- [ ] **LIVE-07 — Host reference models and reusable live components**
  - Support ordinary Scala/JVM reference models, declared native/external models, reusable live drivers/monitors/scoreboards, and deterministic data exchange without requiring portable capture.

- [ ] **LIVE-08 — Live runtime conformance and release gate**
  - Exercise concurrency, multiple clocks, analog time, cancellation, crashes, long regressions, parallel tests, cache reuse, large values/waves, and external reference models.
  - Publish the supported live-HVL/runtime/adapter/host-platform capability and limitations matrix.

### ANA — Analog live HVL simulation

The analog live lane must reach an independently usable release before mixed-signal or generated-testbench completion. Analog users must not wait for the mixed-signal scheduler or UVM-MS.

- [ ] **ANA-01 — Open-source live analog vertical slice**
  - Compile a generated Nodal Verilog-A DUT through a qualified Verilog-A/OSDI compiler, load it in ngspice or another approved open solver, and run it under direct Nodal HVL control.
  - Drive an analog source, advance simulation, sample a typed quantity, check a result, collect a waveform, and retain full evidence.

- [ ] **ANA-02 — Analog source, terminal, and signal-flow access**
  - Implement typed voltage/current/physical-quantity source creation, piecewise and waveform stimulus, terminal/branch probing, parameter override, and safe runtime mutation according to adapter capability.

- [ ] **ANA-03 — Analysis and analog event control**
  - Support DC/operating-point, transient, AC, and approved noise analyses, requested breakpoints, timer/crossing/threshold waits, adaptive-step callback semantics, stop/resume, and explicit unsupported diagnostics.

- [ ] **ANA-04 — Analog measurements and tolerance checks**
  - Implement amplitude/range, threshold/crossing, settling, overshoot, frequency/period/duty, gain, phase, integrated-noise, and other mathematically defined measurements with units, tolerances, and waveform evidence.

- [ ] **ANA-05 — Sweeps, PVT, variation, and deterministic replay**
  - Implement parameter and environment sweeps, PVT matrices, approved Monte Carlo/mismatch/noise seeds, run manifests, failure reduction, and replay without making one simulator's random engine canonical.

- [ ] **ANA-06 — Analog agents, scoreboards, and coverage**
  - Implement reusable analog stimulus, monitors, tolerance-aware scoreboards/reference models, measurements as transactions, coverage bins/crosses, and active/passive component patterns.

- [ ] **ANA-07 — Open-source analog qualification and portability**
  - Qualify the OpenVAF-compatible/OSDI/ngspice path and at least one independent compatible tool or solver profile where practical.
  - Separate Nodal semantic bugs, model-compiler limitations, solver differences, convergence failures, and tolerance-policy differences.

- [ ] **ANA-08 — Deferred commercial analog simulator profiles**
  - Add thin licensed-tool profiles only after the open analog release gate, using the same live HVL tests and normalized result contracts.
  - Commercial availability must not block the open analog release.

- [ ] **ANA-09 — Analog live-HVL release gate**
  - Qualify representative passive, nonlinear, controlled-source, amplifier, oscillator/VCO, hierarchy, event, sweep, PVT, and failure cases.
  - Publish an analog-live capability matrix and reusable conformance kit independently of mixed-signal and generated projections.

### MS — Mixed-signal live HVL simulation

The mixed-signal lane depends on LIVE, the analog-live foundation, the required digital live adapter slice, and Foundation mixed-domain semantics.

- [ ] **MS-01 — Mixed-signal bridge and synchronization contract**
  - Freeze analog/digital time ownership, adaptive timestep and digital-event coordination, threshold localization, delta/event ordering, simultaneous-event precedence, zero-time feedback iteration, convergence, rollback/rejected-step behavior, and termination.
  - Define typed A2D, D2A, discrete-real, conservative/signal-flow, and connect provenance with no implicit unsafe conversion.

- [ ] **MS-02 — Mixed-signal live endpoint API**
  - Extend typed DUT access with analog terminals, digital ports, real/discrete-real values, bridge configuration, thresholds, hysteresis, output levels, slew/rise/fall, `X`/`Z` policy, and source-correlated capability diagnostics.

- [ ] **MS-03 — Open-source live mixed-signal vertical slice**
  - Run one Nodal HVL test against a generated mixed DUT using a qualified open analog engine plus digital co-simulation, initially preferring an engine-owned coordination path such as ngspice/XSPICE/d_cosim with Verilator or Icarus where supported.
  - Verify bidirectional analog/digital interaction, waveform alignment, deterministic termination, and normalized results without a generated Verilog-AMS testbench or UVM-MS.

- [ ] **MS-04 — Bridge, connect, and feedback qualification**
  - Cover ADC, DAC, comparator, sampled-data, digitally controlled source/filter/oscillator, register-controlled mode, multiple bridges, feedback loops, and explicit unsupported connect/resolution cases.

- [ ] **MS-05 — Mixed-signal transactions, agents, and scoreboards**
  - Implement active/passive mixed agents, analog/digital transaction schemas, event correlation, sampled and continuous measurements, tolerance-aware reference models, and reusable converter/control-loop patterns.

- [ ] **MS-06 — Mixed-signal properties, coverage, and register interaction**
  - Add bridge/event/control-loop checks, initialization and settling properties, mode-transition verification, register sequences, analog envelopes, mixed coverage, and source-level failure correlation.

- [ ] **MS-07 — Deferred commercial AMS live adapters**
  - Qualify thin licensed simulator profiles for direct live Nodal HVL control using the common adapter SPI.
  - Keep vendor commands, licenses, connect rules, real-net workarounds, and waveform formats outside common tests.

- [ ] **MS-08 — Open/commercial live semantic parity**
  - Compare common live tests across qualified open and commercial profiles using transaction identity, event timing within declared tolerances, measurements, register/control behavior, replay artifacts, and failure IDs.
  - Classify solver and scheduling differences explicitly rather than forcing false bit-exact equality.

- [ ] **MS-09 — Mixed-signal live-HVL release gate**
  - Exercise multiple analog islands, several digital agents, long regressions, PVT matrices, feedback, bridge stress, waveform volume, crashes, cancellation, and unsupported profiles.
  - Publish the mixed-signal live capability matrix independently of generated Verilog-AMS and UVM-MS completion.

### CAP — Common portable capture

CAP is the only shared generated-projection foundation. It captures target-neutral meaning and does not implement Verilog-TB or UVM methodology mechanisms.

- [ ] **CAP-01 — Portable Core API and capability-set gate**
  - Freeze Portable Core boundaries, capability IDs, scope and package declarations, profile eligibility, nesting/call rules, deterministic helper calls, side-effect restrictions, and source-correlated diagnostics.
  - Prohibit a universal `portable` Boolean and prohibit inheritance between Verilog-TB and UVM profiles.

- [ ] **CAP-02 — Complete common Verification Semantic IR capture**
  - Capture supported tests, scenarios, sequences, process structure, transactions, endpoint bindings, stimulus/monitor intent, checks, scoreboards, coverage intent, configuration, seeds, and source identities with deterministic serialization and round trips.
  - Keep UVM methodology objects and Verilog module/task realization out of Portable Core.

- [ ] **CAP-03 — Precomputed replay and companion-runtime lowering**
  - Generate deterministic stimulus, expected-result, reference-model, and measurement sidecars.
  - Distinguish standalone versus runtime-assisted profiles and retain all provenance.

- [ ] **CAP-04 — Common identity, source-map, result, and package contracts**
  - Freeze stable transaction/check/coverage/failure identities, logical Interface/Register binding, normalized result schemas, source maps, capability manifests, package dependencies, and common-library ABI.
  - Enforce the one-way dependency from profile libraries to common semantic libraries.

- [ ] **CAP-05 — Portable Core conformance and release gate**
  - Qualify common capture, serialization, replay, deterministic hashes, profile-set inference, incompatible-operation diagnostics, and external consumer packaging.
  - Prove that a package may support Verilog-TB, UVM, both, or neither without changing common semantics.

### VTB — Generated procedural Verilog testbench

VTB is an independent module/procedural profile. It does not emulate UVM methodology.

- [ ] **VTB-01 — Verilog-TB capability API and legality matrix**
  - Freeze Verilog-TB-specific extension boundaries for modules, tasks/functions, processes, static storage, file/vector replay, plusargs, waves, termination, and procedural BFM policies.
  - Reject class, factory, TLM, phase, objection, virtual-interface, and other UVM-only requirements.

- [ ] **VTB-02 — Procedural HDL Testbench IR and lowering**
  - Lower eligible Portable Core plus explicit Verilog-TB extensions into a dedicated Procedural HDL Testbench IR.
  - Preserve stable identities, source maps, capability decisions, replay provenance, and deterministic naming.

- [ ] **VTB-03 — Module, task, process, and BFM generation**
  - Generate deterministic DUT wrappers, clocks/resets, flattened Interface helpers, tasks/functions, bounded processes, monitors/checks, procedural scoreboards where legal, and error handling.
  - Keep generated implementation module-based; do not insert a hidden UVM-like object model.

- [ ] **VTB-04 — Open-source execution qualification**
  - Qualify Icarus as the required standalone event-driven reference for DUT plus generated `tb.v`.
  - Qualify a declared Verilator timing/testbench subset separately with differential fixtures for timing, event ordering, two-state/four-state differences, inout, file I/O, waves, timeout, and termination.

- [ ] **VTB-05 — Replay, wave, reporting, and commercial runner profiles**
  - Qualify static data, `$readmem*`/approved file I/O, deterministic expected-result replay, plusargs, VCD/FST, pass/fail reporting, and optional thin commercial procedural-testbench runners.
  - Keep commercial availability optional for the VTB release gate.

- [ ] **VTB-06 — Reusable Verilog-TB VIP qualification**
  - Project representative common VIP into module/task/process BFMs where expressible.
  - Permit Verilog-TB-specific reusable packages without requiring a corresponding UVM projection.

- [ ] **VTB-07 — Verilog-TB release gate**
  - Exercise protocol traffic, stalls, errors, reset, inout, multiple clocks, replay volume, long runs, source maps, and independent consumer compilation.
  - Publish the Verilog-TB operation, tool, language, performance, and limitations matrix.

### UVM — Generated digital UVM

UVM is an independent class/methodology profile. It does not lower through or inherit from the Verilog-TB profile.

- [ ] **UVM-01 — UVM capability API and extension boundary**
  - Freeze UVM-specific profile operations for component/object identity, sequences, lifecycle, TLM, factory, configuration/resources, virtual interfaces, coverage/reporting, and RAL.
  - Keep Portable Core target neutral and diagnose UVM-only components as ineligible for VTB unless an independent VTB implementation is supplied.

- [ ] **UVM-02 — Verification SystemVerilog IR**
  - Implement the generated-language IR needed for packages, classes, inheritance, polymorphism, parameterized classes, interfaces/virtual interfaces, clocking blocks, dynamic containers, tasks/functions, concurrency, constraints, covergroups, properties, DPI/VPI shims, source maps, and deterministic naming.
  - Do not route UVM generation through Procedural HDL Testbench IR.

- [ ] **UVM-03 — Object, component, and hierarchy generation**
  - Generate idiomatic `uvm_test`, env, agent, driver, monitor, sequencer, scoreboard, sequence-item, and supporting object/component classes.
  - Preserve logical Interface/Register identities independently of generated class and hierarchy spelling.

- [ ] **UVM-04 — Sequences, sequencers, drivers, and monitors**
  - Map Portable Core scenarios and transactions to sequence items, sequences, sequencer arbitration, drivers, monitors, and virtual-interface access.
  - Permit explicit UVM-only sequence capabilities without requiring procedural Verilog emulation.

- [ ] **UVM-05 — Factory, configuration, phases, objections, and TLM**
  - Implement factory registration and explicit overrides, configuration/resource mapping, phase participation, objections, TLM ports/exports/FIFOs, and analysis connectivity.
  - Keep these mechanisms in the UVM profile and generated UVM libraries.

- [ ] **UVM-06 — Coverage, reporting, transaction recording, and RAL**
  - Generate covergroups or approved sampling code, UVM reporting and recording, register adapters/predictors, and UVM RAL from canonical Register IR.
  - Retain stable Nodal coverage, check, transaction, register, and failure identities.

- [ ] **UVM-07 — Commercial simulator profiles**
  - Qualify thin VCS-family, Questa-family, and Xcelium-family UVM profiles for compile/elaboration/run, UVM reference-library selection, DPI/VPI, waves, coverage, reporting, and known compatibility workarounds.
  - Keep common generated UVM source identical wherever standards support it and confine unavoidable vendor `ifdef`s to adapter packages/includes.

- [ ] **UVM-08 — Reusable generated UVM VIP qualification**
  - Generate reusable standards-oriented UVM VIP from representative common semantic VIP.
  - Permit UVM-specific VIP and extension packages without requiring a corresponding Verilog-TB implementation.

- [ ] **UVM-09 — UVM release gate**
  - Exercise hierarchy, factory/configuration, phases/objections, TLM, constrained sequences, coverage, RAL, reporting, source maps, scale, reuse, and external consumer integration.
  - Publish the UVM operation, methodology, reference-library, simulator, performance, and limitations matrix.

### AMSP — Generated analog/mixed-signal profiles

AMSP preserves generated AMS interoperability as independent profiles rather than folding it into VTB or digital UVM.

- [ ] **AMSP-01 — AMS portable extension and profile gate**
  - Freeze capturable quantities, analog stimulus, crossings/events, measurements, tolerances, PVT/sweep, bridge/connect, real-net, and mixed-signal transaction capabilities.
  - Declare separate eligibility for Verilog-AMS testbench, open AMS harness, and UVM-MS profiles.

- [ ] **AMSP-02 — Standards-oriented Verilog-AMS testbench projection**
  - Generate capability-declared mixed hierarchy, analog stimulus contributions, digital control, bridge/connect metadata, events, measurements, tolerances, checks, termination, source maps, and sidecars.
  - Rendering source does not claim that an open simulator can execute the profile.

- [ ] **AMSP-03 — Practical open AMS harness projection**
  - Generate supported Verilog-A/OSDI, SPICE/XSPICE, digital co-simulation, replay, and normalized-result collateral.
  - Keep this profile distinct from full Verilog-AMS testbench execution and from live Nodal adapters.

- [ ] **AMSP-04 — UVM-MS generation**
  - Generate UVM-MS class/structural bridges, mixed-signal agents, analog stimulus/monitor bridges, scoreboards, transactions, coverage, configuration, and reuse of eligible generated digital UVM components.
  - Keep UVM-MS profile operations distinct from ordinary digital UVM and from Verilog-AMS procedural realization.

- [ ] **AMSP-05 — Commercial generated-flow profiles**
  - Qualify thin commercial profiles for generated Verilog-AMS testbench and UVM-MS execution.
  - Confine vendor adaptation, binding, connect rules, real-net workarounds, licenses, and waveform details to profile adapters and manifests.

- [ ] **AMSP-06 — Reusable generated AMS VIP qualification**
  - Project at least one converter/control-loop-oriented common VIP into each eligible AMS profile.
  - Permit profile-specific generated VIP without requiring all AMS profiles to be implemented by one source package.

- [ ] **AMSP-07 — Generated AMS release gate**
  - Exercise mixed hierarchy, analog/digital bridges, measurements, PVT, replay, source maps, tool capability rejection, external consumers, and profile-specific scale.
  - Publish separate Verilog-AMS-testbench, open-harness, and UVM-MS capability and limitations matrices.

### XPAR — Cross-projection parity

XPAR compares only the explicitly shared semantic subset. Target-specific methodology or implementation behavior is not forced into false equivalence.

- [ ] **XPAR-01 — Common-subset parity contract**
  - Freeze the exact comparison domain: deterministic stimulus/value streams, transaction values/order, protocol behavior, checks, scoreboard decisions, register behavior, common coverage intent, timeout/termination, replay artifacts, and stable failure IDs.
  - Exclude target-specific UVM factory/phase/TLM behavior and Verilog-TB module/task extensions unless an explicit semantic correspondence is defined.

- [ ] **XPAR-02 — Live versus Verilog-TB parity**
  - Compare eligible Portable Core tests across live Verilator/Icarus and generated VTB execution.
  - Classify scheduler, timing, two-state/four-state, inout, file-I/O, and unsupported-profile differences.

- [ ] **XPAR-03 — Live versus UVM parity**
  - Compare eligible Portable Core tests across live execution and generated UVM.
  - Classify random-solver, phase scheduling, coverage implementation, simulator, and companion-runtime differences.

- [ ] **XPAR-04 — AMS projection parity**
  - Compare eligible common AMS semantics across live/open execution, open harness, generated Verilog-AMS, and UVM-MS within declared tolerances.
  - Classify solver, scheduling, connect, bridge, real-net, and unsupported-feature differences.

- [ ] **XPAR-05 — Cross-projection release gate**
  - Publish profile-pair applicability, common-subset coverage, exclusions, parity results, failure classifications, and source-level correlation.
  - Never report a target-specific feature as a parity failure merely because another profile has no equivalent.

## Dependency and release ordering

```text
Complete Foundation barrier
          |
          v
        LIVE
       /    \
      v      v
Digital     ANA ---------------------> analog-live release
 live        |
      \      v
       +---- MS ---------------------> mixed-signal-live release
             \
              +----------------------> deferred commercial live profiles

LIVE-01 + Foundation capture seams
          |
          v
        CAP -------------------------> Portable Core release
       /   \
      v     v
    VTB     UVM ---------------------> independent digital generated releases
      \     /
       v   v
       XPAR -------------------------> common-subset parity release

CAP + analog/mixed-signal semantics
          |
          v
        AMSP ------------------------> independent generated AMS releases
          |
          v
        XPAR
```

Binding release rules:

- ANA may release after its own open-source and conformance gates even when MS, CAP, VTB, UVM, AMSP, and XPAR remain incomplete.
- MS may release after LIVE, required digital-live support, ANA prerequisites, and its own open-source gate even when generated profiles and commercial profiles remain incomplete.
- CAP may release without VTB, UVM, or AMSP completion.
- VTB and UVM release independently; neither is a prerequisite or implementation base for the other.
- Commercial live adapters and generated commercial profiles are deferred optional profiles and do not block open-source live or VTB releases.
- XPAR compares only profile intersections and does not define the feature set of any profile.
- A user may use only live Nodal HVL indefinitely without accepting portable capture or generated projections.

## Mapping to existing umbrella increments

| Detailed workstream | Existing umbrella roadmap ownership |
| --- | --- |
| LIVE-01 through LIVE-08 | Foundation Increments 147-149 for architecture; Digital Verification Increments 1-5 and 12 for implementation and qualification |
| ANA-01 through ANA-09 | Foundation analog validation Increments 47-53 for minimal smoke infrastructure; AMS Verification Increments 1-4 and 12 for full dependent implementation |
| MS-01 through MS-09 | Foundation mixed-domain semantics plus Digital Verification Increment 1; AMS Verification Increments 1, 2, 5, and 9-12 |
| CAP-01 through CAP-05 | Foundation Increments 147 and 152; Digital Verification Increments 2-6, 8, 10, and 12; AMS Verification Increments 2-8, 10, and 12 |
| VTB-01 through VTB-07 | Foundation Increment 152; Digital Verification Increments 6, 7, 11, and 12 |
| UVM-01 through UVM-09 | Foundation Increment 149; Digital Verification Increments 8, 9, 11, and 12 |
| AMSP-01 through AMSP-07 | Foundation Increments 149 and 152; AMS Verification Increments 6-9, 11, and 12 |
| XPAR-01 through XPAR-05 | Digital Verification Increments 10 and 12; AMS Verification Increments 10 and 12 |

The mapping is many-to-many because the existing umbrella increments describe product outcomes, while this plan separates execution classes, semantic capture, profile capability sets, generated implementations, and release dependencies. Closure evidence must identify both IDs.

## Non-goals for the initial live and generated releases

- Building a new native analog solver merely to avoid external tools.
- Requiring generated Verilog-AMS, UVM, or UVM-MS to run ordinary Nodal tests.
- Translating arbitrary Scala bytecode, libraries, files, or host object graphs into portable verification IR.
- Claiming full Verilog-AMS open-source support from Verilog-A parsing or partitioned co-simulation alone.
- Hiding unsupported operations, solver failures, license failures, or numerical differences behind a generic pass/fail result.
- Making one simulator's callback order, random engine, waveform format, or command language part of Nodal semantics.
- Making UVM inherit from Verilog-TB or using one common target-implementation class for both.
- Emulating UVM factory, phases, objections, TLM, or dynamic objects in procedural Verilog merely to preserve a universal capability claim.
- Weakening UVM into the Verilog lowest common denominator.
- Requiring every common VIP to support every generated profile.

## Completion criteria

This plan is complete only when:

1. live and portable execution classes are frozen, implemented, and independently tested;
2. Portable Core and every generated projection profile have distinct capability IDs and eligibility diagnostics;
3. Verilog-TB and UVM have separate public extension boundaries, lowerings, implementation libraries, conformance suites, and release gates;
4. no UVM lowering passes through Procedural HDL Testbench IR and no VTB lowering passes through Verification SystemVerilog IR;
5. common semantic libraries have no dependency on profile libraries;
6. a package can truthfully support VTB, UVM, both, or neither;
7. live simulation remains strictly richer than or equal to every generated projection rather than being limited by them;
8. analog live simulation has an independently qualified open-source release;
9. mixed-signal live simulation has an independently qualified open-source release;
10. commercial live and generated profiles remain optional, thin, and capability-declared;
11. every generated projection rejects unsupported behavior before producing accepted artifacts;
12. cross-projection parity covers only the explicit common semantic intersection and classifies scheduler, methodology, solver, tolerance, and capability differences; and
13. capability, provenance, reproducibility, profile dependency, and limitations matrices are published with reusable conformance kits.
