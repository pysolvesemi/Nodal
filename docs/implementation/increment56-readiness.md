# Increment 56 readiness and implementation

Status: scoped implementation and focused acceptance checks complete; A–F and
F-056.B.1.1 are complete. G and the parent await verified integration.
Base: `b0fa301533b52bc3e0f150c54d3af498ced3f3b5`, the verified F55 documentation
closure after PR151. Branch: `increment/56-sequential-fsm`.

## Existing owners and required work

F54 already retains exact typed register declarations, initializer edges and
register-next operands. F55 checks their consumed values while keeping registers
outside combinational driver cycles. These are intent/declaration checks; they do
not execute clocked updates. Existing F19 FSM transition/action strings and graph
checks likewise do not implement executable state machines. Memory declarations
are typed, while candidate read/write operations still lack owned executable ports.

The first checkpoint uses the approved
[sequential-state gate](../design-gates/NodalSequentialState-DG-v0.1.md): actual
local clock/reset bindings, owned behavioral chains, register update roots and
reset/hold/enable/priority normalization. Source, frontend, bridge and native
owners reuse canonical value/region/type/static-predicate machinery. Guard
parameters retain static provenance without classifying state existence as
topology-changing. No old descriptor-only fixture is counted as execution proof.

The original post-register obligations were:

- exhaustive action switch and manual enum state;
- flat FSM construction, entry/active/exit/transition actions and observations;
- terminal/completion, exclusive/priority transitions and graph diagnostics;
- deterministic compact/one-hot/Gray/custom/Auto local encoding, separate from
  the external canonical enum ABI, and actual illegal-state handling;
- owned typed memory ports and policies;
- actual approved sequential lowering through Nodal/CIRCT, focused source/IR
  demonstration, affected tests and downstream target handoff cases.

Missing frozen public forms receive additive versioned decisions before use.
Full F57 CDC/RDC/reset controllers, F58 reusable/hierarchical/timed statecharts and
later Verilog/AMS consumers are not reverse prerequisites. A directly required
local binding or guard repair is implemented in its canonical owner; unsupported
execution cannot silently use fake clocks, constants or discarded effects.

## Validation and architecture plan

Focused positives/negatives/boundaries and affected predecessors cover each
implemented checkpoint. In particular, preserve the older combinational test
that excludes register-next from physical writers while adding the separate
sequential multiple-root rejection. Compare actual lowered source/IR, including
reset/enable order and guards at more than a default parameter value. Retain raw
commands/results under `.work/increment56`; no CI or broad qualification.

The first checkpoint architecture review confirms one sequential owner, shared
expression/capture machinery, exact type/enum ABI checks, transactional mutation,
explicit resource bounds and deterministic identities. Future FSM local encoding
will remain separate from the external ABI. The measured bounded observations
below do not establish whole-pipeline complexity or downstream execution. All
original checklist children were still open at that first checkpoint; the later
FSM/CIRCT checkpoint completes B.1 and B.3, while the parent remains open.

## First checkpoint implementation and focused acceptance

The implementation now retains physical local bindings, binary behavioral arms
with explicit capture/block-argument edges, and a shared static-predicate-to-Bool
conversion. Native normalization produces actual priority selections and clocked
updates with hold fallback and explicit reset operands. It does not claim target
HDL emission or completion of the remaining flat-FSM/memory work.

Direct scalar input Clock/Reset bindings must have unambiguous local-domain
provenance. External domains receive actual compiler-owned typed boundary inputs.
Projected capability descriptors retain source compatibility, but unsupported
execution rejects before output. Shared-pin multi-domain aliases retain source
construction only: the existing bridge cannot render an ambiguous physical port
as though it had one exact domain. Async-release structure remains with F57.

Known domain provenance is checked through the existing physical dependency graph,
including ordinary wire drivers, readable Interface member/resolved-component
facts and explicit domain requirements. A requirement must resolve through one
actual direct binding to the local domain. The established unresolved `@none`
input sentinel remains domainless; a real symbol named `none` is constrained like
any other symbol. Source and native ingress coverage are reported separately.

