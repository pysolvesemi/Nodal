# Clock/reset public API v0.2 freeze plan

**Status:** Normative roadmap target  
**Architecture:** [ADR 0007](../architecture/0007-implicit-clock-reset-domains.md)  
**Formal freeze:** Increment 12 design gate  

## Goal

Freeze a compact, domain-aware synchronous API before frontend implementation. Ordinary Nodal source must describe state and intent rather than repeat generated Verilog-AMS event-process syntax.

The architectural rule is:

> **Implicit local domain, explicit crossing, explicit emitted HDL.**

The exact API below is the mandatory Increment 12 freeze candidate. Increment 12 may change a spelling only when compile prototypes demonstrate a Scala ambiguity or a semantic hole, and any change must be recorded in the v0.2 design gate and migration note.

## Frozen candidate surface

### Core clock/reset types

```scala
Clock
Reset
ClockDomain
ClockEdge
ClockRelation
ResetPolicy
ResetPolarity
```

`Clock` and `Reset` are distinct from `Bool`.

Initial values:

```scala
ClockEdge.Rising
ClockEdge.Falling

ResetPolarity.ActiveHigh
ResetPolarity.ActiveLow

ResetPolicy.None
ResetPolicy.Sync
ResetPolicy.Async
ResetPolicy.AsyncAssertSyncRelease(stages = 2)
```

The reset signal polarity is explicit at a boundary. The reset policy defines how state responds and how release is handled.

### Domain construction and application

```scala
ClockDomain.external(...)
ClockDomain.from(...)
ClockDomain.required()
ClockDomain.generated(...)
```

A domain is applied lexically with Scala 3 indentation syntax:

```scala
core:
  val state = Reg(0.U(8))
```

A single-domain child instantiated inside a domain inherits that domain automatically.

An explicit single-domain override uses:

```scala
instance(new Child).domain(core)
```

A reusable multi-domain module declares named requirements:

```scala
final class AsyncBridge extends Module:
  val writeDomain = ClockDomain.required()
  val readDomain = ClockDomain.required()

  writeDomain:
    // write-side state

  readDomain:
    // read-side state
```

The parent binds those requirements with typed selectors:

```scala
val bridge =
  instance(new AsyncBridge)
    .domain(_.writeDomain, bus)
    .domain(_.readDomain, pixel)
```

String-keyed domain names are not part of the public API.

### Root-domain convenience

The recommended root shape is:

```scala
final class Top extends Module:
  val core = ClockDomain.external(
    name = "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )

  core:
    val design = instance(new Design)
```

`ClockDomain.external` creates deterministic external clock/reset ports only when the domain is used. Analog-only and combinational modules receive no unused clock/reset ports.

`ClockDomain.from` binds existing typed signals:

```scala
val core = ClockDomain.from(
  clock = clockPin,
  reset = resetPin,
  edge = ClockEdge.Rising,
  policy = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
  polarity = ResetPolarity.ActiveLow,
  frequency = 100.MHz
)
```

A root with an unresolved sequential-domain requirement is an error. Nodal must not silently choose reset style or polarity.

### Generated and related domains

Generated clocks use an explicit parent relation:

```scala
val pixel = ClockDomain.generated(
  name = "pixel",
  clock = pllClock,
  from = core,
  relation = ClockRelation.Ratio(multiply = 3, divide = 2),
  reset = Rdc.sync(core.reset, stages = 2)
)
```

Initial relationship values:

```scala
ClockRelation.Same
ClockRelation.Ratio(multiply, divide, phase = 0.deg)
ClockRelation.Synchronous(phaseKnown = false)
ClockRelation.MutuallyExclusive
ClockRelation.Asynchronous
ClockRelation.Unknown
```

Only `Same` is directly interchangeable by default. A known relationship may permit a dedicated transfer primitive or timing proof, but equal frequency alone never proves safety.

Manual relationship declarations require source-located evidence and appear in reports. There is no unreported `setSynchronousWith`-style suppression.

