# Increment 57 readiness and implementation

Status: implementation in progress; all F-057 checklist children remain open.
Base: `16530a5b0630b709dcc500d2512fa276c755faab`, the verified F56 documentation
closure after PR152. Branch: `increment/57-clock-reset-crossings`.

## Existing owners and required work

The original F57 scope includes domain construction/application and bindings,
typed clock relationships, all six CDC categories, reset synchronization and
composition, physical clock gates/muxes, waivers and the restricted low-level
event escape. The accepted [clock/reset architecture](../architecture/0007-implicit-clock-reset-domains.md)
and [v0.2 API gate](../design-gates/NodalClockResetApi-DG-v0.2.md) remain normative;
[v0.3](../design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md) additionally defines
Stream as ordered elastic ready/valid. A candidate method returning its input or
recording only a descriptive string is not implementation of those semantics.

F56 supplies actual local clock/reset bindings, sequential sampling, Interface
and memory provenance, canonical updates, and transactional target lowering.
`DigitalAssignmentModel` already owns physical writer dependencies and bounded
SSA traversal. `SequentialModel` checks register, memory, guard and FSM sampling.
The first production prerequisite factors their domain and requirement-binding
queries into one shared native domain-proof owner, preserving existing admission
and diagnostics before adding F57 rules. It does not create a second CDC graph.

Subsequent executable crossing results must be explicit destination-domain cuts
in that same dependency graph. Each crossing independently validates its actual
source dependencies, category, endpoints and protocol/reset contract. Lineage is
retained across the cut for reconvergence and waiver analysis. Typed reset and
clock producers require actual operands and independently checked provenance;
merely labeling an arbitrary value Clock or Reset is insufficient.

F19 domain/crossing records are existing descriptors, not evidence of executable
safety. New executable contracts must distinguish them explicitly. Existing
source/bridge placeholders lose crossing/event edges or refuse generated domains;
those limitations remain open implementation work, not accepted F57 behavior.

## Compatibility decisions and remaining category contracts

The additive [execution gate v0.1](../design-gates/NodalClockResetExecution-DG-v0.1.md)
records the domain/level foundation, continuously-valid Stream compatibility,
single sink-ready ownership, handshake snapshot/coalescing meaning and derived
domain decisions. The remaining detailed reset/clock/protocol implementation
contracts below stay explicit open work; design approval is not test evidence.

- Only a proved `Same` relationship permits direct interchange by default.
  Frequency equality, a declared ratio or a waiver does not erase actual origins.
- The original F12 unsynchronized-reset negative contains only a raw `Async`
  domain binding, without state. Its required diagnostic therefore cannot be
  postponed until a register samples the domain. Preserve the `Async` policy's
  meaning while distinguishing actual synchronized release from an unproved
  external release. F56's historical raw-Async acceptance must be described
  honestly when the F57 safety boundary changes.
- Generated domains lack explicit edge/reset-policy constructor parameters.
  Resolve these from the actual parent and reset capability, rather than invent
  defaults. A Bool-selected clock mux needs an explicit finite source-selection
  rule and compatible reset provenance; do not combine unrelated resets silently.
- The legacy `Cdc.handshake(payload, to)` result is an Expr, with no public
  request/acknowledge endpoints. Its executable transfer/boot semantics must be
  explicit; it cannot promise that every changing source value is delivered.
- `Stream(payload)` must preserve the already-approved elastic protocol. Actual
  valid/ready endpoints, single-owner ready attachment, stalled-payload stability,
  reset and unconsumed-output behavior need a precise compatible contract.
- Gray/Pulse wrappers retain categories but do not prove arbitrary runtime Gray
  transitions or pulse spacing. Any required assumptions/assertions must remain
  represented and source located; no unreported lossless or metastability claim.
- Digital event handles must retain actual source/edge/owner identity before a
  useful low-level process can be admitted. Ordinary domain state and CDC/RDC
  bypass remain forbidden in that escape.

## Validation and architecture plan

Use focused category positives, exact malformed-input negatives and affected F56
sampling/normalization cases. Include direct and combinational crossings, real
Interface and memory dependencies, independent synchronizer reconvergence,
waiver lineage, reset reconvergence and polarity, generated bindings, gate/mux
source identity, and quarantined event effects. Preserve original required
fixtures rather than changing their meaning to fit a placeholder implementation.

Validate source capture, actual bridge SSA, independent native ingress and each
advertised target category separately. Target failure must leave output unchanged.
A future simulator, proof or AMS backend is not an executed result. Keep concrete
65/66/67/72 downstream cases and F58 hierarchy ownership explicit without making
those entire later increments reverse prerequisites.

Reuse bounded actual-graph traversal; avoid all-domain pairwise closure, copied
transitive provenance sets, lane expansion and recursive traversal proportional
to authored hierarchy depth. Check stage/depth/width arithmetic before allocation
and separately bound generated storage. Preserve independent synchronizers as
stateful instances. Measure representative affected growth before any scale claim.
No performance improvement is claimed by readiness inspection.

Raw reports and replay artifacts belong under `.work/increment57`. No CI, broad
qualification or hourly monitoring. Results below identify the particular checked prerequisite/checkpoint; none
complete the remaining F57 categories.

## Initial target feasibility observation

Two small handwritten pinned CIRCT probes verified and lowered locally.
`seq.clock_gate` produces a low-phase enable/test-enable latch plus clock AND;
this is a useful existing target owner to evaluate. `seq.clock_mux` produces an
ordinary combinational mux, so it cannot alone establish Nodal's glitchless
runtime clock-selection promise. The implementation must retain an explicit
checked switching structure or supported mapping. These probes are not
Nodal-generated demonstrations, simulation or completed F57 category evidence.

## Shared native domain prerequisite

The integrated `DomainModel` is a read-only per-module view of the existing IR.
It indexes direct requirement and physical-binding records, rejects duplicate or
malformed proof inputs, and retains the original diagnostic order. Sequential
sampling and memory-port validation now use this owner. Descriptor resolution
still does not imply executable binding; the existing exact typed input-port
profile is unchanged. The former sequential-specific resolver and separate
sampling requirement map are removed rather than retained as competing policy.

The isolated exact-source replay passed 331 focused native cases: 25 new direct
domain queries, 71 sequential, 114 memory, 59 FSM and 62 CIRCT predecessors.
The F19 core-model parse/print/reparse output is stable. All eleven production/test
file hashes were checked before and after applying the frozen patch to the live
branch. These are prerequisite results; the in-progress crossing delta requires
its own integrated replay before publication. No F57 category is complete yet.

Index construction scans direct module records once and retains only pointers
and ambiguity flags. Existing symbol lookup remains authoritative. Per-operation
verifiers can still construct repeated views; no whole-pipeline linearity or
measured speedup is claimed. Views must be rebuilt after changing domain records.
The prerequisite itself does not change generated Verilog-*; actual level-chain
source/native/target work is the next checkpoint.

## Accepted scoped level checkpoint

The shared sampling owner now retains actual crossing identity, source dependency
roots and destination ownership. Its initial reconvergence groups are an actual
register target or an individual memory effect, including explicit guards and
the effective FSM phase/priority controls. A repeated read of one synchronizer
is one boundary; independent synchronizers are not made coherent by equal domain
names. Directly observed combinational output reconvergence still needs explicit
reconciliation against the complete F57 diagnostic contract before parent closure.

Final integrated replay passed 468 native cases across seven directly affected
owners: domain 25, crossing 90, sequential 71, memory 114, FSM 59, assignment
guards 34 and CIRCT 75. The maintained Scala build passed; 106 source/bridge
groups (14 new level/FSM, 55 capture predecessors, 37 bridge predecessors) and
17 selected identity/source/analog predecessors passed. Four direct frontend
negatives and the separate-JAR helper's legal module call/illegal collection
call passed using the current maintained constructor plugin. The stale earlier
plugin's memory-helper rejection is an intermediate tool-selection error, not
final evidence or a new supported-profile restriction.

The FSM phase matrix preserves same-instance reuse and unrelated plain-active
controls. Five actual capture-edge mutations were accepted by the saved
pre-repair native verifier and now reject `NODAL-CDC-057-002`; the future-state
observation case already rejected natively and remains rejected. Source capture
now rejects all corresponding unsafe cases. The independent native ingress and
source checks use their existing FSM phase/priority owners, not a new text walker.
The final unchanged memory-bridge negatives also caught a diagnostic wording
regression; the shared classifier now preserves existing input/state/Interface
CDC001 distinctions without altering admission or weakening those assertions.

A real current constructor-plugin compile emits repeat-identical IR with full
repository-relative spans. The lexical and explicit-destination module-level
public examples both pass native verification, CIRCT conversion and actual
SystemVerilog emission. Isolated `.work` source builds have truthful fallback
source locations, so they are not asserted byte-equal to maintained discovery.
All 45 integrated source/test/document files are accounted for by frozen stage
hashes plus the reviewed diagnostic-only correction and these notes.

Original `SemanticOriginTop` and `ReproducibilityDomainFixture` failures are
retained, not counted as passing: the pre-level maintained classes already reject
the unbound FSM and detached waived assignment respectively. That control used
the existing compiled API/classes; it is not a complete old-tree qualification.
Their assertions remain unchanged for the required remaining F57 categories.

## Executed level target witness

The actual public source is
`core/scala/testkit/test/src/nodal_external/Increment57LevelCrossingSource.scala`.
Its destination block includes:

```scala
val synchronized = Cdc.sync(level.get, to = destination, stages = 2)
val independent = Cdc.sync(level.get, to = destination, stages = 3)
val sampled = Reg(false.B)
sampled := synchronized
val independentSample = Reg(false.B)
independentSample := independent
output := sampled
independentOutput := independentSample
```

