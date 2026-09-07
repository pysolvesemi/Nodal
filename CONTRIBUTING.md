# Contributing to Nodal

Nodal uses one roadmap increment per pull request. Start from the current `dev`
branch and use a branch named `increment/<number>-<slug>`.

## Pull-request contract

Use the title form:

```text
Increment <number> — <summary>
```

Complete every section in the repository pull-request template. A pull request
into `dev` must pass the required Core CI gate before squash merge. Milestone
promotion pull requests into `main` may originate only from `dev`.

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

Install both pinned toolchains once:

```bash
./nodal bootstrap
./nodal style bootstrap
```

Apply deterministic formatting before review:

```bash
./nodal style fix
```

Run the style and policy gate against the integration branch:

```bash
./nodal style check --base-ref origin/dev
```

Run the complete gate before publishing:

```bash
./nodal check --online-toolchain --base-ref origin/dev
```

The same commands run in GitHub Actions. Do not replace them with private CI-only
command sequences.

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

Keep commits focused on the selected increment. Generated build outputs and
local toolchain installations must not be committed. Explain validation,
compatibility impact, and any approved design gate in the pull-request body.
Resolve review conversations before merge.
