# Foundation-gated FPGA and verification tracks v0.1 plan

**Status:** Normative roadmap target
**Revision:** 0.4
**Updated:** 2026-09-04
**Foundation:** the main Nodal incremental roadmap
**Dependent tracks:** FPGA Productivity, Digital Verification, Analog/Mixed-Signal Verification
**Verification architecture:** [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md)
**Direct HDL testbench architecture:** [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md)
**Native digital simulator architecture:** [ADR 0026](../architecture/0026-native-digital-simulator-adapter-architecture.md)
**Live/portable HVL capability and sequencing plan:** [`nodal-hvl-simulation-v0.1-plan.md`](nodal-hvl-simulation-v0.1-plan.md)
**Machine-readable HVL capability surface:** [`nodal-hvl-simulation-v0.1-surface.json`](nodal-hvl-simulation-v0.1-surface.json)
**Feasibility and staging detail:** [`generated-hdl-testbench-projections-v0.1-plan.md`](generated-hdl-testbench-projections-v0.1-plan.md)
**Native adapter staging detail:** [`native-digital-simulator-adapters-v0.1-plan.md`](native-digital-simulator-adapters-v0.1-plan.md)

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
- verification semantic IR and native/procedural-HDL/UVM/UVM-MS projection seams;
- FPGA platform/resource/constraint/report/debug semantic seams.

Dependent tracks own implementations, vendor/tool adapters, generated collateral, reusable VIP, board libraries, and qualification after the barrier opens.

## Primary Nodal HVL execution model and capability classes

The normal Nodal simulation flow is a Scala/Nodal testbench executed by the Nodal live runtime while an external open-source or commercial simulator executes the DUT through a typed adapter. Generated Verilog, Verilog-AMS, UVM, and UVM-MS testbenches are optional interoperability projections; they are not the simulation foundation.

Nodal defines two mandatory capability classes:

- **Live Nodal HVL** is the default. It may use ordinary Scala control flow, collections, classes, software reference models, declared files or external artifacts, and host-side decisions around typed Nodal simulation operations. Its complete host control graph does not need to be statically capturable before execution.
- **Portable/capturable Nodal HVL** is an explicit structured subset of the same public language. Its selected behavior is fully captured into Verification Semantic IR and may be projected to deterministic sidecars, standalone Verilog or Verilog-AMS testbenches, an open AMS harness, UVM, or UVM-MS.

Both classes share Nodal value types, logical Interface and Register identities, transactions, drivers, monitors, scoreboards, checks, coverage, measurements, seeds, failures, source maps, and normalized results wherever their semantics overlap. Live execution records Nodal-visible commands, events, values and outcomes using those semantic identities, but arbitrary Scala internals are not reconstructed as fake portable IR.

Every requested portable projection classifies each operation as directly embedded, precomputed replay, companion-runtime-required, or unsupported. A projection may reject a live-only test, but the same test remains valid for live execution. Generated-backend limitations must never remove or weaken live Nodal HVL operations.

The detailed capability, dependency and independent analog/mixed-signal release gates are normative in [`nodal-hvl-simulation-v0.1-plan.md`](nodal-hvl-simulation-v0.1-plan.md).

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
  - Freeze target-neutral verification semantics for tests/scenarios, transactions, typed endpoints, processes/events/time, deterministic cancellation and fork/join, drivers/monitors/agents, scoreboards/reference models, analysis streams, configuration/resources, random variables/constraints/seeds/replay, functional coverage, properties/checks, register-model bindings, and reusable VIP packaging.
  - Freeze **Live Nodal HVL** as the default host-executed class: ordinary Scala and declared host/reference-model behavior may dynamically control typed Nodal operations without requiring the complete host control graph to be statically captured.
  - Freeze **Portable/capturable Nodal HVL** as an explicit structured subset of the same public language whose complete selected behavior is materialized in Verification Semantic IR for replay, standalone HDL, open-harness, UVM, or UVM-MS projection.
  - Define capability boundaries, nesting/call rules, deterministic precomputation, companion-runtime declarations, undeclared host-effect diagnostics, and source-correlated reasons when a live-only operation prevents a selected projection.
  - Bind drivers/monitors to the logical Nodal `Interface` ABI rather than generated signal names; bind register verification to canonical Register IR identities.
  - Define source maps, stable verification/component/transaction/coverage IDs, capability requirements, backend exclusions, normalized results, replay manifests, and cross-backend parity rules shared by both capability classes.
  - Define analog/mixed-signal verification extensions for quantities, tolerances, measurements, crossings/events, PVT/sweep context, and bridge provenance without implementing UVM-MS.
  - Prove by compile-positive and compile-negative fixtures that generated-target limitations reject only the requested projection and never invalidate supported live execution.

