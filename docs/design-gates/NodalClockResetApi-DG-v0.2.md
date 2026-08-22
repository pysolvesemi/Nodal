# NodalClockResetApi-DG v0.2

**Status:** Approved  
**Scope:** public-api  
**API version:** 0.2  
**Decision:** Freeze implicit clock/reset domains, high-level state, and explicit crossings  
**Approved by:** Repository owner instruction to implement Increment 12 on 2026-08-22

## Decision

Nodal clock/reset public API v0.2 is frozen by this gate. The authoritative inventories are:

- [`public-api-v0.2.json`](../../core/scala/api/public-api-v0.2.json);
- [`clock-reset-diagnostics-v0.2.json`](../../core/scala/api/clock-reset-diagnostics-v0.2.json);
- [`clock-reset-api-v0.2-surface.json`](../roadmap/clock-reset-api-v0.2-surface.json);
- [`tests/api/fixtures/increment12/manifest.json`](../../tests/api/fixtures/increment12/manifest.json).

The binding architectural rule is:

> **Implicit local domain, explicit crossing, explicit emitted HDL.**

The Scala declarations and positive fixtures compile, while the implementation remains intentionally inert.
This gate freezes source forms, type distinctions, diagnostic identities, and semantic obligations. It
must not be read as a claim that elaboration, domain provenance, CDC/RDC verification, MLIR lowering,
or HDL generation is implemented.

Public API v0.1 remains the historical baseline for analog, parameter, hierarchy, and backend forms.
This v0.2 gate supersedes its ordinary synchronous `always(event)` subset. Genuine analog events
remain under `analog` and `on(...)`; irreducible event processes are quarantined under
`nodal.lowlevel.process(event)`.

## Frozen architecture

### Clock and reset types

`Clock` and `Reset` are distinct `Data` types. Neither is an alias of `Bool`, and no ordinary public
conversion from `Bool` is provided. Boundary declarations therefore use typed ports:

```scala
val clockPin = in(Clock)
val resetPin = in(Reset)
```

The frozen edge, reset, and relationship values are:

```scala
ClockEdge.Rising
ClockEdge.Falling

ResetPolarity.ActiveHigh
ResetPolarity.ActiveLow

ResetPolicy.None
ResetPolicy.Sync
ResetPolicy.Async
ResetPolicy.AsyncAssertSyncRelease(stages = 2)

ClockRelation.Same
ClockRelation.Ratio(multiply, divide, phase = 0.deg)
ClockRelation.Synchronous(phaseKnown = false)
ClockRelation.MutuallyExclusive
ClockRelation.Asynchronous
ClockRelation.Unknown
```

Reset policy and boundary polarity remain independently represented. Frequency and phase metadata use
`.MHz` and `.deg` candidate literals. Equal frequencies do not establish a safe relationship.

### Domain construction and lexical application

The frozen constructors are:

```scala
ClockDomain.external(...)
ClockDomain.from(...)
ClockDomain.required(...)
ClockDomain.generated(...)
```

`ClockDomain.external` declares a root boundary. `ClockDomain.from` binds existing typed signals.
Both forms require explicit edge, reset policy, polarity, and frequency in v0.2; the API does not choose
those architectural properties silently.

`ClockDomain.required` declares a reusable domain requirement. A reusable single-domain module may use
an implicit default requirement, while a reusable multi-domain module exposes named requirements.
`ClockDomain.generated` records the source domain and `ClockRelation`.

A domain is applied lexically:

```scala
core:
  val state = Reg(0.U(8))
```

The public syntax does not expose Scala `given` or `implicit` parameters. Increment 16 must implement a
compiler-managed deterministic context rather than thread-local, global, or JVM-identity state.

### Hierarchy binding

A single-domain child inherits the current lexical domain. An explicit override is:

```scala
instance(new Child).domain(core)
```

A multi-domain child uses typed selectors:

```scala
instance(new AsyncBridge)
  .domain(_.writeDomain, bus)
  .domain(_.readDomain, pixel)
```

String-keyed domain maps are rejected. Typed selectors remain valid across refactoring and preserve the
child requirement identity for later diagnostics and lowering.

### State and update semantics

The frozen state constructors are:

```scala
Reg(init)
Reg.uninitialized(kind)
RegNext(next, init)
RegNext.uninitialized(next)
```

The frozen conditional update forms are:

```scala
when(condition):
  ...
elsewhen(condition):
  ...
otherwise:
  ...
```

The semantic contract is:

- a register captures its current domain at declaration;
- `Reg(init)` and `RegNext(next, init)` carry reset values;
- resetless or deliberately uninitialized state is explicit;
- no active assignment means hold;
- one `when`/`elsewhen`/`otherwise` chain defines lexical priority;
- reset dominates clock enable and ordinary update;
- unrelated update roots for one state value are an error;
- conditional state update represents enable intent and does not create a clock.

The placeholder `UInt` addition used by the fixtures freezes only the ordinary source spelling needed by
this gate. Exact result width, overflow, signedness, and narrowing rules remain reserved for the unified
v0.3 gate.

## CDC, RDC, and clock structure

### CDC categories

The frozen crossing surface is:

```scala
Cdc.sync(bit, to = destination, stages = 2)
Cdc.gray(grayValue, to = destination, stages = 2)
Cdc.pulse(pulse, to = destination)
Cdc.handshake(payload, to = destination)
Cdc.fifo(stream, to = destination, depth = 4)
Cdc.waive(value, to = destination, waiver = CdcWaiver(...))
```

`Cdc.sync` accepts only `Expr[Bool]`. `Gray[A]`, `Pulse`, and the minimal `Stream[A]` carrier make the
category distinction visible to Scala compile checks. General protocol construction and complete
`Stream` semantics remain part of public API v0.3; v0.2 freezes only the asynchronous-FIFO crossing
shape.

The source domain is inferred later from provenance and the destination is explicit. Nodal does not
silently insert a crossing. A waiver is source located, reported, and retains provenance and
reconvergence analysis.

### RDC forms

The frozen reset surface is:

```scala
Rdc.sync(reset, to = destination, stages = 2)
Rdc.sync(reset, stages = 2)
ResetController.combine(...)
```

The first `Rdc.sync` overload transfers reset into an existing destination domain. The second is a
construction form consumed while defining a new generated domain; that generated domain becomes the
recorded destination. This resolves the roadmap candidate's generated-domain example without allowing
an unreported destination in ordinary transfer code.

Reset assertion source, polarity, policy, release synchronization, destination, and reconvergence must
remain independently available to later verification.

### Gates and muxes

Physical clock structure is explicit:

```scala
ClockGate(domain, enable, testEnable = false.B, name = "gated")
ClockMux.glitchless(select, domains = Seq(a, b), name = "selected")
```

Both return derived `ClockDomain` values and must retain source, relationship, test, mapping, and timing
metadata. Arbitrary Boolean clock creation remains invalid.

## Analog and low-level events

The analog forms remain ordinary public API:

```scala
analog:
  ...

on(cross(...)):
  ...

on(timer(...)):
  ...
```

They do not create implicit clock/reset ports. Analog-only and combinational modules remain domain
neutral until they declare or inherit sequential state.

The only event-process escape frozen by this gate is:

```scala
nodal.lowlevel.process(event):
  ...
```

It is excluded from the reusable-library subset. Later verification must reject untracked state,
CDC/RDC bypass, and use that can be expressed with ordinary domain-owned state.

## Migration from v0.1

Ordinary v0.1 code such as:

```scala
always(clock.rising):
  state := next
```

migrates to domain-owned state:

```scala
val state = Reg(resetValue)

when(enable):
  state := next
```

A root declares or binds a `ClockDomain`; reusable single-domain children inherit it. The complete
migration contract is in
[`public-api-v0.1-to-v0.2.md`](../migrations/public-api-v0.1-to-v0.2.md).

The migration does not replace `on(cross(...))`, `on(timer(...))`, or other genuine analog behavior.
A true event process that cannot be expressed as state may move to `nodal.lowlevel.process`, subject to
its restrictions.

## Diagnostic contract

All v0.2 clock/reset diagnostics require a primary source span containing path, line, column, and span.
Cross-hierarchy errors must additionally report the relevant domain and instance path when those are
available. The stable initial codes are:

| Code | Meaning |
| --- | --- |
| `NODAL-DOMAIN-001` | Sequential state has no resolved domain. |
| `NODAL-CDC-001` | A value is sampled across unrelated domains directly. |
| `NODAL-CDC-002` | Multi-bit data is passed to `Cdc.sync`. |
| `NODAL-CDC-003` | A pulse is passed through a level synchronizer. |
| `NODAL-RELATION-001` | Safety relies on an unreported clock relationship. |
| `NODAL-RDC-001` | Asynchronous reset release is unsynchronized. |
| `NODAL-RDC-002` | Reset paths reconverge with incompatible provenance. |
| `NODAL-CLOCK-001` | A Boolean is used as a clock. |
| `NODAL-MIGRATION-001` | Ordinary synchronous `always(event)` is used after v0.1. |
| `NODAL-LOWLEVEL-001` | The low-level event escape creates untracked state or bypasses checks. |
| `NODAL-STATE-001` | One state value has unrelated update roots. |

