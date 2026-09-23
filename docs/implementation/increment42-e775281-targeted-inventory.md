# Increment 42 native hierarchy targeted inventory

This control-only record is not source qualification or increment acceptance.

Repository: `pysolvesemi/Nodal`; PR 134; feature `increment/42-analog-hierarchy`.
Base `dev`: `c24207e47e012d8704da5d6d8e650914b2804f28`;
base tree: `2528eeac3a23563ec931e737afec03a80b7e6cd1`.
Candidate: `e775281338502b4aea9885796baa5fa0908de1fd`;
candidate tree: `07b29d5d89ed09b3b90a617210ab50aa4aed3940`;
parent: `17394368c3ab57bcf88e7b551407690754a4b166`.
Controller branch: `ci-control/nodal-42-pr134-e775281-targeted-v3`.

## Affected checks

Every dispatch payload is exactly `{"ref":"increment/42-analog-hierarchy"}`.
All five workflows already exist and contain `workflow_dispatch` with no
inputs. The controller validates live registration, exact definitions, refs,
repository and PR before dispatching and rechecks between dispatches. It does
not modify the default branch or install a new candidate workflow to dispatch.

| Workflow ID | Definition path | Required jobs | Reason |
| --- | --- | --- | --- |
| 338626156 | `.github/workflows/ci.yml` | contracts, scala, native, required | Shared style, source policy and native/Scala gate |
| 342183625 | `.github/workflows/increment-21-native-semantic-pipeline.yml` | semantic-pipeline | Changed production hierarchy verification, all gate profiles and 13 failure stages |
| 342322970 | `.github/workflows/increment-22-cross-layer-diagnostics.yml` | cross-layer-diagnostics | Deterministic closing-instance locations and diagnostic mapping |
| 342865392 | `.github/workflows/increment-23-backend-framework.yml` | backend-framework | Backend semantic-gate consumption, exact goldens and atomic failures |
| 352989730 | `.github/workflows/increment-41-analog-functions.yml` | functions | Changed evidence retention and latest compiler/public-source predecessor |

There are eight required jobs in this targeted set; no strategy matrices and
no PR-only job conditions. Core's `required` job must execute and pass; its
push-status publication step is inapplicable on dispatch and gets no test
credit. Conditional failure-artifact retention is not a qualification job.
Targeted IDs are not the whole required full-CI inventory. Other predecessor
checks remain in the normal CTest and Python suites; all applicable full-CI
workflows remain required after complete implementation targeting.

Candidate definition SHA256 values:

- Core: `52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124`
- 21: `3a26ba42e2140b3f8802a7dec8572e01f97ba2fd9fb87939beef6aa42b125931`
- 22: `ab2e6d0e6bcc8b10ad69bc88b89b2398090346b7c13c932b3fa231bc93d53cbf`
- 23: `6451dee023216644c33748ad149d4d636a95ab5c7fe5c0606d07c1eb4f812087`
- 41: `70d0b2d803180a2104015e3706fb0de7b3617c52a5f46ada2b3a69c863b55ff4`

## Required new-source evidence

The native CTest build must execute `nodal.native.hierarchy-integration`
against this candidate's built `nodalc`. Its 559 actual compiler cases include
512 exhaustive directed graphs with self-edges and independent Kahn outcomes,
deterministic primary source diagnostics, three semantic gate profiles, repeat
and parse/print identity, wider seeded DAGs, and the 50,000-definition parse
control plus acyclic/cyclic verifier cases under a 1 MiB child stack.
Require all 559 cases successful, no crash/timeout or partial rejected output,
plus all inherited CTest cases. The Increment 41 artifact retains this matrix
under `increment42-hierarchy/`, including commands, input/output/binary hashes,
source archive/tree and genuine execution logs. The full source archive must
reconstruct the candidate tree above. Missing evidence is not a pass.

Prior helper qualification belongs only to parent `17394368`: Core run
35825288843 and Increment 41 run 35825298112. The new controller verifies those
as predecessor identities, never as candidate test credit. The baseline
compiler exposed 12 failures in the new 559-case matrix; those are historical
failure evidence, not waived cases. Local candidate checks passed 368 compiler
Python tests and checkers 19-23/41; no local pinned native compilation is
claimed. The controller passed 14 local unit-test methods covering identity and
hash mismatch, write allowlisting, deduplication, unchanged active work,
pagination, failed-run refusal and uncertain-response reconciliation without
POST retry. These test the dispatcher, not the compiler.

## Publication and scope guards

Only this exact controller push may match. Every inherited workflow on the
unchanged base was inspected: each push filter names `dev`, `main` or specific
`increment/...` patterns, none matching this `ci-control/...` branch. There are
no inherited create, workflow_run or pull_request_target triggers. The
controller's push filter names only its exact branch and its three own paths.
The launch commit starts directly from the pinned base and does not use a skip
annotation. It is never merged into source or integration branches.

Permissions are contents read and actions write only. The only allowed write
is workflow dispatch for the five IDs/payload above. No source publication,
merge, arbitrary candidate execution, credential extraction, cancellation,
workflow alteration or protection change occurs. Intent is fsynced before a
single POST; uncertain responses are reconciled, never automatically reposted.
Successful or active same-head runs are retained; failed same-head runs and
active other-head runs stop dispatch for diagnosis. Controller attempt two
cannot POST a missing run. The run records its real controller SHA and ledger.

Whole Increment 42 remains open. Public construction, symbolic overrides,
arrays, native terminal bindings, Verilog-A hierarchy emission/deduplication,
strict target reparse and actual Scala/MLIR/Verilog-A witnesses remain required.
No full CI, merge, numerical/OpenVAF execution or completion is claimed here.

## Inherited trigger audit identity

All 43 inherited YAML definitions were inspected from the authenticated parent
tree `784e8b13c64337cbaeb89b771c0da61370cf4573`. The parent has the same workflow
bytes as base `c24207e47e012d8704da5d6d8e650914b2804f28`; helper changes touched no
workflows. The local audit records each path, SHA256, event/filter object and
false control-push match. It has SHA256
`8a01eb61b5dc7321d69c2082b9962ac339fea3e3b6e1d1760f113dd847a4670e`.
The candidate's sole workflow change is the Increment 41 evidence-retention
step; its triggers remain identical. The current base ref/tree is revalidated
by the controller before any dispatch, so this audit cannot authorize a moved
integration base.