- [ ] **Foundation Increment 148 — Native verification runtime and generated-SystemVerilog IR readiness**
  - Accept ADR 0026 and freeze the Nodal verification scheduler/runtime contract independently of UVM: simulation time, delta/event ordering, clocks, waits, processes, cancellation, timeout, deterministic seed/replay, transaction recording, coverage sampling, failure identity, and simulator callback semantics.
  - Freeze live host-side Nodal HVL execution as the primary runtime path. Generated procedural HDL, UVM, and UVM-MS are not required to compile or run an ordinary live test.
  - Freeze common adapter ownership: Nodal owns HVL scheduling, transactions, randomization, coverage, checks, logical endpoint identity, external-input provenance, replay policy and normalized results; a simulator adapter owns only DUT compilation/elaboration, evaluation or solver execution, signal/quantity access, callbacks, waves, diagnostics and cleanup.
  - Freeze the primary Verilator path as generated Verilog -> Verilator C++ DUT model -> generated stable C ABI -> cached native shared library -> JVM FFI/JNI. Nodal HVL remains in the Nodal runtime; Verilator-generated C++ classes are not a public ABI and VPI is not the default fast access path.
  - Freeze the independent Icarus path as generated Verilog -> `iverilog` -> VVP image -> external `vvp` process -> versioned VPI module -> shared-memory or versioned IPC transport. Icarus does not require DUT-to-C++ translation; native C/C++ is limited to the adapter.
  - Freeze deterministic write/evaluate/settle/event synchronization barriers, width-safe value transport, four-state/profile handling, time conversion, timeout/cancellation/finish precedence, crash recovery, and explicit scheduler-difference classification.
  - Reserve equivalent typed lifecycle, drive/sample, run-until, breakpoint/event, waveform and normalized-result seams for later analog and mixed-signal open/commercial adapters without making one solver ABI canonical.
  - Freeze separate Verilator model, Icarus VVP image, adapter, and run cache identities plus tool/profile/source-map/command/result manifests; test seeds that do not alter the DUT must not force Verilator recompilation.
  - Define a verification-SystemVerilog IR sufficient for generated UVM/VIP: packages, classes/inheritance/polymorphism/parameterized classes, interfaces/virtual interfaces/clocking blocks, tasks/functions, dynamic containers, processes/events/mailboxes/semaphores, constrained randomization, covergroups, properties, DPI/VPI shims, and deterministic naming/source maps.
  - Do not implement the complete live HVL runtime, Verilator wrapper, JVM binding, Icarus VPI/transport adapter, analog/mixed-signal adapter, UVM generator, or commercial simulator adapters in Foundation.

- [ ] **Foundation Increment 149 — UVM/UVM-MS projection and vendor-profile architecture readiness**
  - Accept ADR 0023 and freeze projection from the **portable/capturable** Verification Semantic IR subset to digital UVM and UVM-MS rather than making UVM the canonical Nodal execution model.
  - Define mappings for tests/envs/agents/drivers/monitors/sequencers/sequences/items/scoreboards/TLM/analysis/factory/config/phases/objections/coverage/reporting and generated UVM RAL integration.
  - Define UVM-MS structural/class bridge identities, mixed-signal endpoints, analog stimulus/monitor/measurement contracts, and capability mapping to Foundation AMS semantics.
  - Define handling for precomputed sidecars and explicitly selected companion-runtime services; live-only arbitrary Scala behavior is not silently translated or used to weaken the live API.
  - Define vendor-neutral common source plus thin simulator profiles. Any required `ifdef` is confined to generated vendor adapter/include units, not common VIP/test logic.
  - Record standard version, UVM reference implementation, vendor profile, feature decisions, defines, adapter hashes, source hashes, commands, and unsupported capabilities.
  - Do not generate production UVM/UVM-MS code or vendor scripts in Foundation.

