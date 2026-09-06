# Sequential Scheduled Hardware v0.1 roadmap

**Track ID:** `sequential-scheduled-hardware`  
**Increment prefix:** `SQ`, starting independently at `SQ-001`  
**Revision:** 0.1  
**Created / updated:** 2026-09-06  
**Status:** Planned; all implementation blocked by Foundation  
**Foundation:** [`nodal-development-todo.md`](nodal-development-todo.md)  
**Readiness extension:** [`sequential-scheduled-hardware-foundation-readiness-v0.1.md`](sequential-scheduled-hardware-foundation-readiness-v0.1.md)  
**Track manifest:** [`sequential-scheduled-hardware-v0.1-surface.json`](sequential-scheduled-hardware-v0.1-surface.json)  
**Dependency registry:** [`dependent-track-gate-v0.1.json`](dependent-track-gate-v0.1.json)

## 1. Decision and scope

Add software-like sequential hardware construction to **Nodal**, not to MorphHDL
and not to a separate C/C++ compiler. The frontend remains lightweight Scala 3;
Nodal's typed hardware semantics and authoritative MLIR remain the foundation.
The new layer describes ordered values and effects, while a separate execution
contract determines combinational, pipelined or explicitly iterative hardware.

> Source order defines value/effect dependencies. Scheduling defines time.
> Neither statement order nor local-variable declaration means one clock cycle
> or one register per statement.

This is a new opt-in semantic region, not a change to ordinary RTL assignments,
`Reg`, structural connections, continuous analog equations or HVL execution.
Existing combinational `val` expression chains already express dependencies;
the distinctive additions are safe local reassignment, structured control,
composable timing constraints, automatic transaction alignment and later temporal
iteration. Do not market a wrapper around ordinary expressions as a complete
sequential language.

The initial useful release is pure, single-domain scheduled hardware. Stateful
loops, memory effects, communicating tasks and resource sharing follow separate
capability gates. The track does not promise arbitrary C/C++, pointers, dynamic
allocation, operating-system calls, unbounded recursion, implicit CDC, analog
pipelining, speculative side effects or automatic timing closure.

### Foundation relationship

Research and this roadmap update are allowed now. **No SQ implementation starts
until every Foundation item and SQF-001–004 in the readiness extension is complete.**
Within SQ, dependencies below are additional requirements, not substitutes for
the global barrier. Architecture gaps return to Foundation; implementations do
not become Foundation exit criteria. Historical gates, checked items and global
increment numbers remain unchanged.

Reuse Foundation 13–15 for semantic/API ownership and 59–64 for transaction IR,
fixed/valid/elastic pipelines, timing models, controls and schedule stability.
The existing [pipeline plan](automatic-pipeline-api-v0.3-plan.md),
[staged-loop plan](signed-loop-api-v0.3-plan.md),
[core semantics](core-semantics-api-v0.3-plan.md),
[shape/naming plan](shaped-values-naming-quality-v0.3-plan.md) and
[ExternalModule plan](external-module-integration-v0.1-plan.md) remain authoritative
for their current scopes. Extend them through versioned gates; do not build a
second scheduler, second memory model or competing stream protocol.

## 2. Small frontend, explicit semantic boundaries

All examples below are **API candidates, not executable examples or frozen
signatures**. Compile prototypes must settle exact Scala syntax, widths, imports,
overloads and diagnostics. Prefer a few orthogonal forms over dozens of synonyms.

| User need | Candidate direction | Important boundary |
| --- | --- | --- |
| Ordered local computation | `sequential { ... }` | Transaction-local values; no automatic persistent state. |
| Transaction scheduling | Existing `pipe(input, policy...) { x => ... }` | Reuse typed plain / `Valid` / `Stream` contracts. |
| Whole-live-frontier register cut | `cut()` | New candidate inside a sequential region; not a second `pipe()` constructor. |
| Value anchor / no-cut area | Existing `stage(value)` / `sameStage { ... }` | Validated hard constraints, not optimization suggestions. |
| Static repeated computation | Existing `hwRange`, `loop`, hardware collections | No hidden FSM or cycle per iteration. |
| Temporal algorithm | Later explicit `iterate(...) { ... }` | Separate latency, recurrence and state/effect contract. |
| Advanced scheduling | Immutable policy applied at call/scope level | Returns constraints; cannot mutate RTL or MLIR. |

Normal source should expose hardware values, not SSA node IDs, stage-link objects,
compiler contexts or scheduler implementation classes. Advanced APIs may expose
stable semantic operation handles through a versioned read-only view.