The maintained constructor plugin and bridge emitted actual typed crossing SSA.
Production `nodalc --nodal-sequential-to-circt --mlir-print-debuginfo` followed by
pinned `firtool --format=mlir --verilog --verify-each
--disable-all-randomization` produced this SystemVerilog excerpt (only source
comments omitted):

```systemverilog
reg __nodal_sync_0_stage_0;
reg __nodal_sync_0_stage_1;
reg __nodal_sync_0_stage_2;
reg __nodal_sync_1_stage_0;
reg __nodal_sync_1_stage_1;
always_ff @(posedge destination_clock) begin
  automatic logic _GEN = ~destination_reset & level;
  independentSample <= ~destination_reset & __nodal_sync_0_stage_2;
  sampled <= ~destination_reset & __nodal_sync_1_stage_1;
  __nodal_sync_0_stage_0 <= _GEN;
  __nodal_sync_0_stage_1 <= ~destination_reset & __nodal_sync_0_stage_0;
  __nodal_sync_0_stage_2 <= ~destination_reset & __nodal_sync_0_stage_1;
  __nodal_sync_1_stage_0 <= _GEN;
  __nodal_sync_1_stage_1 <= ~destination_reset & __nodal_sync_1_stage_0;
end
```

The three-stage and two-stage instances remain independent even though their
first-stage D expression is shared. The explicit consumer registers add their
own edge of observation; they are not counted as synchronizer stages. The
complete source-located output is retained under
`.work/increment57/level-live/output/public-level.sv`; repeated source capture
and emission were byte-identical. Separate actual CSE/canonicalization witnesses
retain two independent chains at both two and 32 stages, with causal source,
previous-stage, reset and destination-edge checks. This is the selected compiler
path, not vendor synthesis preservation or physical metastability qualification.

## Level checkpoint reproduction and architecture review

Focused commands (repository root; existing pinned tools, no CI):

```sh
source .work/scala-validation/env.sh
.work/scala-validation/mill-jvm.sh --jobs 1 core.scala.testkit.test.compile
python3 .work/increment57/level-live/run-suites.py final-source Increment57LevelCrossingTests Increment57FsmCrossingTests Increment56SequentialCaptureTests Increment56MemoryCaptureTests Increment56FlatFsmCaptureTests
python3 .work/increment57/level-live/run-suites.py final-bridge Increment56SequentialBridgeTests Increment56MemoryBridgeTests Increment56FlatFsmBridgeTests
python3 .work/increment57/level-live/run-filtered.py
ctest --test-dir .work/native-release -R '^nodal.native.(domain-model|crossing-model|sequential-model|memory-model|flat-fsm-model|sequential-circt|digital-assignment-guard)-unit$' --output-on-failure
python3 .work/increment57/level-live/run-public.py
python3 .work/increment57/level-live/run-target.py
python3 .work/increment57/level-live/run-fsm-parity.py
python3 scripts/check_markdown.py
git diff --check
```

Exact commands/logs, source manifests and target hashes are retained under
`.work/increment57/level-live`; tracked fixtures are in
`tests/scala/level-crossings`, `Increment57LevelCrossingTests`,
`Increment57FsmCrossingTests`, `CrossingModelTest` and `SequentialCIRCTTest`.
The final native executable SHA256 is
`02970b8ff9078d9b87668c4202889c0874675590b03ab2c2a50623aee78c5d1f`.
Pinned constructor plugin SHA256 is
`589adeeaed2a7ccf7ddcfeecc6fb5e179e9a8dbdbe66e82f18c23a0ed4e03b15`.
F19 descriptor parse/print/reparse remains stable. Formatting and Markdown checks
pass; source lint has zero new violations. Existing compiler/dependency warnings
are not described as a warning-free build.

Architecture review: DomainModel owns one read-only index of actual local domain
and binding records. CrossingModel owns the closed executable header; the existing
physical dependency graph and sequential sampler own actual source proof and
lineage. Source construction and native ingress independently validate those
facts. FlatFSM owners export effective phase controls shared with their existing
expansion semantics. Target lowering validates, plans on a private clone, checks
aggregate expansion, and commits only complete verified output. It creates actual
independent destination state directly, never ordinary unsafe source-domain
assignments disguised as safe ones.

Stage counts 2–32 and existing graph/state budgets are checked before expansion.
One DomainModel view is reused for each graph/sampling query; proof work and
lineage intersections are charged. No per-value copied transitive domain sets or
all-domain pairwise closure is introduced. The representative isolated native
128-boundary chain measured 7,679 microseconds in its final observation; this is
not a general benchmark. Source checks measure deterministic record growth, and
target checks reject 3,800 authored 32-stage instances before exceeding their
expansion budget. Existing repeated queries may still cost more than linear in
whole-program size; no universal speedup or complete temporal proof is claimed.

Downstream handoff: F65/F66 should retain the two/32-stage resetless and both-edge/
polarity witnesses plus raw FSM reconvergence negatives for parse/simulation;
F67 should check independent state preservation under synthesis/equivalence,
without treating digital simulation as metastability qualification. F72 should
retain the actual domain boundaries when embedding this digital structure in AMS.
Those later consumers were not executed here. Reset/AASR, typed relationships and
hierarchy propagation, Gray/pulse/handshake/FIFO, waivers, gate/mux structures and
the low-level event escape remain required F57 work. All F57 parent/children stay
open; this is a coherent level foundation checkpoint, not increment completion.

## Reset model checkpoint (reconstructed, 2026-10-20)

The additive [reset execution contract](../design-gates/NodalClockResetExecution-DG-v0.2.md)
now has a native typed producer model: `reset_boundary`, `reset_sync` and
`reset_combine`. `ResetModel` checks physical input identity, release lineage,
polarity, receiving clock/edge, reconvergence and bounded traversal. `DomainModel`
provides clock-only lookup so reset proof does not recursively depend on itself;
generated parents retain exact ownership, inherited edge and acyclic ancestry.
Existing arbitrary foreign-domain clock rejection remains intact. Owned bare
clock inputs may be explicitly bound, including generated domains and two
explicit aliases of one physical input. Existing attributed ports may not be
silently reassigned across unrelated domains; equal frequency is never proof.

Architecture review of this slice: reuse the existing domain index, typed SSA
scope checks and shared digital dependency owner. Reset proof is separate from
ordinary data provenance, not a second assignment engine. Each query bounds
producer depth and retained lineage work; repeated per-operation verification
can still revisit module records, so this is not a linear-time or performance
improvement claim. Closed producer attributes and independent ingress validation
prevent descriptive safety flags from substituting for actual operands.

This is only the native model checkpoint, not target or whole-increment
acceptance. Source construction/bridge capture, executable target lowering and
simulation remain separate work; every F57 checklist item remains open. Historical
results above retain their original scope. Lost unpushed reset results are not
current evidence. CI and full qualification are not run under prototype policy.

Fresh focused validation for the reconstructed native model: **31 reset cases
and 25 DomainModel predecessor cases pass**. Cases include explicit provider and
owned release positives; raw Async without state, forged producer attributes,
polarity changes, wrong clock/edge, reconvergence, cycles, stage/arity bounds and
Sync boundary retarget rejection; generated and explicit bare-clock aliases.
The 12 model/test source files were frozen against SHA256 manifest digest
`ec250e67dfdb9ea84701803794b96cfff7ff1f47d747a8498705034f9cbdb4e4`
before this checkpoint. Reproduction (repository root):

```sh
source .work/tools/env.sh
cmake --build .work/native-build --target nodal-reset-model-unit-tests nodal-domain-model-unit-tests -j3
.work/native-build/core/compiler/test/Unit/nodal-reset-model-unit-tests
.work/native-build/core/compiler/test/Unit/nodal-domain-model-unit-tests
python3 scripts/check_markdown.py
git diff --check
```

Build and focused output are retained under `.work/reset-model-build.log`,
`.work/reset-model-test.log` and `.work/reset-domain-test.log`. Markdown and diff
checks pass. These results do not claim the pending source or target slices pass.

## Reset target lowering checkpoint (2026-10-20)

The native typed reset graph now lowers through the existing transactional
sequential-to-CIRCT owner. Boundary operands remain actual physical ports;
composition normalizes polarity and ORs asserted inputs; each synchronizer
allocates independently symbolized asynchronous-assert release state on its
receiving edge. No initial value is invented. Explicit provider release
assumptions retain actual domain, edge and physical clock in generated output.

Fresh focused native results: **77 CIRCT, 71 sequential, 114 memory, 59 FlatFSM
and 90 crossing cases pass**, in addition to the native model's 31 reset and
25 domain cases. The current selected native total is 467, not a historical
reset-run count. The CIRCT cases include actual CSE/canonicalization with two
independent release chains at both two and 32 stages, preserving distinct
symbols, asynchronous reset and absence of startup presets. The four target/test
files were frozen against manifest SHA256
`b8dfea0a8958c23416f7b320852393d01a300f6854d1fb3ff4f0d96c526230b5`.

```sh
source .work/tools/env.sh
cmake --build .work/native-build --target nodalc nodal-translate nodal-sequential-circt-unit-tests nodal-sequential-model-unit-tests nodal-memory-model-unit-tests nodal-flat-fsm-model-unit-tests nodal-crossing-model-unit-tests -j3
ctest --test-dir .work/native-build -R '^nodal.native.(sequential-circt|sequential-model|memory-model|flat-fsm-model|crossing-model)-unit$' --output-on-failure
python3 scripts/check_markdown.py
git diff --check
```

Focused logs are `.work/reset-circt-tests.log` and
`.work/reset-{sequential,memory,flat-fsm,crossing}-tests.log`; build logs are
`.work/reset-lowering-build.log` and `.work/reset-circt-rebuild.log`. Target
lowering is buildable independently over the preceding native model checkpoint.
The separately pending Scala source slice is not accepted by this checkpoint:
its selected predecessor run has seven unresolved failures, including required
shaped-clock work and a repeated-child definition-identity regression. Its
initial seven end-to-end waveform witnesses pass but are not a substitute for
closing those source failures. All F57 checklist children remain open.

