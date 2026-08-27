# Increment 27 — Natures and disciplines

Increment 27 extends the private `nodal` MLIR dialect with symbolized analog
nature and discipline declarations while keeping public API v0.3 unchanged.

## Implemented vocabulary

- `nodal.nature`: canonical units, access-function identity, and positive finite
  `abstol`.
- `nodal.nature_import`: source- and SHA-256-pinned alias resolving to a canonical
  nature declaration.
- `nodal.discipline`: continuous/discrete domain, required potential nature, and
  optional distinct flow nature.
- `nodal.discipline_import`: source- and SHA-256-pinned alias resolving to a
  canonical discipline declaration.

The compiler library exposes deterministic import resolution and discipline
compatibility helpers. Compatibility compares canonical domain, potential, and
optional flow identity; it does not infer equivalence from matching text.

## Verification

TableGen registration, operation-local verification, native compatibility unit
tests, positive/generic MLIR round trips, negative tolerance/association/import
fixtures, stable diagnostic catalog entries, checker mutation tests, and a
read-only dedicated workflow provide the Increment 27 evidence surface.

Node/branch binding, source lowering, standard-library loading, unit-aware
values, access evaluation, and HDL emission remain deferred to their named
roadmap increments. The existing RC backend therefore remains fail-closed for
these declaration operations.
