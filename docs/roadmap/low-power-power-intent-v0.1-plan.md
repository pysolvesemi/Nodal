# Low-Power Architecture and Power Intent Track v0.1

**Status:** Normative deferred dependent-track roadmap  
**Revision:** 0.1  
**Defined:** 2026-09-02  
**Foundation dependency:** every checkbox in [`nodal-development-todo.md`](nodal-development-todo.md), including its normative Foundation extensions  
**Architecture readiness:** [ADR 0024](../architecture/0024-minimal-asic-advanced-io-readiness-boundary.md) and Foundation Increments 150-151  
**Machine-readable surface:** [`low-power-power-intent-v0.1-surface.json`](low-power-power-intent-v0.1-surface.json)  
**Implementation status:** blocked by the Foundation completion barrier

## Decision

Low-power design is a separately numbered Nodal dependent track named **Low-Power Architecture and Power Intent**.

> **No implementation increment in this track may start or merge until every Foundation increment is complete.**

Standards review, open-source evaluation, API candidate research, qualification planning, and roadmap maintenance may continue while Foundation is incomplete. Research must not merge compiler behavior, public APIs, reusable libraries, generated UPF, tool adapters, or low-power transformations ahead of the barrier. A genuinely missing target-neutral identity or capability seam returns to Foundation; a vendor feature, library mapping, UPF profile, power controller, or implementation adapter remains in this track.

Track numbering starts at **Low-Power Increment 1** after the barrier opens. It does not continue Foundation numbering and does not change the Foundation exit criteria.

## Goals

This track will make power intent a first-class, typed, source-correlated part of Nodal so that a design can describe and verify:

- power and voltage domains, supplies, switched supplies, always-on resources, legal power states, and operating points;
- power switches, isolation, level shifting, retention, power-aware memories, and required control ownership;
- safe shutdown, wake-up, quiescence, reset, clock, retention, isolation, and supply sequencing;
- deterministic generation of supported IEEE 1801 Unified Power Format collateral;
- reusable technology-neutral low-power primitives with generic behavioral models and selected technology implementations;
- power-aware simulation, formal properties, crossing closure, implementation mapping, activity analysis, and evidence-backed optimization.

The primary productivity objective is that designers express architectural intent once and reuse verified primitives, sequencing components, mappings, checks, reports, and generated collateral rather than repeatedly hand-writing low-power wrappers and UPF scripts.

## Non-goals and safety boundaries

The initial track will not:

- make raw UPF Tcl the canonical Nodal authoring model;
- execute arbitrary user-supplied Tcl as part of a supposedly declarative import;
- silently insert isolation, level shifters, retention, power switches, clock gates, or controller logic;
- infer that a signal may be clamped, corrupted, retained, or discarded without explicit architectural policy;
- treat a normal clock enable as a physical clock-gating cell or create user-visible generated clocks by accident;
- claim power savings, sign-off, or silicon correctness from an RTL-only estimate;
- absorb power-grid synthesis, IR-drop, electromigration, thermal sign-off, package power integrity, or foundry sign-off from the future ASIC Productivity and Sign-off Track;
- copy proprietary PDK cells, vendor scripts, copyrighted standard text, or examples whose license and provenance have not been approved.

Unsupported intent must fail with a source-correlated capability diagnostic. Approximation, omission, fallback to ordinary RTL, and profile downgrades must never occur silently.

## Ownership boundary

### Foundation owns

Foundation remains the owner of stable module, instance, port, signal, state, memory, interface, clock/reset domain, CDC/RDC, register, hard-macro, source-map, capability, plugin, artifact, and provenance identities. Foundation Increment 150 and ADR 0024 provide the minimum identity seam for future power domains, supplies, states, transitions, isolation, level shifting, retention, always-on intent, and operating-point associations.

Foundation does not implement the Power Intent IR, UPF, power-aware simulation, cell insertion, power analysis, or the reusable low-power library.

### This track owns

The Low-Power track owns:

