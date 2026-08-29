# Increment 31 — Potential and flow access functions

**Status:** Implemented — awaiting evidence
**Baseline:** fully validated Increment 30 closure at `f33bcff3285f17d228bab4c7577bafd35ab32a65`
**Public API:** unchanged at v0.3
**Roadmap state:** Increment 31 remains unchecked until exact-head CI, review, merge, and separate evidence closure complete

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

The permanent Increment 31 workflow is read-only. It re-runs the repository and
mutation contracts, the complete native compiler suite with the locked native
and lint toolchains, Markdown/package/contribution checks, and artifact
hygiene. The roadmap item remains unchecked while the implementation is
`implemented-awaiting-evidence`.

## Compatibility and remaining ownership

The public Scala API remains v0.3. Existing unqualified legacy
`nodal.access` remains accepted; no new typed producer relies on that form.
Increment 32 retains first-class equation construction, additive contribution
semantics, and residual formation. Later continuous-time increments retain
state, event, analysis, noise, and solver semantics.
