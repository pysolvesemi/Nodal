# ADR 0015: Use native Scala enums and typed reusable statecharts

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Digital enumerations, enum ABI/encoding, Verilog-family lowering, finite-state machines, hierarchy, reuse, bounded recursion, diagnostics, simulation, formal readiness, and source mapping

## Context

Nodal's frozen v0.1 API does not define an enumeration type, and the v0.3 roadmap mentions digital types and state machines without freezing their source API or representation contract. That leaves several important questions unresolved:

- whether enums are ordinary Scala values, hardware data, or both;
- how enum cases receive stable HDL values;
- whether an enum used at a module boundary has a stable ABI;
- how the same semantic enum maps to portable Verilog, future SystemVerilog, Verilog-A, and Verilog-AMS;
- how invalid encodings are detected and handled;
- whether FSM encoding is tied permanently to the public enum encoding;
- how state machines are reused, nested, composed in parallel, and verified;
- what "recursive FSM" means in finite hardware;
- how state names and transitions survive source mapping, waveforms, optimization, and formal verification.

Current Chisel recommends `ChiselEnum` plus `switch`/`is` for FSMs. `ChiselEnum` is a hardware `Data` type, supports explicit numeric values, safe decoding, validity checks, and enum fields inside aggregates. Current SpinalHDL provides `SpinalEnum` with sequential, one-hot, Gray, and custom encodings, plus a dedicated FSM library with entry/exit actions, delayed states, nested FSMs, and parallel FSMs.

Those are useful baselines, but Nodal can improve on them because it starts with Scala 3, an authoritative typed MLIR, explicit clock/reset domains, exact protocol semantics, source maps, a plugin architecture, a formal-readiness plan, and multiple Verilog-family backends.

## Decision

Nodal uses **native Scala 3 enums as the preferred semantic declaration**, derives a first-class hardware enum type, separates the enum's stable interface encoding from local FSM storage encoding, and represents reusable FSMs as typed statechart graphs.

The binding rule is:

> **Names define meaning, explicit encodings define ABI, typed statecharts define control, and every lowering preserves reviewable state identity.**

Exact Scala spellings are compile-tested in Increment 13 and frozen by the unified public API v0.3 gate in Increment 15. The examples below are directional candidates rather than already implemented APIs.

## Native Scala enum declaration

The preferred declaration uses Scala 3 syntax and derived Nodal metadata:

```scala
enum Mode derives HwEnum:
  case Idle, Read, Write, Error
```

The intended ergonomics are:

```scala
val requestMode = in(Mode.hw)
val currentMode = Reg(Mode.Idle)

when(currentMode === Mode.Read):
  // hardware behavior
```

`Mode.Idle` remains a normal Scala enum case for elaboration-time collections and pattern matching, and can also become a typed hardware literal in a Nodal expression context. The generated Scala `ordinal` is never the public HDL encoding contract.

The `Mode.hw` spelling is a preferred candidate implemented through Scala 3 enum mirrors and derived metadata. Increment 13 may select an equally short `EnumType[Mode]`-style fallback only if compile prototypes show that the mirror extension is ambiguous or fragile.

## Semantic enum type versus encoded representation

Each enum has a semantic identity consisting of:

- globally stable type identity derived from package and declaration;
- stable case identity derived from enum type plus case name;
- declaration source locations;
- ordered case inventory for reflection and diagnostics;
- optional aliases or deprecated cases only through an explicit compatibility contract.

Each enum also has a canonical interface encoding. The initial default is compact unsigned sequential encoding with a minimum storage width of one bit. Public or externally reused enums should use an explicit encoding whenever source reordering must not change their ABI.

Directional custom encoding:

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

The initial encoding policies are:

```text
Sequential
OneHot
Gray
Custom
```

For general enum ports, parameters, memories, and aggregates, the selected canonical encoding is part of the public type/ABI contract. Duplicate codes, codes outside the declared width, missing cases, and implicit width growth are errors.

## FSM storage encoding is separate

An FSM may store a semantic state enum using an implementation encoding different from the enum's canonical interface encoding:

```scala
val controller = fsm(
  initial = ControlState.Idle,
  encoding = FsmEncoding.OneHot
):
  // states and transitions
```

