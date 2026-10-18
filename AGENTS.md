# Nodal project instructions

These instructions apply to the whole repository. A more specific `AGENTS.md`
may narrow them for its subtree. A direct user instruction takes precedence.
Read [CONTRIBUTING.md](CONTRIBUTING.md) before implementing an increment.

## Prototype execution policy — 2026-10-18

The owner has approved faster prototype development for every current and future
increment and sub-increment, across Foundation and dependent tracks. This
supersedes the former targeted/full CI, hourly continuation, mandatory independent
Codex review, exhaustive qualification and separate evidence-closure process.
Apply it to open work, including Foundation 43. Historical accepted checkboxes,
results, hashes and evidence remain historical facts; do not rewrite them.

Do not run or dispatch any CI, create a CI launch controller, or enable hourly
monitoring for prototype development. Publish source and documentation commits
with `[skip ci]`, and include it in the actual squash-merge message. Do not
resume an old controller or monitor because an older handoff asks for it.
Existing workflows and qualification harnesses may remain for a future explicit
request; their presence is not an instruction to execute them. A later explicit
owner request is needed to restore CI or full qualification.

Prototype acceptance means the selected implementation has passed its focused
local checks and architecture review. It is not exhaustive certification of
Nodal, every parameter combination or every downstream tool. Fix newly found
bugs when a later increment or user report exposes them, with a focused
regression. Do not delay every increment to prove the absence of future bugs.

## Increment readiness and scope

Before implementation, refresh the integration target, current feature branch,
PR discussion and handoff. Read the applicable checklist, linked design records
and affected production paths. Reuse the existing increment branch and PR;
start a new increment from current `dev` only when starting that increment.
Do not replace branches or compete with another active worker.

Identify the selected feature, affected owners and consumers, positive and
negative cases, and explicit unsupported cases. Preserve stable checklist IDs,
feature semantics, scope and parent/child states. A child is complete when its
own deliverable and focused validation are complete; keep a parent open while
required implementation children remain unfinished. Record a scope change
explicitly instead of silently deleting work. This dated prototype process
change is owner-authorized and applies to older qualification wording in plans,
design gates and implementation handoffs.

Keep public API/core-library boundary design decisions concise and explicit in
the existing design-gate record. No new framework, speculative rewrite or large
qualification matrix is required just to satisfy the readiness review.

## Shared infrastructure and maintainable implementation


For every new or in-progress implementation increment, sub-increment and repair,
apply this rule during the readiness review and verify it again against the
implemented production path before claiming completion. Revisit the decision
when relevant requirements, dependencies or the implementation change.

Choose the simplest maintainable design that meets the required semantics and
scale for current and known forthcoming consumers. Fix underlying problems at
the narrowest correct shared layer; do not accumulate increment-specific
workarounds around a missing or incorrect base implementation.

1. Inspect the live base, canonical representations, existing algorithms and
   actual call sites. Compare reuse, a focused shared-layer correction/refactor,
   and a new component when justified. Record the owning layer, alternatives,
   known consumers and rationale; existing infrastructure may be sufficient.
2. Keep one authoritative owner for each semantic representation and mutable
   registry, with narrow interfaces and explicit dependency direction. Preserve
   ownership, types, effects, source identity and capability boundaries. Avoid
   duplicate engines, copied policy, fixture recognizers and special cases that
   bypass the shared contract. Independent verification at trust boundaries
   remains required; shared semantics do not justify deleting native checks.
3. Integrate the selected design into the real production path. Uncalled helpers,
   parallel registries and example-only success do not establish implementation.
   Preserve supported old and new consumers with applicable positive, negative,
   predecessor and source/output-parity tests. Remove superseded internal paths
   only with evidence; do not weaken assertions or disable failing affected cases to accommodate
   the new design.
4. Review relevant time/memory growth, repeated traversals, copies, recursion,
   graph expansion and coupling. Use proportionate representative measurements
   when performance or scale may change; distinguish measured results from
   expectations. File length, language choice and transport inconvenience alone
   are not performance evidence. No universal speedup or full benchmark program
   is required for every increment.
5. Keep the work bounded. A small correct shared-layer patch is welcome; do not
   require a speculative framework, broad rewrite or language migration. If a
   prerequisite correction is essential, record its owner and dependency through
   the readiness/scope process. Assign optional improvements to their existing
   owners without reverse dependencies or silently deferring required work.
6. Retain one concise decision and outcome record in the existing PR/readiness
   or implementation document, linked to the relevant code and validation. Use
   existing architecture, implementation, review and scale children; add a
   stable architecture-review child only where missing. Do not create a second
   progress ledger. Before completion, confirm that the actual implementation
   still satisfies the recorded decision and applicable evidence requirements;
   a design proposal alone is not completion evidence.

Apply this prospectively; do not reopen historically accepted increments merely
to impose the new process. Documentation-only and architecture-only changes
record proportionate applicability, not artificial production or benchmark work.
Apply the focused prototype validation and merge policy below to all of this
work; this architecture review is not an exhaustive qualification program.

## Architecture review for every increment

