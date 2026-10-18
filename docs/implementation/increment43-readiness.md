# Foundation 43 readiness and implementation checkpoint

**Initial readiness date:** 2026-10-01
**Latest process/handoff update:** 2026-10-18
**Status:** Implementation children complete under focused prototype acceptance;
F-043.G and the parent remain open pending verified integration of PR #139.
**Refreshed integration target:** `dev` at `3d5a6f259505ea5084cf7606c0cfeb92e5843e41`
(read-only verification, 2026-10-18).
**Branch:** `increment/43-analog-arrays-generation`
**Initial integration target:** `dev` at `e34d17ba2c9adb9e765ee4f0c97699128c224340`
**Initial integration tree:** `57d402aada6561e1c51ef9b60c49fff193219d8e`

## Authority and predecessor verification

This is the next eligible Foundation increment, not another track. Follow
[AGENTS.md](../../AGENTS.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), the
[main checklist](../roadmap/nodal-development-todo.md), the
[roadmap index](../roadmap/README.md), and the new F-043 descendants in the
[lightweight iteration amendment](../roadmap/lightweight-hierarchy-iteration-v0.1-plan.md).
The main checklist and amendment remain the sole owners of their existing
checkboxes; this document is an applicability/decision record, not another ledger.

F-042 implementation and closure are accepted. F-160 implementation PR #137 and
closure PR #138 are merged. The actual F-160 closure is
`e16ee949f006e0fd44d8f5c8568718f9d0e2548a`, with qualified/merged tree
`1efa8f2184169f69ae6408d79c8ed597ac460a25`. The refreshed target is its direct
documentation-only child, adding the F-097 compact scalar declaration amendment.
Preserve that concurrent amendment; it adds no F-043 prerequisite. F-097 and
F-159 are not started here. There was no existing F-043 branch or open PR at
readiness review; reuse this branch and its draft PR for subsequent work.

## Inspected production owners and shared-layer decision

The review covered the accepted
[ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md),
the [approved iteration gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md),
F-042/F-160 records, and the actual construction, bridge, native and test paths.

`CoreSemanticsCandidateApi.scala` exposes `Dimension`, `Vec`, `generate` and
`loop`, but those particular candidates still use inert generic recording;
their presence is not implementation evidence. `hdlRange` is the approved
preferred future surface. Do not treat the older two-range-only proposal as
its specification or remove exposed compatibility APIs.

`AnalogProceduralConstruction`, `AnalogControlFlowConstruction` and
`AnalogControlFlowRuntime` already own retained procedural/static loops,
lexical scopes, definite-assignment checks and event placement. Their bridge
and native verifiers remain the trust boundaries. An existing `analogRepeat`
is procedural repetition, not topology replication or per-generated-instance
storage; it must not silently acquire either meaning.

The F-160 construction session/records and F-042 hierarchy own component,
parameter, terminal, connection and source identities. Reuse those owners.
A new parallel hierarchy registry, default-value specialization, raw HDL rewrite
or Scala-to-Rust migration is neither needed nor authorized by this increment.

Chosen design: one small neutral iteration-domain representation and arithmetic
owner, consumed by capture and the existing procedural adapter where semantics
match; explicit generated-region/induction ownership in construction records;
independently checked bridge/native contracts; and target-specific legal layout.
Shape remains semantic and structural, never an implicit memory or packed
analog scalar. Keep immutable records separate from session-owned mutable state.

Alternatives rejected: eager host unrolling of symbolic domains; a separate
range engine for analog and digital code; reusing enclosing lexical storage for
generated lanes; inferring staging from body contents; and treating the current
inert candidates as successful production capture. A bounded shared primitive
is preferable to an unneeded framework or wholesale frontend rewrite.

## Applicability of every existing obligation