This separation allows a public enum to retain a compact stable ABI while a local state register uses one-hot, Gray, custom, or target-selected encoding. A state exported through a port is converted back to the enum's canonical interface encoding unless the user explicitly exports implementation bits.

Initial FSM encoding candidates are:

```text
Compact
OneHot
Gray
Custom
Auto
```

`Auto` is explicit, deterministic, target/profile aware, locked in build evidence, and never silently selected merely because a synthesis tool is installed. Gray encoding must be proven compatible with the transition graph; unlike a warning-only model, Nodal rejects a promised Gray/single-bit transition contract when reachable transitions violate it.

## Enum conversions and validity

Bit conversion is explicit:

```scala
val raw = mode.asBits
val decoded = Enum.decode[Mode](raw)

when(decoded.valid):
  currentMode := decoded.value
```

The exact decode-result spelling is frozen in Increment 15. The semantic requirements are:

- safe decode returns the typed value plus validity;
- unsafe decode is visibly named and cannot be introduced by an implicit cast;
- sparse/custom enums retain holes;
- `isValid`, `isOneOf`, case inventory, width, and encoded value inspection are available through typed APIs;
- enum-to-bits conversion retains source-map and enum metadata;
- bits-to-enum conversion requires validity handling, an explicit illegal-value policy, or a proof that the source is legal;
- equality and switches require the same enum type unless an explicit adapter is used.

## Exhaustive enum selection

Nodal should exploit Scala 3 macros and enum metadata to offer an exhaustive hardware selection form close to native pattern matching:

```scala
switch(mode):
  case Mode.Idle  => idleBehavior()
  case Mode.Read  => readBehavior()
  case Mode.Write => writeBehavior()
  case Mode.Error => errorBehavior()
```

Increment 13 compares this form with a less macro-heavy `switch`/`is` alternative. The frozen behavior must diagnose missing cases, duplicate cases, unreachable cases, invalid wildcard use, and type mismatch. An explicit default remains available for externally decoded invalid values, but it does not erase enum validity diagnostics.

## Backend lowering

SystemVerilog supports native enum types; portable Verilog does not. Nodal therefore preserves one semantic enum while selecting a target-specific representation.

### Portable Verilog

Enum members become non-overridable module-local constants and signals remain vectors:

```verilog
localparam [1:0] MODE_IDLE  = 2'd0;
localparam [1:0] MODE_READ  = 2'd1;
localparam [1:0] MODE_WRITE = 2'd2;
localparam [1:0] MODE_ERROR = 2'd3;

reg [1:0] current_mode;
```

`localparam`, not `parameter`, is the normal representation for enum members because users must not override the meaning of a case. When a module configuration value itself has enum type, that configuration remains an overrideable module `parameter` encoded as a vector/integer, while the legal member symbols remain `localparam`s.

### Future SystemVerilog

A SystemVerilog capability profile emits a typed enum with the same explicit values:

```systemverilog
typedef enum logic [1:0] {
  MODE_IDLE  = 2'd0,
  MODE_READ  = 2'd1,
  MODE_WRITE = 2'd2,
  MODE_ERROR = 2'd3
} mode_t;

mode_t current_mode;
```

Module-private enums may use local typedefs. Enums crossing module boundaries use a deterministic generated package or another separately gated profile strategy with an explicit compile-order manifest. A tool profile that cannot support packages falls back to typed metadata plus vectors rather than changing semantics.

### Verilog-A and Verilog-AMS

Portable Verilog-A/Verilog-AMS profiles emit integer/vector declarations and `localparam` case symbols according to context. Digital state inside Verilog-AMS uses vector/localparam encoding; analog mode parameters use integer-compatible encodings where the selected profile requires them. The sidecar manifest retains enum type, case, encoding, and source identity.

## Manual and high-level FSM layers

Nodal supports two layers built on the same enum and state-transition IR.

### Manual enum FSM

Users may always construct a register and exhaustive switch directly:

```scala
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

This remains useful for small machines and unusual control logic.

### Typed statechart FSM

The preferred reusable layer is concise and state-centric:

```scala
val controller = fsm(initial = ControlState.Idle):
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

Exact keywords are candidates. Required semantics are:

- one explicit initial state;
- state-active, entry, exit, and transition actions;
- explicit terminal/completion states;
- default hold behavior;
- deterministic reset and enable priority inherited from the current `ClockDomain`;
- mutually exclusive transitions by default;
- ordered transition priority only inside an explicit `priority` region;
- source-located diagnostics for overlap, omission, unreachable states, accidental dead ends, and multiple action drivers;
- typed access to current state, next state, entering, exiting, transition-taken, completion, and debug trace without a post-build escape.

## Reset and boot semantics

Nodal does not introduce a hidden boot state by default.

- The state register resets directly to the declared initial state.
- `entry` actions run on actual transitions into a state.
- Reset behavior uses an explicit `onReset` or entry-on-reset policy when required.
- A machine without reset follows the containing domain's explicit resetless-state rules.
- Illegal state handling is explicit through candidates such as assert, recover-to-initial, trap-to-state, or unchecked/don't-care.
- Reset, state enable, transition selection, and ordinary state actions have one frozen priority order.

## Reusable FSM definitions

A reusable machine is an immutable `FsmDef[S]`-class graph rather than a mutable live state-machine object:

```scala
def transfer(config: TransferConfig): FsmDef[TransferState] =
  fsmDef(initial = TransferState.Idle):
    // typed reusable definition
```

A definition:

- accepts explicit typed configuration and runtime bindings;
- cannot capture unrelated dynamic signals implicitly;
- can be instantiated multiple times in one or more clock domains;
- owns stable state, transition, action, and source IDs;
- may include reusable typed `FsmFragment`s;
- can expose typed completion results and status;
- can be contributed through a design plugin only via declared append-only extension points before the graph closes.

## Hierarchical, parallel, and recursive composition

A state may own a nested machine:

```scala
state(ControlState.Run):
  submachine(transfer(config))
    .onDone(ControlState.Idle)
    .onError(ControlState.Error)
```

Parallel regions are explicit:

```scala
parallel(join = Join.All)(
  receiveMachine(config),
  transmitMachine(config)
)
```

Initial join policies include all-complete, any-complete, and explicitly selected winner/priority behavior. Completion and cancellation are typed events, not hidden shared variables.

"Recursive FSM" is defined precisely:

- finite structural recursion performed by Scala elaboration is legal when it produces a finite acyclic machine-instance graph;
- reusable definitions may contain nested definitions and may be instantiated recursively only with a statically decreasing/bounded elaboration argument;
- cyclic unbounded structural recursion is rejected with the complete definition cycle;
- runtime call/return recursion is not synthesized from ordinary Scala recursion;
- a future/initially gated `FsmProcedure`-class contract may provide explicit bounded call/return using a declared stack depth, return state type, overflow policy, and formal/simulation model;
- no API implies an unbounded hardware stack.

This is more scalable than silently expanding recursive functions or relying on mutable nested FSM objects.

## Timed and protocol-aware states

The statechart layer may provide concise generated control structures such as:

```scala
after(40.cycles).goto(ControlState.Error)
await(stream.fire).goto(ControlState.Run)
```

Such helpers lower to explicit counters or protocol conditions in the current domain and appear in reports. They do not create hidden clocks, cross domains, or consume analog events without explicit sampling.

## FSM graph verification

Before HDL lowering, the compiler verifies:

- unique state and transition identity;
- exactly one initial state per region;
- enum/state coverage;
- unreachable and unenterable states;
- accidental terminal/dead-end states;
- overlapping transitions outside an explicit priority region;
- transitions to states outside the machine/region;
- action multiple drivers and inconsistent reset values;
- nested completion and cancellation paths;
- parallel join deadlocks and unreachable completion;
- structural recursion cycles and bounded-depth contracts;
- encoding width, one-hot legality, Gray adjacency, and custom-code uniqueness;
- illegal-state policy completeness;
- clock/reset/domain compatibility;
- forbidden CDC/RDC or analog/event capture;
- pipeline and effect barriers.

The compiler emits a deterministic graph/encoding manifest and optional DOT/JSON report with source locations.

## Optimization and lowering rules

The target-neutral IR retains semantic enum cases and statechart hierarchy until an approved lowering point.