### Scala capture is an engineering gate, not magic

A by-name or inline Scala method does not by itself intercept arbitrary control
flow. SQ-001 must compare typed Scala 3 quote/macro capture with a small staged
builder and any compiler-assisted syntax that is actually justified. Capturing
`var` updates requires preserving the body before JVM execution destroys its
structure. Native Scala `if` requires a host `Boolean`; Nodal `Bool` is hardware.
No implicit `Bool -> Boolean`, source-text reconstruction, host loop execution on
dynamic bounds or broad promise of arbitrary Scala capture is permitted.

Keep typed hardware selection, for example `choose(predicate) { ... }.otherwise
{ ... }`, as a prototype fallback. Native-looking `if`/`match` is optional syntax
only after a working typed capture path and useful error messages exist. Ordinary
Scala elaboration remains ordinary Scala outside a captured region.

## 3. Ordered values without hidden state

Candidate reusable kernel:

```scala
def adjust(x: InputTxn) = sequential {
  val a = x.b + x.c
  val d = a * x.gain
  d + x.offset
}

val output = pipe(inputStream, latency = Latency.Exact(3)) { x =>
  adjust(x)
}
```

`d` uses the newly computed `a`. Independent expressions may execute in parallel
because source order is not a requirement to serialize pure hardware operations.
Without a scheduling wrapper or explicit cut, a pure sequential region is
combinational and has zero added sequential latency. A combinational region
cannot hide memory requests, persistent-state writes or waits.

Local mutation must have genuine value semantics:

```scala
sequential {
  var t = x.a
  val before = t
  t = x.replacement
  Pair(before, t)
}
```

`before` keeps the original value. Internally this becomes distinct SSA versions,
not a wire alias redirected to the final assignment. A read before definite
assignment is an error; every branch must establish the returned value. Local
variables have an explicit or inferred stable type/shape. Lossless arithmetic may
widen, so reassignment to a narrower local requires an explicit supported resize,
wrap, saturate or checked conversion. Software-like order does not import C
integer promotions, undefined overflow or unspecified evaluation order.

Struct/Vec local updates eventually use copy/value semantics, with field-sensitive
analysis. Heap aliases and external mutable Scala collections are not silently
turned into hardware state. A local unused pure computation can disappear; an
observable effect cannot be removed merely because its returned value is unused.

## 4. Pipeline cuts carry live dependencies, not every variable

```scala
val output = pipe(inputStream, latency = Latency.Exact(2)) { x =>
  sequential {
    val a = x.b + x.c
    val unused = x.debug ^ x.mask
    cut()
    val d = a * x.gain
    cut()
    Result(data = d + x.offset, tag = x.tag)
  }
}
```

Ignoring protocol control storage, the first cut carries `a`, `gain`, `offset`
and `tag`; the second carries `d`, `offset` and `tag`. The unused XOR and its
inputs need not survive. In particular, `gain`, `offset` and `tag` must stay with
the same input transaction even though some are first mentioned after a cut.

The contract requires field-sensitive liveness after legal simplification,
reconvergence balancing and predicate/sideband alignment. Constants and symbolic
parameters need not be registered. Copies of the same live value at the same
frontier may share storage. Disjoint lifetimes must not be time-multiplexed into
one register without an explicit legal resource-sharing transformation.

The report distinguishes payload storage, alignment storage, valid/occupancy
state, skids/buffers and persistent state. Liveness minimizes required transport;
it is not a promise of the globally minimum physical flip-flop count.

A lexical `cut()` is a hard frontier for the sequential region's continuation.
It does not freeze unrelated surrounding RTL. Branch-local cuts require explicit
path/merge semantics: balance reconverging pure paths and carry the predicate;
reject unsupported asymmetric control/effects rather than silently dropping or
moving a cut. Cuts inside loops are handled under that loop's selected execution
contract, not by treating all source iterations as one boundary.

### Input sampling is explicit

All dynamic transaction data enters through the typed input. Constants and
parameters may be captured directly; unrelated live wire/CSR capture is rejected.
A future configuration snapshot API samples declared settings at input acceptance
and transports them with the token. Configuration must remain associated with a
stalled token. Frame/packet-epoch snapshots require an explicit epoch boundary.
A later live-control read would need a distinct temporal contract; automatic
alignment must never guess whether the user intended entry-time or use-time data.

Memory reads are explicit operations under a memory contract, not an implicit
snapshot of the whole memory at transaction entry.

## 5. Time, throughput and protocol are separate contracts

