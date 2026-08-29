# Increment 31 — Potential and flow access functions

**Status:** Validated
**Baseline:** fully validated Increment 30 closure at `f33bcff3285f17d228bab4c7577bafd35ab32a65`
**Implementation PR:** [#88](https://github.com/pysolvesemi/Nodal/pull/88), replacing draft [#87](https://github.com/pysolvesemi/Nodal/pull/87) without changing the validated source head
**Merge commit:** `1662b79f5f99686de4af2ed8a016fe8acf5c784e`
**Public API:** unchanged at v0.3
**Roadmap state:** Increment 31 is checked in roadmap revision 1.41

## Implemented

- Canonical nature declarations may carry a physical `dimension`; typed access
  requires that dimension and produces
  `!nodal.quantity<"real", canonical-nature-dimension>`.
- `resolvePotentialFlowAccessNature` resolves continuous disciplines and their
  potential or flow natures through the existing canonical import machinery.
- `nodal.access` retains legacy branch compatibility while new typed branch
  access carries the authored `function` identity.
- `nodal.terminal_access` preserves one-terminal and oriented two-terminal
  source forms. One-terminal access deterministically records the canonical
  discipline-global reference.
- Named branches remain distinct from endpoint-only two-terminal access during
  probe grouping; only implicit branches may coalesce by endpoint pair.
- Named branch access emits a deterministic Verilog-A/Verilog-AMS branch
  declaration and renders `function(branch-name)`; implicit branch access keeps
  the oriented endpoint form.
- `nodal.port_flow_access` represents local angle-delimited total-port flow and
  remains distinct from branch-probe construction.
- `nodal.probe` records compiler-owned source-free potential or flow probe
  intent, including zero-flow or zero-potential constraint intent and canonical
  source provenance.
- `normalizePotentialFlowAccess` and
  `nodal-normalize-potential-flow-access` provide deterministic, idempotent
  reference and probe normalization.
- The transactional Fast, Default, and Release semantic gates run access
  normalization after conservative-connectivity materialization and before
  mandatory verification.
- The verifier rejects invalid forms, disciplines, natures, access-function
  identities, result dimensions, references, local-port use, mixed source-free
  probe kinds, and forged probe provenance with the nine stable Increment 31
  diagnostics.
- Verilog-A and Verilog-AMS rendering uses the verified authored access
  function for new typed access, including branch, one-terminal, two-terminal,
  and `function(<port>)` forms. Conventional `V`/`I` fallback remains only for
  the explicitly preserved legacy branch-access form.
- Native fixtures cover positive normalization, repeated-pass idempotence,
  canonical reference materialization, every stable rejection family, generic
  and discipline-specific spelling, and backend port rendering.

## Validation

The implementation materialization run `33233642892` compiled the native
dialect and backend, passed all 83 CTest cases twice (direct CTest and the
`check-nodal-native` target), and completed the locked clang-tidy gate across
25 translation units. The materialized native implementation commit is
`06365bc383064c1412db1f2580f876b21b621035`.

The accepted implementation head
`647c79d1d78848c492b3128bd79502b6ef8664de` passed the corrected 20/20
exact-head workflow matrix. Dedicated Increment 31 run `33244625475` passed
repository contracts, mutation tests, the native compiler/backend suite,
diagnostics, normalization, idempotence, and artifact hygiene. Core CI run
`33244625490` passed contracts, Scala, native, and the aggregate required gate.

PR [#88](https://github.com/pysolvesemi/Nodal/pull/88) merged the implementation
into `dev` at `1662b79f5f99686de4af2ed8a016fe8acf5c784e`. Draft PR
[#87](https://github.com/pysolvesemi/Nodal/pull/87) is retained as the original
implementation discussion; #88 used the identical source head because the
connector could not transition #87 out of draft state.

The separate evidence closure records the accepted run set in the Increment 31
manifest, checks the roadmap item, and keeps predecessor/successor state checks
valid in both pre-evidence and validated repository states. Temporary closure
scripts and writable workflows are absent from the permanent diff.

## Compatibility and remaining ownership

The public Scala API remains v0.3. Existing unqualified legacy
`nodal.access` remains accepted; no new typed producer relies on that form.
Increment 32 retains first-class equation construction, additive contribution
semantics, and residual formation. Later continuous-time increments retain
state, event, analysis, noise, and solver semantics.
