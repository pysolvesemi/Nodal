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
