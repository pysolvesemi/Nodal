# Core semantics public API v0.3 freeze plan

**Status:** Normative roadmap target
**Architecture:** [ADR 0009](../architecture/0009-core-semantic-contracts.md)
**Enum/FSM architecture:** [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)
**Signed/loop architecture:** [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md)
**Shaped-value architecture:** [ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md)
**Naming architecture:** [ADR 0018](../architecture/0018-expression-materialization-and-semantic-naming.md)
**Quality-gate architecture:** [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md)
**Unified formal freeze:** Increment 15 design gate with automatic pipeline API

## Goal

Freeze the language semantics that clock/reset domains, symbolic parameterization, native enums, reusable FSM/statecharts, parameterized multidimensional shaped values, reusable interfaces, expression materialization/naming, mandatory quality gates, analog equations, memories, external blocks, and automatic pipelines depend on.

The architectural rule is:

> **Explicit stage, lossless value semantics, exact connection, dimension-safe quantity, declared effect.**

The exact spellings below are mandatory Increment 13 candidates. Increment 13 may refine a spelling only when compile prototypes show a Scala ambiguity or a semantic hole. The accepted surface is frozen together with automatic-pipeline API v0.3 in Increment 15.

## Value staging

### Elaboration-only values

Ordinary Scala values remain elaboration-only:

```scala
val debugPorts: Int = 4
val includeTrace: Boolean = true
```

They may control ordinary Scala structure:

```scala
for index <- 0 until debugPorts do
  val port = out(UInt(8))
```

They do not become target HDL parameters unless explicitly declared as Nodal values.

### Symbolic target parameters

```scala
val lanes = param(
  default = 4.integer,
  range = 1 to 16,
)

val width = param(
  default = 12.integer,
  range = 8 to 64,
)
```

A `Param[A]` remains symbolic through hierarchy and HDL emission. It cannot be used as a Scala `Int` or `Boolean`.

### Dynamic values

Ports, wires, registers, memory data, streams, and sampled analog values are runtime hardware values. They cannot control elaboration-time structure or public type shape unless used through an approved symbolic expression category.

### Symbolic generate

Target-visible generation is explicit:

```scala
generate(0 until lanes) { index =>
  val lane = instance(new Lane(width))
}
```

The exact range/index types will be selected by compile prototypes. The frozen contract must distinguish Scala construction loops from target HDL generation and preserve symbolic bounds in parameterized output.

### Required diagnostics

- parameter used as Scala condition or collection length;
- runtime signal used as a type width, array length, or generate bound;
- target generate with a non-static/non-symbolic legal bound;
- bounded hardware loop with a runtime trip count, structural body, unbounded termination, illegal recurrence, or uncovered parameter envelope;
- symbolic structure unsupported by a selected backend.

## Numeric values and widths

Candidate integer/vector types include:

```scala
Bits(width)
UInt(width)
SInt(width)
Bool
```

The v0.3 gate freezes exact result widths for arithmetic, comparison, shifts, concatenation, extraction, and conditionals.

### Lossless default arithmetic

```scala
val sum = a + b
val difference = a - b
val product = a * b
```

Default arithmetic retains all mathematically required result bits. The compiler must not silently discard carry, borrow, sign, or product bits.

### Explicit size and overflow policy

Candidates:

```scala
value.extend(width)
value.truncate(width)
value.wrap(width)
value.saturate(width)
value.resizeChecked(width)
value.toSigned
value.toUnsigned
```

Required distinctions:

- extension is non-lossy and sign/zero aware;
- truncation explicitly drops selected high or low bits under a documented convention;
- wrap applies modular arithmetic semantics;
- saturation clamps to representable bounds;
- checked resize rejects or asserts out-of-range values according to context;
- signedness conversion is distinct from bit reinterpretation.

A narrowing assignment without one of these policies is a compile error.

### Literal and parameter rules

- sized literals have exact width and signedness;
- unsized Scala numeric values do not silently become backend-dependent HDL literals;
- symbolic widths participate in type checking and generated declarations;
- constant folding preserves width, signedness, overflow, and dimension semantics.
- portable Verilog and future SystemVerilog signed declarations, parameters, literals, casts, shifts, arrays, and memories preserve the same target-neutral result;
- `Bits` arithmetic and implicit mixed `UInt`/`SInt` arithmetic/comparison are errors;
- numeric signedness conversion and bit reinterpretation remain separate operations.

## Signed finite-width values and staged loops

[ADR 0016](../architecture/0016-signed-types-and-staged-loops.md), [`signed-loop-api-v0.3-plan.md`](signed-loop-api-v0.3-plan.md), and [`signed-loop-api-v0.3-surface.json`](signed-loop-api-v0.3-surface.json) define mandatory Increment 13 candidates.