## Reset source checkpoint (reconstructed, 2026-10-20)

Source construction now records actual reset producer inputs and independent
effective domain resets while preserving public declared reset identity. The
bridge independently checks bounded ownership/lineage and exact receiving
clock/edge before emitting typed native producers. The additive
`AsyncExternalRelease` policy retains an explicit provider obligation; raw Async
is not silently upgraded, including domains without state. Pending construction
synchronizers bind recursively to their receiving generated domain.

The internal stimulus-only capture path retains descriptor semantics without
allocating executable reset proof. It preserves the unchanged zero-stage and
implicit-cascade stimulus negatives. Exact declared external boundary inputs may
remain unused in a selected stimulus closure, but arbitrary ports and executable
effects are not thereby admitted. Production emission still requires actual
proof and bindings. Owned bare input Clock ports preserve explicit aliases
without inventing an arbitrary domain owner.

Initial fresh focused results: **13 reset source/bridge cases and all 11 unchanged
DomainStimulus cases pass**. Source/API/bridge/simulation production compilation
passes. Seven actual Scala bridge-IR profiles were emitted successfully. The
source files were then formatted; post-format focused and affected predecessor
checks are recorded separately below when complete. This checkpoint does not
claim whole-F57 completion or blanket predecessor acceptance.

```sh
source .work/tools/env.sh
export MILL_OUTPUT_DIR="$PWD/.work/reset-source-mill"
sh .work/cache/mill/download/1.1.7 --no-daemon core.scala.testkit.test.testOnly nodal.internal.testkit.Increment57ResetSourceTests nodal.internal.testkit.DomainStimulusTests
```

Initial focused log: `.work/reset-source-tests.log`. Architecture review keeps
mutable reset identity in the existing construction session, uses a narrow
bridge verifier at the trust boundary and reuses ordinary typed digital emission.
No duplicate domain registry or fixture-specific production recognizer is added.

The final formatted source checkpoint passes **48/48** selected checks: 13 reset,
11 unchanged DomainStimulus and 24 FlatFSM capture. The latter's existing
comparison helper now normalizes nested reset input/destination paths, while
retaining changed-child inequality checks. The previously reported repeated-child
failure was this incomplete test normalizer, not a production definition-identity
defect. The actual FlatFSM bridge suite separately passes all 11 cases, including
identical-definition reuse and changed guard/metadata distinctions.

Final focused log: `.work/reset-source-checkpoint-final.log`. The broader directly
affected predecessor run executed 130 tests, with 123 passing and seven initial
failures (`.work/reset-source-predecessors.log`); one of those failures is resolved
by the final FlatFSM capture run. Remaining changes include intentional old
unsupported-AASR/raw-Async expectations, one typed-input diagnostic attribution
fix and required shaped-clock support. They are not marked passed here.

The final seven source replays were regenerated after formatting, and **7/7 real
target simulations pass** (`.work/reset-target-final.log` and
`.work/reset-target-final/manifest.json`). Tracked public fixtures and replay are
`Increment57ResetSource`, `Increment57MixedResetSource` and
`Increment57ResetReplay`; the reusable runner is
`tests/scala/reset-release/run_target.py`. It uses current nodalc, pinned firtool
and Icarus without replacing generated designs. Assertions cover both edges and
polarities, unspecified pre-assertion startup, immediate assertion/reassertion,
release latency plus the consumer edge, stopped-parent/live-child operation,
serial 2+3 release stages, distinct mixed-polarity roots and retained provider
clock/edge obligation. These selected digital checks are not physical timing or
metastability qualification.

```sh
source .work/tools/env.sh
CP=$(python3 -c 'import json; print(":".join(x.split(":",3)[3] for x in json.load(open(".work/reset-source-mill/core/scala/testkit/test/runClasspath.json"))["value"]))')
java -cp "$CP" nodal.internal.testkit.Increment57ResetReplay "$PWD/.work/reset-source-ir"
python3 tests/scala/reset-release/run_target.py --input .work/reset-source-ir --out .work/reset-target-final --nodalc .work/native-build/bin/nodalc --firtool .work/circt-toolchain/bin/firtool --iverilog .work/tools/iverilog/bin/iverilog --vvp .work/tools/iverilog/bin/vvp
```

Source files are scalafmt-formatted; Python syntax, Markdown and diff checks pass.
CI is not run. This is a scalar reset source/target checkpoint; required shaped
execution and remaining F57 features stay open.

## Scalar predecessor reconciliation (2026-10-20)

The next four-file source/test correction restores precise typed-input diagnostic
attribution: a valid typed reset expression is not misreported as a missing
physical declaration when the clock is malformed. Existing F56 positives that
exercise asynchronous target behavior now declare the explicit external-provider
contract instead of requesting raw unsafe Async. AASR's former unsupported case
is now an actual binding/state positive. Forged unsupported descriptors and
contradictory reset aliases remain negative with their specific new diagnostics.
The F12 raw-Async/no-state negative and unchanged stimulus negatives are not
converted to positive tests.

Focused result: **44/45 pass** (`.work/reset-predecessor-repair.log`): 20/20
Sequential capture, 13/13 Memory bridge and 11/12 Sequential bridge. The sole
remaining failure is the unchanged projected-clock predecessor, to be resolved
by required shaped execution rather than deleting its positive behavior. Native
replay branches conditional on `NODAL_NODALC` were not configured in this run and
are not counted as executed target validation; the separate seven actual source
waveform witnesses above remain the executed target evidence. All F57 items stay
open. This is a diagnostic/compatibility correction, not shaped completion.

## Fixed shaped-control native checkpoint (2026-10-20)

`PhysicalControlModel` reuses ShapeModel's authoritative static projection proof
to identify an actual local input port plus its scalar element. Domain clock
comparison and reset polarity/release lineage consume this shared identity.
Equivalent reshape/view/slice/index paths alias correctly; distinct elements of
one array do not alias. The existing digital type owner supplies a shared
control-kind query for scalar and shaped input-clock admission, retaining reset
ownership and unrelated foreign-domain rejection.

The transactional target planner admits fixed shaped control inputs through a
bounded packed port ABI and actual scalar extraction; Clock elements alone gain
`seq.to_clock`. General target types remain scalar-only. Unsupported control
outputs, dynamic/unproved projections and non-control shaped computations are
not admitted by this extension.

Fresh selected checks: **53 reset-model, 32 DomainModel and 84 CIRCT cases pass**
(169 total). The 13 native files were frozen against manifest digest
`fb5b83226ccddc579955f0a7aeaa3711e2e844d829616af0967bcfd02b455240`.
Logs are `.work/shaped-control-{reset-model,domain-model,sequential-circt}.log`;
compiled tools and build log are under `.work/native-build` and
`.work/shaped-control-native-build.log`. Reproduce by building/running those
three native test targets as in the scalar checkpoint. Clang-format, Markdown
and diff checks pass. A new test signedness warning remains for the next repair;
no warning-free build is claimed.

This is an intermediate native checkpoint, not shaped reset acceptance. Two
known required corrections remain: per-element Sync declaration ownership must
use the actual owning boundary/domain/binding rather than an aggregate port's
single domain label, and an unused last scalar projection needs valid scope
anchoring rather than rejection. The source integration and three actual shaped
waveform profiles are pending a frozen-source replay. All F57 checklist children
remain open; no CI/full qualification is run.

## Fixed shaped source and per-element Sync checkpoint (2026-10-20)

The source session and bridge now reuse canonical shape coordinates for actual
Clock/Reset projection roots. Equivalent index/view/reshape/slice paths resolve
to the same element; distinct reset elements retain separate polarity and release
lineage. Actual domain clocks are rooted in executable digital capture, while
descriptor-only stimulus capture stays separate. The former projected-clock
predecessor now proves an actual binding and actual register execution.

Both trust boundaries establish a shaped Sync boundary's ownership from its
existing actual Sync domain/binding. Same-element conflicting receiving clock or
edge rejects; distinct elements may use independent domains. No element-domain
registry or new schema is added. The target additionally handles valid unused-last
projections without fabricating a scope anchor or materializing dead extraction.
The prior new signed-comparison warning is corrected.

Final frozen source checks: **134/134 pass across all 10 directly affected
suites** (`.work/shaped-control-final.log`). Mill workers can aggregate more than
one suite in a terminal count: the earlier 130-test/123-pass/seven-failure total
was correct, but the interpretation that only nine suites ran or stimulus was
omitted was incorrect. These current totals sum terminal summaries and verify
all ten test-class starts. Native conditional replay branches not configured by
these Scala suites are not claimed as separate target executions.

Fresh native model/target checks: **59 reset, 32 DomainModel and 85 CIRCT cases
pass**. Repair manifest SHA256 is
`7cb9f1fc36df8849c1908040367cc36a5fad23f6760765c3613e7bede848b7f6`.
Logs: `.work/shaped-control-{reset-model,domain-model,sequential-circt}-final.log`.
The four directly affected native predecessor owners are revalidated separately
below; their earlier scalar results are not automatically relabeled current.

**11/11 actual source-to-target waveforms pass** with frozen compiled source and
fresh replay (`.work/shaped-reset-target-final.log` and corresponding manifest).
The seven scalar profiles remain green. Four added profiles prove nonzero vector
selection, matrix row/index selection, two distinct composed reset elements, and
two Sync elements on independent opposite-edge clocks. They check unselected
clock/reset isolation and edge-only Sync assertion as well as reset/release
latency. An intermediate fixture-source edit overlapped compilation and produced
stale line-based names; frozen recompilation/replay fixed this test setup issue,
without changing production naming or generated designs.