Prefer existing `Latency.Exact(n)` / `Latency.Range(min,max)` over ambiguous
`stages = n`. Define whether the endpoint is input acceptance, result availability
or output transfer. Publish all automatically added interface/storage boundaries.
`cut()` introduces one required forward register boundary, while protocol storage
may add capacity or endpoint latency that must appear in the complete contract.

| Quantity | Meaning |
| --- | --- |
| Combinational depth | Logic between sequential boundaries; not a cycle count. |
| Scheduled / no-stall latency | Accepted input to result availability under the declared advancing/no-stall conditions. |
| Initiation interval (II) | Minimum permitted spacing between accepted inputs or started loop iterations; distinguish those two scopes. |
| Actual completion latency | Includes external backpressure, memory waits and variable-latency units. |
| Capacity | Number of in-flight transactions supported, independently of the number of arithmetic operations. |

Fixed-rate/valid-only exact latency follows the frozen domain-enable convention.
For an elastic stream, `Exact(n)` must never promise wall-clock completion under
arbitrary stalls: it constrains the scheduled/no-stall behavior and the report
also gives the stall-dependent contract. Output availability and downstream
acceptance are different events. An implementation that cannot satisfy a hard
latency/II constraint fails; it does not silently weaken the request.

Elastic register insertion moves payload, valid, predicates, tags and occupancy
consistently. It must retain stable output under stall, conserve tokens and avoid
combinational ready cycles. A shifted valid bit beside an independently advancing
payload is not a valid elastic pipeline implementation. Reset, flush, enable and
stall priority reuse the approved Foundation contract; output tokens are invalid
after reset until legitimately produced. Resetless payload storage is allowed
only under that validity contract. Cancellation, drain and flush are distinct.

## 6. Composition and scheduling at any legal level

The same kernel should accept different schedules without rewriting arithmetic:

```scala
val preview = pipe(inputStream, latency = Latency.Exact(2))(adjust)
val faster  = pipe(otherInput, target = 500.MHz, policy = dspPolicy)(adjust)
```

These are separate hardware instances. Ordinary transparent helpers do not become
register or scheduling boundaries. Preserve helper-local names and source paths;
scheduling can cross helper calls and nested transparent regions. A pipeline
policy may attach to an operation/tag, helper invocation, lexical region, loop
body, reduction-tree level or whole transparent composition.

“Any level” does not authorize crossing an opaque module, CDC/RDC, architectural
state, effect commit, exact-latency interface or explicit scheduling boundary.
A boundary alone does not insert a register; latency and movement restrictions
are separate. Existing published module latency remains part of its interface.

### Policy/callback contract

```scala
val dspPolicy = schedulePolicy { view =>
  view.operations
    .filter(op => op.isMultiply && op.resultWidth.provenGreaterThan(16))
    .map(op => CutAfter(op.id))
}
```

The callback runs at compile/scheduling time, not for each input sample. It sees
immutable typed operation/region handles, dependency/effect information, symbolic
widths, optional delay estimates, provenance and user tags. It returns a typed
constraint set; it never allocates hardware or mutates compiler nodes.

Use structural semantic tags/handles, not source line numbers, traversal IDs,
string matches on generated HDL or default parameter values. Symbolic predicates
must be proven across the declared envelope or rejected/left unconstrained under
an explicit policy. Match counts and unmatched required anchors are reported.

Hard local constraints survive composition. Hard caller/local conflicts are
errors with both source locations. Soft defaults may have explicit precedence;
there is no silent last-callback-wins behavior. Policy version/options, declared
inputs and results enter the schedule hash. Undeclared time, randomness, network
or mutable-global dependencies are unsupported; do not claim arbitrary callback
purity can be inferred from one execution. Core verifiers run after every policy.

## 7. Loops: one syntax family, explicit hardware mapping

Keep three existing categories unchanged: host elaboration, symbolic structural
`genRange`/`generate`, and bounded hardware `hwRange`/`loop`. Add temporal iteration
only through an explicit new execution contract. Never infer loop kind from its
body, a `Reg`, a callback or the mere presence of multiplication.

Candidate strict reduction:

```scala
sequential {
  var acc = SInt.literal(0, accWidth)
  for (i <- hwRange(taps)) {
    acc = (acc + x.samples(i).extend(accWidth)).resizeChecked(accWidth)
  }
  acc
}
```

`accWidth` must cover the legal sum range, or the explicit checked conversion must
have the frozen error contract. The iteration sees the previous iteration's new
local value. The base form does not mean one clock per iteration and does not
create persistent `Reg` state. Preserve symbolic `taps`, legal bounds, zero/one
cases and deterministic source/iteration identities.

