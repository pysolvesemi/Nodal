# Signed numeric and staged-loop public API v0.3 plan

**Status:** Normative roadmap candidate
**Architecture:** [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md)
**Unified formal freeze:** Increment 15 design gate
**Machine-readable candidate:** [`signed-loop-api-v0.3-surface.json`](signed-loop-api-v0.3-surface.json)

## Goal

Freeze signed finite-width semantics and three distinct loop categories before compiler implementation:

1. Scala elaboration loops;
2. symbolic structural generate loops;
3. bounded hardware iteration loops.

The binding rule is:

> **Signedness is a type contract; loop kind is a staging contract. Neither is inferred from backend syntax.**

## Signed public types

Initial types:

```scala
Bits(width)
UInt(width)
SInt(width)
Bool
```

`Bits` is signless. `UInt` and `SInt` are numeric. Arithmetic on `Bits` requires explicit reinterpretation.

Directional declarations:

```scala
val sample = in(SInt(width))
val result = out(SInt(width + 1))
val offset = param((-3).S(width))
val state = Reg(0.S(width))
val coefficients = Mem(depth = taps, element = SInt(width), ...)
```

Increment 13 compares `(-3).S(8)`-style literals with `SInt.literal(-3, width = 8)`. The accepted form must preserve exact width and value and reject backend-dependent unsized literal behavior.

## Signed conversions

The freeze distinguishes:

- numeric value conversion;
- bit reinterpretation;
- widening/extension;
- explicit lossy conversion.

Directional candidates:

```scala
unsigned.toSInt
signed.toUIntChecked
bits.asSInt
bits.asUInt
signed.asBits
value.extend(width)
value.truncate(width)
value.wrap(width)
value.saturate(width)
value.resizeChecked(width)
```

A conversion from unsigned to signed that preserves all non-negative values may add a sign bit. `asSInt` keeps width and bit pattern. The exact names are frozen only after compile prototypes.

## Signed expression rules

Increment 15 freezes exact result width/sign for:

- unary minus;
- add/subtract;
- multiply/divide/remainder;
- equality and ordered comparisons;
- arithmetic and logical shifts;
- concatenation and extraction;
- conditionals;
- reductions;
- constants and symbolic parameters;
- memory and aggregate fields.

Mixed `UInt`/`SInt` arithmetic or ordered comparison is rejected without an explicit conversion or a specifically frozen lossless promotion. Right shift of `SInt` is arithmetic only through a signed arithmetic-shift operation; logical shift remains separately expressible.

## Portable Verilog mapping

Required representative output:

```verilog
input  signed [WIDTH-1:0] sample;
output signed [WIDTH:0] result;
reg    signed [ACC_W-1:0] accumulator;
wire   signed [SUM_W-1:0] sum;
parameter signed [WIDTH-1:0] OFFSET = -3;
localparam signed [WIDTH:0] LIMIT = 17;
```

Freeze requirements:

- arbitrary-width `SInt` maps to signed vectors, not generic `integer`;
- signedness is preserved for ports, wires, registers, parameters, localparams, memories, generated functions, and flattened aggregate fields;
- literals are explicitly sized/signed where context is insufficient;
- `$signed` and `$unsigned` appear only to implement an explicit Nodal conversion/reinterpretation contract;
- arithmetic shifts and comparisons preserve target-neutral semantics;
- a compatibility workaround is profile-gated and reported;
- Verilator, Icarus, and Yosys fixtures compare Nodal reference behavior against emitted RTL.

## Future SystemVerilog mapping

Required parity candidate:

```systemverilog
input  logic signed [WIDTH-1:0] sample;
output logic signed [WIDTH:0] result;
logic signed [ACC_W-1:0] accumulator;
parameter logic signed [WIDTH-1:0] OFFSET = -3;
```

The future SystemVerilog gate must preserve signedness in packed structs, enum bases, arrays, memories, functions, parameters, localparams, and loop variables. `int`/`integer` is used only when the Nodal type deliberately requests the target's fixed-width integer category.

## Loop category 1: Scala elaboration

```scala
val copies: Int = 4

for index <- 0 until copies do
  val lane = instance(new Lane)
```

Rules:

- executes in Scala construction;
- accepts elaboration values only;
- creates concrete repeated Nodal objects;
- leaves no loop operation in target IR;
- cannot consume symbolic parameters or dynamic signals as Scala bounds;
- normal Scala collection operations remain elaboration-time unless a Nodal hardware collection explicitly provides another operation.

## Loop category 2: symbolic generate

```scala
val lanes = param(default = 4.integer, range = 1 to 16)

generate(0 until lanes) { index =>
  val lane = instance(new Lane(width))
  connect(lane.input, input(index))
}
```