```sh
python3 tests/scala/reset-release/run_target.py --include-shaped --input .work/reset-source-ir --out .work/shaped-reset-target-final --nodalc .work/native-build/bin/nodalc --firtool .work/circt-toolchain/bin/firtool --iverilog .work/tools/iverilog/bin/iverilog --vvp .work/tools/iverilog/bin/vvp
```

Architecture review retains one shape proof owner and one physical-control query;
source/native verify independently. Packed target extraction adds no lane
unrolling. Existing producer/fact budgets and bounded projection traversal apply;
global boundary consistency can revisit records, so no linear-time or speedup
claim is made. Same-clock composition of purely Sync boundaries still needs
policy propagation in the next narrow reset correction. General relationship,
propagation, remaining CDC/gate/mux/waiver/event work stays open. This fixed-shape
checkpoint does not close whole F57, parameter-unresolved target shapes or CI/full
qualification. Python syntax, Markdown and diff checks pass.

Final shared-library predecessor relink/recheck also passes: 71 sequential,
114 memory, 59 FlatFSM and 90 crossing cases, for a **fresh native total of
510/510** with the 176 model/target cases above. Logs are
`.work/shaped-control-predecessor-{build,tests}.log` and
`.work/shaped-control/predecessor-ctest-full.log`. This is the selected affected
native coverage, not an unexecuted repository-wide pass.

## All-Sync composition correction (2026-10-20)

Generated domains now retain Sync assertion policy for recursively all-Sync
reset composition. The session uses an identity-memoized helper bounded to
128 depth and 524,288 work; this only derives assertion policy, leaving actual
receiver clock/edge proof to the existing independent bridge/native verifiers.
Nested same-clock composition succeeds, different-clock composition rejects,
mixed Sync/external composition rejects, and an explicit receiving synchronizer
provides the legal mixed-contract counterpart.

Fresh focused source result: **30/30 pass** (19 reset and all 11 unchanged stimulus
cases), log `.work/sync-combine-final.log`. Four source files were frozen before
replay. **12/12 actual source-to-target waveform profiles pass**,
`.work/sync-combine-target.log` and manifest: the added same-clock Sync composition
witness proves either root asserts the OR only on the receiving edge, with no
unrequested asynchronous assertion or release pipeline. All prior scalar/shaped
waveforms remain passing. Native implementation is unchanged from the preceding
510-case native checkpoint; those tests are not described as rerun for this
source-only policy correction.

The selected scalar/fixed-shape reset execution checkpoint is now coherent with
its stated limits. Required remaining F57 work moves to Gray/pulse and the other
CDC/domain/clock categories; whole-increment acceptance and all F57 checklist
children remain open. No CI, full qualification or physical CDC guarantee is
claimed. Formatting, Python syntax, Markdown and diff checks pass.

## Executable Gray/pulse native scalar checkpoint

The approved [v0.3 contract](../design-gates/NodalClockResetExecution-DG-v0.3.md)
is now implemented in the native crossing/type/provenance owners and scalar
CIRCT target path. This is a scoped checkpoint, not F57 completion. Source
construction work is validated separately; required fixed aggregate Gray target
support and the remaining F57 categories stay open.

The generalized executable crossing model retains one bounded physical dependency
traversal. Pulse classifications survive aliases and cannot be relabeled by level
or Gray crossings; cache identity includes that sampling obligation. Exact finite
bit-pattern ABI admission reuses DigitalTypeModel and canonical enum definitions.
Pulse epoch identity reuses ResetModel with the actual consuming domain binding
as its SSA use anchor, including when bindings appear after a crossing declaration.
No second graph engine or reset registry was added.

Gray payload/history state is uninitialized, including no-zero Enum payloads.
Only real reset clears the seen marker. Pulse uses seven bits, actual returned
acknowledgement of delivered history and an ungated source toggle. Preserved,
source-located immediate clocked target assertions use parenthesized four-state
knownness tests: unknown Gray seen state skips the first sample intentionally;
unknown Pulse readiness before a reset epoch fails rather than passing vacuously.
Pinned firtool exports concurrent SVA unsupported by the selected Icarus, so these
immediate checks preserve execution instead of stripping assertions. Physical
stability/skew and common reset-duration assumptions remain explicit annotations.

Focused current checks:

- Native crossing model **140/140**, including all 90 unchanged level predecessors;
  shared sequential model **71/71**; CIRCT target **102/102**.
- Independent native ingress accepts **17/17** real Scala replay designs, including
  four Pulse variants and Gray scalar, Enum, Vec and Struct admission. Aggregate
  admission is not yet aggregate target lowering.
- Actual Scala replay → nodalc → firtool SystemVerilog → Icarus **41/41** checks:
  21 Gray and 20 Pulse. Both edges/polarities, stages 2/3/32, resetless first sample,
  Bool/Bits/UInt/SInt and no-zero Enum pass. Invalid Gray transitions and Pulse
  busy/width/pre-reset/simultaneous-edge cases fail nonzero with their expected
  runtime assertion markers. Legal Pulse cases also reassert a common reset
  during an outstanding event and check that no ghost pulse survives recovery.
- Target reproduction: `tests/scala/gray-pulse-crossings/run_target.py` with actual
  `Increment57GrayPulseReplay` output. Current logs are under
  `.work/gray-pulse-scalar-final.log`, `.work/gray-pulse-scalar-final/manifest.json`,
  `.work/gray-pulse-model-final.log`,
  `.work/gray-pulse-native-source-admission.log` and `.work/f57-target/`.
- Python syntax, Markdown and diff-whitespace checks pass. The subsequent directly
  affected memory **114/114** and flat-FSM **59/59** reruns also pass, giving
  **486/486** selected native checks across the five suites above. No CI or broad
  qualification was run.

Architecture review: the narrow existing type, crossing, sampling, reset and
target owners remain authoritative. Checked state allocation is proportional to
bounded width and stages; stable physical symbols prevent independent chains
from merging. No new optimization framework is needed. Fixed Vec/Struct target
support is the next required Gray slice; handshake/FIFO, clock structures,
relationship/hierarchy, waivers and restricted low-level events remain open.

### Gray/pulse source and report checkpoint

The frozen public Gray/Pulse signatures now capture actual executable crossings
through construction records, the bridge and deterministic reports. Source finite
storage bounds, sampling/reconvergence and reset-lineage queries reuse the existing
owners. Bridge ingress independently validates closed obligations, actual endpoint
ownership, exact payload ABI and Pulse common physical reset roots. Aliased Pulse
results cannot be reclassified as level or Gray; corresponding same-domain state
consumers retain their existing semantics. Canonical shaped reset elements are
compared rather than textual paths. ResetMlir returns its memoized verified root
facts for the epoch check, without a second lineage engine.

Current source checks pass **61/61** across the final runs: Gray/Pulse 13, level 9,
FSM crossings 5, reset 19, stimulus 11 and reproducibility report 4. The report
inventory's old descriptor fixture was repaired to use legal owned input/output
domains and reset producers; placeholder waiver inventory remains descriptor-only,
not executable proof. The environment-gated external report replay was not run
by that source suite and is not claimed as separate target evidence.

Final public source replay emits **17/17** designs and is byte-identical to the
replay used for the **41/41** actual scalar target checks above. Reproduction uses
`core.scala.testkit.test.runMain nodal.internal.testkit.Increment57GrayPulseReplay`
with an ignored output directory. Current logs are
`.work/gray-pulse-focused-final.log`, `.work/gray-pulse-report-final.log` and
`.work/gray-pulse-replay-final.log`; final IR is under
`.work/gray-pulse-source-final/`. Source formatting, Markdown and whitespace checks
pass. Aggregate target work and remaining F57 categories stay open; no checklist
parent or child is closed by this source checkpoint.

### Fixed whole-aggregate Gray target checkpoint

The required fixed whole-value Vec/Struct target continuation now uses the shared
`inferDigitalBitPatternFixedBits` query. It reuses complete validated storage
footprints and requires singleton bounds for every width and extent, rather than
specializing a parameter default or mistaking its maximum for an exact size.
Whole payload ports and state use flat packed integers with preserved canonical
source ABI and recursive row-major/declared-field LSB-first layout metadata.
Exported SystemVerilog retains the same complete layout comments. Native checks
assert nested Vec strides and Struct field offsets, not only total width.
Unsupported projections and storage operations still reject atomically; this is
not a claim of general aggregate-operation target lowering.

Fresh focused native checks pass **362/362**: DigitalTypeModel **114/114**,
crossing model **140/140**, CIRCT target **108/108**. The full actual Scala →
CIRCT → SystemVerilog → Icarus matrix passes **45/45**, adding Vec and Struct legal
transport and executed invalid-transition failures to the prior scalar matrix.
Input/output complete packed ABI comments are checked after export. Source files
remain the frozen final replay from the preceding checkpoint; no fabricated
target design is substituted. Logs are `.work/gray-pulse-fixed-type-tests.log`,
`.work/gray-pulse-aggregate-crossing-tests.log`, `.work/f57-target/` and
`.work/gray-pulse-aggregate-final.log`, with per-case hashes in
`.work/gray-pulse-aggregate-final/manifest.json`. Formatting, Python syntax,
Markdown and whitespace checks pass. No CI or broad qualification was run.

Architecture review: exact fixedness belongs to the shared type owner, packing
is bounded and recursive without lane expansion, and the existing target planner
owns transactionality. Native/source semantic shape and exact type equivalence
are unchanged. Fixed whole-aggregate Gray no longer remains an unimplemented
target promise. F57 overall remains open. The next coherent snapshot handshake
slice is frozen in [v0.4](../design-gates/NodalClockResetExecution-DG-v0.4.md),
retaining v0.1's automatic coalescing semantics and genuine returned acknowledgement.

## Coherent snapshot handshake source checkpoint

