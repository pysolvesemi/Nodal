# ADR 0016: Preserve signed numeric intent and separate elaboration, generate, and hardware loops

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Signed digital types, literals, parameters, expressions, ports, memories, Verilog-family lowering, elaboration loops, symbolic generate loops, bounded hardware loops, diagnostics, optimization, simulation, synthesis, and formal readiness

## Context

Nodal's v0.3 roadmap already includes `SInt`, explicit signedness conversion, lossless arithmetic, Scala elaboration values, and symbolic `generate`. That is a sound base, but it does not yet fully freeze:

- exact portable Verilog and future SystemVerilog signed declarations;
- signed parameters, local parameters, literals, arrays, memories, comparisons, shifts, and casts;
- how mixed signed/unsigned expressions are rejected or converted;
- whether a source `for` is elaboration, structural generation, or runtime hardware iteration;
- whether a bounded hardware loop is unrolled or emitted as a procedural HDL loop;
- how loops interact with registers, memories, effects, automatic pipelines, parameters, source maps, and formal verification.

Treating all loops as ordinary Scala control would erase symbolic target generation. Treating all loops as HDL loops would expose backend syntax and make timing unclear. Treating signedness as an emitter detail would allow backend-dependent extension, comparison, shift, and literal behavior.

## Decision

Nodal retains signedness and loop category as target-neutral semantic information until verified lowering.

The binding rule is:

> **Signedness is a type contract; loop kind is a staging contract. Neither is inferred from backend syntax.**

Exact source spellings are compile-tested by Increment 13 and frozen in Increment 15 as part of public API v0.3.

## Signed type model

The initial digital bit-vector families are:

```text
Bits(width)  signless bit container
UInt(width)  unsigned finite-width integer
SInt(width)  two's-complement signed finite-width integer
Bool         one-bit logical value
```

`Bits` supports bitwise manipulation and explicit reinterpretation but does not silently participate in numeric arithmetic. `UInt` and `SInt` preserve signedness through expressions and assignments.

### Signed declarations

Directional source examples:

```scala
val sample = in(SInt(width))
val offset = param((-3).S(width))
val accumulator = Reg(0.S(accWidth))
val memory = Mem(depth = entries, element = SInt(width), ...)
```

The literal spelling is a candidate. Increment 13 compares concise `(-3).S(8)`-style syntax with an explicit `SInt.literal(-3, width = 8)` fallback. Unsized negative Scala integers never become backend-dependent HDL literals silently.

### Numeric conversion versus reinterpretation

Nodal distinguishes value conversion from bit reinterpretation.

Directional candidates:

```scala
unsigned.toSInt          // numeric conversion preserving non-negative value
signed.toUIntChecked     // numeric conversion requiring a legal non-negative range
bits.asSInt              // reinterpret existing bits as two's-complement
bits.asUInt              // reinterpret existing bits as unsigned
signed.asBits            // expose encoded bits
```

The final names may change at the v0.3 gate, but the semantic distinction is mandatory. A numeric unsigned-to-signed conversion may require an added sign bit; a bit reinterpretation keeps width and bit pattern exactly.

### Mixed signedness

Mixed `UInt`/`SInt` arithmetic and ordered comparison are rejected unless the user applies an explicit conversion or an operation whose promotion rule is frozen and lossless.

Nodal does not inherit Verilog's expression-sizing and signedness corner cases. The frontend computes a target-neutral result type first, and the backend inserts declarations, extensions, or casts only to preserve that result.

### Width and operation rules

Increment 15 freezes exact width/sign rules for:

- addition, subtraction, negation, and multiplication;
- division and remainder;
- minimum/maximum and comparisons;
- arithmetic and logical shifts;
- concatenation and extraction;
- conditional expressions;
- reductions;
- parameters and constant folding;
- memory data and aggregate fields.

An arithmetic right shift of `SInt` is semantically distinct from a logical right shift. Narrowing, saturation, wrap, truncation, rounding, and signedness conversion remain explicit.

## Portable Verilog lowering

The portable digital profile emits signed declarations directly where the language/profile supports them:

```verilog
input  signed [WIDTH-1:0] sample;
output signed [WIDTH:0]   result;
reg    signed [ACC_W-1:0] accumulator;
wire   signed [SUM_W-1:0] sum;

parameter signed [WIDTH-1:0] OFFSET = -3;
localparam signed [WIDTH:0] LIMIT = 17;
```

