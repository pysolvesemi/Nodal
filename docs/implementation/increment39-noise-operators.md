# Increment 39 — Noise operators

**Status:** Implementation in progress

## Implementation profile

White, flicker, and table sources retain their existing public API. The approved
[noise design gate](../design-gates/NodalNoiseOperators-DG-v0.1.md) defines dimensions,
constant proofs, independent source identities, reporting labels, analysis scope,
correlation behavior, and capability rejection. The source is represented by the
owned native `nodal.analog_noise` operation; it is not a pure mathematical function.

Each source is assigned once to a collision-avoiding private real variable. Reusing
its expression shares that source; separately authored calls stay independent even
with identical labels. Zero and unused sources are retained. The independent native
verifier rejects caller-supplied fold claims and reconstructs physical dimensions
from operand definitions. Table ordering is preserved rather than interpreted as
an instruction to interpolate in source order.

## Reproducible public witness

Public Scala source:
`examples/continuousTimeApi/src/nodal/increment39fixture/Increment39ConstructionCheck.scala`.
The `AnalogNoiseSource` example combines symbolic white power, a shared source,
an independent same-name source, flicker noise, an unsorted table, and a zero-power
source. It uses current-squared times seconds as the current-noise PSD dimension.

```sh
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment39fixture.Increment39ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment39MlirCheck /tmp/noise.mlir
nodal-translate --nodal-to-verilog-a /tmp/noise.mlir > /tmp/noise.va
```

Generated HDL is retained in the qualification artifact, not committed as a build
output. Actual source/output evidence and their hashes must be recorded after the
compiler and source witness have executed successfully. This document does not
claim an emitted-output demonstration before that execution.

## Validation and completion state

The executable matrix lives at `tests/compiler/fixtures/increment39/run_native_matrix.py`
and is registered in native CTest. It checks parse/print, fold/canonicalize/CSE
idempotence, single-evaluation semantics, preserved source counts, symbolic defaults,
malformed contracts, units, ranges, tables, and absence of partial HDL on errors.
Public construction/bridge tests and read-only CI add separately compiled source
coverage. Structural reparse is not a third-party simulator or numerical PSD oracle.

Increment 39 remains unchecked until required exact-head checks, review, merge,
post-merge verification, and separate accepted-evidence closure are complete.
Increment 38's accepted evidence is unchanged.

## Explicitly deferred

No numerical noise simulation, solver integration, general Verilog-AMS support,
transient/real-time noise extension, correlation group, symbolic table point,
file/logarithmic table, event/procedural source creation, or equation-region source
is qualified here. Unknown runtime power is not proved nonnegative by accepting a
symbolic expression. Unsupported requests receive a capability diagnostic.
