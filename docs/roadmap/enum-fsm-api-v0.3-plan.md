# Enum and reusable FSM public API v0.3 plan

**Status:** Normative roadmap candidate
**Architecture:** [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)
**Unified formal freeze:** Increment 15 design gate with core semantics and automatic pipelines
**Machine-readable candidate:** [`enum-fsm-api-v0.3-surface.json`](enum-fsm-api-v0.3-surface.json)

## Goal

Freeze a short Scala 3 enum and FSM API that is at least as reusable as current Chisel and SpinalHDL while improving backend portability, enum ABI stability, hierarchical composition, transition verification, reset clarity, recursive bounds, source mapping, and formal readiness.

The binding rule is:

> **Names define meaning, explicit encodings define ABI, typed statecharts define control, and every lowering preserves reviewable state identity.**

No enum/FSM implementation is performed by this plan. Increment 13 compile-tests the candidates, and Increment 15 freezes the accepted spellings and semantics.

## Important language correction

SystemVerilog supports native `enum` types. Portable Verilog-2005 and normal Verilog-A/Verilog-AMS profiles do not provide the same SystemVerilog enum type system.

Nodal therefore plans:

- portable Verilog and Verilog-AMS: vector/integer storage plus non-overridable `localparam` member symbols;
- future SystemVerilog: native `typedef enum logic [...]` with the same explicit member values;
- sidecar metadata for every backend so enum type/case identity survives flattening and waveforms.

A module configuration value of enum type is still an overrideable `parameter`; enum member definitions themselves are `localparam`s in portable Verilog because their meanings must not be overridden.

## Preferred enum declaration

```scala
import nodal.*

enum Mode derives HwEnum:
  case Idle, Read, Write, Error
```

Preferred use:

```scala
val requested = in(Mode.hw)
val current = Reg(Mode.Idle)

when(current === Mode.Read):
  // hardware behavior
```

Increment 13 must prove the preferred `Mode.hw` shape works reliably with Scala 3 enum mirrors, imports, nested packages, external libraries, generics, separate compilation, and IDE/source naming. A concise `EnumType[Mode]` fallback may be selected only if the preferred form is technically fragile.

### Required enum behavior

- Enum cases are typed hardware literals in Nodal expression contexts.
- Scala `ordinal` is never assumed to be the HDL code.
- `Mode.values` remains elaboration-time Scala metadata, not dynamic hardware.
- Enum type and case IDs are stable and source-located.
- Enum values work in ports, wires, registers, parameters, aggregates, vectors, memories, `Valid`, `Stream`, pipelines, and external reusable libraries.
- Different enum types are not assignment-compatible merely because their bit widths match.
- Reordering a default-sequential public enum is reported as an ABI change.

## Canonical enum encoding

The enum's canonical encoding defines its interface/storage ABI outside an explicitly recoded local FSM.

Default candidate:

```text
EnumEncoding.Sequential
```

Additional candidates:

```text
EnumEncoding.OneHot
EnumEncoding.Gray
EnumEncoding.custom(width)(case -> code, ...)
```

Directional custom mapping:

```scala
enum Opcode derives HwEnum:
  case Load, Store, Branch, Jump

object Opcode:
  given EnumEncoding[Opcode] =
    EnumEncoding.custom(width = 7)(
      Opcode.Load   -> 0x03,
      Opcode.Store  -> 0x23,
      Opcode.Branch -> 0x63,
      Opcode.Jump   -> 0x6f
    )
```

Freeze requirements:

- width is exact and at least one bit;
- every non-alias case has exactly one code;
- duplicate codes are rejected unless a future alias contract explicitly permits them;
- values outside width are rejected;
- signed encodings are outside the initial contract;
- encoding is elaboration-time metadata and cannot depend on a runtime signal;
- public encoding changes require migration/evidence;
- a machine-readable encoding hash is emitted.

## Enum operations

Candidates to compile and compare:

```scala
mode.asBits
mode.isValid
mode.isOneOf(Mode.Read, Mode.Write)
Mode.width
Mode.cases
Enum.decode[Mode](bits)
Enum.decodeUnsafe[Mode](bits)
```

Safe decode must return typed value plus validity. The final API may use a named result record rather than a tuple so fields remain self-describing.

Required failures:

- implicit bits-to-enum cast;
- sparse/custom decode with ignored validity;
- enum-to-enum assignment across different types;
- enum arithmetic without explicit conversion;
- duplicate or out-of-range code;
- width growth caused silently by a later case;
- public enum declaration whose default encoding is unstable under the selected compatibility policy.