- [x] **Foundation Increment 152 — Direct procedural HDL testbench projection architecture readiness**
  - Accept ADR 0025 and freeze a Procedural HDL Testbench IR lowering seam beneath the canonical Verification Semantic IR; it is generated-language IR, not a second authoring model.
  - Define portable Verilog-2005 and standards-oriented Verilog-AMS testbench profile boundaries, stable logical Interface/Register/test/check identities, source maps, manifests, normalized pass/fail results, and deterministic artifact hashes.
  - Apply embedded/precomputed-replay/companion-runtime-required/unsupported classification only when a portable projection is requested. An unsupported live-only operation rejects that projection but remains legal in a supported live simulator profile.
  - Define deterministic stimulus/expected-result sidecar contracts, seed/replay provenance, waveform/termination policy, and cross-backend parity identities.
  - Make Icarus the required event-driven reference for portable Verilog testbenches and Verilator a separately qualified subset.
  - Distinguish full Verilog-AMS testbench source generation from the practical open AMS harness based on Verilog-A/OSDI, SPICE/XSPICE, and digital co-simulation adapters. Open-source Verilog-AMS execution is enabled only after exact capability conformance.
  - Do not implement Verilog/Verilog-AMS testbench generation, replay lowering, simulator runners, open AMS co-simulation, UVM/UVM-MS generation, or verification libraries in Foundation.

## Foundation completion barrier

The barrier opens only when every checkbox in the Foundation track is complete with required CI/evidence, including Increments 143-149, architecture-only Increments 150-151 defined by the ASIC/memory extension plan, Foundation Increment 152, and any later Foundation item added before release.

The three dependent tracks are then independently schedulable unless they declare additional track-local prerequisites. Within verification, [`nodal-hvl-simulation-v0.1-plan.md`](nodal-hvl-simulation-v0.1-plan.md) defines the shared LIVE workstream and independent analog-live, mixed-signal-live, portable-projection and commercial-profile release gates.

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

Numbering restarts for this track. Live Nodal HVL is the primary execution path. Increments 1-5 implement live capability; Increments 6-10 add portable/capturable generated projections and parity without restricting live tests.

- [ ] **Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice**
  - Implement one live Verification runtime vertical slice for tests, deterministic processes/time, clocks/resets, typed transactions, direct logical Interface access, failures, waveforms, seeds, replay, and normalized results.
  - Permit ordinary Scala host control and reference-model logic around typed Nodal operations; do not require generated UVM or standalone HDL representability.
  - Implement Verilator as the primary fast native adapter by compiling generated Verilog to a C++ DUT model, generating a stable C ABI wrapper, compiling/linking a cached native shared library, and binding it through the approved JVM native mechanism. Use direct batched model access; keep Nodal HVL on the JVM/runtime side.
  - Implement Icarus as an independent event-driven native adapter by compiling generated Verilog to a VVP image, launching an external `vvp` process, loading a versioned Nodal VPI module, and using shared memory or versioned IPC for batched reads/writes, callbacks, run-until/time advance, four-state values, waves, timeout, crash detection, and cleanup.
  - Implement the common scheduler barrier contract: resume Nodal processes, batch writes, evaluate/settle the simulator, return ordered event/value batches, resume monitors/checks/coverage, and select the next event/time command.
  - Add differential smoke tests across native Verilator and native Icarus for combinational settle, sequential/nonblocking updates, clocks/resets, waits, multiple clocks, inout/high-Z where supported, timeout, finish, waves, replay, and source-level failures. Generated UVM and generated standalone HDL testbenches are not involved.