The [v0.4 handshake contract](../design-gates/NodalClockResetExecution-DG-v0.4.md)
now captures through the unchanged public Expr API, construction records, bridge
and existing report transport. This source checkpoint does not claim native or
target handshake execution; those owners are being implemented separately.
Source capture and independent bridge ingress reuse the existing finite payload,
sampling/reconvergence and physical reset-root proof. The shared Pulse reset
endpoint rule now also covers handshake without copying a lineage engine.
Closed obligations preserve automatic latest-snapshot coalescing, bundled-data
settling and common reset epoch. Pulse results cannot be silently relabeled as
snapshot payload. Fixed scalar/Vec/Struct/Enum shapes preserve exact ABI.

The six-suite runner reports **60 passing cases**: handshake 10, Gray/Pulse 13,
level 9, FSM crossing 5, reset 19 and report 4. Of these, **59 source/bridge test
bodies executed**; one report native-HDL parity body was environment-gated and
not run because its native tool variables were unset. No target evidence is
claimed from that gated body. All ten new handshake tests executed. Raw Async
rejection is tested at its established bridge validation boundary, not moved or
weakened merely to satisfy a construction-only test.

Frozen public replay emits **8/8** actual MLIR designs: both edge/polarity pairs,
whole Vec, Struct, no-zero Enum and a valid shared-clock Sync-reset witness.
Actual emitted payload input is `sampled`. Logs are
`.work/handshake-focused-final.log`, `.work/handshake-replay.log` and
`.work/handshake-format-check.log`; replay IR is `.work/handshake-source/`.
Source formatting, Markdown and diff checks pass. Native/target handshake checks
and FIFO/remaining F57 work stay open; no CI or broad qualification was run.

The preceding fixed Gray aggregate work also retained successful executions of
the unchanged sequential 71, memory 114 and flat-FSM 59 predecessor cases, in
addition to its freshly rebuilt type/crossing/target 362 cases. These 606 focused
native executions are recorded separately from the pending handshake backend.

### Executable snapshot handshake native and target checkpoint

The native/target continuation now implements v0.4's automatic coherent latest
snapshot with `2W + 6` state bits, uninitialized payloads, real returned capture
acknowledgement and independent physical synchronizer state. All six protocol
control bits participate in their local knownness checks. The existing crossing
owner factors common-reset proof for Pulse and handshake; the existing sampling
owner prevents Pulse relabeling. There is no separate reset or dependency graph.

A valid shared-clock Sync source witness exposed an existing reset-proof use
anchor bug: symbol-resolved clocks were inspected relative to a reset boundary
that does not itself consume them. PhysicalControlModel now accepts independent
actual-use anchors, and reset lineage retains its actual clock consumer. Proof
still compares exact physical clock and edge; late binding/read aliases pass,
while genuinely different clocks and edges reject. No source reordering or
physical-identity relaxation was used to hide the issue.

Current native focused checks pass **495/495**: crossing 198, reset 65, domain 32,
CIRCT target 129 and sequential 71. All **8/8** actual Scala handshake replay
files independently pass native ingress. The actual source → CIRCT → SystemVerilog
→ Icarus handshake matrix passes **44/44**:

- Both clock edges and reset polarities, whole Vec/Struct, no-zero Enum and valid
  shared-clock Sync reset execute coherent held snapshots and reset recovery.
- Directed busy source updates coalesce rather than being falsely promised as
  lossless events. Stopped/resumed and simultaneous clocks preserve the two-stage
  request and returned-ack timing; destination retains its last delivered value.
- Unestablished and raw-unknown reset epochs fail at each local clock with the
  expected runtime assertion. Six separate post-reset mutations force each real
  control bit unknown and require the corresponding local assertion to fail.
- Affected actual target predecessors rerun on current nodalc: Gray/Pulse
  **45/45** and scalar/shaped reset **12/12**, including shaped Sync composition.

The target harness is `tests/scala/snapshot-handshakes/run_target.py`, using only
actual `Increment57HandshakeReplay` output. Logs are
`.work/handshake-target-actual.log`, `.work/handshake-target-actual/manifest.json`,
`.work/handshake-gray-pulse-predecessor.log`,
`.work/handshake-reset-predecessor.log` and the focused native logs under `.work`.
Source-location/obligation comments and packed aggregate ABI survive export.
Formatting, Python syntax, Markdown and diff checks pass. No CI or broad
qualification was run. Known-state checks remain startup protection, not a
universal dynamic detector of unilateral reset epochs; physical bundled-data
settling and common reset duration remain explicit assumptions.

Architecture review: the implementation reuses bounded exact type, reset,
physical-control and sampling owners; generated state grows linearly with
payload width, and existing stable symbols protect independent instances.
The asynchronous FIFO with genuine ready/valid ownership is next. Remaining
clock structures, relationships/hierarchy, waivers and low-level events keep
F57 open; this checkpoint does not close any parent checklist prematurely.

### Elastic FIFO native model checkpoint

The v0.5 gate now has a closed native `async_fifo` schema and model with three
sampled inputs and three coherent result roles. Exact power-of-two depth, finite
payload ABI, common reset roots, pointer and bundled-data obligations, and the
checked `D*W+8P+2(W+1)` state budget are verified before allocation. The existing
sampling graph carries per-result domain roles and all sampled roots, including
forward-ready chains; independent FIFO identities remain distinct. Domainless
constants are neutral, but unannotated input ports are not clock evidence.

Current focused native evidence: CrossingModel **266/266**, including 68 new FIFO
cases and 198 predecessors (`.work/fifo-crossing-tests.log`). The model and graph
checkpoint is independently tested; source API, target lowering and actual FIFO
waveforms remain separate pending checkpoints. No CI or broad qualification was
run, and no coarse F57 checklist is closed by this partial checkpoint.

### Elastic FIFO target structure checkpoint

CIRCT lowering now emits the bounded uninitialized payload register bank,
registered binary/Gray pointers, two-stage pointer synchronizers in both
directions, exact-capacity full/empty logic and both stall monitors. Pointer
controls and monitor flags reset; bank and payload history do not acquire an
invented initialized value. Ready/valid are locally reset-masked. Export retains
source-located fatal checks for both local epoch/control and stall obligations.

Focused native checks pass **730/730**: CrossingModel 266, SequentialCIRCT 145,
SequentialModel 71, ResetModel 65, DomainGraph 32 and InterfaceModel 151. The
exported native fixture passes firtool verification with randomization disabled.
These checks cover target structure, not executed Scala FIFO waveform behavior;
the maintained actual-target harness is still pending. Source replay fixtures
are being regenerated after final formatting before runtime evidence is taken.

Architecture review: one shared model owns schema and role mapping; all sampled
roots retain coherent identity without combinational feedback. Packed bank and
read selection grow with bounded depth, using a logarithmic read mux rather than
bitwise graph expansion. No CI, broad qualification or F57 closure is claimed.

### Elastic FIFO actual target checkpoint

The maintained `tests/scala/async-fifos/run_target.py` and profile helper consume
actual frozen Scala replay output, then run nodalc, CIRCT and Icarus without
rewriting emitted HDL. Current waveform matrix passes **84/84** across all 14
profiles: both clock edges and reset polarities, exact depths 2/4/8/256, packed
Vec/Struct, nonzero-encoded Enum, Sync on one actual physical clock, unattached
sink, continuous-valid source and a three-clock two-FIFO chain.

A transfer scoreboard checks only accepted valid-and-ready transactions. Exact
capacity, wrap, stopped and simultaneous edges, pointer visibility/return delays,
backpressure, invalid offers and coordinated queue flush are witnessed. The
chain admits exactly eight blocked entries and then preserves order through
repeated backpressured wraps. Unattached ready remains false despite the unused
external input; the continuously offered source obeys the same stall contract.

Actual negative simulations fail nonzero with the specific expected endpoint
obligation: source payload/valid stall violations, missing/unknown reset epochs,
eight forced-unknown real pointer controls, and destination payload/valid stall
mutations. Fallback testbench failures do not count. Export retains exact packed
ABI and physical timing obligations. The supported firtool option
`disallowLocalVariables` accommodates Icarus syntax, with randomization disabled.

Affected actual target predecessors on current nodalc pass: snapshot handshake
**44/44**, Gray/Pulse **45/45**, scalar/shaped reset **12/12**. Logs/manifests are
under `.work/fifo-target-actual`, `.work/fifo-handshake-predecessor`,
`.work/fifo-gray-pulse-predecessor` and `.work/fifo-reset-predecessor` (with adjacent
`.log` files). These are executed waveform checks, not a physical CDC proof.
Source/API final focused checks and publication remain a separate pending slice;
this harness checkpoint does not close F57. No CI or broad qualification ran.

### Elastic FIFO source/API checkpoint

Stream now owns actual payload, valid and forward-ready identities. Explicit
sink acceptance or FIFO attachment owns ready exactly once; unconsumed ready is
false. Normal physical assignment finalization supplies that default and rejects
duplicate ownership. Existing pipeline placeholders explicitly reject carriers
rather than silently dropping their valid/ready semantics. FIFO records retain
all three sampled inputs and coherent result roles; exact bounds/state budget
are checked independently in source and bridge.

Source focused checks pass **58/58**: FIFO 12, snapshot handshake 10, Gray/Pulse
13, level 9 and Interface 14 (configured actual native executable). Frozen actual
public replays provide all 14 waveform profiles above. An additional actual
anchored ready-feedback source replay passes native admission. Source sampling
uses the existing bounded iterative owner with a separate inference cache mode;
anchored feedback is legal while conflicting and unanchored feedback reject.
All 12 source files pass formatting and scoped diff checks.

A subsequent native trust-boundary audit confirmed malformed ready-only feedback
with constant payload could self-certify the source through its own ready-domain
attribute. That native-only attribution repair is active, with source rejection
already tested; this source checkpoint does not claim FIFO closure while the
known malformed-native acceptance remains. Evidence is retained in
`.work/fifo-source-tests.log`, `.work/fifo-feedback-replay` and the frozen source
manifest. No CI or broad qualification was run.

### FIFO independent source-attribution repair

