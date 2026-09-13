# Analog and mixed-signal physical implementation roadmap v0.2

**Status:** Normative dependent-track roadmap; implementation not started  
**Date:** 2026-09-13  
**Revision scope:** External-tool integration only; no Nodal-owned physical-design engine  
**Parent roadmap:** [Nodal Incremental Development TODO](nodal-development-todo.md)  
**Registration:** [Dependent-track gate](dependent-track-gate-v0.1.json)  
**Manifest:** [Analog physical-design surface](analog-physical-design-v0.1-surface.json)  
**Tracks:** AC (12 increments), APL (12 increments), APV (12 increments)  
**Implementation barrier:** ALL FOUNDATION COMPLETE

## Purpose and scope

Extend the Nodal ecosystem from behavioral AMS and structural circuit descriptions to PDK-bound circuit simulation, analog layout, physical verification, and qualified analog hard-macro delivery **using existing open-source or commercial engines**. Nodal implements circuit-language support, exports, thin adapters, capability checks, source correlation, and evidence handling. It does not implement a physical-design engine in these tracks.

Every implementation increment below is blocked until **every Foundation increment and every normative Foundation extension is complete**. Read the current barrier and extension list in `dependent-track-gate-v0.1.json`; completing one early Foundation milestone is not sufficient. Research and documentation may proceed before that barrier. No analog implementation, supported PDK, physical-design execution, or generated GDS becomes a new Foundation exit criterion. New core-semantic gaps discovered during planning return to the normal Foundation architecture process, not to an implicit bypass.

These are 36 planned work items, not implemented APIs or tool integrations. Existing Foundation, FPGA, digital verification, AMS verification, low-power, scheduled-hardware, and debugging completion states remain unchanged. The reserved ASIC Productivity and Sign-off and Memory Interface IP and PHY tracks remain reserved: the analog macro handoff here does not authorize their implementation.

### Approved scope correction from v0.1

At the user's request, remove implementation of a Nodal-owned physical-design engine and the mandatory Rust/separate-repository requirements. Retain all increment and child IDs and all existing open states; re-scope the affected children to external-tool integration rather than deleting them or marking them complete. In particular, APL-001 defines physical-intent/adapter contracts, APL-004 manages external-tool checkpoints and layout artifacts instead of a custom physical database, and APL-007/APL-008 invoke existing placement/routing engines. AC-001/AC-003 no longer require a Rust package reader. The existing `v0.1` filenames remain stable links; the document and manifest revision is 0.2.

## Ownership, language choice, and package boundary

```text
                   Existing Nodal repository
                    nodal-hdl compiler role
                   Scala 3 + MLIR/CIRCT
                            |
              +-------------+--------------+
              |                            |
       behavioral AMS               structural circuit
       Verilog-A/AMS                device/transistor IR
              |                            |
              +-------------+--------------+
                            |
              Circuit + physical-intent export
                  .nax / versioned manifest
                            |
                Thin existing-tool adapters
          reuse existing compiler/adapter infrastructure
                            |
          +-----------------+------------------+
          |                 |                  |
     Simulation        Layout engines     Physical checks
     ngspice or        ALIGN, generators, DRC/LVS/PEX tools
     commercial        commercial tools  + extracted simulation
          |                 |                  |
          +-----------------+------------------+
                            |
                 GDSII/OASIS + reports/evidence
                            |
                   qualified analog macro
                            |
              separately qualified full-chip flow
```

`nodal-hdl` means the language/compiler role of the existing `pysolvesemi/Nodal` repository, not a newly created repository. It retains language semantics, dimensions, disciplines, conservative connectivity, explicit analog/digital bridges, behavioral Verilog-A/AMS lowering, structural device/circuit MLIR, and deterministic circuit-package export. Reuse the existing compiler and simulator contracts; do not create a second language, competing AMS scheduler, or second compiler in an adapter.

**No separate `nodal-analog` repository, standalone Rust analog runtime, Rust package reader, or new Rust toolchain dependency is required by this roadmap.** Analog integration can start as modules/adapters in the existing project. Reuse Scala and the existing native MLIR/CIRCT implementation for compiler work. Thin adapters may use Scala, Python, tool-native scripting, or existing process APIs according to the selected tool. A future `nodal-eda` runner may orchestrate the same artifacts; reusing an already chosen Rust runner there does not require Rust in the analog compiler or create another analog engine. Repository separation and a new language are not acceptance gates.

The `.nax` name remains a provisional versioned circuit/intent handoff, not an executable engine or a full geometry database. Retain stable circuit/instance/device/terminal/net identities, hierarchy, units, symbolic and resolved parameters, source maps, behavioral-view references, PDK requirements, physical intent, model validity, capabilities, hashes, and provenance. Separate unbound design intent from resolved PDK implementation. Exact serialization and public API spellings remain AC-001 decisions. A manifest with circuit/constraint payloads and external artifact references is sufficient for the first integration; a custom binary database is not required.

External tools own detailed geometry, placement/routing state, numerical solving, DRC/LVS algorithms, parasitic extraction, and their native checkpoints. Nodal retains source/circuit identities, constraints, backend-object mappings where available, file/checkpoint references, versions, hashes, measurements, and reports. Query or translate geometry through qualified existing APIs rather than duplicating every polygon in a Nodal database. Explicitly report unavailable source mapping or tool data; do not infer lost information from names alone.

The existing AMS Verification track owns behavioral/system verification, HVL scheduling, agents, stimulus, coverage, and UVM-MS/HDL projections. APV owns adapter integration and evidence for physical checks executed by external tools, reusing behavioral contracts as needed. `nodal-fpga` consumes released macro views; it does not own analog circuit synthesis or layout. Prefer the common project/tool runner and future `nodal-eda` orchestration rather than a competing analog workflow framework. These changes do not alter the language choices or implementation scope of `nodal-fpga` or `nodal-eda`.

### Explicitly outside this roadmap

There are no implementation tasks for a native analog placer/router, geometry kernel, canonical Rust layout database, SPICE/AMS solver, DRC/LVS/PEX engine, or new GDSII/OASIS parser/writer. Use existing libraries, tools, and their APIs for those capabilities. No release gate depends on developing them. Proposing any such engine later requires a separate user-approved roadmap, not automatic expansion of AC/APL/APV. MLIR is reused for circuit semantics; it is not being claimed to supply physical-design algorithms.

## Increment and sub-checklist completion rule