Rules:

- arbitrary-width signed values use signed vectors rather than Verilog `integer`;
- `integer` is used only when the Nodal type/profile explicitly requests its fixed target meaning, such as a generated procedural-loop index;
- signed parameters and localparams retain declared width and sign;
- negative and based literals are emitted with explicit size/sign where needed;
- arithmetic shifts emit the correct operator and typed operands;
- `$signed`/`$unsigned` may be emitted only as a lowering device derived from explicit Nodal conversion/reinterpretation semantics;
- memories/arrays retain signed element metadata even if a tool requires a compatibility lowering;
- aggregate flattening retains each field's sign;
- symbolic signed widths and parameters remain symbolic;
- tool-specific workarounds are profile-gated and reported.

## Future SystemVerilog lowering

A future SystemVerilog profile emits equivalent signed semantics using types such as:

```systemverilog
input  logic signed [WIDTH-1:0] sample;
output logic signed [WIDTH:0]   result;
logic signed [ACC_W-1:0] accumulator;
parameter logic signed [WIDTH-1:0] OFFSET = -3;
```

Packed enum, struct, array, memory, parameter, function, and loop-variable signedness must preserve the same Nodal type contract and numeric results as the portable Verilog profile. `int` or `integer` is not substituted for arbitrary-width `SInt`.

## Verilog-A and Verilog-AMS lowering

Signed digital vectors in Verilog-AMS retain the same vector semantics as the digital profile. Analog `integer` and `real` remain distinct language categories and are not substitutes for finite-width `SInt`.

A backend/profile that cannot represent a signed construct faithfully must lower through an explicitly verified vector/cast form or reject it with a source-located capability diagnostic.

## Three loop categories

Nodal distinguishes three loop kinds.

### 1. Scala elaboration loop

```scala
val copies: Int = 4

for index <- 0 until copies do
  val lane = instance(new Lane)
```

This is ordinary Scala execution. It runs during construction and creates four separate Nodal objects. It does not survive in IR as a target loop and does not emit HDL loop syntax.

Only elaboration values may control this loop. A symbolic `Param` or dynamic hardware value cannot be consumed as a Scala collection bound.

### 2. Symbolic structural generate loop

```scala
val lanes = param(default = 4.integer, range = 1 to 16)

generate(0 until lanes) { index =>
  val lane = instance(new Lane(width))
  connect(lane.input, input(index))
}
```

This is target-visible structural repetition. Bounds and index are symbolic compile-time/generate values. The body may create declarations, instances, connections, and nested legal generate constructs.

The portable Verilog/SystemVerilog/Verilog-AMS digital profile emits deterministic `genvar`/`generate for` syntax when supported. A profile may deterministically unroll a finite concrete case, but it cannot erase symbolic parameterization silently.

Generated hierarchy names derive from source identity and the symbolic index, not JVM traversal order.

### 3. Bounded hardware iteration loop

The preferred candidate is a short explicit operation such as:

```scala
loop(0 until taps) { index =>
  next(index) := data(index) + coefficient(index)
}
```

This loop describes repeated operations inside one combinational or clocked hardware region. The compiler may unroll it or emit a procedural HDL `for` according to a deterministic target profile, but both forms must be semantically equivalent and reported.

Requirements:

- bounds are elaboration constants or legal symbolic constants/parameters;
- the iteration count is finite for every legal parameter value;
- a runtime signal cannot define the initial v0.3 loop trip count;
- loop-carried combinational dependencies are checked for cycles and ordering;
- register assignments in a clocked region still describe updates on one active edge, not one cycle per iteration;
- module/port/instance creation is structural and therefore belongs in `generate`, not a hardware iteration loop;
- `break`, `continue`, data-dependent termination, and unbounded `while` are outside the initial synthesizable contract;
- memory accesses, side effects, external operations, analog contributions, and protocol actions retain ordering/effect rules;
- source locations and iteration/index identity survive unrolling or procedural lowering.

A multi-cycle iterative algorithm is modeled explicitly with an FSM/statechart, stream protocol, memory operation, or separately contracted iterative operator. Nodal never silently turns a bounded procedural loop into a variable-latency multi-cycle engine.