### Sequential state

Ordinary state uses:

```scala
Reg(init)
Reg.uninitialized(kind)
RegNext(next, init)
RegNext.uninitialized(next)
when(condition)
elsewhen(condition)
otherwise
```

Recommended source:

```scala
final class Counter extends Module:
  val enable = in(Bool)
  val value = out(UInt(8))

  val count = Reg(0.U(8))

  when(enable):
    count := count + 1.U

  value := count
```

Rules to freeze:

- a register captures the current domain when declared;
- `Reg(init)` is resettable and infers its type from `init`;
- uninitialized/resetless state is explicit;
- assignment defines next-state logic at the captured domain edge;
- no assignment means hold;
- `when`/`elsewhen`/`otherwise` define lexical priority;
- reset dominates enable and normal next-state updates;
- unrelated multiple drivers are errors;
- clock enable is represented by conditional state update, not by Boolean clock creation.

`always(clock.rising)` is removed from the ordinary synchronous public subset. The migration diagnostic must point users to `Reg` and `when`.

### CDC primitives

Crossings use a semantic namespace:

```scala
Cdc.sync(bit, to = destination, stages = 2)
Cdc.gray(grayValue, to = destination, stages = 2)
Cdc.pulse(pulse, to = destination)
Cdc.handshake(payload, to = destination)
Cdc.fifo(stream, to = destination, depth = 4)
```

Rules:

- `Cdc.sync` accepts only a one-bit level;
- `Cdc.gray` accepts only a value carrying a Gray-code proof/type;
- pulses use pulse/toggle semantics, not a level synchronizer;
- coherent multi-bit payloads use handshake or FIFO semantics;
- a stream crossing lowers to an asynchronous FIFO or approved equivalent;
- the source domain is inferred from value provenance and the destination is explicit;
- the primitive carries implementation, simulation, formal, and constraint intent.

Exceptional use requires:

```scala
Cdc.waive(
  value,
  to = destination,
  waiver = CdcWaiver(
    id = "CDC-001",
    reason = "...",
    relation = ClockRelation.Synchronous(phaseKnown = true)
  )
)
```

A waiver remains visible in downstream provenance and reconvergence analysis. It is never a general crossing-disable tag.

### RDC and reset controllers

Reset transfer uses a distinct namespace:

```scala
Rdc.sync(reset, to = destination, stages = 2)
ResetController.combine(...)
```

The compiler tracks assertion source, polarity, policy, release synchronization, and destination domains. It diagnoses unsynchronized deassertion, reset reconvergence, mixed reset trees, partial-reset dependencies, and state observed while a related domain remains in reset.

### Clock gates and muxes

Physical clock structure is explicit:

```scala
ClockGate(domain, enable, testEnable = false.B, name = "gated")
ClockMux.glitchless(select, domains = Seq(a, b), name = "selected")
```

These return derived `ClockDomain` values with mapping and timing metadata. Arbitrary Boolean-to-clock conversion and combinational clock generation are errors.

### Analog and mixed-signal events

These remain valid:

```scala
analog:
  ...

on(cross(...)):
  ...

on(timer(...)):
  ...
```

Analog/event behavior is separate from synchronous register construction. Analog-to-digital sampling must occur under an explicit destination domain; digital-to-analog drive operations retain their source domain.

### Low-level escape

True event-driven behavior that cannot be represented as domain-owned state is isolated under:

```scala
nodal.lowlevel.process(event):
  ...
```

This escape is not part of the ordinary reusable-library subset, cannot create untracked state, and cannot bypass CDC/RDC or mixed-domain verification.

## Incremental delivery plan

### Increment 12 — Public API v0.2 freeze and contract fixtures

