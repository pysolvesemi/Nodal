# Increment 31 — Potential and flow access functions

**Status:** Started  
**Baseline:** fully validated Increment 30 closure at `f33bcff3285f17d228bab4c7577bafd35ab32a65`  
**Public API:** unchanged at v0.3  
**Roadmap state:** Increment 31 stays unchecked until native implementation, exact-head CI, merge, and evidence closure complete

## Started scope

Increment 31 begins by freezing the compiler contract for:

- canonical nature-driven potential and flow access resolution;
- generic and discipline-specific access identifiers;
- named/implicit branch access;
- one-terminal and two-terminal unnamed-branch forms;
- local angle-delimited total-port flow access;
- typed quantity results from canonical nature dimensions;
- source-free probe classification and legality;
- deterministic Verilog-A/Verilog-AMS rendering.

The approved design gate is
`docs/design-gates/NodalPotentialFlowAccess-DG-v1.0.md`.

## Implementation sequence

1. Extend nature declarations and resolution helpers with canonical dimensions
   and access-kind queries.
2. Add source-semantic terminal and port-flow access operations while preserving
   the legacy branch-access form.
3. Implement access resolution, quantity typing, stable diagnostics, and
   source/provenance retention.
4. Normalize one/two-terminal forms and source-free probe classifications
   deterministically.
5. Replace hard-coded backend `V`/`I` selection for new typed access with
   verified nature-driven rendering.
6. Add positive, negative, generic-IR, backend, CTest, mutation, and
   idempotence fixtures.
7. Pass the dedicated Increment 31 workflow, Core CI, and inherited workflows
   on one exact artifact-free implementation head.
8. Merge implementation and record accepted evidence in a separate closure PR.

## Current boundary

This starting scaffold intentionally makes no claim that the native access
resolver, normalization pass, diagnostics, or backend changes are implemented.
The manifest remains `implementation-started`; the roadmap item remains
unchecked. The roadmap item remains unchecked until the implementation and
its accepted evidence are complete.
