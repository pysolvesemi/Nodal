# Nodal public API v0.1

This page is a concise source reference for the API frozen by `NodalPublicApi-DG-v0.1.md`. The design gate is authoritative.

## Import

```scala
import nodal.*
```

Backend invocation additionally uses `java.nio.file.Path`.

## Minimal analog module

```scala
final class Resistor extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(1.0.kOhm)

  analog:
    V(p, n) <+ resistance * I(p, n)
```

## Parameterized emitted HDL

Use `Param`, not a Scala constructor argument, when a value must remain configurable in emitted HDL:

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

Supported v0.1 source forms include parameterized `Bits` and `UInt` widths:

```scala
Bits(width)
UInt(width)
toUInt(value, width)
```

where `width` is an `Expr[Integer]`, commonly a `Param[Integer]`.

Typed parameter overrides use selectors:

```scala
private val adc = instance(new Adc).param(_.width, width)
```

The standard Verilog-A and Verilog-AMS backends preserve supported parameter declarations and named instance overrides by default. A Scala constructor argument such as `class Adc(width: Int)` is elaboration-time configuration and is not an HDL parameter.

## Module members

Inside a `Module`, the frozen construction methods are:

```text
param
input
output
inout
node
wire
variable
instance
connect
```

## Analog and event constructs

```text
analog
initial
always
on
V
I
ddt
idt
cross
timer
transition
```

`<+` is contribution. `:=` is procedural or signal assignment.

## Types

```text
Real
Integer
Bool
Bits
UInt
Param[A]
Signal[A]
Variable[A]
Node[D]
```

## Backend emission

```scala
import java.nio.file.Path
import nodal.*

Nodal.emit(new RcFilter, Backend.VerilogA, Path.of("out/va"))
Nodal.emit(new ParameterizedAmsChain, Backend.VerilogAMS, Path.of("out/ams"))
```

The current implementation is a compile-only placeholder. Actual elaboration, diagnostics, MLIR lowering, and file generation are implemented by later roadmap increments without changing these frozen source forms.

## Compatibility

v0.1 provides a source compatibility commitment within the `0.1.x` line. It does not yet provide Scala/JVM binary compatibility. Incompatible source changes require a new approved design gate and migration note.
