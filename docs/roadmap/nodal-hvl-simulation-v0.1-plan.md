# Nodal HVL live simulation and portable projection v0.1 plan

**Status:** Normative dependent-track roadmap target  
**Revision:** 0.1  
**Updated:** 2026-09-04  
**Foundation:** the main Nodal incremental roadmap  
**Umbrella tracks:** Digital Verification and Analog/Mixed-Signal Verification  
**Architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md), [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md), and [ADR 0026](../architecture/0026-native-digital-simulator-adapter-architecture.md)

## Purpose

Nodal shall provide a simulation experience analogous in intent to SpinalSim: the verification testbench is authored in Scala/Nodal, the Nodal runtime controls an external open-source or commercial simulator through a typed adapter, and users do not need to write simulator-specific Verilog, Verilog-AMS, SPICE, VPI/DPI, Tcl, or shell testbench code for the ordinary flow.

Generated procedural Verilog, generated Verilog-AMS, generated UVM, and generated UVM-MS remain valuable delivery and interoperability projections. They are not the native Nodal simulation foundation and must not restrict the normal live Nodal HVL experience.

## Global dependency rule

> **No implementation item in this plan may start until the complete Foundation barrier opens.**

Research, architecture review, compile-only prototypes, and tool capability probes may continue while blocked. Any missing language, semantic identity, verification IR, simulator-adapter, source-map, or mixed-domain contract discovered by that research belongs in Foundation rather than in a tool-specific workaround.

The minimal OpenVAF/ngspice compilation and smoke-simulation work already assigned to Foundation remains there because generated analog semantics must be validated with a real compiler and solver before Foundation can close. Full user-facing HVL runtime, agent, coverage, portable-projection, and commercial-adapter implementation remains in the dependent tracks.

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

## Two mandatory HVL capability classes

### 1. Live Nodal HVL

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

### 2. Portable/capturable Nodal HVL

Portable/capturable Nodal HVL is an explicit structured subset of the same public HVL, not a second language.

Its complete selected behavior can be captured into the canonical Verification Semantic IR, including supported process structure, waits, transactions, stimulus, checks, scoreboards, coverage, measurements, configuration, seeds, and source identities. It may then be lowered into one or more of:

- deterministic replay and expected-result sidecars;
- standalone procedural Verilog testbenches;
- standalone Verilog-AMS testbenches;
- practical open AMS harness bundles;
- generated digital UVM;
- generated UVM-MS;
- reusable generated verification collateral.

For each requested projection, every operation is classified as:

- **embedded** — represented directly in the generated target;
- **precomputed replay** — evaluated by Nodal and retained in a deterministic sidecar;
- **companion-runtime required** — legal only with an explicitly selected runtime-assisted profile;
- **unsupported** — projection fails before an accepted artifact is produced.

Silent omission, semantic weakening, or best-effort replacement is prohibited.

### Relationship between the classes

The two classes share public value types, Interface and Register identities, transaction schemas, drivers, monitors, scoreboards, checks, coverage models, measurement definitions, seeds, failures, source maps, and normalized results wherever their semantics overlap.

The binding rules are:

1. Live Nodal HVL remains the richer default experience.
2. Portable/capturable Nodal HVL is opt-in at a test, package, component, scenario, sequence, or explicit region boundary; exact source syntax is frozen by the Foundation public API gate.
3. A live test may call portable components.
4. A portable component may call only portable operations, deterministic precomputable helpers, or explicitly declared companion-runtime services.
5. Requesting a portable projection may reject a live-only test, but that rejection must not make the test invalid for live execution.
6. A generated backend limitation must never remove an operation from the live API or change live runtime semantics.
7. Portability is capability-checked for the selected target profile rather than represented by one misleading universal Boolean.
8. The compiler and runtime must explain the first non-portable operation, its source location, the required capability, and available legal execution modes.

## Verification IR ownership

Nodal retains one common verification semantic model, with a necessary distinction in materialization:

- portable/capturable behavior must be fully materialized as static Verification Semantic IR before projection;
- live host control may be interpreted dynamically, while each Nodal-visible operation, transaction, check, measurement, coverage sample, and result uses the same semantic schemas and stable identities and is recorded in the runtime trace;
- arbitrary Scala control flow, object graphs, or external-library internals are not reconstructed as fake portable IR;
- a trace of one live execution is evidence or replay input, not proof that the original host program is generally portable.

Separate generated-language IRs remain appropriate below the canonical semantic layer: Procedural HDL Testbench IR for standalone Verilog-family benches, verification-SystemVerilog IR for UVM/UVM-MS, solver-facing analog/equation forms, and compiled simulator execution plans. None becomes a second HVL authoring authority.

## Capability and provenance contract

Every simulator and projection profile declares machine-readable capabilities for at least:

- digital two-state/four-state values, inout, resolution, timing and event ordering;
- analog quantities, disciplines, analyses, solver controls, events, noise, tolerances and result fidelity;
- mixed-signal bridges, connect behavior, real nets, analog/digital synchronization and feedback iteration;
- direct value access, callbacks, breakpoints, run-until, cancellation and waveform formats;
- supported portable HVL operations, sidecars, companion runtime, generated language versions and methodology versions;
- tool, adapter, compiler, native toolchain and host-platform versions.

Every accepted run retains design, test, profile, tool, adapter, source-map, command, option, seed, external-input, sidecar, waveform and normalized-result identities. Unsupported, inconclusive, timeout, cancellation, license, tool-crash, adapter, compile, elaboration and simulation failures remain distinct result states.

## Detailed dependent workstreams

The workstreams below refine the existing Digital Verification and Analog/Mixed-Signal Verification umbrella increments. They do not bypass or weaken the Foundation barrier. An umbrella increment cannot close until its mapped detailed items and evidence are complete.

### LIVE — Common live Nodal HVL runtime

- [ ] **LIVE-01 — Live versus portable capability API gate**
  - Freeze the default-live and explicit-portable capability classes, scope boundaries, nesting/call rules, compile diagnostics, profile queries, source locations and compatibility policy.
  - Prove that adding a generated backend limitation cannot remove or weaken a live API operation.

- [ ] **LIVE-02 — Host scheduler, time and process runtime**
  - Implement deterministic simulation time, delta/event ordering, `fork`/join variants, waits, cancellation, timeout, finish precedence, exceptions and failure propagation for live host execution.
  - Keep ordinary Scala control outside static IR while routing Nodal scheduling operations through stable semantic identities.

- [ ] **LIVE-03 — Typed DUT endpoint and hierarchy access**
  - Implement typed logical Interface, port, register, memory, hierarchy and parameter handles independent of generated signal spelling.
  - Support width-safe digital values, physical quantities, aggregate/protocol access and source-correlated access failures.

- [ ] **LIVE-04 — Common simulator-adapter lifecycle and transport**
  - Implement discovery, capability negotiation, compile, elaborate, start, batched read/write, run-until, event subscription, analog sampling/drive where supported, waveform collection, finish, cancellation, crash recovery and cleanup.
  - Support shared-library, stable native ABI, VPI/DPI/PLI, shared-memory/IPC and controlled-process transports behind one versioned SPI without exposing them in normal tests.

- [ ] **LIVE-05 — Seeds, external effects, trace and replay**
  - Implement canonical seed hierarchy, value-stream capture, declared host/external inputs, artifact hashes, runtime command/event traces and exact replay where evidence is sufficient.
  - Diagnose undeclared nondeterminism instead of claiming reproducibility.

- [ ] **LIVE-06 — Checks, failures, waves and normalized results**
  - Implement immediate and deferred checks, tolerance-aware expectations, timeouts, source-level failures, transaction recording, waveform requests, result manifests and test-framework integration.

- [ ] **LIVE-07 — Host reference models and reusable live components**
  - Support ordinary Scala/JVM reference models, declared native/external models, reusable live drivers/monitors/scoreboards and deterministic data exchange without requiring portable capture.

- [ ] **LIVE-08 — Live runtime conformance and release gate**
  - Exercise concurrency, multiple clocks, analog time, cancellation, crashes, long regressions, parallel tests, cache reuse, large values/waves and external reference models.
  - Publish the supported live-HVL/runtime/adapter/host-platform capability and limitations matrix.

### ANA — Analog live HVL simulation

The analog live lane must reach an independently usable release before mixed-signal or generated-testbench completion. Analog users must not wait for the mixed-signal scheduler or UVM-MS.

- [ ] **ANA-01 — Open-source live analog vertical slice**
  - Compile a generated Nodal Verilog-A DUT through a qualified Verilog-A/OSDI compiler, load it in ngspice or another approved open solver, and run it under direct Nodal HVL control.
  - Drive an analog source, advance simulation, sample a typed quantity, check a result, collect a waveform and retain full evidence.

- [ ] **ANA-02 — Analog source, terminal and signal-flow access**
  - Implement typed voltage/current/physical-quantity source creation, piecewise and waveform stimulus, terminal/branch probing, parameter override and safe runtime mutation according to adapter capability.

- [ ] **ANA-03 — Analysis and analog event control**
  - Support DC/operating-point, transient, AC and approved noise analyses, requested breakpoints, timer/crossing/threshold waits, adaptive-step callback semantics, stop/resume and explicit unsupported diagnostics.

- [ ] **ANA-04 — Analog measurements and tolerance checks**
  - Implement amplitude/range, threshold/crossing, settling, overshoot, frequency/period/duty, gain, phase, integrated-noise and other mathematically defined measurements with units, tolerances and waveform evidence.

