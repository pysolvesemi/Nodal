# Automatic Pipeline Public API v0.3 Candidate Plan

**Status:** Candidate for Increment 13 comparison and Increment 14 freeze  
**Architecture:** [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md)  
**Machine-readable candidate:** [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json)

## Goal

Freeze a small, user-friendly automatic-pipeline API before implementing a scheduler. Nodal should remove repetitive stage-register and sideband plumbing while keeping transaction, clock/reset, latency, backpressure, parameterization, arithmetic, and side-effect semantics explicit.

The API is for deterministic hardware pipelining, not general high-level synthesis.

## Prior-art boundary

The Increment 13 comparison must compile representative versions of:

- Chisel-style fixed delay with `Pipe`/`ShiftRegister` and elastic buffering with `Queue`/`Decoupled`;
- SpinalHDL-style payload/node/link/builder graphs and manual stage-link retiming;
- Nodal's proposed transaction-lambda form;
- target-neutral IR that can compare selective CIRCT `pipeline` and ESI reuse.

The selected Nodal surface should preserve the useful semantics while hiding node/link plumbing from ordinary datapath source.

## Candidate ordinary API

### Fixed-rate transaction

```scala
val result = pipe(
  input = Txn(a = a, b = b, c = c, tag = tag),
  target = 500.MHz,
  latency = Latency.Auto,
) { x =>
  Result(
    data = (x.a + x.b) * x.c,
    tag = x.tag,
  )
}
```

A plain input transaction represents one transaction every active cycle. The output has the same fixed-rate contract. `tag` is automatically transported and aligned.

### Valid-only transaction

```scala
val output: Valid[Result] =
  pipe(inputValid, target = 500.MHz) { x => transform(x) }
```

Bubbles propagate with the payload. There is no downstream backpressure.

### Elastic transaction

```scala
val output: Stream[Result] =
  pipe(
    inputStream,
    target = 500.MHz,
    ready = ReadyPath.Registered,
  ) { x => transform(x) }
```

The compiler inserts legal elastic stages while preserving ready/valid behavior.

### Explicit delay

```scala
val delayed = value.delay(3)
val delayedValid = valid.delay(3)
val delayedStream = stream.delay(2)
```

`delay` is structural and does not invoke expression scheduling. Protocol semantics determine how validity and backpressure are delayed.

## Candidate policy types

```scala
sealed trait Latency
object Latency:
  case object Auto
  final case class Exact(cycles: Int)
  final case class Range(min: Int, max: Int)

sealed trait Throughput
object Throughput:
  case object EveryCycle

sealed trait ReadyPath
object ReadyPath:
  case object Auto
  case object Combinational
  case object Registered

final case class PipelinePolicy(
  latency: Latency = Latency.Auto,
  throughput: Throughput = Throughput.EveryCycle,
  target: Option[Frequency] = None,
  ready: ReadyPath = ReadyPath.Auto,
)
```

The compile prototype may refine constructor shape and defaults. Increment 14 freezes exact spellings.

## Candidate constraints

```scala
val sum = stage(x.a + x.b)
val product = sameStage:
  sum * x.c
```

- `stage(value)` creates a hard register boundary after the value.
- `sameStage { ... }` prevents automatic cuts within the region.
- Constraints are local and source-located.
- Numeric stage indices are not the normal public API because they become fragile when unrelated logic changes.

An advanced inspection API returns compile-time metadata or generated reports without making generated stage numbers part of ordinary functional source.

## Transaction capture rules

- All dynamic values used by the transform enter through the typed input.
- Input fields are sampled as one transaction.
- Parameters, constants, and static configuration may be referenced directly.
- A live signal read from outside the transaction is rejected by default.
- A later API may add an explicit live-control construct only after its temporal semantics are gated.
- One input transaction produces one output transaction in order in v0.3.

These rules are required for automatic sideband alignment and meaningful formal equivalence.

## Protocol rules

### Fixed-rate

- One transaction is accepted and produced every active cycle after fill.
- No bubbles or backpressure exist.
- Published interfaces require exact or bounded latency.

### `Valid[T]`

- `valid = false` represents a bubble.
- Payload is irrelevant when invalid unless a debug policy requests stabilization.
- Output valid is delayed and transformed with the payload.
- No downstream ready exists.

### `Stream[T]`

- Transfer occurs when `valid && ready`.
- Data and valid remain stable while stalled.
- No transaction is lost, duplicated, or reordered.
- Minimum latency, storage capacity, fall-through behavior, throughput, and ready-path policy are reportable contracts.
- Fork, join, merge, arbitration, and reorder operations remain separate explicit protocol combinators.

## Latency and scheduling rules

- `Latency.Exact(n)` requires exactly `n` cycles from input acceptance to output availability in fixed/valid pipelines.
- `Latency.Range(min,max)` permits the compiler to choose a deterministic schedule inside the range.
- `Latency.Auto` minimizes or selects latency under target constraints but is not permitted to leak as an unconstrained published module interface.
- With no target timing model, automatic stage placement cannot claim a frequency. The compiler requests explicit boundaries or an applicable model.
- The initial scheduler supports acyclic, single-domain, initiation-interval-one graphs only.
- Arithmetic order, widths, signs, rounding, saturation, overflow, and exceptions remain unchanged.

