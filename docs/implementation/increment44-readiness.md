# Foundation 44 readiness

**Date:** 2026-10-18
**Status:** Implementation and focused acceptance complete; verified integration pending.
**Branch:** `increment/44-analysis-environment`
**Base:** `dev` at `3aea29823aaeea532c30471387cdd26ed51d8719`

F-043 is integrated as `f706d6e8bcc3a36f2adcbfd78fc56289c01934cd` with exact
focused-tested tree equality; the base adds documentation-only closure. Continue
Foundation under AGENTS/CONTRIBUTING: focused positive/negative/boundary checks,
affected predecessors and concise architecture review, `[skip ci]`, no CI or
hourly monitoring. The original F-044 A–G checklist remains authoritative.

## Implemented query contract

Promote simulator-context reads through shared source facts, canonical typed IR,
independent native verification and target spelling. AnalysisContext.active
already has an Increment 38 implementation; temperature, nominal temperature,
time/frequency and named environment surfaces were candidate operations. Dimension
facts alone do not establish implementation. Reuse AnalogFunctions simulator-state
effects, TimeWaveform absolute time, AnalogEvents scalar source expressions and
existing bridge/native/target consumers. Preserve pure-function and static proof
boundaries and initial/final event ownership.

The [approved v0.1 gate](../design-gates/NodalAnalysisEnvironment-DG-v0.1.md)
selects temperature/nominal-temperature/time, including kelvin normalization
and no hidden defaults. It explicitly retains the remaining context/provider
questions. Standard nominal temperature is Celsius, unlike ambient kelvin. A
portable source frequency query was not established by the inspected language
inventory; VPI frequency and vendor `$freq` do not establish generic VA support.
The existing Increment 133 gate froze condition/corner/sweep/seed signatures
with compiler/simulator behavior inert; F-139.B.1 expressly owns their immutable
typed provider contexts. F-044 records and checks that unavailable-provider
boundary, rather than claiming execution or silently replacing environment with
ordinary parameters. The generated registry carries result kind/dimension,
selector, effect, phase and availability; unknown provider calls reject explicitly.

## Original obligations and executed evidence

- A/B: closed semantic query identities, typed units/effects/applicability through
  source/bridge/native/backend; independent malformed-input checks and portable
  versus unavailable capability decisions; initialization/final context remains
  distinct from ordinary parameters and future scheduling work.
- C: wrong units, unknown analysis/context, pure-function/static-context reads,
  initial/final ordering and affected 37–43 regressions, including generated local
  state and source-expression consumer boundaries.
- D: actual public Scala/IR/VA demonstration; clearly distinguished internal
  reparse and any independent compiler run. Concrete later 48/49/52 tool handoffs
  identify analyses, scalar references, tolerances and observability.
- E/F: review query duplication without folding/moving dynamic reads or
  initialization effects; exercise repeated/generated contexts and stable identity.
- G: retain concise evidence/limits and verify integration only after required
  children pass. A–F and their implementation/validation children are complete;
  G and the parent remain open until verified integration.

## Architecture and portability decision

The canonical function registry now owns context capability rows and generates
Scala/native descriptors. Existing analysis and time retain their concrete IR
adapters; only environmental scalar reads require a new operation. A fixed small
lookup table and existing visited-set/memoized expression traversal are sufficient.
No per-instance context registry, hidden cache, new scheduler or provider framework
is introduced. Runtime query duplication is not optimized by assuming constness;
state and initialization ordering remain with their existing owners.

The original F-044 portability obligation is distinct from F-139.B.1's explicitly
scheduled immutable typed PVT/condition/sweep/seed contexts. The source preserves
those signatures but now reports their unavailable capability rather than recording
an inert expression as apparent success. Frequency likewise has no enabled generic
VA source mapping. This is the finite language-output profile recorded in
`backend-profiles-v0.1.json`, with conditional standard nominal-temperature support
separate from actual simulator execution. Existing active-analysis mapping and
initial/final filter semantics are preserved; unique implementation-specific event
filter names remain legal rather than being narrowed to the active-query enum.

One directly affected compatibility correction rejects forged fold/static claims
on analysis/time/context queries and their dependent expressions. The prior
Increment 38 forged-analysis case expected advisory annotation erasure; its new
negative must assert rejection while retaining the ordinary dynamic-query positive.
Historical acceptance remains historical, not evidence of the new rejection.

## Concrete later tool and numerical handoff

The public `AnalogContextSource` witness in
`examples/continuousTimeApi/src/nodal/increment44fixture/Increment44ConstructionCheck.scala`
uses ambient/nominal temperature ratio plus elapsed seconds to drive a voltage.
For F-048, compile the actual generated scalar VA with the selected tool and record
whether `$temperature`, standard `tnom` and `$abstime` are supported. Acceptance
of the syntax is not execution of its simulator parameters or units. Event and
array-bearing companions require their own already-declared tool capabilities.

