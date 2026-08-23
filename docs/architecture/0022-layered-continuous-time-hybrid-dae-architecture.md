# ADR 0022: Use layered continuous-time semantic IR, hybrid DAE analysis, and solver capability contracts

- **Status:** Accepted
- **Date:** 2026-08-23
- **Scope:** Analog equations and contributions, continuous-time islands, topology, differential-algebraic systems, continuous and discrete state, initialization, events, discontinuities, analysis projections, noise, environmental context, solver interfaces, model validity, source maps, and AMS verification

## Context

Nodal already has strong plans for physical quantities, natures and disciplines, nodes and branches, analog contributions, continuous-time operators, events, noise, connect rules, mixed-signal bridges, Verilog-A/Verilog-AMS emission, open-source simulation, target-HDL optimization, and AMS-to-FPGA approximation.

Those plans do not yet assign one explicit architecture to the compiler representation between source construction and target HDL. Without that layer, later implementations could accidentally treat Nodal as a Verilog-AMS text generator and allow target syntax or one simulator's callback model to define:

- which values are continuous unknowns, derivatives, algebraic variables, discrete event variables, or parameters;
- how contributions become equations;
- how conservative topology becomes a solvable equation system;
- how state introduced by `idt`, transfer operators, delays, transitions, hysteresis, or events is identified and initialized;
- how continuous integration, root finding, event iteration, digital scheduling, and mixed-signal bridges interact;
- how DC, operating-point, transient, AC, noise, and future analyses project the same source model;
- what model requirements a selected simulator or solver must satisfy;
- how model validity, environmental context, tolerances, and convergence guidance remain reproducible.

A scalable compiler must preserve source semantics first, derive mathematical and solver-facing forms through verified passes, and keep the first Verilog-A/Verilog-AMS backend independent from any future native analog solver.

## Decision

Nodal adopts the binding rule:

> **Preserve source analog intent, partition explicit analog islands, normalize verified topology and hybrid equations, derive analysis-specific solver forms, and retain capability and validity evidence.**

The exact Scala syntax is evaluated by a later public design gate. This ADR freezes the compiler layers, semantic ownership, and evidence boundaries.

## Layered analog representation

Nodal uses distinct, versioned representations rather than one monolithic analog AST.

### Source-semantic analog IR

The source-semantic layer preserves constructs as authored, including:

- analog regions and procedural scopes;
- potential and flow access;
- contributions and explicit equations;
- analog variables and assignments;
- conditionals and bounded loops;
- continuous-time operators;
- event controls, timers, crossings, and named events;
- analysis and environmental queries;
- noise source declarations;
- connect modules and connect rules;
- source locations, hierarchy, dimensions, and parameter formulas.

This layer is suitable for language diagnostics, source maps, exact target reconstruction, and semantic transformations that must still understand the original construct.

### Topology and connectivity graph

The topology layer records:

- disciplines, natures, terminals, nodes, branches, references, and aliases;
- conservative connection sets and potential/flow conservation;
- signal-flow and discrete-real connectivity as separate categories;
- connect-rule insertion and mixed-signal bridge boundaries;
- static or explicitly capability-gated variable topology;
- hierarchy and logical Interface ABI paths.

Topology is not reduced to directional assignment. Electrical, mechanical, thermal, and other conservative disciplines use the same effort/flow architecture without electrical-only assumptions.

### Hybrid equation-system IR

Verified lowering produces an explicit hybrid equation system containing stable identities for:

- continuous unknowns;
- derivatives and continuous state;
- algebraic unknowns;
- discrete event state;
- parameters, constants, environment values, and independent variables;
- residual equations and contribution provenance;
- event indicator/root functions;
- guarded or mode-dependent equation sets;
- initial equations and state reinitialization;
- physical dimensions, nominal scales, tolerances, and source spans.

The canonical mathematical form is solver-neutral. Residual form may be represented conceptually as:

```text
F(x, xdot, y, z, u, p, environment, time) = 0
```

where continuous state, algebraic values, discrete event state, inputs, parameters, and environment remain separately classified.

### Analysis projections

The compiler derives analysis-specific projections from the same verified model:

- initialization and consistent initial-condition problem;
- DC and operating-point residuals;
- transient hybrid DAE;
- AC and small-signal linearization;
- noise sources, transfer paths, and correlation metadata;
- future capability-gated analyses such as harmonic balance or periodic steady state.

Analysis-specific source behavior is explicit and source located. One analysis must not silently redefine another.

### Target and solver representations

Verilog-A, Verilog-AMS, OSDI-like model interfaces, and any future native solver ABI are lowerings from the verified semantic and analysis layers. They do not define Nodal semantics.

Nodal does not require a native analog solver for the first release. External simulators remain versioned adapters with declared capabilities, normalized evidence, and reproducible commands.

## Explicit `AnalogIsland`

An `AnalogIsland` is a compiler-owned connected continuous-time partition. It owns or references:

- conservative topology and reference structure;
- unknowns, equations, contributions, and state;
- event indicators and mode variables;
- analysis requirements;
- dimensions, nominal scales, and tolerances;
- solver capability requirements;
- source and hierarchy provenance;
- bridges to digital domains or other analog regions.

