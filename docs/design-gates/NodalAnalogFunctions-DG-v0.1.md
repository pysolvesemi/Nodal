# Nodal mathematical and simulator functions design gate v0.1

**Increment:** 38
**Status:** Approved
**Scope:** public-api

## Approved surface

`AnalogMath` supplies the 24 real-valued functions in the closed, versioned registry
`core/compiler/analog-functions-v1.json`. `AnalysisContext.active(AnalysisKind)` now
constructs a simulator query instead of an inert candidate. Function names and target
spellings are not arbitrary user strings. Existing analog event and waveform APIs
retain their contracts. Integer overloads are not implicitly inferred or narrowed.

## Dimensions, constants, and effects

Absolute value preserves physical dimensions. Min, max and hypot require equal
argument dimensions and preserve them. Atan2 requires equal dimensions and produces
a dimensionless angle in radians. Square root halves even dimension exponents;
nonintegral dimension powers are rejected. All other entries require dimensionless
arguments, including powers, floor and ceil. A zero voltage is not dimensionless.

Only proven real constants may be evaluated. Parameter defaults, held initializers,
analysis selection, and caller-supplied fold annotations do not establish constness.
Domain-invalid proven arguments and nonfinite results reject rather than clamp or
produce NaN. Unknown runtime arguments remain unknown; this does not prove their
runtime domains safe. Signed-zero min/max select the right argument on equality.
Constant evaluation uses the pinned host floating-point toolchains, not an exact
symbolic algebra engine or a cross-simulator bit-equivalence promise. Native folding
retains the function operation and source metadata, recording an advisory value with
registry-specific provenance. Backend spelling remains registry-controlled.

Analysis queries return Boolean expressions, read simulator state, and are never
constant-folded, speculated, or classified as static initializers. The mappings are
Initialization to `ic`, Dc to `dc`, OperatingPoint to `static`, Transient to `tran`,
Ac to `ac`, and Noise to `noise`. These phases can overlap: `static` includes operating
point solves preceding other analyses, not only a standalone DC command. Initialization
here means the operating-point initialization preceding transient analysis; initial
and final step events remain separately represented by Increment 37.

## Representation and validation

The registry version and semantic function or analysis identity survive construction,
bridge serialization and typed native IR. Arity, real/Boolean kinds, dimensions and
constant domains are checked independently at the compiler boundary. Legacy f64 units
are reconstructed from definitions, not accepted from a function's claimed result.
Source expressions in event-controlled statements use the same native registry and
retain ordered variable reads. Analysis queries cannot initialize persistent storage.
Unknown identities, versions, target spellings and unsupported types fail closed.

Acceptance requires generated-table consistency, construction and negative tests,
separately compiled public witnesses, native parse/print and optimization checks,
malformed IR, parameter-default and forged-annotation tests, target spelling checks,
predecessor regressions, exact-head CI and a separate evidence closure. No validation
or merge is pre-approved by this gate.

## Scope boundary and references

This profile covers continuous mathematical expressions and queries in the existing
supported event-controlled procedure profile. It does not remove prior restrictions
on event-free procedural lowering or other unsupported contexts. Noise, Laplace/Z,
user-defined functions and environment access remain with Increments 39-44. Stateful
`limexp`, random functions, simulator tasks and solver-specific extensions are not
misclassified as pure mathematics. Numerical simulator qualification remains separate
from structural target acceptance.

Target function and analysis semantics follow the primary simulator reference:
[SIMetrix Verilog-A functions](https://www.simplistechnologies.com/documentation/simetrix/verilog_a_reference/topics/verilog_areference_verilog_afunctions.htm).
In particular, Nodal `log10` emits Verilog-A `log`; `ln` remains natural logarithm.

## Approval evidence

Approved by the project owner's explicit request to implement Increment 38. This
approval authorizes the scoped API and implementation, not a claim of passing tests.