| Existing obligation | Classification and required result |
| --- | --- |
| F-043.A | Required now: architecture, compatibility, ownership and capability boundary against ADR 0017 and accepted hierarchy. This readiness record alone is not final acceptance. |
| F-043.B / B.1 | Required now: production fixed/symbolic analog shapes, indexing/slicing, ordinary Scala replication and explicit target-visible generation. |
| F-043.B.1.1 | Required now: common literal/symbolic iteration domain and stable generated-object/index identities, independently reusable by F-159 without waiting for digital lowering. |
| F-043.B.2 | Required now: bridge/native shape, static-bound and generated-instance storage ownership checks, including forged input rejection. |
| F-043.B.2.1 | Required now: topology versus procedural effects, conservative terminal indexing, generated lexical state and analog operator-placement restrictions. |
| F-043.B.3 | Required now: legal supported target layouts/generation, symbolic bounds and source/index maps; explicit unsupported cases. |
| F-043.B.3.1 | Required now: actual scalar/generated target counterpart witnesses; no illegal flattening, shared state or accidental memory inference. |
| F-043.C | Required now: positive, negative, empty/singleton, invalid dimension/rank/bound, event-state and predecessor regressions. |
| F-043.D / D.1 | Required now: actual public Scala to normalized IR to Verilog-A compiler witnesses. Internal reparse is not independent OpenVAF compilation. |
| F-043.D.2 | Required now: a concrete handoff of cases, analyses, references and tolerances to F-048 compile and F-049/F-052 numerical owners. Their later executions are not claimed here. Use focused compiler witnesses now; later numerical/tool execution does not block prototype acceptance. |
| F-043.E | Required now: proportionate expansion/traversal/output review, preserved symbolic bounds and instance-local state. Optional improvements require measured justification, not a new reverse prerequisite. |
| F-043.F | Required now: nested dimensions, repeated generation, parameter envelopes, deterministic source/index identities and compatibility. |
| F-043.G | Required now: brief checklist notes, actual generation demonstration, architecture review, focused local checks and verified integration; no full qualification or separate closure gate. |

At initial readiness no existing requirement was removed, narrowed, waived, checked
or reclassified as non-applicable. No checklist correction was necessary;
the linked amendment already resolves the preferred range spelling. Digital
mixed-effect lowering/fusion and synthesis/formal evidence remain with
F-055-F-058/F-159. General equation legalization remains F-141. Comprehensive
profiling and optional Rust evaluation remain F-096. These are ownership
boundaries, not excuses to omit analog work required by F-043.

## Implementation sequence and acceptance limits

Start with overflow-safe half-open static-domain arithmetic and integrate it
into an existing real production consumer without changing its staging. Cover
negative and extreme bounds, positive steps, empty and singleton domains,
finite count/last-value limits and failure before invoking an invalid body.
This is a partial implementation checkpoint, not all of F-043.B.1.1.

Next add symbolic bound DAGs and enforced envelopes through the canonical
parameter owner; do not evaluate defaults and discard variability. Capture
induction and generated scopes exactly once, retaining per-lane object/storage
ownership without manufacturing one host object per possible parameter value.
Connect shape/index/slice metadata and generated regions through bridge/native
verification and legal Verilog-A layouts before exposing an accepted profile.

Capture must distinguish an empty legal iteration domain from an illegal
zero-sized shape. Ordinary Scala ranges stay ordinary Scala ranges. Preserve
host evaluation count, deterministic identities and failed-construction cleanup.
Reject unsupported operator placement and dynamic allocation instead of silently
falling back to analog flattening, hidden memories, latency or another backend.

The exact scalar/array capability matrix and emitted target witnesses must be
completed as the implementation becomes coherent. No universal Verilog-AMS,
independent simulator, numerical accuracy, synthesis or speedup claim is made.

## Prototype process and handoff to dot — 2026-10-18