Two shared construction repairs were required. Static-bound proof queries no
longer classify a parameter as topology-changing merely by inspecting its bounds;
actual structural consumers still perform that classification. Semantic-origin
constructor lookup now shares the existing per-construction source-resolution
cache, including misses. The bounded chain probe had identified repeated file-tree
walks in the previously uncached path. The repair preserves repository-root
`.work` exclusion, explicit external roots, ambiguous/unavailable spans and cold
capture identities. Nested else-arm capture transport still grows quadratically;
no whole-pipeline linearity or general speedup is claimed.

A predecessor harness expected an instrumented empty Unit helper to be unavailable
although the existing summary protocol attests it. The original failure and a
focused b0fa3015-plugin/original-helper/original-consumer control against the current
API are retained. The attested helper is now a positive; the exact UNAVAILABLE
negative uses a genuinely uninstrumented same-shape helper. This is not a full
historical-tree replay or retroactive acceptance claim.

Unchanged F12 DomainState/ClockedRegister and F15 migration fixtures compile with
the new plugin/API. The broader unchanged F15 UnifiedV03 fixture rejects its
separate bounded-loop `Seq.foreach` helper with
`NODAL-ITERATION-CALLBACK-DISPATCH`; the same focused old-plugin/current-API control
reproduces it. The fixture remains unchanged and is not counted as passed. This
checkpoint does not broaden the callback effect profile to hide that limitation.


### Focused checkpoint evidence

The integrated maintained replay passed 47 cases: 20 sequential source capture,
12 bridge/native integration, 10 F55 structural-if and five analog event review
cases. A separate exact 17-case selection covers semantic-origin, source-discovery,
analog bridge and selected capture predecessors. The frontend harness passed 26
exact negatives plus public/separate-JAR/runtime positives. The final escaped-local
analog helper repair passes these integrated replays and the maintained frontend
harness. A same-API prior-plugin causal control fails precisely because its
escaped predicate executes before context rejection; the final plugin passes
both local-definition and closure escape negatives with zero predicate/body
effects, plus the unchanged valid nested lexical order.

Native release validation passed 71 sequential cases, 79 capability cases and six
affected predecessor suites, with their test executables relinked against the
current library. Five retained source/trusted documents normalize and round-trip
byte-identically; the projected-capability case remains descriptor-only. Explicit
`NODAL_NODALC` configuration makes the maintained public bridge test execute the
native compiler rather than silently skip it. Exact commands and raw logs are in
`.work/increment56/live/`.

The native limits are 65,536 represented operations/dependency work, 128 lexical
depth, 8,192 captures and 262,144 normalized operations. Bridge arm transport
retains its existing 4,098-argument bound. A 129-register native witness took
38 ms in the integrated release build (the earlier isolated unoptimized build
took 523 ms; these are different build settings, not a speedup comparison). Source chains of 32/64/128 arms took 379/881/3005 ms and retained
559/2143/8383 explicit capture arguments in the recorded run. These are bounded
observations, not whole-pipeline complexity guarantees.

The shared native review caught foreign domain requirements and Interface-member
sampling whose domain provenance was lost at a graph root. The repaired physical
dependency query carries actual verified domain facts; focused controls retain
truly unannotated inputs and reject foreign, unresolved or ambiguous constraints.
Other causal repairs include zero-operand malformed-IR diagnostics and whole-module
normalization transactionality. Review was read-only and is not an additional test
execution claim.

### Public source and actual normalized result

The maintained public fixture
`core/scala/testkit/test/src/nodal_external/Increment56SequentialBridgeSource.scala`
uses an actual synchronous input clock/reset domain and an owned Integer parameter:

```scala
core:
  val state = Reg(0.U(8))
  when(MODE == 0):
    state := input
    when(enable):
      state := ~input
  elsewhen(enable):
    val choice = if MODE == 1 then input else ~input
    state := choice
  otherwise:
    state := 0.U(8)
  output := state
```

