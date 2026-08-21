# ADR 0007: Use implicit local clock/reset domains and explicit crossings

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Sequential public API, hierarchy, clock/reset semantics, CDC/RDC, and mixed-signal sampling

## Context

The Increment 11 prototype expresses ordinary synchronous behavior with an event-controlled form such as:

```scala
always(clock.rising):
  code := nextCode
```

That form is close to emitted Verilog-AMS but is too low-level for a software-based hardware-construction language. It repeats a clock event at each process, separates reset policy from state construction, and makes clock-domain intent harder to preserve through hierarchy and expressions.

Current Chisel modules provide implicit clock and reset values, with explicit scoped overrides for alternate domains. Current SpinalHDL clock domains are lexical contexts captured when registers are created, and SpinalHDL diagnoses unintended clock crossings. Nodal adopts the useful high-level ideas while defining a stricter target-neutral domain model suitable for analog, digital, and mixed-signal compilation.

## Decision

Nodal uses the rule:

> **Implicit local domain, explicit crossing, explicit emitted HDL.**

Ordinary synchronous state is described with high-level constructs such as `Reg`, `RegNext`, `when`, `elsewhen`, and `otherwise`. These constructs capture the current `ClockDomain`; users do not write `always(clock.rising)` for normal state.

The backend still emits explicit Verilog-AMS clock/reset ports and event-controlled processes. The source API therefore remains higher-level than the generated HDL without hiding hardware structure.

### Domain-neutral modules

`Module` remains valid for analog, combinational, and clocked designs. Extending `Module` alone does not add clock/reset ports.

A module becomes sequential when it creates state. A single-domain reusable module has an implicit default-domain requirement and inherits the current domain at instantiation. A top-level unresolved requirement must be explicitly bound; Nodal does not silently choose reset polarity or reset style.

A multi-domain reusable module declares named domain requirements. Parent instances bind those requirements with typed selectors.

### Lexical domain context

Applying a domain creates a lexical elaboration scope. Registers, memories, assertions, samplers, and clocked children capture the current domain when created.

The implementation uses a compiler-managed elaboration context, not public Scala `implicit`/`given` values, thread-local state, or mutable global state. This avoids Scala initialization-order leakage and keeps deterministic parallel elaboration possible.

### Clock and reset are distinct types

`Clock` and `Reset` are not aliases of `Bool`. Arbitrary Boolean-to-clock or Boolean-to-reset conversion is rejected in the ordinary API.

A `ClockDomain` records:

- clock source and active edge;
- reset source, polarity, and policy;
- optional frequency, phase, and generated-clock metadata;
- parent/source provenance for generated, gated, or muxed clocks;
- a stable domain identity independent of JVM object identity.

Clock relationships and reset relationships are tracked separately. Equal frequency values do not prove that two domains are synchronous.

### Reset policy

The initial public policy set covers:

- no reset;
- synchronous reset;
- asynchronous reset;
- asynchronous assertion with synchronized release.

Resettable state declares a reset value. Resetless or intentionally uninitialized state is explicit. Reset dominates clock enable and ordinary next-state updates.

Asynchronous reset release is synchronized per destination domain. Multiple reset sources use explicit reset-controller/bridge primitives so assertion, release, source, and destination metadata remain available for RDC analysis.

### State update semantics

A register captures its domain at declaration. Assignment describes its next value at that domain's active edge.

The language defines deterministic rules for:

- implicit hold when no update condition is active;
- enable and conditional-update priority;
- reset versus enable priority;
- multiple-driver rejection;
- read-before-write and combinational-cycle diagnostics;
- domain ownership of memories and clocked interfaces.

These rules are represented in target-neutral IR and lower to backend event processes later.

### CDC and RDC

Every state-derived value retains source-domain provenance through combinational expressions, ports, hierarchy, interfaces, and mixed-signal boundaries.

A value may enter state in another domain only when the relationship proves it safe or an approved semantic crossing primitive is used. The initial crossing categories are:

- single-bit level synchronization;
- Gray-coded synchronization;
- pulse/toggle transfer;
- request/acknowledge handshake transfer;
- asynchronous FIFO/stream transfer;
- reset synchronization/bridging;
- a source-located, reported exceptional waiver.

Nodal does not silently insert synchronizers. A generic multi-bit value cannot use a single-bit synchronizer. A waiver never erases provenance or disables downstream reconvergence analysis.

### Clock enable, gating, and muxing

Ordinary conditional state updates use enables rather than user-created clocks. Physical clock gating and glitchless clock selection use explicit primitives carrying generated-clock, test-enable, technology-mapping, and timing-constraint metadata.

A gated or muxed clock creates a derived domain and retains its parent relationships. Arbitrary combinational clock generation is an error.