Native strict sampling now traverses a source-ready result's retained payload
and valid roots to establish independent source evidence, while retaining that
result's actual source-domain ownership cut. The bounded visited set terminates
own and mutual feedback; ready-domain metadata cannot certify its own ancestry.
Anchored feedback and one-anchor mutual feedback remain legal, unanchored cycles
and conflicting sources reject. This repairs the preceding malformed-native
acceptance without weakening role domains or reconvergence.

Focused native checks pass **737/737**: crossing 273, CIRCT 145, sequential 71,
reset 65, domain 32 and Interface 151. All **15 actual Scala admissions** pass;
the actual unanchored mutation now rejects with NODAL-CDC-001. The seven new
cases cover own/mutual feedback and source evidence. Logs are
`.work/fifo-feedback-*-tests.log`, `.work/fifo-feedback-source-admission.log` and
`.work/fifo-feedback-unanchored-rejection.log`.

One separate integration issue remains active: target materialization assumes
single-result operands except FSM and rejects a Boolean expression combining
FIFO payload/valid results. Native admission is correct; the shared role-aware
value identity/materialization repair is pending. No FIFO final closure, CI or
broad qualification is claimed by this source-proof checkpoint.

### Scoped elastic FIFO acceptance and result-identity repair

The shared value-identity owner now derives FIFO result identities from the
verified canonical CrossingModel roles. Payload retains its authored identity
for existing shape origins; destination-valid and source-ready have distinct
role-qualified identities. Materialization reserves every supported finite
result identity and no longer treats FSM as the sole multi-result owner.
Malformed contracts, stale role substitution and identity collisions reject.
This resolves the preceding FIFO Boolean-composition lowering failure.

Current final FIFO native evidence is **783/783**: materialization 46 plus the
737 checks above. All **15 actual Scala admissions** pass. Actual anchored
ready-feedback IR now lowers and exports through firtool with all four fatal
checks retained. On that frozen repaired compiler, the maintained full focused
FIFO waveform matrix again passes **84/84** across all 14 profiles. Source/API
focused evidence remains **58/58** from its frozen checkpoint. Earlier source-
attribution and result-materialization issues are resolved by these checkpoints.

Reproduction evidence: `.work/fifo-materialization-*-tests.log`,
`.work/fifo-materialization-source-admission.log`,
`.work/fifo-feedback-target/fifo_feedback.sv`, and
`.work/fifo-final-target/manifest.json` with `.work/fifo-final-target.log`.
The retained compiler `.work/fifo-materialization-checkpoint/nodalc` has SHA-256
`ca33f8f0a37b4d4ea158649f8b143c9c2421b1d5ce2133214189ba5559d6683c`; this isolates
FIFO evidence from subsequent in-flight clock implementation. No later clock
execution is implied by that executable.

Architecture review: finite result-role identity belongs to the existing shared
value/materialization owner, not a target-only exception; ready ownership uses
ordinary assignments; source inference and native sampling reuse bounded graph
traversals. Exact capacity and state cost are charged before expansion. The
selected FIFO execution scope is accepted after focused source/native/target
checks. Physical pointer skew, data settling, reset duration and protocol
assumptions remain explicit. Gates/muxes and the remaining F57 relationship,
hierarchy, reconvergence, waiver and event work keep all coarse F57 items open.
No CI or broad qualification was run.

### Owned gate/mux source and bridge checkpoint

Public ClockGate and ClockMux.glitchless now capture actual owned clock producers
under DG v0.6. Gate controls retain enable/test operands and inactive-phase
obligations. Mux captures both actual parents, the selector, canonical common
assertion roots and serialized switching obligations. Derived domains retain
independent output-clock release, never a fabricated parent release proof.
Shared clock identity is consumed by reset verification; missing/mismatched roots,
foreign controls, invalid callback construction and forged bridge authority
reject. External Bool controls reuse existing canonical `domain=@none` boundary
semantics without inventing source ownership or widening port admission.

The frozen source/bridge checkpoint passes **43/43** focused groups: owned-clock
13, reset 19 and descriptor DomainStimulus 11. All 14 files pass formatting and
scoped diff checks. Actual public Scala replay emits **12 profiles**, covering
both gate/mux edges and polarities, resetless gates, nested gates/mux and shaped
clock inputs. Fixture lines are frozen before replay. Evidence is in
`.work/owned-clock-tests3.log`, `.work/owned-clock-source-replay.log`,
`.work/owned-clock-source-replay` and the source checkpoint hash manifest.

This is source/bridge acceptance only. Additional source predecessors, native
clock proof and actual target waveform integration remain pending. First target
inspection found CIRCT's default clock-gate external cell requires a production
implementation; a handwritten test substitute will not count. No gate/mux
closure, CI or broad qualification is claimed here. All coarse F57 items remain
open while the selected clock implementation and remaining required slices run.

### Owned clock native proof checkpoint

ClockModel now proves either a physical input/element or an exact owned derived
producer, without merging independent clock structures. Domain and reset proof
consume that identity while physical reset-root/element identity remains owned
by the existing physical-control model. Closed gate/mux producers retain actual
parents, controls, reset assertion, receiving domain and obligations. Alternate
mux-clock bindings cannot lose their common assertion/reset-release protection.
Existing sampling/source classification checks control domains, external @none
boundaries and Pulse misuse; reset-asserted Bool results retain actual domain
provenance rather than masquerading as constants.

The native model checkpoint passes **637/637** focused tests: new ClockModel 51,
domain 32, reset 65, sequential 71, crossing 273 and existing CIRCT 145. Exact
model files, formatting and diff checks are frozen separately from target
lowering. Logs are `.work/owned-clock-final-*-model.log` and
`.work/owned-clock-final-sequential-circt.log` (the exact filenames are listed in
the checkpoint log inventory). Target structure and waveform checks are separate
pending acceptance; this checkpoint alone does not claim target execution.

Architecture review: clock parent/binding traversal is bounded and memoized per
query; root-set comparison is O(R log R); the existing dependency graph owns
control evidence. No copied transitive provenance sets or whole-pipeline
linearity claim is introduced. Per-operation verifiers may still rebuild local
domain/dependency views. Model/state limits and causal checks do not close the
remaining F57 representative-scale requirement. No CI or broad qualification ran.

### Owned clock target and waveform checkpoint

The target emits distinct gate/mux clocks, independent release chains and the
bounded serialized mux controller. Gate state is a genuinely uninitialized
inactive-phase latch; mux sampler/controller/request/enable/acknowledgement
state uses real common assertion and independent local release. Generated checks
retain control knownness, reset-epoch and source-located obligations. Parent
clocks, active edges, named derived clocks and actual branch enables survive
export. There is no ordinary combinational mux masquerading as glitchless.

Focused target structure passes **171/171** (26 new clock cases plus 145 existing
CIRCT cases). Final maintained actual Scala waveform evidence passes **36/36**
across 12 profiles: 12 legal and 24 genuine emitted-assertion failures after
verified injection points. Both edges/polarities, resetless gate startup, shaped
parents, nested gates/muxes, active/inactive-phase control changes, stalled
controller/branch clocks and selection reversal during WAIT_ON are exercised.
Every mux legal witness records 47/30 parent pulses and one protected reset-time
cut. No-overlap/full-pulse monitors include same-timestamp glitches; the sole mux
truncation exclusion is known coordinated reset assertion, never normal handover.

The production export pipeline must run official CIRCT `lower-seq-to-sv` with
register randomization disabled before firtool. This materializes the real gate
latch instead of firtool's default external EICG wrapper; neither a handwritten
replacement cell nor edited emitted HDL is used. The exact commands are retained
in `tests/scala/owned-clocks/README.md` and the harness. A resetless gate witness
explicitly establishes an inactive input phase rather than relying on time-zero
Verilog scheduling to initialize an unspecified latch.

Final evidence: `.work/owned-clock-target-final/manifest.json` and per-command
logs/hash files. The immutable compiler `.work/owned-clock-target-checkpoint/nodalc`
has SHA-256
`ec02885b173375cbd010718bf884d2199f1bc0040f4542bcd96e4c3b3e7650c4`.
Model logs are exactly `.work/owned-clock-final-clock-model.log`,
`-domain-model.log`, `-reset-model.log`, `-sequential-model.log`,
`-crossing-model.log` and `-sequential-circt.log` under that same prefix.

Two additional source predecessor repairs remain pending: a stale descriptor
expectation must assert the now-supported shaped binding, and FSM expansion
revealed an effective reset producer emitted after its consuming FSM. The bridge
must fix ordering, not relax native SSA ownership. These prevent final selected
clock acceptance until resolved, despite the passing target matrix. No CI or
broad qualification ran; all coarse F57 items remain open.

### Owned clock affected predecessor repair

The bridge now materializes the exact effective reset producer before an FSM or
enum action that can consume it during normalization. Previously only the raw
reset input was ensured early, leaving reset_boundary after the FSM and causing
a legitimate forward-SSA rejection when reset_asserted was synthesized. The fix
preserves exact reset identity and binding operands; native lexical/scope checks
are unchanged. The regression asserts producer dominance and actual normalization
success, not merely text presence. Shaped-binding tests now check the actual
supported binding and the causal missing-binding reset ownership diagnostic.

The three-file repair passes its focused **26/26** bridge tests. Combined current
source/predecessor selection passes **164/164**: 43 clock/reset/descriptor groups
plus 121 directly affected predecessor groups, with configured actual native
execution. Logs: `.work/owned-clock-predecessor-repair2.log` and
`.work/owned-clock-all-focused-repaired.log`. Actual source and normalized FSM
witnesses are retained under `.work/owned-clock-predecessor-repair-replay`.
The effective reset now precedes both flat_fsm and enum_switch.

