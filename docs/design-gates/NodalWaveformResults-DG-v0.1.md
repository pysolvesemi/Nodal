# Typed waveform results and bounded streaming v0.1

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 51

## Authority and owners

The owner's explicit request to implement all unchecked Foundation increments
from 43 authorizes this F-051 contract and necessary shared repairs. Standing
public publication and completed-increment integration approval applies with
`[skip ci]`. This is the selected implementation contract, not a claim that the
owner separately chose each signature. No CI or exhaustive qualification is added.

`core.integrations` owns the existing ngspice raw protocol, simulator execution,
requested-grid verification and artifact provenance. `core.scala.sim` owns typed
waveforms, physical axes, interpolation and comparison. One bounded stream parser
serves both collected and non-collecting consumption. The existing simulator path
selects a collector or fold; no second tool runner/parser is introduced.

## Typed axes and values

`WaveformAxis[A]` uses existing quantities for time, voltage and current, and the
existing `Frequency` value for frequency. Time is finite and nonnegative; frequency
is finite and positive. Time/frequency coordinates strictly increase. Electrical
sweeps may strictly increase or decrease, with no direction changes or duplicates.
One-point traces are valid. A waveform preserves its axis descriptor, signal unit,
complex values and descriptive provenance. Public signal dimensions reuse existing
quantity and probe types. Wrong/missing axes, foreign probes, incompatible units
and nonfinite samples reject. The real part of the AC frequency field is the
physical coordinate; the finite writer-dependent imaginary slot is not a second
axis. Existing raw metadata remains protocol metadata, not automatically trusted
physical units.

Collected waveform views index existing rows without copying whole columns.
Authored reference waveforms are explicitly distinct from executed-model evidence.
Optional hash strings in descriptive provenance are validated but do not authorize
filesystem reads or turn arbitrary user data into a verified simulator artifact.
Deterministic bounded serialization preserves selected axis/unit, complex values,
order and provenance. Decoding requires expected typed axis/unit and independently
validates the serialized schema and samples.

## Interpolation and comparison

Exact coordinate alignment is the default and requires equal coordinate sequence
and count. Explicit linear alignment samples the reference at each actual physical
coordinate, inside its closed domain. There is no extrapolation. Endpoints return
exact values; an isolated one-point reference supports only its exact coordinate.
Real and imaginary parts are interpolated first, then the selected real, imaginary
or magnitude projection is compared. Descending sweeps use the same physical
interpolation rule. Checked arithmetic rejects nonfinite derived results rather
than clamp or fabricate a match.

Comparison uses the existing absolute + relative*abs(reference) tolerance rule,
with finite nonnegative tolerances and checked allowance. The shared incremental
reducer records sample/failure counts and finite error summaries. A numerical
mismatch is a completed comparison with failures, not a simulator process failure.
Malformed/incompatible data or a late parse error cannot yield a passing result.
No waveform comparison establishes HDL equivalence or general solver correctness.

## Streaming, lifetime and resource contract

The stream parser retains bounded header/schema, one bounded line and one row.
Existing ceilings remain 16 MiB, 16 KiB per line, 1,000,000 lines/cells,
100,000 points and 64 variables; callers may tighten but not relax them. General
protocol parsing retains UTF-8/CRLF compatibility; simulator output remains ASCII.
A supplied stream remains caller-owned, and simulator-owned streams close on every
path. Successful fold results are released only after complete declared content,
EOF/trailing-content checks and consumer/native validation.

Non-collecting simulation parses the fresh raw file directly into the comparison
fold without materializing a raw byte vector or plot rows. Requested schema,
ordinal/grid, monotonicity and final endpoint/count checks remain at the native
simulator boundary and are shared with collected execution. Digest/size describe
the exact consumed bytes. Artifact changes, parser/grid failures and consumer
errors remain distinct from numerical mismatches. Explicit consumer failure has a
catalogued outcome; no aggregate escapes on incomplete validation. No public
side-effectful row callback promises rollback or early trusted rows.

F-050 `SimulationSession.run`, measurements, identities and outer sweep ordering
remain compatible. A streamed comparison has a separate result with evidence and
validated count, never a fabricated collected plot. F-050 batch sweep retention
limits remain in place; streaming import or a single streamed simulation does not
silently make the existing retained sweep unlimited.

## Alternatives and focused validation

Rejected alternatives: a second raw parser, streaming after eager plot allocation,
using sample ordinal as time, untyped unit strings as public dimensional authority,
implicit extrapolation, magnitude-before-complex-interpolation, unbounded streams,
and reporting success before a late error or digest check. Generic user callbacks
and a new dimensional algebra are unnecessary for this finite profile.

Focused checks cover existing raw/simulator predecessors; malformed/late/trailing
input, I/O and bounds; typed axis/unit incompatibility; increasing/decreasing and
one-point traces; independent interpolation expectations; exact/linear comparison
boundaries and mismatches; deterministic serialization; streaming/collected
agreement against independent retained numerical references. Measure a long trace
and representative sweep retention, and execute a real non-collecting generated
model comparison with preserved evidence. The current increment does not affect
generated Verilog-*; it consumes the existing verified model/tool pipeline.
