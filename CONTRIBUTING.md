# Contributing to Nodal

Nodal uses one roadmap increment per pull request. Start from the current `dev`
branch and use a branch named `increment/<number>-<slug>`.

## Pull-request contract

Use the title form:

```text
Increment <number> — <summary>
```

Complete every section in the repository pull-request template. Milestone
promotion pull requests into `main` may originate only from `dev`.

## Prototype checks and merge

The owner-approved 2026-10-18 [prototype policy](AGENTS.md#prototype-validation-and-merge)
applies to every open/future increment and repair. Do not run any CI, targeted
or full qualification, or hourly monitoring. Use `[skip ci]` on commits and the
actual squash-merge message. Old plans, checklists and handoffs that require
remote qualification or separate accepted-evidence closure are superseded for
prototype acceptance; historical accepted results remain unchanged.

Run focused local positive, negative and boundary checks for the changed logic,
plus directly affected regressions. Record commands, results and limitations in
the PR. Each increment needs an architecture/modularity/scalability checklist
review covering shared owners, dependency boundaries and known future consumers.
The implementer may complete this concise review; external Codex review and
exhaustive performance or proof campaigns are not mandatory gates.

Merge a completed scoped branch when those focused checks and architecture
review pass and known in-scope failures are resolved. Use the expected head SHA
and verify the actual squash merge/ref/tree. Respect repository protections;
report a remaining required-status setting instead of fabricating a status or
running forbidden CI. Broader qualification is not required. Fix bugs discovered
later during subsequent increments or from user reports with focused regressions.

Keep concise acceptance evidence and the demonstration below in the same PR;
a separate evidence-closure PR is optional. Do not mark unknown or unexecuted
results as passing, or reopen historical acceptance merely to impose this policy.

## Increment and sub-checklist progress

This rule applies to every roadmap track. New or revised increments should use
one parent `- [ ]` checkbox and nested `- [ ]` checkboxes for independently
trackable implementation, validation, and acceptance tasks. Give children stable
identifiers when they need cross-references, such as `AC-001.a`.

A child may be marked `[x]` once its own deliverable and applicable validation
are complete. Add a brief note with the supporting source commit and focused check results
in the existing checklist; keep generated evidence under ignored `.work/`. Keep the parent `[ ]` while any
required child remains open, any dependency is incomplete, or the increment's
acceptance/evidence gate is outstanding. Partial child completion must not be
reported or counted as completion of the parent increment.

Mark a parent `[x]` only after all required children, dependencies, acceptance
criteria, and completion-report obligations are satisfied. If a completed child
is invalidated or reopened, reopen its parent. Do not silently delete, waive, or
check unfinished children to close an increment; record an explicitly approved
scope change and its rationale instead.

The roadmap's detailed Markdown checklist is authoritative for progress.
Machine-readable manifests should reference the same stable increment/child
identities and dependency rules without introducing a competing editable status
source. This is a tracking policy, not an implemented automatic checkbox-sync
feature. Converting a roadmap to nested checklists must not invent completion
evidence or change historical completion states without review.

## Increment completion demonstrations

This is a standing project instruction for every roadmap track, including
numbered increments and lettered sub-increments. Whenever an increment is
completed and marked `[x]`, include a demonstration in the user-facing
completion report. A checkbox, commit reference, or passing test summary alone
is not a sufficient completion report.

When the increment affects generated HDL:

- Show a small, concrete Nodal Scala example that demonstrates the capability
  supported by the completed increment.
- Show the corresponding actual generated Verilog-* output, or a clearly
  identified relevant excerpt. Name the applicable backend or dialect, such as
  Verilog, SystemVerilog, Verilog-A, or Verilog-AMS; do not imply support for
  unaffected or unsupported backends.
- Explain briefly what the example demonstrates. Include the source path and
  generated-output or fixture path, plus the generation command and relevant
  configuration when needed to reproduce the example.

Use source and output from the completed implementation. Do not label
handwritten, hypothetical, or planned HDL as generated output. When emission
changes are the feature, use before-and-after excerpts where helpful. If an
applicable demonstration is not yet available, report that gap rather than
claiming the completion demonstration is complete.

When the increment does not affect generated Verilog-*, explicitly write:

> The current increment does not affect generated Verilog-*.

Briefly explain why, for example a documentation-only, infrastructure, or
diagnostics change. Still show a Nodal Scala example when it usefully
demonstrates the increment; do not invent a Scala or HDL example when neither
is applicable. A change confined to an analog backend still requires the
applicable generated Verilog-A or Verilog-AMS demonstration, not this fallback.

This reporting requirement supplements, and does not replace, the increment's
existing acceptance criteria, validation evidence, and merge requirements.

## Local validation

Install the pinned toolchain needed by the changed layer (`./nodal bootstrap`
for native tools, `./nodal style bootstrap` for formatters). Run the relevant
formatter and select the affected positive/negative tests and checks. Use filtered
Scala/native/Python tests and representative generated output where appropriate;
record the exact selection in the PR so it is reproducible.

Do not run `./nodal check --online-toolchain --base-ref origin/dev`, all-workflow
qualification, exhaustive matrices or unrelated full suites as routine increment
acceptance. Existing broad commands remain available only for a later explicit
request. Documentation-only edits need focused Markdown/link/checklist and diff
checks. No CI launch or scheduled continuation is needed.

## Formatting and lint rules

Scala code is formatted with pinned Scalafmt and checked with pinned Scalafix.
Native code follows LLVM-compatible ClangFormat and a conservative ClangTidy
rule set. Markdown, package ownership, and contribution policy are checked by
repository-owned scripts with stable diagnostic codes.

Do not suppress a failing rule merely to merge a change. Fix the source, narrow
a rule only with evidence, or record a justified policy change in the same
increment.

## Design-gate changes

A change to either of these surfaces requires an approved design gate in the
same pull request:

- the public API under `core/scala/api/`;
- the core/library boundary, module manifests, architecture enforcement, or
  future `libraries/` packaging contract.

The gate file belongs under `docs/design-gates/`, must follow the versioned
`*-DG-v*.md` naming convention, and must contain both an approved status and the
applicable scope. See [the design-gate policy](docs/design-gates/README.md).

## Commit and review expectations

Keep commits focused on the selected increment. Put generated logs, evidence,
reports, caches and scratch work under ignored root `.work/`; never commit them.
Check Git status and the staged diff before each commit and handoff, stage only
intended paths, and preserve unrelated changes. Explain validation,
compatibility impact, and any approved design gate in the pull-request body.
Resolve review conversations before merge.
