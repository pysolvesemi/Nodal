# Foundation-gated FPGA and verification tracks v0.1 plan

**Status:** Normative roadmap target
**Foundation:** the main Nodal incremental roadmap
**Dependent tracks:** FPGA Productivity, Digital Verification, Analog/Mixed-Signal Verification
**Verification architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md), extended by [ADR 0025](../architecture/0025-generated-procedural-testbench-projections.md)

## Global dependency rule

The existing Nodal incremental roadmap is the **Foundation track**.

> **No FPGA Productivity, Digital Verification, or Analog/Mixed-Signal Verification implementation increment may start until every Foundation increment is complete.**

A dependent track may be researched while Foundation is incomplete, but it may not merge implementation that depends on an unfinished Foundation contract. Any architectural seam discovered during research that would otherwise block a dependent track must be added to Foundation rather than worked around inside the dependent track.

Foundation owns architecture and public contracts for:

- hardware and AMS language semantics;
- source maps, semantic identity, comments, and diagnostics;
- clocks/resets/domains/CDC/RDC;
- interfaces, roles, protocols, digital inout, and mixed-signal bridges;
- continuous-time topology/equations/state/events/analysis/environment;
- register IR and address-map metadata;
- simulation/tool-adapter SPI;
- target-neutral properties and coverage identities;
- plugin and backend capability negotiation;
- verification semantic IR and generated-language/backend seams;
- FPGA platform/resource/constraint/report/debug semantic seams.

Dependent tracks own implementations, vendor/tool adapters, generated collateral, reusable VIP, board libraries, and qualification after the barrier opens.

## Foundation additions

- [ ] **Foundation Increment 143 — Comment/documentation IR architecture and public API gate**
  - Freeze automatic capture policy for ScalaDoc and unambiguous leading Scala comments plus an explicit target-neutral comment/documentation API for guaranteed placement.
  - Define `Comment`/documentation IR with stable ID, kind, text, source span, semantic anchor, intended audience, placement/propagation policy, and original-versus-explicit provenance.
  - Separate ordinary comments from typed synthesis directives, lint waivers, attributes, pragmas, simulation exclusions, and tool commands so preserved user text cannot accidentally change hardware behavior.
  - Define semantic-design hash, presentation/comment hash, and final-artifact hash behavior; comment-only changes must not invalidate logic-equivalence evidence.
  - Define propagation through inlining, elimination, hierarchy/generate expansion, source maps, orphan-comment reporting, generated-instance duplication limits, and output profiles.

- [ ] **Foundation Increment 144 — Scala source-comment capture and Comment IR propagation**
  - Implement Scala 3 source-position/comment extraction for module/interface/port/parameter/state/memory/instance/generate/analog/constraint/verification declarations where association is unambiguous.
  - Implement the explicit comment/documentation API, stable semantic anchors, deterministic ordering, transformation propagation, source correlation, and orphan diagnostics.
  - Add negative tests for accidental directive interpretation, ambiguous ownership, eliminated anchors, and nondeterministic macro/source expansion.

- [ ] **Foundation Increment 145 — Verilog-family comment and documentation lowering**
  - Emit deterministic comments for portable Verilog, SystemVerilog, Verilog-A, and Verilog-AMS from the same Comment IR.
  - Support module/interface/port/parameter/signal/state/instance/generate/process/analog-island/node/branch/equation/contribution/event/constraint anchors where the target has a legal stable placement.
  - Generate documentation/source-correlation artifacts and comment mapping manifests; verify that comment-only changes do not alter semantic HDL hashes.
  - Keep vendor directives and constraints typed and separate from ordinary emitted comments.

