# ADR 0007: Use implicit clock/reset domains and explicit crossings

- **Status:** Proposed
- **Date:** 2026-08-21
- **Scope:** Public API, elaboration, clock/reset semantics, CDC/RDC, and mixed-signal sampling
- **Decision target:** Increment 12 public API design gate

## Context

The Increment 11 API prototype expresses ordinary synchronous behavior with an event-controlled form such as:

```scala
always(clock.rising):
  code := nextCode
```

That form is close to Verilog-AMS, but it carries event-driven RTL syntax into a software-based construction language. It repeats the clock event at each process, leaves reset policy outside the state declaration, and makes clock-domain intent harder to track through hierarchy and expressions.

Current Chisel modules provide implicit clock and reset values, with `RawModule` and scoped clock/reset overrides for explicit cases. Current SpinalHDL clock domains are lexical contexts captured when registers are created; SpinalHDL also reports unintended clock crossings and provides dedicated crossing structures. Nodal should combine the low ceremony of implicit domains with stronger domain provenance, explicit reset semantics, and mixed-signal boundary checks.

The referenced implementations are design inputs, not compatibility targets. Nodal remains a Scala 3 language with its own target-neutral semantics and Verilog-A/Verilog-AMS backends.

## Decision

### Ordinary synchronous logic uses an implicit current domain

A `ClockDomain` is an elaboration context containing a clock, reset policy, edge, optional frequency metadata, and domain provenance. Applying a domain pushes it onto a lexical context stack. Sequential state and clocked child instances capture the current domain when they are created.

Ordinary synchronous logic uses state primitives such as `Reg` and `RegNext`. Assignment to a register describes its next value at the active edge of the current domain. Hardware conditions provide enable and priority semantics.

The primary synchronous API must not require:

```scala
always(clock.rising):
```

`always` is therefore not recommended for the revised ordinary synthesizable API. Event-controlled `on(event)` remains available for true analog events such as `cross` and `timer`, and for explicitly approved asynchronous behavior that cannot be represented as synchronous state.

### Modules are domain-neutral until they create sequential state

Extending `Module` alone does not force clock or reset ports onto an analog or combinational module.

A reusable clocked child is domain-polymorphic. It inherits the current domain when instantiated, so ordinary child source does not repeat clock/reset constructor arguments or port wiring. A top-level or multi-domain parent explicitly binds external domains. A sequential declaration without a current domain is an error unless emission options provide an explicitly named default domain.

Domain ports become structural HDL ports only when a module contains sequential state or a clocked descendant. Clock identity and frequency do not cause one source module to be cloned per instance. A materially different reset or edge policy may require a deterministic structural variant because it changes emitted sequential semantics.

### Illustrative source shape

The exact spelling is frozen by the Increment 12 design gate, but the intended abstraction is:

```scala
final class Adc extends Module:
  val width = param(12.integer)
  val input = in(Electrical)
  val common = in(Electrical)
  val code = out(UInt(width))
  val fullScale = param(1.0.V)

  val codeReg = Reg(UInt(width), init = zero)
  codeReg := quantize(sample(V(input, common)), fullScale, width)
  code := codeReg
```

The parent supplies the current domain once:

```scala
final class Top extends Module:
  val core = ClockDomain.external(
    name = "core",
    clock = in(Clock),
    reset = in(Reset.activeLow),
    policy = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    frequency = 100.MHz
  )

  core:
    val adc = instance(new Adc)
```

This example is architectural, not yet a frozen API fixture.

## Clock-domain model

Each domain records:

- a distinct `Clock` signal, not a `Bool` cast;
- active edge;
- reset source and reset policy;
- reset polarity at the external boundary;
- optional frequency, phase, and generated-clock metadata;
- parent/source provenance for derived or gated clocks;
- a stable domain identity independent of Scala object identity.

The relation graph initially distinguishes:

- **same/alias:** identical sampling domain and safe for direct synchronous use;
- **derived:** generated from a known parent with recorded ratio, phase, or gating provenance;
- **synchronous:** related clocks whose direct transfer still requires an explicit transfer rule or timing proof;
- **asynchronous or unknown:** crossing structure is mandatory.

Only same/alias domains are directly interchangeable by default. Equal frequency values do not prove that two clocks are related.

User-created clocks from arbitrary Boolean expressions are rejected in the normal API. Generated, divided, muxed, or gated clocks must use dedicated primitives that preserve source-domain and constraint metadata.

## Reset architecture

`Reset` is distinct from `Bool`. External polarity and internal behavior are separate concepts.

The initial policy model must cover:

- no reset;
- synchronous reset;
- asynchronous reset;
- asynchronous assertion with synchronized release.

Asynchronous assertion with synchronized release is the recommended external reset policy for ordinary domains, while the final gate must keep the choice explicit rather than silently changing a module's semantics.

Reset is state-specific:

- a register with an initial/reset value participates in the current domain reset;
- a resetless register is declared deliberately;
- reset always dominates clock enable and ordinary next-state updates;
- soft or functional reset is ordinary synchronous control unless a distinct reset domain is explicitly declared.

Reset synchronization and reset-domain transfer use dedicated `ResetBridge` or reset-controller primitives. The compiler tracks reset provenance and diagnoses unsafe deassertion, unrelated reset use, reset-order dependencies, and state shared across incompatible reset domains.

## Sequential assignment semantics

A register captures its domain at declaration. Its assignments define next-state logic for that domain.

The language must provide deterministic rules for:

- default hold behavior;
- conditional enables;
- priority among nested conditions;
- reset versus enable priority;
- multiple-driver rejection;
- read-before-write and combinational-cycle diagnostics;
- memories and clocked interfaces with the same domain ownership model.