- [ ] **Digital Verification Increment 2 — Scenarios, sequences, constrained stimulus, and replay**
  - Implement reusable scenarios/sequence graphs, random variables/constraints/distributions, deterministic seed hierarchy, value-stream capture, exact replay, parallel stimulus, cancellation, timeout, and capability diagnostics.
  - Permit richer live-only host scenarios while identifying explicitly portable scenarios and deterministic sidecar boundaries.

- [ ] **Digital Verification Increment 3 — Agents, drivers, monitors, scoreboards, and reference models**
  - Implement active/passive agents, typed drivers/monitors bound to logical Interfaces, analysis streams, transaction correlation, protocol timing policies, scoreboards, reference-model calls, and reusable BFM/VIP base classes.
  - Support both live components using ordinary Scala/reference models and portable components whose complete selected semantics can be captured.

- [ ] **Digital Verification Increment 4 — Functional coverage and verification reporting**
  - Implement canonical coverpoints/bins/crosses/transition coverage where meaningful, coverage groups/sampling, stable IDs, merge/report, source mapping, exclusions/waivers, and optional UCIS interchange.

- [ ] **Digital Verification Increment 5 — Properties, protocol checks, and register-model verification**
  - Integrate the Foundation property layer with simulation checks and formal hooks.
  - Generate register frontdoor/backdoor operations, predictors, access-policy checks, reset/side-effect/collision coverage, and register scoreboards from canonical Register IR.

- [ ] **Digital Verification Increment 6 — Portable Verilog testbench generation**
  - Lower the supported **portable/capturable** Verification IR subset through Procedural HDL Testbench IR and render standalone Verilog-2005 testbenches for generated portable Verilog RTL.
  - Generate deterministic DUT wrappers, clocks/resets, flattened Interface helpers, tasks/functions, monitors/checks, error counters, wave controls, source maps, manifests, and vector/expected-result replay files where required.
  - Reject operations that are neither directly representable nor legally precomputable; do not silently reduce test intent. Rejection of this projection does not invalidate live execution.

- [ ] **Digital Verification Increment 7 — Standalone open-source Verilog testbench execution and qualification**
  - Qualify Icarus as the required standalone event-driven profile for DUT plus generated `tb.v`, file I/O, timing/events, four-state behavior, inout, waveforms, timeouts, plusargs, and deterministic pass/fail.
  - Qualify a declared standalone Verilator testbench/timing subset separately with differential fixtures for timing, event ordering, two-state/four-state differences, inout, file I/O, waves, timeout, and termination.
  - Keep this mode independent of the native JVM-to-Verilator and JVM-to-Icarus adapters: no live Nodal runtime is required, and profile IDs, cache keys, manifests, and failure classes remain distinct.
  - Emit exact tool/version/options/capability manifests and reject unsupported profile combinations before execution.

- [ ] **Digital Verification Increment 8 — Verification SystemVerilog and digital UVM generation**
  - Render standards-oriented SystemVerilog/UVM from the portable/capturable Verification IR subset: test/env/agent/driver/monitor/sequencer/sequence/item/scoreboard/TLM/factory/config/phases/objections/coverage/reporting and UVM RAL.
  - Generate reusable packages/VIP with deterministic hierarchy/names and source maps.
  - Diagnose live-only host operations at their source locations rather than removing them or weakening the live component API.

- [ ] **Digital Verification Increment 9 — Commercial simulator profiles**
  - Qualify thin VCS-family, Questa-family, and Xcelium-family live and generated-flow profiles for compile/elaboration/run, direct runtime access or DPI/VPI as applicable, UVM library selection, waves, coverage, reporting, and known compatibility workarounds.
  - Keep common Nodal HVL and generated UVM source identical wherever standards support it; confine unavoidable vendor `ifdef`s to adapter packages/includes.