- [ ] **Foundation Increment 146 — FPGA productivity architecture readiness**
  - Freeze a portable FPGA intent model with separate reusable-IP requirements, board/platform resources, and project implementation intent.
  - Define target-neutral Constraint IR categories for clocks/generated clocks/domain relationships/I-O delays/timing exceptions/I-O electrical intent/physical regions/tool intent with stable semantic targets instead of hierarchy strings.
  - Define board/device/package/bank/pin/resource schemas, vendor-capability profiles, raw-vendor escape metadata, constraint coverage/match-count evidence, normalized timing/resource/power reports, debug-probe identities, build/program provenance, and source-correlation seams.
  - Preserve deliberate safety boundaries: no heuristic false paths, no heuristic multicycle paths, no silent floorplanning, no silent unsupported constraint translation.
  - Do not implement XDC/SDC/QSF/PDC/LPF/PCF generation, vendor builds, board libraries, programming, timing-closure exploration, or debug insertion in Foundation.

- [ ] **Foundation Increment 147 — Nodal HVL Verification Semantic IR and public API architecture gate**
  - Freeze target-neutral verification semantics for tests/scenarios, transactions, typed endpoints, processes/events/time, deterministic cancellation and fork/join, drivers/monitors/agents, scoreboards/reference models, analysis streams, configuration/resources, random variables/constraints/seeds/replay, functional coverage, properties/checks, register-model bindings, reusable VIP packaging, and analog/mixed-signal verification intent.
  - Bind drivers/monitors to logical Nodal `Interface` identities and register verification to canonical Register IR identities rather than generated HDL hierarchy strings.
  - Freeze stable verification/component/transaction/check/coverage IDs, source maps, capability requirements, backend exclusions, normalized results, replay manifests, and cross-backend parity rules.
  - Reserve explicit sibling projection identities for native execution, procedural Verilog testbench, procedural Verilog-AMS testbench, UVM, and UVM-MS. Exact public spellings remain deferred to this design gate.
  - Do not implement a testbench generator, simulator, UVM library, or UVM-MS bridge in Foundation.

- [ ] **Foundation Increment 148 — Native verification runtime and procedural-testbench projection architecture readiness**
  - Freeze the Nodal verification scheduler/runtime contract independently of every generated target: simulation time, delta/event ordering, clocks, waits, processes, cancellation, timeout, deterministic seed/replay, transaction recording, coverage sampling, failure identity, and simulator callback semantics.
  - Define the simulator-adapter boundary for direct Verilator/Icarus execution and future mixed-signal adapters without requiring generated testbench source or UVM support.
  - Define a target-neutral Procedural Testbench IR sufficient for generated Verilog-2001 and Verilog-AMS 2023 testbenches: modules/bindings, variables/nets/terminals/disciplines, processes, delays/events/waits, bounded concurrency, tasks/functions, drive/sample/check/scoreboard operations, wave/results, analog stimulus/measurement/tolerance/event intent, deterministic naming, source maps, and capability diagnostics.
  - Separate generated-artifact acceptance from simulator execution evidence and freeze versioned tool capability manifests. Reserve Icarus as the digital event-driven conformance profile, Verilator as a capability-checked fast profile, and bounded research profiles for partial open-source Verilog-AMS execution; do not claim that Verilog-A model support or VAMS parsing equals full Verilog-AMS simulation.
  - Do not implement the complete native HVL runtime, production Verilog/Verilog-AMS testbench generators, co-simulation runtime, or simulator qualification in Foundation.

