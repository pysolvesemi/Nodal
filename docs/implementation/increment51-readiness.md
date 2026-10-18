# Increment 51 readiness and implementation

Status: complete after verified squash integration `38ad908456f2d482fc5045a04bfdafeacdaa474d`.
The sole parent is `07c062cb93b31f1fed9a17ccf2aa4177058187fc`; the integrated
tree exactly matches tested tree `f2bdb1e848ac5d75c2a113f0233638a407b17303`.
All F-051 children and the parent are complete. No CI was run.
Base: `07c062cb93b31f1fed9a17ccf2aa4177058187fc`, verified F-050 documentation
closure. Branch: `increment/51-waveform-results`.

The [approved gate](../design-gates/NodalWaveformResults-DG-v0.1.md) selects one
bounded streaming parser and shared incremental simulator-grid validator, with
typed waveform/interpolation/comparison in the existing public simulation module.
Inspection found that F-050 always materialized raw bytes and plot rows; streaming
after `SimulationResult` would not repair that cost. A true non-collecting simulator
fold is therefore an essential shared prerequisite, not a second simulation stack.

Preserve F-049 raw protocol and requested-grid rejection, descending DC, finite AC
frequency imaginary metadata, F-050 probe/capture identity and outer sweep order.
Existing resource ceilings remain. No source/HDL lowering changes are planned.
The current increment does not affect generated Verilog-*.

Focused plan: protocol positive/negative/boundary/late-error predecessors; typed
axes/units/interpolation and serialization; collected/streamed comparison against
independent retained RC references; actual generated-model non-collecting execution;
long-trace/sweep retention observation and deterministic output. No CI or blanket
qualification. All execution claims will be added only after actual focused runs.

## Implemented ownership and first focused results

The canonical raw parser now accepts an `InputStream` and a private fold. The old
byte-vector API is a collector over the same parser. It retains fixed 8 KiB refill
and 16 KiB line buffers, bounded headers/schema and current row; the collector
alone retains rows and materializes a plot after complete EOF validation. Caller
stream ownership is explicit. Focused read-only review found no parser blocker.

The simulator selects collecting or folding within the same execution/receipt
path. Requested grid validation is incremental and shared. Raw digest/size cover
the consumed pass, with a bounded reread identity check; private consumer failure
has its own diagnostic outcome, and no aggregate survives incomplete validation.
A post-fold tool/staged-input recheck preserves provenance even if an internal
consumer changes files. Numerical disagreement remains a completed comparison.

Typed waveforms use existing dimensioned quantities and frequency values. Views
retain existing immutable row storage without copying signal columns. Exact
comparison is O(n), linear reference alignment O(n log m) with binary lookup,
with bounded reducer state in either case. Serialization is versioned, finite,
canonical hexadecimal IEEE values with explicit typed axis/unit and encoded
strict-UTF-8 provenance. These local algorithm observations do not claim the whole
pipeline is linear or constant-memory.

The first compiled focused bundle passed 73 tests: raw26, simulator22, waveform13,
public typed views4 and F-050 public source8. Final Unicode provenance and post-fold
identity guards add two causal cases. The final affected bundle passed 67 tests
(raw26, simulator23, waveform14, public4), logged in
`.work/increment51/focused-tests-final-2.log`. The earlier new-test compile failure
was a test-helper spelling error, fixed without changing production semantics.
Actual streaming numerical/retention replay passed as recorded below. First-run log:
`.work/increment51/focused-tests-1.log`.
Package visibility, architecture, contribution policy and current diff checks pass.
The final reconciliation below closes only independently completed children.

## Final demonstration and numerical/retention results

The current increment does not affect generated Verilog-*.
It consumes the actual F-050 generated RC model through unchanged F-048/F-049
compiler/simulator profiles. This tested public example demonstrates typed
frequency/current values and complex projection (from `WaveformTests.scala`):

```scala
val waveform = Waveform.reference(
  WaveformAxis.Frequency,
  WaveformUnit.Current,
  Vector(WaveformSample(Frequency.hertz(10), ComplexQuantity(3.0.amps, 4.0.amps))),
  WaveformProvenance("independent analytical reference")
).toOption.get
val sample = waveform.sample(0).toOption.get
val magnitude = sample.value.project(ComplexComponent.Magnitude).toOption.get
assert(sample.coordinate.hertz == 10 && magnitude.siValue == 5)
```

