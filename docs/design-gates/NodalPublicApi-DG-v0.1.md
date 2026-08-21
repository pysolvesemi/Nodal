# NodalPublicApi-DG v0.1

**Status:** Approved  
**Scope:** public-api  
**Revision:** v0.1  
**Decision:** Freeze the first Nodal public source API before substantial implementation  
**Approved by:** Repository owner instruction to implement Increment 11 on 2026-08-21

## Decision

The public Nodal source API described below is frozen at v0.1. Later implementation increments must build semantics behind this source shape rather than replacing it with frontend-specific, compiler-specific, or backend-specific construction APIs.

This gate supersedes `NodalPublicApiCandidates-DG-v0.1.md` as the controlling public-API decision. The candidate gate remains as historical evidence of the comparison process.

The machine-readable companion contract is `core/scala/api/public-api-v0.1.json`. The Markdown gate is authoritative when prose and machine-readable metadata disagree; such disagreement is itself a failing Increment 11 contract.

## Default import

Ordinary models and reusable source packages use one Nodal import:

```scala
import nodal.*
```

No `Nodal` prefix is required for normal language constructs. The `Nodal` object is reserved for explicit compiler/backend entry points.

## Frozen public names

### Core modeling types

```text
Data
Expr[A]
DataType[A]
Real
Integer
Bool
Bits
UInt
Param[A]
Signal[A]
Variable[A]
Node[D]
Instance[M]
Module
Event
Edge
```

### Analog domain declarations

```text
Nature
Discipline
NamedDiscipline
Electrical
Voltage
Current
nature(...)
discipline(...)
```

### Module construction methods

The following protected methods are available directly inside a `Module` subclass:

```text
param(default)
input(type-or-discipline)
output(type-or-discipline)
inout(discipline)
node(discipline)
wire(type)
variable(type, initialValue)
instance(module)
connect(left, right)
```

### Behavioral and access constructs

```text
analog { ... }
initial { ... }
always { ... }
always(event) { ... }
on(event) { ... }
V(node)
V(positive, negative)
I(node)
I(positive, negative)
ddt(value)
idt(value)
idt(value, initialValue)
cross(value, edge)
timer(start)
timer(start, period)
transition(value, delay, rise, fall)
toUInt(value, width)
```

### Frozen operators and conversions

```text
<+     analog contribution
:=     procedural or signal assignment
+ - * /
**     integer exponentiation
> >= < <=
&& || !
rising
falling
toReal
```

### Frozen literal forms

```text
Double.real
Int.real
Int.integer
Boolean.B
Int.U(width)
V
A
Ohm
kOhm
F
pF
s
ns
```

Additional units may be added later without changing these spellings.

## Module construction rules

1. A model is a Scala class extending `Module`.
2. Public ports, parameters, nodes, signals, and child instances are declared as stable `val` members when they must be selectable by parents or external consumers.
3. Scala `val` names are candidate HDL names. Deterministic naming and collision rules are implemented by Increment 14 without changing this source form.
4. `analog`, `initial`, `always`, and event blocks use Scala indentation or braces. No mandatory builder object is exposed.
5. Analog contribution uses `<+`; procedural/signal assignment uses `:=`. They remain semantically distinct.
6. Child access and overrides use typed selectors such as `child(_.port)` and `.param(_.width, value)`. String-keyed connections and overrides are not public API.
7. `connect` is explicit at hierarchy boundaries. Hidden reflection-based auto-wiring is not part of v0.1.
8. Ordinary model code must not import `nodal.internal`, `nodal.bootstrap`, frontend, bridge, compiler, simulator, or CLI packages.

## Parameterized Verilog-A and Verilog-AMS generation contract

Parameterized HDL generation is an explicit requirement of this freeze.

### Module parameters

```scala
val resistance = param(1.0.kOhm)
val width = param(12.integer)
```

`Param[A]` is an HDL parameter reference. It is not a plain Scala constant and must not be erased during elaboration.

For a backend that supports the parameter type and expression, Nodal must preserve the declaration in generated Verilog-A or Verilog-AMS rather than cloning a separately specialized module for each value.

Conceptually, the generated module retains declarations equivalent to:

```verilog
parameter real resistance = 1.0k;
parameter integer width = 12;
```

Exact legal spelling, units, ranges, and declaration ordering are defined by later semantic/backend increments and golden tests.

### Parameterized widths and constant expressions

The frozen forms

```scala
Bits(width: Expr[Integer])
UInt(width: Expr[Integer])
toUInt(value, width: Expr[Integer])
```

allow an HDL integer parameter to control vector widths and conversion widths. Integer parameter expressions may use the frozen integer arithmetic operators, including `**`.

A Scala constructor argument such as `class Adc(width: Int)` is elaboration-time configuration, not an HDL parameter. Users must declare `val width = param(12.integer)` when they require parameterized emitted HDL.

### Instance parameter overrides

```scala
private val adc = instance(new Adc).param(_.width, width)
```