## Exhaustive hardware switch

Preferred candidate:

```scala
switch(mode):
  case Mode.Idle  => idleBehavior()
  case Mode.Read  => readBehavior()
  case Mode.Write => writeBehavior()
  case Mode.Error => errorBehavior()
```

Alternative compile candidate:

```scala
switch(mode):
  is(Mode.Idle):  idleBehavior()
  is(Mode.Read):  readBehavior()
  is(Mode.Write): writeBehavior()
  is(Mode.Error): errorBehavior()
```

The first form is preferred if Scala 3 inline/macro implementation preserves clear diagnostics and source locations. The freeze requires exhaustive-case analysis, duplicate detection, typed defaults, invalid-encoding handling, and deterministic lowering.

## Backend mapping contract

### Portable Verilog

```verilog
localparam [1:0] MODE_IDLE  = 2'd0;
localparam [1:0] MODE_READ  = 2'd1;
localparam [1:0] MODE_WRITE = 2'd2;
localparam [1:0] MODE_ERROR = 2'd3;

reg [1:0] current_mode;
```

Rules:

- enum member symbols are `localparam`;
- public port and memory storage is vector encoded;
- enum configuration parameters are normal module `parameter`s with legal-value metadata;
- symbols are deterministic, collision-safe, and scoped to the containing module/profile;
- comments and sidecar manifests retain source names and type IDs;
- no SystemVerilog syntax leaks into the portable profile.

### Future SystemVerilog

```systemverilog
typedef enum logic [1:0] {
  MODE_IDLE  = 2'd0,
  MODE_READ  = 2'd1,
  MODE_WRITE = 2'd2,
  MODE_ERROR = 2'd3
} mode_t;
```

Rules to evaluate in the future SystemVerilog gate:

- module-private typedef versus design-level package;
- package compile-order manifest;
- enum-typed ports, parameters, arrays, structs, and memories;
- fallback for tools that reject package-based types;
- exact numeric compatibility with portable Verilog output;
- source-map and debug-name parity.

### Verilog-A and Verilog-AMS

- analog/control parameters use integer-compatible encodings where required;
- digital state in Verilog-AMS uses vector/localparam encoding;
- enum metadata remains available to diagnostics, simulation, and source maps;
- backend profile rejects enum uses it cannot represent without semantic loss.

## Manual FSM candidate

```scala
enum ControlState derives HwEnum:
  case Idle, Load, Run, Error

val state = Reg(ControlState.Idle)

switch(state):
  case ControlState.Idle =>
    when(start): state := ControlState.Load
  case ControlState.Load =>
    when(done): state := ControlState.Run
  case ControlState.Run =>
    when(stop): state := ControlState.Idle
  case ControlState.Error =>
    state := ControlState.Idle
```

Manual FSMs use ordinary enum registers and remain fully supported.

## High-level FSM candidate

```scala
val controller = fsm(
  initial = ControlState.Idle,
  encoding = FsmEncoding.Compact
):
  state(ControlState.Idle):
    on(start).goto(ControlState.Load)

  state(ControlState.Load):
    entry:
      count := 0.U
    active:
      count := count + 1.U
    exclusive:
      on(fault).goto(ControlState.Error)
      on(done).goto(ControlState.Run)

  state(ControlState.Run):
    on(stop).goto(ControlState.Idle)

  state(ControlState.Error):
    terminal()
```

Increment 13 compares concise alternatives for `fsm`, `state`, `entry`, `active`, `exit`, `on`, `goto`, `terminal`, `exclusive`, and `priority`. The final surface should remain readable without exposing graph-node/link plumbing.

## Flat FSM semantics

Increment 15 must freeze:

- current lexical `ClockDomain` capture;
- one explicit initial state;
- state-register reset and resetless behavior;
- no hidden boot state;
- whether entry actions run on reset, controlled by an explicit policy;
- active, entry, exit, and transition actions;
- default state hold;
- transition versus action assignment priority;
- exclusive transitions by default;
- explicit ordered `priority` region;
- terminal and complete states;
- current/next/entering/exiting/completion/status accessors;
- illegal-state policy;
- state enable, reset, and ordinary update priority;
- interaction with memories, protocols, side effects, and automatic-pipeline barriers.

## FSM encoding

Preferred candidates:

```text
FsmEncoding.Compact
FsmEncoding.OneHot
FsmEncoding.Gray
FsmEncoding.Custom
FsmEncoding.Auto
```

The encoding applies to local state storage, not the public enum ABI.

Freeze requirements:

- compact encoding is deterministic;
- one-hot generates legality checks and optional recovery;
- Gray encoding is accepted only if the reachable transition graph satisfies the declared one-bit-change contract;
- custom mapping covers every reachable state and has unique legal codes;
- `Auto` is explicit, locked, reproducible, and reported;
- externally visible state is converted to canonical enum encoding;
- internal implementation bits require an explicit debug/export operation;
- recoding after elaboration requires a declared optimization effect and proof obligation.

## Illegal-state policy

Candidates:

```text
IllegalState.Assert
IllegalState.Recover(initial)
IllegalState.Trap(errorState)
IllegalState.Unchecked
```

The final contract distinguishes simulation/formal checks from synthesized recovery hardware. `Unchecked` is explicit and cannot be the silent result of a missing backend capability.

## Reusable `FsmDef`

Directional definition:

```scala
def transfer(config: TransferConfig): FsmDef[TransferState] =
  fsmDef(initial = TransferState.Idle):
    // reusable states and transitions
```

Required semantics:

- immutable definition graph;
- explicit configuration and runtime bindings;
- no accidental capture of unrelated dynamic signals;
- deterministic stable IDs for definitions, instances, states, transitions, actions, and regions;
- multiple instances in the same or different domains;
- typed status and completion result;
- reusable `FsmFragment`-class contributions;
- separately compiled library definitions using public API only;
- plugin contributions only through declared append-only extension points before graph close;
- no mutation of an already elaborated machine.

## Hierarchical submachines

Directional candidate:

```scala
state(ControlState.Run):
  submachine(transfer(config))
    .onDone(ControlState.Idle)
    .onError(ControlState.Error)
```

Freeze requirements:

- child machine owns a typed state enum and completion result;
- activation, reset, suspend/resume, restart, cancellation, and completion policies are explicit;
- parent transition and child completion cannot race ambiguously;
- entry/exit order across hierarchy is deterministic;
- nested machine state is source-mapped and visible in reports;
- default lowering preserves nested machine boundaries;
- optional flattening is a separately verified pass.

## Parallel regions

Directional candidate:

```scala
parallel(join = Join.All)(
  receiveMachine(config),
  transmitMachine(config)
)
```

Initial join policies:

```text
Join.All
Join.Any
Join.First with explicit priority/tie behavior
```

Required checks:

- completion reachability;
- cancellation semantics;
- shared-output/multiple-driver conflicts;
- deadlock and starvation risks;
- domain compatibility;
- deterministic simultaneous-completion behavior;
- formal and simulation status/coverage.

## Timed and protocol-aware states

Directional candidates:

```scala
after(40.cycles).goto(ControlState.Error)
await(stream.fire).goto(ControlState.Run)
```

Generated counters, timeout state, and protocol dependencies are explicit in IR and reports. Helpers do not create clocks, perform implicit CDC, or sample analog values without an approved boundary.

## Structural and runtime recursion

The plan distinguishes four cases:

1. **Reusable nesting:** a definition instantiates another definition.
2. **Finite structural recursion:** Scala elaboration recursively builds a finite graph using a statically decreasing bound.
3. **Unbounded structural recursion:** rejected with a definition-cycle diagnostic.
4. **Runtime call/return recursion:** requires an explicit bounded procedure/call-stack contract.

Directional bounded procedure candidate:

```scala
val parser = fsmProcedure(
  initial = ParserState.Start,
  stackDepth = 4,
  overflow = StackOverflow.Trap(ParserState.Error)
):
  // call/return states
```

Increment 13 may decide whether this exact procedure spelling is frozen in v0.3 or reserved behind the same semantic contract for a later implementation. The architecture must still preserve explicit stack depth, return-state type, overflow/underflow behavior, reset, domain, source mapping, and proof model. Ordinary Scala recursion never implies an unbounded hardware stack.

## Graph verification

Compile-time/elaboration verification covers:

- missing/duplicate enum cases and states;
- unreachable/unenterable states;
- accidental dead ends;
- overlapping transitions outside `priority`;
- transition target outside region;
- missing initial state;
- multiple initial states;
- state/action multiple drivers;
- reset/action conflicts;
- incomplete submachine completion/cancellation paths;
- parallel join deadlocks;
- recursion cycles and bound violations;
- invalid encoding and Gray adjacency;
- illegal-state policy gaps;
- clock/reset and CDC/RDC violations;
- side-effect/pipeline movement violations;
- backend/profile representation failures.

