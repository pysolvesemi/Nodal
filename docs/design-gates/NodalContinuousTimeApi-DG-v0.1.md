# Nodal Continuous-Time Semantic API — v0.1

**Status:** Approved

**Scope:** public-api

**Increment:** 133

**Compatibility base:** Nodal public API v0.3

## Purpose

This gate freezes the complete compile-time continuous-time semantic surface
evaluated by Increment 133. It extends the equation/component checkpoint with
explicit state, initialization, events, analysis/environment access, noise,
validity envelopes, and solver guidance while keeping compiler and simulator
behavior inert.

The binding architecture is ADR 0022. Nodal semantics are defined by source
contracts and verified intermediate representations, not by Verilog-A,
Verilog-AMS, or a particular solver callback ABI.

## Frozen public surface

The frozen source file is:

```text
core/scala/api/src/nodal/ContinuousTimeCandidateApi.scala
```

The surface includes:

- `AnalysisKind`, `AnalysisApplicability`, and `ContinuityClass`;
- equation, contribution, balance, noise, and model-validity identities;
- equation, initial-equation, contribution, and procedural regions;
- partial/concrete physical components and local balance;
- named/implicit conservative branches and structural parameters;
- physical dimensions and explicit analog state;
- fixed, guessed, equation-constrained, steady-state, operating-point, and
  solver-selected initialization;
- event-time state reinitialization;
- named events, crossings, value/time tolerances, and discontinuity metadata;
- analysis time/frequency and immutable environment/PVT/sweep/seed access;
- white, flicker, and table noise with stable identity and correlation;
- static/dynamic model-validity constraints and violation policy;
- nominal scales, absolute/relative tolerances, maximum-step, and convergence
  hints.

## State and initialization

`AnalogState` is explicit semantic state.

- Every state has a stable name, physical dimension, owner, and initialization
  policy.
- `read` observes the state and `derivative` denotes its time derivative.
- Fixed values, guesses, initial equations, steady-state conditions,
  operating-point-derived values, and solver-selected values remain distinct.
- `reinitialize` is an event-time state update and cannot be used as an
  unordered equation or ordinary procedural assignment.
- Backend-created hidden state is not authorized by this gate; later lowering
  must report any required state and provenance.

## Events, tolerances, and discontinuities

`crossing` and named events preserve stable identity and source location.

- Event value tolerance and time tolerance remain separate and dimensioned.
- Edge selection is explicit.
- A `DiscontinuityContract` states whether behavior is exact,
  event-guarded, or transitioned and records the continuity class.
- A tolerance or solver hint cannot change the mathematical model.
- Event ordering, zero-time iteration, oscillation diagnostics, and digital
  bridge interaction are deferred to Increment 137.

## Analysis and environment

`AnalysisContext` and `EnvironmentContext` are immutable semantic queries.

- Supported analyses include initialization, DC, operating point, transient,
  AC, and noise.
- Time and frequency are analysis-owned independent values.
- Temperature, nominal temperature, named operating conditions, corner
  selection, sweep coordinates, and deterministic random seeds are explicit.
- Parameters, environment values, small-signal noise, transient randomness,
  global variation, and local mismatch remain separate categories.
- Analysis-dependent behavior must declare applicability and cannot silently
  redefine another analysis.

## Noise

Every noise source has a stable `NoiseId`.

- White, flicker, and table models retain physical spectral-density dimension,
  hierarchy, source span, analysis applicability, and correlation metadata.
- `Independent` and named correlation groups are explicit.
- Small-signal noise is not statistical parameter variation.
- Noise sources are not merged because their expressions are textually equal.

## Model validity

`ModelValidityEnvelope` is part of the reusable model contract.

It may contain:

- parameter ranges and cross-parameter constraints;
- operating, temperature, topology, and loading limits;
- supported analyses;
- accuracy classification;
- error, warning, or verification-only violation policy.

Static violations should fail before execution where possible. Dynamic
monitoring is explicit and must not silently clamp or modify the model.

## Solver hints

`SolverHint` records guidance separately from equations.

- Nominal values and absolute tolerances are dimensioned.
- Relative tolerance is dimensionless.
- Maximum-step and convergence guidance are explicit metadata.
- Hints do not permit mathematical rewrites or altered observable behavior.
- A required semantic capability that a solver cannot honor causes rejection;
  optional guidance may be recorded as best effort.

## Machine-readable contract

The authoritative public surface is
`core/scala/api/public-api-continuous-time-v0.1.json`. Stable diagnostics are in
`core/scala/api/public-api-continuous-time-diagnostics-v0.1.json`. Compile and
semantic fixture inventory is in
`tests/api/fixtures/increment133/manifest.json`.

## Accepted alternatives

Internal representations, solver interfaces, and target syntaxes may vary.
Symbolic differentiation, automatic differentiation, verified analytic
derivatives, or explicitly capability-gated numerical derivatives may be used
later when they preserve the frozen semantic contract and evidence.

## Rejected alternatives

Rejected alternatives include:

- using emitted Verilog-A/Verilog-AMS as canonical semantic IR;
- making solver hints part of the mathematical equation;
- implicit global environment or uncontrolled random seeds;
- anonymous or expression-derived noise identity;
- hidden initialization or state created without provenance;
- silently approximating unsupported analyses, events, topology, or
  correlations;
- conflating signal-flow values with conservative terminals;
- using waveform similarity alone to justify arbitrary equation rewrites.

## Compatibility impact

This is an additive public API v0.1 layered on the frozen v0.3 compatibility
base. No existing digital behavior changes. Compiler, solver, simulator, and
backend behavior remain inert until their owning increments.

## Required tests

- positive internal and independent external modules compile on the pinned
  Scala toolchain;
- type-negative fixtures fail independently at their diagnostic anchors;
- semantic-negative fixtures cover region, identity, dimension, structure,
  initialization, event, noise, validity, and capability contracts;
- the external fixture imports only `nodal.*`;
- repository formatting, package visibility, contribution policy, Core CI,
  native tests, and all inherited workflows remain green;
- the final accepted head contains no writable or one-shot workflow.

## Approval evidence

Approved by the project owner through the standing increment approval and the
explicit instruction on August 29, 2026 to finish and merge Increment 133.

## Deferred implementation

Source-semantic operation recording, AnalogIsland construction, residual/DAE
formation, structural analysis, continuous-state execution, hybrid scheduling,
analysis projection, derivatives, solver ABI, target lowering, and differential
simulation are deferred to Increments 134 through 142 and the existing analog
implementation track.
