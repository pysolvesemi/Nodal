# Increment 53 readiness and implementation

Status: complete. PR149 was squash-integrated as
`54d958d68593f66f0a653ec328009fef55a94b85`, preserving tested tree
`19ab31427399067363237672f2456e2827385696`. Parent/G are complete for the
explicit finite profile. Base `c5be30eeebb7c90a3cf12e979cd960a16284f57b` is the verified F52
documentation closure. Branch: `increment/53-cross-tool-portability`.

## Selected architecture

The [approved additive gate](../design-gates/NodalCrossToolPortability-DG-v0.1.md)
defines explicit optional simulator selection, one finite electrical bench/value
owner, consumer-specific capability/deck/grid/raw rules, shared process/artifact
integrity and existing typed waveform comparison. The second simulator is VACASK
0.3.4. It receives exactly the same OpenVAF OSDI 0.4 artifact as ngspice; no model
rewrite, implicit compilation, alternate equation system or silent fallback occurs.

Existing ngspice defaults and private source-compatible aliases are retained. The
shared extraction moves semantics, not solver-specific endpoint/count assumptions.
Public collected execution, streaming comparisons and sweep preflight use the same
explicit selected-adapter dispatch. Cross-tool disagreement remains distinct from
failure to run or validate either tool. F52 analytical references remain independent
acceptance evidence; tool agreement alone is not physical correctness or formal proof.

## Official consumer and runtime provenance