- [ ] **Digital Verification Increment 10 — Native Verilator, native Icarus, Verilog-testbench, and UVM semantic parity**
  - Run each applicable **portable/capturable** Nodal HVL test through four explicit modes: native Verilator, native Icarus, generated standalone portable Verilog testbench, and generated UVM.
  - Keep live-only tests in the live qualification matrix and record why generated projection does not apply; non-portability is not a live failure.
  - Compare canonical deterministic stimulus/value streams, transaction ordering, protocol behavior, checks, scoreboards, register behavior, coverage intent/results, timeout/cancellation/termination, replay artifacts, and source-level failure identities.
  - Classify scheduler, timing, random-solver, two-state/four-state, inout, and profile limitations rather than hiding them; an unsupported result is never parity success.

- [ ] **Digital Verification Increment 11 — Reusable digital VIP qualification**
  - Author representative protocol VIP only in Nodal HVL—at minimum `Valid`/`Stream`, APB, and AXI4-Lite or another approved protocol—and generate native BFM/agents, portable Verilog testbench collateral where expressible, and UVM VIP.
  - Qualify active/passive modes, protocol assertions, constrained traffic, coverage, scoreboards, configuration, errors, reset, backpressure, live-only extensions, portable subsets, and reuse in an external consumer project.

- [ ] **Digital Verification Increment 12 — Scale, performance, compatibility, and verification release gate**
  - Exercise large testbench hierarchies, many agents, long regressions, parallel tests, deterministic caching, value/replay volume, coverage merge, procedural-Verilog generation/runtime, UVM compile/runtime scale, source-map performance, ordinary Scala reference models, and declared host effects.
  - Benchmark Verilator code generation/C++ compilation/link, cold and warm model-cache behavior, native-call batching, evaluation throughput, and memory separately from Icarus compile/VVP startup, VPI/shared-memory-or-IPC latency, event throughput, four-state transport, process cleanup, and memory.
  - Exercise many tests reusing one compiled Verilator model and many concurrent external Icarus processes; prove cache invalidation over RTL, parameters, wrapper ABI, tool versions/options, native toolchain/platform, plugins, timing, trace, coverage, and optimization settings.
  - Publish separate supported live-HVL and portable/capturable projection capability matrices plus a reusable VIP author conformance kit.

---

# Analog/Mixed-Signal Verification Track — blocked by Foundation

Numbering restarts for this track and remains separate from Digital Verification. The normative detailed sequencing is split into ANA analog-live, MS mixed-signal-live, and PORT portable/generated lanes in [`nodal-hvl-simulation-v0.1-plan.md`](nodal-hvl-simulation-v0.1-plan.md).

The analog-live gate must be independently releasable before mixed-signal, generated Verilog-AMS, UVM-MS, or commercial-profile completion. The mixed-signal-live gate may also release before portable/generated and commercial profiles. Live Nodal HVL remains the primary user experience throughout.

- [ ] **AMS Verification Increment 1 — Nodal HVL native mixed-signal simulation vertical slice**
  - First close an analog-only live Nodal HVL vertical slice through a qualified open Verilog-A/OSDI compiler and solver: compile, start, drive, run, sample, check, waveform and normalized result.
  - Then extend the live Verification runtime to typed physical quantities, analog terminals/signal-flow values, measurements, tolerances, crossings/events, waveform stimulus, PVT/environment context, and mixed digital/analog synchronization.
  - Execute through available open Verilog-A/solver and digital adapters without requiring generated Verilog-AMS testbenches or UVM-MS.
  - Permit ordinary Scala host control and reference models; portable projection is a separately selected capability.

- [ ] **AMS Verification Increment 2 — Analog/mixed-signal agents, drivers, monitors, and scoreboards**
  - Implement mixed-signal transaction schemas, analog stimulus sources, samplers/monitors, tolerance-aware scoreboards/reference models, event correlation, mixed interface bindings, and reusable active/passive agent patterns.
  - Distinguish live components from portable/capturable components without creating separate semantic types or weakening live behavior.