- [ ] **Foundation Increment 149 — UVM/UVM-MS projection and vendor-profile architecture readiness**
  - Apply ADRs 0023 and 0025 and freeze Verification Semantic IR -> UVM/UVM-MS projections as siblings of native execution and procedural Verilog/Verilog-AMS testbench projections.
  - Freeze Verification SystemVerilog IR readiness for packages/classes, interfaces, typed class/struct containers, concurrency/process control, constrained-random hooks, functional coverage, DPI/VPI boundaries, source maps, and capability diagnostics required by UVM/UVM-MS generation.
  - Define mappings for tests/envs/agents/drivers/monitors/sequencers/sequences/items/scoreboards/TLM/analysis/factory/config/phases/objections/coverage/reporting and generated UVM RAL integration.
  - Define UVM-MS structural/class bridge identities, mixed-signal endpoints, analog stimulus/monitor/measurement contracts, and capability mapping to Foundation AMS semantics.
  - Define vendor-neutral common source plus thin simulator profiles. Any required `ifdef` is confined to generated vendor adapter/include units, not common VIP/test logic.
  - Record standard version, UVM reference implementation, vendor profile, feature decisions, defines, adapter hashes, source hashes, commands, source maps, and unsupported capabilities.
  - Do not generate production UVM/UVM-MS code or vendor scripts in Foundation.

## Foundation completion barrier

The barrier opens only when every checkbox in the Foundation track, including Increments 143-149 and any later Foundation item added before release, is complete with required CI/evidence.

The three dependent tracks are then independently schedulable unless they declare additional track-local prerequisites.

---

# FPGA Productivity Track — blocked by Foundation

Numbering restarts for this track.

- [ ] **FPGA Increment 1 — FPGA public platform/resource/constraint API gate**
  - Freeze board/device/resource/project APIs against Foundation Constraint IR and Interface identities.
  - Compile positive/negative candidates for clocks, pins/banks, connectors, protocol resources, implementation intent, vendor extensions, and stable semantic constraint targets.

- [ ] **FPGA Increment 2 — Board/device/resource database and binding**
  - Implement versioned device/package/board schemas, oscillators, pins/banks/voltages, connectors, common peripherals, capability matching, project resource binding, and reproducible board provenance.

- [ ] **FPGA Increment 3 — Portable timing and I/O constraint engine**
  - Implement primary/generated clocks, declared relationships, asynchronous groups, I/O delays, explicit max/min/multicycle/false-path constraints, interface timing contracts, I/O electrical intent, stable target resolution, and constraint manifests.
  - Never infer unsafe timing exceptions heuristically.

- [ ] **FPGA Increment 4 — AMD Vivado constraint/build backend**
  - Generate ordered XDC/Tcl for timing, I/O, clocking, supported physical intent, build, report, and programming flows.
  - Validate semantic targets before/after synthesis and retain Vivado version/options/checkpoints/reports.

- [ ] **FPGA Increment 5 — Intel Quartus and open-source FPGA backends**
  - Generate SDC/QSF and Quartus build/report flows.
  - Add Yosys/nextpnr architecture-appropriate PCF/LPF/PDC/CST/clock configuration as supported by selected families.
  - Keep unsupported physical constraints explicit rather than approximated.

- [ ] **FPGA Increment 6 — Additional vendor profiles and constraint coverage**
  - Add Microchip and selected Lattice/vendor flows according to demand.
  - Implement unmatched/multi-match/stale-target, unconstrained-clock/path, exception-audit, bank-voltage, pin-conflict, generated-clock, and CDC-constraint coverage reports across all backends.

- [ ] **FPGA Increment 7 — Reproducible build, program, artifact, and normalized reporting**
  - Implement `nodal fpga build/program/report`-style flows, tool lock/provenance, cache keys, checkpoints/bitstreams, normalized timing/resource/power/clock/I-O reports, and source-correlated diagnostics.

- [ ] **FPGA Increment 8 — Timing-closure feedback and bounded design-space exploration**
  - Map critical paths and failures back to Nodal expressions, pipelines, interfaces, memories, FSMs, domains, and generated instances.
  - Produce explicit pipeline/ready-path/memory/DSP/fanout/placement recommendations with latency/protocol/evidence impact.
  - Support bounded strategy/seed exploration under user budgets and produce Pareto timing/resource/power reports; never silently mutate the accepted design.