- [ ] Add compile-only candidates for the exact surface above.
- [ ] Compare Scala ambiguities and minimize the API without weakening semantics.
- [ ] Publish `NodalClockResetApi-DG-v0.2.md` and a v0.1-to-v0.2 migration note.
- [ ] Update the machine-readable public API manifest.
- [ ] Add positive fixtures for implicit single-domain state, explicit root binding, existing-signal binding, generated domains, multi-domain requirements, every reset policy, every CDC/RDC category, gates/muxes, and analog-event separation.
- [ ] Add negative fixtures for missing domain, direct asynchronous sampling, multi-bit `Cdc.sync`, unsafe pulse transfer, unreported relationship assumptions, reset-release errors, Boolean clock creation, normal use of `always`, and low-level-process misuse.
- [ ] Freeze stable diagnostic codes and source locations.
- [ ] Keep frontend behavior inert; this increment freezes contracts rather than implementing hardware semantics.

### Increment 16 — Elaboration and lexical domain context

- [ ] Implement deterministic domain-stack push/pop and state capture.
- [ ] Implement default-domain requirements and single-domain child inheritance.
- [ ] Implement named multi-domain requirements and typed instance binding.
- [ ] Reject reliance on public Scala `implicit`/`given`, thread-local, mutable-global, or JVM-identity semantics.

### Increment 17 — Source locations and deterministic domain naming

- [ ] Capture locations for domains, state, crossings, waivers, gates, and muxes.
- [ ] Define stable external port, generated domain, synchronizer, FIFO, and reset-controller naming.

### Increment 19 — Target-neutral domain IR

- [ ] Add domain declarations/references, relationships, reset policy, state ownership, and timing provenance.
- [ ] Add CDC/RDC and waiver operations.
- [ ] Preserve the model when reusing CIRCT `hw`, `seq`, and `sv` constructs.

### Increment 22 — Cross-layer diagnostics

- [ ] Map missing-domain, CDC, RDC, relation, gating, mux, and waiver diagnostics back to Scala sources.

### Increment 56 — Register and next-state semantics

- [ ] Implement `Reg`, `RegNext`, resetless state, conditional update, enable, priority, and multiple-driver rules.
- [ ] Lower high-level state to CIRCT sequential constructs without exposing backend process syntax.

### Increment 57 — Domain, CDC/RDC, gate, mux, and escape implementation

- [ ] Implement domain creation/application, reset policies, relationship graph, crossing structures, reset controllers, gates/muxes, and low-level escape restrictions.

### Increment 58 — Domain-aware hierarchy

- [ ] Infer structural clock/reset ports.
- [ ] Propagate default and named domains through parameterized hierarchy.
- [ ] Generate deterministic structural variants only when edge/reset semantics materially differ.

### Increments 69, 71, and 72 — Mixed-signal verification and backend

- [ ] Implement explicit sampling/drive provenance.
- [ ] Run hierarchy-wide CDC/RDC and mixed-domain verification.
- [ ] Emit explicit Verilog-AMS ports, event processes, reset logic, synchronizers, asynchronous FIFOs, gates, muxes, and metadata.

### Increment 73 — ADC/DAC proof

- [ ] Demonstrate an ADC and DAC using implicit domains, reset policies, explicit mixed-signal sampling, legal CDC, reports, and deterministic parameterized Verilog-AMS.

## Freeze exit criteria

Increment 12 may be checked only when:

1. ordinary single-domain examples contain no explicit clock event or `always` block;
2. analog/combinational modules receive no unused clock/reset ports;
3. single-domain children inherit correctly and multi-domain children require typed bindings;
4. reset policy and polarity are explicit and independently represented;
5. direct unsafe CDC/RDC examples fail before HDL emission;
6. each semantic crossing category has a positive structural fixture;
7. the external-library fixture uses only the approved public subset;
8. v0.1 migration behavior and diagnostics are documented;
9. the exact API manifest, positive/negative fixtures, and CI are green.

## References

- Chisel modules and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/modules>
- Chisel sequential circuits: <https://www.chisel-lang.org/docs/explanations/sequential-circuits>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- Chisel reset semantics: <https://www.chisel-lang.org/docs/explanations/reset>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
