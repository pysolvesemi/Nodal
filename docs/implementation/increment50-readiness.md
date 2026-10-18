# Increment 50 readiness and implementation

Status: implementation and focused local validation complete (A–F, including D.1/D.2).
Parent and G remain open pending verified integration.

Base: `1c3189fb6e6d9fcf09fd2f0e6d112c3f93d018c1`, the verified F-049
post-merge documentation closure. Branch: `increment/50-scala-simulation-api`.

The [approved API gate](../design-gates/NodalScalaSimulationApi-DG-v0.1.md)
selects a bounded public batch API over existing compiler/simulator services.
Readiness found three necessary shared repairs: quantities discarded numeric values,
clock-domain snapshots discarded frequency/phase/parent information, and the private
F-049 bench admitted only one driven terminal. These are repaired at their owners,
not hidden behind a parallel simulation-only semantic model.

Selected source capture uses the existing transaction with immutable canonical
selected references. A separate stimulus harness preserves domain metadata while
the analog DUT remains subject to unchanged capability checks. Native emitted ABI
must independently agree with selected terminal identities before simulation.

The selected implementation is complete. F-051 streaming and general interpolation
remain later work. Known F-048/F-049 whole-hierarchy/array compiler rejections and
unqualified event/noise execution remain visible. No CI or full qualification was
run under the prototype policy.

## Implemented ownership and review checkpoint

The public package is `nodal.sim`, owned only by `core.scala.sim`; package policy
now names that public owner while preserving the bootstrap smoke and the API's
internal-import restriction. A focused positive/negative package-owner test passes.
The existing module dependencies are sufficient; no manifest dependency is added.

The source seam uses actual capture identities and canonical names. Numeric and
clock/reset descriptors use a distinct metadata operation with the same existing
placement/effect checks, while genuinely unrepresented candidate effects retain
explicit diagnostics. The unsupported-effect reason previously used JVM object
identity in some statements; the shared owner now uses canonical rendering instead.
Final focused deterministic-capture controls pass, including a separate fresh-JVM
first-use zero-phase check. The built-in zero phase has no construction side effect;
explicit authored phase values retain placement checks.

Public simulation admission rejects unrepresented statements, semantic equation/
contribution inventories without executable lowering, and ordinary unrepresented
digital assignments/processes. This closes a discovered partial-DUT risk: F-032
metadata alone must not be mistaken for an executable analog equation. It does not
change F-046's structural-analysis incompleteness contract or claim later DAE lowering.
The separate harness similarly checks its full captured effects before interpreting
clock/reset descriptors. Rejected native/bridge compilation retains construction
provenance where capture and evidence setup succeeded.

The public source API obtains the actual root scalar electrical ABI projection from
the bridge's canonical naming owner and independently checks the emitted header.
Compilation records construction, source/normalized IR, native tool hashes and
commands, generated model and existing F-048 receipts. Model constructor parameters
use existing typed construction checks; the new sweep API varies electrical source
DC values rather than bypassing model constraints with raw simulator overrides.

The F-049 owner now renders bounded multi-terminal single-DUT benches. Legacy and
new requests share one execution/evidence path and one schema/axis validator.
Positive source current is positive-to-negative; current sources use an explicit
zero-volt series sensor retained in the deck. The bounds are 63 sources/probes,
63 declared-plus-sensor nodes, 64 target terminals and 100,000 aggregate PWL points;
raw collection ceilings remain F-049's. Generation scales with bounded authored
sources/vertices, not analysis sample count. No runner, cache or raw parser is copied.

Single-sample public measurements access a row directly; reductions traverse an
iterator without copying the trace. A Cartesian sweep has at most four axes and
128 runs and preflights every point before launching the first. Aggregate retained
output is capped before execution at four million complex cells using exact DC/AC
row counts and worst-case adaptive transient collection ceilings. Every actual run
retains fresh tool evidence. No general interpolation, streaming or whole-pipeline
linear-time claim is made. The actual numerical and focused results below cover this finite profile; these
architecture observations are not whole-pipeline performance guarantees.

