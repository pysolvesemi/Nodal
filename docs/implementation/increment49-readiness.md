# Foundation 49 readiness and implementation

F-049 implementation is ready for integration on `increment/49-ngspice-simulation-harness`, based on
`7fdf47329962a9f82dcfadc233910ae1e17e02c7`. F-048 is integrated and closed.
F-049.A through F are complete. The parent and G remain open pending verified
integration of the tested tree.

## Scope and architecture

The [approved private harness gate](../design-gates/NodalNgspiceHarness-DG-v0.1.md)
keeps typed benches, simulator execution and finite results in `core.integrations`,
reusing F-048 artifacts and process containment. F-050 public APIs and F-051 general
waveforms remain later consumers. The original F-049 checklist controls acceptance.

## Pinned local dependency build

ngspice 47 was built from the official release archive, SHA-256
`894e649651f1838a14095e5a5439e7d3aa63e87ede14d283173fda4fcdef675f`.
The executable SHA-256 is
`ba254f361c728f70cbb09293750d93f541f26c256d56286ddfb755d0d2793711`.
This is a locally built profile, not an official distributed binary. Source content
and preserved mtimes matched all 4,378 archive files; extraction ownership/modes
are not claimed identical. GCC/G++ 14.2 built the release-generated sources without
regenerating autotools or parser files.

Configuration used `CFLAGS=-O2`, `--without-x --with-readline=no
--with-editline=no --with-fftw3=no --disable-klu --disable-openmp --enable-osdi
--disable-debug`, with an isolated prefix. Default XSPICE remains enabled. An
initial no-XSPICE configuration failed an upstream unguarded `OPT_ENH_RSHUNT`
reference. The XSPICE seegenerator object required `-O2 -std=gnu11 -include
stdlib.h` because its release source lacks declarations for malloc/free under
GCC 14. This one-object preprocessing override changes compilation inputs but
patches no vendor source. Build/install and exact command logs are retained under
`.work/increment49/toolchain`; the tracked catalog records reproducible identity.

## Initial runtime feasibility

The actual F-048 public RC model OSDI loaded and executed with this build. Its
ordered declaration is `RcFilter(n,p)`, so the bench binds ground first. An initial
startup attempt using `pre_osdi` failed because that is not the interactive command;
a second attempt quoted the filename and failed because `osdi` passes those quotes
literally. Both failed attempts remain recorded. Fixed staged basename
`osdi model.osdi` succeeds without interpolating arbitrary paths.

Pilot DC (five points), AC (ten frequencies) and finite-rise current transient
(1,038 points) all exited zero and produced the requested plot. Independent RC
references yielded zero DC error, maximum AC component error `2.8e-17`, and maximum
transient voltage error `4.4e-6 V`. These establish dependency feasibility;
the completed production adapter replay and declared acceptance checks below
provide the acceptance evidence.
The raw AC frequency imaginary slot contains a finite subnormal value from the
pinned writer; preserve raw bytes and use only the real frequency coordinate.

## Acceptance boundary

The typed production bench/adapter/parser, original F-049 failure controls, applicable
generated initialization/context cases, whole-model compiler capability decisions
and bounded scale checks are complete. Architecture review and the reproducible
demonstration are recorded below. Verified integration remains open. No CI or broad
qualification was run.

## Focused boundaries observed during implementation

Actual pinned-tool probes confirm `.ac lin 2 10 100` emits one frequency, while
`.ac dec 3 10 50` emits 10, approximately 21.54 and 46.42 Hz, without the requested
endpoint. The finite typed profile rejects these cases explicitly; ordinary
supported linear and integer-decade grids retain exact declared axis checks.
No generic simulator equivalence is inferred from this profile.

## Reproduction prerequisites

The checked-in simulator profile pins this exact local executable and complete
version response, including its build date. A fresh build is not promised
bit-identical: a changed executable/version must be introduced as an explicit
reviewed profile update, not accepted by matching only the release number.
OSDI support is enabled; this release's loader accepts the F-048 OSDI 0.4 artifact
with exported descriptor size. Actual loading and numerical replay, rather than
that static ABI observation alone, establish the selected compatibility.

The shared runner is unchanged from F-048. This increment reuses its isolated
process-group execution, deadlines, exact raw capture and interruption behavior.
Simulator artifacts are independently capped at 64 MiB, copied and checked before
execution; raw output is capped at 16 MiB before parsing. No asynchronous solver
service, cache or global session registry is added. Bench rendering is bounded by
parameter count and never expands its requested sample grid. Result materialization
is bounded and iterative; general streaming remains F-051's responsibility.

## Focused review correction

Review found that a purely relative axis comparison could accept duplicate points
for a high-offset, tightly spaced DC/AC grid. The adapter now requires strict
axis direction and caps comparison tolerance below half the local grid spacing;
causal high-offset duplicate, reordered and displaced-point controls accompany
positive grids. This is protocol validation, separate from numerical DUT tolerances.
The final consolidated focused execution includes the causal controls.

## Focused validation and demonstration

The focused Scala suites pass: `NgspiceRawTests` 19, `NgspiceBenchTests` 17,
`NgspiceSimulatorTests` 14, `Increment49NumericalTests` 3, and the directly affected
`OpenVafCompilerTests` 19. The catalog generator's 41 Python tests pass. Exact-file
pinned Scalafmt/Scalafix, architecture/package visibility, contribution policy and
Markdown checks pass. No native production code or shared process runner changed.