- [ ] **ANA-05 — Sweeps, PVT, variation and deterministic replay**
  - Implement parameter and environment sweeps, PVT matrices, approved Monte Carlo/mismatch/noise seeds, run manifests, failure reduction and replay without making one simulator's random engine canonical.

- [ ] **ANA-06 — Analog agents, scoreboards and coverage**
  - Implement reusable analog stimulus, monitors, tolerance-aware scoreboards/reference models, measurements as transactions, coverage bins/crosses and active/passive component patterns.

- [ ] **ANA-07 — Open-source analog qualification and portability**
  - Qualify the OpenVAF-compatible/OSDI/ngspice path and at least one independent compatible tool or solver profile where practical.
  - Separate Nodal semantic bugs, model-compiler limitations, solver differences, convergence failures and tolerance-policy differences.

- [ ] **ANA-08 — Deferred commercial analog simulator profiles**
  - Add thin licensed-tool profiles only after the open analog release gate, using the same live HVL tests and normalized result contracts.
  - Commercial availability must not block the open analog release.

- [ ] **ANA-09 — Analog live-HVL release gate**
  - Qualify representative passive, nonlinear, controlled-source, amplifier, oscillator/VCO, hierarchy, event, sweep, PVT and failure cases.
  - Publish an analog-live capability matrix and reusable conformance kit independently of mixed-signal and portable projections.

### MS — Mixed-signal live HVL simulation

The mixed-signal lane depends on LIVE, the analog-live foundation, the required digital live adapter slice, and Foundation mixed-domain semantics.

- [ ] **MS-01 — Mixed-signal bridge and synchronization contract**
  - Freeze analog/digital time ownership, adaptive timestep and digital-event coordination, threshold localization, delta/event ordering, simultaneous-event precedence, zero-time feedback iteration, convergence, rollback/rejected-step behavior and termination.
  - Define typed A2D, D2A, discrete-real, conservative/signal-flow and connect provenance with no implicit unsafe conversion.

- [ ] **MS-02 — Mixed-signal live endpoint API**
  - Extend typed DUT access with analog terminals, digital ports, real/discrete-real values, bridge configuration, thresholds, hysteresis, output levels, slew/rise/fall, `X`/`Z` policy and source-correlated capability diagnostics.

- [ ] **MS-03 — Open-source live mixed-signal vertical slice**
  - Run one Nodal HVL test against a generated mixed DUT using a qualified open analog engine plus digital co-simulation, initially preferring an engine-owned coordination path such as ngspice/XSPICE/d_cosim with Verilator or Icarus where supported.
  - Verify bidirectional analog/digital interaction, waveform alignment, deterministic termination and normalized results without a generated Verilog-AMS testbench or UVM-MS.

- [ ] **MS-04 — Bridge, connect and feedback qualification**
  - Cover ADC, DAC, comparator, sampled-data, digitally controlled source/filter/oscillator, register-controlled mode, multiple bridges, feedback loops and explicit unsupported connect/resolution cases.

- [ ] **MS-05 — Mixed-signal transactions, agents and scoreboards**
  - Implement active/passive mixed agents, analog/digital transaction schemas, event correlation, sampled and continuous measurements, tolerance-aware reference models and reusable converter/control-loop patterns.

- [ ] **MS-06 — Mixed-signal properties, coverage and register interaction**
  - Add bridge/event/control-loop checks, initialization and settling properties, mode-transition verification, register sequences, analog envelopes, mixed coverage and source-level failure correlation.

- [ ] **MS-07 — Deferred commercial AMS live adapters**
  - Qualify thin licensed simulator profiles for direct live Nodal HVL control using the common adapter SPI.
  - Keep vendor commands, licenses, connect rules, real-net workarounds and waveform formats outside common tests.

- [ ] **MS-08 — Open/commercial live semantic parity**
  - Compare common live tests across qualified open and commercial profiles using transaction identity, event timing within declared tolerances, measurements, register/control behavior, replay artifacts and failure IDs.
  - Classify solver and scheduling differences explicitly rather than forcing false bit-exact equality.

- [ ] **MS-09 — Mixed-signal live-HVL release gate**
  - Exercise multiple analog islands, several digital agents, long regressions, PVT matrices, feedback, bridge stress, waveform volume, crashes, cancellation and unsupported profiles.
  - Publish the mixed-signal live capability matrix independently of generated Verilog-AMS and UVM-MS completion.

### PORT — Portable/capturable projections and interoperability

This lane is optional for ordinary live simulation. It must never become a prerequisite for the ANA or MS live release gates except where a particular consumer explicitly requires generated collateral.

- [ ] **PORT-01 — Portable/capturable subset API and diagnostics**
  - Freeze structured capture boundaries, legal deterministic helper calls, side-effect restrictions, portable component packaging, profile queries and diagnostics for live-only calls.