The JSON diagnostic manifest owns exact names, phases, messages, suggestions, and source-location
requirements. A later implementation may improve wording compatibly but may not reuse a code for a
different condition.

## Fixture and validation model

Positive fixtures compile as the `examples.clockResetApi` Mill module and as an external consumer in
`examples.externalLibrary`. They cover:

- implicit reusable state and explicit root binding;
- binding existing typed clock/reset signals;
- every reset policy and both clock edges/polarities;
- generated domains and every initial relationship category;
- inherited, overridden, and typed multi-domain hierarchy binding;
- level, Gray, pulse, handshake, FIFO, waiver, reset, gate, and mux forms;
- analog event separation and the quarantined low-level escape.

Negative fixtures have two honest execution modes:

- `scala-type-rejected` fixtures are injected one at a time into a compiled candidate module and must
  fail Scala compilation;
- `semantic-contract` fixtures intentionally compile against inert placeholders and freeze the source
  construct, diagnostic code, and exact diagnostic anchor for the domain-aware frontend implemented by
  later increments.

The gate does not describe a semantic fixture as operational verification before that verifier exists.
`./nodal check` runs the repository contract checker, executable type-negative compilation, all positive
Scala compilation, unit tests, formatting, native checks, and contribution policy.

## Library boundary

A future reusable library may use inherited/required/generated domains, state, typed hierarchy binding,
CDC/RDC, gates, and muxes from the approved subset. Root-only `ClockDomain.external/from` forms remain
application integration responsibilities. `nodal.lowlevel.*`, backend selection, emission, and internal
packages remain excluded.

The external fixture imports only `nodal.*` and proves ordinary inherited-domain state without access to
frontend, compiler, simulator, bootstrap, or internal packages.

## Rejected alternatives

- keep `always(clock.rising)` as the ordinary state API;
- add clock/reset ports to every `Module`;
- model `Clock` or `Reset` as `Bool` aliases;
- infer synchrony from equal frequency;
- insert a generic synchronizer automatically;
- permit a multi-bit bus or pulse through `Cdc.sync`;
- use string-keyed domain binding;
- permit an unreported relationship suppression;
- use combinational Boolean expressions as clocks;
- expose public Scala context parameters as the domain mechanism;
- treat the low-level escape as a reusable-library state API;
- claim semantic CDC/RDC verification before the frontend exists.

## Compatibility policy

- Source compatibility is required across v0.2 patch releases.
- The v0.1 analog, parameter, hierarchy, and backend contracts remain valid unless explicitly changed by
  a later gate.
- Ordinary synchronous `always(event)` is the intentional v0.1-to-v0.2 source break and carries
  `NODAL-MIGRATION-001`.
- Removing, renaming, retyping, or materially changing a v0.2 clock/reset form requires a new versioned
  design gate and migration note.
- Additive overloads are allowed only when existing source remains unambiguous and semantic safety is
  not weakened.
- Binary compatibility is not promised before 1.0.

## Freeze exit evidence

1. Ordinary positive synchronous fixtures contain no `always` or explicit clock event.
2. Analog-event fixtures contain no `ClockDomain`, `Clock`, `Reset`, or `Reg` declaration.
3. Single-domain inheritance, explicit override, and typed multi-domain binding compile.
4. Reset policy and polarity are separate manifest fields and fixture arguments.
5. Unsafe type-level crossings and Boolean clocks fail executable Scala compilation.
6. Every crossing category has a positive structural fixture.
7. The external-library fixture imports only the approved public package.
8. Migration behavior and all stable diagnostics are documented and source anchored.
9. The dedicated Increment 12 workflow and complete `./nodal check` gate are required before merge.

## Follow-up implementation

- Increment 16 implements deterministic lexical domain context and hierarchy binding.
- Increment 17 captures source locations and deterministic domain-related names.
- Increment 19 adds target-neutral domain, relationship, state-ownership, CDC, and RDC IR.
- Increment 22 maps the frozen diagnostics across Scala, hierarchy, and native verification.
- Increments 56-58 implement state, crossings, clock structure, and domain-aware hierarchy.
- Increments 69, 71, and 72 implement mixed-signal provenance, verification, and Verilog-AMS lowering.