| Mapping | Hardware interpretation | Admission rule |
| --- | --- | --- |
| Static bounded computation | Procedural loop or spatial operations under existing semantics | Finite static/symbolic bounds; no hidden temporal change. |
| Full/partial spatial unroll | Replicated operations, lanes and explicit remainder handling | Parameter, area, alias and arithmetic checks. |
| Iterative execution | Reused datapath with explicit FSM/loop-carried storage | User selects temporal execution and accepts its latency/II contract. |
| Pipelined iterations | Overlapping iterations with recurrence/resource scheduling | Proven recurrence, memory/effect and capacity legality. |
| Balanced reduction / prefix scan | Tree or parallel-prefix network | Algebraic and finite-width equivalence contract, not syntactic pattern matching alone. |

Policies should address a named loop or tree level without embedding low-level
stage bookkeeping in its arithmetic. Candidates include cuts every `k` iterations
of an explicitly spatial loop, per-level reduction callbacks, partial unrolling,
rolled/unrolled scheduling and explicit max-resource budgets. Their semantics,
including empty tails and symbolic latency, must be frozen before implementation.

### Loop-carried dependencies are not removable by adding registers

A recurrence with feedback latency `L` cycles and iteration distance `d` imposes
`II >= ceil(L / d)` for that recurrence; take all recurrences and resource/port
limits into account [R3]. For a single accumulator whose next state takes three
cycles and is needed by the immediately following iteration, `II=1` is not legal
without a separately justified transformation or independent contexts. Operator
latency and operator acceptance interval are distinct resource properties.

Do not silently create several accumulators or reassociate arithmetic merely to
claim II=1. Explicit context interleaving must define context identities, order,
storage and final combination semantics.

### Reassociation must preserve the numeric contract

`fold` preserves authored order. A balanced reduction requires a supported
associative operation with identity and compatible intermediate/result semantics,
or an explicit separately qualified relaxed numerical profile. Saturation,
floating-point rounding, checked overflow, mixed widths, side effects and some
four-state operations can invalidate an apparent algebraic rewrite. Nodal's
lossless default is not permission to change user-selected rounding or narrowing.

### Dynamic control is later and bounded

Later temporal `while`/`break`/`continue`/early return require typed loop bounds,
iteration identity, drain/cancellation behavior and a defined limit outcome.
An iteration cap bounds computation, not wall-clock time under indefinite stalls.
Separate watchdog timeouts from algorithmic iteration bounds. Unbounded runtime
loops are not admitted to the initial synthesizable contract.

## 8. Features that make the model more powerful

### A. Stateful streaming and scans

Separate four concepts: local SSA values, per-iteration carried storage,
persistent algorithm state, and externally visible effects. A streaming running
sum, CRC or IIR can read a committed state snapshot and propose a new state under
an explicit stateful contract. It is not expressed by mutating a captured RTL
register inside a supposedly pure pipeline.

The first stateful implementation permits one active state transition per owned
state context. Later overlap needs proven forwarding, recurrence scheduling or
explicitly independent contexts. Define the state linearization/commit point:
when subsequent transactions can observe the update, not merely when an output
happens to leave the pipeline. Cancellation cannot promise to undo committed
state. Cross-process shared mutable state requires explicit arbitration/ownership.

### B. Typed memories and scratchpads

Memory reads/writes carry port, latency, ordering, read-during-write, address
bounds and alias information. Begin conservatively: unknown aliases serialize or
fail a throughput request. Banking, replication, burst/tiling and read forwarding
are explicit verified transformations. `Vec` is not silently replaced by a RAM;
interface `ready` is not inferred from a guessed memory latency.

### C. External operators with real contracts

Reuse `ExternalOp` and its explicit binding to `ExternalModule`. A fixed-latency
multiplier, elastic divider or request/response service declares latency, II,
stallability, reset/flush, effects and executable-model availability. An instance
is not automatically a pure movable operation. A non-stallable unit needs enough
bounded response storage/credits before launch; downstream stalls must never lose
its results. Do not pipeline inside an opaque external body.

### D. Structured hardware tasks and fork/join

Provide later bounded hardware concurrency: independent regions run in parallel,
then explicitly join. Use typed bounded channels, invocation identity and defined
termination/cancellation. One child must not consume a shared input twice or pair
its output with another invocation. Lockstep joins, tagged joins, arbitration and
merge are different contracts. Diagnose combinational cycles and provable
capacity deadlocks; do not claim complete deadlock proofs for arbitrary networks.
These are synthesized processes, not Scala `Future`s or Nodal HVL threads.