Rules:

- represents structural target generation;
- accepts legal elaboration or symbolic parameter/constant bounds;
- symbolic bounds remain symbolic through IR and HDL;
- body may create instances, declarations, connections, and nested legal generate constructs;
- generated names are deterministic and index-aware;
- portable Verilog/SystemVerilog/Verilog-AMS digital profiles emit `genvar` and `generate for` when supported;
- concrete unrolling is explicit or profile-defined and cannot silently specialize a symbolic generic module.

## Loop category 3: bounded hardware iteration

Preferred candidate:

```scala
loop(0 until taps) { index =>
  next(index) := data(index) + coefficient(index)
}
```

Alternative short candidates may be compared in Increment 13, but the operation must remain distinct from `generate` and ordinary Scala `for`.

Rules:

- represents repeated operations in one combinational or clocked hardware region;
- finite trip count for every legal parameter value;
- bound may be elaboration-static or symbolic-static, never a runtime signal in v0.3;
- compiler may emit a procedural HDL `for` or verified unrolled operations;
- choice is deterministic, profile-controlled, and reported;
- module/port/instance creation is illegal in a procedural loop;
- clocked assignments still occur on one active edge, not one cycle per iteration;
- loop-carried dependencies, multiple drivers, array bounds, memory ordering, effects, and external latency are checked;
- `break`, `continue`, data-dependent termination, and unbounded `while` are excluded initially;
- a multi-cycle algorithm uses FSM/statechart, pipeline, stream, or an explicitly contracted iterative operator.

## Hardware collection operations

Candidates such as:

```scala
vector.map(...)
vector.zip(...)
vector.reduce(...)
vector.fold(...)
vector.scan(...)
```

lower through the bounded iteration/transaction graph model. Reduction order is preserved unless the user and optimization profile explicitly allow reassociation.

## Positive compile matrix

Increment 13 must compile:

- signed ports, wires, registers, parameters, localparams, aggregate fields, arrays, and memories;
- negative and based signed literals with symbolic widths;
- numeric conversion versus reinterpretation;
- arithmetic/logical right shifts;
- lossless signed arithmetic and explicit narrowing;
- Scala elaboration loop with concrete count;
- symbolic generate loop with parameter bound and nested hierarchy;
- concrete and symbolic bounded hardware iteration;
- hardware `map`/`reduce` candidates;
- bounded loop over signed values and memories;
- external-library consumer using only public APIs.

## Negative compile matrix

Required failures include:

- implicit `Bits` arithmetic;
- implicit `UInt`/`SInt` mixing;
- unsized negative literal whose width is ambiguous;
- signed-to-unsigned conversion without policy;
- arithmetic/logical shift ambiguity;
- symbolic `Param` used as Scala range bound;
- dynamic signal used as generate or bounded-loop trip count;
- structural declaration inside a procedural loop;
- unbounded `while` or data-dependent termination;
- loop-carried combinational cycle;
- loop multiple driver or out-of-range index;
- effectful/external operation whose ordering or latency is undeclared;
- backend/profile unable to preserve signedness or loop semantics.

## Freeze exit criteria

Increment 15 may freeze the signed/loop contract only when:

1. `Bits`, `UInt`, and `SInt` roles are unambiguous;
2. signed literals and every conversion category compile with exact widths;
3. result width/sign rules and mixed-sign diagnostics are source-located;
4. portable Verilog signed declarations/parameters/literals/shifts match reference behavior;
5. future SystemVerilog parity is specified without redefining Nodal semantics;
6. elaboration, generate, and bounded hardware iteration are distinct in compile contracts;
7. symbolic generate survives parameterized output;
8. procedural/unrolled lowering has deterministic and equivalent results;
9. dynamic/unbounded loops fail explicitly;
10. source maps, parameter envelopes, effects, memories, optimization barriers, and formal-readiness evidence are specified;
11. positive/negative and external-library fixtures pass CI.

## Increment integration

- Increment 13: compile and compare public candidates.
- Increment 15: freeze public API v0.3 semantics.
- Increment 19: target-neutral signed and loop IR.
- Increment 22: stable diagnostics.
- Increment 54: signed type/declaration/literal foundation.
- Increment 55: signed expressions and bounded hardware iteration.
- Increment 58: symbolic structural generate and deterministic hierarchy naming.
- Increment 65: portable Verilog signed and loop lowering.
- Increment 66: lint/simulation/waveform fixtures.
- Increment 67: synthesis/equivalence/formal-readiness checks.
- Increment 72: Verilog-AMS lowering.
- Increment 85: optimization preservation and proof matrix.
- Increment 99: future SystemVerilog signed/loop mapping gate.