Every increment and sub-increment must have an explicit checklist item covering
architecture, modularity and scalability. Reuse and strengthen its existing
architecture child where present. For new work without one, add a stable child
ID in that increment's authoritative roadmap; do not create a second ledger.
Completed historical increments are not reopened to add a new checkbox.

Review before implementation and again against the actual result:

- canonical ownership, reuse and module boundaries;
- dependency direction and interfaces for known later roadmap consumers;
- avoidable duplication, special cases and coupling;
- time/memory growth, repeated traversals, recursion and generated expansion;
- concrete future bottlenecks and a focused remedy or documented follow-up.

A concise implementer review in the PR or implementation note is sufficient.
Use independent review when useful or requested, but do not block the prototype
on an external Codex review service or an expensive benchmark program. Measure
only when a specific affected scaling concern needs evidence. Do not claim a
performance improvement that was not measured.

## Prototype validation and merge

1. Run focused local positive, negative and boundary-case tests/checks for the
   affected increment and changed logic. Include directly affected predecessor
   regressions when a shared layer changes. Prefer existing filtered suites and
   representative compiler/target witnesses; do not run unrelated repository-wide
   suites, full qualification, exhaustive matrices, numerical/formal campaigns
   or any CI as a routine per-increment gate.
2. Retain runtime semantic verifiers and useful negative tests. This policy
   reduces validation breadth and ceremony, not the meaning of the implemented
   feature. Never turn a known failure into a pass by disabling its check. Fix
   a confirmed in-scope bug and add a small regression; record unsupported
   behavior and unrelated follow-up work honestly.
3. Record the commands, selected cases, outcome, source revision and limitations
   in the existing PR/implementation note. A missing tool or unexecuted test is
   not a pass. Use an equivalent focused check where possible and state any
   remaining limitation. Large logs, sealed full-matrix receipts, separate
   accepted-evidence PRs and remote CI run IDs are not required for prototype
   acceptance. Preserve existing historical evidence without relabeling it.
4. An increment branch may be squash-merged into `dev` once its scoped feature
   work, focused positive/negative checks and architecture review are complete,
   with no known unresolved in-scope failure. Broader testing is not a merge
   prerequisite. No mandatory independent Codex review, full suite, simulator
   certification or CI status is imposed by the prototype process.
5. Before an authorized merge, refresh head/base, preserve concurrent changes,
   use the expected head SHA, and include `[skip ci]` in the squash message.
   Verify the actual merged flag, commit parent, target ref and merged tree
   against the reviewed source. Record `ci: not-run (prototype policy)`; never
   report skipped checks as passed. If GitHub protection still requires a CI
   status, report that concrete repository-setting blocker; do not fabricate
   statuses, bypass protections or launch CI against the owner's instruction.
6. Update roadmap children and the completion report in the same increment PR
   when their deliverables are ready. A separate evidence-closure PR is optional,
   not a gate. Include the actual generation demonstration described below.
   Unknown bugs discovered later are repaired in the then-current development
   work or a focused bugfix, with a regression for the reported behavior.

Documentation-only policy or handoff updates need only affected document/link,
checklist consistency and diff checks. Do not run compiler suites for them.

## Handoff and monitoring

Keep hourly continuations disabled. Work resumes when the owner or destination
agent asks, not through scheduled polling. At handoff, publish all substantive
source work append-only, record branch/PR/head, focused results, known failures,
remaining feature work and next steps, and release any worker lease. Preserve
other workers' changes. An older handoff cannot override this process or restart
CI. Do not recreate duplicate local commits already published under equivalent
remote commits.

## Repository hygiene

Keep generated logs, evidence, reports, caches and scratch work under the ignored
repository-root `.work/` directory. Never commit those generated artifacts or
force-add ignored files. Configure temporary/output/cache paths there for new
work; preserve existing ignored tool/build directories until a safe migration
is appropriate. Do not remove tracked historical fixtures/evidence or unrelated
files as part of cleanup.

Record verified completion with `[x]` and brief notes in the existing authoritative
checklists: what changed, the focused checks/results and relevant source commit.
Keep a parent open until its required children are complete. Do not create a
second progress ledger or commit generated evidence bundles/reports in place of
these notes. Source tests and maintained fixtures remain normal source files;
new raw outputs and run receipts belong in `.work/`.

Run `git status --short` before starting edits, before each commit, and before
handoff. Review the staged diff, stage only intended paths and preserve unrelated
changes. Never use broad cleanup/reset/staging to hide another worker's changes.
Confirm no `.work/` artifact is staged and report any remaining worktree changes
at handoff.

## Publication and completion demonstration

Preserve source ancestry: no force-push, rebase or rewrite of published feature
history. Nodal's final squash merge into `dev` remains the integration policy.
Do not commit build outputs, downloaded toolchains or large temporary logs.

Whenever an increment is completed and marked `[x]`, follow
[Increment completion demonstrations](CONTRIBUTING.md#increment-completion-demonstrations).
Show a concrete Nodal Scala example and corresponding actual generated Verilog-*
when applicable, identify the backend and relevant paths, and explain the result.
Do not substitute hypothetical output for actual generation.

When the increment does not affect generated Verilog-*, explicitly state:

> The current increment does not affect generated Verilog-*.

Add a brief reason and a Scala example only when useful and applicable.