- [ ] **AMS Verification Increment 3 — PVT, sweeps, stochastic stimulus, and deterministic replay**
  - Integrate Foundation environment/PVT/noise/variation semantics with constrained verification scenarios, corner matrices, Monte Carlo/mismatch seeds, deterministic run manifests, failure reduction, and replay.
  - Retain declared host inputs and simulator random-engine provenance; do not claim exact replay when evidence is insufficient.

- [ ] **AMS Verification Increment 4 — Analog measurements and functional coverage**
  - Implement reusable measurements for amplitude/range, threshold/crossing, settling, overshoot, frequency/period/duty, jitter, gain, phase, integrated noise, SNR/ENOB-like metrics where mathematically defined, plus tolerance-aware bins and cross coverage.
  - Keep measurement definitions target neutral and retain waveform/source evidence.
  - Use this increment with the ANA detailed lane to close an analog-live release that does not wait for mixed-signal or generated projections.

- [ ] **AMS Verification Increment 5 — Mixed-signal properties and register/control interaction**
  - Add bridge/event/control-loop checks, analog envelope assertions, initialization/settling checks, mode transition verification, mixed register/control sequences, and source-level failure correlation using Foundation property and Register IR identities.
  - Complete the MS detailed lane's bridge, synchronization, open-source co-simulation and feedback qualification before claiming mixed-signal-live release.

- [ ] **AMS Verification Increment 6 — Standards-oriented Verilog-AMS testbench generation**
  - Lower the supported **portable/capturable** Verification IR subset through Procedural HDL Testbench IR and generate a capability-declared Verilog-AMS top-level testbench for a generated Verilog-AMS DUT.
  - Generate mixed hierarchy, analog stimulus sources/contributions, digital control, bridge/connect metadata, events, measurements, tolerances, checks, termination, source maps, manifests, and deterministic sidecar data where legal.
  - Rendering a Verilog-AMS file does not claim open-source executability; the selected simulator profile must prove every required digital, analog, event, analysis, connect, and solver capability.
  - A live-only test rejected by this projection remains valid for a supported live simulator profile.

- [ ] **AMS Verification Increment 7 — Open-source AMS harness generation and capability-qualified execution**
  - Generate the practical open harness bundle from the portable/capturable Verification IR subset using supported Verilog-A/OSDI models, SPICE/XSPICE stimulus and analyses, digital Verilog/co-simulation collateral, replay data, and normalized results.
  - Qualify OpenVAF/ngspice and selected digital/co-simulation adapters by exact feature profile.
  - Keep this generated harness separate from the primary live runtime adapters and from full Verilog-AMS testbench execution.
  - Permit direct generated Verilog-AMS-testbench execution only for an open adapter that passes the required conformance suite; never infer full support from parsing alone.

- [ ] **AMS Verification Increment 8 — UVM-MS generation from Verification IR**
  - Generate UVM-MS 1.0-oriented class and structural verification collateral from the portable/capturable Nodal HVL subset, including mixed-signal agents, analog stimulus/monitor bridges, scoreboards, transactions, coverage, configuration, and reuse of generated digital UVM components where appropriate.
  - Keep live simulation and direct HDL/open-harness projections independent of UVM-MS.

- [ ] **AMS Verification Increment 9 — Commercial mixed-signal simulator profiles**
  - Qualify thin VCS-family, Questa-family, and Xcelium-family profiles for direct live Nodal HVL control according to available licensed environments and supported standards.
  - Also qualify generated Verilog-AMS and UVM-MS profiles where supported, but do not require those projections for the live adapter.
  - Isolate compile/elaboration/binding/connect-rule/real-net/waveform/license/vendor workarounds in adapter units and manifests.
  - Commercial profile availability does not block analog-live or open mixed-signal-live release.

