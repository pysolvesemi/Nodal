# Nodal analog events design gate v0.1

**Increment:** 37  
**Status:** Approved  
**Scope:** public-api

## Semantic contract

The reference is the [Accellera Verilog-AMS 2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf), section 5.10. Analog events are nonblocking monitored or analysis-lifecycle events, not Boolean values, digital clock domains, or solver callbacks. The frontend retains an event expression and an explicitly controlled statement region. The native compiler verifies that structure independently before target emission.

`cross` detects either, rising, or falling zero crossings. It does not initialize a value at time zero. `above` detects positive crossings and also participates in initialization and DC analysis; it is not rewritten into `cross`. `timer` retains absolute start time, optional period, tolerance, and enable. A positive period repeats; a nonpositive period is one-shot. Dynamic arguments are preserved rather than replaced with parameter defaults. `initialStep` and `finalStep` retain optional analysis names. Omitted analysis filters mean all applicable analyses, not a fabricated transient-only filter.

Event OR is a typed composition. It preserves operand order and distinct monitor identities and executes one controlled body when any constituent fires. It is not Boolean short-circuit logic. Empty controlled bodies are retained because an event can request a solver evaluation point even without an assignment.

## Arguments and physical dimensions

Monitored values and timing expressions are real. Crossing expression tolerance has the monitored expression's dimension. Time tolerance, timer start, and period have time dimension. Only a proven dimensionless exact zero may fill a time slot without a time unit. A zero voltage is not a zero second. Known tolerances must be finite and nonnegative; zero explicitly requests simulator-selected tolerance. Dynamic tolerances remain dynamic. Integer enables are preserved. Optional arguments keep their omission and actual position rather than acquiring guessed defaults.

## Context and controlled statements

The initial implementation accepts `on(event)` inside the explicit `analogProcedure` region, with component-local ownership. Module-level `on` and legacy `analog { on(...) }` convenience forms are not silently accepted. Runtime-dependent placement of stateful crossing monitors is rejected. Controlled bodies permit ordered assignments and existing supported procedural conditionals, case selection, and bounded loops. Contributions, continuous equations, analog filters, and nested event controls are rejected inside the body. Analog lifecycle events cannot be passed to digital low-level processes. Digital edge candidate handles cannot silently become analog monitored events.

An assignment that runs only when an event occurs does not establish unconditional definite initialization. Event-held state must have a valid initialization contract before it is read. Continuous contributions remain outside controlled bodies. Event-held continuity must be derived from actual writes, never accepted from an unverified metadata claim.

## Identity, validation, and evidence

Retain source locations, owner-qualified identities, event kinds, argument dimensions, analysis filters, composition, controlled statement ordering, and state references across construction, serialization, native verification, and emission. Optimizations may simplify pure arguments but cannot erase monitored events, merge independent state histories, reorder controlled assignments, or hoist event-only work into continuous evaluation.

Acceptance requires positive and negative construction and native tests, actual public-source-to-native witnesses, deterministic serialization and source maps, target output checks, event and state retention before and after optimization, predecessor regressions, exact-head CI, post-merge CI, and separate immutable evidence closure. Structural target checking is not a general Verilog-A parser or numerical simulation evidence.

## Implementation boundary

The initial tranche provides source construction, source-semantic native IR, independent event-argument verification, and procedural dataflow checks. The lowering tranche accepts event-containing procedures only, with static persistent root initializers, ordered captured reads, and checked runtime loop envelopes. Event-held real storage is accepted only after a completed source procedure proves a root-local static initializer and exclusively event-controlled writes; native verification independently repeats that proof. Event updates, `transition`, and continuous contributions are emitted in one ordered analog process. Static monitor loops use `genvar` so each elaborated occurrence has its own history. Ordinary event-free procedures, legacy analysis-restricted writes, and unsupported expressions still fail closed. Complete target qualification and evidence closure remain outstanding work for Increment 37; they are not moved to a later increment by this gate.

## Deferred work

This increment does not implement a numerical event scheduler, analog solver, digital/analog co-simulation adapter, `absdelta`, named-event triggering, stochastic event sources, or full Verilog-AMS digital processes. Simulator execution and analysis-specific numerical accuracy remain separately gated.

## Approval evidence

Approved by the project owner's explicit request to implement Increment 37. Approval authorizes implementation, not a claim of passing validation or completion.