## Focused validation checkpoint

- Shared legacy/new bench and adapter suites: 42/42 passed (17 legacy bench,
  nine generalized bench, 16 simulator tests), including high-offset axis controls.
- Actual pinned-tool integration replay: five-point DC, three-point complex AC,
  1,037-point PWL and 1,035-point pulse, with source-current orientation, RC
  reference and repeat identity checks. Uses the actual previously generated RC
  model compiled anew, not a handwritten target substitute.
- Final shared/source/domain suite: 55/55 passed (eight public source tests,
  11 domain stimulus, 14 selected capture, six construction, seven source-origin,
  nine analog-function predecessors). This includes same-clock-source consistency,
  short flat-window conflicts, source-effect rejection, and strict snapshot equality.
- Two additional fresh-JVM zero-phase controls pass: entire cold/repeated capture
  equality and first-ever zero-phase use in restricted function/generated contexts.
- Package-owner focused Python tests: 2/2; package visibility, architecture and
  contribution-policy checks pass. Exact-file formatting/lint and diff checks pass.

The later allocation and aggregate-sweep guards passed a fresh eight-test public
rerun and the actual source-to-simulator replay below. This is a focused replay,
not a second count of eight distinct tests.

## Public generated-model demonstration and numerical evidence

`Increment50SimulationTests.scala` defines the actual public-source fixtures;
`Increment50SourceReplay.scala` uses only the public API for capture, compilation,
bench construction, execution, measurements and assertions. The RC fixture has
R = 1,000 ohm and C = 1 microfarad, with declaration order intentionally different
from the canonical target ABI. The generated model contains:

```verilog
module Increment50Rc(a, z);
  inout a, z;
  electrical a, z;
  parameter real C = 1e-06;
  parameter real R = 1000;
  analog begin
    I(z, a) <+ ((V(z, a) / R) + (C * ddt(V(z, a))));
  end
endmodule
```

The complete emitted model, including source provenance and standard includes,
is retained with the run. This excerpt is not the compilation input. Public source
selection maps the actual terminal identities to this verified ABI; no handwritten
model or simplified target graph substitutes for it.

Eleven actual numerical simulations pass 1,757 independent-reference assertions:

- RC DC: five points from -1 to 1 V; source current reference -V/R,
  absolute tolerance 1e-10 A plus relative 1e-6. Exact agreement in this run.
- RC AC: ten points, three per decade from 10 to 10,000 Hz; source-current
  reference -(1/R + j*2*pi*f*C) A for unit voltage. Same current tolerances;
  maximum component discrepancy 2.78e-17 A.
- RC transient: 1,038 adaptive points; 1 mA current ramp beginning at 1 ms,
  0.1 ms rise, zero initial voltage. Independent piecewise solution of
  C*dV/dt + V/R = I(t); tolerance 2e-5 V plus relative 0.002.
  Maximum discrepancy 4.37e-6 V; worst normalized tolerance use 4.07%.
- A six-terminal actual generated weighted-input model executes electrical PWL
  for 1 MHz core, related 1.5 MHz (+90 degree child phase), independent 0.8 MHz
  clock, and active-low raw reset. It returns 1,571 points. The 670 plateau
  comparisons use independently calculated square-wave phases, not generated
  PWL, with tolerance 1e-8 V plus relative 1e-7; all agree exactly.
- Four two-dimensional source-sweep points each execute a two-point DC analysis.
  Independent weighted references agree exactly. Repeated DC preserves physical
  axes, numerical values and request/model/artifact identity with fresh receipts.
- Two concurrent independent source captures/compiles/runs use R = 1,000 and
  2,000 ohm, separate files and three DC points each; references agree exactly.