- [ ] **FPGA Increment 9 — Vendor primitive/IP abstraction and debug instrumentation**
  - Qualify target-neutral memory/DSP/PLL/clock-buffer/I-O-delay/SERDES/FIFO/ECC/transceiver abstractions where portable contracts are possible.
  - Add typed probes/triggers and adapters for ILA, Signal Tap, and open-source analyzers with source/waveform correlation.

- [ ] **FPGA Increment 10 — Board bring-up, IP packaging, HIL, and ecosystem qualification**
  - Generate reusable IP packaging metadata, software collateral, memory/interrupt/register integration artifacts, board self-tests, programming/HIL flows, and a supported board/vendor/tool capability matrix.
  - Qualify representative complex FPGA subsystem builds across at least one commercial and one open-source flow.

---

# Digital Verification Track — blocked by Foundation

Numbering restarts for this track.

- [ ] **Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice**
  - Implement the Verification IR/native runtime vertical slice for tests, deterministic processes/time, clocks/resets, typed transactions, direct signal/interface access, failures, waveforms, seeds, and replay.
  - Run generated Verilog directly through Verilator as the primary fast adapter and Icarus as an independent event-driven adapter; generated HDL testbench or UVM source is not involved.

- [ ] **Digital Verification Increment 2 — Scenarios, sequences, constrained stimulus, and replay**
  - Implement reusable scenarios/sequence graphs, random variables/constraints/distributions, deterministic seed hierarchy, value-stream capture, exact replay, parallel stimulus, cancellation, timeout, and capability diagnostics.

- [ ] **Digital Verification Increment 3 — Agents, drivers, monitors, scoreboards, and reference models**
  - Implement active/passive agents, typed drivers/monitors bound to logical Interfaces, analysis streams, transaction correlation, protocol timing policies, scoreboards, reference-model calls, and reusable BFM/VIP base classes.

- [ ] **Digital Verification Increment 4 — Functional coverage and verification reporting**
  - Implement canonical coverpoints/bins/crosses/transition coverage where meaningful, coverage groups/sampling, stable IDs, merge/report, source mapping, exclusions/waivers, and optional UCIS interchange.

- [ ] **Digital Verification Increment 5 — Properties, protocol checks, and register-model verification**
  - Integrate the Foundation property layer with simulation checks and formal hooks.
  - Generate register frontdoor/backdoor operations, predictors, access-policy checks, reset/side-effect/collision coverage, and register scoreboards from canonical Register IR.

- [ ] **Digital Verification Increment 6 — Generated Verilog testbench projection and open-source execution**
  - Lower the supported Verification IR subset through Procedural Testbench IR into deterministic portable Verilog-2001 testbench modules, DUT bindings, clocks/resets, timed/event-driven scenarios, tasks, drivers, monitors, checks, bounded scoreboards, timeouts, plusargs/files, waves, result records, source maps, and run manifests.
  - Materialize deterministic stimulus and expected-value streams when constrained randomization, object models, or coverage databases cannot be represented in portable Verilog; reject unsupported semantics explicitly rather than weakening them.
  - Compile and run the generated Verilog DUT plus generated Verilog testbench with a pinned Icarus event-driven conformance profile. Qualify an optional Verilator `--timing` fast profile with explicit scheduler, two-state/four-state, timing-check, and unsupported-feature disclosures.
  - Prove that testbench artifact generation and simulator execution report separate pass/fail states and hashes.

- [ ] **Digital Verification Increment 7 — Verification SystemVerilog and digital UVM generation**
  - Render standards-oriented SystemVerilog/UVM from the same Verification IR: test/env/agent/driver/monitor/sequencer/sequence/item/scoreboard/TLM/factory/config/phases/objections/coverage/reporting and UVM RAL.
  - Generate reusable packages/VIP with deterministic hierarchy/names and source maps.

