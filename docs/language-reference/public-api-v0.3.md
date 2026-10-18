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

[Conservative node arrays v0.3](../design-gates/NodalConservativeNodeArrays-DG-v0.3.md)
adds the module constructor
`node(discipline: D, dimension: Dimension, dimensions: Dimension*): NodeArray[D]`.
The required-first-dimension `in(discipline, dimension, dimensions*)`,
`out(discipline, dimension, dimensions*)`, and `inout(discipline, dimension, dimensions*)`
overloads declare conservative boundary arrays of the same NodeArray type.
The existing `node(Electrical): Node[Electrical.type]` scalar form is unchanged.
NodeArray is a conservative endpoint carrier, not Data, Expr, Signal or Vec(Real).

```scala
val ground = inout(Electrical)
val taps = node(Electrical, 2, 3)
analog:
  I(taps.at(1, 2), ground) <+ V(taps.at(1, 2), ground) / 1000.0
```

Owned local and instance-qualified boundary array references support `row(index)` on rank two, `plane(index)` on rank
three, `slice(lower, upperExclusive, axis = 0)`, `flatten`, and
`reshape(dimensions*)`. Views preserve the owned root and Electrical capability;
they declare no additional nodes. NodeArray flatten preserves checked signed-64
cardinality, including an Integer-expression literal when the extent exceeds Int.
The existing Vec flatten limit is unchanged. Slices are zero-based, half-open and unit-stride,
with bounds proved for every legal parameter value. A final full-rank `at` may
follow any valid composition. Direct and composed selections share physical
identity when the canonical maps prove equality; source occurrences remain distinct.

The implemented profile uses owned local/boundary Electrical declarations and static full-rank
`at(indices: Dimension*): Node[D]` selections. Positive fixed/bounded symbolic
shapes and every index must be valid over the complete legal parameter envelope.
Zero indices are valid. Selections work inside or outside analog; repeated equal
canonical root/map selections reference the same physical node, with distinct source
occurrences retained. Independent symbolic tuples with unproved overlap reject,
rather than silently becoming separate nets. Selected nodes retain existing
scalar branch/access/connection semantics and immediate-child scalar connectivity.
Arrays owned by another Module cannot be selected by bypassing that Module's
boundary. Conservative array connections and writable Real-array storage have separate capability contracts. The generated selection profile is described below.

[Conservative node arrays v0.5](../design-gates/NodalConservativeNodeArrays-DG-v0.5.md)
uses `connect(left, right)` or `left <> right` for local/local, local/child and
child/child relations. Select a child boundary through its exact instance, for
example `child(_.port.slice(0, 2))` or `child(_.port).slice(0, 2)`. Child projections
retain shape intent until all `.param` overrides are finalized, including overrides
authored after the view or relation. Dependent Integer defaults substitute that
final instance environment; equal defaults, names, envelopes or element counts do
not prove extent identity. Raw child declaration handles do not identify an instance.

Each child array port requires exactly-once incident coverage. Whole references,
proved prefix/slice partitions, and scalar `at` connections participate in this
shared native coverage check. Shapes and corresponding extents must match on both
relation sides. Noncontiguous child regions require an exact region proof; a flat
enclosing interval is insufficient. Generated children are checked per actual
instance occurrence: selecting one lane in each iteration does not cover the
other lanes of that iteration's distinct child bus. Child views retain their exact
instance scope; ordinary local roots remain available in legal descendant scopes.

The conservative target layout is one ascending vector per root, with logical
row-major ordinal `k` mapped to vector index `k`. Whole actuals and proven contiguous
views use vector/part-select connections. Noncontiguous views use a compact
structural adapter whose generated alias instances share the same internal net;
no lanes are enumerated by the Scala compiler. Target Integer/genvar carrier limits
still apply. Internal target parsing is not independent simulator acceptance.

## Generated conservative topology