- the canonical Nodal Power Intent IR and its public API;
- power-intent validation, hierarchy/refinement, and cross-domain analysis;
- reusable low-power primitives and power-management controller libraries;
- IEEE 1801 generation and constrained import profiles;
- power-aware behavioral semantics, properties, coverage, and normalized evidence;
- technology-library mappings and open/commercial tool capability profiles;
- low-power activity analysis and explicitly selected optimization passes.

### Other dependent tracks own

- **Digital Verification** owns the general Nodal verification runtime, transactions, scheduling, coverage infrastructure, and simulator adapter framework. This track contributes power-aware semantics and checks through those stable contracts when available.
- **ASIC Productivity and Sign-off** owns synthesis, floorplanning, power-grid construction, placement, clock-tree synthesis, routing, extraction, STA, IR-drop, electromigration, DRC/LVS, and sign-off execution.
- **FPGA Productivity** owns FPGA vendor flows. It may map supported clock enables, clock buffers, and power-reporting capabilities, but unsupported ASIC power-gating or multi-voltage intent must remain explicit.

## Canonical architecture

Nodal low-power support has three layers.

### 1. Canonical Power Intent IR

The compiler owns one target-neutral semantic model, provisionally represented by Nodal-owned MLIR operations or attributes. Exact operation and public API names are frozen by Low-Power Increment 1 rather than by this planning document.

The model must represent at least:

- supply ports, supply nets, supply sets, power/ground functions, voltage and operating-point metadata;
- power domains, domain membership, hierarchy, exclusions, inheritance, composition, and IP-level refinement;
- power switches, control expressions, acknowledgement/stability conditions, input/output supplies, and legal switch states;
- legal power states, state tables, state transitions, transition preconditions, and DVFS associations;
- isolation, level-shifting, combined isolation/level-shifting, retention, always-on, and repeater/buffer strategies;
- source and sink domains, crossing classes, placement intent, clamp/hold behavior, control polarity, and controlling-domain ownership;
- retained state sets, save/restore events, retention supplies, reset interactions, and corruption semantics;
- technology mapping requirements, capability requirements, typed vendor extensions, source spans, stable IDs, and deterministic serialization.

Power intent must bind to semantic identities, not generated hierarchy strings. Transformation, inlining, generate expansion, instance specialization, and readable HDL naming must preserve the binding or produce an error.

### 2. Reusable low-power library

Reusable user-facing components live under a future `libraries/lowpower/` package and depend on canonical core semantics. The library may provide controllers, protocol adapters, helpers, examples, and pre-verified composition patterns. It must not become a parallel power-intent model.

### 3. Technology and tool profiles

Technology mappings and EDA integrations are versioned profiles or plugins. A profile declares exactly which semantic operations, UPF commands, primitive mappings, simulation behaviors, insertion operations, physical stages, reports, and evidence it supports. Installing a profile never changes a design unless it is explicitly selected in a locked build plan.

## UPF relationship

IEEE 1801-2024, commonly called UPF 4.0, is the normative external semantic reference for the first complete power-intent profile. Nodal's Power Intent IR remains canonical; UPF is a generated, imported, or attached external representation.

The initial profiles are:

- **`IEEE1801_2024_Core`** — a standards-oriented, capability-checked subset with deterministic output and explicit exclusions;
- **`OpenROAD_Supported`** — the exact subset proven against a pinned OpenROAD revision and regression corpus;
- future commercial-tool profiles — thin capability and command variations over the same canonical intent.

Generated UPF must carry a manifest mapping every generated name and command to stable Nodal identities and source spans. Unsupported commands or semantics fail generation. A typed raw-vendor escape may be added later, but it must be isolated, profile-scoped, non-portable, source-correlated, and included in artifact hashes and provenance.

Import initially accepts only a constrained declarative subset. It must not source or execute arbitrary Tcl. General external UPF may be attached as an opaque, versioned artifact when it cannot be safely imported; Nodal must then report which intent it cannot inspect or prove.

## Primitive-library direction

The initial semantic primitive catalog is planned as follows. Names are candidates until Low-Power Increment 1 freezes the public surface.

