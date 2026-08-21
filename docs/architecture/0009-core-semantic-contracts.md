# ADR 0009: Freeze staged values, safe numerics, typed interfaces, quantities, and effects

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Public API, type system, elaboration, parameterization, connections, analog quantities, memory/external operations, and automatic-pipeline legality

## Context

The clock/reset and automatic-pipeline architectures depend on semantic rules that cannot be reconstructed safely after implementation:

- whether a value exists during Scala elaboration, remains a symbolic HDL parameter, or changes at hardware runtime;
- how widths, signedness, overflow, narrowing, and shifts behave;
- whether aggregate payloads carry direction internally or receive direction only at a port boundary;
- whether a connection may silently resize, drop, or reinterpret fields;
- whether analog values have physical dimensions or are merely untyped reals;
- whether an operation is pure, stateful, memory-affecting, analog, observational, or external;
- whether an operation may move across an automatically inserted pipeline stage.

Leaving these decisions implicit would make native parameterized Verilog-A/Verilog-AMS, automatic pipeline scheduling, external reusable libraries, and early diagnostics unstable. Nodal is being built from scratch and therefore adopts explicit, safe defaults rather than preserving legacy HDL surprises.

## Decision

Nodal uses the rule:

> **Explicit stage, lossless value semantics, exact connection, dimension-safe quantity, declared effect.**

The exact Scala spellings remain subject to compile-only evaluation, but the architecture below is binding for the v0.3 public API gate.

## Value stages

Nodal distinguishes three stages.

### Elaboration values

Ordinary Scala values such as `Int`, `Boolean`, `String`, `Seq`, and user configuration objects exist only while constructing hardware. They may control Scala metaprogramming and source generation, but they do not appear as symbolic HDL values unless explicitly converted into a Nodal declaration.

### Symbolic HDL values

`Param[A]`, constant declarations, symbolic widths, symbolic array lengths, symbolic ranges, and target-generate indices remain representable in target HDL. They survive elaboration, MLIR, optimization, hierarchy, and backend emission.

A symbolic parameter is not a Scala `Int` and cannot silently control a Scala `if`, collection length, pattern match, or ordinary Scala loop.

### Dynamic hardware values

Ports, wires, registers, memory data, protocol payloads, and sampled analog values vary at hardware runtime. They cannot control elaboration-time structure.

### Stage boundaries

The initial public architecture includes:

- ordinary Scala control for elaboration-only structure;
- `Param[A]` and symbolic expression types for target-visible parameters;
- an explicit target `generate(...)` construct for symbolic replication or conditional structure;
- stable diagnostics for symbolic-as-Scala and runtime-as-shape misuse.

A target-visible generate loop and a Scala construction loop are distinct operations with distinct source diagnostics.

## Numeric and width semantics

Ordinary arithmetic is lossless by default within finite-width integer semantics.

- Unsigned addition and subtraction retain the mathematically required carry/borrow width.
- Signed addition/subtraction retain sign and required width.
- Multiplication uses the full combined result width.
- Comparison returns `Bool`.
- Shift width and fill rules are deterministic for constant and symbolic shift amounts.
- Signed and unsigned values do not silently reinterpret one another.
- Assignment never silently narrows, truncates, wraps, saturates, or changes signedness.

Lossy intent is explicit through candidate operations such as:

```scala
value.truncate(width)
value.wrap(width)
value.saturate(width)
value.resizeChecked(width)
value.toSigned
value.toUnsigned
```

The v0.3 gate may refine spellings, but it must retain separate semantics for truncation, modular wrap, saturation, checked resize, extension, and signedness conversion.

Automatic scheduling and retiming preserve the exact typed arithmetic graph. They may not reassociate expressions, change rounding, change overflow behavior, or substitute a different implementation unless the user selects an explicitly equivalent implementation policy.

## Directionless aggregates and protocol types

Reusable payload types are directionless. Direction belongs to a port or connection boundary, not to nested fields.

The intended shape is:

```scala
final case class Pixel(
  red: UInt,
  green: UInt,
  blue: UInt,
) extends Bundle

val input = in(Stream[Pixel])
val output = out(Stream[Pixel])
```

The same payload type can be used internally, in memories, as a parameterized transaction, in `Valid[T]`, in `Stream[T]`, or by an external reusable library.

The initial transport families are:

- plain `T`: fixed-rate value every active cycle;
- `Valid[T]`: payload plus validity, with bubbles and no backpressure;
- `Stream[T]`: ordered ready/valid transport with backpressure.

Protocol conversion is explicit. Nodal never silently converts among plain, valid-only, and elastic transport.

## Exact connections and adapters

Exact connection is the default.

A direct connection requires compatible:

- payload shape and field names;
- widths and signedness;
- protocol and ordering semantics;
- directions and driver ownership;
- clock/reset domain provenance;
- latency contract where the boundary publishes latency.

Direct connection does not silently:

- truncate or extend data;
- drop, rename, reorder, or invent fields;
- reinterpret signedness;
- convert protocol;
- cross domains;
- insert latency.

