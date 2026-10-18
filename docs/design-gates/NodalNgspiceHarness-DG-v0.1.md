# ngspice simulation harness v0.1

**Status:** Approved
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 49

## Authority and owning layer

The owner's explicit request to implement every unchecked Foundation increment
from 43 authorizes this finite F-049 implementation. Standing publication and
completed-increment integration approval applies with `[skip ci]`. This gate
records the selected internal contract; it does not claim the owner separately
selected these signatures. F-050 retains the public simulation API and F-051 the
general waveform/streaming representation.

`core.integrations` owns simulator profiles, typed bench generation, subprocess
execution, bounded ngspice raw parsing and evidence. Reuse the F-048 process runner
and successful compiler receipts. The compiler/bridge does not own simulation.
The existing backend catalog owns pinned consumers; simulator entries remain
separate from source-language capability admission. No second runner, inferred
pin order, arbitrary deck scripting, model rewrite or leaf-only hierarchy fallback
is introduced. There is no result cache.

## Accepted request and bench contract

A simulation requires a successful F-048 compile receipt and a regular, nonempty,
size/hash-verified OSDI artifact. A request names a pinned simulator profile,
absolute executable, evidence directory and bounded separate version/run timeouts.
The tool must match the exact executable hash, version response and host profile.
A locally built profile records its original archive and build overrides explicitly.

The finite private bench binds exactly two declared terminals, in explicit supplied
order, to ground and one driven node. Module/parameter names are validated identifiers;
parameters are unique and finite. Unsupported topology is rejected before execution.
Typed analyses are a DC voltage sweep, unit-voltage linear/decade AC sweep, or
transient current ramp/pulse. Transient starts with zero driven-node voltage and
uses UIC; this is an explicit initial condition, not a general initialization policy.
Stimulus values, time/frequency axes, solver tolerances, Celsius temperature and
nominal temperature are part of the deterministic deck and request identity.
Requested grids are bounded before expansion; deck generation never enumerates
samples. The pinned AC profile supports one equal-endpoint frequency or linear
sweeps with at least three points, and integer-decade spans. ngspice 47 emits only
one point for a two-point linear request; fractional-decade endpoint semantics
are outside this first explicit grid contract. DC grids reject a next-point gap
smaller than the solver stop slack rather than silently accepting extra rows. Adaptive transient output is independently bounded at collection.

Every attempt has a fresh directory and fixed staged basenames. Controlled startup
loads `model.osdi` using the actual interactive `osdi` command, sets ASCII output
and strict error handling. Batch execution uses `-b -r results.raw bench.cir` and
no `.control` block. HOME and SPICE_SCRIPTS point to that directory; ambient user
startup files and OSDI paths are not inherited. Arguments are separate vector
entries; no caller text is interpolated into shell commands.

## Evidence and result boundary

Reuse process-group containment and bounded raw stdout/stderr from F-048. Preserve
compiler identity/provenance, artifact identity, simulator/profile identity, deck,
startup configuration, invocation, controlled environment, exit/failure and raw
results. Failed or stale output cannot become successful evidence. Success requires
actual populated expected analysis output from the deck that instantiates the
staged model, not merely exit zero. Load/deck/nonconvergence/tool/profile/process/
malformed-output failures remain explicit; unknown errors are not accepted.

The private parser accepts one ASCII ngspice plot with exact analysis kind,
real/complex flag, variable indices and row cardinality. It rejects binary data,
invalid UTF-8, nonfinite values, truncation, extra plots and unsupported dimensions.
Fixed ceilings are 16 MiB, 16 KiB per line, one million lines, 100,000 points and
64 variables. Caller limits may tighten these ceilings. These are collection
bounds, not a claim that an arbitrary external solver cannot grow a file before
its deadline. AC frequency uses the real component of its frequency scale; the
raw imaginary scale slot is retained but has no physical axis interpretation.

Simulation completion and numerical acceptance are separate. Numerical checks
name independent references, units, stimuli, initial conditions and tolerances
before evaluating results. No universal equivalence, all-event/noise support or
solver portability follows from selected successful cases.

## Alternatives and focused checks

Rejected alternatives are hand-written smoke scripts as the production owner,
arbitrary SPICE strings, simulator logic inside the source bridge, hidden compiler
substitution, and a premature public/general waveform API. The typed private
adapter supplies F-050/F-051 with reusable evidence and analysis ownership.

Focused checks cover generated model load, DC/AC/transient numerical references,
initialization where supported, invalid benches, corrupted/missing artifacts,
load errors, nonconvergence, timeouts, exit-zero errors, malformed output and
repeatable request identity. Bounded larger sweep/transient cases measure relevant
collection behavior. Existing F-048 runner/compiler checks remain predecessor
controls. Whole-hierarchy/array compiler rejections remain visible, without leaf
substitution. Architecture review examines bounded allocation and ownership;
no CI, exhaustive simulator qualification or universal performance claim is needed.

## Primary references

- [ngspice OSDI integration](https://ngspice.sourceforge.io/osdi.html).
- [ngspice manual](https://ngspice.sourceforge.io/docs/ngspice-manual.pdf),
  OSDI loading, batch execution, DC/AC/transient analyses and raw files.
- [ngspice 47 source archive](https://downloads.sourceforge.net/project/ngspice/ng-spice-rework/47/ngspice-47.tar.gz):
  `src/frontend/commands.c`, `com_dl.c`, `rawfile.c` and `outitf.c` define
  the exact pinned command and output behavior.

Implementation results belong in
[F-049 readiness](../implementation/increment49-readiness.md).

## Final implementation review

The implementation retains the approved ownership and finite boundaries. Review
identified and repaired offset-relative grid tolerance: DC/AC axes now require
strict direction and spacing-capped comparison. Focused causal controls and actual
source execution pass. Bench size does not grow with requested samples; parser
work and retained vectors grow with bounded observed output. Exact final results,
limitations and reproduction are recorded in the readiness document. This is
prototype acceptance for the selected profile, not a general solver certification.