The typed override must lower to a named HDL parameter override when supported. Conceptually, Verilog-AMS output retains a form equivalent to:

```verilog
Adc #(.width(width)) adc (...);
```

The default policy is preservation of one parameterized module definition plus named overrides. Backend specialization or module cloning may exist only as an explicit future optimization/profile and must never silently replace parameterized output requested through the standard Verilog-A or Verilog-AMS backend.

### Parameter validation boundary

Later increments must validate:

- supported parameter types;
- constant-expression legality;
- ranges and exclusions;
- width positivity and static requirements;
- override type compatibility;
- unresolved parameter references;
- backend and simulator capability restrictions.

These checks may add diagnostics but must not change the frozen construction syntax.

## Backend entry points

The frozen backend selector is:

```scala
enum Backend:
  case VerilogA, VerilogAMS
```

The frozen emission entry point is:

```scala
Nodal.emit(
  top: => Module,
  backend: Backend,
  outputDirectory: java.nio.file.Path
): Unit
```

Example:

```scala
import java.nio.file.Path
import nodal.*

Nodal.emit(
  new ParameterizedAmsChain,
  Backend.VerilogAMS,
  Path.of("out/ams")
)
```

`Nodal.emit` is intentionally small. Later increments may add result objects, diagnostics access, profiles, CLI options, or overloads, but this signature and the two backend case names remain source-compatible within the v0.1 line.

Backend choice is outside model construction. Models must not contain backend-specific syntax objects or conditionals.

## Representative frozen examples

### Parameterized resistor

```scala
final class Resistor extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(1.0.kOhm)

  analog:
    V(p, n) <+ resistance * I(p, n)
```

### Parameterized ADC

```scala
final class Adc extends Module:
  val width = param(12.integer)
  val analogInput = input(Electrical)
  val common = input(Electrical)
  val sampleClock = input(Bool)
  val code = output(UInt(width))
  val fullScale = param(1.0.V)

  always(sampleClock.rising):
    code := toUInt(V(analogInput, common) / fullScale, width)
```

### Typed hierarchy and override

```scala
final class ParameterizedAmsChain extends Module:
  val width = param(10.integer)
  private val adc = instance(new Adc).param(_.width, width)
  private val dac = instance(new Dac).param(_.width, width)

  connect(adc(_.code), dac(_.code))
```

## Rejected alternatives

The following alternatives are rejected for v0.1:

- `NodalComponent`, `NodalModule`, or other branded base names in ordinary source;
- `Parameter` as the primary ordinary spelling instead of `Param`;
- callback-heavy or explicit builder objects for every analog/procedural region;
- `contribute(...)` as the primary replacement for `<+`;
- verbose potential/flow accessor objects replacing `V(...)` and `I(...)`;
- mandatory wrapper bundles for all ports;
- string-keyed port connections or parameter overrides;
- automatic conversion of every `Param` into a Scala elaboration constant;
- default cloning of one generated module per parameter value;
- backend-specific Verilog-A/Verilog-AMS AST nodes in user model source;
- public access to frontend, bridge, MLIR, native compiler, or simulator internals;
- creating an official reusable `libraries/` package before the core contract and publication architecture are implemented.

## Core-only and future library-author surfaces

The versioned future library-author subset is listed in `public-api-v0.1.json`. It includes model construction, types, expressions, hierarchy, parameters, events, operators, and literals required to author reusable source models.

The following are core/user-project entry points rather than required library-author dependencies:

```text
Backend
Nodal.emit
```

Future libraries must depend only on the listed public modeling subset. They receive no privileged internal imports and must compile in an independent module exactly as an external user project would.

The frozen subset does not create, publish, or promise an official Nodal model library.

## Compatibility policy

1. v0.1 freezes source names and signatures listed by this gate and manifest.
2. Within `0.1.x`, incompatible source changes require a new approved public-API design gate and migration note.
3. Additive public API also requires a design gate because the public API path is protected.
4. Implementation semantics may be completed or tightened by later increments when the source form remains compatible and diagnostics are explicit.
5. No Scala/JVM binary compatibility guarantee is made before v1. Source compatibility is the v0.1 commitment.
6. Internal classes, placeholder runtime machinery, MLIR operations, bridge protocols, compiler passes, generated file layout, and simulator adapters are not frozen by this gate unless explicitly listed.
7. Increment 12 must convert this gate into positive and negative compile contracts. Until those fixtures land, this gate and manifest are the authoritative contract.

## Required follow-up evidence

Increment 12 must cover at least:

- one-import model compilation;
- all representative analog and mixed-signal examples;
- parameterized integer widths;
- parameter references in expressions;
- typed hierarchy and named overrides;
- external reusable-module compilation using only the library-author subset;
- backend entry-point compilation;
- negative tests for internal imports, string-keyed overrides, unsupported ambiguous overloads, and misuse of contribution versus assignment;
- stable diagnostic codes for prohibited API usage.
