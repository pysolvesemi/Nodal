# Nodal public API v0.3

Public API v0.3 is the unified source contract for digital, analog, and mixed-signal construction.
Implementation remains staged after the freeze.

## Import and module

```scala
import nodal.*

final class Counter extends Module:
  val domain = ClockDomain.required("core")
  val width = param(16.integer)
  val enable = in(Bool)
  val input = in(UInt(width))
  val output = out(UInt(width))

  domain:
    val state = Reg(0.U(width))
    when(enable):
      state := input
    output := state
```

## Values

- `Bool`, `Bits`, `UInt`, and `SInt` are distinct finite-width kinds.
- narrowing and signedness changes are explicit;
- `Struct` is the storable directionless record;
- `Vec` is structural shaped data; `Mem` is addressable storage;
- `Quantity[D]` checks physical dimensions.

## Connectivity

```scala
sealed trait Link extends Interface

object Link:
  val definition = Interface[Link](
    "Link",
    InterfaceMember.stream("payload", UInt(32))
  )
  val sourceRole = Role[SourceRole]("source", RoleAccess.Master("payload"))
  val sinkRole = Role[SinkRole]("sink", RoleAccess.Slave("payload"))
```

`connectExact` is the only direct connection. Conversion uses an explicit adapter module. `Struct` and
`Interface` are never interchangeable.

## Pipeline

```scala
val scheduled = pipe(
  Txn(Input(a, b, tag)),
  PipelinePolicy(latency = Latency.Exact(2))
): current =>
  Result(stage(current.a + current.b), current.tag)
```

Every dynamic read enters through the transaction. `Valid` is valid-only and `Stream` is elastic.
`stage`, `sameStage`, `ParameterEnvelope`, and `inspectSchedule` make constraints and evidence explicit.

## Digital inout and AMS

Digital inout has separate read, drive, release, split-carrier, hierarchy, and pad operations.
Conservative terminals and directional analog signal-flow values remain distinct. Mixed-signal
conversion always uses an explicit bridge contract.

## Emission

```scala
val emission = Nodal.emit(
  new Counter,
  EmitOptions(
    backend = Backend.Auto,
    digitalProfile = DigitalProfile.Synthesis,
    interfaceLayout = InterfaceLayoutPolicy(InterfaceLayout.PortableFlattened)
  )
)
```

`Emission` carries deterministic in-memory files and a `DesignReport`. Native SystemVerilog is not a
v0.3 backend.

## Additive structural slices

[Structural slices v0.1](../design-gates/NodalStructuralSlices-DG-v0.1.md) adds
`slice(lower: Dimension, upperExclusive: Dimension, axis: Int = 0)` on
`Expr[Vec[A]]`. The zero-based axis is selected at construction. Bounds are
half-open, unit-stride static Integer values; `0` and `0.integer` are legal lower
bounds. Construction proves `0 <= lower < upperExclusive <= extent` for every
legal structural parameter value. Empty, reversed, dynamic and unproved ranges
reject. The result retains rank, element type and all other dimensions.

For example, `samples.slice(1, 3, axis = 1).row(0).at(1)` selects a read-only
structural view and then its second element. Slices compose with prior module
port views, slices, row/plane selection, reshape and flatten, preserving the
immediate source mapping. Writable slices and l-values remain separate open
Foundation obligations. Native structural IR verification does not imply
Verilog-A array or target-layout support; unsupported targets reject explicitly.