- [ ] **PORT-02 — Complete Verification Semantic IR capture**
  - Capture supported tests, scenarios, sequences, process structure, transactions, agents, scoreboards, coverage, checks, measurements, configuration, seeds and source identities with deterministic serialization and round trips.

- [ ] **PORT-03 — Precomputed replay and companion-runtime lowering**
  - Generate deterministic stimulus, expected-result and measurement sidecars; distinguish standalone versus runtime-assisted profiles and retain all provenance.

- [ ] **PORT-04 — Standalone portable Verilog testbench projection**
  - Generate and qualify the supported digital subset with Icarus as the event-driven reference and a separately qualified Verilator subset.

- [ ] **PORT-05 — Standards-oriented Verilog-AMS testbench projection**
  - Generate capability-declared mixed hierarchy, analog stimulus, digital control, bridge/connect metadata, measurements, checks and termination.
  - Rendering source does not claim that an open simulator can execute the selected profile.

- [ ] **PORT-06 — Practical open AMS harness projection**
  - Generate Verilog-A/OSDI, SPICE/XSPICE, digital co-simulation, replay and result collateral from the same portable intent.
  - Keep this profile distinct from full Verilog-AMS testbench execution.

- [ ] **PORT-07 — Digital UVM generation**
  - Generate idiomatic standards-oriented UVM, reusable VIP and RAL from the portable subset while retaining stable source and semantic identities.

- [ ] **PORT-08 — UVM-MS generation**
  - Generate UVM-MS class/structural bridges and mixed-signal verification collateral from the portable subset without changing live semantics.

- [ ] **PORT-09 — Commercial generated-flow profiles**
  - Qualify thin commercial profiles for generated UVM, UVM-MS and Verilog-AMS testbenches. Confine vendor adaptations to generated adapter units and manifests.

- [ ] **PORT-10 — Projection parity and release gate**
  - Compare each applicable portable test across live execution, replay, standalone HDL, open AMS harness, Verilog-AMS and UVM/UVM-MS profiles.
  - Publish operation-level portability, sidecar, companion-runtime, language, methodology, simulator and limitations matrices.

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

LIVE + domain semantics
          |
          v
        PORT ------------------------> generated/reusable interoperability releases
```

Binding release rules:

- ANA may release after its own open-source and conformance gates even when MS and PORT remain incomplete.
- MS may release after LIVE, required digital-live support, ANA prerequisites and its own open-source gate even when PORT and commercial profiles remain incomplete.
- Commercial live adapters are deferred optional profiles and do not block open-source live release.
- PORT projections are independently selectable and do not redefine the feature set of live HVL.
- A user may use only live Nodal HVL indefinitely without accepting the portable subset.

## Mapping to existing umbrella increments

| Detailed workstream | Existing umbrella roadmap ownership |
| --- | --- |
| LIVE-01 through LIVE-08 | Foundation Increments 147-149 for architecture; Digital Verification Increments 1-5 and 12 for implementation and qualification |
| ANA-01 through ANA-09 | Foundation analog validation Increments 47-53 for minimal smoke infrastructure; AMS Verification Increments 1-4 and 12 for full dependent implementation |
| MS-01 through MS-09 | Foundation mixed-domain semantics plus Digital Verification Increment 1; AMS Verification Increments 1, 2, 5, 9-12 |
| PORT-01 through PORT-10 | Foundation Increments 147, 149 and 152; Digital Verification Increments 6-10; AMS Verification Increments 6-10 and 12 |

The mapping is many-to-many because the existing umbrella increments describe product outcomes, while this plan separates capability-class and execution dependencies. Closure evidence must identify both IDs.

## Non-goals for the initial live release

- Building a new native analog solver merely to avoid external tools.
- Requiring generated Verilog-AMS or UVM-MS to run ordinary Nodal tests.
- Translating arbitrary Scala bytecode, libraries, files or host object graphs into portable verification IR.
- Claiming full Verilog-AMS open-source support from Verilog-A parsing or partitioned co-simulation alone.
- Hiding unsupported operations, solver failures, license failures or numerical differences behind a generic pass/fail result.
- Making one simulator's callback order, random engine, waveform format or command language part of Nodal semantics.

## Completion criteria

This plan is complete only when:

1. live and portable capability classes are frozen, implemented and independently tested;
2. live simulation remains strictly richer than or equal to every generated projection rather than being limited by them;
3. analog live simulation has an independently qualified open-source release;
4. mixed-signal live simulation has an independently qualified open-source release;
5. commercial live and generated profiles remain optional, thin and capability-declared;
6. portable projections reject unsupported behavior before producing accepted artifacts;
7. cross-backend comparisons retain explicit solver, scheduler, tolerance and capability classifications; and
8. capability, provenance, reproducibility and limitations matrices are published with a reusable conformance kit.