These rules are target-neutral and lower to Verilog-AMS event-controlled blocks only in the backend.

## CDC and RDC policy

Every sequential value carries source-domain provenance through combinational expressions. A destination register may directly consume only values that are safe for its domain relation.

Unsafe crossings are errors before HDL emission and include a source-to-destination path and stable diagnostic code.

Approved crossing primitives must be narrow and semantic:

- single-bit level synchronizer;
- Gray-coded counter synchronizer;
- pulse/toggle synchronizer;
- request/acknowledge handshake bridge;
- asynchronous FIFO or stream bridge for multi-bit payloads;
- reset synchronizer and reset bridge;
- explicitly constrained synchronous-rate transfer where timing evidence exists.

A single-bit synchronizer must reject ordinary multi-bit payloads. Nodal must not automatically insert a synchronizer for an arbitrary crossing, because the correct structure depends on data coherency and protocol semantics.

There is no generic `crossClockDomain` suppression tag in the ordinary API. Exceptional waivers require an identifier, reason, declared relation, and emitted report/constraint evidence.

## Clock enable and clock gating

Ordinary control uses a register enable:

```scala
when(enable):
  state := nextState
```

This keeps reset priority explicit and avoids creating accidental clocks. A physical integrated clock gate is a separate primitive with library mapping, test-enable behavior, stop/restart semantics, and a derived-domain relationship. Direct Boolean clock gating is rejected.

## Mixed-signal boundary

Analog values are continuous and do not belong to a digital clock domain. A digital register may not directly consume an analog potential or flow expression.

Analog-to-digital transfer requires an explicit sampling/threshold/quantization operation under the current destination domain. Digital-to-analog transfer requires an explicit hold, transition, or connect-rule operation carrying source-domain update semantics.

This boundary gives the compiler enough information to validate event feedback, quantization, update timing, and analog/digital scheduling before backend emission.

## Hierarchy and interfaces

Single-domain children inherit their parent's current domain. Multi-domain modules declare named domain boundaries and elaborate each region under the corresponding domain.

Clocked interfaces and streams carry domain metadata. Connecting interfaces in different domains requires a matching bridge rather than an untyped wire connection. Domain metadata must remain available for external reusable libraries through the public core API without exposing frontend internals.

## IR, analysis, and backend consequences

The elaboration model and Nodal MLIR must represent:

- clock/reset domain declarations and references;
- domain ownership of state, memories, and clocked ports;
- domain relations and generated-clock provenance;
- reset policy and reset value;
- CDC/RDC bridge operations and waiver metadata;
- explicit analog/digital sample and drive operations.

CDC/RDC and mixed-domain verification run on target-neutral IR. Verilog-AMS translation then emits explicit clocks, resets, event controls, synchronizers, FIFOs, and reset controllers. The public API remains higher level than the generated HDL.

Optional reports may later generate clock/reset inventories, crossing reports, reset trees, simulator stimulus metadata, and timing-constraint skeletons. Frequency metadata assists reporting and parameter calculations but never substitutes for a domain relation.

## Consequences

### Positive

- Ordinary synchronous source is shorter and does not repeat event syntax.
- Clock and reset ownership is available during elaboration rather than reconstructed from emitted HDL.
- CDC and RDC violations can be diagnosed across hierarchy before backend generation.
- Analog-only and combinational modules remain clockless.
- Reusable modules can be instantiated in different clock identities without source changes.
- Reset policy, enable priority, clock gating, and mixed-signal sampling become explicit architectural concepts.
- The same domain model can drive simulation, formal checks, reports, and future timing constraints.

### Costs

- The Increment 11 synchronous API must be amended by a new versioned design gate before contract fixtures are finalized.
- Domain provenance must be preserved across frontend objects, MLIR, optimization, and backends.
- Multi-domain modules and safe crossing libraries require substantial verification.
- Reset-policy differences may create structural module variants when emitted semantics differ.

## Rejected alternatives

- **Keep `always(clock.rising)` as the normal synchronous API:** too close to backend event syntax and weakens domain abstraction.
- **Automatically add clock/reset ports to every `Module`:** pollutes analog and combinational modules.
- **Treat clocks or resets as ordinary Boolean signals:** loses intent and permits unsafe construction.
- **Infer safety from equal frequency metadata:** equal rates do not establish phase or source relation.
- **Silently insert synchronizers:** unsafe for pulses, multi-bit data, protocols, and reset release.
- **Allow a generic crossing-suppression tag:** hides intent and prevents structural verification.
- **Use clock gating as an ordinary enable mechanism:** creates avoidable clock trees and reset-priority hazards.
- **Force every register onto a global reset:** harms reuse and obscures state-specific reset intent.

## Follow-up increments

- Increment 12 converts this recommendation into an approved public API and semantic design gate.
- Increment 13 freezes positive and negative source contracts for the amended API.
- Increment 14 implements the ambient domain stack and hierarchy inheritance.
- Increment 17 adds target-neutral clock/reset domain constructs to Nodal MLIR.
- Increments 54-56 implement sequential state, domains, CDC/RDC, hierarchy propagation, and parameterized digital structure.
- Increments 58 and 60 implement explicit mixed-signal transfers and cross-domain verification.
- Increment 62 proves the model with ADC and DAC vertical slices.

## References reviewed

- Chisel modules and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/modules>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- Chisel reset types and inference: <https://www.chisel-lang.org/docs/explanations/reset>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
- SpinalHDL PLL/reset-controller example: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Examples/Simple%20ones/pll_resetctrl.html>