Its actual normalized document contains nested `nodal.sequential_select` values,
retains the pure `nodal.structural_if` value for `choice`, and ends in
`nodal.sequential_update` with the actual state, selected next value, Clock, Reset
and initializer operands. The update carries `edge = "rising"`,
`reset_policy = "sync"` and `reset_polarity = "active_high"`. Its output still
reads the register, rather than the next-state expression. Parameter comparisons
remain explicit; no parameter default selects an elaboration arm. Full emitted
and normalized documents and exact commands are retained under
`.work/increment56/bridge-stage/build/` and the integrated replay directory.
This establishes Nodal sequential normalization; CIRCT and HDL output remain
separate required follow-on work.


Integrated compilation exposed two additional trust/compatibility obligations.
The new behavioral kernel entrypoint now owns the callback-carrier check and
transaction poisoning, so direct unattested calls fail before executing their
body; the frontend separately rejects authored calls to compiler-owned entrypoints.
Trusted malformed-graph tests use an explicitly reflective test adapter and are
kept distinct from the unchanged public plugin-compiled witness. Existing analog
procedure uses of shared `when`/`elsewhen`/`otherwise` retain their prior lexical
block construction through the actual analog procedure owner. They do not acquire
digital priority semantics. The initial failed integrated compile is retained;
the unchanged affected analog controls pass in the integrated replay. The final
escaped-helper repair also checks active procedure ownership before any eager
predicate effect, even when the helper was defined lexically inside an analog
procedure. Context-free host-mutating helpers without actual analog identity
remain rejected; a failed digital check never chooses analog semantics.

The five new operations are registered as digital behavior in the shared backend
capability catalog, with unsupported analog admission. Generated consumer profile
hashes track that catalog change; no downstream consumer capability is upgraded.

### Reproduction and remaining acceptance

The final maintained command uses the configured native release binary:

```sh
source .work/scala-validation/env.sh
export NODAL_NODALC="$PWD/.work/increment56/live/bin/nodalc"
.work/scala-validation/mill-jvm.sh --jobs 1 core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment56SequentialCaptureTests \
  nodal.internal.testkit.Increment56SequentialBridgeTests \
  nodal.internal.testkit.Increment55StructuralIfTests \
  nodal.internal.testkit.AnalogEventReviewTests
```

The final 47-case log is `live/logs/maintained-tests-escape-final.log`; exact
17-case predecessor selectors and their runner are retained alongside it. The
frontend maintained harness is `tests/scala/behavioral-guards/run.py`, with final
run `frontend-stage/maintained-escape-boundary/run-yp8e_mks`. Native binary SHA256:
`290691113dffe59c851ff8d1c845bf1f7c9305037926d5acba894bf7bf11b6d2`.
All scratch references above are relative to `.work/increment56/` unless their
repository path is explicit. Generated witnesses/logs are not committed.

Strict owner compilation and formatting passed. The source lint comparison
retains nine unchanged baseline return/throw violations and introduces none; it
is not a claim that the whole existing lint suite passes. Focused Markdown,
backend-registry regeneration and whitespace checks pass. No CI was run.
At this first checkpoint, the remaining FSM, switch, encoding, memory-port and
approved CIRCT features prevented F56 completion. The subsequent checkpoint below
implements FSM/switch/encoding and the finite CIRCT adapter; memory remains open.

## Subsequent checkpoint contracts

[Gate v0.2](../design-gates/NodalSequentialState-DG-v0.2.md) freezes exhaustive
action switching and flat-FSM capture/verification/normalization. It preserves
frozen Unit-returning APIs, adds a scoped switch and typed custom local encoding,
and records the real exhaustive-host-match compatibility change for Custom.
Typed switch inventory reaches native validation before behavioral normalization;
FSM actions reuse the sequential root rather than a second scheduler.

[Gate v0.3](../design-gates/NodalSequentialState-DG-v0.3.md) selects the finite
whole-module fixed-scalar register/flat-FSM CIRCT profile, with actual feedback,
synchronous reset and owning-clock assertion semantics. The pinned handwritten
CIRCT probes established feasibility only; the integrated implementation and
actual source-to-target results are recorded below. Memory ports remain a required
Nodal semantic owner under v0.4. These checkpoints do not close the full F56 parent.

[Gate v0.4](../design-gates/NodalSequentialState-DG-v0.4.md) selects owned memory
ports with whole-element storage compatibility, explicit byte masks, real
ordering/collision/domain semantics and bounded address proofs. Read construction
is initially module-level and continuously enabled; read values remain usable
in branches/actions. This preparation gate is implemented by the final memory checkpoint below.

