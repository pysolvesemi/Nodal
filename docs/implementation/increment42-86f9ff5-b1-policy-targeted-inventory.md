# Increment 42 B.1 policy formatter-repair targeted inventory — 86f9ff5

This control-only checkpoint may dispatch exactly one affected workflow on exact
feature head `86f9ff5d2d5da1d708f6ecb8b78b7e69dbfeedf7`, tree
`0f75e1d6aa0628199249464da90f7b9d237944fd`, PR #134 -> `dev`.

## Diagnosed exact-head failure and repair

Exact source head `a72b8e84f779bd00c4349f7a48af854d696397f0`
was targeted by Core CI run `35974436004`. The controller ledger artifact
`10796979546` records exactly that dispatch with no uncertain response.
Actual latest-attempt jobs/logs were inspected: `native` and `scala` succeeded;
`contracts` job `107551466122` passed architecture, toolchain, compiler Python,
Markdown, package-visibility and contribution-policy checks, then failed only
when pinned Scalafmt reported
`core/scala/api/src/nodal/AnalogHierarchyOverridePolicy.scala`. Aggregate
`required` failed solely from that dependency. This is deterministic source
failure evidence, not an infrastructure transient, so the old run is not rerun.

The feature later advanced through user-authorized documentation-only commit
`c4d268dc9ff364f400cd9e77bda84f3c057c895c`; those roadmap/gate changes did not
modify compiler source and intentionally ran no CI. The repaired source successor
`86f9ff5d2d5da1d708f6ecb8b78b7e69dbfeedf7` changes exactly one file from `c4d`:
`core/scala/api/src/nodal/AnalogHierarchyOverridePolicy.scala`. Its old blob
`a1ac0c4371f838ccb0d6d47286237d03fad38594` was independently reconstructed from
the fetched bytes. The replacement blob
`e3ea99bf13c88eb67a2b2e4fe0f42075934b073f` was computed locally and returned
identically by GitHub. The patch only places the multiline parent-ownership
condition in the repository's existing formatted Scala 3 continuation layout;
policy semantics, tests, approved roadmap/gates and all F-042 completion states
are unchanged. Exact-head all-event Actions inventory was empty before controller
publication.

This remains partial F-042.B.1 work. The production construction transaction
still does not derive authoritative evidence/call the helper, and the newly
approved constructor/direct-port/`<>` descendants remain open.

## Targeted workflow

Core CI is the failed/newly affected workflow requiring fresh exact-head
qualification: workflow ID `338626156`, path `.github/workflows/ci.yml`, Git blob
`db695c972d0fb077cd355cf01d2c204afb6de40f`. Applicable jobs are `contracts`,
`scala`, `native`, and aggregate `required`; on `workflow_dispatch` the push-only
status publication is inapplicable while `Require every Core CI job` must execute
and pass.

No old-head job is rerun. No full CI, merge, B.2/B.3 work or completion claim is
authorized from this formatting repair. Later real public/construction integration
must receive its own affected-workflow inventory and targeted qualification.

## Controller restrictions

The controller branch is
`ci-control/nodal-42-pr134-86f9ff5-b1-policy-v3`, created exactly from current
`dev` `c3ea7cbf0432de7143a08b4431d51708c307d67e`. The controller requests only
`contents: read` and `actions: write`, executes no feature source, updates no
source refs, performs no rerun/cancel/full-CI/merge action, and may POST only the
Core workflow dispatch payload `{"ref":"increment/42-analog-hierarchy"}` after
exact repo/PR/ref/tree/parent/workflow checks and inherited-trigger audit.

Same-head queued/running/successful Core dispatches are retained. A same-head
failed/cancelled dispatch blocks a new POST for diagnosis. Intent is fsynced
before the single POST; an accepted response that cannot be reconciled to a
matching run is recorded as uncertain and is never retried automatically.
