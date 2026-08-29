# Continuous-time analog/AMS compiler architecture v0.1 plan

**Status:** Normative roadmap target
**Architecture:** [ADR 0022](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md)
**Roadmap owner:** Increment 132
**Public semantic gate:** Increment 133
**Implementation and qualification:** Increments 134-142

## Goal

Give Nodal a solver-independent continuous-time compiler architecture that is stronger than a Verilog-A/Verilog-AMS text generator while preserving the existing incremental path to useful emitted models.

The binding rule is:

> **Preserve source analog intent, partition explicit analog islands, normalize verified topology and hybrid equations, derive analysis-specific solver forms, and retain capability and validity evidence.**

This phase is cross-cutting. It integrates the existing Phase 1-4 analog and AMS increments rather than replacing or renumbering them.

## Scope boundaries

This plan does not require Nodal to ship a native analog solver in the first release.

The first authoritative execution path remains:

```text
Scala/Nodal source
        ↓
source-semantic analog IR
        ↓
verified topology/equation/event/analysis contracts
        ↓
Verilog-A or Verilog-AMS target IR
        ↓
external compiler/simulator adapter
```

The same semantic layers later support an OSDI-like model ABI, a native solver plugin, advanced analyses, and AMS-to-FPGA approximation without changing source semantics.

This plan does not:

- turn conservative equations into directional signal flow;
- make Modelica syntax or semantics part of Nodal;
- make one simulator's scheduling or callbacks authoritative;
- authorize automatic approximation, index reduction, topology rewrite, model reduction, or smoothing;
- freeze exact public Scala spelling before Increment 133;
- populate a production analog model library.

## Architectural layers

### Source-semantic analog IR

Preserve analog regions, contributions, equations, procedural analog statements, operators, events, analysis/environment queries, noise, connect constructs, dimensions, hierarchy, parameters, and source spans.

### Topology graph

Represent terminals, nodes, branches, references, aliases, connection sets, potential/flow conservation, signal-flow regions, bridges, and explicit topology modes.

### Hybrid equation-system IR

Represent stable unknown, equation, residual, state, derivative, event, parameter, environment, and noise identities with dimensions, scales, tolerances, guards, analysis applicability, source spans, and parameter envelopes.

### Analysis projections

Derive initialization, DC/operating point, transient, AC/small-signal, and noise forms through verified transformations. Future analyses remain capability gated.

### Solver/target layer

Lower verified forms to Verilog-A, Verilog-AMS, an OSDI-like ABI, or a future native solver interface. Adapters declare capabilities and retain commands, versions, options, hashes, diagnostics, and results.

## `AnalogIsland` contract

Each island owns:

- conservative topology and references;
- continuous and algebraic unknowns;
- residual equations and contribution provenance;
- continuous and discrete state;
- event/root indicators;
- analysis and environment requirements;
- dimensions, nominal scales, and tolerances;
- solver capability requirements;
- bridge boundaries;
- source and hierarchy provenance.

Island merge, split, reduction, or approximation is illegal without a selected transformation and retained validation evidence.

## State categories

The architecture distinguishes:

- explicit user continuous state;
- state introduced by `idt` or transfer operators;
- delay, transition, slew, and waveform state;
- hysteresis and event state;
- sample/hold and mixed-signal bridge state;
- target- or solver-required state with explicit provenance.

Every state has initialization, reinitialization, analysis, observability, ownership, and validity metadata.

## Structural verification

Minimum structural checks include:

- topology connectivity and references;
- conservation and discipline compatibility;
- equation/unknown balance;
- structural matching and block decomposition;
- under/over-constraint;
- structural singularity;
- algebraic and event loops;
- initialization completeness/conflicts;
- parameter-envelope-dependent topology or rank;
- unsupported variable structure;
- solver/backend requirement inventory.

Higher-index reduction, tearing, homotopy, limiting, and model reduction remain separately approved transformations.

## Hybrid scheduler

The semantic schedule distinguishes:

1. construction and elaboration;
2. analysis initialization;
3. continuous solve/integration;
4. root and scheduled-time event detection;
5. analog/discrete event execution;
6. zero-time event iteration to a fixed point;
7. digital-domain and bridge interactions;
8. continuous restart;
9. finalization.

Adapters may implement different algorithms but must preserve observable ordering and reject unsupported behavior.

## Analysis and noise

Initial analysis kinds are:

- initialization;
- DC/operating point;
- transient;
- AC/small-signal;
- noise.

Analysis-specific behavior, linearization, derivative requirements, noise identity/correlation, and environmental dependencies are explicit in IR and manifests.

Transient stochastic variation, Monte Carlo variation, and small-signal noise are distinct concepts with separate seeds and evidence.

## Environment and model validity

A run context may include time, frequency, temperature, nominal temperature, corner/process identity, supplies or declared operating conditions, sweep coordinates, and deterministic random seeds.

A model validity envelope may constrain parameters, operating quantities, analyses, PVT, frequency/time scale, topology, loading, and accuracy claims. Violations never silently clamp or alter equations.

## Solver hints

Nominal values, scaling, tolerances, maximum step, limiting, and convergence guidance are represented separately from equations. Required semantic behavior and optional quality-of-implementation hints are distinguishable.

## Machine-readable artifacts

The phase ultimately produces:

- canonical analog semantic manifest;
- topology/island manifest;
- equation/unknown/state/event inventory;
- DAE structural report;
- initialization report;
- analysis projection report;
- derivative/Jacobian/sparsity report;
- environment/PVT/seed manifest;
- model validity manifest;
- solver capability negotiation report;
- target-lowering/source-map manifest;
- differential validation evidence.

## Existing roadmap integration

### Increments 16-26

Preserve construction ownership, source spans, analog IR seams, deterministic IDs, textual MLIR, and the minimal RC vertical slice. Increment 24 remains intentionally small but must not preclude later island/equation/state identities.

### Increments 27-47

Implement source language constructs against the source-semantic layer. Increments 35-40 register continuous state, events, analysis, noise, and transfer-operator requirements rather than lowering them directly to untracked backend calls. Increments 44-46 consume environment, analysis, discontinuity, state, and validity metadata.

### Increments 48-53

Validate generated target models while retaining semantic, analysis, solver-capability, environment, and source-map evidence. The external simulator is not the semantic authority.

### Increments 68-78

Use the hybrid scheduler and island/bridge contracts for mixed-signal conversion, connect rules, Verilog-AMS, simulator adapters, profiles, UVM-MS, and conformance.

### Increments 128-129

Use the same topology, island, analysis, and target-layout identities for conservative interface members and flattened Verilog-A/Verilog-AMS lowering.

### ADR 0011 AMS-to-FPGA approximation

The authoritative continuous-time model and its analysis projections remain the reference. Approximation consumes explicit semantic/state/event/envelope data but never replaces it automatically.

### ADR 0013 target-HDL optimization

Analog target passes preserve or explicitly invalidate equation, topology, state, event, analysis, noise, environment, validity, derivative, and capability properties. Waveform-only comparison cannot justify arbitrary continuous-time rewrites.

## Increment plan

- [x] **Increment 132 — Continuous-time equation, hybrid DAE, and solver architecture roadmap contract**
  - Accept ADR 0022, this plan, and the machine-readable surface.
  - Record layered analog IR, `AnalogIsland`, topology/equation/state/event/analysis graphs, hybrid scheduling, solver capability negotiation, environment/PVT, validity envelopes, and evidence boundaries.
  - Synchronize the main roadmap and architecture index without freezing public syntax or implementing compiler/solver behavior.

- [ ] **Increment 133 — Analog semantic API and analysis contract design gate**
  - Freeze the equation/component checkpoint first: unordered equations, additive contributions, procedural-assignment separation, conservative connections, partial/concrete components, local balance, structural parameters, and initial equations.
  - Publish `NodalEquationComponentApi-DG-v0.1.md` and its machine-readable checkpoint surface; Increment 32 may begin after this checkpoint is accepted even while the remaining full gate is still being validated.
  - Freeze the complete continuous-time public surface covering analog state and reinitialization, event tolerances and discontinuities, analysis context, environment/PVT, noise identity/correlation, validity envelopes, and solver hints.
  - Publish `NodalContinuousTimeApi-DG-v0.1.md`, a machine-readable public surface, migration notes, stable diagnostics, positive/negative fixtures, and an external reusable physical-component fixture.
  - Keep frontend, equation normalization, residual formation, solver, and backend behavior inert.