## Flat-FSM and fixed-scalar target integration

The second checkpoint adds the v0.2 flat-FSM/action-switch owner and v0.3
whole-module CIRCT adapter. Memory-port implementation remains separate under
v0.4; this checkpoint does not complete F56.

### Shared owners and trust boundaries

Source construction records actual enum, machine, action, transition and register
identities. The existing checked Sequential callback carrier owns action effects;
transition arguments and enum initialization use the shared Pure proof machinery.
Successful enum proofs replay their original charged work and imported dependencies;
reuse is restricted to no-deeper tree/helper contexts. Deeper uses revalidate.
There is no callback replay or package/name-based effect exemption.

The canonical FlatFSMModel validates exhaustive states, local encoding, transition
policy and explicit region captures. SequentialModel retains register/domain and
sampled-input ownership. Both source and native ingress reject action targets in a
foreign clock domain, including constant RHS values under nested when/switch arms.
Normalization preserves phase-first action order, priority self-edge behavior,
reset suppression and actual next/taken observations. The complete cloned module
is committed only after validation; malformed siblings cannot leave partial output.

SequentialToCIRCT consumes the same normalized facts, assignment dependency graph
and materialization analysis. Typed backedges preserve physical register feedback.
Reset-free compregs plus explicit synchronous reset muxes preserve reset priority
without hidden initialization or history. Actual Error properties become labeled,
owning-edge clocked assertions. The finite adapter rejects unsupported parameters,
aggregates, hierarchy, interfaces, memory and reset policies transactionally rather
than specializing defaults. The admitted scalar inventory and bounds are listed
in the v0.3 gate and production adapter.

Repeated child definitions retain the existing canonical reuse path. Expression
collision keys are local to the actual module namespace, rather than accidentally
including an occurrence handle; full source paths and semantic metadata remain
unchanged. The causal controls include repeated pure-expression and FSM children,
different bodies/edges and quoted source metadata. Runtime Integer/Real storage
captures remain distinct from static parameter proofs. Scalar assignments use the
shared chronological operation stream.

### Integrated native replay

The release build passes 59 flat-FSM, 62 CIRCT-adapter and 71 sequential-model
cases. Directly affected predecessors pass: digital types 103, values 362,
storage 119, enums 68, assignment guards 34, materialization 40, structural-if 69,
parameter integer bounds 58 and target capabilities 79. Logs are under
`.work/increment56/flat-fsm-live/logs/`; the release `nodalc` SHA256 is
`7e823debc0308c3f4abe1de6b61f1f6af45a42d1b743e732a90977e063fd5414`.
The initially mistyped build target is retained as a failed setup attempt, not a
semantic test. The corrected build and post-domain-repair rebuild both pass.

Maintained Scala compilation and 83 focused cases pass: flat-FSM source/bridge,
sequential source/bridge, structural-if and analog-event review suites. The 17
selected source-origin/discovery/bridge/capture predecessors also pass. Its first
compile correctly rejected a raw test-only compiler-owned call and host conditionals placed
directly inside checked transition arguments. The raw boundary probe now uses the
existing explicitly reflective test adapter; construction-time fixture selection
occurs before the transition call. Production plugin restrictions are unchanged.
No failed intermediate run is counted as acceptance.

The final maintained API/plugin/bridge artifacts regenerate 13 public Nodal
witnesses. All normalize and reparse; six independently mutated native inputs
reject with diagnostics, and exact authored machine root/next/taken identities
survive normalization. The runner also passes 27 bridge/affected-waveform groups.
Four actual public designs (enabled register, full flat FSM, Error and recovery)
pass `--nodal-sequential-to-circt`, byte-identical target reparse, pinned CIRCT
verification and real SystemVerilog export. The parameter-bearing nested fixture
normalizes in Nodal and rejects at the finite CIRCT capability boundary with
`NODAL-CIRCT-056-001`; its parameter default is not substituted.

### Public source-to-target example

The maintained `nodal_external.Increment56EnabledRegisterSource` uses the actual
public API and compiler plugin:

```scala
val core = ClockDomain.from(
  clock, reset, ClockEdge.Rising, ResetPolicy.Sync,
  ResetPolarity.ActiveHigh, 100.MHz, "core"
)
core:
  val stored = Reg(0.U(8))
  when(enable):
    stored := input
  output := stored
```

Its emitted target has one positive-edge register, no initial value and an
explicit `reset ? 0 : enable ? input : stored` next-state expression. The
executed CIRCT export contains this SystemVerilog (location comments omitted):

```systemverilog
  reg  [7:0] stored;
  always_ff @(posedge clock)
    stored <= _GEN;
  assign _GEN = reset ? 8'h0 : enable ? input_0 : stored;
  assign output_0 = stored;
```
 The full
FSM witness retains three registers and an owning-edge labeled Error assertion.
The generated `.mlir` and `.sv` artifacts are in
`.work/increment56/flat-fsm-live/public/`. Reproduction after the maintained Scala
export, for `name=public-enabled-register` or `public-flat-fsm`:

```sh
out=.work/increment56/flat-fsm-live/public
.work/increment56/flat-fsm-live/bin/nodalc "$out/$name.mlir" \
  --nodal-sequential-to-circt -o "$out/$name.circt.mlir"
.work/circt-toolchain/firtool-1.154.0/bin/circt-opt \
  "$out/$name.circt.mlir" --verify-each --verify-clocked-assert-like \
  --lower-seq-to-sv --lower-verif-to-sv --export-verilog -o /dev/null \
  > "$out/$name.sv"
```

The scratch public runner initially omitted its own allocation-site plugin and
included an obsolete duplicate Scala library from the inherited broad classpath.
Both setup failures are retained separately; final execution uses the maintained
plugin and one pinned Scala library. Neither failure was counted as a semantic
pass. Successful export is not a claim of downstream synthesis, formal
equivalence or HDL simulation; F65/F66/F67/F72 retain those original consumers.

### Output-quality, scale and later-consumer handoff

No extra state optimization is needed for this semantic checkpoint. Private local
encoding remains independent of canonical enum values; the target emits only
actual registers and the required Error property. It adds no hidden boot, reset
history or action-enable state. Existing required-materialization analysis remains
the sole owner; unsupported intent without its quality context rejects.

A source 64/256-state pair retains 645/2,565 expression nodes at maximum expression
depth 11/13. A native 65-state/256-priority-transition case normalizes to 1,886
operations. Exact 51-operation expansion succeeds and a 50-operation budget fails
transactionally; a 401-transition Exclusive fixture exhausts the shared 65,536
proof-work limit normally. These are bounded representation/budget witnesses, not
whole-pipeline timing or linear-complexity claims. Component elapsed-time samples
are retained in scratch logs and include contention; no speedup is claimed.

The required later-consumer cases are concrete: F65/F66 should parse and simulate
the emitted enabled-register reset/enable/hold fixture, both physical clock edges,
flat-FSM ordered self-transitions, terminal completion, Error and recovery, plus
memory-port collision cases once that owner is complete. F67 should compare the
same verified private state/next-data graph and canonical enum boundary behavior
under declared reset assumptions, without inventing startup state. F72 should
retain the physical domain/port identity at the digital/analog boundary and reject
unsupported combined profiles explicitly. Those consumers retain their own
execution and acceptance; this checkpoint's actual CIRCT verification/export is
not their completion.

The final integrated frontend harness also passes: 23 exact source-located
negative fixtures, public positives, genuine separate-JAR summaries and impure/
missing replacements, no-plugin rejection and runtime attestation/poison controls.
Its immutable result is `flat-fsm-live/frontend/run-kcfk214e/manifest.json`; the
maintained plugin jar SHA256 is
`2c93de69e31d27c9bd71718a31e52d9d4507e6595745bf8a65e9a974b998d339`.
The earlier component behavioral/structural compiler harnesses retain their own
passing artifact-bound results; final source domain checks were replayed with the
maintained API. Focused Markdown, registry generation, formatting and whitespace
checks pass. No CI was run. B.1 and B.3 are complete for their stated deliverables;
At that checkpoint B.2 and the F56 parent remained open. The final memory
checkpoint below completes B.2; G and the parent await verified integration.