### E. Streaming algebra with explicit cardinality

Support `map`, strict/balanced `reduce`, `fold`, `scan`, `zip`, `window`, packet/frame
boundaries and later `filter`/bounded expansion. Keep `pipe` one-input/one-output.
Filtering, replication, batching and expansion use separate operators with
zero/one/many output, completion and backpressure contracts. A sideband is aligned
by transaction/packet identity, not by inserting a guessed constant delay after
a variable-cardinality transform.

Window/line-buffer, FIR, CRC and pixel-processing examples demonstrate the API;
optional reusable algorithms belong in libraries using public contracts only.
Compiler semantics and protocol machinery remain in core. Do not infer an image
boundary policy or create a special compiler path for a display-controller block.

### F. Exactly-once effects and explicit cancellation limits

Ordered memory writes, state commits and external sends need effect tokens and a
specified commit event. Stalls cannot repeat an accepted side effect. Flush may
kill uncommitted work, drain committed work or return a typed cancellation result
according to contract. No rollback is promised after an irreversible external
operation. Multiple external writes are not automatically atomic; an atomic
transaction requires an actual capable endpoint/protocol or explicit buffering
and a defined commit scheme.

Errors such as bounds failure, arithmetic checks or iteration limits return a
typed error/result or follow a selected containment policy. Do not synthesize
arbitrary host exceptions or silently discard failed transactions.

### G. Explainable schedules and portable kernels

A report answers: which values were registered, why, how many bits, which source
operation caused a cut, what constrains II, and which requirement made scheduling
impossible. Preserve helper names, aliases, branch/loop identities and mapping to
emitted registers/state. Show latency, II, capacity, critical estimated paths and
resource assumptions separately.

A kernel can be schedule-polymorphic across call sites without becoming a
clone-per-default-parameter module. Offer explicit schedule locking/diffing for
stable IP delivery. Parameterized widths/shapes remain symbolic; one conservative
schedule must cover the declared finite envelope. Schedule changes that require
structurally different parameter cases or symbolic latency require an explicit
supported contract, not hidden specialization or silent default-value unrolling.

Optional offline design-space exploration can compare legal latency/II/resource
profiles and retain reports. It is an explicit tool action, never automatic work
triggered by declaring a kernel. Estimated timing is not physical sign-off.

## 9. Timing feasibility and compiler structure

The proposed lowering is:

```text
Typed captured source / small construction model
  -> authoritative Nodal MLIR sequential regions and structured control
  -> SSA values + branch/loop carries + transaction/state/effect provenance
  -> existing pipeline/iteration scheduling interfaces and immutable constraints
  -> verified scheduled regions and explicit transport/control/state
  -> existing Nodal digital lowering / selected CIRCT hw, comb, seq, sv
  -> portable Verilog; other supported Verilog-family capability profiles
```

Unscheduled and scheduled representations are stages of one authoritative MLIR
flow, not independent Scala and native semantic implementations. Selective reuse
of MLIR structured control and CIRCT scheduling is subject to semantic matching
[R2, R4]; do not force arbitrary effects or fully elastic protocols into a dialect
that does not model them. Continuous analog equations never enter this scheduler.

Pure scheduling preserves data/control dependencies. Effectful scheduling also
preserves ordered effect dependencies and state commit semantics. Each lowering
must retain enough evidence for mandatory verification before emission.

A timing model is versioned and target/envelope specific. Include operator delay,
latency, acceptance interval, resource occupancy and interface/setup/margin costs
where the model supports them. Missing estimates are unknown, not zero. Without
a model, explicit cuts or a declared abstract cost policy can produce hardware
but cannot support a frequency claim.

A 1.4 ns indivisible combinational operation cannot meet a 1 ns stage budget merely
by placing a register before/after it. The implementation must choose an available
legally internally pipelined operator, explicitly decompose it with a proved
contract, relax the target, or fail. More register boundaries do not split an
opaque operation. Timing-driven rescheduling and arithmetic restructuring are
separate opt-in transformations with separate evidence.

## 10. Verification and acceptance model

The reference is the **unscheduled sequential value/effect semantics**, not a
second implementation of the scheduling pass. Use an independent finite-width
interpreter/reference model and per-transform checks. Compare transaction traces
at declared input/output/effect-commit boundaries. Fixed latency uses cycle-aware
comparison; elastic/iterative designs allow declared stalls but must preserve
values, order, cardinality and effects. Stuttering equivalence alone does not
prove performance or eventual progress; liveness claims state fairness and bound
assumptions explicitly.

