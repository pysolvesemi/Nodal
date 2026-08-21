# NodalPublicApi-DG v0.1

**Status:** Approved  
**Scope:** public-api  
**API version:** 0.1  
**Decision:** Freeze the Nodal public core API before substantive frontend implementation  
**Approved by:** Repository owner instruction to implement Increment 11 on 2026-08-21

## Decision

Nodal public API v0.1 is frozen by this gate. The authoritative machine-readable inventory is [`core/scala/api/public-api-v0.1.json`](../../core/scala/api/public-api-v0.1.json). The Scala declarations under `core/scala/api/src/nodal/` and the examples named here must compile, but their implementation remains intentionally inert until later roadmap increments.

The freeze covers source spellings, type relationships, construction forms, operators, backend entry points, native HDL parameterization behavior, compatibility rules, and the subset available to future reusable libraries. It does not claim that elaboration, naming, diagnostics, MLIR, HDL emission, or simulation semantics are already implemented.

## Import contract

Ordinary models and future reusable libraries use one import:

```scala
import nodal.*
```

No `Nodal` prefix is required on language constructs. Public model source must not import `nodal.internal.*`, `nodal.bootstrap.*`, frontend, bridge, compiler, simulator-adapter, or testkit packages.

## Frozen language names

### Core types and values

The v0.1 public type surface contains:

- `Data`, `Expr[A]`, and `DataType[A]`;
- `Real`, `Integer`, `Bool`, `Bits`, and `UInt`;
- `Module`, `Param[A]`, `Signal[A]`, `Variable[A]`, and `Instance[M]`;
- `Nature`, `Discipline`, `NamedDiscipline`, `Electrical`, and `Node[D]`;
- `Event` and `Edge`;
- `Backend`, `EmitOptions`, `EmittedFile`, `Emission`, and `Nodal`.

The predefined analog values are `Voltage`, `Current`, and `Electrical`. User-defined analog metadata is constructed with `nature(...)` and `discipline(...)`.

### Module declarations

Inside a `Module`, the frozen declaration methods are:

```scala
param(default)
in(kind)
out(kind)
inout(discipline)
node(discipline)
wire(kind)
variable(kind, initialValue)
instance(module)
connect(left, right)
```

`in` and `out` are frozen instead of declaration helpers named `input` and `output`. Scala interprets source such as `val input = input(...)` as a recursive value unless users add ceremony. The shorter spellings preserve clear direction while allowing natural port names:

```scala
val input = in(Electrical)
val output = out(Electrical)
```

`inout` retains the Verilog-AMS spelling because it does not create the same common shadowing problem.

### Behavioral blocks and events

The frozen block and event forms are:

```scala
analog:
  ...

initial:
  ...

always:
  ...

always(event):
  ...

on(event):
  ...
```

The frozen analog and event functions are `V`, `I`, `ddt`, `idt`, `cross`, `timer`, and `transition`. `Edge.Either`, `Edge.Rising`, and `Edge.Falling` are the initial edge values.

### Operators

The v0.1 source operators are:

- analog contribution: `<+`;
- behavioral assignment: `:=`;
- arithmetic: `+`, `-`, `*`, `/`, and unary `-`;
- comparison: `<`, `<=`, `>`, and `>=`;
- Boolean: `!`, `&&`, and `||`;
- event conveniences: `.rising` and `.falling`.

Operators build target-neutral expressions. They do not directly encode Verilog-A or Verilog-AMS text.

### Literals and conversions

The initial unit extensions are `.V`, `.A`, `.Ohm`, `.kOhm`, `.F`, `.pF`, `.s`, and `.ns`. Scalar helpers are `.real`, `.integer`, `.B`, and `.U(width)`. The frozen conversion functions are `toUInt(...)` and `toReal(...)`.

Additional units may be added compatibly. Existing meanings or return types may not change within v0.1.

## Construction rules

1. A user module is a Scala class extending `Module`.
2. Public ports, parameters, nodes, wires, variables, and child instances are declared as stable `val` members.
3. A `Param[A]` is also an `Expr[A]`; symbolic parameters can appear wherever the corresponding expression type is accepted.
4. Child access uses typed selectors such as `child(_.output)` rather than string names.
5. Parameter overrides use typed selectors: `.param(_.gain, value)`.
6. Connections use `connect(...)`; string-keyed connection maps are not part of v0.1.
7. Ordinary model construction contains no backend object and no Verilog text fragments.
8. Source names and deterministic generated names will be implemented behind this API. JVM object identity must never determine emitted names.

## Native parameterized Verilog-AMS contract

Parameterized Verilog-A and Verilog-AMS generation is a required part of the plan and of this frozen API contract.

### Source form

```scala
final class ParameterizedAdc extends Module:
  val width = param(12.integer)
  val fullScale = param(1.0.V)
  val input = in(Electrical)
  val common = in(Electrical)
  val clock = in(Bool)
  val code = out(UInt(width))

  always(clock.rising):
    code := toUInt(V(input, common) / fullScale, width)
```

A parent propagates a symbolic parameter without converting it to a Scala constant:

```scala
final class Top extends Module:
  val adcWidth = param(10.integer)
  private val adc = instance(new ParameterizedAdc)
    .param(_.width, adcWidth)
```