An island boundary is a scheduling and optimization barrier unless a separately verified transform proves a partition, merge, reduction, or approximation valid.

Digital clock/reset domains, directional analog signal-flow regions, discrete-real nets, and conservative analog islands remain distinct graph kinds connected only by explicit bridges.

## Equation identity and contribution semantics

Every equation, residual, contribution, unknown, state object, event indicator, noise source, and bridge receives a stable semantic identity.

A contribution is not merely an assignment. Lowering records:

- contributed potential or flow;
- branch and orientation;
- additive participation;
- guard or mode;
- physical dimension;
- source location and hierarchy;
- initialization and analysis applicability.

Equation reassociation, contribution combination, elimination, and canonicalization require declared rules and preserved provenance. Textual similarity alone is not equivalence.

## DAE and topology verification

The compiler performs staged structural analysis before solver or backend execution:

- connected-component and island construction;
- reference-node and conservation checks;
- equation/unknown inventory;
- incidence and dependency graphs;
- structural matching and block decomposition;
- under-constrained and over-constrained systems;
- structurally singular systems;
- algebraic loops and mixed continuous/discrete loops;
- unsupported or unsafe variable topology;
- initial-condition completeness and conflicts;
- parameter-envelope-dependent structural changes;
- backend and solver capability requirements.

Initial support may classify difficult higher-index or variable-structure systems as unsupported rather than silently altering the model. Index reduction, tearing, homotopy, model reduction, or topology rewriting require separately approved transformations and retained evidence.

## State and initialization

Analog state is explicit in IR even when introduced by a high-level operator.

State categories include:

- user-declared continuous state;
- state introduced by integration or transfer operators;
- delay and waveform-shaping state;
- discrete event state and hysteresis memory;
- bridge/sample-and-hold state;
- backend-required state with explicit provenance.

Each state object records ownership, dimension, initialization policy, analysis applicability, reset/reinitialization behavior, observability, and source origin.

Initialization distinguishes at least:

- fixed initial value;
- initial guess;
- initial equation;
- steady-state condition;
- operating-point-derived initialization;
- uninitialized/solver-selected value;
- explicit event-time reinitialization.

The compiler reports hidden or backend-created state. Optimizations may not duplicate, remove, or move state across island, event, analysis, bridge, or observability boundaries without a verified rule.

## Hybrid scheduling and event iteration

Nodal defines target-neutral observable ordering among:

- initialization;
- continuous integration;
- root/crossing detection;
- scheduled time events;
- analog event statements;
- discrete event-state updates;
- zero-time event iteration to a fixed point;
- digital event and clock-domain activity;
- analog/digital bridge sampling or drive updates;
- finalization.

The exact internal algorithm may vary by simulator, but adapters must satisfy the frozen observable contract or reject the model.

Event iteration has explicit convergence and iteration-limit diagnostics. Zero-time oscillation, conflicting updates, event loops, bridge loops, and variable-topology cycles are not left to simulator-dependent behavior.

Verilog-AMS 2023 dynamic event tolerances, interrupted transitions, named-event clarifications, and connect-module receiver behavior are represented as semantic capabilities rather than raw backend text.

## Discontinuities, smoothness, and tolerances

Nodal distinguishes mathematical behavior from solver guidance.

Semantic constructs include:

- exact discontinuities;
- zero-crossing or threshold events;
- hysteresis;
- transition and slew behavior;
- delay and hold behavior;
- piecewise equations;
- declared smoothness or continuity class where meaningful.

Solver guidance includes:

- absolute and relative tolerances;
- event/time tolerances;
- nominal magnitudes and scaling;
- maximum-step requests;
- convergence or limiting hints;
- preferred but non-semantic analysis options.

A solver hint cannot change the mathematical model. An adapter that cannot honor a required semantic tolerance or event contract rejects the run; optional hints are recorded as best effort.

## Analysis, small-signal, and noise semantics

Analysis context is typed and versioned. Models may declare supported analyses and analysis-dependent behavior explicitly.

AC/small-signal lowering records the operating point, linearization inputs, derivatives, and output mapping. Noise lowering records:

- stable noise-source identity;
- physical PSD dimension;
- white, flicker, or table model;
- hierarchy and source location;
- correlation group or explicit independence;
- applicable analyses and frequency variables.

Noise sources are not merged or renamed merely because expressions appear equal. Random variation and transient noise remain distinct from small-signal noise.

## Derivative, Jacobian, and sparsity contract

The semantic IR exposes differentiability and derivative requirements without requiring one implementation technique.

A solver-facing lowering may use:

- symbolic derivatives;
- automatic differentiation;
- verified analytic derivative implementations;
- capability-gated numerical differentiation.

The model manifest records derivative method, unsupported/non-smooth operations, residual sparsity pattern, state and algebraic variable ordering, and analysis-specific derivative requirements.

Incorrect derivatives are semantic failures. Derivative caching, common-subexpression elimination, and sparse evaluation must preserve source and equation identity.

## Environment, PVT, and stochastic context

