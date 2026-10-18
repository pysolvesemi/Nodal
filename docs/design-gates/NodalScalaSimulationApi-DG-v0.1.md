# Scala simulation API v0.1

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 50

## Authority and ownership

The owner's explicit request to implement every unchecked Foundation increment
from 43 authorizes this bounded F-050 API and its necessary shared prerequisites.
Standing public publication and completed-increment integration approval applies
with `[skip ci]`. This records the selected contract, not a separate owner choice
of individual signatures. No CI or exhaustive qualification is required.

`core.scala.sim`, package `nodal.sim`, owns the public batch testbench contract.
It consumes the existing construction transaction, bridge/native compiler and
F-048/F-049 compiler/simulator services. `core.integrations` retains typed deck
rendering, pinned consumer profiles, process isolation and raw evidence. There is
no second subprocess engine, hidden simulator script or implicit tool fallback.
F-051 retains general waveform streaming/interpolation. F-050 provides finite
sample measurements and explicit assertions sufficient for its batch contract.

## Source capture and dimensions

Existing dimension-tagged quantities retain their finite SI numeric value;
addition computes a checked finite sum of the same dimension. Negative electrical
values are legal; positive time/frequency constraints belong to their consumer.
Frequency retains positive finite hertz and phase retains finite degrees. The
built-in zero phase is a side-effect-free constant; explicit authored metadata
retains placement checks. Existing
MHz/degree syntax remains valid. Overflow and nonfinite values reject rather than
clamp. Clock domains retain actual parent, frequency, clock/reset source and ratio
phase at the existing construction owner. Reset synchronization is explicitly
tagged, not inferred from arbitrary operand shapes.

A private selected-reference capture seam constructs the root and evaluates typed
selectors inside one existing transaction, finishes canonical naming, then returns
an immutable snapshot and selected semantic references. Foreign-session objects
reject. No root, callback, session or global registry survives capture. Public
terminal/parameter/domain handles are privately constructed and capture-owned;
source identity and spans remain correlated with the canonical snapshot.

Analog DUT and stimulus-only clock/reset harness are separate captures. Harness
metadata is interpreted as a stimulus description, never removed from a DUT to
make an unsupported digital model appear analog. Actual digital-domain DUTs retain
the existing Verilog-A capability rejection. Selected electrical scalar boundary
terminals must correspond exactly to the actual emitted top-module ABI; pin order
is verified against emitted output, not guessed from selector or declaration order.
Model parameters are supplied through the ordinary typed source constructor before
capture, retaining existing source type/range validation. This API does not expose
unvalidated simulator-side model-parameter overrides. The batch sweep profile
varies independent electrical source DC values; parameterized model sweeps may
construct and compile each typed source explicitly.

## Finite batch bench and evidence

One compiled OSDI DUT may have one to 32 selected, explicitly bound electrical
terminals in the public profile.
Typed voltage/current sources, independent analysis selection, explicit initial
conditions and saved probes use the existing bounded integration owner. Supported
analyses are checked DC grids, linear/decade AC and transient. The F-049 two-terminal
API remains compatible, including existing generated decks and numeric controls.
Identifiers, node/source references, parameter values, grids and aggregate waveform
points are checked before rendering/execution. Arbitrary SPICE text is not accepted.

Compilation and simulation retain the complete source graph, native commands and
outputs, capability decisions, compiler/simulator identities, hashes, process
outcomes, raw artifacts and mapped diagnostics. Compile failure, solver failure,
measurement failure and assertion failure are distinct. No successful tool exit
alone becomes a passing numerical assertion. Repeated runs use fresh evidence
locations; independent sessions do not share mutable captures or working files.
Bounded Cartesian source sweeps have one to four axes and at most 128 runs,
deterministic axis and point identities, and reject excess cardinality before
expansion. A DC analysis-controlled source cannot also be a sweep axis. All
bounded point benches are validated before any point executes. Retained output is
preflight-bounded to four million complex cells across the sweep: exact admitted
DC/AC rows, and the parser worst-case 100,000 transient rows (subject to its one
million-cell per-run cap), times the saved schema. This conservative bound can
reject a transient sweep whose nominal grids look small; no partially collected
sweep is silently truncated. No compilation/result cache is introduced.

