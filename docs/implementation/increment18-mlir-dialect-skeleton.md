# Increment 18 implementation: Nodal MLIR dialect skeleton

Increment 18 introduces the first private Nodal MLIR dialect without adding
hardware semantics or changing public API v0.3.

## Source organization

- `core/compiler/include/nodal/Dialect/Nodal/` contains TableGen definitions
  and generated-header front doors.
- `core/compiler/lib/Dialect/Nodal/` contains dialect initialization and the
  placeholder verifier.
- `nodalc` loads both the retained CIRCT HW dialect and the new `nodal`
  dialect.
- `core/compiler/test/IR/` contains valid and invalid textual fixtures.
- `core/compiler/test/Unit/DialectTest.cpp` proves typed operation loading
  and custom parser/printer round-trip behavior.

## Placeholder contract

`nodal.placeholder "label"` is intentionally semantics-free. The `label`
attribute must be non-empty. No later compiler pass may interpret the
placeholder as hardware, scheduling, analog, or backend behavior.

## Validation

The permanent workflow runs the unified repository contracts, Increment 6
compatibility checks, Increment 18 mutation tests, the locked native build,
native unit and CTest suites, custom syntax round trip, generic operation
printing, and negative verifier behavior.

Roadmap completion and run identifiers are recorded only after both the
dedicated workflow and Core CI pass on the pull request.