### Required lowering behavior

- `Param[A]` lowers to a native HDL parameter declaration, not to a copied Scala value.
- Parameter references remain symbolic through elaboration, MLIR, optimization, hierarchy, and emission.
- Integer parameter expressions are accepted in `Bits(...)` and `UInt(...)` widths.
- Parent parameters may drive child overrides and remain symbolic across multiple hierarchy levels.
- Named overrides emit native instance parameter overrides.
- A module is not cloned once per parameter value. The compiler emits one parameterized definition for each structural module shape unless a future approved backend gate documents an unavoidable target limitation.
- Constant folding may simplify expressions but must not erase a public parameter or specialize hierarchy merely because a default is known.
- A backend that cannot represent a legal parameter use must issue a stable unsupported-feature diagnostic. It must never silently specialize or substitute the default.

The intended normalized Verilog-AMS shape is:

```verilog
module parameterized_adc(input, common, clock, code);
  parameter integer width = 12;
  parameter real fullScale = 1.0;
  output [width-1:0] code;
  // behavior omitted
endmodule

parameterized_adc #(
  .width(adcWidth)
) adc (...);
```

Exact whitespace, declaration placement, escaped identifiers, and profile-specific syntax remain backend decisions. Symbolic declaration and override semantics do not.

The target-neutral parameter declaration/reference model is implemented in Increment 16 and subsequent expression, hierarchy, and backend increments. Increment 11 freezes the user-facing contract; it does not claim generation is already operational.

## Backend entry points

The frozen in-memory entry point is:

```scala
val emission: Emission =
  Nodal.emit(
    new Top,
    EmitOptions(backend = Backend.VerilogAMS),
  )
```

The approved backend values are:

```scala
Backend.VerilogA
Backend.VerilogAMS
```

`Backend.VerilogAMS` is the default. `Nodal.emit` returns an `Emission` containing deterministically ordered `EmittedFile` values and performs no implicit filesystem writes. A future CLI may write those files, but it must call the same core compilation path.

`Backend.VerilogA` must reject mixed-signal constructs that exceed its analog-only capability profile. `Backend.VerilogAMS` is the first complete mixed-signal target. SystemVerilog-AMS requires a later versioned gate and is not part of v0.1.

## Representative frozen examples

### Resistor

```scala
final class Resistor extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(1.0.kOhm)

  analog:
    V(p, n) <+ resistance * I(p, n)
```

### Comparator

```scala
final class Comparator extends Module:
  val positive = in(Electrical)
  val negative = in(Electrical)
  val result = out(Bool)
  val offset = param(0.0.V)

  analog:
    on(cross(V(positive, negative) - offset)):
      result := V(positive, negative) > offset
```

### Hierarchy and parameter override

```scala
final class FilterTop extends Module:
  val input = inout(Electrical)
  val output = inout(Electrical)
  val common = inout(Electrical)
  val resistance = param(2.0.kOhm)

  private val filter = instance(new RcFilter)
    .param(_.resistance, resistance)

  connect(input, filter(_.input))
  connect(output, filter(_.output))
  connect(common, filter(_.common))
```

## Core API and future library-author subset

The entire frozen API is published by Nodal core. No reusable model library is implemented by this increment.

Future libraries may use the model-authoring subset listed in `public-api-v0.1.json`: data and expression types, module declarations, analog/digital blocks, events, typed instances, typed parameter overrides, and connections. Libraries receive no privileged package access.

The following application/compiler entry points are deliberately excluded from the library-author subset:

- `Backend`;
- `EmitOptions`;
- `EmittedFile`;
- `Emission`;
- `Nodal.emit`.

A library describes reusable models; the consuming application selects a backend and invokes emission.

## Rejected alternatives

The following alternatives are rejected for v0.1:

- `NodalComponent`, `NodalModule`, `NodalParam`, or other branding prefixes in ordinary source;
- `input(...)` and `output(...)` as the primary declaration helpers because of common Scala self-shadowing;
- a mandatory builder/context argument on every declaration;
- callback-heavy forms such as `analog { builder => ... }`;
- primary APIs named `contribute(...)`, `potential(...)`, or `flow(...)` instead of `<+`, `V`, and `I`;
- string-keyed port connections or parameter override maps;
- backend-specific AST nodes exposed to model authors;
- elaboration-time specialization as a substitute for native HDL parameters;
- generating one module copy per parameter value;
- public access to frontend, MLIR bridge, native compiler, or simulator internals;
- populating `libraries/` before the core contract and publication model are proven.

## Compatibility policy

- Public source compatibility is required across v0.1 patch releases.
- Removing, renaming, retyping, changing construction rules, or materially changing semantics requires a new versioned public API design gate and migration note.
- Additive names and overloads are permitted only when existing source remains unambiguous.
- Binary compatibility is not promised before 1.0; source compatibility is the controlling commitment.
- Fixing behavior that contradicts this gate is a conformance correction, but any source break still requires a new gate.
- Internal packages, placeholder runtime classes, source-file layout, MLIR operations, and backend implementation classes are not compatibility surfaces.

## Follow-up

Increment 12 converts this freeze into compile-positive and compile-negative contract fixtures with stable diagnostics. Substantive elaboration begins only after those contracts are in place.