For F-049/F-052, drive an unloaded output with ambient 300 K, nominal 26.85 Celsius
(300 K), and transient time 0–2 seconds. The scalar reference is
`V = (T_K / (tnom_C + 273.15) + time_seconds) volts`; expected endpoint values
are 1 V and 3 V. Repeat with ambient 330 K and unchanged nominal for 1.1 V and
3.1 V. Compare absolute voltage error <= 1e-8 V + 1e-6 relative and maximum step
0.01 s. A temperature sweep at DC must follow the same ratio without substituting
a module parameter or folding at a default. These numerical runs are not yet
executed and do not block compiler-only prototype acceptance.

The generated companion repeats the voltage contribution twice; compare the
same source against two explicitly authored contributions. Initial/final event
companions should observe elapsed state at the start/end of transient analysis,
preserve one body per event and independent generated storage, and preserve the
ordered local-temperature read before assignment. These internal variables need
hierarchical observation or an explicit observation variant before numerical
comparison; generated source text alone does not expose their values. No callback
or solver scheduling guarantee beyond the language event contract is invented.
Unsupported vendor filter names cannot count as observed event positives.

The directly affected target-parser predecessor that rejected `$abstime` inside a
live event-cache input now becomes a positive: F-044 deliberately admits the
observational time query in supported procedural consumers. Malformed time calls,
unknown temperature spellings and nominal fallback arguments remain negative;
stateful `ddt` in that position remains rejected. This narrow admission does not
reclassify arbitrary waveform operators as observational queries.

## Executed independent compiler check

The actual public continuous witness emitted the following contribution:

```verilog
V(positive, negative) <+ ((($temperature / ($simparam("tnom") + 273.15)) + ($abstime / 1)) * 1);
```

OpenVAF-reloaded `20260616-3-g0e83f1ed` compiled this exact generated VA into an
OSDI shared object successfully. Source SHA-256:
`1196e1b55f899c0d0a566bb3485a2aa274889ffa309379e5cc8d6b73a5ff99f3`;
OSDI SHA-256: `e9ac18c6148fd863489757785b8da61aad3ba00738ce73561e427b20f5c080a4`.
Command: `.work/f43-array-initializer-profile/openvaf-reloaded/openvaf-r
.work/f44-context/public/continuous.va -o
.work/f44-context/openvaf/openvaf-reloaded.osdi`.

The same source with OpenVAF `23.5.0` exited 101 and reported an internal LLVM
code-generation panic (`openvaf/mir_llvm/src/context.rs:83`, failed option unwrap).
This is a tool crash, not a successful compile or a diagnosed language rejection.
Version, binary identity, logs and the crash report are retained under
`.work/f44-context/openvaf/`. No simulator loaded/executed the produced OSDI;
nominal parameter availability at runtime, numerical values, event ordering and
other target profiles remain separate checks. No external issue was filed.

The public combined generated construction (continuous block plus a separate
procedure inside one generated region) passes normalization but reaches the
inherited `NODAL-BACKEND-CAPABILITY-001` one-analog-region target limit. Separate
generated continuous and procedural witnesses exercise the supported ownership
paths; they do not prove combined-region target support. The mixed case remains
explicit construction evidence and an unsupported target case, rather than being
silently relabeled as a passing emitted profile.

A query-only generated analog region with no contribution, owned event procedure
or bound-step effect also normalizes but is rejected by the inherited generated
emission profile. Its source/IR acceptance is not reported as target acceptance.
The checked diagnostic preserves this boundary; it does not silently discard an
unsupported generated region.

## Focused native and source acceptance

Executed native checks pass: 48 new context cases, 592 event-reparser cases, 57
user-function reparser cases, 45 transfer reparser cases, seven actual context
backend checks, the affected backend and analog-expression units, 21 storage checks,
and 18 selected analysis/folding predecessors. The new backend checks cover
ordinary/generated VA and AMS, deterministic repeats and transactional rejection.
The 18 predecessor cases retain all six ordinary analysis-query spellings before
and after folding and reject direct/transitive forged fold certificates.

Commands use `.work/native-release/core/compiler/test/Unit/` binaries:
`nodal-context-unit-tests`, `nodal-event-reparse-unit-tests`,
`nodal-user-function-reparse-unit-tests`, `nodal-transfer-reparse-unit-tests`,
`nodal-analog-context-backend-unit-tests`, `nodal-backend-unit-tests`,
`nodal-analog-expression-unit-tests` and `nodal-storage-model-unit-tests`.
`python -B .work/f44-analysis-predecessor.py` runs the selected maintained
Increment 38 helpers without executing the full matrix. Build and result logs
are retained as `.work/f44-context-native-build*.log` and `.work/f44-*.log`.