The public-source replay invokes actual source construction, bridge normalization,
native target emission, pinned OpenVAF compilation and the production ngspice
adapter. All twelve regenerated target models are byte-identical to the F-048
final replay. Four original models compile; six whole hierarchy models and the
persistent array retain compiler errors, and runtime ceil retains the compiler
crash. The RC repeat adds one successful compilation with identical artifact and
request identity. No rejected graph is replaced by an extracted leaf.

Numerical references are written before simulator execution:

- RC DC: `I(Vdrive) = -V/R`, R = 1,000 ohms; five points from -1 to 1 V.
- RC AC: unit voltage, `I(Vdrive) = -(1/R + j*2*pi*f*C)`, C = 1 microfarad;
  ten frequencies from 10 to 10,000 Hz. Current tolerance is
  `1e-10 A + 1e-6*abs(reference)` per real/imaginary component.
- RC transient: zero initial voltage, 1 mA current ramp after 1 ms, rise 0.1 ms,
  10 microsecond maximum step and 10 ms stop. The independent piecewise solution
  of `C*dV/dt + V/R = I(t)` uses tolerance
  `2e-5 V + 2e-3*abs(reference)`; it is not fitted to measured output.
- Initial-step storage: unloaded output equals 1 V. Context uses 37 Celsius
  ambient and 27 Celsius nominal, with expected
  `(37+273.15)/(27+273.15) + t` V. Safe-function output is `3.5+sin(t/1 s)` V.
  These use zero-current stimulus and voltage tolerance
  `1e-9 V + 1e-6*abs(reference)`.

Observed maximum absolute errors were zero for DC/storage, `2.78e-17 A` for AC,
`4.373e-6 V` for RC transient, `6.67e-16 V` for context and `1.78e-15 V` for
safe-function output. Numerical acceptance is distinct from model loading.
Missing-model and deliberately conflicting ideal-voltage benches produce actual
`LoadFailure` and `Nonconvergence`. Unit controls additionally cover invalid
requests, timeout/interruption, capture failures, exit-zero errors, stale/tampered
artifacts, malformed output and wrong/duplicate/reordered axes.

The DC deck is 228 bytes for five points and 230 bytes for 10,001; transient
decks are 281 and 279 bytes for the short and long intervals. Rendering does not
expand output points. Scale cases retain 10,001 DC points (754,266 raw bytes) and 10,038 transient points
(511,128 raw bytes). A 100,000-point parser fixture including construction and
assertions took 949 ms in one focused run. These are local observations, not a
benchmark or speedup claim. RC DC/AC/transient repeats preserve exact numeric
values and request identity; raw Date and the nonphysical AC frequency imaginary
slot are deliberately excluded from numerical identity. No stochastic source is
used, so no hidden random seed or event/noise qualification is claimed.

Focused reproduction, using the repository's pinned toolchain environment:

```bash
./mill --no-server core.scala.testkit.test.testOnly \
  nodal.internal.testkit.NgspiceRawTests \
  nodal.internal.testkit.NgspiceBenchTests \
  nodal.internal.testkit.NgspiceSimulatorTests \
  nodal.internal.testkit.Increment49NumericalTests \
  nodal.internal.testkit.OpenVafCompilerTests
python3 -m unittest discover -s tests/compiler -p test_backend_capability_registry.py
./mill --no-server core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment49SourceReplay .work/increment49/replay \
  .work/native-release/bin/nodalc .work/native-release/bin/nodal-translate \
  .work/f43-array-initializer-profile/openvaf-reloaded/openvaf-r \
  openvaf-reloaded-20260616-linux-x86_64 \
  .work/increment49/toolchain/prefix/bin/ngspice \
  ngspice-47-local-linux-x86_64 "$(git rev-parse HEAD)"
```

Each replay retains exact source copies with `.bytes` suffixes, normalized IR,
complete target graph checks, model/OSDI hashes, commands, compiler and simulator
receipts, raw output, predeclared references, comparisons and repeat checks. HEAD
is recorded as the source base; per-file retained hashes identify the exercised
working tree without claiming it was already clean/published.

## Later consumers and compatibility

F-050 can build the public session/source/analysis/assertion API above these private
services. F-051 owns general typed axes, physical units, interpolation and streaming;
the finite raw parser does not replace that work. F-052 retains broader numerical
regression and capability qualification. Initial-step storage here is the only
newly executed event profile; other event forms, private helper protocols and
noise require their own explicit later cases. Existing F-043/F-048 hierarchy/array
and compiler-crash handoffs remain open consumer limitations. This increment does
not redefine Nodal semantics to match them.


## Final reconciliation and evidence

F-049.A/E: the sole integration owner, existing process runner and catalog are
reused; the final review found no remaining blocker after the axis fix. No broader
optimization is needed for the measured finite profile. F-049.B/C/D: production
loading, benches, analyses, typed failures and actual numerical comparisons pass.
F-049.F: bounded scale, retained explicit options, exact source/artifact/request
identity and repeatable numerical output pass. Original supported cases remain
meaningful; consumer rejections are retained, not relabeled as passing simulation.
F-049.G and the parent wait for the verified integration commit.

Final evidence is `.work/increment49/replay-final/source-replay-8679539002980930345`.
The earlier complete run is retained separately; a test-helper lint cleanup was
followed by the final source-matching replay. `source-preservation-check.json`
confirms all 19 source copies and twelve F-048 target comparisons. Final logs are
`source-replay-final.log`, `final-focused-tests.log` (72 Scala tests), and
`catalog-tests.log` (41 Python tests) under `.work/increment49`.
All eleven positive simulations and both actual negative controls completed as
expected; `replay-checks.json` has no failures. The final scale process observations
were 57 ms for DC and 202 ms for transient; they exclude compilation and are not
repeatable performance guarantees.