[Conservative node arrays v0.6](../design-gates/NodalConservativeNodeArrays-DG-v0.6.md)
also admits `node(Electrical, dimensions...)` inside `hdlRange`. The root belongs
to that exact generated occurrence and is visible in legal descendant scopes.
Its dimensions remain module-owned static Integer values or expressions, with
positive signed-64 cardinality; the induction cannot determine its dimensions.
Boundary `in`/`out`/`inout` declarations remain module-only. Roots, views and
selected nodes cannot escape to the module or a sibling range. Existing scalar
and array relations use those same owned roots and physical maps.

Generated-local source paths retain module-authored names and collision suffixes.
The private declaration snapshot adds optional typed `generatedScope`, checked
against the existing region declaration inventory; ordinary declarations omit
this field and keep their historical artifact representation. This is an additive
v0.3 snapshot transport field, with no public signature or artifact version change.
Targets declare the existing ascending Electrical vector in the actual generated
block, including constant-index scopes that need no induction SSA argument.

[Conservative node arrays v0.4](../design-gates/NodalConservativeNodeArrays-DG-v0.4.md)
extends the existing `hdlRange(lower, upperExclusive, step)` callback with owned
structural topology. Each callback is captured once, including empty domains.
Multiple child instances, scalar local Electrical nodes and connections may occur
inside nested rectangular ranges. Child definitions keep their own parameters,
ports and bodies; their instance occurrences belong to the exact lexical range.
An escaped or sibling instance handle cannot be selected, connected or overridden.

```scala
val count: Param[Integer] = param(4.integer, range = 1 to 32)
val taps = inout(Electrical, count)
val common = inout(Electrical)
hdlRange(0, count): i =>
  val element = instance(new Cell)
  connect(taps.at(i), element(_.p))
  connect(element(_.n), common)
```

A full-rank `at` can use a direct visible range induction together with static
indices, including on an existing module-owned view. The range retains its source
index, so nonzero origins and positive strides do not become ordinal indices.
For a possibly nonempty domain, every attained index must fit the full legal
extent envelope, using exact upper-bound identity or conservative bounds. An
always-empty range has no attained coordinates, but still validates ownership,
rank, types, child ports and bindings. General induction arithmetic and
nonrectangular bounds are not admitted by this profile.

Whole generated child-array bindings and parameter overrides reuse the ordinary
invariant parent-owned contracts. Child definitions cannot capture parent induction.
The compiler retains compact lexical structural generate scopes and reusable child
definitions rather than expanding every lane. Existing generated analog payloads
retain their separate execution and state ownership. Generated persistent Real-array
initialization follows the declaration-lifetime profile below. Per-occurrence
parameter values remain unfinished.

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

[Writable Real arrays v0.3](../design-gates/NodalWritableRealArrays-DG-v0.3.md)
admits static uniform fills, including their shape views, as persistent root
initializers. [v0.6](../design-gates/NodalWritableRealArrays-DG-v0.6.md) extends this
to generated procedure roots: each structural occurrence has its own scoped
Real-array declaration and static replicated initializer. Symbolic dimensions
and static scalar parameters remain symbolic. Pure generated state, mixed child
topology and nested rectangular scopes use the same declaration lifetime.
Initialization is not repeated at analog evaluation or event entry. Lexical local
initializers still copy their source on each reached entry. Mutable reads, probes,
time and stateful dependencies cannot initialize persistent roots through a fill
or view wrapper. Empty ranges retain source validation. Target shape and signed
Integer/genvar limits remain checked; generic VAMS2023 validation does not imply
support in external tools that reject Real arrays.

[v0.4](../design-gates/NodalWritableRealArrays-DG-v0.4.md) admits selected scalar
storage reads in existing Boolean conditions/case selectors and legal event
arguments through actual live references. Reads preserve short-circuit and event
evaluation sites. This adds no public Real-to-Integer cast for loop bounds.

[v0.5](../design-gates/NodalWritableRealArrays-DG-v0.5.md) admits compound selected
scalar local initializers through explicit immutable captures. Checked uniform
fill/view-derived scalar expressions retain their actual source and effects.
Statement consumers may share one captured fill; live control/event consumers
currently admit only one occurrence of each retained fill per complete expression,
including all primitive argument slots, and reject a fill shared across OR monitor
occurrences. Ordinary scope, unit, event and definite-assignment rules still apply.