## Higher-level collection operations

For vectors and aggregates, Nodal may provide typed `map`, `zip`, `reduce`, `fold`, and `scan` candidates that lower through the same loop IR. They do not define a fourth loop category.

The compiler preserves reduction order unless an explicit associative/reassociation contract permits change. This is especially important for finite-width signed arithmetic and floating/real operations.

## Loop IR and verification

The authoritative IR distinguishes:

- elaborated repeated objects, which no longer carry a loop operation;
- structural generate regions with symbolic bounds/index;
- bounded hardware iteration regions with ordered effects and a typed induction variable.

Verification covers:

- legal stage and bound category;
- finite parameter envelope;
- index width/sign and range;
- illegal dynamic trip count;
- structural declarations inside procedural loops;
- loop-carried combinational cycles;
- multiple drivers and conflicting updates;
- memory collision and ordering;
- side effects and external latency;
- signed induction/comparison behavior;
- backend/profile support;
- source-map preservation;
- deterministic unroll/procedural choice.

## Optimization, pipelines, and formal readiness

Optimizations may unroll, reroll, fuse, split, or canonicalize loops only under a declared pass effect with preserved signedness, operation order, parameters, hierarchy, effects, domains, protocols, latency, source maps, and proof obligations.

Automatic pipelines may schedule pure operations derived from a bounded loop only after the loop is normalized into a finite transaction graph. It cannot change iteration order, recurrence, reduction semantics, or resource sharing implicitly.

Compiler-generated checks may prove:

- unrolled versus procedural-loop equivalence;
- signed width/extension/cast equivalence;
- index bounds;
- no out-of-range array access;
- legal parameter envelopes;
- no unintended combinational recurrence;
- preserved latency and protocol behavior.

## Consequences

### Positive

- Signed arithmetic has one Nodal meaning across Verilog-family backends.
- Portable Verilog and future SystemVerilog preserve identical widths and values.
- Users can distinguish construction replication, target structural generation, and hardware iteration from source syntax.
- Symbolic generate loops retain native HDL parameterization.
- Bounded procedural loops remain readable while the compiler can choose a portable implementation.
- Dynamic or multi-cycle iteration cannot acquire hidden latency.
- Loop and signedness metadata support optimization, source maps, simulation, synthesis, and formal checks.

### Costs

- Scala/frontend macros must prevent confusing `Param` bounds with ordinary Scala ranges.
- Procedural-loop lowering needs profile-specific compatibility testing.
- Signed Verilog expression rules require deliberate casts/extensions to preserve Nodal semantics.
- Loop normalization, memory dependence, and recurrence analysis add compiler complexity.

## Rejected alternatives

- **Let Verilog infer signedness:** backend sizing and mixed-sign rules would define Nodal behavior.
- **Use `integer` for every signed value:** loses arbitrary width and changes synthesis/storage semantics.
- **Treat `Bits` as signed when convenient:** removes type safety.
- **Use Scala `for` for symbolic generate:** symbolic parameters would be erased during construction.
- **Use one `for` API for structural and procedural loops:** declaration legality and timing become ambiguous.
- **Allow runtime-bounded loops and infer an FSM automatically:** introduces hidden latency and HLS behavior.
- **Always unroll:** can explode output and erase useful symbolic target loops.
- **Always emit procedural loops:** some structures require generate hierarchy and some target profiles cannot represent the body safely.

## Follow-up increments

- Increment 13 compiles signed declarations/literals/conversions and all three loop categories.
- Increment 15 freezes signed and loop semantics in public API v0.3.
- Increment 19 adds signed types, structural generate, and bounded iteration operations to target-neutral IR.
- Increment 22 adds signed/loop diagnostics.
- Increments 54 and 55 implement signed types, expressions, literals, declarations, collection operations, and bounded hardware iteration.
- Increment 58 implements symbolic structural generate and hierarchy naming.
- Increment 65 emits signed portable Verilog plus generate/procedural loops.
- Increment 66 adds signed and loop simulation/lint fixtures.
- Increment 67 adds equivalence/formal readiness for signed lowering and loop normalization.
- Increment 72 emits signed and loop constructs in Verilog-AMS.
- Increment 85 constrains signed/loop optimization passes.
- Increment 99 evaluates native SystemVerilog signed declarations and loop lowering.
