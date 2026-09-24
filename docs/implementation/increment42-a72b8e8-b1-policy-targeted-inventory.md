# Increment 42 B.1 policy formatting-repair targeted inventory — a72b8e8

This control-only checkpoint may dispatch exactly one affected workflow on exact
feature head `a72b8e84f779bd00c4349f7a48af854d696397f0`, tree
`1920ebd5dcb43b50558fa89d90f2ab9955f02b7f`, PR #134 -> `dev`.

## Diagnosed parent failure and repair

Parent `fef8f1fc6f22fea071175bb049593c33f42efc23` was targeted by Core CI run
`35969466010`. `native` and `scala` succeeded. `contracts` job `107535534319`
failed only when pinned Scalafmt reported one misformatted file,
`core/scala/api/src/nodal/AnalogHierarchyOverridePolicy.scala`; the aggregate
`required` job then failed from that dependency. The preceding architecture,
contribution-policy, compiler Python, Markdown, package-visibility and other
contracts completed successfully. This is deterministic source failure evidence,
not an infrastructure transient.

The repaired candidate is the direct non-force `[skip ci]` child of `fef8f1f`.
Compare `fef8f1f..a72b8e8` is exactly one file, the policy helper, and only applies
the repository's existing ScalaDoc layout. The new helper blob is
`a1ac0c4371f838ccb0d6d47286237d03fad38594`; policy behavior, isolated tests,
design gate, roadmap state and broader Increment 42 behavior are unchanged.
All-event Actions inventory for exact `a72b8e8` was empty before controller
publication.

This remains deliberately partial F-042.B.1 work. The production construction
transaction still does not call the helper, so no B.1 or parent roadmap checkbox
may be checked from this repair or its qualification.

## Targeted workflow

Core CI is the failed/newly affected workflow requiring fresh exact-head
qualification after the source repair: workflow ID `338626156`, path
`.github/workflows/ci.yml`, Git blob
`db695c972d0fb077cd355cf01d2c204afb6de40f`. Applicable jobs are `contracts`,
`scala`, `native`, and aggregate `required`; on `workflow_dispatch` the push-only
status publication is inapplicable, while `Require every Core CI job` must
execute and pass.

No old-head job is rerun. Increment 15/16 remain PR-only and were not newly
changed by this formatting repair. Increment 20 and analog predecessor targeting
remain for the later real construction/bridge integration head. No full CI,
merge, B.2/B.3 work or completion claim is authorized from this helper repair.

## Controller restrictions

The controller branch is
`ci-control/nodal-42-pr134-a72b8e8-b1-policy-v2`, created exactly from current
`dev` `c3ea7cbf0432de7143a08b4431d51708c307d67e`. The controller requests only
`contents: read` and `actions: write`, executes no feature source, updates no
source refs, performs no rerun/cancel/full-CI/merge action, and may POST only the
Core workflow dispatch payload `{"ref":"increment/42-analog-hierarchy"}` after
exact repo/PR/ref/tree/parent/workflow checks and inherited-trigger audit.

Same-head queued/running/successful Core dispatches are retained. A same-head
failed/cancelled dispatch blocks a new POST for diagnosis. Intent is fsynced
before the single POST; an accepted response that cannot be reconciled to a
matching run is recorded as uncertain and is never retried automatically.
