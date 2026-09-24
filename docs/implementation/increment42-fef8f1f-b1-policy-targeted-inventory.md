# Increment 42 B.1 policy targeted inventory — fef8f1f

This control-only checkpoint may dispatch exactly one affected workflow on exact
feature head `fef8f1fc6f22fea071175bb049593c33f42efc23`, tree
`7b692238537801b8be7991f94fa3c3a28eb3b8df`, PR #134 -> `dev`.

## Candidate scope

Compared with parent `29705bb8722541ba01a585a6178d625a66bfe424`, the feature
adds exactly:

- `core/scala/api/src/nodal/AnalogHierarchyOverridePolicy.scala` — private pure
  fail-closed policy helper, Git blob `706dd8ebb5db9da6e8cb4ec0b6059e0debfe19ac`;
- `core/scala/testkit/test/src/nodal/internal/testkit/AnalogHierarchyOverridePolicyTests.scala`
  — isolated 12-case policy suite, blob `c35cbada3da028b0921682e0d9fb9b78333c8c0c`;
- `docs/design-gates/NodalAnalogHierarchyConstruction-DG-v0.1.md` — approved
  revised existing-API construction gate, blob `7e5105313de1612ae5c91262f5fb1a436760635b`.

This is deliberately partial F-042.B.1 work. The production construction session
does not call the helper yet; no B.1 roadmap checkbox may be checked from this
checkpoint.

## Targeted workflow

Core CI only is newly affected for this isolated helper checkpoint because it
provides the repository contribution/style/contracts gate and compiles/tests the
whole Scala core including the new utest suite. Workflow ID `338626156`, path
`.github/workflows/ci.yml`, exact current Git blob
`db695c972d0fb077cd355cf01d2c204afb6de40f`. Required jobs are `contracts`,
`scala`, `native`, and aggregate `required`; on `workflow_dispatch` the push-only
status publication is inapplicable, but `Require every Core CI job` must execute
and pass.

Increment 15/16 PR workflows are not dispatched for this partial checkpoint:
their current definitions have no `workflow_dispatch`, and this checkpoint does
not alter the existing public method surface or construction transaction they
freeze. Their relevant compatibility obligations remain mandatory for the later
integrated B.1 candidate. Increment 20 and analog predecessor workflows become
affected when the real construction/bridge path is integrated; old-head success
is never credited to that future source head.

## Controller restrictions

The controller branch is
`ci-control/nodal-42-pr134-fef8f1f-b1-policy-v1`, direct from current `dev`
`c3ea7cbf0432de7143a08b4431d51708c307d67e`. The controller requests only
`contents: read` and `actions: write`, executes no feature source, updates no
source refs, performs no rerun/cancel/full-CI/merge action, and may POST only the
Core workflow dispatch payload `{"ref":"increment/42-analog-hierarchy"}` after
exact repo/PR/ref/tree/parent/workflow checks and inherited-trigger audit.

Same-head queued/running/successful Core dispatches are retained. A same-head
failed/cancelled dispatch blocks a new POST for diagnosis. Intent is fsynced before
the single POST; an accepted response that cannot be reconciled to a matching run
is recorded as uncertain and is never retried automatically.