- [ ] **Digital Verification Increment 8 — Commercial simulator profiles**
  - Qualify thin VCS-family, Questa-family, and Xcelium-family profiles for compile/elaboration/run, DPI/VPI, UVM library selection, waves, coverage, reporting, and known compatibility workarounds.
  - Keep common UVM source identical wherever standards support it; confine unavoidable vendor `ifdef`s to adapter packages/includes.

- [ ] **Digital Verification Increment 9 — Native, Verilog-testbench, and UVM semantic parity**
  - Run the same supported Nodal HVL tests in native/open-source mode, generated-Verilog-testbench mode, and generated-UVM mode and compare stimulus/replay streams, transaction ordering, checks, scoreboards, register behavior, coverage intent/results, termination, and source-level failure IDs.
  - Classify scheduler, four-state, and random-solver differences rather than hiding them.

- [ ] **Digital Verification Increment 10 — Reusable digital VIP qualification**
  - Author representative protocol VIP only in Nodal HVL—at minimum `Valid`/`Stream`, APB, and AXI4-Lite or another approved protocol—and project the supported views to native BFM/agents, procedural Verilog testbench collateral, and UVM VIP.
  - Qualify active/passive modes, protocol assertions, constrained traffic, coverage, scoreboards, configuration, errors, reset, backpressure, and reuse in an external consumer project.

- [ ] **Digital Verification Increment 11 — Scale, performance, compatibility, and verification release gate**
  - Exercise large testbench hierarchies, many agents, long regressions, parallel tests, deterministic caching, generated-Verilog compile/runtime scale, coverage merge, UVM compile/runtime scale, and source-map performance.
  - Publish supported HVL/native/Verilog-testbench/UVM/SystemVerilog/simulator capability and limitations matrices plus a reusable VIP author conformance kit.

---

# Analog/Mixed-Signal Verification Track — blocked by Foundation

Numbering restarts for this track and remains separate from Digital Verification.

- [ ] **AMS Verification Increment 1 — Nodal HVL native mixed-signal simulation vertical slice**
  - Extend the native Verification IR/runtime to typed physical quantities, analog terminals/signal-flow values, measurements, tolerances, crossings/events, waveform stimulus, PVT/environment context, and mixed digital/analog synchronization.
  - Execute through available open Verilog-A/solver and digital adapters without requiring generated Verilog-AMS testbench or UVM-MS source.

- [ ] **AMS Verification Increment 2 — Analog/mixed-signal agents, drivers, monitors, and scoreboards**
  - Implement mixed-signal transaction schemas, analog stimulus sources, samplers/monitors, tolerance-aware scoreboards/reference models, event correlation, mixed interface bindings, and reusable active/passive agent patterns.

- [ ] **AMS Verification Increment 3 — PVT, sweeps, stochastic stimulus, and deterministic replay**
  - Integrate Foundation environment/PVT/noise/variation semantics with constrained verification scenarios, corner matrices, Monte Carlo/mismatch seeds, deterministic run manifests, failure reduction, and replay.

- [ ] **AMS Verification Increment 4 — Analog measurements and functional coverage**
  - Implement reusable measurements for amplitude/range, threshold/crossing, settling, overshoot, frequency/period/duty, jitter, gain, phase, integrated noise, SNR/ENOB-like metrics where mathematically defined, plus tolerance-aware bins and cross coverage.
  - Keep measurement definitions target neutral and retain waveform/source evidence.

- [ ] **AMS Verification Increment 5 — Mixed-signal properties and register/control interaction**
  - Add bridge/event/control-loop checks, analog envelope assertions, initialization/settling checks, mode transition verification, mixed register/control sequences, and source-level failure correlation using Foundation property and Register IR identities.