Measurements select explicitly named probes and units and finite reductions.
Absolute/relative tolerance is `abs(actual-reference) <= absolute +
relative*abs(reference)` with finite nonnegative tolerances and finite operands.
Missing probes, incompatible units, nonfinite measurements and invalid tolerances
reject explicitly. The API does not silently interpolate or align unlike axes.

## Clock/reset electrical stimulus

Clock/reset descriptions preserve source domain identity, edge, polarity, reset
policy and relation. Equal frequencies do not imply synchronization. Same/Ratio
relationships use actual parent identity; asynchronous domains have independent
origins. Unknown timing relationships reject requests that require a numerical
phase. Explicit electrical levels and finite transition times are required; this
is an intentional testbench electrical mapping, not an implicit Clock-to-Bool or
digital-to-analog source-language coercion.

For this electrical testbench projection, a generated domain's absent edge,
reset polarity and reset policy inherit the actual parent's descriptors. The
source's distinct reset source and explicit Rdc.sync stage metadata remain
separate. This is an explicit F-050 reference convention, not an invented
source-language implementation of the supplied generated clock expression.
Drives sharing an actual clock source must agree on the generated electrical
waveform; different domains cannot reinterpret one source as incompatible clocks.
Reset drives sharing a raw source likewise must agree.
Computed gate/mux clock expressions and unknown relationships are rejected.

Clock origin is an explicit reference active-edge midpoint, not the first edge
in a simulation. Periodic prehistory determines the value at time zero. External
independent domains require their own origin; Same/Ratio inherit the actual parent
reference plus positive phase/360 times the child period. Child frequency is parent
frequency times multiply/divide. Rise/fall midpoint defines the declared edge;
the opposite midpoint is duty times period after a rising edge, or (1-duty) times
period after a falling edge. Finite nonoverlapping ramps and representable distinct
times are checked. Edge times derive from origin plus integer ordinal times period,
not repeated floating addition. Aggregate generated PWL points are bounded before
allocation at 100,000.

Reset drives describe raw boundary assertion/release midpoints with explicit levels,
polarity and ramp widths. ResetPolicy.None rejects reset drive. Reference-only
observations use an explicitly inclusive sampling convention: a reset midpoint
coincident with a clock midpoint is visible to that edge. Sync observes the next
inclusive edge; Async observes the raw time; AsyncAssertSyncRelease(n) observes
assertion asynchronously and release at the nth inclusive active edge. An explicit
Rdc.sync stage reference is recorded separately from the domain policy reference,
never silently composed with it or applied to the raw waveform. These are declared
testbench reference conventions, not a promise about HDL scheduling or executed
digital synchronizers. Electrical stimulus execution does not establish that a
digital synchronizer or RDC implementation executed in the analog DUT.

## Alternatives, compatibility and focused validation

Rejected alternatives are retaining dummy numeric quantity values, a second
mutable elaboration registry, reflection over arbitrary DUT members, hand-supplied
ABI order as the public normal path, simulator strings in the public API, stripping
digital domains from DUTs, and treating unsupported analysis as passed simulation.

Focused checks cover numeric boundaries and dimensional compile failures;
source/foreign ownership and deterministic captures; actual terminal ABI;
clock/reset related and independent timing; source and grid rejection; DC/AC/
transient generated-model references; failed measurements/assertions; bounded
multi-axis sweeps and concurrent sessions. Retain direct F-048/F-049 and construction
predecessor checks. Known whole-hierarchy, array and event/noise consumer limitations
remain explicit. No universal solver, scheduling or digital-verification claim
follows from these selected analog executions.
