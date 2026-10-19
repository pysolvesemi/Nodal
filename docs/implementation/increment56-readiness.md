# Increment 56 readiness and implementation

Status: implementation in progress; all original F56 children remain open.
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

Subsequent required F56 work remains explicit:

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
original checklist children and the parent remain open.

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
The remaining required FSM, switch, encoding, memory-port and approved CIRCT
features listed above still prevent F56 completion or merge-ready status.
