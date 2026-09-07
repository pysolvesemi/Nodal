# Nodal noise operators design gate v0.1

**Increment:** 39
**Status:** Approved
**Scope:** public-api

## Approved surface

Implement the existing `whiteNoise`, `flickerNoise`, `tableNoise`, `NoiseId`,
`NoisePoint`, and `NoiseOptions` signatures without introducing raw target-language
strings. A source is a real-valued, effectful small-signal noise operator. This
profile accepts independent sources in unconditional `analog` regions. Unsupported
contexts and options reject explicitly instead of losing semantics in the bridge.

## Source identity, analysis, and correlation

Each API call creates one owned source identity that survives Scala capture,
serialization, native verification, optimization, and Verilog-A emission. A shared
expression is evaluated once and reused. Distinct calls remain independent even
when they share a reporting label. Neither common-subexpression elimination nor
constant folding may merge, remove, or duplicate noise sources; this includes
unused sources and zero power. Labels are nonempty printable ASCII without quotes
or backslashes. Spaces and punctuation within this portable profile are supported.

Only `AnalysisApplicability.only(AnalysisKind.Noise)` is accepted. The target
follows standard small-signal semantics: sources are active during noise analysis
and return zero in other analyses. No elaboration-time analysis folding is allowed.
Simulator extensions for transient/real-time noise are outside this profile.
`NoiseCorrelation.Group` is rejected; identical names do not implement correlation.
Sharing one evaluated expression is the supported correlation mechanism. Noise
modulation of another noise source is also rejected in this initial profile.

## Dimensions, constants, and table semantics

Power is a spectral density, not amplitude: its dimension is result squared per
hertz (equivalently result squared times time). For example, `A * A * s` produces
current noise and `V * V * s` produces voltage noise. Dimensionless noise has a PSD
with time dimensions. Physical exponents after dividing by time must be even.
Zero-valued arguments do not erase their physical units.

Proven powers must be finite and nonnegative. Symbolic or runtime white/flicker
power stays symbolic; accepting an unknown value is not proof of its runtime domain.
Parameter defaults do not establish a constant. Flicker power is the PSD at one
hertz; its exponent is a finite dimensionless constant, including zero, negative,
and fractional values. No exponent-dependent unit is assigned to the coefficient.

A table is a nonempty sequence of frequency/PSD pairs. This initial capability
requires proven constant points. Frequencies are finite, nonnegative, in hertz,
and unique; PSDs are finite, nonnegative, and dimensionally equal. Input order is
retained. The Verilog-AMS standard defines frequency sorting, linear interpolation,
and constant endpoint extrapolation, including a single-point constant spectrum.
The backend emits an assignment pattern to `noise_table`, not a sampled random
number generator or a file-based table.

## Independent checks and acceptance

The native dialect independently checks kind, version, arity, types, dimensions,
proven domains, source identity, ownership, labels, analysis/correlation capability,
constant-table proof, and forged fold annotations. Advisory result dimensions and
parameter defaults are not trusted. Each source lowers to one module-local real
variable assignment, with deterministic collision avoidance, before its uses.
An independent expression parser checks emitted call grammar and arity.

Acceptance requires separately compiled public Scala, source-derived MLIR, native
malformed-input and optimization tests, structural target reparse, source-map and
identity checks, exact-head CI, review, post-merge validation, and separate evidence
closure. The approval of this gate does not certify any of those results.

## Boundaries and reference

Procedural/event-controlled noise creation, equation-region noise, generated arrays,
correlation groups, symbolic table points, file/logarithmic tables, transient-noise
extensions, numerical solver execution, and general Verilog-AMS qualification are
not claimed. Existing mathematical, waveform, and event contracts remain unchanged.

Semantics follow sections 4.6.4.1–4.6.4.6 of the primary
[Accellera Verilog-AMS LRM 2023](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf).

## Approval evidence

The project owner's explicit request to implement Increment 39 authorizes this
scoped implementation of the existing API. Required validation and merge gates are
not waived.