- Flattening a hierarchical FSM is an explicit optimization pass, not a frontend side effect.
- The default readable lowering preserves machine boundaries and separate nested/parallel state registers where practical.
- Any flattening, state minimization, recoding, or retiming pass declares effects on state identity, encoding, latency, reset, debug/source maps, and formal obligations.
- Public enum ABI is preserved unless an explicit adapter or specialization contract changes it.
- State register recoding cannot alter externally visible enum values.
- Automatic pipelines cannot move logic across state-transition, entry/exit, completion, or action-effect barriers unless a separately verified transformation contract allows it.

## Simulation, debug, and formal readiness

Enum and FSM metadata supports:

- typed simulator reads/writes and legal-value checking;
- state names in waveforms and transaction logs;
- transition traces with source locations;
- coverage of states, transitions, entry/exit paths, terminal/completion paths, and illegal states;
- generated formal properties for legal encoding, one-hot invariants, allowed transitions, no unintended deadlock, completion, and reset convergence;
- counterexample reconstruction using semantic state names even after local recoding;
- stable debug exports without exposing internal compiler objects.

These compiler-generated properties belong to Increment 67's formal-readiness scope. User-authored FSM properties use the deferred ADR 0014 formal layer when implemented.

## Consequences

### Positive

- Native Scala 3 syntax provides IDE completion, refactoring, exhaustiveness, and reuse.
- One semantic enum maps cleanly to portable Verilog constants and future SystemVerilog native enums.
- Public enum ABI remains independent of local FSM performance encoding.
- Typed decoding prevents sparse/custom enum corruption from becoming silent.
- Small manual FSMs and large reusable hierarchical statecharts share one IR and backend path.
- Nested, parallel, timed, plugin-composed, and bounded-recursive control are explicit and analyzable.
- No hidden boot state or implicit transition priority obscures reset behavior.
- State identity survives optimization, simulation, formal verification, source maps, and waveforms.

### Costs

- Scala 3 enum derivation and macro-based exhaustive switches need compile-prototype validation.
- Enum ABI and local state encoding require two related but distinct metadata models.
- Hierarchical and parallel FSM verification is more complex than lowering a switch statement.
- Bounded runtime procedure/call-stack support requires explicit resources and proof contracts.
- SystemVerilog package/type emission must be separately capability-gated and compiled in deterministic order.

## Rejected alternatives

- **Use raw `UInt` constants only:** loses type safety, legal-value checking, source identity, and backend-native enum opportunities.
- **Use Scala `Enumeration`:** weaker Scala 3 typing and poor hardware literal/type integration.
- **Copy Chisel's or SpinalHDL's object/value API exactly:** misses native Scala 3 enum ergonomics and keeps semantics tied to implementation objects.
- **Make declaration ordinal the permanent ABI:** reordering source would silently alter external protocols.
- **Use `parameter` for enum members:** enum case meanings must not be overrideable; portable Verilog uses `localparam` for members.
- **Use one encoding for every instance:** prevents local one-hot/Gray optimization without changing public interfaces.
- **Let `Auto` depend on installed synthesis tools:** breaks reproducibility.
- **Permit overlapping transitions by source order silently:** hides priority bugs.
- **Introduce a hidden boot state:** changes externally visible reset/entry timing.
- **Flatten every nested FSM immediately:** destroys reusable hierarchy, debug identity, and compositional verification.
- **Allow unbounded recursive FSM calls:** finite hardware requires explicit bounds and storage.

## Follow-up increments

- Increment 13 compiles enum, encoding, decoding, exhaustive selection, flat FSM, reusable definition, hierarchy, parallel, timed, and bounded-recursion candidates.
- Increment 15 freezes the enum/FSM public API and migration rules as part of unified v0.3.
- Increment 19 adds enum/statechart/domain constructs to target-neutral MLIR.
- Increments 54 and 56 implement enum types, state registers, flat FSMs, diagnostics, and manual/high-level forms.
- Increment 58 implements reusable hierarchical/parallel FSM composition and bounded recursive/procedure contracts.
- Increment 65 emits portable Verilog `localparam` mappings and vector state.
- Increment 67 adds generated enum/FSM equivalence and formal-readiness properties.
- Increment 72 emits enum/FSM structures in Verilog-AMS.
- Increment 99 evaluates native SystemVerilog enum/package lowering in the future SystemVerilog-AMS/backend gate.

## References reviewed

- Chisel enums: <https://www.chisel-lang.org/docs/explanations/chisel-enum>
- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>
- SpinalHDL enums: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>
- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>