The owner explicitly replaced the old qualification/continuation process with
[focused prototype development](../../AGENTS.md#prototype-validation-and-merge).
Do not run any CI, full qualification or hourly monitoring. The old independent
Codex/full-CI/Increment34-PR-witness/push-only-status/separate-closure gates in
historical PR comments do not govern new prototype acceptance. Preserve feature
semantics and existing tests; use affected positive/negative/boundary regressions
and a concise architecture/modularity/scalability review. Fix later-discovered
bugs when found. Store new generated outputs and logs in ignored root `.work/`;
record verified `[x]` completion with brief notes in the existing checklists.

### Published source and coordination

- Repository: `pysolvesemi/Nodal`; branch:
  `increment/43-analog-arrays-generation`; draft [PR 139](https://github.com/pysolvesemi/Nodal/pull/139).
- Latest implementation source: `9850d0928d8e5c7ad1a0c639e2870ca3f45a3c29`,
  tree `13bf4c2b783cbd64e4a5ce541cf3a40b5b9fe7e1`, parent
  `6bfec16088934a93ff81ceaa06c170d1882efc9f`.
- Integration target remains `dev` at
  `3d5a6f259505ea5084cf7606c0cfeb92e5843e41`.
- This policy/handoff commit is an append-only child of that source. Refresh
  the live branch before starting; do not reset it to the implementation SHA.
- All substantive local work is already present remotely. Old local commits
  with different hashes have identical published trees/patches and must not be
  pushed again. No unpublished compiler source or generated evidence is needed.
- The latest worker lease was released in PR comment 6246843976. Hourly task
  `6abe9447b0508191bff0f81af632a8e6` is disabled by the owner's migration request.
  Do not re-enable it or launch/replay an old CI controller.

### What is implemented and checked

Consult [shape contract](increment43-shape-contract.md),
[integer bounds](increment43-integer-bounds.md),
[generate count](increment43-generate-count.md), and especially the latest
sections of [compound bounds and generated target transport](increment43-compound-bound-transport.md)
for the exact supported profile and source/test owners. Shared shape/range
contracts, symbolic bound transport and generated ownership feed the real
frontend/bridge/native/backend path. Generated node arrays, bounded nested
peer-node operator/result-scale paths, source maps and zero-based nested
occurrence indexing are implemented within the recorded capability limits.

The latest source adds a negative-origin nested peer `idt` witness with exact
state/induction ownership, target/index checks, deterministic re-emission,
source maps and forged-state/fan-out negatives. It adds no production admission
rule. The preceding source fixed nonzero unit-step origins in nested row-major
indexing while preserving direct one-dimensional bounds and indices.

Historical local results on 9850d092: native 145/145, Python 474/474,
ClangFormat 64 and document/policy checks passed. Local Mill/Scalafmt was blocked
by Maven DNS; do not relabel that as a local pass. These broad old results are
retained history, not a requirement to repeat them under the new process.

At migration, the already-launched Core run
[39105880444](https://github.com/pysolvesemi/Nodal/actions/runs/39105880444)
reported success. Bridge run
[39105893537](https://github.com/pysolvesemi/Nodal/actions/runs/39105893537)
failed in online toolchain provenance lookup: job 117611535922 reported
NODAL-TOOLCHAIN-101/103/106, GitHub HTTP 403 rate-limit errors for CIRCT/LLVM.
This is an infrastructure failure, not a confirmed compiler regression or a
passing Bridge result. No retry or new CI is needed under the new policy.

### Next implementation work

Reconcile the existing F-043 main and amendment children against actual code,
then mark only completed child deliverables with short verified notes. They
remain open at migration; this process update does not claim F-043 completion.
Finish required fixed/symbolic shape/index/slice behavior, generated storage and
operator ownership, supported target layouts and source/index maps within the
approved scope. The published prototype checkpoints now admit independent wider branch/operator
groups, deeper direct generated equation chains, composed structural views and
read-only fixed/symbolic unit-stride slices. Their exact tests and limits are in
[increment43-compound-bound-transport.md](increment43-compound-bound-transport.md)
and [increment43-shape-contract.md](increment43-shape-contract.md). Commits
`8900b8a2eb9f00f3f63cad6b8dc34d097657b003` and
`2b81ba5cdf0f7672aa86211fbb2b62b69bfc9a74` were published with tested-tree equality
and no CI. These are partial implementation checkpoints, not whole-child closure.

The owned module-local electrical array checkpoint is published as
`35d093770721e232c0d666f1b6b1b404220daed1`, with exact tested-tree equality and no
CI. Under the [node-array gate](../design-gates/NodalConservativeNodeArrays-DG-v0.1.md),
static scalar selections share canonical physical identity through native
connectivity, scalar hierarchy actuals and compact symbolic target layout.
Nineteen focused Scala cases, eleven Scala predecessors, 37 native node-array
checks, four shape integrations and affected backend/syntax tests passed; exact
commands and limitations remain in the existing shape-contract note.

The lexical writable Real storage checkpoint is published as
`6bc6a3ff7c263f290d81b9263d5ac082bec91f31`, with exact tested-tree equality and
no CI, under the
[additive writable-array gate](../design-gates/NodalWritableRealArrays-DG-v0.1.md).
Existing procedural records own typed selections and fresh immutable read
snapshots. Shared native storage/coverage proofs and compact mapped target loops
preserve overlapping-copy behavior without lane expansion. Thirteen configured
public methods, eleven affected Scala predecessors, eight source/scalar checks,
41 native semantic cases, 15 registered native tests, 21 descriptor checks and
179 target parser checks passed, together with affected backend tests. Exact
commands and limitations are recorded in the shape-contract note.

Event-independent shared procedural validation and target admission is published
as `df68386ced6671d572dab42359256d5082868a6f`, with exact tested-tree equality
and no CI, under
[v0.2](../design-gates/NodalWritableRealArrays-DG-v0.2.md). It preserves authored
ordinary execution order and replaces generated operation-count recognition
with shared owner/control/storage checks. Forty-nine semantic cases, 32 direct
scalar/control predecessors, affected event/backend matrices and five configured
public methods passed; exact scope and commands remain in the implementation note.

Ordinary persistent shaped initialization is published as
`06a209712de094a7851c3c89fa3fb7991666c28e` with exact tested-tree equality
and no CI, under
[v0.3](../design-gates/NodalWritableRealArrays-DG-v0.3.md): compact VAMS2.4 constant
replicated declaration initialization, preserving the standard initialization
phase rather than an event/runtime reset. Actual tested OpenVAF versions reject
all Real arrays, including existing public witnesses; the concrete F48 consumer
handoff is documented separately from language-structural acceptance. Generated
persistent initializer lifecycle remains outside this ordinary-root extension.

Live selected-control/event references are published as
`d609a6d30e2365e1bbc5edc4bbe5e615348ca931` with exact tested-tree equality
and no CI, under
[v0.4](../design-gates/NodalWritableRealArrays-DG-v0.4.md): actual scalar storage
reference operands, compact static coordinate setup and live numeric leaves at
the existing evaluation sites. First-match/short-circuit behavior, loop-bound
evaluation once per entry and event monitor lifetime are preserved. Eleven public
methods, nine affected predecessors and focused native/backend checks passed;
commands and limits are recorded in the shape-contract note. Public loop
composition uses existing scalar Integer storage; no new Real-to-Integer cast
is introduced. The source-consumer extension is published as
`53d4767c21126c019eb2300ba1aba1d8866f1d7e` with exact tested-tree equality
and no CI, under
[v0.5](../design-gates/NodalWritableRealArrays-DG-v0.5.md): checked single-occurrence
uniform-fill scalarization and compound selected local initialization, preserving
once-only fill evaluation. Twelve new public groups, 18 affected predecessors,
native/backend checks and eight actual dual-target models pass; commands and
limits are in the shape-contract note. Repeated shared-fill live/event consumption remains
explicitly open until a correct lazy evaluation contract is implemented. Generated
persistent initialization still needs per-occurrence declaration lifetime.
The shared-layer extension is published as
`9ab2380787865e17283217912dae14573788c66a`, with exact tested-tree equality
and no CI, under
[NodalConservativeNodeArrays-DG-v0.2](../design-gates/NodalConservativeNodeArrays-DG-v0.2.md):
owned local conservative views and canonical scalar physical endpoint maps.
Seven new public methods, 13 affected predecessors, nine actual dual-target
models and focused native/backend checks pass. It preserves local unpacked-net
layout; boundary vector layout and whole-array
instance mappings remain subsequent required work. The next integrated extension
is governed by
[NodalConservativeNodeArrays-DG-v0.3](../design-gates/NodalConservativeNodeArrays-DG-v0.3.md):
boundary arrays, whole-child bindings, exact finalized instance parameter
substitution and compact noncontiguous structural adapters. This is selected
implementation scope published as `b534451750ab1674fc269632d3c226cdafb6fc89`
with exact tested-tree equality and zero Actions runs. Focused acceptance passes: eight new public
methods, 16 affected predecessors, 49 native binding cases and ten actual
dual-target models. The shape-contract note records commands, source/output
examples, architecture review and target-consumer limits. Source-authored
generated topology is the next required shared-scope implementation under
[NodalConservativeNodeArrays-DG-v0.4](../design-gates/NodalConservativeNodeArrays-DG-v0.4.md).
Its selected implementation is published as
`6098797e7d666636af49a5bc7cab5280703616b1`, with exact tested-tree equality
and zero Actions runs. It passes eight new public methods, 16 directly affected
predecessors, 50 native cases and 13 actual dual-target models, with exact native
and target replay. Shared ownership/domain proofs and scoped target emission
received focused architecture review. Commands, integration repairs and finite
limits are recorded in the shape-contract note. Generated persistent shaped-state
declaration lifetime is implemented under
[NodalWritableRealArrays-DG-v0.6](../design-gates/NodalWritableRealArrays-DG-v0.6.md):
the exact tested tree is published as
`be57bd4cd206fd6f8b0a57af3462cce57c1b0d4a`, with zero Actions runs.
Five new public methods, 16 affected predecessors, 151 focused backend checks and
ten actual dual-target models pass. Actual generated scopes own source recorders,
native procedure uniqueness and target declarations independently; ordinary
procedure behavior and lexical entry copies remain intact. The next concrete
shared-layer target is conservative array connection relations and exact partial
child-port coverage under
[NodalConservativeNodeArrays-DG-v0.5](../design-gates/NodalConservativeNodeArrays-DG-v0.5.md).
This integrates deferred finalized child shapes, two-sided physical maps and
compact exact region coverage in the existing connectivity owner. The selected
implementation passes six new public methods, 14 affected predecessors, 54 native
relation cases, 140 relation backend checks and 16 actual dual-target profiles
with exact native/target replay. Focused architecture review and changed-file
format/document/contribution checks pass. The exact tested tree is published as
`9f04db02e054bba18ab5a0580f245fe5d05a5084` with `[skip ci]` and zero Actions
runs.
The shape-contract note records commands, ownership review,
source/IR/target examples and remaining limits.
The required generated-probe placement repair (F-043.B.2/B.3) is implemented:
physical groups retain their source-free semantics while checked active scopes
own derived records and dominating operands. The exact formerly failing public
flow witness now passes; focused checks cover 39 native cases, 191 shared-query
unit assertions, 102 backend checks, three public methods and four actual
VA/AMS profiles. Affected predecessors and focused architecture review pass;
the shape-contract note records commands and the preserved before failure.
The exact tested tree is published as
`d13f032ef75e02a8ed196924b76ffb3791058993` with zero Actions runs.
Generated local conservative-array declarations are implemented under F-043.B.1–B.3
and [v0.6](../design-gates/NodalConservativeNodeArrays-DG-v0.6.md). Focused acceptance
passes five public methods, nine affected Scala predecessors, 41 native cases,
199 shared-query assertions, 185 backend checks and 12 actual VA/AMS profiles
with exact replay. Public integration also repaired disconnected-root/fresh-lane
alias proofs and comparison-only SSA names for reusable child definitions. The
shape-contract note records architecture review, actual output, commands and
finite limits. The exact tested tree is published as
`1c87baaf13e27e5c3b72d9658ab2e542d3c3c7f1` with `[skip ci]` and zero
Actions runs.
Repeated shared-live/event fill consumption required by writable-array gate v0.5
is implemented and focused-tested under
[v0.7](../design-gates/NodalWritableRealArrays-DG-v0.7.md): an actual-identity
deferred DAG, finite lazy control plans and bounded inline event demand wrappers
with independently checked compiler-private output-helper ABI. Numeric evaluation
stays at its reached event argument site; ready flags reset before the complete
event statement. Final checks include 44 native graph assertions, 426 target-parser
checks, 41 control-plan checks, affected storage/expression/generated-persistent
and writable backend regressions, 16 source methods, 11 selected predecessors,
nine native-enabled public methods and 14 actual VA/AMS profiles. The
[implementation note](increment43-shape-contract.md#repeated-live-fill-demand-sharing--v07)
records exact commands, causal negative cases, measured output and limitations.

## Final original-scope reconciliation

The main A–F children and three linked F-043 iteration descendants now have their
own implementation and focused validation. This reconciles the original checklist,
ADR 0017 and approved gates; it does not waive required semantics or add unlimited
composition requirements. G and the parent remain open until verified integration.

- A/B: shared shape/domain arithmetic, structural view maps, actual hierarchy and
  generated-scope ownership are used across source, bridge, native and backend.
  Fixed and parameter-envelope shapes, indexing, rank-preserving slices, writable
  Real/quantity arrays, conservative Electrical arrays and boundary adapters,
  ordinary Scala replication and explicit generated regions have production paths.
  Generated local declarations, persistent/event state, selected live controls and
  shared fill demand preserve occurrence-local identity and evaluation semantics.
- C/D: retained causal positives/negatives cover empty iteration versus illegal
  zero-sized shapes, singleton/invalid dimensions, rank/bounds/overflow, forged
  owners/SSA, alias uncertainty, definite assignment, memory/packing rejection and
  predecessor semantics. The implementation note includes actual public Scala,
  normalized IR and emitted VA examples, plus concrete F-048/F-049/F-052 cases,
  analyses, scalar references, observability needs and tolerances.
- E/F: symbolic bounds and source/index maps remain structural; generated lanes
  are not expanded at a parameter default. Nested/repeated generation, child reuse,
  parameter envelopes and deterministic replay are exercised. Shared map/fact
  owners and finite demand plans remove identified duplicate work. Residual
  quadratic scans, growing provenance and bounded event text are disclosed;
  no whole-pipeline linearity or universal speedup is claimed.

Accepted capability boundaries remain explicit: analog objects do not become
packed digital values or inferred memories; shape/selection proofs must hold over
legal parameter envelopes; uncertain aliases and unsupported operator placements
reject. The target profile has checked integer/layout and parser-depth limits.
Shared event expansion is capped before expansion at 4,096 wrappers/1 MiB per
consumer and 65,536 wrappers/16 MiB per module definition. These are tested finite
resource boundaries, not missing required sharing/reset behavior. Internal target
reparse is language-structural evidence. Tested OpenVAF real-array incompatibility
and unexecuted electrical-vector/private-helper consumer capability remain explicit
F-048 handoffs; later numerical execution remains F-049/F-052 work.

Focused architecture review found no remaining concrete blocker after causal
repairs. No CI, full qualification or simulator success is claimed. Earlier
checkpoint sections preserve their historical partial status; this final summary
supersedes their next-work descriptions, without changing historical evidence.

Use the existing shared owners, unified `hdlRange`, approved design gates and
public witnesses. Add only affected positive/negative checks for each changed
path; no exhaustive matrix or broad predecessor rerun. Keep F-048/F-049/F-052
handoff notes concise; later simulator/numerical work is not a reverse dependency.
Once the scoped implementation, focused checks and architecture review are
complete, use the normal verified squash merge with `[skip ci]` and show an
actual Scala/IR/generated-Verilog-A example. A separate evidence-closure PR is
not required. The coordinating owner performs the final expected-head verified
squash merge; implementation workers must not merge concurrently.

Handoff policy checks: six focused positive/negative document/contribution tests,
changed Markdown links/structure, JSON parsing, ignored `.work/` handling and
Git diff checks passed. Existing checklist states and accepted records were
preserved. No CI or compiler suite was run for this policy-only update.