`Increment51SourceReplay.scala` then exercises actual collected and non-collecting
`SimulationSession.compare` against predeclared independent references. The final
replay has 41 simulation receipts and 43 comparison records: 42 passing comparisons
and one intentional five-sample numerical mismatch, correctly reported as completed
comparison with five failures. There are zero unexpected failures.

- DC: five points, -1 through 1 V; source current -V/R for R=1,000 ohm.
  Exact alignment gives identical zero-error collected/streamed summaries.
- AC: five linear frequency points from 10 through 10,000 Hz; unit drive and
  C=1 microfarad give -(1/R+j*2*pi*f*C) A. Explicit linear complex interpolation
  of the independent endpoint reference precedes real/imaginary/magnitude
  projection. Maximum imaginary/magnitude error is 6.94e-18 A.
- Transient: 1,038 adaptive points for the F-049 current-ramp RC reference.
  The independent ODE solution is sampled every 1 microsecond and explicitly
  linearly aligned; maximum error is 4.743e-6 V. Collected and streamed summary
  values are exactly equal. Tolerances remain 1e-10 A + relative1e-6 for DC/AC,
  and 2e-5 V + relative0.002 for transient; none were tuned to hide mismatches.
- Typed serialization repeats byte-identically and round-trips with axis/unit,
  complex samples and provenance intact. Wrong axes/foreign probes reject before
  tool execution; missing tools and intentional numerical mismatch remain distinct.
- A 32-run retained source sweep executes 1,001 DC points per run: 32,032 rows,
  64,064 complex cells. Each uses the independent affine current reference with
  explicit linear alignment; native requested-grid validation remains active.
  The affine reference domain [-2,+2] V contains the requested [-1,+1] V sweep,
  so endpoint roundoff needs no extrapolation or hidden epsilon.

The separate 100,000-point, 2,340,901-byte affine raw fixture is checked against
an independent two-point reference through the same parser. The fold result
retains zero actual rows; the comparison collector retains 100,000 rows/200,000
complex cells. One local heap observation after GC requests was 10,129,224 to
10,138,408 bytes for the fold, and 11,186,984 to 21,095,384 for the collector;
observed sampled heaps were 83,352,168 and 107,407,432 bytes. Zero of 100 sampled
weak row references remained live after the request. The fold took 0.811 seconds
and collector 0.509 seconds: no speedup claim is made. The retained sweep took
3.897 seconds, with heap 7,445,864 to 10,069,008 bytes after GC requests and sampled
heap 35,234,832 bytes. These include JVM/allocation/GC noise, are not peak memory
or guarantees, and do not imply constant process memory. F-050 retained sweep
limits still reject an excessive request before execution.

Final evidence: `.work/increment51/public-replay/waveform-replay-8151941494200909540`.
All twelve retained source copies/hash entries match final files. The directory
contains canonical source, actual VA/OSDI provenance, versioned reference/result
waveforms, numerical summaries, raw artifact hashes, commands and receipts,
retention observations and limitations. `public-source-replay.log` is terminal
success. Base HEAD is explicitly a working-tree base, not a clean-source claim.

Focused reproduction using the pinned repository toolchain:

```bash
./mill --no-server core.scala.testkit.test.testOnly \
  nodal.internal.testkit.NgspiceRawTests \
  nodal.internal.testkit.NgspiceSimulatorTests \
  nodal.internal.testkit.WaveformTests \
  nodal.internal.testkit.Increment51WaveformTests
./mill --no-server core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment51SourceReplay .work/increment51/public-replay \
  .work/native-release/bin/nodalc .work/native-release/bin/nodal-translate \
  .work/f43-array-initializer-profile/openvaf-reloaded/openvaf-r \
  openvaf-reloaded-20260616-linux-x86_64 \
  .work/increment49/toolchain/prefix/bin/ngspice \
  ngspice-47-local-linux-x86_64 "$(git rev-parse HEAD)"
```

A/E: canonical shared parser/validator and typed public owner reviewed, with no
remaining blocker; bounded measured retention needs no speculative cache. B/C/D:
typed parsing, streaming, interpolation/comparison, independent references and
meaningful malformed/unit/boundary/late-error controls pass. F: long-trace and
retained-sweep observations plus deterministic versioned serialization pass.
All children and the parent are complete after the verified integration above.
No CI or broad qualification ran; existing compiler capability limits remain.