Official release: [VACASK 0.3.4](https://codeberg.org/arpadbuermen/VACASK/releases/tag/_0.3.4),
source commit `c1a1c84f1b2b9aa71c0cddf06e555441434db7b7`.
The archive SHA256 is
`780949fc75181d5839cc8c8091e05c2ca2e70a73c9f920e88553228061c44027`;
executable SHA256 is
`bb606780f55d0b4f4b0e50503381d991cd2fd12298f85563bb4438b33c1abb13`.
The released executable is unchanged. Its AGPL-3.0 license is retained in the local
distribution; no click-through acceptance or source/binary patch was needed.

The host provides glibc 2.41 and the base C/C++/Fortran/OpenMP runtime. Six additional
SONAME files (KLU, OpenBLAS, AMD, COLAMD, BTF and SuiteSparseConfig) are extracted
locally from official Debian trixie packages, without system installation. Exact
package versions/archive hashes and runtime content hashes are pinned in the existing
backend profile catalog. These pins cover explicitly added dependencies, not a claim
of a hermetic operating system. The profile validates the bounded exact runtime
manifest and does not inherit LD_LIBRARY_PATH.

Primary sources: the official tagged `lib/osdifile.cpp` dispatches both OSDI 0.4
and 0.5 with version-specific descriptor checks; `simulator/main.cpp` establishes
`--help`, `--tomlfile`, analysis abort handling and startup ordering. The released
manual documents native analyses, sources, tolerances and raw output. `lib/outrawfile.cpp`
records the distinct all-`notype` ASCII schema. Retained exact source excerpts and
archive metadata are under ignored `.work/increment53/research/`.

## Initial actual capability observations

The pinned executable launches with the local runtime and reports `vacask 0.3.4`.
Unchanged F52 diode, RLC and RC OSDI artifacts produce populated DC, complex AC and
transient output in bounded smoke runs. Maintained adapter validation and actual
numerical comparisons subsequently passed as recorded below.

Important consumer differences are implemented explicitly:

- OSDI module/parameter names are lowercased by VACASK. Binding normalization must
  reject collisions and does not edit the compiled model.
- Floating-point stepped DC can omit the intended final point. The adapter requests
  a bounded explicit checked coordinate vector instead of accepting missing coverage.
- AC linear `points` denotes intervals, so N requested samples require N−1 intervals;
  single-point AC uses an explicit value. AC saves use `dv`/`di`.
- Raw schema tags are `notype`; physical units come from the admitted typed bench,
  never invented raw voltage/current tags. Each writer has a strict closed dialect.
- `abort always` is required because the consumer otherwise continues after failed
  analyses. Explicit `--tomlfile` replaces default startup configuration; the fresh
  netlist-directory configuration must also remain absent.

No CI or broad qualification was run. The executed finite profile and meaningful
failures below are the prototype acceptance evidence.

## Discovered transient accuracy boundary

The first maintained replay retained a genuine RC transient disagreement at the
original 10 microsecond requested step: VACASK's first sample after the PWL slope
change at 1 millisecond is 0.0009900990099 V, versus the independent finite-rise
solution 0.00049833749168 V. The 0.0004917615 V error exceeds the unchanged
`2e-5 V + 0.002 * abs(reference)` criterion. Ngspice meets that criterion.

Controlled diagnostics reuse the same OSDI and stimuli. Tightening relative tolerance
to 1e-8 or selecting gear2 does not remove that first error. Changing only the
requested initial/maximum timestep to 1 microsecond yields 10,097 VACASK samples,
zero independent-reference failures and a maximum error about 4.997e-6 V. The
post-breakpoint pattern is consistent with an integration startup effect; it is
not claimed as a proved description of the solver's internal algorithm.

The maintained final replay retains the coarse case as an expected completed
numerical disagreement and passes the refined common-step case on both consumers.
No reference tolerance, source expression, model artifact, stimulus or adapter
semantics is relaxed. Both outcomes are part of the advertised finite profile;
this does not qualify every transient timestep or event form.


## Focused validation and architecture review

The shared bench extraction preserves the existing ngspice deck/grid semantics and
private compatibility aliases. Shared ASCII parsing is parameterized by two closed
writer dialects; each adapter still validates its own requested grid. Artifact
staging, bounded process execution, raw hashing/EOF and callback transactions reuse
the existing owners. Public collected, streaming and sweep execution use one
explicit adapter dispatch. Both adapters receive the identical authenticated OSDI.
No model optimization or approximate language normalization is needed for this
increment; consumer spelling changes apply only to the admitted bench binding.

Focused executions:

- Seven shared/adapter suites: 82/82 passed (shared bench 5, ngspice circuit 9,
  ngspice simulator 23, ngspice raw 26, VACASK simulator 10, VACASK bench 5,
  VACASK raw 4). The final VACASK simulator suite then passed 11/11, adding an
  exact transient-zero-start negative.
- Public cross-tool source/type/alignment tests: 5/5 passed.
- Affected public predecessors and artifact lifetime tests: 19/19 passed
  (F50 8, F51 4, F52 solver tolerances 5, artifact staging 2).
- Legacy ngspice bench tests: 17/17 passed. The original F48–F52 evidence remains
  historical and is not relabeled as a new exhaustive replay.
- Backend registry checks: 46/46 passed, including malformed manifests, provenance,
  stable existing profiles and deterministic generated registry checks.

The final adapter/source architecture review found no remaining concrete issue.
A discovered artifact-input descriptor leak on failed output acquisition was fixed
with nested resource ownership; 32 repeated existing-destination failures preserve
the destination and leave no source descriptors open. Review did not execute tests.

## Finite capability, scale and determinism

Qualified consumer pair: existing `ngspice-47-local-linux-x86_64` and
`vacask-0.3.4-linux-x86_64`, with
`openvaf-reloaded-20260616-linux-x86_64`. DC uses the same explicit requested grid;
AC covers linear samples and positive integer-decade spans. Transient comparisons
require explicit linear alignment, complete equal endpoint domains and no
extrapolation. Exact alignment intentionally rejects differing adaptive grids.
Complex interpolation occurs before real/imaginary/magnitude projection. Zero
tolerance is allowed and the actual RLC replay retains a completed disagreement.

The raw parser retains its 16 MiB, 16 KiB line, 100,000-point and 1,000,000-cell/line
bounds. The VACASK manifest is capped at eight directories, 64 files, 128 MiB per
file and 256 MiB total explicit runtime libraries. Cross-tool comparison retains
one bounded reference waveform and streams the candidate through the existing
reducer; it does not retain a second waveform. The actual refined RC case checks
10,026 ngspice and 10,097 VACASK samples. Repeated fresh executions retain distinct
run identities while matching model/artifact and requested-grid semantics. No
whole-pipeline linearity, solver equivalence or performance improvement is claimed.

Noise, arbitrary event forms, arrays rejected by the compiler, general DAE models,
noninteger-decade VACASK grids and unsupported startup/runtime configurations are
outside this executed intersection. They remain explicit capability failures;
optional selection does not waive qualification of the delivered profile.

## Reproduction

`Increment53CrossToolReplay` accepts: output directory, nodalc, nodal-translate,
OpenVAF executable/profile, ngspice executable/profile, VACASK executable/profile,
explicit runtime-library directory and source revision. Run it with
`./mill --no-server core.scala.testkit.test.runMain nodal.internal.testkit.Increment53CrossToolReplay`
and those positional arguments. The exact local invocation is retained in
`.work/increment53/run-cross-replay.sh`; profiles and official archive/runtime
pins are tracked in `core/compiler/backend-profiles-v0.1.json`.

The current increment does not affect generated Verilog-*.
It executes unchanged generated F52 diode, amplifier, controlled-source, RLC and
RC models through a second consumer, preserving source/model/artifact hashes,
independent analytical references, both raw files and both execution receipts.

The maintained replay uses this public Scala call (paths and admitted bench are
constructed immediately above it in the fixture):

```scala
val va = SimulatorTools(
  vacaskPath, "vacask-0.3.4-linux-x86_64", evidenceRoot,
  Duration.ofSeconds(60), SimulatorBackend.Vacask, Vector(runtimePath)
)
val comparison = SimulationSession.compareTools(
  refinedBench, ng, va, WaveformAxis.Time, rcProbe,
  Tolerance(2e-5.volts, 2e-3), alignment = WaveformAlignment.Linear
)
```

`refinedBench` uses the unchanged compiled `Increment52Sources.rc()` with R=1000
and C=1e-6, the four-point PWL above, a 1e-6 s requested step and 0.01 s stop.
Its retained `rc-generated.va` contains the actual Verilog-A contribution:

```verilog
I(z, a) <+ ((V(z, a) / R) + (C * ddt(V(z, a))));
```

The original 10 us and refined 1 us requests retain separate request/grid identities,
both tool receipts and raw hashes. Changing the explicit requested step does not
mutate the compiled model or silently substitute a run in the comparison API.


## Final executed evidence

The final maintained run is
`.work/increment53/replays/cross-tool-17083499019262899689`, with terminal
`failures=0` and 88 checked assertions. `source-hashes.tsv` matches the final source
files, and `tool-hashes.tsv`, `receipts.tsv`, `comparisons.tsv`, `checks.tsv` and
per-tool native receipts retain actual execution identities. TSV display cells
escape control separators; original native receipts and raw bytes are unchanged.
Five generated models compile once each. Actual retained runs include a two-point
public VACASK sweep, repeated fresh DC/AC runs, all real/imaginary/magnitude
comparisons, missing-tool/profile negatives and adaptive-grid incompatibility.

The refined RC maximum independent errors are 7.87146e-8 V (ngspice) and
4.99677e-6 V (VACASK); maximum aligned cross-tool difference is 4.96151e-6 V,
within the unchanged criterion. The coarse case retains 174 failing comparisons
and is correctly `Disagreement`, not `Compared`. An actual zero-tolerance RLC
comparison also remains `Disagreement`; the foreign probe, unavailable tool,
unsupported analysis/profile and exact-grid mismatch retain distinct failures.
The two-point public sweep checks ordered coordinates, independent references,
unique run identities and both retained receipts.

Final Scalafmt, syntactic Scalafix, package visibility, architecture,
contribution policy, generated registry freshness and whitespace checks passed.
Raw local logs remain under `.work/increment53/`; no CI was launched.

Verified integration: PR149 is merged; dev matched the squash SHA, its sole parent
is `c5be30eeebb7c90a3cf12e979cd960a16284f57b`, and the exact tested tree is unchanged.
The merge message includes `[skip ci]`; Actions inventory was zero. This closure
changes documentation only and does not imply additional production qualification.
