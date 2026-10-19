# Increment 52 readiness and implementation

Status: focused implementation and replay complete; F-052 parent/G remain open until verified
integration. Base `40562445ef681e1ed29bfdd899adbee7b06429d2` is the verified F51
closure. Branch: `increment/52-analog-regression`.

## Architecture and selected capability profile

The [approved gate](../design-gates/NodalAnalogRegression-DG-v0.1.md) defines the
finite shared contract. Maintained fixtures and independent analytical references
use existing public source/compiler/bench/result services and existing private
adapters. No second process runner, raw parser, solver, target-text rewrite or
handwritten substitute model was added.

Three essential shared corrections were implemented:

- Actual ordinary analog Variable assignments carry `analog-assignment` admission
  attribution; unsupported digital assignments still reject. Declarative placement,
  source naming, generated ownership and DA remain checked. A caught procedural
  recorder failure poisons its construction transaction rather than publishing an
  attributed but unrepresented effect.
- Explicit `AnalogHierarchyMode.MaterializeScalar` invokes native
  `--nodal-to-verilog-a-scalar-hierarchy`. The complete verified scalar electrical
  graph is materialized before ordinary rendering using actual connectivity/SSA
  mappings. Root parameters stay symbolic; child literal/direct-parent actuals,
  occurrence-local functions and root initialization are preserved. Original
  derived probes are independently verified and recomputed after mapped wiring
  changes. Authored filenames/text/spans remain untouched; deterministic encoded
  correspondence records retain hierarchy identity. Default named emission stays
  unchanged. Expansion preflight checks 1,000 occurrences, 100,000 operations and
  depth 64; failure publishes no target.
- Public dimension-safe `SolverTolerances` configures the existing solver owner,
  independently from waveform acceptance. Defaults remain relative 1e-6, current
  absolute 1e-12 A and voltage absolute 1e-9 V. Finite-positive values and the
  existing relative <= 1e-3 profile bound are checked. `SimulationBench.create`
  appends an optional argument; sweep copies preserve canonical options and the
  adapter retains settings in request/evidence identity. No raw deck, GMIN or
  temperature knob was added to this public contract.

The materialized child profile is continuous scalar electrical behavior, pure
nonrecursive functions and ordinary dimensionless Real parameters with literal
defaults and literal/direct parent actuals. Child procedures/stateful operators,
arrays, structural generation, custom disciplines and equation inventories reject.
The tests exercise supported interactions rather than an unlimited cross product.

Ordinary Real parameter initializer DAGs remain outside the bridge profile
(`NODAL-BRIDGE-021`). The regression sources use symbolic literal coefficients and
explicit typed unit factors in analog expressions: inductance coefficient times
one second, and frequency coefficients divided by one second. Equations and
acceptance limits were not changed or default-specialized. Reserved authored
`ground`/`input`/`output` field names were repaired to portable source spellings;
public simulation port labels stayed unchanged. Initial failing evidence remains.

## Executed local checks

- 33 assignment/admission and directly affected Scala checks pass: new admission 5,
  F50 public simulation 8, source origin 7, procedural placement/ownership 6,
  declarative exclusion 1, event effects/held continuity 3, generated ownership 3.
- Final focused Scala run passes 51 tests: scalar references/source 14, hierarchy
  construction/default-mode 3, F50 simulation 8, NgspiceBench 17 and
  NgspiceCircuitBench 9. Separate solver tolerance checks 5 pass
  defaults, stricter rendering, invalid values, adapter revalidation and units.
- Native materialization passes seven positive categories and 11 transactional
  negatives, including all three expansion bounds, forged probe support/ownership,
  malformed correspondence, root override, multi-anchor connectivity, unsupported
  child behavior, equation inventories and reserved names. A separate input/output
  controlled-amplifier child exercises source-free probe recomputation. Repeated
  `Leaf.scala` provenance tests prevent semantic-prefix rewriting of filenames.
- Affected native backend and statement-provenance tests pass. Exact formatting,
  package visibility, architecture, contribution-policy and diff checks pass.
  Five unchanged pre-existing syntactic lint violations in CandidateApi and
  ConstructionSession were compared against the base; none was added/suppressed.