Intentional structural conversion uses a typed adapter or view contract. The v0.3 gate evaluates concise `via(...)`, `viewAs[...]`, or equivalent spellings while preserving exact-by-default behavior.

## Physical quantities

Analog and mixed-signal values carry physical dimensions in the frontend and IR even when ordinary source syntax remains compact.

Examples include voltage, current, resistance, capacitance, charge, time, frequency, power, and dimensionless values.

Rules include:

- unit suffixes create values with known dimensions;
- nature potential and flow access functions establish dimensions;
- addition/subtraction require compatible dimensions;
- multiplication/division derive dimensions;
- `ddt` divides by time and `idt` multiplies by time;
- comparison requires compatible dimensions;
- symbolic parameters retain dimensions;
- conversion to or from dimensionless numeric values is explicit.

The compiler rejects expressions such as voltage plus current before HDL generation and reports expected and actual dimensions with stable codes.

The dimension system is compiler-facing. Ordinary users are not required to spell long type-level exponent vectors.

## Effects and scheduling barriers

Every operation is classified by effect and scheduling contract.

Initial effect categories include:

- pure combinational;
- sequential state;
- memory access;
- analog contribution/state;
- event generation or observation;
- external operation;
- irreversible or externally visible side effect.

Only pure operations and explicitly movable protocol operations may be automatically scheduled or retimed.

User registers, memories, CDC/RDC structures, analog boundaries, event creation, assertions with observability requirements, external side effects, and explicit anchors are barriers unless a dedicated contract states otherwise.

## Memory contracts

Memory behavior is never inferred from backend defaults. A memory declaration records:

- depth and element type;
- read mode and read latency;
- write behavior and masks;
- read-under-write semantics;
- domain ownership of every port;
- ordering and collision behavior;
- initialization/reset capability where supported.

The candidate public surface includes explicit asynchronous and synchronous read policies, read-under-write values such as read-first/write-first/no-change/undefined, and declared fixed latency.

An automatic pipeline may schedule around a memory only when the memory contract makes latency, ordering, and side effects explicit.

## External operation contracts

Black boxes, external IP, simulator functions, and implementation-selected units declare:

- input/output types and protocols;
- fixed, ranged, or variable latency;
- initiation interval or throughput;
- clock/reset domain requirements;
- purity or statefulness;
- side effects and observability;
- simulation, synthesis, and formal models or explicit absence thereof.

An unknown external operation is a hard scheduling and optimization barrier. Nodal never guesses its latency or purity.

## Parameterized structure

Symbolic widths, counts, and ranges remain one native parameterized module structure whenever the target backend can represent them.

Automatic scheduling over timing-affecting symbolic parameters requires a finite legal envelope and one schedule valid for that envelope. Clone-per-parameter-value specialization is forbidden unless a separate explicit specialization feature is approved later.

## Diagnostics and evidence

The compiler must provide stable source-located diagnostics for:

- symbolic parameter used as a Scala value;
- runtime value used as a type/shape/generate bound;
- implicit narrowing or signedness change;
- incompatible aggregate or protocol connection;
- implicit field loss or protocol conversion;
- physical-dimension mismatch;
- unknown memory behavior;
- unknown external latency/effect;
- effectful operation inside a movable pipeline region;
- parameter envelope insufficient for a stable schedule.

Machine-readable manifests record the frozen value stages, numeric rules, protocols, quantity dimensions, effect categories, and backend capability dependencies.

## Consequences

### Positive

- Native parameterized HDL has a precise staging contract.
- Width and overflow bugs fail early instead of appearing in generated RTL.
- Payloads remain reusable across ports, pipelines, memories, and libraries.
- Connections are reviewable and never silently lossy.
- AMS equations gain dimension checking without verbose user syntax.
- Automatic pipeline scheduling has a sound purity and latency boundary.
- Memory and external IP behavior is portable across backends and tools.

### Costs

- Some familiar HDL shortcuts require explicit conversion.
- Full-width arithmetic may create wider hardware unless the user selects a narrower policy.
- Memory and external blocks require more metadata before advanced scheduling.
- The compiler must preserve stage, width, dimension, protocol, and effect provenance through all IR layers.

## Rejected alternatives

- **Treat every integer-like value as Scala `Int`:** destroys symbolic parameterization.
- **Infer target generate from ordinary Scala loops:** conflates construction and target structure.
- **Copy legacy truncating arithmetic defaults:** permits silent overflow and makes retiming harder to verify.
- **Put directions inside payload fields:** reduces reuse and complicates protocol composition.
- **Allow best-effort bulk connection:** silently loses or resizes information.
- **Represent all analog values as untyped `real`:** misses dimension errors before simulation.
- **Assume memories or external units are pure and one-cycle:** unsafe for ordering, latency, and automatic scheduling.

## Follow-up increments

- Increment 13 compiles and compares the exact candidate forms.
- Increment 14 evaluates automatic-pipeline syntax against these semantic foundations.
- Increment 15 freezes the unified v0.3 core-semantics and automatic-pipeline public contract.
- Later digital, analog, memory, pipeline, backend, and verification increments implement the accepted rules.
