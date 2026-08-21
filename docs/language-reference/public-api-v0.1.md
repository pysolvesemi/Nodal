# Nodal public API v0.1 reference

This page summarizes the source contract approved by [`NodalPublicApi-DG-v0.1.md`](../design-gates/NodalPublicApi-DG-v0.1.md). The design gate and [`public-api-v0.1.json`](../../core/scala/api/public-api-v0.1.json) are authoritative.

## Import

```scala
import nodal.*
```

## Module shape

```scala
final class Example extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val common = inout(Electrical)
  val gain = param(2.0.real)

  analog:
    V(output, common) <+ gain * V(input, common)
```

Module declarations are `param`, `in`, `out`, `inout`, `node`, `wire`, `variable`, `instance`, and `connect`.

## Parameterized HDL

`Param[A]` extends `Expr[A]`. Parameters remain symbolic and lower to native Verilog-A or Verilog-AMS parameter declarations and named instance overrides.

```scala
final class ParameterizedBus extends Module:
  val width = param(12.integer)
  val input = in(UInt(width))
  val output = out(UInt(width))

  always:
    output := input
```

```scala
final class Parent extends Module:
  val width = param(10.integer)
  private val child = instance(new ParameterizedBus)
    .param(_.width, width)
```

The compiler must retain symbolic parameter references, must not clone a module for each value, and must diagnose unsupported target uses instead of silently substituting defaults.

## Analog behavior

Use `analog`, contribution `<+`, potential `V`, flow `I`, `ddt`, `idt`, `cross`, `timer`, and `transition`.

```scala
analog:
  I(p, n) <+ capacitance * ddt(V(p, n))
```

## Digital and event behavior

Use `initial`, `always`, `always(event)`, `on(event)`, behavioral assignment `:=`, and `.rising` or `.falling` event conveniences.

```scala
initial:
  ready := false.B

always(clock.rising):
  ready := true.B
```

## Hierarchy

Instances are typed. Select a child member with `instanceHandle(_.member)`, override a parameter with `.param(_.parameter, value)`, and connect compatible endpoints with `connect(...)`.

## Backends

```scala
val result = Nodal.emit(
  new Top,
  EmitOptions(backend = Backend.VerilogAMS),
)
```

The v0.1 backends are `Backend.VerilogA` and `Backend.VerilogAMS`. Emission is in memory and returns an `Emission` containing ordered `EmittedFile` values. The API is compile-only at this milestone; implementation follows in later roadmap increments.

## Compatibility

Source compatibility is required across v0.1 patch releases. Breaking changes require a new versioned design gate and migration note. Future reusable libraries may use only the model-authoring subset listed in the manifest and may not import implementation packages.