- [ ] **AMS Verification Increment 6 — Generated Verilog-AMS testbench projection**
  - Lower the supported Verification IR subset through Procedural Testbench IR into deterministic Verilog-AMS 2023 top-level testbench modules, disciplines/terminals, digital processes, analog sources/contributions, timed and continuous events, crossings/timers, measurements, tolerance-aware checks, mixed-signal bindings/connect intent, waves/results, source maps, and execution manifests.
  - Keep generated-artifact validation independent from simulator execution. Reject unsupported Nodal HVL semantics explicitly and never substitute real-number or split-domain approximations without a selected profile.
  - Prove deterministic source, stable verification/check identities, target-language validation, and parity of precomputed stimulus/expected streams.

- [ ] **AMS Verification Increment 7 — Open-source Verilog-AMS conformance profiles and explicit split-harness fallback**
  - Build positive, negative, and semantic conformance suites for exact version-pinned open-source candidates, beginning with GNUCAP/modelgen-verilog's explicitly partial Verilog-AMS support and only the documented supported subsets of Icarus/Verilator.
  - Treat OpenVAF plus ngspice as an analog-only Verilog-A model path, not evidence of arbitrary Verilog-AMS testbench execution. Publish a feature-level matrix for digital event control, analog solving, disciplines, contributions, connect behavior, crossings/timers, mixed scheduling, measurements, and failure handling.
  - Enable an open-source Verilog-AMS execution profile only for the subset that passes the conformance suite; retain full Verilog-AMS artifact generation even when no open profile can execute it.
  - Where separately approved, generate an explicitly labeled Nodal-coordinated split harness using digital Verilog testbench plus Verilog-A/SPICE analog execution. Record partition, synchronization, tolerance, and parity evidence and never report it as a full Verilog-AMS run.

- [ ] **AMS Verification Increment 8 — UVM-MS generation from Verification IR**
  - Generate UVM-MS 1.0-oriented class and structural verification collateral from the same Nodal HVL environment, including mixed-signal agents, analog stimulus/monitor bridges, scoreboards, transactions, coverage, configuration, and reuse of generated digital UVM components where appropriate.
  - Keep native simulation and procedural Verilog-AMS testbench generation independent of UVM-MS.

- [ ] **AMS Verification Increment 9 — Commercial mixed-signal simulator profiles**
  - Qualify thin VCS-family, Questa-family, and Xcelium-family AMS/UVM-MS profiles according to available licensed environments and supported standards.
  - Isolate compile/elaboration/binding/connect-rule/real-net/waveform/vendor workarounds in adapter units and manifests.
  - Qualify standards-oriented generated Verilog-AMS testbench execution in the same profile framework where the selected commercial simulator supports it.

- [ ] **AMS Verification Increment 10 — Native, Verilog-AMS-testbench, and UVM-MS semantic parity**
  - Compare the same supported Nodal HVL environment across native/open execution, qualified generated-Verilog-AMS-testbench execution, and generated-UVM-MS runs for stimulus/replay streams, transactions, analog event timing within declared tolerances, measurements, scoreboards, register/control behavior, coverage intent, termination, and failure IDs.
  - Classify solver, scheduling, tolerance, and unsupported-profile differences explicitly.

- [ ] **AMS Verification Increment 11 — Reusable AMS VIP qualification**
  - Author reusable mixed-signal VIP only in Nodal HVL for at least one converter/control-style interface and one analog-monitoring interface, then project the supported views to native agents, procedural Verilog-AMS testbench collateral, and UVM-MS VIP.
  - Qualify analog stimulus, measurements, tolerance-aware checking, digital control, mode changes, PVT/sweeps, coverage, error injection, reuse, and source correlation.

- [ ] **AMS Verification Increment 12 — Scale, performance, compatibility, and AMS verification release gate**
  - Exercise large mixed-signal verification hierarchies, long transient runs, sweep/corner matrices, deterministic caching/replay, waveform/measurement volume, generated-Verilog-AMS compile/runtime scale, UVM-MS generation/runtime scale, and result reduction.
  - Publish supported HVL/native/Verilog-AMS-testbench/UVM-MS/tool/solver capability and limitations matrices plus mixed-signal VIP authoring and conformance guidance.
