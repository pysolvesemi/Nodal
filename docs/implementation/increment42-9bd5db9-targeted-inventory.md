# Increment 42 formatting-repair targeted inventory

This control-only record is not source qualification or Increment 42 acceptance.

Repository: `pysolvesemi/Nodal`; PR 134; feature `increment/42-analog-hierarchy`.
Base `dev`: `c24207e47e012d8704da5d6d8e650914b2804f28`;
base tree: `2528eeac3a23563ec931e737afec03a80b7e6cd1`.
Candidate: `9bd5db950c7e103bf86807276f7dc0814b1a735b`;
candidate tree: `e660f8d0740845a1b69767028c36c134bb94676a`;
parent: `e775281338502b4aea9885796baa5fa0908de1fd`.
Controller branch: `ci-control/nodal-42-pr134-9bd5db9-targeted-v4`.

## Why fresh targeting is required

The parent `e775281` introduced the production iterative hierarchy verifier and
its 559-case native matrix. Its targeted Core, Increment 21, Increment 22 and
Increment 23 workflows all failed at the same pinned clang-format check in
`core/compiler/lib/Transforms/Passes.cpp`; Core native and Scala passed, and the
preceding contract/compiler suites (including 368 compiler Python tests) had
passed before formatting rejected the file. Increment 41 independently finished
successfully on the parent and built the compiler, ran 137/137 CTests including
`nodal.native.hierarchy-integration`, and retained a 559/559 hierarchy result
artifact. These are authentic parent facts, not repaired-head qualification.

Candidate `9bd5db9` is a direct non-force child changing only formatter layout in
that one C++ file. Because source changed, no parent success is reused as
candidate credit. All five affected workflows below are dispatched fresh.

## Affected checks

Every dispatch payload is exactly `{"ref":"increment/42-analog-hierarchy"}`.
All five workflows are active, already registered and support `workflow_dispatch`
with no inputs. The controller validates exact live repository, PR, base, head,
tree, ancestry, workflow IDs/paths/bytes and parent run outcomes before each
write, and rechecks source identity between dispatches.

| Workflow ID | Definition path | Required jobs | Reason |
| --- | --- | --- | --- |
| 338626156 | `.github/workflows/ci.yml` | contracts, scala, native, required | Shared style/source/native/Scala gate; parent formatting failure |
| 342183625 | `.github/workflows/increment-21-native-semantic-pipeline.yml` | semantic-pipeline | Production semantic pipeline consumes hierarchy verifier |
| 342322970 | `.github/workflows/increment-22-cross-layer-diagnostics.yml` | cross-layer-diagnostics | Closing-instance diagnostic/source mapping |
| 342865392 | `.github/workflows/increment-23-backend-framework.yml` | backend-framework | Backend semantic-gate consumption and atomic failure handling |
| 352989730 | `.github/workflows/increment-41-analog-functions.yml` | functions | Native build plus retained current-head hierarchy evidence |

There are eight required jobs; no strategy matrices and no PR-only job
conditions. Core's `required` dependency check must run and pass; its
push-status publication step is inapplicable on `workflow_dispatch` and gets no
test credit.

Candidate workflow SHA256 values, unchanged from the immediate parent because
`e775281..9bd5db9` changes only `Passes.cpp`:

- Core: `52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124`
- 21: `3a26ba42e2140b3f8802a7dec8572e01f97ba2fd9fb87939beef6aa42b125931`
- 22: `ab2e6d0e6bcc8b10ad69bc88b89b2398090346b7c13c932b3fa231bc93d53cbf`
- 23: `6451dee023216644c33748ad149d4d636a95ab5c7fe5c0606d07c1eb4f812087`
- 41: `70d0b2d803180a2104015e3706fb0de7b3617c52a5f46ada2b3a69c863b55ff4`

## Required repaired-head evidence

The repaired native build must execute all 137 registered CTests, including
`nodal.native.hierarchy-integration`. The Increment 41 artifact must retain
`increment42-hierarchy/results.json` from the current built `nodalc` with exactly
559 cases and zero failures, including the 50,000-definition parse control,
acyclic verifier and cyclic back-edge case under a 1 MiB child stack. Preserve
input/output/diagnostic/compiler hashes and the exact source archive; the archive
must reconstruct candidate tree `e660f8d0740845a1b69767028c36c134bb94676a`.
Wrong-head, missing, skipped-applicable, cancelled, timed-out, zero-job or partial
evidence is not passing qualification.

Historical parent Increment 41 artifact `10745457560` has API/upload SHA256
`446944080f9e80528f706c54b4f31b627323cceac0539ad8f405742c5813b967`.
Its retained `results.json` SHA256 is
`9464ee7b075180908d807d480be4a2dc2f2e6db0e7be29f464cf8a1b93663d95`,
records schema `nodal.increment42.native-hierarchy-matrix.v1`, compiler SHA256
`86e46a4dc559c70b567f241eb418e21c7702fae89633287f2bddd637a0064bd2`,
559 cases and zero failures. Its source archive reconstructs parent tree
`07b29d5d89ed09b3b90a617210ab50aa4aed3940`. This is retained only as historical
failed-head evidence and cannot qualify `9bd5db9`.

## Trigger and publication guards

The integration base is byte-identical to the base authenticated before the v3
controller: `c24207e...` / tree `2528ee...`. The authenticated inherited trigger
audit covered all 43 base workflow definitions (audit SHA256
`8a01eb61b5dc7321d69c2082b9962ac339fea3e3b6e1d1760f113dd847a4670e`):
push filters name `dev`, `main` or `increment/...`; there are no inherited
`create`, `workflow_run` or `pull_request_target` triggers; none matches a
`ci-control/...` branch. The candidate changes no workflow after `e775281` and
the new exact controller branch remains outside every inherited push class.
The live base ref/tree is revalidated before dispatch, so movement invalidates
this audit rather than silently broadening it.

The controller itself matches only its exact branch and its own three paths,
starts directly from pinned `dev`, and carries no skip annotation. Permissions
are only `contents: read` and `actions: write`. The only allowed write is POST to
the five workflow-dispatch endpoints with the exact feature-ref payload. It never
executes feature-controlled code, updates source refs, merges, cancels work or
changes repository protections. It fsyncs intent before one POST and reconciles
uncertain responses without automatic POST retry. Active/successful same-head
runs are retained; unsuccessful same-head or active other-head work blocks a new
dispatch.

Whole Increment 42 remains open after this native repair. Public hierarchy
construction, symbolic override DAGs, arrays, bridge/native terminal binding,
Verilog-A hierarchy emission/deduplication, target reparse, public Scala/IR/VA
witnesses, predecessor regressions and full acceptance remain required.