| Candidate primitive | Required semantic contract |
|---|---|
| `ClockGate` | source clock domain, enable ownership, test/scan override, glitch-free contract, output clock relationship, generic simulation, technology mapping |
| `GlitchlessClockMux` | legal source clocks, select synchronization/handshake, switching preconditions, output relationship and fail-safe behavior |
| `IsolationClamp` | source/sink domains, clamp or hold policy, control/polarity, isolation supply, placement intent and off-domain corruption behavior |
| `LevelShifter` | source/sink supplies and voltages, direction/rule, placement intent, threshold/profile requirement and mapping |
| `IsolationLevelShifter` | combined isolation and translation semantics with one proven ordering and mapping contract |
| `RetentionReg` / `RetentionBank` | retained state identity, save/restore protocol, retention supply, reset policy, corruption and initialization semantics |
| `PowerAwareMemory` | array state policy, retention or reinitialization contract, macro views, save/restore limits and verification model |
| `AlwaysOnBuffer` | always-on supply ownership, source/sink domains, control/data role and technology mapping |
| `PowerSwitch` | switched supply relationship, control/acknowledgement, legal states, sequencing conditions and implementation mapping |
| `PowerDomainController` | explicit state graph, quiesce/request/acknowledge protocol, ordering constraints, timeouts, errors and software-visible integration |

Each primitive requires a generic behavioral model for ordinary RTL simulation where meaningful, a power-aware model, formal/temporal properties, stable logical identity, mapping requirements, and a deterministic mapping manifest. Generic models are not substitutes for sign-off library cells.

## Open-source reference and reuse policy

The following projects are research and qualification references recorded on 2026-09-02. Exact commits, licenses, dependencies, and compatibility must be re-verified before any code or collateral is reused.