Fifteen focused Scala methods pass (14 initial cases plus the exact repeated-fill
case with no storage reads), as do eight selected function/time/event/user-function
and generated-storage predecessors. The sharing case retains two actual deferred
handles, empty storage-read inventories, and separate control/event consumers;
simulator reads are not converted into static fill values merely because their
storage-read inventory is empty. Pinned formatting/lint and final actual target
profile replay are recorded with the final publication evidence below.

Five generated registry/closure checks pass using the selected
`tests.compiler.test_increment38.RegistryTests` methods, including context/profile
consistency and deterministic generation. Existing 24 mathematical functions and
six analysis identities remain unchanged. Package visibility, contribution-policy
preflight, diagnostic JSON and changed-document checks pass.

Focused native/parser and source/bridge reviews found no concrete blocker after
repairs. Explicit empty conditional-arm fixtures were corrected to valid nonempty
bodies so static-query negatives exercise their intended diagnostic instead of
malformed-region rejection. Final builds include the target scanner refinement;
no stale pre-refinement binary is used as acceptance evidence.

## Actual source and normalized IR demonstration

The public continuous witness constructs:

```scala
val normalizedTemperature = EnvironmentContext.temperature /
  EnvironmentContext.nominalTemperature
val elapsed = AnalysisContext.time / 1.0.s
V(positive, negative) <+ (normalizedTemperature + elapsed) * 1.0.V
```

Its actual normalized IR contains these typed definitions (including the additional
temperature argument to the source math call, copied without
attribute elision):

```mlir
%3 = "nodal.analog_context"() <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "AnalogContextSource.normalizedTemperature", source_end_column = 65 : i64, source_end_line = 10 : i64}, query_kind = "temperature", registry_version = "1"}> : () -> !nodal.quantity<"real", "temperature">
%4 = "nodal.analog_context"() <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "AnalogContextSource.nominal_temperature_increment44_construction_check_l11_3edc1929", source_end_column = 44 : i64, source_end_line = 11 : i64}, query_kind = "nominal_temperature", registry_version = "1"}> : () -> !nodal.quantity<"real", "temperature">
%13 = "nodal.analog_context"() <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "AnalogContextSource.temperature_increment44_construction_check_l14_c168beb1", source_end_column = 59 : i64, source_end_line = 14 : i64}, query_kind = "temperature", registry_version = "1"}> : () -> !nodal.quantity<"real", "temperature">
```

The ratio and time arithmetic consume these SSA values before the contribution.
The generated VA and independent compiler result above correspond to this public
source and normalized IR, not a hand-authored substitute.


## Final public replay and checklist disposition

All seven public source witnesses verify under explicit VA and AMS profiles.
The five supported continuous, event, shared-fill, generated-continuous and
generated-event witnesses produce ten outputs with exact direct/normalized
lowering parity. Four exact capability negatives retain the two inherited
unsupported generated layouts described above. Initial/final ordering, local
initialization inside the initial handler, final-handler time assignment, generated
local state and complete-monitor shared-fill reset/cache placement are asserted.
These are compiler structure checks, not numerical scheduling results.

Reproduce the maintained public export with:

```sh
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.runMain nodal.internal.testkit.Increment44MlirCheck .work/f44-context/public
.work/native-release/bin/nodalc --pass-pipeline='builtin.module(nodal-gate-default)' .work/f44-context/public/continuous.mlir
```

The selected Scala test command is
`.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly
nodal.internal.testkit.Increment44ContextTests`; selected predecessor commands and
public target replay are retained in `.work/f44-context/check-scala.sh` and
`.work/f44-context/replay-targets.py`. Final summaries are
`.work/f44-context/scala-focused-summary.log` and
`.work/f44-context/target-replay-summary.log`. The exporter constructs each public
module repeatedly and compares complete bridge documents, including source
identities; generated witnesses retain structural repetition instead of expanding
lanes. This bounded representative check is not an unlimited hierarchy benchmark.

Pinned Scalafmt 3.11.5 passes all 12 changed/new Scala files. Pinned syntactic
Scalafix passes changed tracked lines and all four new files; whole-file lint
still has 15 inherited findings and is not reported as passing. Native formatting,
registry generation, package visibility, contribution policy, JSON and Markdown
checks accompany publication. No CI or broad suite was executed.

A/B are satisfied by the shared descriptor, typed source/IR/native/target path and
explicit capability/effect boundary. C is satisfied by exact malformed/static/pure
negatives and the focused lifecycle/predecessor checks. D retains the actual
public source/IR/target demonstration, separately classified independent compiler
result and concrete numerical handoff. E requires no extra query CSE: replacing
simulator observations with constants or moving them across consumers would need
additional semantic proof; existing explicit shared-fill demand ownership is
preserved. F is covered by repeated source instances, repeated target emission,
analysis/context combinations and generated occurrence-local witnesses. G and the
parent await the verified integration commit. No F-139 provider implementation or
unsupported vendor frequency behavior is claimed by this F-044 completion.