The reset report records raw, policy and explicit staged-release reference times
as SI numbers. Inclusive edge coincidence and periodic clock prehistory are
explicit testbench conventions. Raw electrical reset execution does not establish
that digital synchronizer hardware ran. Transition neighborhoods are deliberately
excluded from the square plateau oracle; ramp construction has separate focused
positive/boundary checks. No event/noise execution qualification is added.

The assertion criterion is abs(actual-reference) <= absolute + relative*abs(reference).
Declared references precede execution; tolerances were not adjusted to fit results.
Unsupported digital DUTs, missing tools, failed measurements and failed assertions
remain distinct outcomes. A 12-by-12 Cartesian product and a 21-run transient
worst-case retention request reject before creating simulator evidence.

Compilation uses the pinned F-048 OpenVAF-reloaded profile and F-049 locally built
ngspice-47 profile. Their exact identities, the one-object standard-header build
option, source archive and capability limitations remain documented in
[the F-049 readiness record](increment49-readiness.md). This is not an official
released ngspice binary or a new tool/profile qualification.

## Reproduction, cost and final reconciliation

```bash
./mill --no-server core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment50SimulationTests \
  nodal.internal.testkit.DomainStimulusTests \
  nodal.internal.testkit.SelectedCaptureTests \
  nodal.internal.testkit.ConstructionKernelTests \
  nodal.internal.testkit.SemanticOriginTests \
  nodal.internal.testkit.AnalogUserFunctionTests \
  nodal.internal.testkit.NgspiceBenchTests \
  nodal.internal.testkit.NgspiceCircuitBenchTests \
  nodal.internal.testkit.NgspiceSimulatorTests
./mill --no-server core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment50SourceReplay .work/increment50/public-replay \
  .work/native-release/bin/nodalc .work/native-release/bin/nodal-translate \
  .work/f43-array-initializer-profile/openvaf-reloaded/openvaf-r \
  openvaf-reloaded-20260616-linux-x86_64 \
  .work/increment49/toolchain/prefix/bin/ngspice \
  ngspice-47-local-linux-x86_64 "$(git rev-parse HEAD)"
```

The replay retains source `.bytes` copies and SHA-256 inventory, canonical captures,
IR, actual VA/OSDI, tool commands/identities, compiler/simulator receipts, raw plots,
references, every comparison and sweep coordinates. Base HEAD is labeled as a
working-tree base, not a false claim that uncommitted tested sources were published.
The earlier completed replay is retained separately from the final replay after a
witness-only change to serialize reset times numerically instead of object IDs.

The first completed public replay took 29 seconds including its Mill invocation;
this is one local observation, not a benchmark guarantee. Generation is bounded by
authored PWL vertices and source count. Measurement access avoids whole-trace copies;
sweep preflight bounds accumulated result retention before any process launches.
Fresh compilation remains explicit for independent sessions. These measured small
runs and checked limits justify no further speculative cache or optimization here.

A/E: shared capture, canonical ABI, bench, runner, parser and evidence owners are
reused; focused architecture review has no remaining blocker. B/C/D: actual public
source-to-generated-model loading and applicable DC/AC/transient references pass,
with exact negative controls. F: repeat identities, two-axis sweeps, bounded
allocation and independent concurrent sessions pass. G and the parent await the
verified squash integration; no original required feature is waived by this record.

Final evidence: `.work/increment50/public-replay/public-replay-14952597584840214923`,
log `public-source-replay-final.log`. All fifteen retained source files match the
final source bytes; numerical comparison TSV and both generated VA models are
byte-identical to the preceding successful run. The report records raw reset
assertion/release at 1e-7/3.2e-6 s, policy assertion/release at
1e-7/4.366666666666667e-6 s, and separate staged release at
5.033333333333334e-6 s. Final eight-test resource replay is
`public-resource-tests.log`; shared suites are `public-focused-tests-final.log`
and `integration-focused-tests-3.log`. Fresh-JVM controls are
`cold-phase-final.log` and `cold-restricted-phase-final.log`.