### Analog and mixed-signal boundaries

`analog` regions and `on(cross(...))` retain genuine analog/event semantics. They do not justify exposing Verilog-style `always` as the normal synchronous API.

Analog-to-digital observation requires an explicit sampler, threshold/comparator, or ADC boundary under a destination domain. Digital-to-analog updates retain their source domain and transition policy. The compiler tracks analog-event and digital-domain provenance separately.

### Low-level escape

A clearly separated low-level process API may be provided for true event-driven behavior that cannot be represented by the domain-aware state model. It is not the ordinary register API, is excluded from the initial reusable-library subset unless separately approved, and must not bypass CDC/RDC or mixed-domain verification.

## Public API freeze target

Increment 12 must evaluate and freeze the exact user-facing surface around these names and roles:

- `Clock`, `Reset`, `ClockDomain`, `ClockEdge`, `ClockRelation`, and `ResetPolicy`;
- `ClockDomain.external`, `ClockDomain.from`, `ClockDomain.required`, and `ClockDomain.generated`;
- lexical application with `domain: ...`;
- `Reg`, `RegNext`, `when`, `elsewhen`, and `otherwise`;
- typed instance-domain binding with `.domain(...)`;
- `Cdc.sync`, `Cdc.gray`, `Cdc.pulse`, `Cdc.handshake`, `Cdc.fifo`, and `Cdc.waive`;
- reset bridge/synchronizer operations under a distinct RDC/reset namespace;
- explicit `ClockGate` and glitchless `ClockMux` primitives;
- an isolated `lowlevel.process(event)` escape rather than ordinary `always`.

Exact signatures and diagnostic contracts are frozen only after compile-positive and compile-negative prototypes in Increment 12. The architectural decisions in this ADR are binding unless superseded by a later ADR and versioned public API gate.

## IR and tooling consequences

The elaboration model and Nodal MLIR must preserve:

- domain requirements, declarations, references, and bindings;
- domain ownership of state and clocked ports;
- clock/reset relationships and generated-clock provenance;
- reset policy, reset value, and release behavior;
- CDC/RDC operations and waiver metadata;
- analog/digital sampling and drive operations.

This model supports early diagnostics, clock/reset inventories, crossing and waiver reports, simulator stimulus, formal assumptions, generated-clock metadata, timing-constraint intent, and deterministic backend emission.

## Consequences

### Positive

- Ordinary synchronous source is shorter and more scalable.
- Analog-only and combinational modules remain clockless.
- Clock/reset ownership is known during elaboration instead of reconstructed from HDL.
- CDC and RDC bugs can be diagnosed across hierarchy before emission.
- Reusable modules can be instantiated under different clock identities without source changes.
- Reset policy, enable priority, gating, and mixed-signal sampling become explicit architectural concepts.

### Costs

- The Increment 11 ordinary synchronous/event API must be amended through public API v0.2 before contract fixtures are finalized.
- Domain provenance must survive every frontend, MLIR, optimization, and backend layer.
- Multi-domain hierarchy, CDC/RDC structures, and reset release require substantial verification.
- Materially different edge/reset semantics may require deterministic structural module variants.

## Rejected alternatives

- **Keep `always(clock.rising)` as the ordinary API:** too close to backend event syntax and weakens domain abstraction.
- **Give every `Module` clock/reset ports:** pollutes analog and combinational modules.
- **Treat clock/reset as `Bool`:** loses intent and allows unsafe construction.
- **Infer safety from equal frequency:** rate equality does not establish phase or source relation.
- **Silently insert synchronizers:** unsafe for pulses, coherent buses, protocols, and reset release.
- **Provide an unrestricted crossing-suppression tag:** hides intent and prevents structural analysis.
- **Use clock gating as an ordinary enable:** creates avoidable clocks and reset-priority hazards.
- **Force all state onto one reset policy:** harms reuse and obscures state intent.

## Follow-up increments

- Increment 12 freezes public API v0.2, migration, contracts, and stable diagnostics.
- Increment 15 implements elaboration, hierarchy, and lexical domain context.
- Increment 18 adds target-neutral domain and crossing constructs to Nodal MLIR.
- Increments 55-57 implement state, CDC/RDC, and domain-aware hierarchy.
- Increments 65, 67, and 68 implement mixed-signal transfer, verification, and Verilog-AMS lowering.
- Increment 69 proves the architecture with ADC and DAC vertical slices.

## References reviewed

- Chisel modules and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/modules>
- Chisel sequential circuits: <https://www.chisel-lang.org/docs/explanations/sequential-circuits>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- Chisel reset semantics: <https://www.chisel-lang.org/docs/explanations/reset>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