Raw commands/results are under ignored `.work/increment52/` and
`.work/increment52-native-*.log`; committed source fixtures own reproduction.
No CI, hourly monitor or broad qualification was run.

## Actual generated-model and numerical evidence

All ten scalar families compile through capture, native normalization/verification,
actual Verilog-A and pinned OpenVAF into fresh OSDI artifacts. Final scalar replay
also compiles three seeded recapture pairs: 16 compilations total. Its 48 session
receipts contain 46 successful simulations, one expected ToolUnavailable and one
actual Nonconvergence. All 45 waveform-component comparisons (7,362 component samples) pass; language, bench,
resource and intentional numerical-discrepancy controls also pass separately.
Seventeen retained source copies match their recorded hashes.

| Case | Independent contract and observed result |
| --- | --- |
| RC | DC/AC plus finite-rise current transient; 1,038 transient points, maximum error 4.743e-6 V against 2e-5 V +0.002 relative. |
| RLC | Actual internal junction and separate inductor/capacitor derivatives; 13 AC points, real/imaginary/magnitude maximum error 4.441e-16 V against 1e-8 V +1e-5 relative. |
| Diode | Signed Shockley DC current and AC derivative; 13 DC points maximum error 1.03e-18 A against unchanged 1e-15 A +1e-5 relative. |
| Amplifier/comparator | Native pure functions, affine/clipped output, both knees and comparator equality/high branch; 1e-9 V +1e-6 relative. |
| Oscillator/VCO | Phase integrates instantaneous frequency; changing-control PWL and fixed-frequency cases each 3,018 transient points. VCO maximum error 4.442e-7 V against predeclared 0.003 V +0.003 relative. No instantaneous-frequency-times-time substitution. |
| Initial step | Real generated initialization/held storage, 24 transient points, observed error 0 against 1e-9 V +1e-6 relative. |
| Sweeps/repeats | 12-point Cartesian controlled-source sweep and exact fresh repeat, three fixed-seed RC parameter recapture pairs; exact coordinates/model/IR identities where asserted. Oversized 144-point sweep rejects before tools. |

The first scalar numerical attempt correctly reported two diode discrepancies:
8.04e-14 A and 5.94e-13 A at -0.05 V/0 V with solver abstol 1e-12 A. A causal rerun
on the same OSDI changed only abstol to 1e-18 A; relative/voltage tolerances,
stimuli/model/GMIN/temperature stayed identical. All 13 points then passed the same
acceptance criterion, zero-bias residual 0. Both raw results remain retained. This
is an explicit solver accuracy requirement, not a relaxed numerical reference.

The complete repeated/nested hierarchy passes external rootGain 2/4/6 plus an exact
repeat at 4 on one OSDI. Four independent leaf currents over five DC points provide
80 comparisons, observed error 0 for these selected values against 1e-12 A +1e-6
relative. Four ambient/nominal temperature settings pass 21 transient points each
against `(tempC+273.15)/(tnomC+273.15)+timeSeconds`. A separate bounded hierarchy
plus root initial-step/state interaction passes two runtime gains (2 and 6), each
with five DC points against `-(gain*voltage+1)/1000 A` and the same current tolerance.
The original four-leaf hierarchy fixture remains unchanged.

F45 original canonicalization and a separately authored unsummed Scala reference
both compile and pass 16 DC/transient runs, covering defaults, gain-only change,
offset cancellation and repeat. All three outputs pass the original 1e-8 V +1e-6
relative criterion, with 72 comparison records and maximum error 3.553e-15 V.
Contribution/call/local structure is checked separately. Transients cover 0–2 s
with 208 returned points; analytical and source-to-reference comparisons both pass.

### Pinned consumers and retained evidence

