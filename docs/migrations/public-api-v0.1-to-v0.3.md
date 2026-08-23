# Nodal public API v0.1 to v0.3 migration

This document combines the v0.1-to-v0.2 clock/reset migration with the unified v0.3 semantic freeze.

## Analog and parameters remain

The short analog forms remain valid:

```scala
final class Gain extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val gain = param(2.0.real)

  analog:
    V(output) <+ gain * V(input)
```

Native symbolic parameters remain one target module per structure. v0.3 does not specialize or clone a
module for every parameter value.

## Replace ordinary synchronous always

v0.1 ordinary synchronous code:

```scala
always(clock.rising):
  state := next
```

migrates to v0.2/v0.3 domain-owned state:

```scala
val core = ClockDomain.required("core")

core:
  val state = Reg(resetValue)
  when(enable):
    state := next
```

Genuine analog `on(cross(...))` and `on(timer(...))` behavior remains. Only irreducible low-level event
processes use `nodal.lowlevel.process`, outside reusable libraries.

## Adopt v0.3 values, connectivity, and pipeline

Use `SInt` plus explicit conversions for signed arithmetic, `Vec` for structural shaped values, `Mem`
for storage, `Struct` for directionless records, and `Interface`/`Role` for connectivity. Use `Valid`,
`Stream`, or `Txn` according to protocol semantics. Adaptation and CDC/RDC remain explicit.

## Emission

`EmitOptions()` now selects `Backend.Auto`. Pass `Backend.VerilogAMS` explicitly to preserve the v0.1
emission default.