Every implementation increment requires relevant positive and negative fixtures,
source-correlated diagnostics, retained IR/RTL/report artifacts and existing
repository gates. Use Foundation's direct internal verification infrastructure;
SQ does not require the complete dependent HVL/UVM track merely to test hardware.
A later optional HVL integration must not create a circular dependency.

Required fixture families include:

- local overwrite/snapshot, shadowing, helper capture, branch assignment and
  dead computation; reject host mutation, live captures and escaping local values;
- mixed widths/signs, checked narrowing, parameter boundaries, zero/one/odd loop
  counts, nested Struct/Vec fields and helpers with symbolic shapes;
- multiple cuts, late-used inputs, reconvergent branches, predicates/tags, empty
  stages, conflicting anchors and policy determinism across repeated builds;
- bubbles, random backpressure, pipeline fill/drain, reset/flush during stall,
  ready-path loops, non-stallable external-unit responses and configuration epochs;
- recurrence-limited II, unroll remainders, port conflicts, aliases,
  read-during-write, shared resource occupancy, state visibility and exactly-once
  effects under retry/stall/cancel;
- fixed versus variable latency, filtering/expansion cardinality, bounded runtime
  exits, deadlock counterexamples and unsupported-capability rejection;
- public-library-only consumers, deterministic readable names, one symbolic
  module per structure, source/report correlation and target capability failures.

For supported digital profiles retain independent Verilator/Icarus simulation,
Yosys synthesis and applicable bounded/unbounded formal evidence through the
repository's pinned flows. Declare two-/four-state scope; do not count unsupported,
unknown, skipped or timed-out work as passed. Large-state formal limitations must
be reported honestly alongside the tested/proven subset.

## 11. Increment plan

All checkboxes are open. All increments inherit the full Foundation barrier and
the readiness supplement. Dependencies are explicit; row order is not a substitute.
SQ-001–010 form the first useful release. SQ-011–024 are separately gated expansion,
not prerequisites for using the pure sequential layer after SQ-010 qualifies.

### Release A — ordered pure computation and flexible pipelines

- [ ] **SQ-001 — Semantic/API gate and capture feasibility**
  - Depends on complete Foundation, including SQF-001–004.
  - Freeze versioned semantic rules, minimal spellings, Scala capture/fallback,
    diagnostics, positive/negative compile prototypes and external-library usage.
  - Exit: approved new/amended design gate and a manifest distinguishing candidate,
    unsupported and supported syntax; no claim that all Scala is synthesizable.

- [ ] **SQ-002 — Sequential locals and authoritative SSA lowering**
  - Depends on SQ-001. Capture local binds/reassignments/snapshots, definite
    assignment, explicit conversions and source identities into Nodal MLIR.
  - Exit: independent reference parity for pure combinational kernels, no accidental
    persistent state, and negative host-effect/escaping-local fixtures.

- [ ] **SQ-003 — Branches, aggregate updates and joins**
  - Depends on SQ-002. Implement supported typed conditions/matches, branch result
    merges and value-semantic Struct/Vec updates; preserve arithmetic and shape.
  - Exit: exhaustive/incomplete branch cases, old/new aggregate snapshots and
    signed/width/alias diagnostics, including unsupported native Scala forms.

- [ ] **SQ-004 — Hard cuts, liveness and fixed/valid alignment**
  - Depends on SQ-003. Lower whole-frontier cuts and existing value/same-stage
    anchors through Foundation pipeline infrastructure, with field-sensitive
    liveness and all late data/control/sideband inputs aligned.
  - Exit: exact latency and reset/enable convention proven for fixed/valid
    examples; reports account for every inserted transport register.

- [ ] **SQ-005 — Elastic sequential regions and lifecycle**
  - Depends on SQ-004. Integrate existing elastic stages, readiness, capacity,
    bubbles, reset, flush and drain without an independent protocol engine.
  - Exit: stalled-output stability, conservation/order, no duplicate tokens and
    simultaneous reset/stall/flush corner cases pass the applicable checks.

- [ ] **SQ-006 — Helpers, higher-order kernels and nested scopes**
  - Depends on SQ-004. Capture certified helper/callback bodies and explicit
    captures; schedule across transparent calls while preserving local names.
  - Exit: nested constraints and opaque/latency/domain barriers compose correctly;
    helper abstraction does not force registers or lose source provenance.

- [ ] **SQ-007 — Declarative schedule policies and stable anchors**
  - Depends on SQ-006. Add immutable scheduling views, validated decisions,
    semantic selectors, hard/soft precedence and reproducibility metadata.
  - Exit: illegal mutation, unmatched required anchors, symbolic-width ambiguity
    and conflicting nested constraints produce stable diagnostics.

