# Increment 58 readiness: hierarchy and reusable statecharts

## Baseline and acceptance boundary

Implementation starts from canonical `dev`
`615aad86775611bb8234e95ebd5371f16c4868ca`, tree
`1b8153fc0bccadb7ffa5faa743bdd95c25bd5845`, on
`increment/58-domain-hierarchy-statecharts`. The live integration ref was
independently refreshed before branching. The working tree was clean.

The [prototype policy](../../AGENTS.md#prototype-validation-and-merge) applies:
focused local positive, negative, boundary and affected predecessor checks;
no CI, full qualification or hourly automation. The initial ownership and
execution decisions below are not acceptance evidence; executed checkpoints
are recorded separately. All F-058 children remain open.

## Required scope and authoritative owners

The [main checklist](../roadmap/nodal-development-todo.md) owns F-058.A-G.
The linked [Area amendment](../roadmap/areas-symbolic-conditionals-v0.1-plan.md)
also owns required F-058.A.1 and F-058.B.2.2. The
[layout amendment](../roadmap/portable-verilog-vec-layout-v0.1-plan.md)
owns required F-058.B.2.1. These children are not optional documentation.

- B.1: selected interface roles and nested members, actual domain and resolved
  identity, inout pass-through, conservative topology, symbolic interface arrays
  and stable logical-to-physical paths across an exact child boundary.
- B.2: default/named domains and inferred capabilities, symbolic parameter/shape
  bindings, structural generation, deterministic material edge/reset variants,
  index-aware names and layout-independent boundary adapters. Area/member
  identity and guarded generated references belong to this same hierarchy,
  not a second elaborator.
- B.3: immutable reusable definitions/fragments with explicit runtime bindings,
  nested and parallel execution, typed completion/cancellation, timed/protocol
  states and separately bounded structural recursion and runtime call stacks.
  Metadata-only declarations do not establish execution.

The approved [lightweight hierarchy gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md),
[clock/reset hierarchy gate](../design-gates/NodalClockResetExecution-DG-v0.8.md),
[unified API gate](../design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md),
[statechart ADR](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)
and sequential-state gates constrain additive API decisions. New exact
spellings need an explicit concise versioned gate before production use.

## Shared-layer design decision

Reuse the existing transaction-owned `ConstructionSession`, canonical Instance,
typed interface leaf expansion and region/iteration capture. Existing default,
named and lexical domain selection already retain actual binding operands.
Extend those records rather than replacing them with a flattened hierarchy.
Reuse native `HierarchyModel` for occurrence identity, requirement substitution
and bounded proof, together with `DomainModel`, `DigitalAssignmentModel` and
`DigitalInterfaceModel`. Native ingress independently verifies source claims.

Reuse `FlatFsmConstruction`, `FlatFsmGraphContract` and `FlatFsmRuntime` for
state/action identity, capture and encoding. The composition layer must feed
the existing typed graph and sequential effect owners; it must not duplicate
the action parser, update priority engine or domain sampling rules. Separate
definition identity from each live instance's registers, counters and stack.
Keep source/debug/proof identity through any explicit lowering.

Rejected alternatives are one hierarchy per feature, a shadow occurrence graph,
one module per parameter value, a callback closure advertised as an immutable
definition, and a descriptor-only statechart advertised as executable hardware.
Known consumers are the existing digital and analog backends, F59-F63 control
and scheduling, F65-F67 emission/simulation/proof, F72 AMS and F159 mixed
iteration. These consumers do not create reverse completion dependencies.

## Initial production-path findings

F57's direct child digital port selection is exact and parent-owned, but its
implemented admission excludes generated scopes. Interface endpoint propagation
must extend the canonical boundary rather than permit foreign raw handles.
F57's concrete closed-root occurrence proof is not general hierarchical HDL.

`FsmDefinition` currently contains only a name and canonical enum type.
`parallel`, `submachine`, `boundedCallStack` and `after` explicitly reject with
`NODAL-FSM-058-UNAVAILABLE`; flat graph execution is not reusable composition.
Removing those refusals without live state, domain/reset and independent native
checks would not implement B.3.

## Validation and scale contract

Each coherent checkpoint records its exact production files, focused test
selection, actual executed counts and source revision here. Preserve positive
and causal negative cases for actual bindings, role/direction and driver rules,
generated existence/index bounds, dynamic capture, structural cycles, stack
overflow/underflow, joins, cancellation, reset and domain mismatches.

Actual source-to-IR-to-target demonstrations are required where target behavior
is implemented. Reparse-only checks are not independent execution; future
backend support is not a tool result. Generated logs and target files stay under
ignored `.work/`, while maintained source fixtures and runners remain tracked.
No checkbox closes until its own implementation and focused checks pass.

Review repeated traversal, occurrence expansion, structural depth and live
state/stack growth separately. Reused module definitions must remain compact
over symbolic parameter envelopes. Measure representative deep/wide hierarchy,
nested/parallel statecharts and stack boundaries, not a global benchmark suite.
State explicit resource limits and unsupported profiles without weakening
existing semantic rejection.

Ordinary Scala loops stay elaboration-only. Symbolic target generation cannot
be replaced by cloning each parameter value. Root-only low-level event escape,
analog/conservative topology, resolved-net ownership and actual clock/reset
provenance remain unchanged. General F159 mixed-effect loop partitioning and
future F65 internal-array configuration retain their own owners; required F58
structural hierarchy and boundary layout semantics are not deferred to them.

## Checkpoint 1: immutable definitions and explicit bindings

The [v0.5 sequential-state contract](../design-gates/NodalSequentialState-DG-v0.5.md)
adds immutable `FsmDef`, fragments, state declarations and typed guard/action
slots. Explicit instance bindings feed the existing flat-FSM capture, shared
structural admission, bridge and native execution. This checkpoint does not
include the concurrently developed Area, interface-boundary or hierarchy-target
changes. No F-058 checkbox closes.

Focused checks use Scala 3.8.4 and JDK 25.0.2:

- Ten maintained frontend checks passed, including separate producer/consumer
  JARs, checked action bytecode, dynamic graph capture, typed enum mismatch,
  mutable/nested action binding, forged runtime, missing-plugin and replacement
  uninstrumented-producer rejection.
- Eleven new runtime/bridge/native groups passed. These cover immutable graph
  data, independent instances, exact/missing/extra/foreign bindings, lexical
  observations, foreign construction values, bounded lazy sequences, structural
  admission, exact slot identity, custom encoding and forged native graph input.
- Actual two-instance public source passed default gating, normalization, CIRCT
  and SystemVerilog export, then Icarus simulation. The waveform checks independent
  state, compact/one-hot storage with shared enum ABI, transition-before-entry
  precedence, active actions, completion/terminal hold, forced illegal-code
  recovery and synchronous reset.

The native and target checks above were repeated against the frozen F57
compiler SHA-256
`2b0121c941d1299927b4e770dd1937d4c6072b7a2efc5bd42273f5f01ede9dcd`,
so they do not depend on concurrent native F58 edits. The default `Error`
illegal-state policy exports assertions, which this Icarus build cannot parse;
simulation uses an explicit `RecoverToInitial` source variant instead of
removing assertions or rewriting the generated DUT. Maintained reproductions
are in `tests/scala/reusable-fsm/`; generated artifacts and exact command/hash
manifests remain under ignored `.work/f58-statecharts/`.

The first affected-predecessor replay passed 38 of 41 tests. Three old F56
repeated-child fixtures failed F57's actual-parent-input lineage requirement.
A fresh isolated compile of canonical `9f6d1a5` API/plugin and unchanged
fixtures reproduced all five underlying failing constructions; those source
files are identical to canonical `615aad8`. The narrow fixture repair supplies
the missing parent data connections and preserves every existing assertion.
This baseline reproduction is retained in
`.work/f58-statecharts/predecessor-baseline/`; it is not counted as a passing
regression run.

Final acceptance compiled the exact staged API, integrations, frontend, bridge
and plugin independently of the other F58 worktree changes, with the original
constructor-to-callback phase ordering. All 52 focused groups passed:
F56 capture 24, F56 bridge 12, F57 crossing 5 and F58 reusable definitions 11.
The ten separate frontend checks and the actual generated waveform also passed
using those standalone artifacts. The early graph-record budget is checked
before copying or traversing its transitions. Source hashes, compile commands
and results are retained under `.work/f58-statecharts/staged-check/`; the
generated waveform is under `.work/f58-statecharts/target-staged/`.

Nested/parallel lifecycle, typed completion/cancellation, timers/protocols,
structural recursion, bounded runtime stacks and dedicated composition
report/proof metadata remain required work, not implemented by this checkpoint.

## Checkpoint 2: native hierarchy execution and exact Interface boundaries

The [hierarchy execution contract](../design-gates/NodalHierarchyExecution-DG-v0.1.md)
retains exact formal clock/reset operands through normalization and emits real
reusable target modules and instances. Only demanded capabilities become input
ports; material edge/reset differences select deterministic variants, while
different physical clocks alone do not duplicate definitions. Normalization
uses one transactional root candidate rather than cloning the root per module.
Indexed variant/binding lookup and existing occurrence/operation budgets bound
the represented work.

Native `interface_boundary` independently resolves an exact immediate-child
endpoint and its full typed definition. It preserves selected roles, actual
binding provenance and real parent/child data dependencies. Imported definition
consistency includes sibling-only roles, even when the parent definition never
declared that role. This checkpoint contains native ingress and proof, not
Interface target emission or the corresponding source projection API.

Focused native acceptance passed 513 cases across seven suites: Interface model
168, Interface round-trip 25, sequential model 71, sequential CIRCT 145,
hierarchy CIRCT 13, hierarchy controls 27 and hierarchy model 64. This includes
causal extra-role forgery rejection, minimal crossing controls, unused-source-edge
definition sharing, formal operand/attribute forgeries, transactional unsupported
symbolic target requests and a 32-instance shared-definition scale witness.

The frozen compiler SHA-256 is
`2125c01fdc146894cf5e4a2e9a58dfc52b9f56b6590022268a728a5442eacbe7`.
Its pre/post native source manifests match exactly; the manifest digest is
`c1aefc68552879a51882f8c8edb028ea54f51a3f0e1c1be74af1f47dad7da1db`.
Build and test records remain under `.work/f58-native-checkpoint/`.

Five maintained actual public-source examples pass default gating, CIRCT and
SystemVerilog export, then generated-DUT Icarus simulation: shared independent
clocks/state, nested forwarding, opposite edge/reset-polarity variants,
resetless state and inherited asynchronous-assert/two-edge reset release through
two child levels. Nine retained F57 source cases also pass gating, lowering and
SystemVerilog export. These nine are export checks, not nine additional waveform
claims. The four source-structure tests and five-case exporter also passed a
strict standalone compile/run against checkpoint 1's isolated source artifacts,
without the ongoing Area/Interface source changes; all five emitted input hashes
match the simulated inputs. Reproduction is in `tests/scala/domain-hierarchy/`; receipts are under
`.work/f58-hierarchy-target-checkpoint/` and
`.work/f58-hierarchy-predecessor-checkpoint/`.

The affected backend catalog generator also passes `--check`, with 49 focused
registry tests passing. Exact canonical `615aad8` inputs reproduced its 13
missing F57 operation entries before repair. Those forms now retain conservative
digital/unsupported analog-target admission; the new Interface projection is
explicitly unsupported by those targets. Generated native and Scala registries
were regenerated together. The historical F47 digest assertion remains unchanged,
with later additive inventories excluded explicitly rather than replacing it.

Symbolic parameters/shapes, generated hierarchy families, complete boundary
layout adapters, Interface arrays/resolved/conservative topology and actual
Interface target emission remain required F58 work. No checklist child closes
from this finite concrete digital checkpoint.

## Checkpoint 3: lexical Areas and typed Interface source projection

The [Area contract](../design-gates/NodalAreasHierarchy-DG-v0.1.md) implements
compiler-owned anonymous, reusable, inherited, helper-created and nested lexical
groups inside the existing construction transaction. Areas add neither module
boundaries nor state. Exact captured members retain canonical hardware identity,
ownership, local formal-domain provenance and deterministic lexical names.
Compiler-inserted structural-member casts admit only the exact statically
refined member type; arbitrary capability-changing casts remain forbidden.
Construction failures, including constructor-argument failures, poison the
transaction even if authored code catches the exception.

The [Interface contract](../design-gates/NodalInterfaceHierarchy-DG-v0.1.md)
adds typed parent-owned projections of an actual child endpoint and same-role
`forwardExact` connections. Role declaration and boundary direction remain
separate. The source and bridge check complete typed ABI, selected/nested roles,
exact actual bindings and real data lineage; a projected output never becomes
writable and a projection never creates a second endpoint or net.

With Scala 3.8.4/JDK 25.0.2 and frozen checkpoint-2 native compiler `2125c01f`,
the combined focused run passed 52 tests: Area 6, Interface hierarchy 7,
F54 Interface source 9 and bridge 14, F57 hierarchy 9 and Interface observations
7. The configured native checks executed rather than skipping.
The clean standalone Area harness also passed seven negative fixtures, actual
callback writes, constructor failure/evaluation-order/depth tests, repeated
helper naming, separate producer/factory JAR inheritance and nested projection,
missing-metadata and missing-plugin checks.

Fresh actual Scala Area replays passed the explicit default native gate for
lexical and external-write cases; the duplicate-driver case was rejected by
the existing driver owner. External-write also passed target lowering and
SystemVerilog export, retaining one `group_target` wire and direct output
assignment, with no Area module or extra latency. This is an export/structure
witness, not a claimed Area waveform test. Actual Interface forwarding/input
drive replays pass native admission; Interface target lowering still refuses
transactionally with no output, rather than claiming nonexistent emission.

Maintained reproductions are in `tests/scala/areas/` and the source test classes.
Receipts remain under `.work/f58-source-checkpoint-tests.log`,
`.work/f58-areas/`, `.work/f58-area-source-checkpoint/` and
`.work/f58-area-native-checkpoint/`. Per-Area membership sets and deferred naming
avoid repeated declaration scans; existing depth/transaction limits remain.
Generated Area collections, symbolic boundary selection, Interface arrays,
resolved/conservative propagation and target adapters remain required open work.

## Checkpoint 4: contextual native boundary and controlled execution

The [boundary ABI contract](../design-gates/NodalHierarchyBoundaryABI-DG-v0.1.md)
adds a bounded, query-local view over the existing hierarchy, parameter, shape,
digital type and Interface owners. Exact formal/actual type comparison uses the
actual occurrence substitution, not equal type text, equal defaults or equal
bounds. Complete Interface leaves retain roles and dimensions even when no
source access names them. No second occurrence graph or endpoint is introduced.

The native target now transports bounded symbolic scalar and packed-Vec child
ports through reusable parameterized modules and real instances. Checked signed
32-bit target parameter arithmetic is a representation constraint of the pinned
CIRCT textual roundtrip; semantic source integer facts remain 64-bit. Bounded
ordinary Integer configuration uses existing parameter constraints rather than
being relabeled structural. Unsupported or overflowing target expressions fail
transactionally. Generic bodies are not cloned for individual parameter values.

The [controlled execution contract](../design-gates/NodalSequentialState-DG-v0.6.md)
extends the existing flat-FSM executor with an explicit four-code lifecycle and
separate local-state storage. Native v2 normalization preserves reset, cancel,
restart, enable, entry and exit priority; ordinary v1 behavior is unchanged.
Result identities come from the same FSM owner. Sparse enum decoding preserves
both the original-input validity and the declared fallback, rather than assuming
contiguous codes or a zero fallback. Attributed bitwise fan-in is emitted as
bounded balanced binary trees: a focused exported-HDL witness exposed the pinned
firtool dropping later operands of attributed variadic operations. The repair
retains all operands and source correlation; the three-reset regression checks
each independent reset cause.

Focused native checks passed 800 cases across 12 suites: digital types 114,
materialization 46, Interface 186, observations 25, sequential 71, FSM 70,
sequential target 149, hierarchy target 13, hierarchy control 27, hierarchy data
64, boundary ABI 23 and symbolic target 12. This is focused prototype acceptance,
not global qualification. Source capture and bridge additions for these native
contracts are a separate checkpoint; their working-tree demonstrations are not
claimed as committed public API by this native slice.

Architecture review: canonical owners remain authoritative, derived views are
query-local and bounded, complete ABI collection does not require eager source
member access, and target work is bounded by legal footprints rather than a loop
over parameter values. The target limit remains distinct from source metadata
scale. Symbolic named-field adapters, Interface target connections and resolved
or conservative topology, generated families and real target loops, formal-domain
FSM instances, hierarchical/parallel composition and bounded recursive control
remain required open F58 work. No checklist parent or child closes here.

## Checkpoint 5: finalized symbolic source boundaries and lifecycle API

Source capture defers only exact child digital-port boundary views until all
instance parameter overrides are final. One shared, bounded instance-query cache
then resolves actual parent-owned widths and dimensions for digital ports and
Interface arrays. The bridge independently repeats contextual ABI checks; equal
bounds, defaults or formal spelling cannot substitute for occurrence identity.
Only reachable canonical integer DAG nodes are exported. Ordinary Integer range
constraints are emitted without inventing structural effects. Interface arrays
retain declared role authority, complete dimensions and exact child identity;
unsupported resolved-member effects remain explicit errors.

Controlled immutable FSM instances use the existing construction, flat-FSM and
sequential owners. Separate instances retain independent local state and lifecycle
storage. Controls and observations preserve lexical lifetime, exact types and
construction ownership. Resetless initialization requires the explicit restart
contract. The source API does not yet claim formal-domain machines, nested or
parallel submachines, timed/protocol policies or bounded call stacks.

The final focused source run passed 41 groups: symbolic hierarchy 4, shared
structural boundary 5, Interface arrays 5, controlled FSM 7, predecessor boundary
arrays 9 and digital collections 11. Earlier affected predecessor runs passed
55 Interface/hierarchy groups and 52 ordinary/reusable FSM groups; those receipts
remain distinct from the final 41-group snapshot. Configured native checks ran
rather than skipping. Controlled source is independently admitted through the
full default gate and standalone normalization, each starting from original IR.

Actual source replay and target demonstrations remain under
`tests/scala/domain-hierarchy/` and `tests/scala/reusable-fsm/`. Scalar and Vec
hierarchy witnesses retain two generic modules and two tested root parameter
configurations, with identical early/late override captures. No per-value module
clones are used. These are bounded SystemVerilog/CIRCT witnesses, not a portable
Verilog or global qualification claim. The standalone Area regression also covers
eager fields produced by ordinary Scala collection mapping; those host loops
remain elaboration-only.

The shared owners and bounded query lifetimes are unchanged. The reachable-DAG
regression adds 256 unrelated nodes without enlarging the exported expression
graph; this is a structural check, not a broad performance measurement. All
remaining feature obligations listed in checkpoint 4 stay open.

The final target compiler (`efd32347`) passed all nine hierarchy profiles: four
symbolic scalar/Vec cases and five concrete predecessor cases (shared and nested
hierarchy, material domain variants, resetless and inherited reset release).
Both lifecycle profiles, reused controlled machines and an initially-terminal
machine, passed unchanged waveform assertions through the actual production
pipeline. Receipts are in `.work/f58-symbolic-target-checkpoint4/`,
`.work/f58-hierarchy-target-checkpoint4/` and
`.work/f58-statecharts/lifecycle/{target-passed,terminal-target-passed}/`.
Failed earlier export receipts remain separate; hand-edited diagnostic IR is not
acceptance evidence. The native compiler has 800 passing focused cases as
recorded in checkpoint 4.

## Checkpoint 6: contextual native state and complete Interface payload ABI

Native sequential consumers can retain an exact unresolved local domain
requirement until closed-root occurrence proof. The shared sequential owner
supplies contextual clock/reset intent; the hierarchy owner recognizes the
consumer itself as an inference obligation, without requiring a pre-existing
Clock projection. Missing roots and unbound requirements still reject. Explicit
`if_reset` private-state initialization is resolved per actual reset-bearing or
resetless occurrence, without fabricating a Reset projection or startup value.
Ordinary mandatory initializers retain their reset requirements. FSM execution
continues through the same flat executor and sequential-update normalization.

Complete Interface payload admission now retains formal child declarations and
separate checked actual types. The existing digital type comparator proves both
exact occurrence contexts; complete imported ABI admission is cached by
(definition, instance), not definition alone. Unaccessed leaves and sibling
instances with different bindings cannot inherit another occurrence's proof.
This is native admission and provenance, not Interface target lowering.

The matched native compiler `3a8ddf17` passed 831 cases in 12 focused suites:
digital type 114, materialization 46, Interface 196, observations 25, sequential
71, FSM 77, sequential target 149, hierarchy target 22, hierarchy control 27,
hierarchy data 64, boundary ABI 28 and symbolic target 12. Before/after native
source manifests agree. Receipts are under `.work/f58-contextual-native-third/`.
The nine previously accepted actual-source hierarchy profiles also pass complete
gate, target export and simulation on this compiler, with receipts under
`.work/f58-{symbolic,hierarchy}-target-checkpoint6/`.

New formal-FSM and symbolic Interface public-source integration remains a
separate in-progress checkpoint, including canonical single-definition reuse;
this native checkpoint does not claim those unfinished source results. Derived
views remain bounded and query-local. Repeated identical Interface leaf type
proofs are a known local optimization opportunity, not a new semantic owner or
a claimed performance improvement. Generated semantics/target loops, aggregate
field adapters, resolved and conservative topology, hierarchical/parallel FSMs,
timed protocols and bounded call/return remain open as previously recorded.

## Checkpoint 7: generated source references and contextual public APIs

The [generated Area contract](../design-gates/NodalGeneratedAreas-DG-v0.1.md)
adds typed saved/nested member references and one-template range capture through
existing lexical, generated-region and structural-bound owners. Hidden and
host-only members do not become exports; a public readonly alias retains its
actual storage identity without gaining write authority. Ordinals are checked
against every legal parameter value, not defaults. Ordinary Module/Area
structural-if-to-when composition uses the same ancestry and exclusive-driver
proof as generated lanes; pure collection/procedural contexts remain restricted.
Legacy two-, three- and four-argument range callbacks retain both source syntax
and their compiled entry points/default getter. New range values use an
extension callback adapter to avoid ambiguous overloads.

Generated family bridge/native transport and real target loops remain separate
work. Until their canonical schema is consumed, the bridge explicitly rejects
family/export/member facts rather than dropping them. A million-lane source
metadata envelope still contains one template, not a million elaborated Areas;
this does not claim million-lane target admission or emitted output.

Symbolic Interface payloads retain one formal role graph plus complete ordered
parent-owned actual types, using the same batched descriptor transform and
instance integer query as digital ports. Public external-package fixtures cover
Struct/Vec protocol payloads, whole arrays, early/late overrides and unaccessed
ABI mutations. Equal bounds/defaults are not identity. A public wrapper cannot
turn a raw child endpoint into a boundary; direct access to the private instance
module separately fails with the expected compiler access diagnostic. Native
admission runs on the matched checkpoint-6 compiler, not a future target.

Required-domain FSMs now share one source definition across reset-bearing,
resetless and forwarded occurrences. Only actual private FSM storage carries
conditional initialization; ordinary initialized registers still require reset.
Declaration collision keys are local to their already module-local allocation
scope, preserving distinct actual paths while giving identical definitions
identical local names. Neither bridge comparison nor runtime semantics was
weakened to obtain definition reuse. Independent typed field observations were
also added to the nested aggregate source witness, beyond whole pass-through.

The coordinated focused run executed 73 source/native-facing groups: required
FSM 6 and actual native 1, controlled FSM 7, hierarchy source 4, F56 capture 24
and bridge 12, aggregate hierarchy 2, Interface payload 5 and actual native 1,
and generated Areas 11. Its sole failure was an obsolete diagnostic phrase in
the F56 predecessor; the compatible phrase was restored without changing the
assertion or rejection. The focused bridge rerun passed all 12 groups with unchanged assertions. Two
additional exact semantic naming predecessors passed with explicit execution
counts.
Earlier shared structural query and symbolic boundary checks remain separate
receipts, not extra groups added to this count.

All three corrected actual-source required-FSM target profiles pass the complete
gate, CIRCT lowering, firtool export and Icarus waveform pipeline on compiler
`3a8ddf17`: mixed reset presence, material edge/polarity and forwarded domains.
The source has one canonical FSM definition; target variants differ only where
material domain controls require it. Resetless private state is not asserted
initialized before accepted restart. Receipts are under
`.work/f58-statecharts/required/target-{mixed,material,forwarded}/`.

The focused Area harness also passes lexical/generated separate-JAR capture,
negative member/capability/host-mutation checks and plugin requirements. A JAR
compiled against the preceding API runs all original callback arities against
the new runtime. Maintained demonstrations are in `tests/scala/areas/`,
`tests/scala/interface-hierarchy/` and `tests/scala/reusable-fsm/`; outputs remain
under `.work/f58-generated-areas/` and `.work/f58-interface-payload/`.
These are bounded local results, not completion of generated native execution,
Interface target adapters, resolved/conservative closure, statechart composition,
timed protocols, recursion or any F58 parent checklist.

Complete formal Interface/boundary/actual-type inventory in the canonical
construction JSON artifact remains an identified serialization follow-up; the
actual MLIR and independent native checks above retain those facts. This slice
does not claim that unfinished artifact coverage.

## Checkpoint 8 — canonical boundary artifact coverage

On canonical source checkpoint `d5dc7e84`, the existing
`ReproducibilityContract` serializer now retains complete Interface formal role
and member contracts, declaration boundary identity, ordered parent actual
member types and typed connectivity references. It also retains the existing
Area inventory and generated family/export/member/scope identities, including
retained family facts without a digital body. These are conditional additive
fields in the canonical artifact, not a second registry or a permission source.
The checkpoint-7 serialization gap above is resolved by this slice.

The focused command in `.work/f58-boundary-artifact/run.sh` selected
`Increment58BoundaryArtifactTests` and `ReproducibilityContractTests` and executed
**8/8 groups successfully**. Full Scala/Java source manifests before and after
match. Mutation checks distinguish boundary, formal role, actual type, member,
family and scope changes while permitted inventory traversal reorderings retain
identical artifacts. The configured native reproducibility predecessor executed
actual MLIR-to-Verilog-A and manifest comparisons, without skipping, using frozen
`nodalc` hash `3a8ddf17cc4322fd5aca9e59849eb45ed15b19ecaba06e9efe805f6bcd0cb880`
and `nodal-translate` hash
`9fcc1704bcae2da890365d238aea291302a1bbad19198b671dbc791b3791261a`.
Commands, logs and exact tool hashes remain under `.work/f58-boundary-artifact/`.

This is serialization acceptance, not new Interface/generated target acceptance.
The serializer traverses canonical bounded inventories and preserves meaningful
member/export order; it neither expands symbolic lane counts nor introduces a
new hierarchy engine. The current slice does not affect generated Verilog-*.
All remaining F58 implementation and checklist obligations stay open.

## Checkpoint 9 — direct generated-family native semantics

On canonical checkpoint `e33e2dcb`, the existing native storage, shape, assignment,
structural-if and sequential owners now verify direct typed generated families,
closed export inventories and exact member access. Ordinal selection proves
`lower + ordinal * positiveStep < upper` over all legal bindings, not a sampled
value. Export/member access is monotonic, references preserve actual storage,
and template-local driver/overlap proof retains generated occurrence identity.
Structural-if capture canonicalization reuses one SSA/type-use visitor; retained
state and opposite-arm updates normalize through the existing sequential owner.

The shared parameter capture probe no longer mistakes a generated induction for
a fixed type-environment binding. Actual shape/predicate resolution still retains
its generated facts; no zero/default substitute is introduced. Repeated Interface
type comparisons use the existing model's bounded query-local exact-context memo,
with forged sibling/last-member regressions. There is no global permission cache
or measured universal speedup claim.

A serialized, source-matched build and focused execution passed **781/781 cases**
across seven suites: Storage 147, AssignmentGuard 34, StructuralIf 73,
ShapeIteration 130, Sequential 84, DigitalType 114 and Interface 199. The Parameter
executable also passed its 23 named symbolic cases and earlier assertion sections;
those uncounted sections are not folded into the 781 total. Four exact actual
public-source default-gate inputs pass: lane, ordinary, selected and guarded.
Verification remains enabled. Full native before/after manifests match, with
manifest digest `8b7c6389b872a47e345b40d34ff4e97f83acf35b8da89cb0dbf3990d9c7f6a99`.
The frozen compiler is `.work/f58-generated-native/third/nodalc`, SHA256
`01059e71197a1ef88b7e673a76d4479b5631a309cf6b813d1788c7c952425f8b`.
Exact build/test commands, inputs, hashes and logs remain in that directory.
The synchronized backend registry generator checks and focused catalog tests
pass (50 tests); unsupported new raw generated forms remain explicitly declared,
not silently admitted as target output.

This checkpoint is native semantic admission, not completion of nested generated
families, generated module/interface carriers, target loops or waveforms. The
million-lane case retains one bounded template and metadata; it does not claim a
million-lane target result. The updated generated-Area gate records successful
pinned primitive probes and the reusable-helper target plan as implementation in
progress, including actual consumer limitations, rather than Nodal acceptance.
Source bridge changes producing the four new inputs are a separate pending
checkpoint. All required F58 child and parent obligations remain open.

## Checkpoint 10 — native composition and scalar resolved topology

On canonical checkpoint `2097ac43`, the additive v0.7 composition ingress is
implemented by one bounded query-local `FsmCompositionModel`. It verifies actual
private-state/control operands, exact owner states and domains, unique parentage,
complete child inventories, acyclic depth/work bounds and structural completion
possibility. Canonical local graph admission, encodings and lifecycle codes stay
in `FlatFSMModel`; v3 adds its establishment result without changing v1/v2.
Pure join/activation expansion uses the existing detached sequential candidate
and operation budget before the unchanged state executor. Identity-only operands
are excluded from combinational dependencies. All/Any joins observe registered
child status; cancellation, restart, pause and recovery retain the v0.7 ordering.

The native resolved slice extends the existing verified Interface dependency
facts and occurrence graph, rather than introducing a second hierarchy engine.
Scalar read/drive/pass-through joins retain actual drivers and their occurrence
contexts; unaccessed endpoints are logical slots, not newly allocated nets.
Nominal child domains cannot launder actual foreign drivers or restore strict
attribution to joined aliases/read results. New boundary-joined split views still
reject; complete split, standalone inout and public typed read/target support
remain required continuation.

The matched second native run executed **458/458 cases** in this checkpoint's
six affected dialect/model suites: composition 17, FlatFSM 81, Sequential 84,
AssignmentGuard 34, Interface 217 and observation 25. Composition tests include
All/Any status truth tables, arbitrary binary current/next/lifecycle codes and
event/control combinations, impossible joins, identity/domain/future forgeries,
semantic dependency roles and exact resource bounds. An optimized-only dangling
initializer-array bug in the new replacement loop was fixed with a stable local
array; the same optimized test now passes. An ordinary-value role probe is
explicitly non-diagnostic, and its causal regression passes. No assertion was
weakened. The backend generator check and focused catalog 51 tests also pass.

The complete workspace run additionally passed 190 target cases, but those use
the pending named-field adapter and are **not** acceptance of this native-only
checkpoint. Its target implementation/test files are deliberately excluded here.
The frozen workspace diagnostic compiler is
`.work/f58-composition-native/second/nodalc`, SHA256
`23a5fa27949c0986722f5b82dc0453aa6302753a41c9a229b9f9f965343b7eda`.
Before/after manifests including C++, headers, TableGen, registry includes,
JSON and CMake match; commands and individual logs are adjacent. Earlier native
manifest recipes covered C++/headers/TableGen/CMake with separate registry checks;
they did not include every generated registry input in that manifest itself.

Source composition/report/replay and resolved public projections remain a
separate pending source checkpoint. Their shared compile exposed and repaired
an exact public fixture argument error and a new companion effect-summary gap;
focused source execution is still pending at this native checkpoint. The current
slice does not itself establish a generated Verilog-* demonstration. No F58 child
or parent is closed by these bounded native results.


## Checkpoint 11 — explicit named-field parameterized hierarchy target

The existing sequential target now accepts the explicit uniform option
`boundary-abi=named-fields`. Legacy packed output remains the default; field-use
presence never selects the ABI. Logical Port/Struct leaves share the canonical
hierarchy parameter/shape proof and one inverse leaf map per module port. Instance
pins reference that map without copying it; field lookup is cached per admitted
logical shape. Nested sibling dimensions remain separate and metadata cost is
bounded by represented fields/dimensions, not physical bit count.

The final frozen adapter passed **190/190** focused target cases: symbolic
hierarchy 19, sequential target 149 and hierarchy target 22. The matched compiler
is `.work/f58-named-boundary/second/nodalc`, SHA256
`6fb378a41d7100712d6a29b4bf00f365d637fdbb82a4072f679b71a5a3a299e2`.
Its before/after production and test manifests match. Actual maintained Scala
aggregate early/late override exports passed the full default gate, named-field
lowering, firtool export, Slang lint and Icarus compilation. The maintained
`tests/scala/domain-hierarchy/run_target.py` additionally passed both actual DUT
waveform profiles, each at width/count overrides (7,3) and (2,4), preserving two
module bodies and real instances. Checks exercise distinct fields/lane patterns,
independent ready/signed observations, negative signed representation, parameter
widths and inverse-map dimension annotations. Whole nested sample pass-through
is not an independent nested-lane extraction proof. Commands, tool/input/output
hashes and results are under `.work/f58-aggregate-waveforms`.

This is a bounded digital Port/Struct SystemVerilog target profile, not portable
Verilog qualification, complete Interface target support, generated-family loops
or complete fixed-Struct CDC adaptation. The default ABI and those remaining
consumers retain their explicit limitations. No F58 completion box is closed.

## Checkpoint 12 — exact nested composition enable forwarding

The actual nested immutable-source replay exposed an overly restrictive native
activation-use check: a child owning a further composition must forward its exact
inherited enable to that nested activation. The canonical composition owner now
permits only its enable result at the nested activation's parent-enable operand,
with the actual owned child's local/status identity. Full-model admission still
checks the complete parent future/control tuple, domain and unique topology.
Arbitrary uses, foreign parents and cancel-as-enable remain rejected.

The optimized composition suite passes **20/20** cases, including three causal
nested regressions; FlatFSM **81/81** and Sequential **84/84** predecessors pass.
The frozen compiler `.work/f58-composition-native/nested/nodalc`, SHA256
`ef01aec403d9c0db760c7efa90ae71f22ad8c352ab4144a726dca29d7fc2460d`,
also passes all four actual-source All/Any/Priority/nested pipelines through the
full default gate, CIRCT, firtool and Icarus simulation. Receipts are retained in
`.work/f58-statecharts/composition/target-final-{all,any,priority,nested}`.
The source replay and maintained bench remain pending in the next source
checkpoint; these are matched integration observations, not a claim that this
native-only commit contains that source. This repair adds no separate executor
and does not close remaining timer/protocol/recursive-control obligations.

## Checkpoint 13 — composed source, generated storage replay and resolved projections

Immutable typed child slots now retain exact parent/state ownership and explicit
runtime bindings. Two-phase capture allocates each actual local/lifecycle pair
once, then captures canonical parent/child executors and registered-status joins.
All/Any and ordered per-child Priority reuse existing transition semantics;
finite topology, depth, occurrence and selected-join operand work are preflighted
before expansion. Repeated immutable reuse is charged by physical occurrence,
not object count. Artifact records retain definition hashes, exact composition
identities, structural completion possibility and independently checked costs.
No product-state encoding or second state engine is introduced.

The generated-Area source bridge retains exact family/export/member identities,
actual body ownership and the least legal enclosing projection scope. Maintained
source witnesses include per-lane enable, exact Bits payload, inversion, reset
hold and aliases for symbolic and concrete lane families. Scalar resolved child
Interface projections preserve absolute read/drive/connect rights and finalized
actual member types without creating new nets. Exact endpoint shape comparison
uses an explicit actual-dimensions witness, not a fictitious Bool member lookup;
an ordinary UInt-only Interface regression protects the shared correction.

Focused source acceptance comprises **47 passing groups**: generated Area 14,
composed FSM 10, controlled FSM 7, required-domain FSM 6, artifact 4, resolved
Interface 5 and resolved native replay 1. The fifth matched run established the
first five suites; after a test-only parent-parameter rename made the distinct
formal/actual witness observable, the sixth matched run passed resolved 5+1.
The native group executes two actual default gates. Earlier failed compiles,
fixture assertions and a missing compiler environment are retained in logs;
none is counted as passing or hidden by weakened assertions.

Four actual compiled composition source profiles pass the complete default gate,
CIRCT lowering, firtool export and Icarus waveforms on compiler `ef01aec4` from
checkpoint 12. All/Any/Priority use three distinct local/lifecycle register pairs;
nested uses four, plus the two explicit action-value registers in each witness.
The generated output visibly joins registered Completed status rather than a
Cartesian state encoding. Maintained commands and hashes are in
`.work/f58-statecharts/composition/target-final-{all,any,priority,nested}`.
Six actual shared-classpath generated-Area replays independently pass the default
gate on the same frozen compiler, with identical predecessor source hashes;
receipts are in `.work/f58-generated-areas/shared-checkpoint`. These six are
native semantic witnesses, not generated-family target waveform claims.

Nested generated declaration capture, generated module/Interface carriers,
retained target loops, complete resolved/inout/conservative topology, timed and
protocol states, explicit finite construction and runtime call/return stacks
remain required continuation. This source checkpoint closes no F58 parent or
child checklist item. Markdown, Python syntax and diff checks pass.

## Checkpoint 14 — bounded lexical nested-family native admission

Existing storage, parameter, shape and structural-body owners now admit an actual
family beneath an unbroken Generate-family ancestry. Independently static bounds
retain lexical dominance and the canonical structural Integer expression proof;
member ordinals reuse that same proof instead of discarding it through a second
module-only query. Storage/export identity remains directly in the exact inner
family. Recursive body admission shares its finite depth/work budget and keeps
procedural/behavioral declaration capture outside this selected profile.

A fresh exact local-family wire/register root is independent in each nested
occurrence. Repeated outer/module roots do not gain that independence. The new
causal register test exposed an early register-next dependency branch that lacked
this check; it now rejects nested writes unless their actual root belongs to that
exact inner family. No loop iteration or parameter value is enumerated, and no
new family registry or graph is introduced.

Matched native acceptance passed **476/476 cases**: storage 155, structural 73,
shape iteration 130, sequential 84 and assignment guard 34. Eight new storage
cases cover nested lexical selection, nonzero lower/step coordinates, independent
symbolic bounds, out-of-range ordinals, non-independent bounds, local register
updates, repeated outer writes and raw storage escape. The parameter executable
also exits zero with its 23 named symbolic checks and earlier assertions.
Three actual maintained source exports (concrete, symbolic and register-update)
pass the full default gate with zero diagnostic bytes. The pending source
promotion is separate; these exports are not claimed as committed source here.

The frozen workspace compiler is `.work/f58-nested-native/final/nodalc`, SHA256
`30973efaca3f0038120a787d28cba9c365063b7481ead9e3c4f93fb96329b769`.
Full native source/test manifests match before/after the build and tests. This
workspace also contains the separate pending Interface target adapter: 199 of
200 target cases passed, with one exact expected-diagnostic mismatch retained
for its owner. That target is excluded from this checkpoint and these results do
not claim its acceptance. Logs, commands and hashes remain adjacent to the frozen
compiler. Outer-dependent domains, guarded family declarations, external selected
family chains, generated symbols and actual retained target loops remain required
continuation. No F58 checklist completion is inferred.

## Checkpoint 15 — ordinary Interface target boundary adapters

The existing target logical-port plan now consumes complete ordinary digital
Interface endpoints as well as Port declarations. Exact endpoint/member identity
and selected-role direction determine pins, not the first monitor/member view.
Existing Interface dependency facts supply drive/connect aliases; complete
contracts are borrowed once per original definition, while per-material-variant
pin emission stays separate. Symbolic outer arrays and nested Struct/Vec payloads
reuse the named-field split/join and inverse maps, parameter DAG and collision-safe
names. Missing/undriven outputs, untyped legacy endpoints and unsupported locally
driven declared inputs fail transactionally rather than disappearing from output.

The matched final compiler passes **202/202** target cases: symbolic/Interface 31,
sequential 149 and hierarchy 22. Twelve new Interface cases include complete
unaccessed inventories, first-monitor-view direction, exact symbolic payloads,
name collisions, malformed payload maps and unsupported-profile diagnostics.
The frozen compiler `.work/f58-interface-target/final/nodalc` has SHA256
`0a34866460df1e4ac0ee20c1622fe3e212ca2ade5e0c101d4703add814313202`;
production/test manifests match before/after. Earlier fixture errors and the
exact diagnostic mismatch are retained, not treated as successful acceptance.

All **11 actual-source waveform profiles** pass on this compiler: four fresh
maintained Interface array/payload early/late exports, both aggregate profiles and
five concrete hierarchy predecessors. Each Interface/aggregate profile exercises
two parameter configurations. The Interface benches observe independent payload,
valid and array fields plus actual child input pins. Gate/lowering/firtool/Icarus
commands and input/output/tool hashes are retained under
`.work/f58-interface-target/maintained-waveform-final`,
`aggregate-predecessors-final` and `concrete-predecessors-final`. The additive
maintained exporter was strictly compiled against checkpoint-13 shared classes;
its four outputs match those actual existing public-source fixtures exactly.

This is ordinary digital Interface target support, not resolved/inout/conservative
or control Interface target support, general generated loops, portable-Verilog
qualification or completion of fixed-Struct CDC inverse adaptation. Those required
continuations and pinned generate-consumer limitations remain explicit in the
existing gates. Markdown, Python syntax and diff checks pass; no F58 completion
box changes.

## Checkpoint 16 — timed execution, selected family chains and net aliases

The canonical FlatFSM executor admits v4 timer and read-only transfer predicates,
retaining explicit lifecycle mode and actual private/sample operand queries.
Each timed occurrence owns one UInt31 saturating elapsed register; timer expiry,
entry/reentry/recovery, pause, cancellation and reset use the existing transition
and sequential-update owners. Stream transfer is a sampled valid-and-ready
predicate, not new ready ownership or a protocol scheduler. Legacy v1-v3 remain
admitted. The fixed counter width is an explicit bounded-storage tradeoff, not a
minimal-area optimization claim.

Generated exports and members gain a closed optional family kind. A family view
retains the exact direct nested Generate and ordered actual selection prefix;
its read-only structural access does not invent or erase descendant storage
capabilities. The existing storage verifier bounds query depth at 128 and work
at 65536, verifies complete unique inventories and rejects runtime/escaped
family handles. No second family registry or sampled-lane expansion is added.
External selected writes, generated symbol views and target loops remain open.

Standalone resolved-net boundary aliases reuse the actual immediate-child
endpoint and existing resolved topology. They preserve exact contextual types,
resolution policy and real driver/read dependencies, without fabricating a
standalone domain constraint. Source carriers and resolved/inout target support
are separate remaining work. Sequential normalization now orders the bounded
late-control dependency span before capturing it, checks the original explicit
effect subsequence and fails transactionally if that order cannot be retained.
It does not hoist declarations across scopes or reorder unrelated late bindings.

Focused native acceptance is **987/987** cases: Sequential 89, Memory 116,
FlatFSM 94, composition 20, assignment guards 34, Interface 241, observation 25,
storage 165, structural-if 73 and shape/iteration 130. The Parameter executable
also exits zero, including its 23 named symbolic checks and earlier assertions.
The initial recovered run had two correctly rejected new negatives with stale
expected diagnostic text; only those exact assertions changed. Their final
89/89 and 241/241 reruns pass. Interrupted pre-restart runs are not counted.
Production manifests remain identical; the final copied compiler has SHA256
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
Commands and matched manifests are under `.work/f58-generated-normalization`.

All six maintained GeneratedArea inputs now normalize successfully, including
symbolic and concrete per-lane registers. Six scratch selected-family source
witnesses pass the full native gate, including three-level and separate-JAR
views. Four standalone-net controls accept the real read/driver case and reject
wrong mode, missing endpoint and feedback; its target conversion still refuses
resolved carriers explicitly. The actual timer-source and target demonstrations
have also passed on this compiler, but their source/API and maintained runner
changes are reserved for the next coherent checkpoint. No F58 parent/child box
is closed by this backend slice.

## Checkpoint 17 — reusable timing source and finalized hierarchy expressions

Public immutable/live `after` and explicit `FsmTransfer`/`awaitTransfer` bindings
capture the v4 contract from [SequentialState v0.8](../design-gates/NodalSequentialState-DG-v0.8.md).
An immutable transfer binding retains the actual Stream carrier; each machine
occurrence captures its own pure predicate after independently rechecking that
carrier. Reusing a binding never shares predicate ownership or ready drive
permission. Reports include the actual counter bits and timing/transfer facts;
the bridge recomputes ownership, thresholds and private storage independently.
Required-domain timers preserve conditional initialization rather than choosing
the first child's material reset policy.

Late-bound expression descriptors now carry a once-sealed internal intent rather
than publishing provisional formal types. The existing construction finalizer
resolves exact-copy, alias, unchanged-shift, field and complete-equality intents
in bounded dependency order after hierarchy binding. All internal readers use
the canonical final descriptor. Operation owners retain their existing semantic
rules; pending shape-changing inference rejects explicitly instead of guessing.
This is not a second type-inference engine or permission to use parameter defaults
as proofs. General pending arithmetic/shape-changing support remains open.

Nested generated Area source uses the actual parent family and existing lexical
captures, with exact ordinal dependencies placed at their legal dominating owner.
The source and bridge now agree with checkpoint14's independently bounded nested
body/local-update profile. External selected-family source views remain the next
separate slice; outer-induction-dependent bounds and guarded declarations are
not claimed here.

Focused shared-build acceptance is **92 passing groups** across three matched
batches: 67 timer/deferred/nested and directly affected expression checks, 23
lifecycle/composition/required predecessors, and two composed-timer/native-deferred
checks. Production API/plugin/bridge and test compilation passed. The 67-group
run was rerun after an executor restart; interrupted runs are not counted.
Two earlier timer test-fixture inventory/Option mistakes were corrected without
weakening their assertions. Source manifests match through each accepted batch.
The maintained deferred-native group gates four actual hierarchy expression
profiles on checkpoint16's frozen compiler.

Seven actual timing source profiles plus the composed-timer profile pass the
full native gate. **Five generated-SystemVerilog simulations pass**: three-cycle,
one-cycle, transfer wait, mixed-reset formal reuse and composed timed children.
They check dormant/terminal hold, exact elapsed edges, independent state, pause,
valid-only/ready-only stalls, read-only ready observation, cancel/restart priority,
registered All completion and occurrence-specific reset. Resetless startup values
are deliberately not asserted; maximum-threshold saturation is a native-model
boundary test, not a billion-cycle target run. The composed witness retains
three executors and eight private registers without Cartesian state expansion.
Replays and command/input/output/tool receipts are under `.work/f58-timing-source`
and `.work/f58-timing-target`, using compiler SHA256
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
Finite elaboration recursion, bounded runtime stacks and the remaining hierarchy,
resolved/inout and target-generation requirements remain open.

## Checkpoint 18 — selected family source views and standalone net boundaries

Generated Area collections retain typed, ordered selection prefixes through the
existing source capture, bridge and artifact owners. A family-valued member is
an exact read-only structural view of its direct nested family, not a runtime
payload, new family registry or cloned lane. Terminal storage retains its own
capabilities. Schema3 records family fields; storage-only schema2 producers remain
compatible, while old producers cannot invent family metadata. Named helpers,
grouped aliases, three-level selection and separate compilation use the same
checked path. External selected writes and target lowering of nested views remain
open, as do guarded declarations and induction-dependent bounds.

The public standalone inout selector now reuses the exact child endpoint through
a single canonical boundary carrier. Late formal element types seal after actual
instance binding; ordinary local nets still validate immediately. Reads, pure
expressions, conditional drivers and pass-through retain exact resolution policy,
placement, contextual element identity and topology. Bridge validation independently
checks the child relation; it emits a boundary alias rather than another physical
net. Joined-component split transport and resolved/inout target support remain
explicitly unfinished, not silently approximated.

Focused shared source acceptance is **39/39 groups**: selected families 7, nested
families 7, generated predecessors 14, standalone inout 5, actual native inout 1,
and artifacts 5. The native group gates five actual source modes and rejects a
cross-boundary combinational feedback cycle. The initial retry was interrupted
during compilation and is not counted; the recovered run uses the unchanged
source manifest. The earlier forged-parent negatives now fail at the stronger
exact-direct-export check, so only their diagnostic assertions were updated.
Five maintained selected-family replays also pass the full native gate on frozen
compiler SHA256 `3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
Commands, hashes and manifests are under `.work/f58-selected-standalone-source`;
the separate-JAR Area harness also passed its selected-family positive and typed
negative fixtures. No F58 parent or remaining required behavior is closed here.

## Checkpoint 19 — retained generated register loops and guarded family ownership

The existing CIRCT hierarchy adapter emits one helper module per actual generated
family template/material profile and a real `sv.generate.for`, not one module or
body per parameter value. It reuses physical port construction and sequential
register emission; logical captures and selected outputs retain actual template
identity. Symbolic loops remain symbolic, while concrete loops can explicitly
request `preserve-constant-loops=true`. The direct scalar-storage profile admits
static alternatives, per-lane enable/reset and exact output partitions. Nested
families, generated module/interface symbols, nonunit-stride adapters, symbolic
scalar widths and resolved/analog family endpoints remain required continuations.

Native lexical ownership also admits real family declarations and local member
selection inside exact structural-if arms. Bound captures follow the existing
region capture and Integer expression owners, retaining the original dependency
and rejecting raw uncaptured operands or escaped handles/storage. Independently
valid bounds are supported; predicate-refined existence, induction-dependent
bounds and public guarded-family source capture are not claimed by this slice.

Focused final target acceptance is **209/209**: hierarchy 29, sequential 149 and
symbolic 31. Storage **172/172**, structural **73/73**, and the Parameter executable
also pass. Several new target fixtures initially omitted required canonical
region/yield identities; those fixtures were repaired rather than weakening
verification. Final compiler SHA256 is
`83d54e05ab033664162048cd1071fea8a3b0a09db1c710447355179c0092aa53`;
pre/post production manifests match.

The maintained generated-Area target runner gates and lowers actual public
source replays, checks two module bodies and one retained loop/helper instance,
and simulates unchanged emitted SV at symbolic lane counts 1, 3 and 8 plus
concrete 3. CIRCT-Slang/arcilator checks independent lane enable/hold/invert/copy,
reset and output aliases. This is explicitly two-state, not startup-X evidence.
Invalid count 9 retains its range fatal and is refused by the pinned arcilator;
it is not reported as a successful simulation. Pinned Icarus rejects the legal
outer generate end label, so no SV rewriting is used to manufacture acceptance.
Five actual concrete-hierarchy Icarus predecessor waveforms also pass on this
same compiler. Receipts are under `.work/f58-generated-target/third` and `fifth`;
the maintained reproduction contract is [Generated Area target witness](../../tests/scala/areas/TARGET.md).
No broader target or F58 completion is implied.

## Checkpoint 20 — bounded immutable graph unroll

The additive [SequentialState v0.9 contract](../design-gates/NodalSequentialState-DG-v0.9.md)
uses the existing checked callback owner and immutable graph admission for
`FsmDef.unroll`. Exactly 0..127 elaboration steps retain the same enum evidence;
existing occurrence, depth and record budgets are checked before another step.
No callback survives into the graph or hardware. Direct guard/action/child and
typed transfer inventories expose existing immutable authored identities, not
name-based binding or a new graph registry. The distinct immutable-FSM callback
profile rejects runtime hardware, binding capture, mutation, unknown effects,
recursive helpers and nested unroll; imported helpers retain exact summaries.

Focused acceptance is **34 passing groups**: unroll 4, reusable 11, timed 9 and
composed 10. One reusable predecessor initially expected the older foreign-slot
diagnostic; canonical HEAD already included child slots in that message. The
exact assertion was corrected and its 11-group rerun passes, with the rejected
behavior unchanged. All production source hashes match across the batch; the
only changed test hash is that documented assertion. The maintained frontend
harness passes **13 checks**, including separate compilation, missing-plugin
rejection and finite/budget/effect boundaries.

Actual public unrolled source passes the full native gate, CIRCT lowering,
SystemVerilog export and Icarus waveform checks for three independent machines,
registered child completion, pause, cancel/restart and reset. The typed-transfer
unrolled source separately passes the native gate. These use frozen compiler
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`;
receipts are under `.work/f58-unroll-shared` and
`.work/f58-statecharts/unroll/promotion`. Finite elaboration is not runtime
recursion: explicit bounded call/return stacks and their overflow/underflow,
reset, report and target semantics remain required and in progress.

For precision, checkpoint18's accepted feedback negative is a parent-local
read-to-driver cycle on a child-boundary alias. A cycle arising only after
hierarchy occurrence union is a separate next regression, not implied by that
accepted result.

## Checkpoint 21 — exact guarded generated-member existence

`StaticPredicateModel` now supplies a bounded lexical Integer-ordering query to
`ShapeModel.resolveGeneratedOrdinal`. It reuses the existing guard Boolean
expansion and canonical Integer DAG comparison; no second predicate engine,
parameter enumeration or global type-bound refinement is introduced. The query
first uses existing all-legal facts, then checks actual enclosing structural-arm
predicates and their exact capture provenance. True/false arms, negation,
conjunction and universally sufficient disjunctions retain the same finite
work/depth budgets. An unknown alternative is not a proof. This initial query
proves exact compared DAG orderings, not arbitrary transitive constraint solving.

Native generated member admission can therefore select ordinal zero from a
zero-inclusive family envelope inside the actual positive-count arm, while
rejecting the opposite/inclusive-zero guard, a same-envelope foreign parameter
and an unproven later ordinal. Family/storage scope and capability checks remain
unchanged; a proof cannot make an escaped handle legal. Original retained bounds,
physical types and generated templates are never rewritten by the query.

Focused acceptance is **419 cases**: storage 182, assignment guards 34,
structural-if 73 and shape/iteration 130, all passing. The ten new storage cases
cover actual guarded existence and causal Boolean/identity failures; accepted
source manifests match. The complete compiler relink uses a full matching
native before/after manifest and is copied at
`.work/f58-guarded-family/n2b/compiler/nodalc`, SHA256
`fb333d6c71a70ff589bb23d3fed2b470832fda96e30b6895722f3733df75a033`.
This is native semantic acceptance. The public guarded-family source path and
its corresponding guarded-existence query are being integrated separately;
no source or target execution is claimed from these native cases alone.

## Checkpoint 22 — resolved reporting and occurrence-only feedback regression

Resolved-net snapshot reporting now builds a query-local identity index, visits
operation operands once, preserves authored effect order and sorts the same
canonical paths. This removes the repeated all-operation scan for every net
without adding a persistent topology registry or changing physical identity.
A 128-net fixture checks ordered reports and deterministic artifacts; no measured
speedup or exhaustive scale claim is made. Repeated selector lookup retains the
existing canonical registry pattern rather than adding a net-specific second
mutable index.

Focused acceptance is **25 unique passing groups**: standalone source 9, actual
native standalone 2, F54 Interface source 9 and artifacts 5. The first run passed
24; a new wrapped-foreign negative expected the later Interface diagnostic but
correctly failed earlier at exact sequential dependency ownership. Its exact
code/message assertion was repaired, and the complete nine-group rerun also
executed the descendant lexical-scope negative. Production manifests match;
no rejection was weakened or converted into an accepted path.

The new actual public-source cross-feedback case routes a boundary read into the
real child's payload input, so the cycle appears only after hierarchy composition.
It rejects with `NODAL-DIGITAL-055-010: hierarchy connection contains a causal
combinational dependency cycle`. Read-only, literal open-drain and open-source
boundary modes also pass the native gate on frozen compiler
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
Local, sibling, wrapped foreign and descendant capabilities retain their exact
owner checks. Logs, manifests and actual inputs are under
`.work/f58-standalone-followup`. Typed resolved Interface member carriers and
resolved/inout target emission remain separate required work.

## Checkpoint 23 — source guarded generated-family declarations

The existing generated-body record now retains its exact lexical parent separately
from nearest enclosing Generate ancestry. Source capture and bridge follow that
same canonical region/capture chain; guarded family declarations and local typed
selection do not invent another hierarchy. Bounds must remain independently valid
across their full envelope in this source slice. A maybe-empty family may be
declared without selection; predicate-dependent member existence is the next
source continuation against checkpoint21's native proof.

A narrow additive StructuralFamily effect-summary bit 128 admits checked family
factory/bind/map through direct and separately compiled helpers. It does not widen
old StructuralIf bit 8 or grant declaration authority to Pure, procedural-loop or
sequential callbacks. ImmutableFsm bit 64 remains distinct. Unknown/authored
certificates and forbidden effects continue to fail closed. The artifact retains
historical `parent_region` and conditionally adds `body_parent_region` for the
actual lexical body edge, preserving old bytes when that new fact is absent.

The integrated source batch passes **49/49 groups**: guarded 7, nested 7, selected
7, generated 14, unroll 4 and structural-if 10. The final artifact/guarded batch
passes **13/13**, with the seven guarded groups intentionally repeated after the
serialization change. Both production manifests match. The Area separate-JAR
harness passes seven lexical and five generated predecessor fixtures plus nine
new guarded/effect-metadata negatives; the typed helper positive remains actual
separate compilation, not a copied source-only example.

Four promoted public replays (guarded, nested, helper and empty family) pass the
full native gate on frozen compiler
`83d54e05ab033664162048cd1071fea8a3b0a09db1c710447355179c0092aa53`.
The independently compiled helper witness also passed that gate. Receipts and
source/classpath hashes are under `.work/f58-generated-areas/n2a/shared-promoted`,
with grouped source checks in `.work/f58-guarded-source-shared` and
`.work/f58-guarded-artifact`. Guarded-family target execution, module-defined family
selection under a later guard, induction-dependent bounds and generated symbol
views remain required work, not implied by this source/native acceptance.

## Checkpoint 24 — explicit bounded runtime call stacks

`FsmDef.withCallStack` atomically validates a same-enum closed fragment graph,
capacity 1–128 and independent overflow, underflow and invalid-frame policies.
Typed calls retain exact entry and resume states; returns have no invented fixed
destination. The existing graph/composition owners charge a bounded matching-return
summary before expansion. The native v5 layout retains ordinary/call/return edge
kinds, canonical private-storage roles and explicit policy records, not a second
runtime or an all-states return approximation.

The existing executor owns a minimal-width depth pointer and capacity times local
state-width frames, balanced reads and exactly one selected frame write. Frames
are not initialized; pointer reset, restart, cancellation, recovery and terminal
lifecycle make stale frames unreachable. AssertHold suppresses the faulting edge
and its entry/exit effects while retaining ordinary active-state behavior. Trap
performs real state entry and clears stale stack/completion state. Existing domain,
sampling, timer, composition and physical reset proofs remain authoritative.

Matched shared Scala checks pass **43/43 groups** (stack 9, unroll 4, reusable 11,
timed 9, composed 10). The integrated frontend harness passes **17 checks**, with
**8 actual source replay profiles**. Native checks pass **164/164 cases** (137
flat FSM and 27 composition), including 43 new stack and seven composition cases.
The native full production/test manifests match. The frozen compiler is
`5ec53036d0ccc2c9f14898e5d6e21c76693651b0dab564a75e0b19236d99375b`.

Three unchanged public-source to gate to CIRCT to SystemVerilog to Icarus profiles
pass: nested continuation/fault/action/lifecycle behavior; one definition in
independent reset-bearing and resetless clock occurrences; and composed child
registered completion/cancellation. AssertHold source gates, lowers and exports
with its sampled assertion intact. Pinned Icarus cannot parse that assertion;
no assertion is removed or disabled, and no AssertHold Icarus pass is claimed.
Its fault/action/lifecycle semantics are covered by the focused native cases.

Receipts are under `.work/f58-statecharts/stack/shared-accept`, native checks under
its `native` directory, and shared source counts/manifests under
`.work/f58-stack-shared`. The maintained demonstration is
`tests/scala/reusable-fsm/run_stack_target.py`; the contract is
[NodalSequentialState DG v0.10](../design-gates/NodalSequentialState-DG-v0.10.md).
Register-backed frames make no RAM or minimum-area claim. Stack Gray encoding
remains explicitly unsupported. Other required F58 hierarchy/interface work and
all parent acceptance boxes remain open.

## Checkpoint 25 — typed resolved views and source finalization

The exact prospective source tree matches all 370 Scala files from the retained
139-group integrated acceptance snapshot. Its 23 changed source paths add typed
resolved Interface views, guarded generated-family existence/naming and deferred
arithmetic finalization. Four reproducibility groups were additionally repeated
with the actual translator enabled and are not double-counted. The matching
source manifests and individual counts are retained in
`.work/f58-integrated-source`. This snapshot includes the previously accepted
bounded-stack source; it does not replace that implementation with an older one.

Resolved views preserve the original complete member witness, actual placement,
mode and domain authority through the existing capability and component owners.
Guarded existence uses actual structural predicates and capture identity without
changing global parameter bounds; generated Area names use actual family identity.
Deferred arithmetic reuses the canonical result-type intent and operation
inference, retaining width, divisor, assignment and forged-result negatives.
Seven resolved native profiles include six positives and the causal child-feedback
negative; five guarded-family exporters supply 24 default-gate inputs.

The corresponding concrete arithmetic target behavior is accepted separately,
not attributed to this source-only checkpoint. General symbolic arithmetic,
region-owned inference, generated Instance/Interface routing and remaining
physical boundary profiles are still required continuations. No F58 parent
checkbox is closed by this checkpoint.

## Checkpoint 26 — deferred scalar conversion and Struct values

Four source paths extend the existing bounded result-intent lifecycle through
canonical scalar-conversion and nominal Struct owners. All 372 Scala source files
in this prospective tree match the retained 31-group shared acceptance snapshot;
logs, individual counts and four actual default-gate replay profiles are retained
in `.work/f58-deferred-values/shared`. Parent ranges differ from child formal
envelopes, so the checks exercise real substitution rather than matching defaults.

Wrong nominal or equal-envelope foreign schemas, field order, width direction,
narrowing/sign, assignment and forged bridge results remain causal negatives.
FiniteReal width 1023 accepts and 1024 rejects in source, without a digital Real
target claim. The concrete all-values target does not pass: packed projection
and named-field saturation refusals remain explicit. This source checkpoint
therefore establishes neither that waveform nor region-owned/inferred-storage
lifecycle support. Those consumers remain separate required work where applicable.

### Accepted native prerequisite for family capture

The isolated native prerequisite snapshot matches all 223 repository source
entries of `.work/f58-generated-target/nested/third/source-before.sha256`
(digest `cc68ac0a`), identical to its after manifest. Seven changed native paths
are restored byte-for-byte from accepted source; unrelated files are preserved.
The retained compiler is `237a956f`. Focused Hierarchy 35, Storage 204,
Structural 73, DigitalType 114 and AssignmentGuard 34 checks pass separately.
Six shared family-capture source inputs pass its native default gate. These are
existing matched receipts, not newly executed tests or a whole F58 completion.

The ODS and tracked generated registry inputs are unchanged. Historical generated
build-directory headers and external transitive TableGen inputs are not
independently sealed by this receipt; fresh reproduction is not claimed. Later
prefix-map assertions and three additional negatives are not part of this exact
snapshot. Nested lane-distinct target behavior and broader pending profiles
remain open. No security-held consumer execution is resumed by preserving this
previously accepted source checkpoint.

## Checkpoint 27 — generated-family capture through structural choices

The exact 375-file Scala source inventory matches the retained 73-group shared
acceptance, with twelve changed source paths beyond the preceding scalar/Struct
slice. Counts include eight family-capture groups, seven artifact groups and
58 affected Area/Generate/structural predecessors. Six actual shared-compiled
family-capture replays pass the frozen native compiler's default gate. Exact
source manifests and counts are in `.work/f58-family-capture-shared`; replay
receipts are in `.work/f58-generated-areas/n2c/shared-promoted`.

The canonical ordered capture owner transports a closed non-Data family argument.
Original family identity and actual capture edge remain separate; artifact
`family_input` appears only when present, preserving earlier bytes otherwise.
The source checkpoint depends on the independently checked native family transport
implementation and does not itself establish generated-symbol or target support.
No represented lane expansion, alternate hierarchy graph or parent closure is
introduced. Later nested assignment and physical target results remain separate.

## Checkpoint 28 — bounded nested assignment images

The exact accepted native snapshot restores 12 paths and matches all 223
repository source hashes in `.work/f58-nested-images/third/source-before.sha256`
(`197e87fa`), unchanged after its focused run. Compiler `795f3ef4` passed
ShapeIteration 173, ShapeRegion 55, AssignmentGuard 41, InterfaceModel 254,
InterfaceObservation 25, HierarchyTarget 39, SequentialTarget 149 and
SymbolicHierarchy 31 checks, 767 total. These are retained matched results, not
a new run or a waveform claim.

ShapeModel retains canonical ordered outer iteration images and exact authored
projection types. Assignment coverage and guards check complete occurrence
ancestry. Unit-stride Cartesian coverage uses the existing partition proof;
nonunit images retain holes. Budgets depend on authored axes, expressions and
cuts, not lane products. Correlated/triangular maps, nested residue completion,
multi-axis flat re-radixing and mixed ordered-loop nesting remain unsupported.
The combined snapshot includes resolved boundary and target static-bound fixes.

Historical fixture failures and the interrupted second runner remain preserved;
only the immutable third runner is accepted. ODS changes are included in the
tracked source inventory; generated build-directory headers are not separately
sealed, so fresh generated-header reproduction is not claimed. Broader source
and target profiles remain separately required before Foundation 58 can close.

## Checkpoint 29 — shared resolved split and nested shape source

The exact 416-file Scala snapshot in `.work/f58-split-shape-shared`
(manifest `49f737b4`) is reconstructed with 15 changed paths and the matched
InterfaceHierarchy design gate. Its 56 named executed groups pass; retained
final `795f3ef4` replay checks six split and eight typed-member expected gate
outcomes, including causal feedback refusal, in two repeated groups. Native
prerequisites are now preserved by checkpoint 28. Complete before/after source
inventories agree. These are existing matched results, not a new execution.

The source slice preserves canonical generated shape and resolved-boundary
owners. Separate maintained target behavior is not inferred from these checks.
Target runners and TARGET.md are not changed by this checkpoint; a mismatched
historical target-document receipt is excluded rather than labeled exact.
Broader generated routing, analog and pending lifecycle work remains open.

## Checkpoint 30 — fixed named-field CDC payload adapters

The matched snapshot in `.work/f58-fixed-struct-cdc/final-candidate` retains
227 focused native checks (SequentialTarget 157, HierarchyTarget 39,
SymbolicTarget 31) on `93a1af6b`, with all 233 native source hashes matched.
Its 418-file source inventory adds the two exact named-CDC fixture/test files;
three source groups and three actual native gate inputs pass. The same inputs
produce retained Gray, handshake and FIFO four-state waveform passes, with
illegal-Gray and illegal-FIFO-stall monitor negatives. The exact runner and
contract are included; README adds only the accepted CDC section as fresh scoped
documentation, excluding unrelated arithmetic instructions. Historical failed runs
remain preserved. These results are retained acceptance, not new execution.

Canonical packed-to-named-field adapters preserve logical row-major layout and
reuse existing CDC state and monitors. The admitted profile is fixed payloads
up to 4096 bits, explicit named fields and actual fixed contiguous ShapeIndex.
Symbolic CDC, dynamic and noncontiguous selection remain unsupported. Existing
source and generated-header provenance limits remain explicit; this partial
checkpoint neither completes Foundation 58 nor resumes held consumer repairs.

## Checkpoint 31 — lexical deferred explicit-storage finalization

The exact 421-file Scala source snapshot in `.work/f58-lexical-shared`
(manifest `ca94c05a`) adds four paths after the CDC fixtures. Its 23 focused
groups pass: lexical 8, arithmetic 4, deferred hierarchy 5 and deferred values 6.
Eight actual direct/family/structural/when early-late gate replays use the now
preserved `93a1af6b` native prerequisite; each early-late pair has identical
input hashes. The exact contract addition is retained with its original hash.
These are matched historical checks, not a newly executed run.

Scope remains existing explicit-storage result finalization in canonical lexical
contexts. Pure collections, procedural loops, branch-result joins and inferred
storage lifecycles are excluded from this checkpoint and remain required where
applicable. No broader target or full pending-construction completion is claimed.

## Checkpoint 32 — coherent native occurrence and conservative integration

The corrected second integration snapshot in `.work/f58-native-integration/second`
restores 25 native paths and matches all 230 accepted repository source hashes
(manifest `e2b3e8d5`). Compiler `9fbcef7b` and translator `14df2009` passed nine
selected unit executables: 650 named cases plus the separately reported
conservative-connectivity and scalar-materialization units. Retained checks
also cover 10 actual gate outcomes, eight generated parameter overrides, five
analog profiles and ten causal analog negatives. Counts are not combined across
these distinct receipts. The corrected second run, not prior failing attempts,
is the accepted evidence; no tests are newly executed by this preservation.

Canonical hierarchy/domain owners retain actual generated occurrence provenance,
external physical-boundary proof and conservative Interface materialization and
closure. Existing CDC target source is preserved. Generated selected-prefix,
broader source and physical-target continuations remain separate required work.
Historical build-generated headers are not newly reproduced or independently
sealed here. Foundation 58 remains incomplete.

## Checkpoint 33 — conservative/external interfaces and deferred joins

The exact 393-file core/test snapshot in `.work/f58-external-join-shared`
(manifest `b10500d6`) adds 20 source paths after checkpoint 31. Its focused
combined 85 groups pass on native `9fbcef7b` and translator `14df2009`, now
preserved by checkpoint 32. Sixteen join gates include eight byte-identical
early-late pairs; eight external profiles and three conservative native groups
retain their distinct receipts. The smaller manifest excludes 36 examples by
filter, not deletion; all examples remain preserved. No broad predecessor
certification or fresh test execution is claimed.

Canonical Interface ownership adds scalar all-conservative capability and
bounded declaration-only resolved external signatures. Deferred structural
joins now retain complete ABI/capture authority: opposite-arm, poisoned capture,
escaping arm and unsupported contexts still refuse. Prior join-refusal coverage
is replaced by implemented early-late equivalence, not suppressed. The exact
InterfaceHierarchy contract and scoped join contract addition are included;
its historical private-overlay paragraph remains distinguished from the newer
shared 85-group receipt.

Conservative arrays, signal-flow execution, analog CIRCT, mixed-interface
erasure, broader generated/selected source and physical target completion
remain open. Foundation 58 is not complete and no F72 qualification is claimed.

## Checkpoint 34 — scalar resolved pins and true external target

The exact native snapshot in `.work/f58-resolved-target/final-candidate`
(manifest `13e3ad73`) changes two native paths and matches all 233 source hashes.
Compiler `459fbe76` passed 245 focused checks: HierarchyTarget 57,
SequentialTarget 157 and SymbolicHierarchy 31. Seven actual shared-source
four-state waveform profiles use the already preserved 393-file source
snapshot. Retained inputs, tool hashes and output receipts distinguish these
physical behaviors from logical source acceptance. No new consumer execution
is performed by preserving this checkpoint.

The exact runner is included. Only the scalar-resolved sections of the accepted
ABI contract and README are appended as fresh scoped documentation, preserving
prior joins/CDC text and excluding unrelated missing arithmetic-runner material.
The admitted profile is fixed scalar resolved physical pins and true external
declarations. Split, generated and symbolic physical boundaries remain open
at this checkpoint; no paired ordinary-assignment substitute is introduced.
Foundation 58 remains incomplete.

## Checkpoint 35 — body-local generated child source

The exact 404-file source/fixture inventory in
`.work/f58-generated-occurrence-shared` (manifest `9a18e6da`) adds 11 core Scala
paths and nine frontend fixtures. Retained acceptance covers 75 groups, nine
frontend refusals and 14 native cases on `9fbcef7b`/`14df2009`. Those native
verifier prerequisites are preserved by checkpoint 32; later scalar target
acceptance is separate and is not relabeled as this source replay. The exact
frontend runner and only the scoped body-local GeneratedAreas contract section
are included, preserving existing unrelated documentation.

Existing InstanceRecord generated scope, constructor ownership and final type
obligations retain actual body-local child inputs/outputs and physical bindings.
Ordinary Scala loops remain elaboration-only. Guarded controls, exported and
selected Instance/Interface prefixes, lane-indexed clocks/resets and generated
child HDL remain separate required continuations. No actual target behavior or
Foundation 58 completion is claimed by this source checkpoint.