Each top-level `- [ ]` below is one increment. Every nested `- [ ]` is an independently trackable required task with a stable suffix `.a` through `.f`.

A child may become `[x]` when that child's deliverable and its applicable validation are complete, with a commit/artifact/evidence reference recorded alongside it or in the linked completion report. **Its parent stays `[ ]` while any required child is `[ ]`, any prerequisite is incomplete, or acceptance/evidence remains outstanding.** A partially completed increment must never be counted as complete. Close the parent only after all six children and all dependencies are complete and the increment's exit gate is satisfied. A failed or invalidated child reopens its parent. Do not remove, waive, or mark an unfinished child complete merely to close the increment; change scope explicitly and preserve the rationale.

For every increment, child `.f` is its acceptance/evidence gate: a commit alone, a generated file alone, or skipped tooling does not pass it. Existing [completion demonstrations](../../CONTRIBUTING.md#increment-completion-demonstrations) apply to completed increments and sub-increments. Show actual generated Verilog-A/AMS when affected. Otherwise state that the current increment does not affect generated Verilog-* and demonstrate the relevant actual SPICE, layout, or evidence artifact instead where applicable.

Markdown is authoritative for checkbox progress. The JSON surface registers identities, dependencies, children, and release gates, not a second independently editable per-child progress database. This documentation revision leaves **all 36 parents and all 216 children open**. Adding or re-scoping checklists does not implement an automatic checkbox-sync tool.

## Dependency and release rules

Every `Depends on` line additionally inherits ALL FOUNDATION COMPLETE. Cross-track work may proceed in parallel after its named dependencies; completing an entire unrelated track is not required. Later optional adapters must not delay the first useful open releases.

| Release gate | Required closure | Claim allowed |
| --- | --- | --- |
| AC-006 | AC-001 through AC-006 | Declared circuit/profile can be exported, PDK-bound, simulated, and reproduced. |
| APL-006 | APL-001 through APL-006 and their AC prerequisites | Declared external automatic-layout slice produces inspectable layout; no sign-off claim. |
| APV-005 | APV-001 through APV-005, APL-006, AC-007 | Declared open DRC/LVS/PEX and post-layout correlation loop passes. |
| AC-012 | AC-001 through AC-011 | Qualified circuit/PDK release with explicit simulator support matrix. |
| APL-012 | APL-001 through APL-011 and APV-005 | Qualified layout integration release with scalability evidence. |
| APV-012 | APV-001 through APV-011, AC-012, APL-012 | Macro qualification for the exact declared process/profile; foundry sign-off only with matching approved evidence. |

The first useful circuit and physical-loop gates do not wait for commercial adapters, a complete PLL, arbitrary specification-to-circuit synthesis, a new repository, Rust, or a custom physical-design engine.

## Track AC — Analog Circuit and PDK Enablement

Owner: existing Nodal compiler and tool-adapter modules. Scala/MLIR owns source/circuit/export semantics; adapters bind PDK data, project SPICE, invoke external simulation/optimization, and collect results. Numbering starts independently at AC-001.

- [ ] **AC-001 — Analog Package, Circuit IR, and adapter-boundary gate**
  - Depends on: ALL FOUNDATION COMPLETE.
  - [ ] **AC-001.a** Approve ownership and dependency direction for the existing Nodal compiler, `.nax`, thin tool adapters, AMS Verification, and macro consumers; require no new repository or Rust subsystem.
  - [ ] **AC-001.b** Define circuit, device, terminal, parameter, unit, hierarchy, source, and multi-view identities; separate behavioral equations from structural device instances.
  - [ ] **AC-001.c** Define a versioned package manifest, capability negotiation, stable serialization, migration policy, hashes, and opaque external-view references without bundling restricted IP.
  - [ ] **AC-001.d** Prototype the existing compiler exporter and an independent adapter reader in a tool-appropriate language; reject malformed, oversized, missing, incompatible, or untrusted package content without requiring Rust.
  - [ ] **AC-001.e** Preserve existing AMS semantics and make unsupported topology/model/physical capabilities explicit errors rather than silent lowering.
  - [ ] **AC-001.f** Exit: approved design gate, deterministic round-trip fixtures, compatibility/negative evidence, and recorded public-API decisions; no physical synthesis claim.

- [ ] **AC-002 — Structural devices and reusable subcircuits**
  - Depends on: AC-001.
  - [ ] **AC-002.a** Add typed MOS/passive/external-device instances, terminal roles, model references, dimensions, and declared legal parameter ranges to the compiler construction/MLIR path.
  - [ ] **AC-002.b** Support reusable hierarchical subcircuits and parameterized circuit generators without string-based net identity or inferred terminal order.
  - [ ] **AC-002.c** Distinguish ideal simulation devices, realizable process devices, and imported hard macros; disallow simulation-only sources in a physical-release profile.
  - [ ] **AC-002.d** Validate connectivity, bulk/supply references, dimensions, hierarchy, parameter resolution, and deliberate connection exceptions with source diagnostics.
  - [ ] **AC-002.e** Add inverter, RC, current-mirror, and differential-pair circuit fixtures with independently specified expected device/connectivity graphs.
  - [ ] **AC-002.f** Exit: structural positive/negative tests and semantic fixtures pass; source examples and actual IR/output evidence are retained.

- [ ] **AC-003 — Deterministic nodal-hdl to .nax export**
  - Depends on: AC-002.
  - [ ] **AC-003.a** Export verified structural MLIR into the approved `.nax` schema with parameters, hierarchy, model references, and capability requirements intact.
  - [ ] **AC-003.b** Preserve stable source-to-circuit identities across export, import, flattening projections, and generated names.
  - [ ] **AC-003.c** Include behavioral-view references and mixed-domain boundary metadata without treating Verilog-A equations as an inferred transistor implementation.
  - [ ] **AC-003.d** Implement a bounded adapter reader/validator and optional debug representation using existing project or selected-tool infrastructure; do not prescribe a Rust reader or a second compiler.
  - [ ] **AC-003.e** Exercise reproducibility, relocatable paths, dependency hashes, corrupted packages, and unsupported versions across independent processes.
  - [ ] **AC-003.f** Exit: reproducible source-to-package-to-circuit fixtures and diagnostics pass; external tools consume exported data without depending on live MLIR objects or the compiler process.

- [ ] **AC-004 — PDK device binding and legal operating envelopes**
  - Depends on: AC-003.
  - [ ] **AC-004.a** Define versioned PDK/platform/device/model/corner identities, license metadata, local installation references, and process capability manifests.
  - [ ] **AC-004.b** Bind abstract device requests to explicit model/subcircuit names, terminal ordering, geometry rules, finger/multiplicity meanings, and unit scaling.
  - [ ] **AC-004.c** Validate supply, temperature, geometry, device option, and operating-range requirements; reject unavailable models rather than substituting a generic transistor.
  - [ ] **AC-004.d** Qualify one named open PDK/device subset with pinned model hashes and a reproducible installation recipe; do not promise process portability from name mapping alone.
  - [ ] **AC-004.e** Keep unbound `.nax` separate from resolved implementation data and avoid redistributing restricted model decks or design-rule content.
  - [ ] **AC-004.f** Exit: reference-device binding tests, wrong-terminal/unit/model/range failures, and a reviewed PDK capability/license matrix pass.

- [ ] **AC-005 — PDK-bound SPICE projection and simulator contract**
  - Depends on: AC-004.
  - [ ] **AC-005.a** Generate deterministic hierarchical SPICE for the bound subset, including explicit units, parameters, model includes, terminal order, and source correlation.
  - [ ] **AC-005.b** Keep simulation testbenches, stimuli, analyses, and controls separate from the physical circuit netlist.
  - [ ] **AC-005.c** Define the initial ngspice profile and out-of-process request/result protocol, including timeouts, tool versions, output paths, and cleanup.
  - [ ] **AC-005.d** Negotiate supported analyses/device models before execution and normalize process failure, unsupported, convergence failure, and valid-result states.
  - [ ] **AC-005.e** Reparse or independently compare emitted circuits, test escaping/include resolution, and reject injection/path traversal or unsupported constructs.
  - [ ] **AC-005.f** Exit: circuit-equivalent SPICE fixtures and simulator-protocol tests pass with explicit unsupported cases; full vertical-slice qualification remains AC-006.

- [ ] **AC-006 — First open circuit-simulation vertical slice**
  - Depends on: AC-005.
  - [ ] **AC-006.a** Run Nodal source through MLIR, `.nax`, PDK binding, SPICE, external simulation, and normalized results using one locked command/manifest.
  - [ ] **AC-006.b** Qualify DC, AC, and transient analyses on the supported fixture set with independent reference calculations or netlists and declared tolerances.
  - [ ] **AC-006.c** Add an explicitly qualified Verilog-A/OpenVAF/OSDI model adapter where supported; do not present this as full Verilog-AMS simulator support.
  - [ ] **AC-006.d** Preserve waveforms, measurements, seeds, model/tool hashes, convergence status, source IDs, and execution logs.
  - [ ] **AC-006.e** Exercise missing tools/models, timeout, non-convergence, invalid analysis requests, and deliberate circuit defects without false passes.
  - [ ] **AC-006.f** Exit: the first useful circuit release is reproducible on a clean environment with an exact supported-profile matrix and actual source/netlist/result examples.

- [ ] **AC-007 — PVT, sweeps, and statistical variation**
  - Depends on: AC-006.
  - [ ] **AC-007.a** Represent process corners, supplies, temperatures, parameter sweeps, operating modes, and analysis plans with stable identities and physical units.
  - [ ] **AC-007.b** Expand deterministic run matrices using the common runner with bounded concurrency, restart/checkpoint support, and cache keys covering every model/environment input.
  - [ ] **AC-007.c** Distinguish process variation, local mismatch, and user parameter variation; run Monte Carlo only with qualified statistical models and recorded seeds.
  - [ ] **AC-007.d** Aggregate measurements, tolerances, failed runs, and coverage of requested corners without dropping non-convergent cases.
  - [ ] **AC-007.e** Compare serial/parallel results and deliberately missing/corrupt corner evidence; keep confidence/yield claims tied to the actual sample/model assumptions.
  - [ ] **AC-007.f** Exit: complete PVT/sweep matrices and variation capability reports reproduce; unsupported statistical support remains explicitly unsupported.

- [ ] **AC-008 — Measurements and bounded sizing/optimization loops**
  - Depends on: AC-007.
  - [ ] **AC-008.a** Define typed objectives, hard limits, measurement methods, candidate parameter ranges, resource budgets, and stop/failure conditions.
  - [ ] **AC-008.b** Integrate an existing sizing optimizer or parameter-sweep facility over designer-selected circuit families; keep topology and permitted changes explicit without developing a native synthesis engine.
  - [ ] **AC-008.c** Retain candidate lineage, simulations, rejected candidates, convergence outcomes, Pareto tradeoffs, and deterministic replay data.
  - [ ] **AC-008.d** Add reusable Nodal circuit templates and a capability boundary for external bounded topology-selection tools; do not schedule a native topology-search engine or claim arbitrary AMS synthesis.
  - [ ] **AC-008.e** Demonstrate a sized small amplifier/current-mirror family meeting declared pre-layout constraints; detect infeasible requests and budget exhaustion.
  - [ ] **AC-008.f** Exit: independent measurement checks and replayable sizing results pass; post-layout closure is deferred to APV, not assumed from pre-layout success.

- [ ] **AC-009 — Additional and commercial simulator profiles**
  - Depends on: AC-006.
  - [ ] **AC-009.a** Define dialect/model/analysis capability profiles for additional simulators without leaking vendor syntax into source semantics.
  - [ ] **AC-009.b** Add one qualified additional adapter and retain a documented seam for HSPICE/Spectre/Xyce-class flows as applicable, not a blanket support claim.
  - [ ] **AC-009.c** Handle executable discovery, licenses, runtime isolation, sensitive files, and simulator-specific failure states explicitly.
  - [ ] **AC-009.d** Compare supported common circuits using agreed numerical tolerances and separate expected model differences from translation defects.
  - [ ] **AC-009.e** Preserve portable circuit IDs and identical measurement definitions across simulator projections; unavailable vendor jobs report unavailable, not pass.
  - [ ] **AC-009.f** Exit: exact adapter/version/model capability evidence and reproducible cross-simulator comparisons are published for the declared release profile.

- [ ] **AC-010 — Circuit-to-layout physical-intent handoff**
  - Depends on: AC-004, AC-008.
  - [ ] **AC-010.a** Carry device grouping, matching, symmetry, common-centroid/interdigitation intent, sensitive nets, pin intent, and regions through stable circuit identities.
  - [ ] **AC-010.b** Separate required constraints from preferences and define conflicts, unsupported intent, explicit relaxations, and source diagnostics.
  - [ ] **AC-010.c** Export resolved geometry/device parameters plus unresolved physical intent without forcing any one layout-tool schema or geometry database into `.nax`.
  - [ ] **AC-010.d** Preserve intent across hierarchy, generated instances, sizing changes, and parameter specialization; invalidate stale constraints when their targets change.
  - [ ] **AC-010.e** Test missing targets, incompatible dimensions, contradictory constraints, and binding revisions using independent expected mappings.
  - [ ] **AC-010.f** Exit: a versioned circuit/physical-intent package is consumed by a thin external-tool adapter with complete identity and diagnostic coverage, without a new physical engine.

- [ ] **AC-011 — Mixed-signal macro and multi-view consistency**
  - Depends on: AC-006, AC-010.
  - [ ] **AC-011.a** Link behavioral Verilog-A/AMS, structural circuit, digital wrappers, clocks/resets, supplies, modes, and configuration interfaces to one macro identity.
  - [ ] **AC-011.b** Preserve conservative terminals, digital nets, discrete-real values, and explicit converters as distinct interface categories.
  - [ ] **AC-011.c** Reuse existing AMS scheduling/verification contracts for threshold, sampling, hold, initialization, and time-resolution semantics; do not add a second scheduler.
  - [ ] **AC-011.d** Validate port/model/parameter/mode consistency and state each model's validity envelope and omitted physical effects.
  - [ ] **AC-011.e** Correlate behavioral and transistor views on a declared small mixed-signal fixture; require only the relevant external verification capabilities.
  - [ ] **AC-011.f** Exit: multi-view mismatch negatives and correlation tolerances pass with actual models/results; no automatic analog-RTL-to-transistor claim.

- [ ] **AC-012 — Circuit/PDK portability and release qualification**
  - Depends on: AC-001 through AC-011.
  - [ ] **AC-012.a** Qualify the declared circuit/model/analysis feature matrix, package versions, simulator adapters, and supported PDK bindings.
  - [ ] **AC-012.b** Demonstrate a second process/profile binding where authorized, with explicit resizing/revalidation and reported unsupported devices rather than assumed physical portability.
  - [ ] **AC-012.c** Measure circuit-graph size, package size, import/export time, memory, run scheduling, and reproducibility on named small and large fixtures.
  - [ ] **AC-012.d** Publish install, debugging, failure recovery, compatibility, migration, and licensing instructions with frozen fixture/artifact hashes.
  - [ ] **AC-012.e** Audit dependency provenance, non-convergence handling, negative tests, and every earlier child/evidence link; record remaining capability gaps.
  - [ ] **AC-012.f** Exit: all AC children and prerequisites are complete and the selected circuit release is reproducible; layout and tapeout qualification remain separate gates.

## Track APL — Analog Layout and Physical Implementation

Owner: thin layout integration modules and qualified external layout tools. The track name describes the flow being enabled, not a Nodal-owned physical-design engine. Existing tools own geometry databases, primitive generation, placement, routing, and stream readers/writers; Nodal owns intent translation, capability checks, artifact references, and source correlation.

- [ ] **APL-001 — Physical-intent and external-layout adapter contract**
  - Depends on: AC-010.
  - [ ] **APL-001.a** Define circuit/device/group/net/constraint identities and stable references to tool-owned objects, layers, regions, ports, and layout artifacts; do not define a complete native geometry database.
  - [ ] **APL-001.b** Specify matching, symmetry, common-centroid/interdigitation, orientation, abutment, dummy, well/guard-ring, shielding, keepout, and sensitive-net intent.
  - [ ] **APL-001.c** Preserve hierarchy, provenance, required-versus-preferred constraints, and unsupported/conflicting-constraint diagnostics.
  - [ ] **APL-001.d** Define a small versioned adapter manifest with circuit-to-tool mappings, units, capabilities, artifact hashes, and native-checkpoint references; detailed physical state stays in the external tool.
  - [ ] **APL-001.e** Define external-backend lowering contracts and conformance fixtures; do not equate a common constraint name with identical tool semantics.
  - [ ] **APL-001.f** Exit: approved intent/adapter contract and positive/negative fixtures pass; no Rust subsystem, geometry kernel, or physical DB is an acceptance requirement.

- [ ] **APL-002 — Physical technology and layout PDK binding**
  - Depends on: APL-001, AC-004.
  - [ ] **APL-002.a** Bind the selected external tool's legal device generators, layout units/grids, layer-purpose maps, vias, wells, taps, and process-specific views.
  - [ ] **APL-002.b** Preserve rule-deck/model/version/license provenance separately from abstract architecture and keep restricted PDK data external.
  - [ ] **APL-002.c** Match schematic device parameters to external layout-generator parameters, including fingers, multiplicity, orientation, contacts, and effective geometry.
  - [ ] **APL-002.d** Declare supported rule/layout features and reject missing process definitions, unsafe unit conversions, and out-of-range dimensions.
  - [ ] **APL-002.e** Create an auditable physical-tool binding for the initial AC PDK subset and independently check device terminal/layer maps.
  - [ ] **APL-002.f** Exit: bound-device/layout fixtures and negative PDK tests pass; no DRC-clean or foundry-approved claim is made before verification.

- [ ] **APL-003 — External device, passive, and guard-ring generator adapters**
  - Depends on: APL-002.
  - [ ] **APL-003.a** Adapt existing qualified transistor/passive/tap/guard-ring generators for the selected process; do not implement replacement primitive-layout engines.
  - [ ] **APL-003.b** Invoke those generators to obtain paired physical and schematic views with explicit terminal access, sizing, fingers, dummies, and device identity.
  - [ ] **APL-003.c** Map selected matched groups and reusable small circuit templates to supported generators; preserve intentional dummy and bulk connections.
  - [ ] **APL-003.d** Evaluate GLayout/BAG-class generators only through declared adapter and license/tool profiles; their presence is not automatic PDK or sign-off support.
  - [ ] **APL-003.e** Sweep supported geometries and compare generated topology/terminal maps independently, retaining representative layouts for later DRC/LVS qualification.
  - [ ] **APL-003.f** Exit: deterministic external-generator outputs and unsupported-case diagnostics pass; physical correctness still requires APV evidence.

- [ ] **APL-004 — External layout artifacts and GDSII/OASIS interchange**
  - Depends on: APL-003.
  - [ ] **APL-004.a** Record tool-owned layout/checkpoint references, circuit mappings, format/tool/PDK versions, and hashes in an artifact manifest rather than a canonical Rust physical DB.
  - [ ] **APL-004.b** Invoke existing tool or library APIs for declared GDSII/OASIS import/export; do not implement a new stream parser/writer or duplicate the backend geometry model.
  - [ ] **APL-004.c** Verify units, layer maps, transforms, cell names, and macro references through external APIs/reports; reject lossy unsupported conversions.
  - [ ] **APL-004.d** Use immutable artifact/checkpoint references and dependency hashes for restart, bounded loading, and invalidation; preserve the original tool-native files.
  - [ ] **APL-004.e** Compare hierarchy/geometry/port meaning using independent existing readers where available; require byte reproducibility only where the selected writer guarantees it.
  - [ ] **APL-004.f** Exit: external-tool round-trip, corruption, unit/overflow, and scale fixtures pass with an explicit supported-format matrix and no custom layout database dependency.

- [ ] **APL-005 — Imported layouts and hard-macro reuse**
  - Depends on: APL-004, AC-011.
  - [ ] **APL-005.a** Register hand-designed or generated macros and import them into the selected external tool with GDS/OASIS, abstracts, circuit views, provenance, and permitted-use constraints.
  - [ ] **APL-005.b** Map tool-reported terminals and supplies to circuit IDs while retaining opaque internals where licensed views do not expose them.
  - [ ] **APL-005.c** Validate bounding boxes, legal transforms, placement obstructions, layer units, keepouts, model versions, and pin consistency through supported APIs.
  - [ ] **APL-005.d** Use the external tool to compose imported and generated blocks; do not imply imported IP has been requalified for another process or operating envelope.
  - [ ] **APL-005.e** Exercise port swaps, missing views, wrong process, incompatible units, unavailable internals, and changed macro revisions.
  - [ ] **APL-005.f** Exit: a declared mixed imported/generated layout fixture is reproducible with explicit opaque-view and verification limitations.

- [ ] **APL-006 — First ALIGN automated-layout vertical slice**
  - Depends on: APL-005, AC-006.
  - [ ] **APL-006.a** Lower the selected sized circuit, physical constraints, and PDK binding into a pinned ALIGN adapter profile.
  - [ ] **APL-006.b** Preflight supported devices/constraints/tool versions and reject unsupported physical intent before execution rather than silently omitting it.
  - [ ] **APL-006.c** Run the external engine on a small transistor-level benchmark through primitive generation, placement, routing, and stream-out with retained stage artifacts.
  - [ ] **APL-006.d** Collect tool-native checkpoints, layouts, logs, constraint reports, and circuit/device/net mappings into the artifact manifest; do not import geometry into a new Nodal physical DB.
  - [ ] **APL-006.e** Reproduce successful and intentionally infeasible/unroutable runs; distinguish complete, partial, failed, and unsupported results.
  - [ ] **APL-006.f** Exit: the first external automatic-layout profile produces inspectable reproducible GDS/layout evidence; DRC/LVS/PEX closure is APV-005, not assumed here.

- [ ] **APL-007 — Hierarchical placement through external tools**
  - Depends on: APL-006.
  - [ ] **APL-007.a** Translate matched groups, regions, fixed macros, pin access, and legal orientations into the selected existing placer's API/constraint inputs.
  - [ ] **APL-007.b** Invoke external placement and check symmetry/common-centroid and abutment postconditions using qualified reports/APIs.
  - [ ] **APL-007.c** Separate hard constraints from cost objectives and record every accepted explicit relaxation with affected source IDs.
  - [ ] **APL-007.d** Reuse tool-native incremental placement/checkpoints where supported and invalidate stale downstream extraction/verification after changes.
  - [ ] **APL-007.e** Benchmark adapter overhead and external-tool quality/runtime/memory; test contradictory, impossible, and unsupported constraints.
  - [ ] **APL-007.f** Exit: selected hierarchical cases meet declared placement constraints with evidence; developing a Nodal-native placer is outside this track.

- [ ] **APL-008 — Analog routing through external tools**
  - Depends on: APL-007.
  - [ ] **APL-008.a** Map differential/symmetric routing, shielding, sensitive nets, current classes, layer preferences, via policies, and pre-routes to supported external-tool inputs.
  - [ ] **APL-008.b** Invoke the existing router or supported scripted layout flow while preserving connectivity, pin access, keepouts, and required intent; do not implement a replacement routing algorithm.
  - [ ] **APL-008.c** Track tool-reported routed-net/circuit correspondence and invalidate extraction, DRC, and performance evidence whenever relevant geometry changes.
  - [ ] **APL-008.d** Report unsupported or infeasible routing intent and explicit overrides; do not silently replace analog constraints with ordinary shortest-path routing.
  - [ ] **APL-008.e** Qualify focused sensitive/differential/current-carrying examples and inject opens, shorts, shield defects, and wrong-layer routes through existing tool fixtures.
  - [ ] **APL-008.f** Exit: externally routed examples pass connectivity/intent checks with replayable logs; electrical performance and reliability claims require APV checks.

- [ ] **APL-009 — Mixed-signal macro boundary and integration views**
  - Depends on: APL-008, AC-011.
  - [ ] **APL-009.a** Collect and correlate physical boundary, supply, clock/reset, digital-control, conservative-terminal, and analog-keepout views from supported tools.
  - [ ] **APL-009.b** Obtain applicable LEF/abstract and timing/interface data through existing engines and adapters; Liberty alone must not stand in for analog behavior or jitter/noise models.
  - [ ] **APL-009.c** Support separately synthesized digital wrappers around the analog macro with a reproducible black-box and port-mapping contract.
  - [ ] **APL-009.d** Preserve isolation, power sequencing, reference-clock, reset/lock, and mode constraints as interface requirements rather than hidden tool assumptions.
  - [ ] **APL-009.e** Exercise a small mixed-signal macro shell and deliberate digital/analog port, supply, clock, or view-version mismatches.
  - [ ] **APL-009.f** Exit: internally consistent integration views and a checked consumer handoff are retained; full-chip ASIC implementation remains separately governed.

- [ ] **APL-010 — External layout inspection and source correlation**
  - Depends on: APL-006, AC-003.
  - [ ] **APL-010.a** Retain tool-object-to-circuit-to-Nodal source mappings and queryable device/net/constraint references without using names as the only keys.
  - [ ] **APL-010.b** Add thin headless query/report and optional existing-viewer adapters; neither a new GUI nor a native physical database is a prerequisite.
  - [ ] **APL-010.c** Correlate placement/routing failures and physical-verification markers to semantic sources and exact tool-native layout revisions.
  - [ ] **APL-010.d** Bound adapter metadata and query costs using external APIs and artifact references rather than eagerly copying all geometry.
  - [ ] **APL-010.e** Test mapping across hierarchy, imported macros, transformed arrays, flattened projections, and missing source information.
  - [ ] **APL-010.f** Exit: a reviewer can trace selected source devices/nets to actual geometry and back through the selected tool, with explicit unavailable/opaque results.

- [ ] **APL-011 — Commercial and custom-layout adapter seam**
  - Depends on: APL-006.
  - [ ] **APL-011.a** Define process/layout/generator adapter profiles with version, capability, licensing, sandboxing, and artifact contracts.
  - [ ] **APL-011.b** Implement and qualify one additional accessible external layout/generator integration; distinguish tested commercial profiles from unexecuted adapter stubs.
  - [ ] **APL-011.c** Translate canonical physical intent without changing language semantics or hiding unsupported constraint differences.
  - [ ] **APL-011.d** Preserve imported/generated IP rights and keep confidential process data, commands, and result artifacts in authorized storage.
  - [ ] **APL-011.e** Compare equivalent declared cases across adapters and check unavailable licenses, missing views, and failed tool executions.
  - [ ] **APL-011.f** Exit: the selected adapter contract is demonstrated and untested profiles remain explicitly unqualified; commercial availability does not block APL-006.

- [ ] **APL-012 — Layout-adapter scale and release qualification**
  - Depends on: APL-001 through APL-011, APV-005.
  - [ ] **APL-012.a** Freeze the supported PDK/device/constraint/layout-format matrix and selected external primitive/placement/routing adapter versions.
  - [ ] **APL-012.b** Qualify hierarchical and heterogeneous benchmark layouts, including imported hard macros and source/provenance preservation.
  - [ ] **APL-012.c** Measure external-engine costs separately from adapter metadata, artifact transfer, query, checkpoint reuse, and invalidation costs at increasing named scales.
  - [ ] **APL-012.d** Publish reproducible tool-native layout packages, capability gaps, adapter installation/licensing requirements, and compatibility/migration guidance.
  - [ ] **APL-012.e** Audit all earlier APL children and APV-005 evidence, including unit/geometry/connectivity negatives and stale-evidence rejection.
  - [ ] **APL-012.f** Exit: all declared integration cases and dependencies pass with retained evidence; no new repository, Rust subsystem, native geometry DB, placer, or router is required.

## Track APV — Analog Physical Verification and Sign-off

Owner: Nodal tool-adapter/evidence modules using process-qualified external verification and extraction engines. No Nodal-native DRC/LVS/PEX or analog solver is implemented. APV results never replace the existing behavioral AMS Verification track or a foundry's required full-chip qualification.

- [ ] **APV-001 — Normalized physical-verification evidence model**
  - Depends on: AC-001, APL-001.
  - [ ] **APV-001.a** Define run, rule, marker, device/net, circuit revision, layout revision, PDK/deck/tool, corner, waiver, and evidence identities.
  - [ ] **APV-001.b** Represent pass, fail, unsupported, not-run, tool-error, timeout, and incomplete evidence as distinct states; missing evidence is never success.
  - [ ] **APV-001.c** Bind every result to exact input/tool/deck hashes, options, environment, and capability profile with immutable artifact references.
  - [ ] **APV-001.d** Define explicit waiver authority/scope/expiry and invalidation after circuit, layout, process, or tool changes.
  - [ ] **APV-001.e** Add independent report-parser/normalizer fixtures including truncated logs, zero-check runs, stale artifacts, and mismatched revisions; reuse existing geometry readers.
  - [ ] **APV-001.f** Exit: reviewed evidence schema and failure-state tests pass; model infrastructure alone does not mark any physical check complete.

- [ ] **APV-002 — Open DRC baseline**
  - Depends on: APV-001, APL-004.
  - [ ] **APV-002.a** Bind the selected PDK's available rule deck to a pinned KLayout/Magic-class DRC adapter and declare actual rule coverage.
  - [ ] **APV-002.b** Run external DRC on primitive, matched-group, and small layout fixtures with exact stream/layer/grid provenance.
  - [ ] **APV-002.c** Normalize violations and correlate markers to layout/source IDs without treating a tool exit code alone as a clean result.
  - [ ] **APV-002.d** Inject width/spacing/enclosure and other supported violations and verify expected rule IDs and geometry using existing tools.
  - [ ] **APV-002.e** Report unavailable rules and explicit waivers; never rename an open-deck result as foundry sign-off.
  - [ ] **APV-002.f** Exit: declared DRC-positive/negative fixtures pass with complete executed-rule and deck/tool evidence.

- [ ] **APV-003 — Open LVS baseline**
  - Depends on: APV-002, AC-005.
  - [ ] **APV-003.a** Invoke a qualified KLayout/Netgen-class LVS profile to extract devices/connectivity and compare against the intended bound circuit; reuse its extraction/comparison engines.
  - [ ] **APV-003.b** Define terminal equivalence, allowed device reduction, bulk/supply treatment, multiplicity, and parameter tolerances explicitly.
  - [ ] **APV-003.c** Preserve hierarchy and source correspondence through extraction and normalized mismatch reports.
  - [ ] **APV-003.d** Detect injected opens, shorts, pin/bulk swaps, missing devices, and incorrect dimensions or finger interpretation.
  - [ ] **APV-003.e** Handle black-box/imported-macro limitations, missing models, and incomplete extraction without declaring whole-design LVS clean.
  - [ ] **APV-003.f** Exit: reference and deliberately faulty layouts receive correct independent LVS results with exact circuit/layout/deck provenance.

- [ ] **APV-004 — Parasitic extraction and extracted-circuit identity**
  - Depends on: APV-003.
  - [ ] **APV-004.a** Add a qualified external extraction adapter with explicit capacitance, resistance, coupling, and reduction capabilities; do not implement extraction algorithms.
  - [ ] **APV-004.b** Preserve device/net correspondence and versioned references to extracted circuits tied to exact layout, process, corner, and extraction settings.
  - [ ] **APV-004.c** Distinguish schematic parasitics, extracted parasitics, and reduced models; prevent double-counting or silently missing unsupported effects.
  - [ ] **APV-004.d** Collect simulator-compatible extracted netlists from existing tools with terminal/unit/model checks and bounded artifact sizes.
  - [ ] **APV-004.e** Validate known RC structures and extraction-corner changes independently; inject incorrect units, disconnected parasitics, and stale results.
  - [ ] **APV-004.f** Exit: supported extraction cases reproduce within declared tolerances; RF, substrate, and advanced effects remain unsupported unless explicitly qualified.

- [ ] **APV-005 — First post-layout simulation and correlation loop**
  - Depends on: APV-004, APL-006, AC-007.
  - [ ] **APV-005.a** Execute the selected source/package/circuit/external-layout/DRC/LVS/PEX/post-layout-simulation flow using one locked run manifest and the common runner.
  - [ ] **APV-005.b** Reuse the same stimuli and measurement definitions for pre-layout and extracted simulations with explicit model differences.
  - [ ] **APV-005.c** Check post-layout specifications and permitted degradation, not merely waveform similarity or successful simulator completion.
  - [ ] **APV-005.d** Retain every circuit/layout/check/measurement artifact and invalidate results after any upstream change.
  - [ ] **APV-005.e** Demonstrate failures caused by parasitics, circuit/layout mismatch, non-convergence, missing checks, and incomplete corner coverage.
  - [ ] **APV-005.f** Exit: the first complete open physical loop passes for a declared small circuit/profile, with reproducible limits and no automatic tapeout-signoff claim.

- [ ] **APV-006 — Post-layout PVT, variation, and closure loops**
  - Depends on: APV-005, AC-008.
  - [ ] **APV-006.a** Run declared extracted-circuit PVT, operating-mode, and statistical matrices through external simulators with all process/model/seed assumptions retained.
  - [ ] **APV-006.b** Measure gain/bandwidth/stability/noise/power or other circuit-specific requirements only with qualified analysis capabilities.
  - [ ] **APV-006.c** Feed failed measurements into bounded external sizing/layout iterations with explicit budgets and complete candidate lineage; do not implement a native solver or physical optimizer.
  - [ ] **APV-006.d** Require fresh layout/DRC/LVS/PEX evidence after physical changes and fresh measurement evidence after model or specification changes.
  - [ ] **APV-006.e** Report infeasible specifications, budget exhaustion, convergence failures, and unsupported yield estimates rather than choosing only passing samples.
  - [ ] **APV-006.f** Exit: a bounded circuit family demonstrates repeatable extracted-performance closure with complete matrices and independent measurement checks.

- [ ] **APV-007 — Reliability and additional physical-rule capabilities**
  - Depends on: APV-006, APL-008.
  - [ ] **APV-007.a** Define applicable ERC, voltage-domain, well/tap, latch-up, antenna, density, EM/IR, thermal, and aging check identities and responsibility boundaries.
  - [ ] **APV-007.b** Integrate only available qualified external analyses/decks and explicitly record absent foundry/reliability capabilities; develop no replacement reliability engine.
  - [ ] **APV-007.c** Preserve activity, supply, current, temperature, lifetime, mode, and package assumptions required by each analysis.
  - [ ] **APV-007.d** Correlate violations and margins with circuit/layout/source identities and approved waiver records.
  - [ ] **APV-007.e** Inject supported electrical/reliability-rule defects and reject incomplete or invalid check coverage.
  - [ ] **APV-007.f** Exit: capability-specific reliability evidence passes for the declared profile; unsupported checks block any release profile that requires them.

- [ ] **APV-008 — Commercial sign-off and extraction adapters**
  - Depends on: APV-005.
  - [ ] **APV-008.a** Define approved-process/tool/deck/extraction profile contracts and verify the authorization needed to use protected process/IP data.
  - [ ] **APV-008.b** Implement an accessible commercial/sign-off adapter or explicitly retain it as unqualified until the actual licensed flow can execute.
  - [ ] **APV-008.c** Preserve native reports alongside normalized evidence and prevent an adapter stub, mock, or open-deck run from claiming commercial sign-off.
  - [ ] **APV-008.d** Compare declared common DRC/LVS/extraction fixtures against the qualified reference with documented tolerances and capability differences.
  - [ ] **APV-008.e** Test license/tool/deck failures, confidentiality boundaries, result corruption, and wrong-process or wrong-version requests.
  - [ ] **APV-008.f** Exit: actual approved-tool evidence exists for every claimed sign-off profile; unavailable access leaves the corresponding task open and does not block APV-005.

- [ ] **APV-009 — Final stream-out and archive qualification**
  - Depends on: APV-006, APL-004.
  - [ ] **APV-009.a** Freeze exact final layout/circuit/PDK/view versions and the external tool's stream-out options, units, layer maps, hierarchy, and top-cell identity.
  - [ ] **APV-009.b** Re-import delivered GDSII/OASIS using qualified existing readers and verify intended geometry/hierarchy equivalence and required final-stream physical checks.
  - [ ] **APV-009.c** Reject mismatched, stale, partial, or unverified final files even when an earlier tool-native checkpoint revision passed.
  - [ ] **APV-009.d** Build a reproducible release archive with checksums, tool/deck provenance, logs, waivers, instructions, and access/license classifications.
  - [ ] **APV-009.e** Exercise clean-machine restoration and deliberate file/version corruption without needing hidden local tool state.
  - [ ] **APV-009.f** Exit: the exact delivered stream and archive are traceable to valid checks; stream-out success alone is not tapeout approval.

- [ ] **APV-010 — Analog hard-macro release package**
  - Depends on: APV-009, APL-009, AC-011.
  - [ ] **APV-010.a** Package applicable externally produced GDS/OASIS, LEF/abstract, extracted, and timing views with the circuit, behavioral, digital-wrapper, and physical-interface views.
  - [ ] **APV-010.b** Preserve legal configurations, supply/clock/reset/lock sequencing, electrical limits, PVT/model envelopes, pin maps, and integration constraints.
  - [ ] **APV-010.c** Include characterization data, unsupported effects, release profile, checks/waivers, license restrictions, and immutable source/tool evidence.
  - [ ] **APV-010.d** Validate consistency across all applicable views and state why a view is not applicable rather than fabricating timing or analog characterization.
  - [ ] **APV-010.e** Demonstrate an independent macro consumer can load the package and diagnose incompatible process, interface, or evidence revisions.
  - [ ] **APV-010.f** Exit: a reproducible, internally consistent macro package is published for its declared qualification profile, not silently promoted to foundry sign-off.

- [ ] **APV-011 — Mixed-signal and full-chip handoff closure**
  - Depends on: APV-010, APV-007.
  - [ ] **APV-011.a** Define macro-consumer contracts for future ASIC/FPGA integration, keeping full-chip floorplan/P&R/sign-off execution in its separately approved owner.
  - [ ] **APV-011.b** Validate black-box digital integration, supplies/domains, reference clocks, resets, configuration/modes, analog boundaries, and package-level assumptions.
  - [ ] **APV-011.c** Integrate relevant existing AMS Verification capabilities for a small macro-plus-digital-shell fixture without blocking on unrelated verification features.
  - [ ] **APV-011.d** Make missing top-level coupling, substrate, package, power, clock-jitter, reliability, or DFT qualification explicit handoff obligations.
  - [ ] **APV-011.e** Exercise deliberate view, domain, connectivity, and configuration mismatches and retain consumer-side acceptance evidence.
  - [ ] **APV-011.f** Exit: the macro handoff is validated with all remaining full-chip responsibilities named; this does not create or complete a reserved ASIC implementation track.

- [ ] **APV-012 — Tapeout-oriented analog macro qualification**
  - Depends on: APV-001 through APV-011, AC-012, APL-012.
  - [ ] **APV-012.a** Audit all parent/child completion, declared release capabilities, open defects, assumptions, and exact final circuit/layout/view hashes.
  - [ ] **APV-012.b** Execute every mandatory simulation/physical/reliability check using qualified existing tools for the selected process and macro profile; missing or unsupported mandatory checks block release.
  - [ ] **APV-012.c** Retain actual approved process/tool/deck evidence before using a foundry-sign-off label; otherwise release only under the accurately named open/research or Nodal-qualified profile.
  - [ ] **APV-012.d** Freeze a reviewable macro release dossier, integration responsibilities, characterization/test plan, archive, provenance, and authorized release approval.
  - [ ] **APV-012.e** Reproduce the release from clean inputs and audit negative/failure paths, reproducibility limits, confidentiality, and license compliance.
  - [ ] **APV-012.f** Exit: all required children/dependencies and selected-profile checks are complete with explicit approval; chip-level tapeout and manufactured-silicon validation remain separate evidence.

## Implementation and CI strategy after the Foundation barrier

Use contract/serialization/diagnostic and small circuit checks on ordinary implementation changes; use scheduled or explicitly requested integration jobs for supported SPICE and layout profiles; use separate authorized infrastructure for licensed tools and confidential PDKs. Preserve exact versions, input hashes, seeds, numerical tolerances, and convergence states. Static graph/type checks and formal checks of digital wrappers do not prove arbitrary continuous-time analog behavior. Simulation, independent netlist/geometry checks, DRC/LVS/PEX, and post-layout measurement each provide different evidence.

Reuse existing project process-adapter infrastructure and the common runner, with future `nodal-eda` integration optional. Require clean failures, bounded resource use, resumable run matrices, cache invalidation, independent reference fixtures, and distinct unsupported/not-run/passed states. Open CI must not require proprietary PDKs or silently treat unavailable commercial checks as passing. Each release selects and documents its mandatory capabilities. A commercial sign-off release remains blocked until its actual required tools/decks execute. No new analog-native engine, repository, Rust runtime, or duplicate workflow framework is a CI prerequisite.

This roadmap revision itself is documentation-only. It creates no workflow, runs no CI/test/simulation/physical tool, creates no `nodal-analog` repository, and changes no compiler or generated HDL behavior.

## Deliberate exclusions and risk controls

- Implementation of a Nodal physical-design engine, native geometry database/kernel, placer/router, SPICE/AMS solver, DRC/LVS/PEX engine, or new stream parser/writer is removed from these tracks. Reuse existing tools/libraries. Any later native-engine proposal requires separate user approval and a separate scope decision.
- No new Rust subsystem or separate `nodal-analog` repository is required. Circuit/compiler work stays in existing Nodal Scala/MLIR infrastructure; adapters use the selected tool's practical language/API.
- Arbitrary behavioral Verilog-A/AMS to transistor synthesis is not an implemented or promised capability. AC-008 starts with known circuit families and external bounded sizing; broader synthesis requires a separately approved extension.
- Existing tool support is circuit-, constraint-, and PDK-specific. A missing capability is an explicit blocker for that profile, not permission to silently drop constraints or begin a native-engine rewrite.
- PDK mappings and layout constraints are not automatically portable. Every process/device/rule/simulator combination needs its own declared capability and validation evidence.
- GDSII/OASIS generation, passing an open DRC deck, or functional simulation does not by itself establish foundry approval, manufacturability, analog performance, or silicon reliability.
- No physical result may be reused after relevant source, circuit, layout, model, PDK, tool, rule deck, corner, or option changes without a valid dependency/invalidation decision.
- Protect third-party IP, PDK models/decks, license servers, and proprietary results. Open-source tool licensing and foundry data licensing are separate integration requirements.

## Reference integration contracts to evaluate

These are candidate tool boundaries inherited from the research, not claims that adapters or production profiles already exist:

- [ALIGN](https://align-analoglayout.github.io/ALIGN-public/) for a selected sized-SPICE/constraint/PDK-to-layout slice.
- [OpenFASoC/GLayout](https://openfasoc.readthedocs.io/) and [BAG](https://bag3-readthedocs.readthedocs.io/) for generator-oriented alternatives with explicit environment and licensing requirements.
- [ngspice](https://ngspice.sourceforge.io/docs.html) and [OpenVAF](https://openvaf.semimod.de/) for a qualified open circuit/model simulation profile, not general Verilog-AMS execution.
- [KLayout](https://www.klayout.de/doc/manual/index.html), [Magic](http://opencircuitdesign.com/magic/), and [Netgen](http://opencircuitdesign.com/netgen/) for declared stream/verification/extraction adapters.

All adapter versions, supported subsets, PDK profiles, numerical limits, and sign-off claims must be established by the relevant implementation gate, not inferred from these references.