## Reports and source maps

Required deterministic outputs:

- enum inventory and encoding manifest;
- enum ABI hash;
- FSM definition/instance/state/transition graph;
- local storage encoding map;
- initial/reset/illegal-state policy;
- hierarchy and parallel-region tree;
- generated counters and bounded stacks;
- unreachable/dead-end findings;
- state/transition coverage IDs;
- source locations and optimized-origin chains;
- optional DOT and JSON graph reports.

## Simulation and formal readiness

The compiler-generated support includes:

- typed enum forcing/reading with validity checks;
- state and transition names in traces;
- transition logging and coverage;
- legal-state and one-hot assertions;
- allowed-transition checks;
- reset convergence;
- no-unintended-deadlock checks;
- submachine and parallel completion covers;
- bounded-stack overflow/underflow checks;
- counterexample reconstruction after recoding.

Increment 67 owns compiler-generated formal readiness. User-authored state properties remain in the deferred formal API phase.

## Compile-positive matrix

Increment 13 must compile:

- default native Scala enum;
- explicitly encoded sparse opcode enum;
- enum ports, parameters, aggregate fields, vectors, memories, `Valid`, and `Stream`;
- enum register and exhaustive switch;
- safe decode and explicit unsafe decode;
- compact, one-hot, Gray-valid, custom, and explicit Auto FSM encodings;
- flat FSM with entry/active/exit/transition actions;
- explicit priority transitions;
- terminal/completion state;
- reusable `FsmDef` instantiated multiple times;
- nested submachine with typed completion;
- parallel all/any join;
- timed and protocol-aware state;
- finite elaboration-time recursive composition;
- bounded procedure/call stack candidate;
- enum/FSM external-library consumer.

## Compile-negative matrix

Required failures include:

- relying on Scala ordinal as hardware ABI;
- duplicate/missing/out-of-range enum encoding;
- implicit bits-to-enum or cross-enum cast;
- ignored validity for sparse decode;
- non-exhaustive switch without explicit invalid/default policy;
- duplicate state declaration;
- no or multiple initial states;
- overlapping transitions outside `priority`;
- transition to a foreign state;
- unreachable state under strict policy;
- accidental dead-end state;
- entry/exit/reset multiple driver;
- invalid one-hot/custom/Gray encoding;
- hidden boot timing assumption;
- incomplete submachine completion path;
- parallel join deadlock or ambiguous simultaneous completion;
- unbounded recursion;
- bounded stack without overflow policy;
- cross-domain submachine without explicit bridge;
- backend enum representation unsupported by profile.

## Freeze exit criteria

The unified v0.3 gate may freeze enum/FSM only when:

1. native Scala enum derivation is stable under separate compilation and external libraries;
2. enum semantic identity and canonical encoding are distinct and documented;
3. portable Verilog/localparam and future SystemVerilog enum mappings agree numerically;
4. safe decode and invalid-value behavior are explicit;
5. exhaustive switch diagnostics are source-located;
6. flat FSM reset, action, transition, and priority semantics are unambiguous;
7. reusable definition, nested, parallel, timed, and bounded-recursion candidates compile;
8. local FSM encoding cannot silently alter public enum ABI;
9. graph, encoding, source-map, simulation, and formal-readiness evidence is specified;
10. positive/negative fixtures and external-library consumer pass CI.

## Increment integration

- Increment 13: candidate compilation and architecture comparison.
- Increment 15: unified public API v0.3 freeze.
- Increment 19: enum and statechart IR.
- Increment 22: enum/FSM diagnostics.
- Increment 54: enum type and ABI implementation.
- Increment 56: enum registers, exhaustive switches, and flat FSM implementation.
- Increment 58: reusable hierarchical/parallel/timed/bounded-recursive FSM composition.
- Increment 65: portable Verilog lowering.
- Increment 67: compiler-generated formal readiness.
- Increment 72: Verilog-AMS lowering.
- Increment 99: future SystemVerilog native enum research/freeze.

## References

- Chisel enum documentation: <https://www.chisel-lang.org/docs/explanations/chisel-enum>
- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>
- SpinalHDL enum documentation: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>
- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>