- [ ] **Increment 134 — Source-semantic analog IR, `AnalogIsland`, and stable identities**
  - Implement source-semantic operations for contributions, equations, operators, events, analyses, noise, environment, connect constructs, and solver hints.
  - Build deterministic islands and stable IDs for topology objects, unknowns, equations, state, events, noise, bridges, and analyses.
  - Add normalized parse/print, source maps, parameter formulas/envelopes, mutation tests, and semantic manifests.

- [ ] **Increment 135 — Topology expansion, residual DAE construction, and structural verification**
  - Expand conservative connections and contribution sets into solver-neutral residual systems while preserving provenance.
  - Classify continuous, derivative, algebraic, discrete, parameter, environment, and independent variables.
  - Implement incidence/dependency graphs, structural matching, block decomposition, equation/unknown balance, references, conservation, singularity, algebraic loops, initialization structure, variable-topology classification, and parameter-envelope checks.
  - Reject unsupported higher-index or variable-structure systems explicitly; do not perform unapproved index reduction.

- [ ] **Increment 136 — Continuous state, initialization, operators, and hidden-state reporting**
  - Implement state ownership for `ddt`, `idt`, Laplace/Z-domain operators, delay, transition, slew, hysteresis, sample/hold, and bridge state.
  - Implement fixed values, guesses, initial equations, steady-state conditions, operating-point-derived initialization, reinitialization/jumps, and conflict diagnostics.
  - Generate state/initialization reports and reject accidental duplication, movement, or hidden backend state.
  - Add semantics-preserving operator lowering and differential fixtures.

- [ ] **Increment 137 — Hybrid event scheduler, discontinuities, and mode-dependent topology**
  - Freeze and implement observable ordering for initialization, integration, root detection, timers, analog events, digital events, bridge updates, zero-time event iteration, restart, and finalization.
  - Implement dynamic event tolerances, named events, interrupted transitions, event fixed-point convergence, zero-time oscillation diagnostics, and event dependency graphs.
  - Classify hard discontinuities, smoothness, hysteresis, guarded equations, and explicitly supported topology modes.
  - Keep solver hints separate from mathematical behavior.

- [ ] **Increment 138 — Analysis projections, linearization, derivatives, and noise**
  - Derive initialization, DC/operating-point, transient, AC/small-signal, and noise projections from one semantic model.
  - Implement derivative/Jacobian interfaces, sparse structure, differentiability diagnostics, symbolic/automatic/analytic derivative evidence, and numerical-derivative capability fallback where explicitly allowed.
  - Preserve stable noise identity, PSD dimensions, hierarchy, correlation, transfer paths, and analysis applicability.
  - Add cross-analysis consistency and derivative differential tests.

- [ ] **Increment 139 — Environment, PVT, statistical variation, and model validity envelopes**
  - Implement immutable typed environment contexts for temperature, nominal temperature, corner/process, supplies/declared conditions, analysis/sweep coordinates, and deterministic random seeds.
  - Separate parameters, environment, small-signal noise, transient stochastic behavior, global variation, and local mismatch.
  - Implement static and dynamic validity-envelope checks, applicability/accuracy metadata, violation policy, source-located diagnostics, and manifests.
  - Add PVT/seed matrix determinism and envelope-boundary fixtures.

- [ ] **Increment 140 — Solver capability profiles and simulator/model ABI seam**
  - Define solver-neutral residual, state, event, derivative, noise, environment, and result interfaces.
  - Publish capability descriptors and negotiation for analyses, DAEs, events, variable topology, noise, derivatives, tolerances, connect rules, mixed-signal bridges, statistics, hierarchy, and result fidelity.
  - Define an OSDI-like external model adapter seam and a future native solver plugin seam without requiring either to define Nodal semantics.
  - Retain adapter versions, options, commands, hashes, capability decisions, diagnostics, and failure classification.