| Reference | Planned use | Boundary |
|---|---|---|
| [IEEE 1801-2024](https://standards.ieee.org/ieee/1801/7466/) and the [IEEE SA Open UPF project](https://opensource.ieee.org/upf) | normative terminology, semantics, Annex E examples, reusable UPF-library concepts, conformance fixtures | do not copy standard prose into Nodal; preserve license/provenance for adopted open-source examples |
| [OpenROAD UPF module](https://openroad.readthedocs.io/en/latest/main/src/upf/README.html) and its regression tests | first executable open-source UPF reader/writer and implementation capability profile | profile the exact tested subset; never equate current OpenROAD support with complete IEEE 1801 support |
| [OpenTitan primitives](https://opentitan.org/book/hw/ip/prim/index.html) | fixed semantic primitive interfaces with generic and technology-dependent implementations | reuse the mapping pattern, not OpenTitan-specific module naming as Nodal semantics |
| [Lambdalib](https://github.com/siliconcompiler/lambdalib) and Lambdapdk | portable cell abstraction, package organization, PDK mapping and reduced-order generic models | Nodal requires typed power intent and source correlation beyond a cell wrapper library |
| [Lighter](https://github.com/AUCOHL/Lighter) | reference for an optional Yosys-based automatic clock-gating pass and validation methodology | no automatic gating until explicit opt-in, equivalence, DFT/test, timing, profitability and rollback contracts are proven |
| [croc-pmu](https://github.com/joboscanprojects/croc-pmu) | compact end-to-end PMU, UPF, retention/isolation, firmware and OpenROAD-oriented qualification witness | case study only; independently validate sequencing, commands, cells, results and tool versions |

Nodal should reuse standards, algorithms, examples, models, or code only when doing so is technically exact and license-compatible. Nodal owns its IR, APIs, adapters, source maps, capability negotiation, diagnostics, tests, and validation evidence.

---

# Track TODO — blocked by Foundation

Every checkbox below remains blocked until the Foundation completion barrier opens.

## Architecture and canonical intent

- [ ] **Low-Power Increment 1 — Architecture, public API, capability and evidence design gate**
  - Accept a dedicated low-power ADR that refines ADR 0024 without moving implementation into Foundation.
  - Compile and compare concise public API candidates for supplies, domains, states, strategies, retained state, operating points, sequences, primitive selection, UPF profiles, imports, vendor escapes, reports and waivers.
  - Freeze ownership between canonical IR, reusable libraries, generated UPF, attached external UPF, technology mappings, simulator semantics, implementation adapters, power analysis and ASIC sign-off.
  - Freeze stable IDs, hierarchy/refinement rules, source maps, deterministic serialization, artifact hashes, capability negotiation, unsupported-feature policy and normalized evidence.
  - Define positive and negative design-gate fixtures before accepting implementation.

- [ ] **Low-Power Increment 2 — Canonical Power Intent IR, serialization and mandatory verifiers**
  - Implement target-neutral IR for supplies, supply sets, domains, switches, states, transitions, isolation, level shifting, combined strategies, retention, always-on intent, operating points, mappings and typed extensions.
  - Bind intent to canonical module/instance/port/signal/state/memory/interface/clock/reset identities and preserve bindings through hierarchy, generate expansion, specialization and optimization.
  - Implement deterministic textual and machine-readable serialization, source maps, semantic hashes and round-trip tests.
  - Reject duplicate or ambiguous identities, invalid ownership, supply cycles, overlapping domain membership, missing controls, illegal state references and unsupported target requirements at compiler boundaries.

- [ ] **Low-Power Increment 3 — Supply network, power-domain hierarchy, legal states and refinement**
  - Implement supply connectivity, primary/switched/retention supplies, voltages, operating points, domain membership, hierarchy, exclusions, composition and IP-to-SoC refinement.
  - Implement legal power-state tables and explicit transitions with preconditions, acknowledgements, stability conditions and DVFS associations.
  - Define how reusable IP publishes abstract power intent and how an integrator binds or refines it without rewriting internal semantic identities.
  - Produce deterministic domain, supply, state, refinement and unresolved-binding reports.

## Crossings and reusable implementation intent

- [ ] **Low-Power Increment 4 — Power-domain crossing analysis and strategy legality**
  - Build a semantic crossing graph for scalar, aggregate, interface, protocol, clock, reset, memory, hard-macro and supported mixed-signal boundaries.
  - Determine whether each crossing requires no action, isolation, level shifting, combined cells, always-on buffering, retention-aware handling, explicit waiver or rejection.
  - Verify clamp/hold legality, voltage direction, source/sink state combinations, placement intent, control polarity, control availability from a powered domain, reset interaction, clock behavior and test overrides.
  - Start with diagnostics and explicit strategy application. Do not silently insert cells or alter architecture.
  - Generate complete, source-correlated crossing and strategy-coverage reports with unmatched, multiply matched, stale and waived targets.

- [ ] **Low-Power Increment 5 — Reusable primitive library and technology-mapping contract**
  - Implement the approved clock gate, glitchless clock mux, isolation, level-shifter, combined, retention, always-on, power-switch, power-aware memory and controller primitives.
  - Reuse Foundation clock/reset/CDC/RDC contracts rather than creating duplicate domain semantics.
  - Provide generic behavioral and power-aware models, assertions, deterministic interfaces, parameter rules, scan/test controls, source/sink metadata and unsupported-use diagnostics.
  - Implement versioned technology-library mappings selected through capability profiles, with exact cell/port/polarity/function matching and a mapping manifest.
  - Never treat a generic model as a sign-off implementation or silently replace an unmapped required primitive with ordinary RTL.

## UPF interoperability

- [ ] **Low-Power Increment 6 — Deterministic IEEE 1801-2024 UPF generation profiles**
  - Implement the approved `IEEE1801_2024_Core` projection for supplies, sets, domains, switches, states, isolation, level shifting, retention, always-on intent and implementation mappings included in the frozen subset.
  - Emit deterministic command ordering, quoting, hierarchy selection, generated names, comments, source correlation and a Nodal-to-UPF identity manifest.
  - Implement a separately declared `OpenROAD_Supported` profile tied to a pinned capability matrix rather than approximating unsupported standard features.
  - Reparse generated collateral, compare canonical intent, exercise positive/negative fixtures, and fail on unsupported semantics or ambiguous object selection.

- [ ] **Low-Power Increment 7 — Constrained UPF import, external attachment and semantic round-trip**
  - Parse a safe declarative subset without executing arbitrary Tcl, shell commands, file-system actions or tool extensions.
  - Normalize supported imported objects into the canonical Power Intent IR with stable imported identities, source locations, provenance and explicit refinement scope.
  - Support opaque attachment of external UPF when safe import is impossible, while clearly limiting the checks, transformations and equivalence claims Nodal can make.
  - Define semantic round-trip equivalence independent of whitespace, command ordering and generated names; report information loss and profile-only constructs explicitly.

## Sequencing and verification

- [ ] **Low-Power Increment 8 — Power-management sequencing and reusable PMU/PPU/PDC library**
  - Implement typed request/acknowledge, quiesce, idle, wake, fault, timeout and current/target-state interfaces plus reusable controller/state-graph components.
  - Let designs explicitly order transaction blocking, quiescence, clock gating, retention save, isolation, reset, supply switching, stability wait, restore, de-isolation and clock release.
  - Verify sequence preconditions and controlling-domain availability rather than imposing one universal shutdown/wake-up order.
  - Integrate optional software control/status through canonical Register IR and generate matching documentation and verification bindings.
  - Provide parameterized examples for clock-only sleep, switchable non-retained domains, retained domains and multi-domain dependencies.

- [ ] **Low-Power Increment 9 — Power-aware simulation, properties, coverage and equivalence evidence**
  - Model powered, off, corrupt, isolated, retained, restoring and invalid-transition behavior with explicit four-state/profile rules.
  - Check isolation-before-invalid-source, save-before-power-off, stable-supply-before-restore or de-isolation, restore-before-use, legal clock/reset ordering, quiescence, always-on control ownership and protocol safety.
  - Provide target-neutral properties, generated checks, state/transition/crossing/strategy coverage and normalized failures with source identities.
  - Integrate with the general Nodal verification runtime when available and provide independently qualified generated-HDL or formal subsets where practical.
  - Add thin commercial power-aware simulator profiles only through capability negotiation; no vendor simulator defines canonical semantics.

## Open implementation, analysis and release

- [ ] **Low-Power Increment 10 — OpenROAD and open-PDK implementation profile**
  - Qualify the exact supported OpenROAD UPF reader/writer, domain, switch, isolation, level-shifter, area, voltage and cell-mapping capabilities against pinned revisions and tests.
  - Bind semantic primitives and strategies to verified Liberty/LEF cell views and selected open-PDK profiles with exact port, function, polarity, voltage and placement checks.
  - Produce implementation scripts/collateral, inserted-cell and unmapped-intent reports, before/after netlist correlation, source maps, commands, tool versions, logs and artifact hashes.
  - Keep power-grid synthesis, IR-drop, electromigration, extraction and sign-off in the ASIC Productivity and Sign-off Track.
  - Reject a selected open-PDK profile that lacks required cells or views rather than substituting unsafe logic.

- [ ] **Low-Power Increment 11 — Activity, power estimation and explicitly bounded optimization**
  - Import or produce VCD/SAIF-class activity with stable signal/domain/state correlation and report dynamic, leakage, clock, memory and state-dependent power where the selected tools and libraries support it.
  - Report clock-gating effectiveness, residual activity in disabled/off domains, state residency, transition energy assumptions, unmapped activity and confidence/provenance.
  - Add optional automatic clock-gating evaluation as a structured target-IR optimization inspired by open-source work such as Lighter, but require explicit selection, eligible-region bounds, enable-equivalence proof, scan/test handling, timing/cell capability checks, profitability evidence and deterministic rollback.
  - Never silently modify latency, reset behavior, clock topology, CDC/RDC, observability, formal assumptions or accepted power intent.
  - Label estimates by tool, library, corner, voltage, temperature, activity source and modeling limits; an RTL estimate is not sign-off.

- [ ] **Low-Power Increment 12 — Ecosystem qualification, documentation and v1.0 release closure**
  - Qualify representative witnesses for clock gating, two-domain power gating, multi-voltage level shifting, isolation, retained state, power-aware memory, hierarchical IP refinement, controller sequencing and unsupported-profile diagnostics.
  - Include at least one reproducible open-source implementation witness using a pinned OpenROAD/open-PDK combination whose required cells and capabilities have been verified.
  - Compare canonical IR, generated UPF, crossing reports, behavioral simulation, properties, implementation mappings, normalized power reports and source correlation across the matrix.
  - Publish the supported standard/tool/PDK/primitive/verification capability matrix, tutorials, migration guidance, failure examples, provenance and release evidence.
  - Close only when every required track artifact is deterministic, reviewable, reproducible and free of silent omission or approximation.

## Dependency and scheduling matrix

| Increment | Depends on | Earliest parallel work after dependencies |
|---|---|---|
| 1 | Foundation barrier | none; architecture gate first |
| 2 | 1 | canonical implementation |
| 3 | 2 | may overlap final serialization hardening |
| 4 | 3 | crossing analysis |
| 5 | 2 and approved contracts from 4 | generic models and mappings may proceed in parallel |
| 6 | 3, 4 and 5 | standards and OpenROAD profiles may be developed in parallel |
| 7 | 6 | importer and semantic round-trip |
| 8 | 3 and 5 | may proceed alongside 6-7 |
| 9 | 4, 5 and 8 | may integrate available Digital Verification infrastructure without making that track a hidden prerequisite for all checks |
| 10 | 5 and 6 | open implementation qualification |
| 11 | 5, 9 and 10 | analysis before optimization; optimization remains optional |
| 12 | 7 through 11 | final matrix and release closure |

No entry in this table overrides the global Foundation barrier.

## Required artifacts and acceptance evidence

Every accepted design must be able to produce the applicable subset of:

- normalized Power Intent IR and machine-readable serialization;
- supply/domain/state and hierarchy/refinement reports;
- complete crossing, strategy, waiver, stale-target and unmatched-target reports;
- primitive and technology-mapping manifests;
- generated UPF plus identity/source-map manifest;
- imported or attached external-UPF provenance and limitations report;
- power-sequence graph, legal-transition table and controller/register documentation;
- power-aware simulation, property, formal, coverage and counterexample results;
- implementation tool/profile/PDK/cell capability manifests and inserted-cell correlation;
- activity and power reports carrying tool, version, corner, voltage, temperature, model and activity provenance;
- deterministic semantic, collateral, configuration, toolchain and final-artifact hashes.

Acceptance requires that:

1. every power-domain crossing is covered by an explicit valid strategy, proven no-action rule, approved typed waiver or error;
2. every required primitive has a valid selected implementation for the target profile;
3. every legal state and transition has defined supply, clock, reset, isolation, retention and externally visible behavior;
4. every generated or imported UPF object maps to canonical semantic identity or is reported as opaque/unresolved;
5. unsupported behavior fails before an artifact is accepted;
6. all claimed transformations preserve functional, protocol, reset, clock, CDC/RDC and power-intent contracts with evidence;
7. every power claim states its modeling and activity assumptions and is not presented as sign-off unless a future sign-off track supplies that evidence.

## Research permitted before the barrier

Before Foundation completion, contributors may:

- track IEEE 1801, CIRCT/MLIR, OpenROAD, OpenTitan, Lambdalib, Lighter, open PDK and power-aware simulator developments;
- prototype API candidates or parsers on non-merge research branches;
- collect licensed examples, negative fixtures, expected diagnostics and capability matrices;
- evaluate whether an apparent blocker is a genuinely missing Foundation identity seam;
- refine this roadmap and its machine-readable surface.

They may not merge public APIs, IR operations, compiler passes, libraries, UPF generation/import, tool adapters, transformations, or qualification claims from this track until the Foundation barrier opens.

## Deferred extensions beyond the first complete track

Later separately approved extensions may cover advanced IEEE 1801-2024 features, richer successive refinement, abstract power sources, virtual supplies, supply-net tunneling, Value Conversion Methods, advanced retention macros, mixed-signal supply behavior, thermal/energy co-modeling, battery/regulator control, chiplet/package power intent, commercial sign-off adapters, and additional interchange formats. These are not required to start the initial track unless Low-Power Increment 1 explicitly promotes a feature into the frozen v0.1 contract.
