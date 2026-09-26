# Increment 42 - Readiness review and implementation-plan correction

**Reviewed:** 2026-09-24
**Status:** Readiness review recorded; increment remains incomplete.
**Scope:** Foundation 42 only; existing `increment/42-analog-hierarchy` / PR #134.

## Subsequent owner-approved syntax amendment - 2026-09-24

The owner has now selected constructor-declared typed HDL parameters, ordinary
`new Child(...)` attachment, direct child-port access and conservative `<>` as
the preferred common hierarchy syntax. Read the
[approved roadmap amendment](../roadmap/lightweight-hierarchy-iteration-v0.1-plan.md)
and [new scoped gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md)
before continuing B.1. Those requirements supersede the earlier common-case
explicit-only choice in this historical review; the existing explicit APIs and
small policy helper remain useful canonical/compatibility infrastructure.
The unrelated `.port`/`.instances` convenience names and old V2 blob set are
still not mandatory. Do not mistake helper-only qualification for constructor,
port/operator or real transaction integration.

The amendment also records `hdlRange` as one target-visible iteration domain
that can produce both structural and procedural regions from typed effects and
declared domains. It replaces the intermediate context-only proposal; ordinary
Scala loops still stay elaboration-time. 43 owns analog generation/shared capture,
55-58/159 own digital semantics/unified iteration, and 96 owns later profiling,
refactoring and optional Rust evaluation. None is a reverse prerequisite for 42.
Keep MLIR authoritative and add necessary real integration without a second
hierarchy engine or a guaranteed tiny line-count estimate.

The main roadmap retains existing progress; the amendment is the only progress
location for its newly added descendants. No existing acceptance checkbox or
historical evidence changes. This publication changes documentation only, with
CI explicitly waived. It neither implements the new syntax nor qualifies a new
compiler candidate. AGENTS.md is unchanged. Preserve the earlier audit below as
the record of the baseline decision, interpreted with this later approval.

## Authority and inspected baseline

This review applies the current [AGENTS.md](../../AGENTS.md) pre-implementation
readiness gate to work already in progress. It records the owner's request to
revisit the increment checklist and determine whether that removes the blocker.
It is not a feature waiver, an implementation acceptance, or a new API freeze.

The inspected integration target is `dev` at
`c3ea7cbf0432de7143a08b4431d51708c307d67e`, tree
`6edbf959d28104a1de62623e8b7e31170232bd64`. Its change from the previous common
base `c24207e47e012d8704da5d6d8e650914b2804f28` is only `AGENTS.md`.
The inspected feature head is
`f82c7f1cf5c30631d6d5bf6f46f2088fa0f0341d`, tree
`026ebe9432ab6698a473ce123e1fbca9140b27c5`.

Read alongside [CONTRIBUTING.md](../../CONTRIBUTING.md), the
[design-gate policy](../design-gates/README.md), the complete
[Foundation roadmap](../roadmap/nodal-development-todo.md), and the
[implementation history](increment42-analog-hierarchy.md).

The roadmap remains the sole editable progress checklist. Its parent state,
child IDs, original feature obligations and historical evidence are unchanged.
The table below classifies those obligations; it is not a second set of
completion checkboxes. No existing child is genuinely non-applicable as a whole,
so none is removed or marked complete by this review.

## Checklist applicability and dependency audit

| Existing ID | Classification and retained obligation |
| --- | --- |
| F-042.A | Required now: reuse the existing module, instance, parameter and terminal models; distinguish module-local functions from child-module connectivity. Any protected API-path changes still require the applicable approved design gate. |
| F-042.B | Required now: aggregate of the three implementation layers; helper-only results cannot close it. |
| F-042.B.1 | Required now: public construction, named child-port boundaries, typed symbolic overrides and legal fixed instance replication, including ownership and rejection checks. The particular convenience names `Instance.port` and `Module.instances` are optional implementation choices, not roadmap requirements. |
| F-042.B.2 | Required now: typed bridge/native bindings, terminal and parameter ownership, recursive hierarchy and cross-owner diagnostics with source locations. Reuse the existing native graph repair; its success does not establish the missing port/override integration. |
| F-042.B.3 | Required now: supported hierarchical Verilog-A emission, retained symbolic overrides, stable identities and reusable definitions. No clone-per-default substitution or source-only prototype counts as this deliverable. |
| F-042.C | Required now: mismatch, illegal override, foreign terminal and recursion negatives, plus proportionate equations/events/module-local-function predecessor combinations. Do not replace integrated rejection checks with helper tests. |
| F-042.D | Required now: aggregate of compiler witnesses and an explicit downstream qualification handoff. |
| F-042.D.1 | Required now: actual public Scala, normalized IR and generated Verilog-A witnesses, internal reparse and retained provenance. Internal parsing is not independent OpenVAF compilation. |
| F-042.D.2 | Required now: identify applicable cases, analyses, references and tolerances for later 48/49/52 qualification. Execution owned by those later increments must not become a reverse dependency for the explicitly compiler-only 42 profile. Missing execution required by a claimed profile remains blocked, never passed or N/A. |
| F-042.E | Required now: review duplicate definitions, symbolic overrides and names. A separate general optimization framework is not a prerequisite; implement fixes actually needed for the supported output contract and record justified no-new-optimization conclusions. |
| F-042.F | Required now: bounded depth, repeated-instance and non-default-parameter coverage with deterministic instance/source identities. Reuse the graph stress infrastructure, but still test frontend and emitted hierarchy independently. |
| F-042.G | Required now: complete evidence, limits, demonstrations, review, final-head qualification, verified integration and any accepted-evidence closure. No helper, documentation or native-only checkpoint closes the increment. |