OpenVAF-reloaded `20260616-3-g0e83f1ed`, profile
`openvaf-reloaded-20260616-linux-x86_64`, executable SHA256
`b117284d8f871dbf54609ab009cdff819f443d8d33064111b8b0782af5c7e931`.
Ngspice 47 local profile `ngspice-47-local-linux-x86_64`, SHA256
`ba254f361c728f70cbb09293750d93f541f26c256d56286ddfb755d0d2793711`.
The [F49 tool-build distinction](increment49-readiness.md) remains: this is the
pinned locally built dependency with recorded standard-header build flag, not an
official released binary or a hidden source patch. OSDI 0.4 execution is established
by populated model-dependent outputs, not compilation alone.

Selected successful raw evidence directories:

- `scalar-numerical-18399889632632447398`: final scalar replay.
- `scalar-diode-only-4935021634627056594`: default/strict solver causality.
- `hierarchy-12055820959762942455`: final whole-hierarchy/context/state replay.
- `canonicalization-3302748847441835633`: F45 original/unsummed comparison.

These are under `.work/increment52/{scalar,hierarchy,canonicalization}-replays/`.
Receipts retain source/model/artifact/tool/request/raw hashes and commands. Source
revision identifies the base; retained working-tree bytes, not that base alone,
identify the tested implementation. All six final hierarchy source hashes match
the tested working tree; all ten hierarchy/context/state simulations pass.

## Optimization, scale and limits

Reuse of shared compiler/process/raw/waveform owners avoids redundant frameworks.
The bounded fixture set retains actual outputs and meaningful negatives; no cache
or fixture deletion was justified. A 129-occurrence native witness emits 83,228 bytes,
observed 173,408 microseconds for emission; the whole focused process took 0.752 s
and 22,588 KiB maximum RSS. This is one measured finite observation, not a speedup or
whole-pipeline asymptotic claim. Construction/token/read-set costs and existing
native identity scans are not claimed linear. Result retention is bounded by the
existing simulator/raw limits; this increment does not replace F51 streaming work.

Noise and unsupported cross/above/timer/private-effect helpers are not numerically
qualified here. The historical H42 equation-only source retains source inventory,
not executable DAE behavior; F134/F135 full parents remain open. Arrays and broader
child state/hierarchy forms retain their actual compiler/profile exclusions.
The original F43–F48 handoffs remain distinguishable from this supported F52
intersection; no old rejection or parse-only result is reclassified as execution.

## Reproduction and actual source demonstration

The maintained entry points are `Increment52SourceReplay` (modes `compile-only`,
`diode-only`, `numerical`), `Increment52HierarchyReplay`, and
`Increment52CanonicalizationReplay`. Each accepts output root and absolute native,
OpenVAF/profile and ngspice/profile paths. Scalar/canonicalization also take the
base HEAD SHA; scalar adds its mode. Invoke through the existing testkit runMain:

```sh
./mill --no-server core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment52SourceReplay .work/increment52/replay \
  "$NODALC" "$NODAL_TRANSLATE" "$OPENVAF" \
  openvaf-reloaded-20260616-linux-x86_64 "$NGSPICE" \
  ngspice-47-local-linux-x86_64 "$(git rev-parse HEAD)" numerical
```

The tested VCO source preserves the physical phase integral:

```scala
val base: Param[Real] = param(baseHertz.real)
val sensitivity: Param[Real] = param(sensitivityHertzPerVolt.real)
analog:
  val frequency = (base + sensitivity * V(control, referenceNode) / 1.0.V) / 1.0.s
  val cycles = idt(frequency, 0.0.real)
  V(outputNode, referenceNode) <+ amplitude * AnalogMath.sin((2.0 * math.Pi).real * cycles)
```

Exact full sources are `Increment52AnalogFixtures.scala` and the separate
hierarchy fixtures. Public solver selection for the low-current diode is
`solverTolerances = SolverTolerances(currentAbsolute = 1e-18.amps)`; waveform
acceptance remains separately declared in `Increment52References`.

A/F: shared ownership, finite expansion/retention, deterministic sweeps and seeded
repeats reviewed. B/C/D: real source/model execution, physical references and
meaningful failure stages and the final hierarchy/state interaction are recorded above.
E: no additional optimization is justified by the selected measured costs.
G and parent stay open until verified integration.