## Final owned memory-port integration

The v0.4 implementation replaces candidate strings with typed `memory_read`,
`memory_write` and canonical `memory_write_effect` operations tied to the sole
MemoryOp. Reads retain the declared compact latency/collision policy; writes carry
actual typed address/data/mask/guard/clock edges and independently checked dense
per-memory order. The shared sequential normalizer composes real module-root and
FSM phase order, then defers canonical edge effects until their lifted producers
and physical clock dominate. This preserves pre-edge sampling and authored write
priority without a second branch scheduler.

MemoryModel recomputes type, ownership, bounds, masks, domain sampling and conflict
facts. It reuses DigitalValueModel's checked exact-value facts and ParameterModel's
all-legal bounds; runtime Integer storage is not promoted to static evidence.
Synchronous reads are dependency cuts; asynchronous reads retain address edges.
The existing sequential and Interface owners provide sampled-domain provenance.
A non-previewing shared normalization preflight prevents recursive raw/canonical
cross-memory preview while retaining final conflict checks on the normalized clone.

The read-only edge query operates on canonical typed ports and caller-supplied
pre-edge values/cells, returning sparse writes and first-stage responses. Its
compact response equation represents latency without allocating the memory depth
or a latency-length pipeline. It refuses unnormalized nested writes. Per-address
known-byte coverage preserves partial-write priority and can recover a fully
specified word after all bytes are overwritten; unknown bytes remain unspecified.
There is no second mutable simulator registry, implicit zero initialization or
memory reset invented from the owning domain's reset pin.

Component replay has passed causal OldData/NewData/NoChange/Undefined, latency1/2,
ordered byte-write reversal, zero/disjoint masks, all element families, actual
Interface/requirement provenance and transactional budget/ownership negatives.
Actual public guarded/FSM replay caught the late-clock producer placement defect
that the first unit fixtures missed; the unchanged public witness and strict
future-clock negative now pass in the component build. Final integrated main-tree memory replay and the directly affected F55 bridge
repairs now pass. The parent remains open until verified integration.

### Concrete memory consumer handoff

F63/F65 should consume the checked canonical effects and compact read-response
contract, preserving actual clock/edge, guard and dense per-memory order. Required
later target witnesses are the public UInt32/data + UInt4/mask case, reversed
ordered byte writes with distinct values, simultaneous disjoint writes, and the
same-address OldData/NewData/NoChange/Undefined cases. NoChange must retain its
first response-stage state; latency 2 must advance later stages even when that
first stage holds. Uninitialized cells and pipeline stages remain unspecified.

F66 should execute those same collision cases with distinct pre-edge contents and
new data, including an all-zero mask and complete byte coverage built from several
partial writes. F67 should compare normalized versus emitted behavior for each
policy, byte priority and latency 1 versus 2, without assuming zero initialization.
F72 should retain the digital memory's clock/reset boundary in mixed output;
absence of a memory reset is intentional even when the owning domain has one.
These are concrete downstream obligations, not claims that RAM emission or those
later consumer executions passed here. The current CIRCT adapter rejects the
complete memory-backed design before returning partial target output.

### Directly affected widening-reduction compatibility repair

The extra maintained F55 bridge selection exposed five failures on the published
pre-memory F56 tree (18/23 groups passed). The Integer storage separation had
changed the bridge static-argument index to admit only `static_capture`, excluding
reduction width formals previously handled by the shared reduction owner. The
repair retains runtime Integer separation: only formals admitted by successful
reduction-context validation receive width facts, and bounds still come from the
active shared query. Retained type arithmetic is distinguished from actual
structural predicates and loop/Generate bounds; width formals do not thereby
become legal topology predicates. Lexical ownership is checked separately from
proof admission, including escaped or sibling formals.