The boundary with F-043 is retained: 42 must support its legal fixed scalar
instance-replication profile; symbolic/shaped analog arrays, target generation
and generated-instance lexical storage belong to 43. This boundary is not a
reason to remove fixed instance replication from 42. Empty and invalid forms
must receive explicit capability/diagnostic treatment, not accidental behavior.

## Reuse the existing public construction surface first

The inspected `CandidateApi.scala` already supplies:

- `instance(new Child)`, `Instance.apply(select)` and `Instance.param(select, value)`;
- typed signal/conservative-node declarations and `connect(left, right)`;
- ordinary Scala construction, which can express finite elaboration-time loops.

Prefer a source witness using those forms before adding new convenience APIs.
Select a child's declared port through its existing typed selector, and validate
that the actual selected declaration is an allowed port of that immediate child
when constructing the connection/binding. Selector syntax alone is not proof of
ownership: selecting an internal or foreign declaration must still be rejected.

For a fixed collection, first qualify repeated scalar `instance(...)` calls in
an ordinary Scala loop or `Vector.tabulate`. Preserve element identities and
source/index paths. This is a proposed reuse route, not a claim that all array
or generated-state semantics are implemented today.

The unpublished `.port` and `.instances` additions are therefore not mandatory
prerequisites to B.1. Omitting them does not omit named ports or fixed replication.
Keep the existing `.param` surface while enforcing its required staticness,
exact target/owner, duplicate, type and unit rules. In particular, a parent-owned
operand does not make `transition(parentBias)` a static override expression.
If an additional public form proves necessary, justify and freeze that exact
contract through the design-gate policy before relying on it.

## Replace the all-or-nothing unpublished-draft plan

The old B.1 V2 archive and its four blob hashes are snapshots of an unpublished
implementation proposal, not immutable acceptance requirements. Preserve them
as review history, but do not require those exact four files to be published
before useful implementation work can proceed. This review supersedes that
implementation-plan constraint; it does not relax exact-byte verification for
whatever new candidate is actually published.

Use the following work packages under the existing B.1 identity:

1. Specify the boundary using existing source forms and retain an approved gate
   for any protected changes. Put pure static-expression/type/unit/ownership
   policy in a focused internal helper with explicit inputs and isolated tests.
   Do not duplicate mutable construction state or infer ownership from display
   names, reflection or global registries.
2. Add the smallest necessary integration hooks to the existing construction
   transaction, preserving exact declaration/instance identity and source
   provenance. Validate connections and overrides on the actual public path;
   an uncalled helper is not an implementation of B.1.
3. Add integrated public-source positive/negative cases, including the existing
   UInt override and analog parameter-to-parameter predecessors, fixed
   repetition, foreign/internal ports, duplicate/type/unit mismatches and
   stateful override rejection. Format with the pinned tools, then qualify the
   exact candidate through affected targeted CI.

Small coherent helper/test checkpoints may be published atomically on the same
feature branch when useful and honestly labelled as partial. They do not close
B.1, cannot bypass the eventual integration edit, and are not permission to
expose a broken multi-file intermediate commit. Every candidate must have a
fresh exact changed-file inventory and hashes; never mix a rewritten helper
with a stale V2 test, gate or receipt merely because its blob already exists.

Continue B.2 and B.3 after B.1 is compiled and targeted-qualified, except for an
explicitly justified combined repair needed to make the boundary coherent.
Neither broader array/generation work nor a general optimization framework is
a prerequisite for this compiler-profile implementation.

## What this does and does not unblock

This removes two unnecessary process dependencies: adding convenience APIs
before using the existing ones, and materializing the exact old four-file draft
as a single indivisible starting point. It allows focused policy/tests and
source-witness work instead of repeated manual large-blob transfer attempts.
The CandidateApi replacement is no longer inherently required by this plan.

The underlying large-file publication limitation is separate and is not fixed
by changing a checklist. During this review the local runtime still could not
resolve GitHub or Maven hosts, and Remote Desktop reported no connected device.
The connected Git Data actions can atomically publish existing blobs and short
new text, but no verified local-file/patch-to-blob route was available for the
large construction-kernel integration edit. That edit remains required if the
implementation needs it. A smaller helper does not magically make a remaining
large-file replacement transferable.

Do not manually transcribe large source files, reference mismatched blobs, use
sequential contents updates for a coupled candidate, create a source-staging
branch, add a self-publishing workflow, relax compiler contracts, or substitute
string-based ownership merely to evade that limitation. Check a newly available
safe transport only when actionable; otherwise continue useful scoped work or
remain quiet. Do not keep retrying the same known-failing transfer.

## Evidence and continuation

The native hierarchy phase was qualified on `f82c7f1`, with its receipts retained
in PR #134 comment `5797675760`: Core `35874867603`, pipeline `35874885453`,
diagnostics `35874901496`, backend `35874917405` and functions `35874933112`.
These are historical exact-head receipts, not newly executed validation for a
subsequent documentation or source commit. This review neither reruns them nor
relabels them as evidence for an unpublished B.1 implementation.

Follow the imported AGENTS rule: keep one hourly continuation enabled through
implementation, qualification and closure, including while a reported blocker
persists. A persistent blocker alone is not authorization to pause it. The
monitor must read this review and the live refs before acting; it must not race
an active worker, repeat unchanged blocker reports, or retry corrupting transfers.

This change is a documentation-only readiness checkpoint using `[skip ci]`.
No compiler source, workflow, test, capability or roadmap completion state is
changed. No CI, full qualification, merge into `dev` or completion demonstration
is claimed for B.1. The current documentation change does not affect generated
Verilog-*; the unfinished Increment 42 still requires an actual Scala/Verilog-A
demonstration at acceptance.
