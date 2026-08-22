# Clock/reset public API v0.2

This reference summarizes the source contract frozen by
[`NodalClockResetApi-DG-v0.2.md`](../design-gates/NodalClockResetApi-DG-v0.2.md). The Scala surface is
compile-only in Increment 12; domain elaboration, verification, MLIR, and HDL lowering follow in later
increments.

## Import

```scala
import nodal.*
```

Ordinary source does not import frontend, compiler, internal, or bootstrap packages.

## Reusable single-domain state

```scala
final class Counter extends Module:
  val enable = in(Bool)
  val value = out(UInt(8))

  val count = Reg(0.U(8))

  when(enable):
    count := count + 1.U(8)

  value := count
```

Declaring state gives a reusable module a default domain requirement. The parent satisfies it by
instantiating the child inside a domain or by calling `.domain(actualDomain)`.

## Root domain

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

`ClockDomain.external` creates boundary intent only when the domain is used by state or a clocked child.
Extending `Module` alone does not add clock/reset ports.

## Existing clock/reset signals

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

`Clock` and `Reset` are distinct from `Bool`.

## Reset policies

```scala
ResetPolicy.None
ResetPolicy.Sync
ResetPolicy.Async
ResetPolicy.AsyncAssertSyncRelease(stages = 2)
```

Boundary polarity is separately selected with `ResetPolarity.ActiveHigh` or
`ResetPolarity.ActiveLow`. Reset dominates enable and normal update.

## State constructors

```scala
val resettable = Reg(0.U(8))
val resetless = Reg.uninitialized(UInt(8))
val delayed = RegNext(input, 0.U(8))
val delayedResetless = RegNext.uninitialized(input)
```

No assignment means hold. Use one lexical priority chain per state value:

```scala
when(load):
  state := input
elsewhen(enable):
  state := state + 1.U(8)
otherwise:
  state := idleValue
```

## Multiple domains and hierarchy

```scala
final class AsyncBridge extends Module:
  val writeDomain = ClockDomain.required("write")
  val readDomain = ClockDomain.required("read")

  writeDomain:
    // write-side state

  readDomain:
    // read-side state
```

Typed parent binding:

```scala
instance(new AsyncBridge)
  .domain(_.writeDomain, bus)
  .domain(_.readDomain, pixel)
```

Single-domain override:

```scala
instance(new Counter).domain(pixel)
```

## Generated domains and relationships

```scala
val pixel = ClockDomain.generated(
  name = "pixel",
  clock = pllClock,
  from = core,
  relation = ClockRelation.Ratio(multiply = 3, divide = 2, phase = 0.deg),
  reset = Rdc.sync(core.reset, stages = 2),
)
```

Initial relations:

```scala
ClockRelation.Same
ClockRelation.Ratio(multiply, divide, phase)
ClockRelation.Synchronous(phaseKnown)
ClockRelation.MutuallyExclusive
ClockRelation.Asynchronous
ClockRelation.Unknown
```

Only `Same` is directly interchangeable by default. Frequency equality alone is not relationship proof.

## CDC

```scala
val levelOut = Cdc.sync(level, to = destination, stages = 2)
val grayOut = Cdc.gray(Gray(counter), to = destination, stages = 2)
val pulseOut = Cdc.pulse(Pulse(pulse), to = destination)
val payloadOut = Cdc.handshake(payload, to = destination)
val streamOut = Cdc.fifo(Stream(payload), to = destination, depth = 4)
```

A source-located exception uses:

```scala
val accepted = Cdc.waive(
  payload,
  to = destination,
  waiver = CdcWaiver(
    id = "CDC-001",
    reason = "reviewed synchronous transfer",
    relation = ClockRelation.Synchronous(phaseKnown = true),
  ),
)
```

A waiver remains reported and does not erase provenance.

## RDC and reset controllers

Transfer into an existing domain:

```scala
val resetOut = Rdc.sync(source.reset, to = destination, stages = 2)
```

Generated-domain construction form:

```scala
reset = Rdc.sync(source.reset, stages = 2)
```

Explicit reset composition:

```scala
val combined = ResetController.combine(powerOnReset, softwareReset)
```

## Physical clock structure

```scala
val gated = ClockGate(
  core,
  enable,
  testEnable = testEnable,
  name = "gated",
)

val selected = ClockMux.glitchless(
  select,
  domains = Seq(gated, alternate),
  name = "selected",
)
```

Ordinary enables use `when`; gate and mux primitives represent intentional physical clocks.

## Analog and event separation

```scala
analog:
  on(cross(V(input, common) - threshold, Edge.Rising)):
    crossed := true.B

  on(timer(0.0.ns, period)):
    sampled := V(input, common)
```

These are genuine analog events and remain valid. Ordinary synchronous source does not use `always`.

The restricted escape is:

```scala
nodal.lowlevel.process(event):
  ...
```

It cannot create untracked ordinary state or bypass domain verification and is excluded from reusable
libraries.

## Diagnostics and fixtures

Stable codes and source-location requirements are in
[`clock-reset-diagnostics-v0.2.json`](../../core/scala/api/clock-reset-diagnostics-v0.2.json). Positive
and negative source contracts are listed in
[`tests/api/fixtures/increment12/manifest.json`](../../tests/api/fixtures/increment12/manifest.json).