The unused-capture adversarial fixture also selected the newly introduced Clock
input instead of its intended ordinary data input. Its selection now identifies
the intended type, retaining the original unused-SSA assertion. Repeated child
comparison preserves separate instance locations and child paths while allowing
actual equivalent definitions to share one canonical module. Original retained
F55 output had occurrence-derived definition spans/reset-source names; current
physical clock/reset boundaries and true definition locations remove those
incidental differences. Exact canonical equality and genuinely differing
source/body controls, rather than module count alone, establish compatibility.
All 40 focused collection/structural/widening bridge groups pass in both the
isolated repair replay and the final maintained build. The 45 affected
memory/storage/sequential/FSM/waveform groups were rerun on that final bridge and
pass as well.

### Executed public memory example

The maintained `Increment56MemoryBridgeTests` native replay constructs the following
actual public fixture in `Increment56MemoryBridgeSource.scala`. Its `new-data` case
selects `collision = ReadUnderWrite.NewData`; other defaults stay unchanged.

```scala
final class Increment56MemoryPolicySource(
    latency: Int = 1,
    collision: ReadUnderWrite = ReadUnderWrite.OldData,
    ordering: MemoryOrdering = MemoryOrdering.Ordered,
    reverse: Boolean = false,
    disjoint: Boolean = false,
    zero: Boolean = false
) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val memory = Mem(UInt(16), 2, latency, collision, ordering, root)
  val firstAddress = 0.U(1)
  val secondAddress = (if disjoint then 1 else 0).U(1)
  val firstValue = 0x1122.U(16)
  val secondValue = 0x3344.U(16)
  val firstMask = 1.U(2)
  val secondMask = (if zero then 0 else 3).U(2)
  if reverse then
    memory.write(secondAddress, secondValue, secondMask)
    memory.write(firstAddress, firstValue, firstMask)
  else
    memory.write(firstAddress, firstValue, firstMask)
    memory.write(secondAddress, secondValue, secondMask)
  val read = memory.read(firstAddress)
```

The corresponding normalized Nodal IR below is an actual excerpt from
`.work/increment56/memory-live/public/new-data.normalized.mlir`; unrelated
declarations and operand producers are omitted, with SSA names unchanged. This
is canonical memory IR, not emitted RAM SystemVerilog. The existing register/FSM
SystemVerilog demonstration above covers the implemented target profile.

```mlir
    "nodal.memory"() <{depth = "2", domain = @root, element_type = !nodal.uint<16>, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment56MemoryPolicySource.memory", source_end_column = 68 : i64, source_end_line = 197 : i64}, ordering = "ordered", read_latency = 1 : i64, read_under_write = "new_data", sym_name = "memory"}> : () -> ()
    %3 = "nodal.memory_read"(%0) <{domain = @root, memory = @memory, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment56MemoryPolicySource.read", source_end_column = 39 : i64, source_end_line = 210 : i64}}> : (!nodal.uint<1>) -> !nodal.uint<16>
    "nodal.memory_write_effect"(%0, %2, %1, %8, %7) <{domain = @root, edge = "rising", memory = @memory, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment56MemoryPolicySource.memory_write_0", source_end_column = 54 : i64, source_end_line = 208 : i64, source_ordinal = 0 : i64}, order = 0 : i64, root_id = "Increment56MemoryPolicySource.memory_write_0"}> : (!nodal.uint<1>, !nodal.uint<16>, !nodal.uint<2>, !nodal.bits<1>, !nodal.capability<"clock">) -> ()
    "nodal.memory_write_effect"(%4, %6, %5, %9, %7) <{domain = @root, edge = "rising", memory = @memory, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment56MemoryPolicySource.memory_write_1", source_end_column = 57 : i64, source_end_line = 209 : i64, source_ordinal = 1 : i64}, order = 1 : i64, root_id = "Increment56MemoryPolicySource.memory_write_1"}> : (!nodal.uint<1>, !nodal.uint<16>, !nodal.uint<2>, !nodal.bits<1>, !nodal.capability<"clock">) -> ()
```

Reproduce through `Increment56MemoryBridgeTests` with `NODAL_NODALC` pointing to
the built `nodalc`; its `NativeCompilerRequest` supplies
`--nodal-normalize-sequential` and writes raw/normalized fixtures beneath
`.work/increment56-memory-bridge`. The executed configuration used the maintained
release binary in Nodal-IR normalization mode, without RAM target emission. The
second ordered write
replaces both bytes with `0x3344`; reversing these actual writes instead leaves
`0x3322`. Native edge-query cases independently establish that value difference
and policy-specific old/new/hold responses.