## Automatic alignment

The compiler must:

- balance reconvergent operands to the same transaction and stage;
- carry tags, predicates, exception flags, packet metadata, and other sidebands only to their uses;
- align branch-select and data paths without user shift-register chains;
- reject ambiguous mixing of values from different transaction identities;
- report every inserted alignment delay.

## Clock/reset contract

- A pipeline captures the current lexical `ClockDomain`.
- One pipeline region cannot contain a CDC/RDC.
- Crossings use the frozen `Cdc`/`Rdc` API before or after the region.
- Payload registers are resetless by default.
- Valid, occupancy, and control state follow the current domain reset policy.
- Reset dominates flush, stall, enable, and normal update; Increment 14 freezes the complete priority table.
- Automatic scheduling never creates a clock or physical gate.

## Parameterized HDL contract

The default is one native parameterized module, not value-specialized clones.

A timing-affecting parameterized pipeline is automatically scheduled only when every relevant parameter has a finite declared envelope and the selected timing model can produce one conservative schedule valid across that envelope.

Example direction:

```scala
val width = param(12.integer, range = 8 to 64)

val y = pipe(
  Txn(a = a, b = b),
  target = 500.MHz,
  scheduleFor = Envelope.Max,
) { x => x.a + x.b }
```

The schedule is fixed for the legal envelope; emitted widths remain symbolic. If no safe envelope exists, the compiler requires explicit cuts, a checked assumption, or a concrete compilation. It must not silently clone the module per width.

The exact envelope API is compared in Increment 13 and frozen in Increment 14.

## Operators, memories, and controls

The v0.3 gate must reserve typed contracts for:

- fixed-latency operators and black boxes;
- elastic variable-latency operators;
- synchronous memory ports with declared latency and ordering;
- flush/cancel semantics;
- commit barriers for side effects.

The initial automatic transform remains pure feed-forward dataflow. Analog contributions, arbitrary procedural assignments, module construction, memory writes without an ordering contract, and opaque side effects are compile errors inside the region.

## Schedule stability

- Scheduling is deterministic for the same source, toolchain, timing model, target profile, parameter envelope, and policy.
- Generated stage names derive from source/hierarchy symbols, not traversal order or JVM identity.
- Reports include a schedule hash and all inputs that affect it.
- Exact latency is an interface contract.
- A schedule change that preserves a ranged or internal-auto contract is reported, not hidden.
- Bounded retiming may move only pipeline-owned registers and never crosses explicit anchors or semantic barriers.

## Required diagnostics

Increment 14 freezes stable codes for at least:

- missing current clock domain;
- cross-domain value in a pipeline;
- unrelated live input capture;
- timing target without a delay model;
- impossible exact/ranged latency;
- inconsistent transaction identities at reconvergence;
- side effect or memory operation without a contract;
- variable-latency unit in a fixed-rate pipeline;
- invalid ready-path or combinational ready loop;
- unsupported parameter envelope;
- parameter schedule that would require value-specialized cloning;
- hard stage/same-stage constraint conflict;
- published `Latency.Auto` without an interface bound.

## Compile-positive fixture matrix

- fixed-rate arithmetic with automatic cuts;
- explicit `delay`;
- `Valid[T]` bubbles;
- elastic `Stream[T]` with randomized backpressure;
- automatic tag/predicate alignment;
- reconvergent datapath balancing;
- exact, ranged, and internal auto latency;
- `stage` and `sameStage` constraints;
- parameter-envelope-safe schedule;
- fixed-latency operator;
- external reusable-library pipeline using public APIs only.

## Compile-negative fixture matrix

- pipeline without a domain;
- CDC hidden inside a region;
- live external signal capture;
- arbitrary side effect;
- incompatible protocol conversion;
- impossible latency/target;
- missing timing model;
- unconstrained published auto latency;
- arbitrary multi-bit or variable-latency misuse;
- conflicting hard constraints;
- unbounded timing-affecting parameter;
- clone-per-parameter scheduling request.

## Staged implementation increments

- **Increment 13:** candidate prototypes and architecture comparison.
- **Increment 14:** v0.3 design gate, manifest, migration notes, and compile contracts.
- **Increment 58:** transaction graph and latency-provenance IR.
- **Increment 59:** fixed-rate and valid-only scheduling.
- **Increment 60:** elastic ready/valid synthesis.
- **Increment 61:** timing/resource models and target-driven partitioning.
- **Increment 62:** controls, anchors, memories, and multi-cycle units.
- **Increment 63:** hierarchy, schedule stability, reports, and bounded retiming.
- **Increment 68:** Verilog-AMS backend integration.
- **Increment 69:** ADC/DAC mixed-signal proof.

## Freeze exit criteria

Increment 14 is complete only when:

1. the public API design gate is approved;
2. exact names, overloads, types, defaults, and imports are listed in a machine-readable manifest;
3. every positive fixture compiles against public core APIs;
4. every negative fixture fails with a stable code and source location;
5. fixed/valid/elastic, reset, latency, parameter-envelope, and side-effect semantics are explicit;
6. CIRCT reuse boundaries are documented;
7. the external-library fixture uses no internal package;
8. the v0.3 compatibility and migration policy is published;
9. Core CI passes; and
10. the roadmap checkbox is changed only in the completing increment.