Both previously reported predecessor failures are resolved. Final replay
regeneration comparison and target predecessor checks remain to be recorded
before final selected clock acceptance. The already-passing 637 native model,
171 target structure and 36 actual clock waveform checks retain their scoped
meaning. No CI or broad qualification was run; remaining F57 slices stay open.

### Scoped owned clock acceptance

After the bridge repair, all **12 actual clock replay profiles regenerate
byte-identically** to the inputs of the final **36/36** waveform matrix. Combined
source/predecessor checks pass **164/164**. Native evidence covers **663 distinct
focused checks**: 51 ClockModel, 26 ClockCIRCT, 32 domain, 65 reset, 71 sequential,
273 crossing and 145 existing CIRCT. The earlier model637 and target171 counts
overlap in the existing145 CIRCT checks; they are not additive.

On the immutable final clock compiler, affected actual target predecessors also
pass: FIFO **84/84**, snapshot handshake **44/44**, Gray/Pulse **45/45** and
scalar/shaped reset **12/12**. Logs/manifests are under
`.work/owned-clock-{fifo,handshake,gray-pulse,reset}-predecessor`; regenerated
source evidence is `.work/owned-clock-source-replay-final` and its adjacent log.
Both reported source predecessor issues are fixed without weakening native
scope/reset checks. The selected gate/mux execution scope is accepted.

Physical setup/hold and reset-duration assumptions, resetless gate startup and
the protected mux reset-assertion exception remain exactly as DG v0.6 states.
This does not certify physical timing or close the remaining relationship,
hierarchy, observed-output reconvergence, waiver, event, scale and final F57
integration work. All coarse F57 items remain open. No CI or broad qualification
was run. DG v0.7 selects the next bounded executable relationship-proof slice.

### Executable relationship native checkpoint

The closed clock_relationship operation carries actual Clock operands, exact
endpoint bindings, typed fields, proof classification and explicit obligations.
DomainModel owns a cached exact relationship index, distinct from physical clock
identity. Only validated Same permits ordinary direct sampling; explicit CDC
source and FIFO/Stream protocol endpoint ownership remain exact. Gate controls
may use validated Same permission without changing their actual attribution.
Reset receiving-clock identity and independent crossing identities are unchanged.

Bounded exact rational constraints retain canonical decimal phase, signed/wrapped
phase, known frequency consistency, physical aliases, alternative paths and
contradiction checks. Intermediate arithmetic is limited to 4096 bits before
oversized multiplication/allocation. Mode exclusion is independent of nominal
Ratio/Synchronous timing; Same or physical identity still contradict exclusion.
Source-located target comments retain assumptions and both actual clocks, not a
runtime claim that external electrical streams were certified. Relationships
invent no clock generator or state.

Focused native checks pass **789/789**: relationship 107, domain 32, clock 51, reset 65,
crossing 273, sequential 71, sequential-CIRCT 145 and clock-CIRCT 45. The latter has
26 owned-clock predecessors and 19 relationship target checks. Logs are
`.work/relationship-final-<suite>.log`; exact source hashes are in
`.work/relationship-native-checkpoint/source.sha256`. The immutable target compiler
is `.work/relationship-native-checkpoint/nodalc`, SHA-256
`f6f6151e99d3f634f0940f28dd7a3f56b5d3df311bf7d1f47e35ded3c606b089`.

Architecture review: one DomainModel permission/consistency index uses bounded
adjacency/spanning constraints and separate Same indexing. Exact identity APIs
and shared sampling dependency traversal are preserved; no pairwise closure or
parallel CDC engine is introduced. Clock-only producer resolution remains below
relationship permission, avoiding recursive proof when validating Same gate
controls. Source checks and actual Scala waveform acceptance remain pending at
this checkpoint. DG v0.8 separately records the required concrete hierarchy proof
and formal requirement-capability seam. All coarse F57 items remain open; no CI
or broad qualification ran.

### Relationship source and actual flat target checkpoint

ConstructionSession now uses the shared bounded relationship permission index for
ordinary sampling and Same gate controls. Physical/derived ClockMlir identity,
ResetMlir receiving-clock proof and explicit CDC/protocol endpoint identity remain
separate. Immutable relationship snapshots retain actual operands, typed fields,
proof classification and obligations; the bridge independently resolves those
operands. The shared clock identity owner also rejects Reset/Bool values forged
as Clock before bridge emission.

Focused source checks pass **75/75**: relationship 32, owned-clock 13, reset 19 and
DomainStimulus 11. Log: `.work/relationship-source-tests3.log`. The exact 11-file
source manifest is `.work/relationship-source-checkpoint.sha256`; final replay
success is `.work/relationship-source-replay4.log` with 12 actual source IR profiles
under `.work/relationship-source-replay`.

Maintained `tests/scala/clock-relationships/run_target.py` passes all **7 legal
Same waveform profiles** and **5 category target/comment checks**, using the
immutable relationship compiler recorded above. Both edges/reset polarities,
structural identity, distinct external inputs, transitive Same and shaped inputs
retain independent clock/reset/state owners. Matching active edges coexist with
different inactive duty phases. Each waveform observes 31 active edges,22 operating
samples and 2 reset assertions; nonshaped mixed/nested expressions produce 11 high
and 11 low samples rather than a vacuous constant conjunction.

Actual generated SystemVerilog, VCDs, command logs, source/tool/target hashes and
manifest are under `.work/relationship-target`. No emitted HDL was rewritten and
no substitute DUT was used. Category comments preserve exact typed assumptions;
these checks do not certify the external Same/timing/exclusion assumptions at
runtime. No relationship hardware or state is invented.

Direct target predecessors pass separately on the same immutable compiler:
owned clocks **36/36** and scalar/shaped reset release **12/12**, with logs and
manifests under `.work/relationship-{owned-clock,reset}-predecessor`. Repeated
relationship replay is byte-identical. Source predecessor checks found two stale
Sync-mode handshake/FIFO fixtures claiming 100 MHz and 20 MHz for the same physical
clock; exact alias consistency correctly rejects those contradictory claims.
Their fixture repair and rerun remain pending, without weakening the verifier.
Hierarchy, observed-output reconvergence, waivers, low-level events, representative
scale and final integration remain required F57 work. No coarse checklist closes
from this checkpoint, and no CI or broad qualification ran.

### Scoped relationship acceptance

The two stale Sync-mode handshake/FIFO fixtures now declare 100 MHz consistently
for their shared physical clock. Both retain an explicit contradictory-frequency
negative, and no production validator was weakened. The repaired affected source
selection passes **156/156**, giving **231/231 distinct source groups** with the
previous 75. Log: `.work/relationship-source-predecessors2.log`; the four-file
repair manifest is `.work/relationship-predecessor-checkpoint.sha256`.

The final twelve relationship replays are byte-identical on regeneration. The
corrected actual handshake Sync profile passes **3/3** legal/epoch/unknown-reset
cases, and FIFO Sync passes **1/1**, on the immutable relationship compiler.
Manifests and command logs are under `.work/relationship-handshake-sync-target`
and `.work/relationship-fifo-sync-target`. Existing maintained runners gain only
an optional repeated profile selector, retaining default matrices, stimuli and
assertions while allowing these bounded predecessor runs.

Selected relationship execution is accepted with **789 native**, **231 source**,
**7 actual Same waveforms plus 5 target category checks**, and the separately
reported **36 clock / 12 reset / 3 handshake Sync / 1 FIFO Sync** target predecessors.
This is scoped prototype evidence, not physical timing or external-contract
certification. Known Same, exact physical/reset identity, protocol endpoint
ownership and typed category limitations remain those of DG v0.7. Required
hierarchy, output/Interface reconvergence, waivers, events, scale and final F57
integration remain open. No CI or broad qualification ran.

### Concrete hierarchy source and bridge checkpoint

The source instance boundary now carries parent-owned typed digital port proxies,
exact local requirement identities, actual parent capability bindings and explicit
binding origins. Default/inherited/named selection uses bounded iterative
resolution without replacing the child's local requirement with a parent path.
Existing assignment ownership handles single-driver inputs and readonly outputs;
raw foreign child handles remain invalid. Shared source dependency proof follows
actual input/output drivers and retains separate crossing occurrences.

The bridge emits child-local formal Clock/Reset projections, exact executable
instance-domain bindings and typed digital instance ports under DG v0.8. It does
not copy parent SSA into child scope or claim that descriptor dictionaries alone
certify clock/reset ownership. Reused definitions retain generic requirement
state, not the first occurrence's guessed edge/reset policy. Existing conservative
terminal operations remain separate.

Focused source/bridge checks pass **17/17**: eight source and nine bridge groups,
including required endpoint annotations, ownership/driver negatives, inherited
and named bindings and cross-instance crossing provenance. Log:
`.work/hierarchy-source-tests4.log`. Eight actual source IR witnesses are retained
under `.work/hierarchy-source-replay`, generated successfully by the maintained
Increment57HierarchyReplay entry point; its log is
`.work/hierarchy-source-replay.log`. Exact twelve-file hashes are recorded in
`.work/hierarchy-source-checkpoint.sha256`.

This bounded source/bridge checkpoint does not claim completed native contextual
admission, predecessor acceptance or hierarchy HDL. The active native owner must
independently discharge every reachable occurrence, and unsupported hierarchy
target export must reject transactionally rather than silently flatten or omit
behavior. Those checks and directly affected source predecessors remain pending.
All coarse F57 items remain open; no CI or broad qualification ran.

### Hierarchy source identity and predecessor repair

Source sampling now retains the authored local requirement identity separately
from the concrete resolved domain, including multiple requirements bound to one
actual domain. Physical aliases remain equivalent for actual source attribution;
there is no first-match reconstruction of a local endpoint. Finish-time UInt
checks use the actual declaration owner rather than an inactive construction
scope. A predecessor fixture now explicitly truncates its widened UInt addition,
without weakening exact assignment typing; hierarchy snapshots distinguish local
requirements from resolved parent bindings.

