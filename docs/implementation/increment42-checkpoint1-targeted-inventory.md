# Increment 42 checkpoint 1 targeted inventory

This control-branch record is not an increment acceptance record.

Repository: pysolvesemi/Nodal. PR: 134. Base dev:
c24207e47e012d8704da5d6d8e650914b2804f28. Candidate:
0bff9004582df2300a040db96808b61c9382d02e, tree
ee1c455d77c0dcb4e85bb8cf835de307be3efdf9.

The six-file candidate adds only two dependency-free native helper headers,
two tests, native test registration and the partial implementation record.
The broader Scala/MLIR/emitter integration is not in this candidate.

Targeted workflow inventory, no inputs other than candidate ref:

- 338626156, .github/workflows/ci.yml, definition SHA-256
  52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124.
  Jobs: contracts, scala, native, required; no matrix. Covers the shared style,
  policy, pinned native build/CTest and compatibility gates. The aggregate
  push-status step is inapplicable on workflow_dispatch and earns no execution
  credit; the required job still checks every dependency result.
- 352989730, .github/workflows/increment-41-analog-functions.yml,
  definition SHA-256
  e493d46dfd20853dbe7cf18951b24598c52e06573e1a97d3c5779f9f99e8bb6d.
  Job: functions; no matrix. Covers the latest analog predecessor, actual
  public Scala/IR/target witness and pinned native CTest including the new
  helper tests. It does not validate the unpublished Increment 42 integration.

All inherited workflow push filters were inspected from the unchanged .github
subtree ab23f54dc1857dd5c1997d31a9881f265a516c63, workflows tree
ec9df3df2645be461fe3a722865f370e5b032c60. None matches the exact ci-control
branch used here. Core CI matches increment/**, which this controller avoids.
The sole newly matching push workflow is the allowlisted dispatcher.

The dispatcher validates live repo/PR/ref/tree and workflow identities before
POST, retains existing runs, blocks concurrent other-head runs and never
retries an uncertain POST. Controller reruns cannot POST a missing run.
Seven local unittest methods passed, including identity, definition, write
allowlist, duplicate-context and active-other-head checks. This is controller
validation, not compiler qualification.

After launch, inspect actual candidate checkout/tree, all latest-attempt jobs
and logs. Missing/skipped applicable checks and a green controller are not
passing compiler evidence. Full qualification, implementation completion,
review and merge remain outstanding. No full-CI or source publisher exists
in this controller; it requests only these two targeted workflows.