- [ ] **AMS Verification Increment 10 — Native, open-harness, Verilog-AMS-testbench, and UVM-MS semantic parity**
  - Compare each applicable portable/capturable Nodal HVL environment across live open execution, the open AMS harness, generated Verilog-AMS testbench runs on capable tools, and generated UVM-MS.
  - Keep live-only tests in a separate live qualification matrix with explicit non-portability reasons.
  - Compare transactions, analog stimulus, event timing within declared tolerances, measurements, scoreboards, register/control behavior, coverage intent, termination, deterministic replay artifacts, and failure IDs.
  - Classify solver, scheduling, tolerance, connect, host-effect, and unsupported-feature differences explicitly.

- [ ] **AMS Verification Increment 11 — Reusable mixed-signal VIP qualification**
  - Author representative mixed-signal VIP only in Nodal HVL, then generate native/open-harness behavior, Verilog-AMS collateral where supported, and UVM-MS forms from its portable subset.
  - Qualify at least one converter/control-loop-oriented VIP with digital control plus analog terminals/measurements, reusable configuration, active/passive modes, scoreboards, coverage, PVT scenarios, live-only extensions, portable components, and external consumer reuse.

- [ ] **AMS Verification Increment 12 — Scale, portability, and mixed-signal verification release gate**
  - Exercise multiple analog islands, many digital agents, ordinary Scala reference models, long regressions, PVT/Monte Carlo matrices, waveform/result volume, replay artifacts, generated Verilog-AMS and open-harness scale, coverage merge, UVM-MS compile/runtime scale, source maps, cancellation, crash recovery and failure reduction.
  - Publish separate analog-live, mixed-signal-live, portable/open-harness, Verilog-AMS-testbench, UVM-MS, commercial-simulator, analysis, real-net and connect-rule capability and limitations matrices plus reusable conformance kits.
  - Permit analog-live and mixed-signal-live release gates to close independently according to the detailed plan; this final umbrella gate covers the complete track rather than delaying those earlier usable releases.

## Feasibility conclusion

The requested architecture is feasible and valuable with four binding rules:

1. **Live Nodal HVL is the primary and richer default simulation experience.** It executes on the host, may use ordinary Scala and reference models, and controls open-source or commercial simulators through typed adapters.
2. **Portable/capturable Nodal HVL is an explicit structured subset of the same language.** Its selected behavior is fully represented in Verification Semantic IR for generated or standalone projections.
3. The common semantic representation remains Nodal-owned; arbitrary live Scala internals are not reconstructed as fake portable IR, while Nodal-visible operations and runtime traces retain common types and stable identities.
4. Generated procedural HDL, open-harness, UVM, and UVM-MS outputs are capability-limited projections that must reject, precompute, or require an explicit companion runtime for unsupported behavior. Their limitations never weaken live execution.

For native digital execution, Nodal freezes two different adapter architectures: Verilator is the primary fast compiled-model path using a generated C++ DUT model behind a stable C ABI and JVM native binding; Icarus is the independent event-driven path using an external VVP process, VPI, and versioned shared-memory/IPC transport. Nodal HVL remains in the Nodal runtime in both cases.

Analog live execution first qualifies an open Verilog-A/OSDI and solver path under direct Nodal HVL control. Mixed-signal live execution then adds explicit bridge and scheduling semantics and may initially use a qualified engine-owned coordination path such as SPICE/XSPICE plus digital co-simulation. Commercial simulators are later thin adapters using the same Nodal testbench model.

Portable Verilog testbench generation for generated Verilog RTL remains a separate open-source path with Icarus as the standalone event-driven reference and a separately qualified Verilator subset. It must not be confused with native Icarus execution.

Verilog-AMS testbench generation is valid at the language level and remains a future portable projection. Current open-source AMS execution is based on live Nodal HVL plus Verilog-A/OSDI, SPICE/XSPICE, and digital co-simulation adapters, or on a separately generated open AMS harness. Nodal must not claim general open-source Verilog-AMS-testbench support until a selected adapter passes the exact capability suite.

The standards baseline remains IEEE 1800-2023 SystemVerilog, IEEE 1800.2-2020 UVM with the selected Accellera reference implementation locked by profile, Accellera UVM-MS 1.0, and Verilog-AMS 2023. Version selection remains explicit so later standards or reference releases do not silently change generated verification behavior.