The frozen eight-file follow-up passes **145/145** focused source checks in
`.work/hierarchy-source-tests5.log`, including hierarchy negatives, exact alias
transport, ConstructionKernel and selected relationship/reset/owned-clock and
crossing predecessors. Separately, the previously run **84** analog/bridge and
sequential-capture predecessor cases pass; these selections are not asserted to
be disjoint. Exact follow-up hashes are in
`.work/hierarchy-source-followup.sha256`.

Representative construction checks exercise 48 typed siblings (49 modules),
10 nested modules, and rejection at the declared 129-level bound. Observed local
snapshot/bridge times were approximately 1120/395 ms and 126/69 ms respectively;
these are bounded examples, not a general performance claim. The ninth actual
alias witness and final native contextual checkpoint remain pending. Independent
unused-output/Interface observation coverage is a separate required follow-on;
this source checkpoint does not claim it. No CI or broad qualification ran.

### Mandatory native concrete hierarchy checkpoint

DG v0.8 contextual admission now follows exact typed instance bindings, immediate
child digital ports and formal capability projections. The immutable occurrence
model references original IR, resolves forwarded physical/reset identities and
instantiates the existing dependency/sampling proof without cloning IR or
manufacturing local binding operations. Reused definitions retain distinct state
and crossing occurrences. Structural ownership, instance-target cycles and finite
bounds cover every definition; actual execution obligations cover the explicitly
selected root. Unused well-formed templates are not claimed executed. Legacy flat
descriptor units remain supported, while missing/conflicting contextual binding
authority rejects. Mandatory module admission does not depend on an optional pass
or a serialized verification marker.

The frozen 24-file native slice passes **47 distinct hierarchy-data cases plus
16 repeat-context runs**, **27 contextual clock/reset cases**, and **1115 direct
predecessor cases across 12 suites**. The semantic-pipeline executable also
passes. Exact suite inventory and logs are in
`.work/hierarchy-native/frozen-test-summary.log` and adjacent `frozen-*.log`.
The immutable compiler is `.work/hierarchy-native-checkpoint/nodalc`, SHA256
`6ee7b15f58124eb0957632112d4b46b9f0ba33c09b39a6c0e8dae293a99c0d8d`;
its exact source hashes are in that directory's `manifest.json`.

Actual Scala-to-native replay passes **25/25** checks: nine default semantic-gate
admissions, nine explicit unsupported hierarchy target rejections, and seven
causal mutations covering actual capability operands, binding authority, forged
drivers and cross-instance reconvergence. In-memory tests verify both target
lowering and sequential normalization leave rejected IR unchanged. Results are
in `.work/hierarchy-native/source-final/results.json`. No substitute hierarchy
HDL, implicit flattening or F58 completion is claimed.

Binding-mutation diagnostics retain Scala source locations. Two data-flow mutation
diagnostics currently originate from bridge assignments with unknown locations;
source transport repair remains open rather than claiming complete source-mapped
causal diagnostics. A default-initialized child projection check, standalone
output/Interface observation integration, waivers, events and final F57 integration
also remain open. These are focused prototype checks, not exhaustive qualification;
no CI ran and no coarse F57 checklist closes from this checkpoint.

### Default initialized child reset check

The previously noted projection question is resolved without a production change.
An initialized child register outside every lexical domain passes actual
source-to-bridge-to-native admission with each default, named and inherited
binding. All three resetless variants reject. The unused formal Reset projection
is absent in these witnesses because generic initialized state resolves the
reset-bearing actual instance binding directly; absence is not a reset-proof
bypass. The isolated suite passes **3/3**, with all three positive native replays
under `.work/hierarchy-default-reset/replay` and adjacent `tests1.log`/`replay.log`.
Assignment source-location and observation follow-ons remain open.

### Native observed-output and Interface reconvergence

The canonical Interface dependency owner now supplies coherent observation groups:
one payload/valid forward group per protocol member, separate ready and unrelated
member/endpoint groups, and canonical resolved components retaining every actual
data/enable dependency. Aliases do not invent chains, undriven boundaries do not
invent observations, and no Interface domain annotation authorizes sampling.
The assignment graph preserves group identity and occurrence while reusing the
existing sampler. Both flat and hierarchical production admission observe actual
written ordinary outputs, even without registers or other explicit samples.
Unrelated outputs remain separate; selectors and actual driver cones stay causal.

The frozen seven-file native slice passes **25/25** new owner/production admission
cases, plus Interface **151/151**, crossing **273/273**, hierarchy **47 distinct
plus 16 repeat runs**, contextual controls **27/27**, sequential **71/71**,
assignment guard **34/34**, and storage **119/119** predecessors. New cases include
otherwise-unused combined outputs, reused-chain aliases, separate outputs,
selector/data reconvergence, protocol groups and resolved data/enable causality.
No duplicate CDC checker or physical-bit expansion was added; bounded canonical
grouping reuses the existing Interface maps and shared dependency engine.

Exact source hashes are `.work/interface-observations-checkpoint/native7.sha256`;
logs are under `.work/interface-observations-held`. The immutable compiler is
`.work/interface-observations-checkpoint/nodalc`, SHA256
`7564e190ee98259db207d8b2139c29a4f1db2476906e455cd97fb5b4df58012e`.
Source admission, actual Scala replay and assignment-location repair remain
pending in their separate checkpoint. Waivers, events and final F57 integration
remain required; no CI, broad qualification or coarse checklist closure occurred.

### Source observations and authored assignment locations

The Interface capture owner now exposes canonical actual-expression observation
groups, following directed ordinary connections without merging fanout consumers
and canonicalizing only true resolved aliases. The existing source sampler
consumes these groups and separate ordinary written outputs, retaining selectors
and shared causal region controls. No endpoint annotation is treated as data
ownership proof, and no second CDC graph was introduced.

Authored digital assignments now capture their existing semantic source span and
transport it through assignment snapshots. Fallback is restricted to actual
synthesized Stream-ready defaults and uses the real owned target/value span.
The frozen five-file slice passes **59 focused source checks**, including 16 new
observation groups, directly affected predecessors and three assignment-location
checks; three additional filtered SemanticOrigin predecessors pass separately.
One intermediate test used the wrong relative fixture path under Mill; correcting
that lookup retained the exact authored-line assertion, and all three mapping
checks then passed. Logs and exact hashes are under
`.work/source-interface-observations`, including `frozen5.sha256`.

Actual replay against the immutable native observation compiler passes **14/14**
expectations: twelve legal source witnesses and two source-derived shared-source
mutations rejecting with `NODAL-CDC-057-002` (protocol payload/valid and output
selector). Results are `native-replay-results.json` in that directory. Output
mutation diagnostics retain the actual output declaration, and digital assignment
IR retains the authored assignment spans. The protocol mutation currently reports
an unknown member-access location; a narrow truthful endpoint-declaration source
fallback remains open before full source-mapped acceptance. Waiver/event work and
final F57 integration remain required; no CI or broad qualification ran.

### Member diagnostic mapping repair

Canonical Interface member paths now inherit the real endpoint declaration span
when no more specific source span exists. The regenerated protocol reconvergence
mutation reports the actual member access at
`Increment57InterfaceObservationSourceTests.scala:57:5`, rather than an unknown
location; the output-selector mutation remains mapped at line 80. No invented
leaf span or source-expression parser was added.

The three-file repair passes **25/25** focused source checks (four mapping, seven
observation admission, fourteen Interface bridge) and all **14** regenerated
source/native replay expectations against the same immutable compiler. Logs and
exact hashes are `.work/source-interface-observations/member-repair*`.
A newly observed old child fixture had unconnected symbolic-width/Bool inputs;
it now uses a concrete connected UInt(1) boundary and Bool input, with an explicit
value-preserving symbolic-width select inside the child. Standalone symbolic type
coverage and alternate-parameter identity rejection remain, and the original
unconnected child is an explicit negative. No parameter-default equality or F58
boundary capability was invented. Scoped hierarchy/observation source-location
gaps are resolved; waivers, events and final F57 integration remain open.

### Owned waiver source and bridge checkpoint

The existing Cdc.waive spelling now captures a distinct owned stateless record,
exact fixed-whole payload ABI, actual source/destination and authored local
endpoint identity, bounded UTF-8 ID/reason, typed relation and explicit retained
obligations. Report descriptors derive from these records, not the reverse.
The bridge independently resolves real operands and exact fields/types/owners;
mandatory native proof remains the independent actual attribution/occurrence
boundary rather than a duplicate bridge CDC engine.

The existing relationship owner provides a read-only typed intent query. It uses
independent physical identity, frequency, timing, phase and exclusion facts without
inserting an edge or granting ordinary sampling permission. Opposite active edges
never become Same, and frequency correlation does not invent phase. Source
sampling separates destination permission from retained physical lineage, preserving
upstream crossing identities, Pulse classification and actual combinational
operands. Waiver reference bookkeeping detects feedback through the exception
without turning valid projected-write/Interface aliases into invented cycles.

The frozen fourteen-file source/model/bridge slice passes **57/57** focused checks:
seven waiver source/bridge groups, 46 relationship groups (including fourteen new
intent groups), and four reproducibility groups. The prior report fixture's
contradictory self-Asynchronous rationale is retained as a negative; its legal
executable counterpart uses Same. Exact files/hashes are
`.work/owned-waiver-source-checkpoint.{paths,sha256}` and the log is
`.work/owned-waiver-source-tests1.log`. Eleven actual source IR witnesses were
emitted under `.work/owned-waiver-source`; native/target execution and directly
affected sampler predecessors remain pending, not claimed from source compilation.

A demonstrated global relationship gap remains separately tracked: frequencies
derived in distinct opposite-edge timing components for one physical producer
must be checked consistently. The boundary query rejects that conflict; a focused
shared-owner global repair is still required before final F57 acceptance. Event
execution and final integration also remain open. No CI or broad qualification ran.
