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
immediate source mapping. Writable slices use the explicit variable capability
described below. Native structural IR verification does not imply
Verilog-A array or target-layout support; unsupported targets reject explicitly.

## Additive conservative node arrays

[Conservative node arrays v0.2](../design-gates/NodalConservativeNodeArrays-DG-v0.2.md)
adds the module constructor
`node(discipline: D, dimension: Dimension, dimensions: Dimension*): NodeArray[D]`.
The existing `node(Electrical): Node[Electrical.type]` scalar form is unchanged.
NodeArray is a conservative endpoint carrier, not Data, Expr, Signal or Vec(Real).

```scala
val ground = inout(Electrical)
val taps = node(Electrical, 2, 3)
analog:
  I(taps.at(1, 2), ground) <+ V(taps.at(1, 2), ground) / 1000.0
```

Local array references support `row(index)` on rank two, `plane(index)` on rank
three, `slice(lower, upperExclusive, axis = 0)`, `flatten`, and
`reshape(dimensions*)`. Views preserve the owned root and Electrical capability;
they declare no additional nodes. NodeArray flatten preserves checked signed-64
cardinality, including an Integer-expression literal when the extent exceeds Int.
The existing Vec flatten limit is unchanged. Slices are zero-based, half-open and unit-stride,
with bounds proved for every legal parameter value. A final full-rank `at` may
follow any valid composition. Direct and composed selections share physical
identity when the canonical maps prove equality; source occurrences remain distinct.

The initial profile uses module-local Electrical declarations and static full-rank
`at(indices: Dimension*): Node[D]` selections. Positive fixed/bounded symbolic
shapes and every index must be valid over the complete legal parameter envelope.
Zero indices are valid. Selections work inside or outside analog; repeated equal
canonical root/map selections reference the same physical node, with distinct source
occurrences retained. Independent symbolic tuples with unproved overlap reject,
rather than silently becoming separate nets. Selected nodes retain existing
scalar branch/access/connection semantics and immediate-child scalar connectivity.
Arrays owned by another Module cannot be selected by bypassing that Module's
boundary. This does not add conservative array ports, generated selections,
bulk array connections or writable Real-array storage.

## Additive writable Real arrays

[Writable Real arrays v0.1](../design-gates/NodalWritableRealArrays-DG-v0.1.md)
adds `Variable.writable: VariableSelection[A]`. The private-constructor selection
exposes `value: Expr[A]` and `:=(value: Expr[A])`; it allocates no storage. Real-array
selections support `at`, `row`, `plane`, `slice`, `flatten`, and `reshape`, preserving
the existing read-only Expr methods and their return types.

`Vec.fill(Vec(Real, lanes))(0.0.real)` produces an explicit shaped value for the
existing initialized-variable constructor or an exact-shaped assignment. Units
come from its scalar expression. Scalar broadcast is never implicit in `:=`.

```scala
analogProcedure:
  on(initialStep):
    val cells = variable(Vec(Real, 3), Vec.fill(Vec(Real, 3))(0.0.real))
    cells.writable.at(0) := 1.0.real
    cells.writable.slice(1, 3) := cells.slice(0, 2)
```

Reads remain deferred expressions. Each assignment or initializer takes a fresh
complete RHS snapshot before writing, so overlapping copies preserve source
values and reusing an Expr after mutation reads current storage. Shapes and
indices must be static, owned and valid over their full legal parameter ranges.
Partial writes initialize only proven selected regions; event writes do not
initialize storage unconditionally outside the event.

The bounded target profile covers event-local Real storage. Persistent root
array initialization retains an explicit target capability boundary pending its
required lifecycle-correct layout. Selected array reads in control predicates,
selectors, loop bounds and event expressions currently reject because those
consumers need a typed reference-operand extension. Array operations inside their
bodies remain subject to the ordinary scope, event and definite-assignment rules.

A direct selected scalar value can initialize local Real storage through an
explicit snapshot. Compound selected scalar initializers remain unsupported in
this checkpoint; scalar assignments can still consume their captured reads.
