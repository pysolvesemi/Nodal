# Public API migration: v0.1 to v0.2 clock/reset domains

Public API v0.2 replaces backend-shaped ordinary synchronous event processes with domain-owned state.
Analog event semantics remain unchanged.

## Scope

This migration applies to ordinary digital state written in v0.1 as `always(clock.rising)` or
`always(clock.falling)`. It does not replace:

- `analog` contributions;
- `on(cross(...))` analog threshold events;
- `on(timer(...))` analog timer events;
- a separately justified irreducible event process under `nodal.lowlevel.process(event)`.

The stable migration diagnostic is `NODAL-MIGRATION-001`.

## Ordinary single-domain state

### v0.1

```scala
final class Counter extends Module:
  val clock = in(Bool)
  val reset = in(Bool)
  val enable = in(Bool)
  val value = out(UInt(8))

  always(clock.rising):
    value := value + 1.U(8)
```

### v0.2 reusable module

```scala
final class Counter extends Module:
  val enable = in(Bool)
  val value = out(UInt(8))

  val count = Reg(0.U(8))

  when(enable):
    count := count + 1.U(8)

  value := count
```

The reusable module no longer declares ordinary Boolean clock/reset ports. Its state creates an implicit
single-domain requirement and inherits the current domain when instantiated.

### v0.2 root binding

```scala
final class Top extends Module:
  val core = ClockDomain.external(
    name = "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz,
  )

  core:
    instance(new Counter)
```

The root explicitly owns edge, reset policy, polarity, and frequency. Nodal does not choose them from
port names or Boolean expressions.

## Binding existing pins

Use typed `Clock` and `Reset` signals:

```scala
val clockPin = in(Clock)
val resetPin = in(Reset)

val core = ClockDomain.from(
  clock = clockPin,
  reset = resetPin,
  edge = ClockEdge.Rising,
  policy = ResetPolicy.Sync,
  polarity = ResetPolarity.ActiveHigh,
  frequency = 50.MHz,
)
```

A `Bool` cannot bind the `clock` parameter. That misuse is frozen as `NODAL-CLOCK-001`.

## Conditional updates and priority

Separate v0.1 event blocks that drive one state value must become one deterministic lexical chain:

```scala
when(load):
  state := input
elsewhen(enable):
  state := state + 1.U(8)
otherwise:
  state := idleValue
```

Omitting an assignment means hold. Reset dominates the conditional chain. Unrelated update roots are
rejected as `NODAL-STATE-001` rather than receiving backend-dependent process ordering.

## Resetless state

Use explicit resetless construction:

```scala
val state = Reg.uninitialized(UInt(8))
val delayed = RegNext.uninitialized(input)
```

Do not encode resetless intent by omitting reset handling from an event process. A resettable register
uses `Reg(init)` or `RegNext(next, init)`.

## Multiple domains

Declare requirements in a reusable multi-domain module:

```scala
final class AsyncBridge extends Module:
  val writeDomain = ClockDomain.required("write")
  val readDomain = ClockDomain.required("read")

  writeDomain:
    // write-side state

  readDomain:
    // read-side state
```

Bind them with typed selectors:

```scala
instance(new AsyncBridge)
  .domain(_.writeDomain, bus)
  .domain(_.readDomain, pixel)
```

Do not migrate to string-keyed domain maps.

## Crossings

A direct assignment across unrelated domains is not a migration strategy. Select the semantic transfer:

```scala
Cdc.sync(level, to = destination)
Cdc.gray(Gray(counter), to = destination)
Cdc.pulse(Pulse(event), to = destination)
Cdc.handshake(payload, to = destination)
Cdc.fifo(Stream(transaction), to = destination)
```

Reset release uses `Rdc.sync` or a reset policy with synchronized release. Nodal does not silently insert
a generic synchronizer because levels, Gray values, pulses, coherent payloads, streams, and resets have
different correctness conditions.

## Clock enable, gate, and mux migration

A Boolean condition controlling state update remains a `when` enable. It does not become a generated
clock. Physical clock structure uses:

```scala
ClockGate(domain, enable, testEnable = testEnable, name = "gated")
ClockMux.glitchless(select, domains = Seq(a, b), name = "selected")
```

Combinational Boolean-to-clock conversion is rejected.

## Analog and event code

Keep genuine analog events unchanged:

```scala
analog:
  on(cross(V(input, common) - threshold, Edge.Rising)):
    crossed := true.B

  on(timer(0.0.ns, period)):
    sampled := V(input, common)
```

A true event-driven operation that cannot be represented as domain-owned state may be isolated:

```scala
nodal.lowlevel.process(trigger.rising):
  observed := true.B
```

The low-level block cannot create ordinary untracked state or suppress CDC/RDC verification. It is not
part of the reusable-library subset.

## Diagnostic mapping

| v0.1 pattern or migration error | v0.2 diagnostic |
| --- | --- |
| Ordinary `always(event)` state | `NODAL-MIGRATION-001` |
| Root state without a resolved domain | `NODAL-DOMAIN-001` |
| Boolean used as clock | `NODAL-CLOCK-001` |
| Direct unrelated-domain sampling | `NODAL-CDC-001` |
| Multi-bit value passed to `Cdc.sync` | `NODAL-CDC-002` |
| Pulse passed through a level synchronizer | `NODAL-CDC-003` |
| Unreported synchrony assumption | `NODAL-RELATION-001` |
| Unsynchronized asynchronous reset release | `NODAL-RDC-001` |
| Reset reconvergence hazard | `NODAL-RDC-002` |
| State created inside the low-level escape | `NODAL-LOWLEVEL-001` |
| Unrelated update roots for one register | `NODAL-STATE-001` |

Every diagnostic requires a primary path, line, column, and source span. The exact frozen inventory is
[`clock-reset-diagnostics-v0.2.json`](../../core/scala/api/clock-reset-diagnostics-v0.2.json).

## Compatibility boundary

The v0.2 break is limited to the ordinary synchronous `always(event)` subset and Boolean clock/reset
modeling. Analog functions, parameters, hierarchy, native symbolic parameterization, and backend entry
points remain governed by the v0.1 gate unless a later versioned gate changes them.