- [ ] **SQ-008 — Symbolic bounded loops, folds and scans**
  - Depends on SQ-003, SQ-004 and SQ-006. Support strict loop carries and nested
    static/symbolic hardware iteration; integrate qualified balanced reductions
    and per-level hooks rather than a duplicate reduction framework.
  - Exit: zero/one/odd/boundary counts, widths/shapes and cross-cut carries preserve
    semantics and parameter identity; illegal reassociation is rejected.

- [ ] **SQ-009 — Timing/envelope-aware scheduling and bounded retiming**
  - Depends on SQ-007 and SQ-008. Reuse timing models and register ownership;
    schedule within exact/ranged latency and parameter-envelope contracts.
  - Exit: infeasible indivisible operators, absent models and forbidden boundary
    movement fail explicitly; all estimated timing claims retain model provenance.

- [ ] **SQ-010 — Release A qualification and user documentation**
  - Depends on SQ-005 and SQ-009. Qualify independent semantics/RTL comparison,
    backpressure/formal fixtures, portable Verilog and readable deterministic
    parameterized output. Publish small examples and a capability matrix.
  - Exit: documented supported subset, no ordinary-RTL regression, approved
    release evidence and no requirement to wait for later HLS-like features.

### Release B — explicit iteration, state and hardware services

- [ ] **SQ-011 — Explicit temporal iteration and bounded FSM lowering**
  - Depends on SQ-010. Gate the iterative API, invocation lifecycle, loop counters,
    loop-carried storage, completion and exact/bounded latency terminology.
  - Exit: rolled versus spatial implementations match the same logical algorithm;
    `hwRange` outside explicit temporal execution retains its original meaning.

- [ ] **SQ-012 — Persistent state, recurrence and visibility**
  - Depends on SQ-011. Implement owned state contexts, state snapshot/update,
    declared linearization, reset and serialized state transitions first.
  - Exit: running-sum/CRC fixtures and cancellation cases show exactly when state
    changes; unsafe overlapping state use or implicit captured Reg writes fail.

- [ ] **SQ-013 — Pipelined loops and partial unrolling**
  - Depends on SQ-012 and SQ-009. Implement recurrence/resource bounds, prologue,
    steady-state/epilogue, iteration tags, explicit partial unroll and tails.
  - Exit: achieved versus requested II is reported; impossible hard II fails;
    no hidden reassociation or independent-context substitution occurs.

- [ ] **SQ-014 — Memory ports, aliases and scratchpad scheduling**
  - Depends on SQ-011 and SQ-012. Bind the existing memory model; implement typed
    requests, latency/order, bounds, alias checking and conservative port use.
  - Exit: read/write hazards and stall behavior match the memory contract;
    unknown aliasing cannot be waved away to achieve throughput.

- [ ] **SQ-015 — Fixed and variable-latency ExternalOp integration**
  - Depends on SQ-005 and SQ-011. Bind explicit ExternalModule implementations,
    operator latency/II, non-stallable response capacity and request identity.
  - Exit: delayed/outstanding results, reset/cancel and missing model failures are
    covered; a declared module alone is not counted as an executing model.

- [ ] **SQ-016 — Structured parallel regions and bounded channels**
  - Depends on SQ-005, SQ-006 and SQ-011. Add bounded fork/join, explicit channel
    ownership, lockstep/tagged joins, merge/arbitration and progress contracts.
  - Exit: no cross-invocation pairing, bounded storage and useful cycle/deadlock
    diagnostics; arbitrary HVL or host concurrency is not accepted implicitly.

- [ ] **SQ-017 — Explicit resource sharing and binding**
  - Depends on SQ-013, SQ-014 and SQ-015. Add resource budgets, operator occupancy,
    deterministic arbitration/binding and spill/storage cost reporting.
  - Exit: one-versus-many-unit examples preserve semantics and report changed
    latency/II; an unmet resource budget is never silently exceeded.

- [ ] **SQ-018 — Bounded dynamic loops and structured exits**
  - Depends on SQ-011 and SQ-012. Gate runtime predicates, break/continue/early
    return, maximum iterations, limit results and in-flight drain behavior.
  - Exit: all exits conserve transactions/effects; wall-clock timeouts and loop
    bounds remain distinct; unbounded synthesizable execution remains rejected.