- [ ] **Increment 141 — Verilog-A/Verilog-AMS and solver-facing lowering parity**
  - Prove source-semantic, residual/analysis, and target-lowering correspondence for supported constructs.
  - Emit deterministic state, event, noise, analysis, environment, contribution, and source-map metadata alongside Verilog-A/Verilog-AMS.
  - Add target reparse, OpenVAF/OSDI where supported, ngspice and mixed-signal adapter differential fixtures, and explicit capability rejection.
  - Do not claim waveform equivalence as proof for unsupported structural transformations.

- [ ] **Increment 142 — Continuous-time validation, scale, and reusable-model qualification**
  - Add DC/operating-point/transient/AC/noise differential suites, event-order and initialization tests, parameter/PVT/seed matrices, cross-simulator comparisons, and failure classification.
  - Exercise large sparse islands, hierarchy, repeated models, deterministic ordering, derivative/Jacobian performance, cache keys, and memory/runtime budgets.
  - Qualify one external reusable analog model package using only public contracts, including model/environment/validity/solver manifests and portability reports.
  - Publish a capability and limitations matrix for higher-index DAE, dynamic topology, advanced analyses, stochastic features, and simulator extensions.

## Required positive matrix

The design and implementation gates should cover:

- RC and RLC networks;
- controlled sources and multi-discipline effort/flow examples;
- explicit and operator-created continuous state;
- fixed, guessed, equation-based, and steady-state initialization;
- algebraic loops that are legal and solvable;
- crossing, timer, named-event, and bridge-triggered events;
- zero-time event iteration that converges;
- piecewise and smoothed models;
- DC, operating point, transient, AC, and noise projections;
- independent and correlated noise sources;
- temperature and parameter sweeps;
- deterministic corner and mismatch seeds;
- model validity envelopes;
- parameterized hierarchy and repeated analog instances;
- source-to-target and source-to-solver manifests;
- one external reusable model.

## Required negative matrix

Required failures include:

- floating or reference-less conservative island;
- discipline, dimension, or conservation mismatch;
- under- or over-constrained equation system;
- structural singularity;
- unsupported higher-index DAE;
- unbounded or parameter-dependent invalid structural mode;
- conflicting initial conditions;
- state without legal initialization where required;
- hidden or duplicated state after a transformation;
- event loop or zero-time oscillation;
- illegal discontinuity or unsupported dynamic topology;
- analysis query outside a supported context;
- non-differentiable expression required by an analysis without capability;
- invalid noise dimension or correlation;
- stochastic source without deterministic scope/seed policy;
- model-envelope violation;
- required semantic tolerance unsupported by the adapter;
- solver profile missing a required capability;
- target lowering that loses equation/state/event/noise/source identity;
- unsupported simulator extension leaking into a portable profile.

## Exit criteria for Increment 132

Increment 132 is complete when:

1. ADR 0022 is accepted;
2. this plan and the machine-readable surface are committed;
3. the main roadmap is revision 1.16 and contains Increments 132-142;
4. the architecture index lists ADR 0022;
5. the plan explicitly integrates existing analog/AMS increments without claiming implementation;
6. public syntax remains deferred to Increment 133;
7. no native analog solver is required for the first release;
8. Markdown, JSON, contribution-policy, and Core CI checks pass.

## References

- Accellera Verilog-AMS standards: <https://www.accellera.org/downloads/standards/v-ams>
- Accellera Verilog-AMS 2023 release summary: <https://www.accellera.org/news/press-releases/393-accellera-approves-verilog-ams-2023-standard-for-release>
- OpenVAF: <https://openvaf.semimod.de/>
- OSDI simulator interface: <https://openvaf.semimod.de/docs/details/osdi/>
- MLIR interfaces: <https://mlir.llvm.org/docs/Interfaces/>
- Modelica hybrid DAE representation, reviewed for compiler architecture comparison only: <https://specification.modelica.org/master/modelica-dae-representation.html>