### Memory architecture and scale review

The completed-namespace semantic-name allocator now reserves all authored names
before assigning effect identities, including declarations appearing after a
write. This retains one canonical identity owner; neither the bridge nor native
verifier relaxes uniqueness. The bridge transports actual SSA operands, while
native admission independently recomputes address, mask, domain and order facts.
The canonical memory query is stateless and consumes explicit pre-edge values;
no competing behavioral simulator or hidden state initialization was added.

The maintained release memory test measured 32 independent ports, 496 charged
pair queries, depth and latency of 1,000,000,000, and 110 normalized operations
in 15 ms for that one local run. This demonstrates compact depth/latency retention,
not a speedup or whole-pipeline linearity claim. Pairwise conflict proof remains
bounded quadratic work. Strict over-budget, malformed-input and transactional
failure controls passed. No additional optimization is justified here: changing
write ordering, coalescing unknown-mask writes, synthesizing initial cells or
folding NoChange into a stateless value would alter observable semantics.

### Maintained memory checkpoint results

The main-tree release replay passed 114 memory, 71 sequential, 59 flat-FSM,
62 sequential-CIRCT, 79 target-capability, 362 digital-value, 119 digital-storage,
34 assignment-guard and 69 structural-if cases (969 total), plus five exact
memory capability/target-refusal checks. Maintained Scala compilation passed;
55 source/sequential/FSM groups and 45 memory/storage/bridge/waveform groups
passed, as did 17 selected source-identity/discovery predecessors. The real
frontend plugin harness accepted its positive and separately compiled helper
and rejected all eight exact memory allocation/effect/private-entry negatives.

The public native replay covers 16 positive designs and six precise negative
mutations; repeat normalization/idempotence passed 32 checks in the frozen
component replay. All 16 main-tree normalized outputs equal those component
outputs byte-for-byte. Two raw enum source-location records correctly name their
different physical compilation roots; comparison after that exact root rebase
matches, and emitted artifacts are not rewritten. The naming repair preserves
18 earlier noncolliding raw outputs and 14 normalized outputs byte-for-byte in
the same source context.

Fifteen changed Scala files pass pinned formatting. The focused lint comparison
retains exactly the 11 pre-existing diagnostics on unchanged offending statements;
this is not a clean-lint claim. Original F15 fixtures remain unchanged: the plain
UnifiedV03 compile-only example passes; exact pre-memory/current plugin controls
retain the same inherited closed-dispatch `Seq.foreach` and unavailable
`enumEncoding` summary failures. These controls do not claim an executed historical
whole-tree build. The separate F55 width-consumer regression repair passes its final 40 maintained
groups; the final bridge also passes the 45 directly affected groups again.

## Final scoped acceptance and integration handoff

A–F and the mandatory F-056.B.1.1 amendment are complete for the approved finite
profiles. Earlier register/FSM/CIRCT evidence remains the evidence for those
unchanged production paths; the final memory checkpoint and shared bridge repair
results above cover their affected consumers. Native and source architecture
reviews found no remaining concrete blocker after the causal fixes. There is no
known required in-scope failure left. G and the parent stay open until the exact
tested tree is squash-merged and independently verified.

The maintained validation used pinned local toolchains, selected suite runners
and `NODAL_NODALC=.work/increment56/memory-live/bin/nodalc`; complete commands and
raw logs remain under `.work/increment56/memory-live`. A repository-native way to
reproduce the public memory selection is:

```sh
NODAL_NODALC="$PWD/.work/native-release/bin/nodalc" \
  ./mill core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment56MemoryBridgeTests
```

The final native SHA256 is
`c4b9bf58f6cb7ad9f36135f9321e37fc8cbf8e233f6b1a487b8263cb1d83d911`.
The final memory frontend harness used plugin SHA256
`589adeeaed2a7ccf7ddcfeecc6fb5e179e9a8dbdbe66e82f18c23a0ed4e03b15`.
No CI, simulator RAM execution or later consumer qualification is claimed.