Environment is an explicit immutable run context rather than an uncontrolled global namespace. It may include:

- simulation time and frequency;
- temperature and nominal temperature;
- process/corner identity;
- supply or externally declared operating conditions;
- deterministic random seeds;
- global variation and local mismatch contexts;
- selected analysis and sweep coordinates.

Model parameters, environmental values, small-signal noise, and statistical variation remain distinct. Every stochastic result retains normalized seeds, distributions, scopes, and provenance.

## Model validity envelope

A reusable analog model may declare:

- legal parameter ranges and cross-parameter constraints;
- supported terminal voltage/current/power ranges;
- temperature, frequency, time-scale, and analysis limits;
- expected topology and loading assumptions;
- accuracy or approximation class;
- unsupported operating regions;
- warning, error, or verification-only violation policy.

Static violations fail before simulation where possible. Dynamic envelope monitoring is explicit and verification-oriented; it does not silently clamp or modify the model.

The validity envelope is separate from the AMS-to-FPGA validation envelope, although both use compatible evidence and quantity representations.

## Solver and simulator capability contract

Each model and requested analysis produces a machine-readable requirement inventory. A solver or simulator adapter declares capabilities such as:

- supported analyses;
- implicit or explicit equation support;
- DAE/state/event features;
- dynamic topology;
- noise and correlation;
- derivatives and Jacobian callbacks;
- connect rules and mixed-signal bridges;
- tolerance and discontinuity behavior;
- environmental/statistical features;
- parameterized hierarchy and model ABI;
- result and diagnostic fidelity.

Capability negotiation occurs before execution. Unsupported features are errors, not silent approximations.

## Verification and evidence

Required evidence includes:

- deterministic source-semantic and normalized analog IR;
- topology, unknown, equation, state, event, and analysis inventories;
- structural DAE diagnostics and mutation tests;
- initialization and event-ordering fixtures;
- dimension and scaling checks;
- derivative/Jacobian differential checks;
- DC, operating-point, transient, AC, and noise comparisons;
- parameter, environment, PVT, and seed matrices;
- Verilog-A/Verilog-AMS target reparse and differential simulation;
- solver capability and failure classification;
- model-envelope diagnostics;
- cross-tool portability where available;
- large sparse-island performance and deterministic ordering;
- external reusable-model conformance using public contracts only.

Waveform comparison alone is insufficient for arbitrary equation or event transformations.

## Relationship to existing roadmap

This ADR does not replace the existing analog and AMS increments.

- Increments 24-47 implement source analog constructs and safe canonicalization.
- Increments 48-53 validate emitted models and result handling.
- Increments 68-78 implement mixed-signal types, bridges, connect rules, verification, backends, and conformance.
- Increments 128-129 close conservative interface and flattened backend behavior.
- Increments 133-142 implement and qualify the cross-cutting continuous-time architecture defined here.

The exact public API is owned by Increment 133. The first compiler vertical slice may remain intentionally small while preserving the seams required by this ADR.

## Consequences

### Positive

- Nodal can reason about analog models rather than only emit Verilog-AMS text.
- Source semantics, mathematical equations, solver callbacks, and target syntax remain independently versioned.
- Conservative multi-domain models share one topology/equation architecture.
- State, initialization, events, noise, and analysis behavior become reviewable and source mapped.
- External simulators remain replaceable adapters.
- Future native solving, OSDI integration, model reduction, FPGA approximation, and advanced analyses have stable extension seams.
- Analog optimization receives stronger invariants than waveform-only comparison.

### Costs

- The compiler needs multiple analog IR layers and structural analyses.
- Hybrid scheduling and state ownership require careful specification.
- DAE and derivative verification add implementation effort before advanced optimization.
- Cross-analysis and cross-simulator validation is expensive.
- Some valid Verilog-AMS models may initially be classified unsupported until structural and solver capabilities mature.

## Rejected alternatives

### Use emitted Verilog-A/Verilog-AMS as the canonical analog IR

Rejected because source intent, stable identity, dimensions, state, and solver requirements are difficult to reconstruct after rendering.

### Normalize directly to one solver callback ABI

Rejected because it couples Nodal semantics to one simulator architecture and loses exact target-HDL reconstruction.

### Treat all analog behavior as causal signal flow

Rejected because conservative networks are acausal equation systems with potential/flow conservation.

### Leave state and initialization implicit in operators

Rejected because hidden state breaks optimization, reproducibility, periodic analyses, and diagnostics.

### Let the simulator define mixed-signal event ordering

Rejected because observable behavior and portability would vary by adapter.

### Mix solver hints into mathematical equations

Rejected because changing a tolerance or convergence option could silently change model meaning.

### Require a native analog solver before Verilog-A emission

Rejected because the first useful Nodal release can validate through external compilers and simulators while retaining a solver-neutral architecture.

## Follow-up roadmap

The detailed staged plan and machine-readable architecture candidate are:

- `docs/roadmap/continuous-time-ams-v0.1-plan.md`
- `docs/roadmap/continuous-time-ams-v0.1-surface.json`

Increment 132 records this architecture. Increments 133-142 freeze, implement, lower, and qualify it.