- [ ] **SQ-019 — Ordered effects, cancellation and configuration epochs**
  - Depends on SQ-012, SQ-014, SQ-015 and SQ-018. Add explicit commit barriers,
    effect ordering, exactly-once acceptance and configuration snapshot epochs.
  - Exit: stalls/retries/flush never duplicate writes; irrevocable operations and
    unsupported multi-endpoint atomicity are diagnosed/documented honestly.

### Release C — reusable algorithm families and scalable optimization

- [ ] **SQ-020 — Streaming algebra, windows and cardinality contracts**
  - Depends on SQ-013, SQ-016, SQ-018 and SQ-019. Compose map/scan/window/batch,
    strict/qualified reductions, filter and bounded expansion on explicit channels.
  - Exit: packet/frame metadata, completion and backpressure remain correct through
    rate changes; examples use only public core contracts.

- [ ] **SQ-021 — Schedule-polymorphic libraries and interface contracts**
  - Depends on SQ-006, SQ-007, SQ-009 and SQ-010. Package reusable kernels with
    transparent/opaque boundaries, caller policies, stable ABI/latency and symbolic
    parameter contracts; gate any symbolic-latency/structural schedule extension.
  - Exit: alternate caller schedules do not mutate the kernel or force unwanted
    module cloning; optional libraries remain independent of compiler internals.

- [ ] **SQ-022 — Verified loop/dataflow transformations**
  - Depends on SQ-008, SQ-013, SQ-014 and SQ-017. Add individually gated fusion,
    fission, interchange, tiling, banking, explicit context interleaving and lawful
    reassociation only where dependence/numeric/effect proofs allow them.
  - Exit: every enabled transformation retains legality/equivalence evidence and
    unsupported cases fail or remain unchanged under an explicitly soft policy.

- [ ] **SQ-023 — Schedule explanation, locking and exploration**
  - Depends on SQ-007, SQ-009 and SQ-010. Extend baseline reports with source-level
    register/latency/II explanations, constraint conflict sets, schedule diffs,
    explicit locked schedules and optional reproducible design-space sweeps.
  - Exit: repeated builds reproduce schedules; changed tool/model/policy/envelope
    invalidates the correct evidence; no synthesis estimate is called sign-off.

- [ ] **SQ-024 — Full-track qualification and capability release**
  - Depends on SQ-010, SQ-017, SQ-019, SQ-020, SQ-021, SQ-022 and SQ-023.
  - Qualify representative pixel arithmetic, FIR/reduction, CRC/scan, iterative
    arithmetic, memory processing and task networks through supported tool profiles.
  - Exit: publish evidence and performance/resource tradeoffs, known limitations,
    migration policy and traceability; unsupported targets/features are not passed.

## 12. Research basis and non-dependencies

These primary references informed the design; they are not dependencies or claims
that Nodal currently implements the referenced systems. Accessed 2026-09-06.

- **[R1] [XLS pipeline scheduling](https://google.github.io/xls/scheduling/):**
  useful separation of timing, dependencies and register-lifetime cost. Adopt the
  principle of carrying only live values and reporting competing objectives,
  without importing XLS as Nodal's semantic engine.
- **[R2] [CIRCT pipeline dialect](https://circt.llvm.org/docs/Dialects/Pipeline/):**
  useful scheduled/unscheduled and latency operations. Its documented II=1 and
  external-input/stall behavior require explicit compatibility checks; it is not
  automatically a full match for Nodal's transaction capture or elastic semantics.
- **[R3] [AMD loop pipelining](https://docs.amd.com/r/en-US/ug1399-vitis-hls/Pipelining-Loops):**
  dependency and initiation-interval constraints motivate explicit recurrence and
  throughput contracts. Nodal need not inherit tool-specific loop defaults or
  silently relax a hard II request.
- **[R4] [MLIR structured control flow](https://mlir.llvm.org/docs/Dialects/SCFDialect/):**
  reference for structured loops, carried values and control lowering; hardware
  time, finite-width rules and transaction effects remain Nodal contracts.
- **[R5] [Calyx static timing](https://docs.calyxir.org/lang/static.html):**
  useful distinction between explicit static latency and dynamic control, plus
  compositional sequential/parallel reasoning. Do not equate sequential value
  dependencies with one hardware cycle per statement.

## Documentation-only change record

The 2026-09-06 change defines this independent track and Foundation readiness
checkpoints, registers them and adds navigation. It changes no compiler source,
tests, workflows or frozen public API and closes no implementation checkbox.
The user authorized direct commit/push and no CI for this roadmap update.
Implementation increments still require the normal approved design gates,
applicable tests and repository validation. No new simulation, formal, synthesis,
timing or CI result is claimed by this document.