`Bits` is signless, `UInt` is unsigned, and `SInt` is two's-complement signed. The v0.3 gate freezes exact signed declarations, literals, parameters/localparams, ports, memories, aggregate fields, arithmetic/comparison/shift result types, numeric conversion versus bit reinterpretation, mixed-sign diagnostics, and portable Verilog/future SystemVerilog mappings.

The gate also distinguishes:

- ordinary Scala loops, executed and fully elaborated during construction;
- symbolic structural `generate(...)`, preserved as target-visible hierarchy;
- a separate bounded hardware-loop operation, lowered deterministically to procedural HDL or verified unrolled operations.

Runtime trip counts, unbounded `while`, hidden multi-cycle loop synthesis, structural declarations inside procedural loops, and backend-defined signedness are outside the initial contract.


## Multidimensional shapes, target layouts, naming, and quality profiles

ADRs [0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md), [0018](../architecture/0018-expression-materialization-and-semantic-naming.md), and [0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md), plus [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md), define mandatory Increment 13 candidates.

Preferred shaped-value direction:

```scala
val matrix = in(Vec(SInt(width), rows, cols))
val element = matrix(row, col)
```

`Vec` has semantic rank/dimensions and structural storage. `Mem` remains explicit addressable storage. Portable Verilog uses a canonical flat carrier; future SystemVerilog defaults to unpacked multidimensional ports of packed elements. Flatten/index/reshape order is target independent.

Preferred emission configuration direction:

```scala
EmitOptions(
  temporaries = TemporaryPolicy.InlineSafe,
  naming = NamingPolicy.Semantic,
  checks = CheckProfile.Default
)
```

Safe single-use expressions inline without anonymous-wire chains. Required temporaries/state receive reasoned semantic names and expression-level source maps. Fast/Default/Release profiles all retain mandatory internal safety checks; external lint/synthesis/equivalence evidence increases by profile. Accidental latches, combinational loops, multiple drivers, hierarchy/scope, width/sign/shape, CDC/RDC, storage, effect, protocol, and target-legality errors prevent accepted emission.


## Native enums and FSM/statechart semantics

[ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md), [`enum-fsm-api-v0.3-plan.md`](enum-fsm-api-v0.3-plan.md), and [`enum-fsm-api-v0.3-surface.json`](enum-fsm-api-v0.3-surface.json) define the mandatory Increment 13 candidates.

Preferred enum direction:

```scala
enum Mode derives HwEnum:
  case Idle, Read, Write, Error

val mode = in(Mode.hw)
val state = Reg(Mode.Idle)
```

The v0.3 gate freezes semantic case identity, canonical ABI encoding, sparse/custom maps, safe decode, exhaustive selection, ports/parameters/aggregates/protocols/memories, and target mappings. Scala ordinal is never the HDL code.

Portable Verilog and Verilog-AMS emit vector/integer storage plus member `localparam`s. Future SystemVerilog emits native typed enums with identical explicit values. Enum configuration parameters remain overrideable module parameters; enum member meanings remain non-overridable.

Preferred FSM direction:

```scala
val controller = fsm(initial = ControlState.Idle):
  state(ControlState.Idle):
    on(start).goto(ControlState.Run)
  state(ControlState.Run):
    exclusive:
      on(done).goto(ControlState.Idle)
      on(fault).goto(ControlState.Error)
```

The gate freezes flat/manual and high-level FSM semantics, entry/active/exit/transition actions, reset/no-hidden-boot behavior, exclusive versus priority transitions, illegal-state policy, local storage encoding independent of enum ABI, typed status, reusable immutable definitions, nested/parallel/timed machines, finite structural recursion, explicit bounded runtime call stacks, graph diagnostics, reports, source maps, and formal readiness.


## Directionless aggregates

The candidate base type is `Bundle` or a refined equivalent:

```scala
final case class Pixel(
  red: UInt,
  green: UInt,
  blue: UInt,
) extends Bundle
```

Aggregate fields do not contain nested port direction. Direction is applied only at a boundary:

```scala
val input = in(Pixel(...))
val output = out(Pixel(...))
```

The same aggregate type is reusable in internal expressions, memories, protocols, pipelines, and external libraries.

The v0.3 gate must freeze:

- product/record declaration form;
- field ordering and deterministic naming;
- nested aggregate and parameterized multidimensional `Vec` rank/dimension/index/flatten/reshape rules;
- equality, assignment, and literal construction;
- parameterized field widths/counts;
- backend flattening and source-map behavior.

## Protocol-typed transport

The common transport types are:

```scala
T
Valid[T]
Stream[T]
```

Semantics:

- plain `T`: fixed-rate value each active cycle;
- `Valid[T]`: payload plus bubble validity, no backpressure;
- `Stream[T]`: ordered ready/valid transport with backpressure.

The types are general interfaces, not pipeline-only wrappers. They carry protocol, domain, ordering, latency, and source metadata in the compiler.

No implicit conversion is permitted among them.

## Exact connections and adapters

Candidate direct connection:

```scala
connect(destination, source)
```

or an operator selected by compile comparison. Direct connection requires exact compatibility and does not resize, drop fields, convert protocols, cross domains, or insert latency.

Intentional adaptation uses a typed contract:

```scala
connect(destination, source.via(PixelAdapter))
```

or:

```scala
connect(destination, source.viewAs[ReducedPixel])
```

The v0.3 gate freezes one concise adapter/view spelling plus:

- adapter direction and reversibility;
- field mapping and diagnostics;
- width/sign conversion requirements;
- protocol conversion requirements;
- domain and latency behavior;
- external-library visibility.

## Physical quantities

The source API remains compact:

```scala
val resistance = param(1.kOhm)
val capacitance = param(10.pF)

analog:
  V(p, n) <+ resistance * I(p, n)
  I(p, n) <+ capacitance * ddt(V(p, n))
```

The frontend/IR carries dimensions for voltage, current, resistance, capacitance, charge, time, frequency, power, and dimensionless values.

The v0.3 gate freezes:

- unit-aware literal construction;
- dimension propagation through arithmetic;
- compatible comparison/addition rules;
- `ddt`/`idt` dimensional behavior;
- explicit dimensionless conversion;
- nature potential/flow dimension binding;
- source-located unit diagnostics.

The implementation must avoid exposing verbose exponent-vector types in normal user code.

## Effect contracts

Candidate effect categories:

```scala
Effect.Pure
Effect.Stateful
Effect.Memory
Effect.Analog
Effect.Event
Effect.Observation
Effect.External
Effect.SideEffect
```

The exact public exposure may be reduced, but the compiler must retain equivalent categories.

Only pure operations and explicitly movable protocol operations may move through automatic pipeline scheduling or retiming.

## Memory contracts

Candidate declaration shape:

```scala
val memory = Mem(
  depth = entries,
  element = UInt(16),
  read = Read.Sync(
    latency = 1,
    underWrite = ReadFirst,
  ),
  write = Write(mask = ByteMask),
)
```

Required policy space:

- asynchronous versus synchronous reads;
- exact read latency;
- read-first, write-first, no-change, or undefined read-under-write;
- write masks and granularity;
- port clock domains;
- collision and ordering behavior;
- initialization/reset capability;
- synthesizable versus simulation-only features.

A missing policy must not be filled from backend defaults.

## External operation contracts

Candidate declaration shape:

```scala
val result = ExternalOp(
  inputs = Txn(a = a, b = b),
  output = UInt(width),
  latency = Latency.Exact(3),
  throughput = Throughput.EveryCycle,
  effect = Effect.Pure,
)
```

Required metadata:

- types and protocols;
- latency and initiation interval;
- domain/reset requirements;
- effect and ordering;
- simulation/synthesis/formal model availability;
- parameter envelope or implementation choices;
- source mapping and black-box identity.

Unknown latency or effect creates a hard optimization and scheduling barrier.

## Relationship to automatic pipelines

Automatic-pipeline API v0.3 depends on this contract:

- all dynamic inputs enter through a transaction;
- arithmetic is fixed and lossless unless explicit conversions are present;
- aggregate sidebands are directionless and exact;
- protocol types define fixed, valid-only, or elastic behavior;
- physical quantities do not enter digital scheduling without explicit sampling;
- effectful operations and memories move only under declared contracts;
- parameterized schedules require finite envelopes.
- shaped-value indexing/layout/storage and expression materialization/naming boundaries remain exact unless a separately verified transformation contract permits change.
- every accepted emission passes the mandatory ADR 0019 internal and selected external quality gates.
- enum codes and FSM state/action/transition/completion boundaries remain exact typed scheduling barriers unless a separately verified transformation contract permits movement or recoding.

Therefore Increment 15 freezes this plan and the automatic-pipeline plan in one versioned gate rather than approving either independently.

## Compile-positive matrix

Increment 13 must compile candidates covering:

- Scala-only configuration;
- symbolic parameters in widths, lengths, and generate loops;
- dynamic values separated from shape values;
- full-width unsigned and signed arithmetic;
- signed ports/registers/parameters/memories, exact negative literals, numeric conversions, bit reinterpretations, and arithmetic/logical shifts;
- Scala elaboration loops, symbolic structural generate loops, bounded hardware loops, and collection map/reduce candidates;
- native Scala enums with default and sparse/custom encodings;
- enum ports/parameters/aggregates/vectors/memories/protocols, safe decode, and exhaustive selection;
- manual and high-level flat FSMs plus reusable nested/parallel/timed/finite-recursive definitions and bounded-stack candidates;
- every explicit narrowing/overflow policy;
- directionless nested aggregates and vectors;
- parameterized rank-one through rank-four `Vec`, exact shape/index/slice/flatten/reshape, signed elements, structural `Vec` versus `Mem`, and portable-Verilog/future-SystemVerilog layout candidates;
- safe expression inlining, shared/observable/target-required materialization, semantic anonymous-register names, and expression-span source maps;
- Fast/Default/Release check profiles and typed waiver metadata;
- exact plain, `Valid`, and `Stream` ports/connections;
- explicit adapters/views;
- dimension-correct analog equations;
- memory read/write policy variants;
- pure fixed-latency and variable-latency external operations;
- external reusable-library use through public APIs only.

## Compile-negative matrix

Required failures include:

- parameter used as Scala `if`/loop/list length;
- runtime signal used as width/generate bound;
- implicit narrowing or signedness change;
- implicit `Bits` arithmetic, mixed `UInt`/`SInt` operation, ambiguous negative literal, or signed/logical shift mismatch;
- symbolic parameter used as a Scala loop bound, runtime generate/hardware-loop bound, structural procedural-loop body, unbounded/data-dependent loop, or loop-carried combinational cycle;
- Scala ordinal used as enum ABI or invalid/duplicate enum code;
- implicit bits-to-enum/cross-enum conversion or ignored sparse-decode validity;
- non-exhaustive switch, overlapping transitions, missing initial state, hidden boot assumption, invalid local encoding, or unbounded FSM recursion;
- aggregate field mismatch or silent field loss;
- runtime/zero/negative/overflowing shape dimension, rank/index/reshape mismatch, illegal target layout, implicit `Vec`/`Mem` conversion, or signed element loss during flattening;
- forced unsafe inlining, traversal-counter-only normal names, duplicate semantic names, or unclassified required temporary;
- disabled mandatory safety check, blanket waiver, accidental latch, combinational loop, multiple driver, undriven output, or unexpected memory inference;
- protocol conversion without an adapter;
- domain crossing through direct connection;
- voltage/current or other dimension mismatch;
- ambiguous memory read-under-write behavior;
- unknown external latency/effect inside a pipeline;
- side effect or analog contribution inside a movable pipeline region;
- parameterized schedule without a finite envelope.

## Freeze exit criteria

The unified v0.3 gate may be approved only when:

1. all three value stages are distinguishable in compile contracts;
2. numeric widths and explicit lossy conversions are unambiguous;
3. aggregate payloads are directionless and exact connections are proven;
4. plain/valid/stream semantics are shared consistently with the pipeline plan;
5. physical-dimension diagnostics work at compile-contract level;
6. memory and external effects have enough metadata to bound scheduling;
7. automatic-pipeline candidates compile against the same types and rules;
8. external-library fixtures use only the frozen public subset;
9. native enum ABI, safe decode, exhaustive selection, portable localparam mapping, and future SystemVerilog numeric parity are proven;
10. flat and reusable hierarchical/parallel/timed/bounded-recursive FSM candidates have unambiguous reset, transition, encoding, report, and diagnostic contracts;
11. signed declaration/literal/parameter/memory/expression/conversion and Verilog-family mapping contracts are exact;
12. elaboration, generate, and bounded hardware-loop contracts plus procedural/unrolled equivalence and dynamic-loop rejection are proven;
13. multidimensional shape/layout/storage and signed-element mappings are exact across portable Verilog and future SystemVerilog contracts;
14. safe inlining removes avoidable anonymous-wire chains while required objects receive deterministic semantic names and source maps;
15. mandatory check profiles, transactional emission, check inventory, and typed waiver boundaries are unambiguous;
16. the machine-readable manifest, migration notes, diagnostics, and CI are green.

## References

- Chisel width inference: <https://www.chisel-lang.org/docs/explanations/width-inference>
- Chisel connection operators: <https://www.chisel-lang.org/docs/explanations/connectable>
- SpinalHDL stream and flow concepts: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html>
- CIRCT ESI channels: <https://circt.llvm.org/docs/Dialects/ESI/>
- Accellera SystemVerilog arrays and ports: <https://www.accellera.org/images/eda/vlog-pp/0438.html>
- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>
- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>
- Chisel enums: <https://www.chisel-lang.org/docs/explanations/chisel-enum>
- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>
- SpinalHDL enums: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>
- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>
