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
are recorded separately. All F-058 children were open at this initial baseline;
later child acceptance is recorded in the checkpoints and authoritative checklists.

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

## Checkpoint 36 — selected prefix and guarded control proof

Six native paths reproduce the exact 206-file snapshot in
`.work/f58-selected-control-shared/final/before.json` (`8c7fc2ae`), identical
to its after inventory. The filter omits 27 unchanged predecessor build/catalog/
registry/tool files; their hashes were checked against the complete previous
233-file inventory and preserved. Compiler `660d55cb` passed Storage 212,
ShapeIteration 173, Sequential 99 and SequentialTarget 157 cases, 641 total,
plus ten selected/symbolic gate outcomes and four guarded normalize/default-gate
profiles. These retained results do not imply rerunning every preceding suite.

Canonical selected occurrence-prefix and physical control provenance remain
owned by existing Shape, storage and sequential models. Explicit zero checks
prevent unsigned budget exhaustion wrap while preserving depth 128 and work
65536. Broader source/selected Interface, physical targets and pending lifecycle
continuations remain open. No new consumer execution or whole F58 acceptance
is claimed by this native checkpoint.

## Checkpoint 37 — selected dependent-family source domains

Eight source paths reproduce all 398 hashes in
`.work/f58-selected-source-shared/source-before.json` (`69402774`), with matching
after inventory. The filter excludes nine frontend fixtures already preserved
by checkpoint 35; none are deleted. Retained shared checks cover 91 groups in
11 suites and ten actual native replays on `660d55cb`, matching checkpoint 36
prerequisites. The selected-domain contract section is recovered from a retained
later baseline and appended as scoped documentation; its private-stage counts
remain historical and are not substituted for this shared receipt.

Canonical structural Parameter binding and selected ancestry provide exact
dependent-family domains without per-value cloning. Digital-loop predecessor
checks retain provenance/category, eight-hex generated suffix and repeatability
invariants rather than a stale hardcoded suffix. Guarded/selected child
Interface and physical target continuations remain separate; this checkpoint
does not complete generated hierarchy execution or Foundation 58.

## Checkpoint 38 — fixed scalar resolved split transport

The exact 233-file native snapshot in `.work/f58-split-target/shared`
(manifest `0c4e7758`) changes two target/test paths. Compiler `6813d044` passed
Hierarchy 67, Sequential 157 and Symbolic 31 checks, 255 total. Five actual
four-state profiles cover down/up/sibling/reused/variant routes; three local
split and seven physical predecessors remain separately recorded. Exact
standalone fixtures and runner are included. ABI/README sections are composed
freshly from accepted split text while preserving joins/CDC and excluding
unrelated arithmetic-runner references. No tests are newly executed here.

Existing transactional resolved-split facts and occurrence parent links retain
original data/enable sidebands through canonical logical ports, without reading
back the physical pin or adding state/latency. Relative ABI variants preserve
independent actual drivers. Fixed scalar Bits/UInt/SInt only is admitted here;
symbolic, array and generated routes remain required continuations. Unchanged
emitted SystemVerilog and four-state behavior are not electrical certification.

## Checkpoint 39 — guarded generated physical capture source

The matched 403-file snapshot in `.work/f58-guarded-domain-shared`
(manifest `13502109`) adds nine core source paths plus five exact frontend
fixtures and their maintained runner. Shared acceptance retains 54 groups,
26 native inputs, 12 domain replays and 14 frontend stages on `660d55cb`;
these counts remain separate. Existing nine frontend fixtures are preserved.
Only the guarded-capture GeneratedAreas contract section is composed into the
current gate. Later domain fixture extensions are explicitly not part of these
results, and no current target compiler is substituted for the historical one.

Canonical isolated structural-arm and constructor capture ownership preserve
physical clock/reset provenance for guarded children. Positive-step and
dependent domain source cases retain actual lexical bounds. Selected/exported
Interface routes and actual generated HDL remain separate required consumers.
This partial checkpoint is neither new test execution nor Foundation 58 closure.

## Checkpoint 40 — generated-domain target witness prerequisites

The exact three-file fixture/test/replay extension in
`.work/f58-generated-domain-extension` matches its complete 403-file inventory
(`4dadbe93`), with seven groups and 13 retained full native gates on `660d55cb`.
This source extension precedes the later generated-domain target receipt: its
13 waveform inputs use source hash `e596957f`, not the earlier guarded-domain
fixture. No production API change or target acceptance is inferred solely from
these source checks. The remaining selected-domain target profiles retain their
separate original source identity.

### Maintained nested target runner prerequisite

The exact runner `ffdbb137` from
`.work/f58-generated-target/nested/final-candidate/accepted-files` is retained
before the generated-domain runner, which imports its `digest` and
`pin_offsets` helpers. It has only standard-library imports and a guarded main.
Both maintained fixture hashes already match the historical receipt in
`nested/maintained-final/manifest.json`. On compiler `795f3ef4`, direct symbolic
1/3/8, concrete 3 and nested 2x3/3x2/4x4 profiles pass with unchanged SV; range
controls retain refusal at nine lanes and 5x3. This is matched historical
two-state evidence, not a current-compiler rerun or four-state claim. The
mismatched historical TARGET.md copy remains excluded; no fixtures are changed.

## Checkpoint 41 — maintained generated-domain target

The exact native snapshot in `.work/f58-generated-domain-target/shared`
(manifest `002bbbaa`) changes two target/test paths and matches all 233 hashes.
Compiler `8fa475e1` passed HierarchyTarget 71, SequentialTarget 157 and
SymbolicHierarchy 31 cases, 259 total. Sixteen maintained profiles passed 47
configurations through unchanged SV, Slang and arcilator, explicitly two-state.
Thirteen inputs match the checkpoint 40 domain extension; three retain selected
source `0915a24f`. Exact runner helper dependencies precede this checkpoint.
The runner is byte-identical; TARGET.md includes only freshly composed domain
text, not the mismatched historical nested-document copy.

Real reusable family/structural-arm helpers preserve nonzero/nonunit positive
steps, empty/maybe-empty and captured/dependent/guarded domains without lane
cloning. Canonical selected-prefix existence governs use; no default-width or
maximum-lane substitution occurs. Symbolic operation support remains exact-type
bitwise/alias/Bool select with uninitialized resetless storage, not general
arithmetic/resize/reset literals. Generated child Instance/Interface, descending
domains, resolved/analog routes and four-state startup remain separate scope.
These are retained matched results, not new consumer execution or F58 closure.

## Checkpoint 42 — managed optional CIRCT capability profile

The ten exact files in `.work/f58-circt-overlay/shared-validation` manifest
`31b4498b` preserve the optional parameterized-inout repair profile, explicit
source/patch identity and managed install/verify/CMake checks. They precede the
bounded symbolic resolved consumer. Historical shared acceptance passed 36
Python checks, baseline and repaired-profile CMake checks, tampered identity
refusal and managed verification. The baseline profile and native target source
were unchanged. Four retained lock/bootstrap support paths match their inputs.
No installation, download or target execution is performed by this snapshot
commit; later dirty managed-profile changes remain separate.

The exact upstream unified patch has two required blank context markers at
lines 61 and 69. Those single-space lines are retained to preserve patch
semantics and its verified hash; all other staged whitespace checks pass.

## Checkpoint 43 — bounded symbolic resolved physical transport

The matched `.work/f58-symbolic-resolved-target/shared` manifest `60bc4bc4`
pins 248 inputs. All 246 non-composed inputs match this index; two documents
retain scoped sections only. Two native files and the standalone runner are
exact historical accepted bytes. The managed compiler `cb7381a1` and library
`12478d2a` passed 267 native cases (79 hierarchy, 157 sequential, 31 symbolic),
seven fixed and five symbolic unchanged-DUT four-state profiles at widths
2/7/8. Baseline capability refusal and a managed compiler with baseline-library
override refusal are separately retained. These are historical matched results,
not a new execution or full Scala run. Input hashes remain pinned by the
maintained-input manifest: two typed-resolved and three split replays.

True bidirectional scalar Bits/UInt/SInt transport reuses verified component
proof and the optional managed profile. Resolved Interface arrays, generated or
selected physical routes, and broader conservative/electrical profiles remain
open. No replacement of electrical connectivity with paired assignments occurs.

## Checkpoint 44 — managed structural-consumer prerequisite

The 12 exact historical accepted source/test/profile files in
`.work/f58-circt-consumer-next/managed/shared` preserve the optional bounded
structural profile, before any later consumer work. Their before-hashes match
the preceding commit; the symbolic target `4b5f3676` and inout v1 source/patch
manifest remain unchanged. Stored source manifest `cee4be2c` matched before and
after. Existing bootstrap executable metadata is retained. Unified patch blank
context markers are preserved verbatim and checked independently.

The original receipt records 55 Python checks, five SSA/graph two-state probes,
seven private-generated-target configurations and five symbolic-resolved
four-state profiles at widths 2/7/8. Private generated-target behavior does not
promote that target here. Baseline dominance/late-lvalue failures, incompatible
inout types and missing-output CMake refusal are retained causal evidence.
This commit only preserves accepted source and receipts; no new consumer
execution, installation or retry of the separate held repair occurs.

## Checkpoint 45 — coherent managed symbolic-consumer profile

Thirty exact files from `.work/f58-circt-successor-profile/acceptance.json`
(`28c750fa`) preserve the optional six-component profile. Every changed-file
before-hash matches the preceding structural profile; runner imports and test
module prerequisites are present. No target admission, installed prefix mutation
or held comparator execution is part of this commit.

Historical acceptance includes 51 manager checks (42 unchanged plus nine new),
41 compiled translation units, ten symbolic verifier cases, six native API
cases, 266 raw-bit values, five generate four-state profiles and nine inout API
cases with two refusals. Profile identity pins builder `435a799e`, patch
`e516eead` and installed descriptor `ecca89d2`. Unchanged SV is consumed.

The predecessor keeps its ID, source/patch inventory and semantics. Its builder
checksum is deliberately refreshed to the modified canonical installer; stale
installed identity is refused, never silently relabelled. Exact prior manifest
and receipt remain saved. The 42 predecessor manager checks and source/ordered
compile-command/recipe identity parity do not claim a new v1 native install:
compilation was mocked for recipe comparison, and output comparisons used the
previous installed binary. Actual new native build/live checks apply only to
the successor. Required unified-patch blank context markers remain exact.

## Checkpoint 46 — coherent native query and physical target composition

This atomic native prerequisite uses all 341 authored compiler paths from
`.work/f58-selected-dependencies/shared-native-composition-accepted` manifest
`a5baeb47`, overlaid only with the explicitly accepted unified target
`12a1d607` and six maintained Unit files (CMake, hierarchy and four new suites).
Forty-one paths differ from the preceding commit; no prior compiler source is
deleted. The six generated Ops evidence entries remain ignored, not source
files; their exact bytes match the unified build. Supplemental Dialect/Types
headers map to the recorded authored ODS inputs in generated-provenance.json.

Query compiler `d59b274b` historically passed 16 focused native executables; its
260 unique CLI runs/270 expectations are separate baseline evidence, not reruns
on the later target. Unified compiler `fbe93efe` retains dialect `d2da1a96` and
managed descriptor `ecca89d2`. Its seven direct C++ suites pass 318 cases:
86 hierarchy, 157 sequential, 31 symbolic, 20 generated occurrence, seven
selected-child Interface, ten direct Interface and seven typed-fill. These
embed native IR and have no Scala runner dependency. The corrected hierarchy86
receipt, not the stale 71-case earlier log, is authoritative.

This commits a matched semantic/target composition, not the stale query target
alone. Canonical occurrence and logical-port owners remain shared. Source
fixtures, maintained runner and waveform closure are deliberately deferred to
the next source checkpoint; no fresh source, target or consumer execution is
claimed here. Dependent payload target admission and broader electrical
profiles remain open. F58 is incomplete.

The accepted occurrence test contains one two-space blank line inside its
embedded raw MLIR fixture (line 68). It is preserved as exact tested input; all
other staged whitespace checks pass. No semantic test assertion is relaxed.

## Checkpoint 47 — coherent source ownership and array prerequisites

The exact 108-file source engine in
`.work/f58-generated-occurrences/source-coherent-final/accepted` manifest
`f54a0979` is preserved with its actual fixtures, not an assumed unchanged
403-file test inventory. Forty-four paths change: 23 engine paths, seven
predecessor fixtures, six selected/resolved-array maintained files and eight
global-Interface initializer fixture/runner files. All bytes match the accepted
source manifest. Other omitted examples/tests remain untouched.

Historical strict API/plugin/bridge compilation passed. Eight maintained
resolved groups, six selected groups, 97 affected predecessor groups, six
initializer stages/eight runtime outcomes and 29 native-exported inputs used
query compiler `d59b274b`. Frontend/integration classes were reused, not freshly
rebuilt as a complete testkit. Eight ordinary and eight selected byte-parity
cases pass; three dependent artifacts differ only in checked source locations
and derived origin IDs. Counts are separate scopes, not an additive total.

The accepted record also retains 19 checked-empty initialization and seven
guarded-ordering outcomes; their scratch-harness maintenance is audited
separately, not presented as a new maintained suite here. The unified native
checkpoint precedes this source, but its older body/selected waveforms used
distinct recorded source stacks. Only matching resolved-array fixture bytes
are asserted; no blanket source-to-target rerun is implied. Public array rank
limits, broader selected/generated electrical admission and dependent payload
target work remain open.

## Checkpoint 48 — durable regression fixture sources

Seven exact accepted scratch fixtures are now retained under the domain
hierarchy fixture tree, with a bounded recipe explaining isolated replay cwd,
the two separate ordering compilations and expected 19+7 historical outcomes.
This is byte-preserving source retention with disclosed path relocation, not
new maintained-harness acceptance or a rerun. A future harness must verify
changed source-location/origin identities. No obsolete combined-ordering or
positive-weak-Or assumptions are copied.

## Checkpoint 49 — maintained generated-target replay tools

Ten exact files from the unified target manifest `e1ab313e` retain four
generated-occurrence/selected-instance source+replay fixtures, four maintained
runners and their two scoped guides. Both guide class references are present.
The guarded-selected runner's only nonstandard import is supplied by the exact
selected-instance runner; the others use the standard library. Resolved-array
fixture bytes already match checkpoint 47. The runners consume supplied IR and
do not silently recompile a different Scala stack.

Historical unchanged-SV suites are reported separately: 22 body-local, 12
selected-instance, four guarded-selection and 25 resolved-array configurations.
Their compiler is the unified `fbe93efe` composition. Body exports used the
earlier guarded-control source stack; selected exports used API `d5960770`,
bridge `656ac392`, plugin `1d720ab5`, all recorded with exact source hashes.
These are not asserted to be fresh compilations of checkpoint 47's 108-file
source engine. Selected Interface/direct/typed-fill runner source mappings
remain separate; their missing fixture provenance is not hidden by this slice.

## Checkpoint 50 — coherent external-array native and target boundary

Eleven exact files from `.work/f58-external-array-target` combine the nine-file
native receipt `4e0f15ce` and two-file target receipt `2b57a2c0`. All recorded
base hashes match checkpoint 49. The native 341-authored-input inventory and
generated supplements were independently audited; its older target/test and
CMake are not blindly restored. The accepted target explicitly extends unified
`12a1d607`; current CMake and four generated target suites remain present.

Historical native acceptance passed 16 focused executables including 545
Interface cases. Compiler `9ec28e55` passed 91 hierarchy, 157 sequential and
31 symbolic target cases, three authored external-array four-state profiles
and the existing nine ordinary source profiles across 25 configurations.
These are separate scopes; this snapshot does not grant public external-array
source admission or broader generated electrical behavior. Native/target
owner and dependency identities are matched rather than inferred from the
subsequent analog-only probe. No new compiler or consumer execution occurs.

## Checkpoint 51 — retained lexical Interface identity fixtures

Four exact maintained source/test/replay fixtures from
`.work/f58-generated-occurrences/lexical-interface/frozen-forward-identity`
(manifest `2952b238`) are retained. Historical acceptance used API `dbc8d005`,
bridge `72c3664a` and plugin `b17f58fc`: nine source groups, nine exports and nine
positive native gates, a duplicate-identity negative and eight ordinary parity
cases. These counts are not retroactively attributed to checkpoint 47's later
source engine. Endpoint identity ownership is already retained there, but no
new whole-testkit compilation is claimed. The broader selected Interface target
runner's nested/nonunit/refusal input mapping remains separately audited.

## Checkpoint 52: composed native query and conservative adapter proof

This checkpoint promotes twenty native owner changes and five matching query
regressions without replacing the hierarchy, Shape, Parameter, Collection or
conservative-connectivity owners. Selected occurrence and capture/type queries,
non-unit generated conservative endpoints and selected-child materialization
retain bounded proofs and causal refusals. The checkpoint-50 digital target
source is unchanged.

A fresh serialized combined-link run passed thirteen affected query suites and
323 direct target-IR cases: hierarchy 91, sequential 157, symbolic 31, occurrence
20, selected Interface 7, direct Interface 10 and typed fill 7. Query observations
were ShapeRegion 75, ShapeIteration 173, Storage 223, AssignmentGuard 41,
Collection 110, DigitalType 186, Interface 545, Observation 25, StructuralIf 95,
Hierarchy 314, Boundary 56 and Crossing 273. Parameter reported constructor 38,
dependent-bound 7 and 23 symbolic pass lines; these are not invented into an
unreported whole-suite total.

The composed conservative adapter passed 125 executions: 111 retained parity
rows, verify/emit pairs for five public-source profiles, duplicate refusal,
adjacent budget 49912 success / 49913 refusal and transactional refusal. All
five Verilog-A outputs byte-match the accepted public-source supplement,
including two distinct selected ordinals 1 and 2. This is adapter-first emission,
not numerical simulation, external-parser acceptance or general digital target
qualification. Source-generation fixtures follow in their own checkpoint.

The exact link reused accepted objects and archives; it did not recompile the
target. Seven changed headers were audited as additive declarations or new
standalone types, and thirteen ABI-facing/generated files matched exactly.
Overlay precedence, configured Version provenance, all link input hashes and
source mappings remain recorded. Combined nodalc SHA-256 begins `8e5da176`,
probe `9f3cc30b`; the immutable acceptance receipt is
`1209b36ced9ca16c2146a391068638b4030578232c4737ed7f331aadea92ffdf`.
The unchanged seven target and eight other query test sources were checked
against the predecessor; the five changed query sources are included here.

No firtool, comparator, held consumer repair, CI or full suite was invoked.
Pending geometry lifecycle, broader generated/selected Interface routing and
remaining physical/backend profiles stay open. Foundation 58 is incomplete.

## Checkpoint 53: matched source reduction and conservative capture closure

The accepted pre-C1 source stack is now preserved on the composed native
checkpoint. All 108 API/bridge/plugin/annotation/resource inputs were verified;
24 engine files changed. Five maintained Scala source/test files and six exact
standalone fixtures retain their causal checks and separate compilation scopes.
No experimental pending-construction epoch changes are included.

Historical source acceptance used API `b16d1bbc`, bridge `1ebe7884` and plugin
`8d5aab70`: 28 selected-child source/bridge expectations; 26 retained reduction
checks; 11 A/B lifecycle primitive checks; three conservative groups; and
separate 14 reduction plus five conservative byte-identical export comparisons.
The selected first/control fixture pairs were freshly compiled on that stack.
The retained maintained suites reused their previously compiled test JARs with
final API/bridge substituted, not a fresh whole-testkit compilation. The fixture
guide records original stacks, isolated pairs and relocation limits.

The original source receipt's pending-native and repeated-connection-identity
warnings are superseded only for the five exact public profiles by supplement
`59ba2c8c` and checkpoint 52's fresh composed adapter replay. Two distinct
selected ordinals 1 and 2 emit byte-identical accepted Verilog-A. The authored
connection ordinal remains owned by ConstructionSession; this is not a new
connectivity engine or blanket cross-boundary alias authority.

The source stack preserves pending-width/capture inference and canonical
generated conservative views within their checked profiles. Full pending
outer/inner reduction geometry and callback lifecycle remain open. Internal
A/B prerequisites do not admit those callbacks. Broader selected conservative
profiles, remaining generated/physical routing and held consumer work remain
open; no CI, global suite or numerical/backend qualification is claimed.
Foundation 58 remains incomplete.

## Checkpoint 54: maintainable adapter regressions and fresh source compilation

The conservative adapter selection is now represented by maintained source
templates, assert-counted generators, an exact probe source, explicit expected
results and a focused Python runner. The runner accepts a supplied probe and
public-source output directory; it imports no historical receipts and searches
for no predecessor binary. Generated data stays below ignored root `.work`,
probe invocations have a 60-second timeout, and large adjacent-budget inputs
are generated sequentially and removed. There is no new native build-target
or fresh probe-compilation claim.

Nine generation/expectation controls passed, including import isolation,
mutation anchors, refusal/transaction checks, timeout cleanup and disk reserve.
Fresh coherent-source replay passed the same 125 executions across 81 exact
inputs with ten structural checks. This overlaps checkpoint 52, not another
125 feature cases. The 111 predecessor checks and four duplicate/budget checks
retain their exact expectations. Full public input/output hashes are pinned
separately for default `coherent-source53` and explicit `historical` profiles;
no comment or correspondence metadata is normalized away. The source assets
total 1,038,579 bytes; logs, binaries and padded budget files are not committed.

A separate fresh source53 check also closes its retained-test-JAR limitation.
Four strict compilations and six runtime groups passed on the exact accepted
API/bridge/plugin, with 135 pinned inputs unchanged and coherent source lookup:
pending reduction 26 observations; conservative three maintained groups plus
five public exports, eight causal and six bridge refusals; selected first
positive plus 28 controls; and 11 no-plugin A/B lifecycle controls. The existing
combined native probe verified/emitted all five fresh selected profiles.

Relocated coherent source lookup changes MLIR provenance and Verilog-A comments
(including authored induction identity instead of a fallback identity). Those
outputs are deliberately not called byte-identical to historical results. All
five Verilog-A non-comment line sequences are identical, while the maintained
current profile additionally pins exact full output bytes. Old output evidence
is preserved separately. No assertions were weakened or stale test JARs counted
as fresh compilation.

C1 epoch/closure work remains private and unaccepted; pending geometry and the
remaining Foundation 58 feature/backend scope stay open. No CI, full suite,
firtool or held consumer action was invoked.

Whitespace check limitation: the exact hash-pinned historical MLIR templates
retain 122 blank-line trailing-space findings and five terminal blank lines in
42 files. The complete diff check reports these known fixture-byte exceptions;
the non-template diff check passes. No whitespace checking configuration was
disabled and no expected input hashes were silently regenerated.

## Checkpoint 55: selected conservative literal parameters

The existing selected-child occurrence/profile proof now admits ordinary,
dimensionless finite Real/f64 literal defaults and actuals. ConstructionSession,
immutable Interface transport and native ConservativeConnectivity independently
check admission; ParameterModel, instance verification and scalar materialization
continue to own parameter meaning, constraints, binding and substitution. No
second evaluator or parameter registry was added. A local immutable name set and
MLIR SymbolTable avoid repeated membership/body scans; expected per-query work
is linear in child operations and bindings, not a measured speedup.

The immutable boundary additionally rejects duplicate parameter attribute keys
before map conversion. Causal tests showed that unsupported-first defaults,
units and classifications previously disappeared through last-wins conversion;
eight both-order cases now refuse, preserving earlier canonical diagnostics
where applicable. Generic parameter transport is not relaxed or rewritten.

Focused source/native acceptance passed on an exact 115-file source lookup root:
three physical roots with defaults 2/1 and distinct actuals 3/7 and 11/13; six
source refusals plus recovery; eight other forged snapshots; duplicate binding
transport and native rejection; and eight duplicate-attribute cases. Actual
Verilog-A retains the child's parameter-dependent equation, instance overrides,
a nonunit generate loop and eight endpoint correspondence records. The authored two-child star topology
still verifies but fails physical emission transactionally. It differs from the
accepted direct leaf-to-leaf wiring, so this does not establish a child-count
restriction.

The maintained explicit-input runner and nine authored Scala fixtures passed
six fresh Scala mains and 25 native invocations, plus six runner controls for
output/input safety, exact mutation anchors and timeout behavior. The three
old-native comparisons remain historical evidence, not maintained dependencies
or additional current-profile cases. All 125 directly affected predecessor
executions passed unchanged. These counts overlap development checks and earlier
checkpoints; they are not a sum of newly implemented features.

The runner accepts explicit matching overlay/support artifacts, compiles fixture
paths within the supplied canonical source root, and checks their bytes against
the maintained bundle. It records ordered inputs without claiming to prove a
prebuilt binary's source provenance. Generated outputs remain under ignored
root `.work`. Source relocation changed full metadata as expected; full diffs
are retained, and three physical bodies matched when metadata comments were
excluded solely for comparison, never by rewriting output. All nine newly
authored fixtures subsequently passed the pinned formatter and the six-main,
25-native-call replay passed again on the formatted source root. Formatting
changed source spans and induction metadata; full raw diffs are retained and
historical byte parity is not claimed for that successor.

See [the maintained focused runner guide](../../tests/scala/domain-hierarchy/SELECTED_CONSERVATIVE_PARAMETERS.md)
for commands, artifact composition and assertions. Independent scoped review,
production/fixture Scala and new native-hunk formatting, document and diff checks passed.
The full native formatter still reports 207 inherited replacements, each mapped
to the predecessor; none remains in the new hunks. A narrow native rebuild after
11 whitespace-only corrections produced a byte-identical object/probe and all
25 replay outputs matched. No CI or full qualification ran.
The profile remains single-level, fixed, nonempty and scalar with literal
selection; parent-reference/expression actuals, explicit native override forms,
arrays, nested/symbolic families and the fixture's shared-scalar star topology
remain open. C1 and broader Foundation 58 completion are not claimed.

## Checkpoint 56: bounded internal construction epochs

ConstructionSession remains the single owner of incremental preparation, epoch
tickets and final publication. Its existing queues now discover later work,
validate region/signature and authored identity freezes, retire stale snapshots,
and avoid replaying completed work on a second drain. Final-name validation
failures in the guarded closure poison that owner before publication. Static
query snapshots are not emitted metadata: final snapshots retain sealed counts
and types, while unsupported post-freeze changes are refused rather than
automatically resealed. The checkpoint-55 literal-parameter admission is retained.

Canonical Interface layout, enum, reduction, structural-query and semantic-origin
consumers share the same unchanged cumulative 65,536 work allowance. Source lookup,
reads and parsing use an invocation-local snapshot/cache with owner accounting
and restoration on failure. Ordinary/no-owner behavior remains inert. This
reuses existing consumers and registries instead of adding a second construction
or inference engine. Empty drains still pay integrity-scan costs; opaque iterator
execution time and arbitrary same-size filesystem rewrites are not bounded or
detected merely by charging element visits and checking file attributes.

Focused acceptance covers 12 incremental runtime groups, including discovery,
Area/generated members, epoch identity, descriptor work and shared meters, plus
final-name poisoning and isolated capture accounting. The maintained runner
compiled 17 formatted fixtures. Its expanded Interface fixture initially refused
at the work limit; reviewed selective imports shortened the same two real port
calls without changing operations, provenance, fees or assertions. After that
leaf alone was recompiled into the same output, all seven maintained groups
passed, including counts 2/3, second-batch publication, no reappend, stale tickets,
recovery and work/cache controls. This is composed fresh-17 plus targeted-1
evidence, not a successful exit of the original failed all-suite invocation.

A separate authentic Session/materializer control exhausts inside captureSite
before origin append, poisons publication, retires its query and proves fresh
recovery. A larger i64 Interface fixture really captures its parent Integer but
naturally refuses final preparation; successful i64 publication is not claimed.
Source bytes, discovery paths and parsing remain real charged work. The preserved
formatter-expanded refusal makes that sensitivity explicit, not a path-independent
capacity guarantee or evidence for 65,536 records.

Original checkpoint-53 comparison remains 24/25 raw matches because two old
parameter-diagnostic expectations became stale in checkpoint 55. A separate
runner successor changes only those expectations: exact 55 and this composition
both pass with five byte-identical outputs, including recovery. Three actual
checkpoint-55 literal/default/two-child source runtimes also pass against the
composed API and matched bridge, reusing their existing classes. This is runtime
compatibility, not fresh fixture compilation or additional native acceptance.
These checks overlap earlier evidence and are not summed as new feature counts.

All seven production Scala files and 17 maintained fixtures passed the pinned
formatter. Independent contract/source and focused evidence review cover the
exact 73 API compile inputs plus 24 bridge dependencies. Commands, source hashes,
failed attempts and compact receipts are retained locally. The previous scratch
43 MiB cap was exceeded; current allocation fits the revised 46 MiB cap and
235 MiB free-space reserve. An earlier rebuild peak was not sampled, and its
conservative coexistence estimate is not proof of historical cap compliance.
See [the maintained runner guide](../../tests/scala/domain-hierarchy/BOUNDARY_CLOSURE_C1.md)
for explicit inputs, cases and limits. No CI, full qualification or new target
consumer run was performed. Public pending-geometry callbacks, context/provenance/
source-order scheduling, nested callback admission and the rest of Foundation 58
remain open. This is an internal prerequisite checkpoint, not increment completion.

## Checkpoint 57: checked shared-anchor physical identity

The canonical ShapeModel mapper now has a named one-way extension from a checked
generated occurrence to an invariant scalar projection of a module-owned root.
It retains authentic relation/projection checks, supported direct Generate scope,
source inverse and round-trip proof, and transactional result assignment. It
never guesses a generated lane by inverting a shared scalar. The legacy public
mapper keeps its ABI and behavior; there is no second graph or layout engine.

ConservativeConnectivity first preserves the existing traversal, shortcuts, edge
order and terminal results. Only Unknown continues into two bounded searches;
Equal requires an exact physical-point intersection, not shared component/root
membership. Both searches spend the original remaining work allowance and a
cumulative state ceiling including the legacy traversal. Deduplication and
intersection checks are charged. The existing limits remain 16,384 work and
1,024 states. Enhanced varying-map work is larger (367 versus 497 in the measured
case), and left exploration can exhaust before an intersection is reached; this
is a bounded proof-availability limitation, not permission to increase limits.

Nine authored causal profiles distinguish topology from child count: a one-child
star already failed before this change, whereas one/two-child chains passed.
All 18 candidate verify/emit calls pass, preserving the 15 previously successful
outputs byte-for-byte. The actual rejected pair was the first child's generated
p[1]/n[1] selectors. Singleton and different-ordinal shared-anchor controls also
pass, while separate/isolated anchors remain distinct. Thirteen mapper runs
exercise 1,796 checks, including provenance, malformed/foreign projections,
reverse refusal, holes/endpoints, output immutability and exact operand capture
preflight. The latter is not captured-Generate admission.

A separate instrumented test-only source records 111 budget/state queries:
legacy chain success at 1,792 and refusal at 1,791 remain unchanged; the shared
star succeeds at 5,429, with explicit dedup/intersection exhaustion below it and
a cumulative six-state/five-state boundary. Instrumentation only lowers limits
and is absent from production. Fourteen emitted-source outputs pass 479 topology
and parameter assertions; these are source/output checks, not simulation.
Predecessor evidence remains 120 exact matches plus five audited shared-anchor
expectation changes, not 125 unchanged passes. The earlier maintained run remains
23 exact plus two obsolete two-child refusal expectations.

The maintained successor now freshly compiles all nine fixtures and passes six
Scala mains plus 28 native invocations against the checkpoint-56 API, matched
bridge/plugin and exact native candidate. Its two-child positive checks retain
gains 3/7, default/equation, nonunit occurrence normalization, real shared-top
aliases and correspondence inventory. Independent fixed-override backend failures
replace the obsolete transaction expectation; all existing mutation controls
remain. An initial bundled/source formatting mismatch correctly refused before
compilation. A private matching bundle then passed without weakening that gate.
See [the maintained guide](../../tests/scala/domain-hierarchy/SELECTED_CONSERVATIVE_PARAMETERS.md).

Invariant scalar projections can have an array root. A minimal same-coordinate
authored control verifies and compares Equal; a different-coordinate raw API
control stays Unknown, never falsely Equal, and its full module is refused. This
is not complete alias classification, child-array support or new array backend
admission. Floating-terminal rejection also remains separate from alias proof.
Invalid diagnostic-printer and null-parent test-harness attempts are retained
as invalid evidence, not successes.

Independent code/evidence review and pinned new-hunk formatting pass. Formatting
rebuilds produce identical objects and executable; inherited unrelated findings
remain explicit. Original 73 link inputs and 480 dependency hashes stay unchanged,
including existing CIRCT dependencies: this is Nodal source work, not CIRCT-free
execution or the held comparator/firtool consumer repair. Compact source/recipe/
receipt retention and sampled allocation preserve the 36 MiB owned cap and
235 MiB reserve. No CI or full qualification ran. Pending geometry/context/source
order, broader selected/backend profiles and the remaining Foundation 58 work
remain open; this is a partial checkpoint, not increment completion.

## Checkpoint 58: maintained mapper contract controls

The focused mapper controls are now maintained as a standalone source/fixture
bundle with an explicit-existing-probe runner. Thirteen corrected inputs cover
legacy and invariant-anchor mapping, nonunit coordinates, holes/endpoints,
provenance/refusal, failure immutability and bounded work. The runner checks all
1,796 assertions, exit status and exact output hashes. These are the same mapper
cases already reported in checkpoint 57, not an additional semantic acceptance
count or a new broad qualification suite. The runner's fresh replay passes all
thirteen against the accepted executable.

Pinned formatting of the proposed C++ source passes. A single-TU compilation
using the same flags/includes wrote only to anonymous memory; its 71,960-byte
object is byte-identical to the previously accepted object. Thus the unchanged
executable composition remains matched without relinking or retaining another
object/binary copy. Historical absolute-path build commands, library hashes and
execution receipts stay in ignored scratch; only maintained source, fixture
expectations, runner and portable dependency guidance are committed.

The controls deliberately parse raw inputs without whole-module verification.
They do not establish backend admission of unsupported raw profiles. In
particular, captured-operand preflight still returns Invalid and scalar-to-lane
inversion remains refused. Mapper state-limit controls are not connectivity
state-ceiling evidence. Initial invalid harnesses and unrelated later array-root
experiments are excluded. See [the focused guide](../../tests/compiler/fixtures/increment58/shape-anchor-mapper/README.md)
for these limits and matching build requirements. The small handoff retains
278,528 allocated bytes, within its revised 384 KiB allowance; sampled native
owned allocation remains below 36 MiB, with borrowed preexisting source inodes
reported separately and the 235 MiB free-space reserve preserved. No CI, target
consumer run or held CIRCT repair was performed. Foundation 58 remains incomplete.

## Checkpoint 59: bounded internal singleton construction prerequisite

Registered construction intent now uses the existing Session closure and retained
result cell to seal a root singleton and publish its canonical shape-index side
record. Unregistered, late, non-root and nonsingleton requests remain refused;
callbacks are not invoked. This is an internal C2a prerequisite, not public
pending-outer reduction admission. No second importer or scheduler is introduced.

Construction-only snapshot, naming and publication accounting preserves the
65,536-unit budget, existing validators and source/reflection/descriptor fees.
The exact publication profile is root-local singleton shape inspection in one
external synchronous-reset domain with checked canonical metadata and control
identities. Other profiles retain coarse accounting. Charges describe structural
visits, copies, comparisons and explicit string probes, not universal JVM hashing
or CPU bounds. Scoped accounting restores on failure and poisons the owning
transaction and stale tickets rather than permitting partial publication.

The maintained runner freshly compiles twelve formatted fixture sources and
passes four groups: fifteen singleton controls, six accounting controls, five
naming controls and three publication/failure controls. Separate retained-class
compatibility passes seven C1 groups, the five naming controls, twenty-six pending
controls and eleven lifecycle controls. All fourteen pending outputs are raw
byte-identical to baseline; these compatibility runs are not fresh consumer
compilation. Explicit legacy JVM constructors and the reflection entrypoint are
preserved. The final API compiles 73 sources with 24 pinned bridge dependencies.
See [the maintained focused guide](../../tests/scala/domain-hierarchy/REDUCTION_CONSTRUCTION_C2A.md).

Earlier registry, budget, syntax, compatibility and provenance failures remain
recorded as failures. A natural helper relocation preserves the original authored
callsite; no output normalization, span spoofing or weakened assertion was used.
The causal publication control separately injects monotone budget exhaustion in
a real export scan and proves restoration, poison, refusal and fresh recovery.
Diagnostic counting comparisons are not hardware-capacity claims.

The final matched build used a 44 MiB owned cap and a 246,808,576-byte free-space
reserve. Verified raw-plus-archive coexistence was 44,855,296 owned bytes with
252,043,264 bytes free. Complete archive verification preceded raw-output cleanup;
sampled measurements do not establish an unobserved continuous global peak.
Sources, rebuild recipes and concise receipts are retained for compacted outputs.

C2b capture/context/source-order/final-name lifecycle and C2c multi-element
invocation remain open. Public zero, invalid and nonsingleton callback semantics
are not newly enabled; pending inner geometry is unchanged. Foundation 58 remains
incomplete. No CI, broad qualification, held target consumer repair or publication
retry was performed.

## Checkpoint 60: private root request provenance and restoration

The existing root-singleton construction owner now retains private request
context and allocation-frontier metadata. Every genuinely new canonical
declaration or expression identity receives one allocation stamp; reference
reuse does not restamp. Shared manual and queued completion validate canonical
input/root/lexical identities through charged, stack-safe dependency traversal.
The frontier does not replace existing cycle, orphan, type or boundary proofs.
Unknown potentially value-bearing metadata remains unsupported.

Context installation is enclosed by restoration even when installation fails.
Restoration does not roll back allocations, source identities, budget, registry
entries or sealed payloads. A partial failure poisons the owning transaction and
invalidates its tickets. This slice supports only the already-admitted ordinary
root context, not generalized installation of a different lexical context.

The maintained runner freshly compiles two formatted sources and passes thirteen
focused controls. A real charged failure after installation but before the
completion body verifies restoration, unchanged retained identities, exhausted
budget, poison, stale-ticket refusal, nonpublication and fresh recovery. Thirteen
separate retained-class predecessor groups pass, including the 29 C2a controls,
C1 closure controls, 26 eager/pending controls and eleven lifecycle controls. All
fourteen emitted outputs remain raw-byte-identical; retained compatibility is
not fresh predecessor compilation. Existing post-seal failure coverage remains
green, and legacy signatures and original source anchors are preserved. See
[the maintained context guide](../../tests/scala/domain-hierarchy/REDUCTION_CONTEXT_C2B1.md).

The matched build pins 73 API sources and 24 bridge dependencies. Initial recipe,
type-inference, local-class warning and maintained-runner preparation failures
remain recorded, with narrow repairs and no warning suppression. Verified
raw-plus-archive coexistence was 24,760,320 owned bytes with 248,688,640 bytes free,
within the 32 MiB stage cap and 246,808,576-byte reserve. Exact archive-size
admission and complete verification preceded raw cleanup; samples do not prove
an unobserved continuous peak.

Generalized lexical context, selected-proxy authority, late-source/final-name
ordering, callbacks and nonsingleton construction remain open. Public pending
outer geometry is not enabled, and Foundation 58 remains incomplete. No CI,
broad qualification or held publication/target-consumer action was retried.

## Checkpoint 61: private bound-domain construction safeguards

This checkpoint preserves independently accepted groundwork, not complete direct
Instance-port construction. Canonical request provenance and a one-use registration
permit protect the fixed private selector. A narrow root External Sync domain and
one Required child occurrence use actual preparation, snapshot and publication
accounting. Existing domain/control reconstruction and validation still execute;
raw eligibility is separate from post-resolution identity checks. Unsupported
profiles retain their coarse path, and failed derived invariants poison the owner.
The procedural owner proves the exact retained module pair is empty without changing
its existing singleton observer. No second hierarchy or connectivity engine is added.

Source capture may avoid an unused stack suffix only for a newly borrowed meter
owned by this exact registered-construction Session and builder. Existing cached
source identities must prove the first located constructor; an earlier unknown key
retains the original walk. Every visited frame and decision remains charged, and
normal source resolution, byte rereads, lexical selection and API provenance remain
unchanged. This is removal of actual unused work, not a fee discount or new cache.

The maintained runner freshly compiles its two fixture groups and passes four
mains: sixteen synthetic selector cases, actual owner/callback/nesting/failure
gates, source freshness checks, and three actual bound-phase exhaustion controls
with fresh recovery. Scope restoration, poison, stale-ticket refusal and retained
registrations are asserted. Preparation exhaustion occurs at the first real debit
before map mutation. Source freshness uses a private source-reader seam, and live
gate tests deliberately exhaust the first frame; they are not full hardware
completion. Sixteen separate retained-class predecessor groups pass, with all
fourteen output files raw-byte-identical. Consumers were not recompiled.

An identical-class diagnostic preserves seventeen raw lines across the previous
and optimized APIs: sixteen capture sites and the complete semantic-origin result.
Both full Session executions still fail. Separate retained-child procedural-state
rejection and complete module-vector parity assertions pass before their later
finish refusal; module-vector equality is not whole-emission equality. See the
[maintained groundwork guide](../../tests/scala/domain-hierarchy/reduction-groundwork-c2b2a/REDUCTION_GROUNDWORK_C2B2A.md)
for exact scopes, inputs and readable pending fixtures.

The complete seventeen-case direct-port witness remains an expected-positive
capacity failure at the unchanged 65,536-unit ceiling during final canonical
naming. It is excluded from the green runner, not reclassified as supported
refusal. Public callbacks, generalized context installation, late naming,
parameter/alias type authority and full C2b/C2c remain open. Proposed removal of
unused metadata suffix computation is separate from this source snapshot.

All 97 effective source inputs are pinned. The matched prefix build measured
55,848,960 owned bytes at verified raw-plus-archive coexistence, within the 62 MiB
cap and 246,808,576-byte free-space reserve. Sampling does not prove an unobserved
continuous peak. Three completed predecessor archives were compacted only after
source, recipe, manifest and dependency verification; their historical recipes
require restoration. Compile warnings, fixture/recipe mistakes and each genuine
capacity refusal remain recorded honestly. Foundation 58 is incomplete. No CI,
broad qualification, publication retry or held target-consumer action was run.

## Checkpoint 62: avoid unused semantic-operation suffix work

A four-line change in the existing semantic-origin owner defers pure suffix
computation that metadata operations never consume. Construction-exact mode
charges the lazy cell and all suffix work whenever a consuming branch executes.
FIFO, pipe, delay and operation- or owner-based FSM branches retain the same
values. Legacy mode forces evaluation at its original point before branch probes.
No source reads, freshness checks, naming passes or budget coefficients are
removed, and no stem, locale, digest or cross-invocation cache is introduced.

The maintained runner freshly compiles three sources and passes all six resolver
cases. These deliberately API-classified fixtures assert real captured spans and
API identities, but are resolver-unit diagnostics rather than authored hardware.
Complete in-process result equality, generated categories, unused metadata work,
used-suffix failure, nested restoration and legacy failure order are checked.
Counting-callback sentinels are not whole-Session exhaustion evidence. Twelve
serialized-result and legacy-charge lines match between baseline and successor
using identical compiled fixtures; serialization orders maps and sets without
omitting values. Lazy evaluation may add an exception frame, so identical helper
stack bytes are not promised. Sixteen retained predecessor groups and fourteen
raw output comparisons pass; those consumers were not recompiled. See the
[maintained suffix guide](../../tests/scala/domain-hierarchy/dead-operation-suffix/DEAD_OPERATION_SUFFIX.md).

All 97 effective inputs match the committed source closure. Verified raw-plus-ZIP
coexistence measured 51,400,704 owned bytes within the existing 62 MiB cap, with
442,441,728 bytes free above the unchanged reserve. Archive verification preceded
raw cleanup; sampled measurements are not a continuous-peak guarantee.

The unchanged direct-port witness now completes final naming but still exhausts
the 65,536-unit budget during actual publication relationship-index grouping.
This remains an expected-positive capacity failure, not an accepted refusal.
No relationship-index reuse or budget change is included. Full direct-port
publication, generalized contexts, public callbacks and Foundation 58 completion
remain open. No CI, broad qualification or held action was retried.

## Checkpoint 63: bounded private construction diagnostics

The existing Session and construction kernel now support an explicit, early-bound
private diagnostic policy with a fixed 131,072-unit allowance. Activation requires
the actual registered carrier, context, allocation stamp, legal operation kind and
checked callback from the same Session and root. Authority checks are charged
before the canonical owner initializes and receives its exact accumulated debt
once. Binding alone grants no semantic admission. The default cumulative limit,
legacy entry and pre-owner debt limit remain 65,536; independent local limits are
unchanged. There is no refill, automatic promotion or revival after poisoning.
The diagnostic identity remains available for terminal reporting without changing
public snapshot schemas.

The matched API compiled and packaged successfully. The maintained runner freshly
compiled three sources and passed three mains: 18 admission labels (one donor,
16 controls and fresh recovery), actual publication-scan exhaustion, and actual
129-level descriptor-export depth refusal. They check exact debt transfer,
no-refill behavior, scope restoration, stale queries, poison, preserved registry
state and fresh recovery. The depth witness is an additional exporter input, not
registered deep hardware or evidence for the separate 65,536-node bound. Successful
activation is demonstrated through the explicit entry; automatic no-request refusal
is tested, but successful automatic-policy activation remains unverified. See the
[diagnostic policy guide](../../tests/scala/domain-hierarchy/diagnostic-work-policy/DIAGNOSTIC_POLICY.md).

Fifteen retained predecessor groups pass, and all fourteen raw output comparisons
match. The sixteenth guard initially failed because its reflection helper selected
an overload by name alone. A helper-only name, arity and unique-match correction
passes against both APIs with the original consumer class unchanged; the public
JVM descriptor also matches. This is repaired-helper compatibility, not an
unmodified sixteen-group pass. Retained consumers were not freshly compiled.

The original seventeen-case positive remains unaccepted. Its default policy still
exhausts during publication, with changed failure timing recorded. The diagnostic
policy reaches a genuine undriven child-input refusal instead. A separately named
driven witness is uncompiled and excluded. The donor's 32,409-unit debit is not a
measurement of that witness. No default-capacity or full direct-port publication
claim follows from these diagnostics. Foundation 58 remains open.

All 97 effective source inputs match this checkpoint. Verified raw-plus-archive
coexistence used 55,910,400 bytes within the 62 MiB stage cap; 437,063,680 bytes
remained free above the unchanged reserve. Fixture and compatibility growth later
reached 4,558,848 bytes, exceeding its 3 MiB suballowance despite staying within
the whole-stage cap. This overrun is retained honestly; only separately allowed
compact finalization followed. Measurements are not a continuous-peak guarantee.
No CI, broad qualification, publication retry or held consumer action occurred.

## Checkpoint 64: driven input and automatic-owner diagnostic witnesses

Tests and documentation only: the production API and all 97 effective source
inputs are unchanged from checkpoint 63. A separately named driven witness adds
one real public assignment from the existing completed singleton scalar to its
selected child-input proxy. Canonical assignment operands, domain, source ordinal,
source map and final snapshot linkage are asserted. Metadata-only exact accounting
continues to reject this operation, so the existing coarse fallback remains active.
No driver count is fabricated and no hierarchy or CDC validator is relaxed.

The maintained runner freshly compiles four sources and passes two mains. The
driven main passes the seventeen original cases on this valid topology plus one
separately named calibration. Full publication uses 117,839 diagnostic units;
calibration finishes using 118,409. Both fresh Sessions execute selection through
the same callsite after identical paid observations. Its measured 11,182-unit cost
allows the negative to debit monotonically to one unit short of completion. It
then fails at the actual final registration charge, retaining exactly one stamped
proxy and source entry but no binding, with zero balance, stale queries, restored
scopes, poison, no rollback and fresh recovery. Earlier calibration failures are
retained rather than counted as successful post-registration tests.

The second main proves automatic owner and policy activation during normal finish
after manual singleton precompletion. It checks the exact canonical shape-index
projection, result identity, policy tag and debt, with zero callbacks and 25,448
units used. It does not demonstrate automatic pending-cell completion. The earlier
assertion expecting an unused value in reachable digital expressions was corrected
to its actual shape-index representation without changing hardware.

See the [driven diagnostic guide](../../tests/scala/domain-hierarchy/fixtures/driven-diagnostic-policy/DRIVEN_DIAGNOSTIC.md).
The fixed 131,072-unit policy remains diagnostic-only. The default 65,536-unit
positive remains unaccepted, and the original undriven witness remains invalid.
No exact-accounting gate, budget, public callback or context admission changed.
The source partition and changed diagnostic setup are recorded; no equality with
the original default workload is claimed. Foundation 58 remains unfinished.

The complete fixture slice used 2,060,288 allocated bytes within its separately
approved 2 MiB allowance, while the existing 62 MiB stage cap and free-space reserve
remained intact. No API rebuild, CI, broad qualification, publication retry or held
consumer action was performed.

## Checkpoint 65: exact accounting for one canonical InstanceInput assignment

Scoped accounting groundwork extends ConstructionSession's existing bound-domain
proof for one finalized registered singleton driving its actual root InstanceInput.
The canonical driver, request, declaration, literal type/domain and complete ordinal
inventory are proved before exact work. Existing preparation, naming, validation
and exporters still run with newly live work charged at their existing owners.
Unknown shapes retain coarse fallback; a post-admission mismatch poisons rather
than replaying or refunding. No duplicate registry/engine, higher limit, validator
bypass or public callback admission is introduced. Zero-assignment and domain-free
fees retain their old branches. This is the reviewed narrow shared-layer design.

Nine freshly compiled groups pass 25 focused suites, including the driven 17 cases
plus calibration, raw structural/ordinal exclusions, exact post-admission poison,
actual exporter exhaustion/restoration/stale-query/no-rollback/fresh recovery, six
exporter seams and complete snapshot parity. Same compiled baseline/candidate full
snapshots are byte-identical at 11,653 bytes, including final source-map assembly;
matched accounting costs are 123,636 versus 88,366, not a CPU speedup claim. Six C2a
controls, an organic domain-free three-assignment witness and automatic precompleted
activation have identical observed old/new fees on identical anchored lookups.
Sixteen retained groups and fourteen raw outputs also pass/match; retained classes
were not freshly compiled and use the previously accepted exact-arity helper.

The maintained runner requires correctly typed repository markers and hash-pins
build.mill. Earlier markerless scratch projections silently selected the real
repository; their receipts are preserved but not used as projected-source evidence.
Anchored fresh replays establish this checkpoint. Historical costs are not silently
rewritten or equated across source locations and observation routes. See the
[single-assignment accounting guide](../../tests/scala/domain-hierarchy/single-assignment-accounting/SINGLE_ASSIGNMENT.md).

The unchanged 65,536-unit driven positive still fails the assignment descriptor
proof, with five units remaining in the separately retained final run. Diagnostic
131,072 remains diagnostic-only; default admission, automatic pending-cell
completion, broader C2b/C2c and Foundation 58 remain open. The current increment
does not affect generated Verilog-*: only accounting eligibility/charges change,
and complete captured hardware snapshots match. No new generation claim is made.

The final maintained replay stayed below the 62 MiB owned-stage cap with more than
246,808,576 bytes free; sampled measurements are not a continuous-peak guarantee.
No API rebuild during this recovery, CI, full qualification, publication retry or
held consumer action occurred. All source changes remain locally checkpointed
while the explicit publication denial remains in force.

## Checkpoint 66: operation-local prepared source capture

The canonical Session now pairs the two source captures for an eligible ordinary
bound-domain digital assignment through one private builder-owned, single-use
prepared result. It retains the complete first source site and immutable decoded
source observations, not resolved names or a drain-wide cache. Existing ordinal
allocation and Session/origin append order remain unchanged. Consumption validates
exact builder/module/kind/operand-vector identity, rereads bounded source input,
compares stamps and decoded lines with actual work charged, then appends the origin.
A failure after Session append poisons the canonical owner without rollback or
publication. Observable between-capture source changes now deliberately reject;
the earlier independent captures did not promise this stronger rejection.

Reuse requires the exact registered-construction owner, a real successful source
observation, no text-discovery/span fallback, and a positively terminated constructor
prefix of at most 80 frames. First capture and noneligible second capture retain
the original call depth. Independent emitted-code review proves the old second
path adds four visible frames, so an eligible constructor remains within 96.
Classification/normalization instructions are unchanged. A provisional wrapper-based
design was rejected before build because it perturbed the fallback stack boundary.
No whole-drain cache, second owner, budget increase or public API is introduced.

The unchanged compiled whole fixture on one identical anchored projection has
byte-identical complete snapshots; paid whole-route work falls from 88,526 to
83,489 units. This is a 5,037-unit accounting reduction, not a CPU speedup claim.
Twenty-five retained runtime suites pass, with full snapshot and C2a/domain-free/
automatic-precompleted fee pairs byte-equal. Eleven new freshly compiled controls
cover identity/reuse/freshness failures and actual assignment exhaustion before
capture or during paid decoded-line comparison, including exact ordinal/append
order, poison/stale queries/refused publication and fresh recovery. Synthetic
corruption seams are identified separately from actual public-assignment cases.

Actual first-source/full-origin provenance matches across ordinary and nonreusable
constructor-prefix cases and controlled cache/source absence. A separate compact-name
fixture reaches depths through 110 under unchanged diagnostic capacity; emitted
non-tail recursion proves the constructor lies outside the 96-frame window at
depth 96. Its shorter names serve only stack-boundary validation, never whole-route
capacity acceptance. These boundary fixtures intentionally stop after capture;
they do not establish successful final publication. Original long-name
depth-80-and-higher exhaustion receipts
remain failures, not parity passes.

The maintained bundle passes three fresh strict-warning compilations, 38 suites
and 12 exact-output pairs. Its shared runner also passes a fresh legacy fee suite
with unchanged default manifest selection; all six new Scala fixtures pass formatting.

See the [prepared-source guide](../../tests/scala/domain-hierarchy/prepared-operation-source/README.md)
for focused fixture and runner recipes.

The unchanged default positive still exhausts during final semantic-name source
reading with 165 units remaining on its separately anchored route. Diagnostic
131,072 is not production capacity. Default admission, automatic pending-cell
completion, broader C2b/C2c and Foundation 58 remain open. Measurements from
different source projections are not interchangeable. No Verilog generation
change is claimed; complete captured snapshots match.

The 97-source API build was archive-verified with full raw/archive coexistence
of 62,308,352 bytes below the 65,011,712-byte stage cap and with the existing
246,808,576-byte free-space reserve. Reconstructable compaction retained source,
recipes, failure/diagnostic receipts, both baseline APIs and current raw outputs.
No CI, full qualification, publication retry or held consumer operation ran.

## Checkpoint 67: bounded eager FSM scale and determinism

Three maintained ordinary eager-construction profiles extend the representative
F-058.F evidence: one stack leaf at capacity 2, four parallel leaves at capacity 8,
and depth-three binary composition with four leaves at capacity 32. They capture
1/5/7 machine occurrences, 2/32/128 frames and 6/80/280 stack-storage bits. Exact
parent/slot/owner-case topology, unique declaration/control/local/stack ownership,
authored clock domain, register types and stack arithmetic are checked. Each
profile compares complete ConstructionSnapshot values and raw representations
across two fresh Sessions; only counts, sizes and hashes are retained.

Architecture review reuses the existing FsmDef, FsmChild, FlatFsmGraphContract and
canonical capture/storage owners. There is no new registry, evaluator, production
code, public API or resource-policy selection. These fixtures use no reservation
or reduction path and assert that construction boundary work is inactive. Earlier
standalone-composition and owner-case display-name assumptions failed as fixture
errors; corrected checks require canonical absent metadata and authored case IDs,
without weakening production behavior. Failed receipts remain preserved.

Strict-warning compilation and all three profiles pass; a fresh replay through
the existing manifest runner reproduces identical snapshot hashes. Independent
review verifies the fixture and pinned inputs. Direct per-Java-process observations
are 193324/220124/222512 KiB maximum RSS and 1.329/1.971/2.420 seconds elapsed,
including two captures per process. These are environment-specific observations,
not limits, comparative speedups or performance guarantees. The maintained wrapper
is not a per-Java measurement and is excluded from those claims. See the
[bounded eager FSM guide](../../tests/scala/reusable-fsm/fixtures/scale/README.md)
for the shared-runner recipe and source-pinning requirements.

The fixture-only allocation remains below 2 MiB; sampled combined owned allocation
is 53,723,136 bytes, below 65,011,712, with 435,728,384 bytes free against the
246,808,576-byte reserve. No API/native rebuild, CI, full qualification, publication
retry or held target-consumer action occurred. The current increment does not
affect generated Verilog-*: only maintained construction tests and documentation
change, and no generation or target-execution claim is made.

This accepts only representative eager capture/storage/determinism evidence.
F-058.F and Foundation 58 remain open, as do default 65,536-unit admission,
automatic pending-cell completion and broader C2b/C2c. Diagnostic 131,072 remains
diagnostic. A proposed explicit finite production opt-in is pending a separate
public-contract decision; it is not implemented or inferred from these tests.

## Checkpoint 68: direct-parent ordinary Real forwarding

This bounded F-058.B continuation extends the existing generated Instance.param
path without a new public API or capacity decision. The canonical Session admits
an exact direct reference to an ordinary dimensionless Real declaration belonging
to the enclosing parent; the immutable bridge independently checks that original
owner inventory, declaration attributes and unique binding/formal identity.
Existing literal child defaults and selected scalar one-level conservative
restrictions remain. Source identity is never inferred from names, path prefixes,
equal defaults or a second registry.

Native review found that generated override admission has two semantic consumers:
ParameterModel and StructuralIf. A first candidate reached the latter's unchanged
Integer-only gate and failed a genuine positive. The correction introduces one
non-evaluating, operation-target-aware ParameterModel query used by both owners.
It proves exact override/instance/child-formal linkage, a direct parent-body f64
ConstParameterRef to an ordinary unitless Real declaration, and reference order
before the family. Its native scope is an otherwise verified one-level Generate
directly inside a Module, not only selected Interfaces or literal-bound families.
StructuralIf retains its attribute whitelist, operation verification, exact
hierarchy boundary and original Integer all-lane fallback. ParameterModel's Real
exception bypasses only that Integer proof and falls through existing evaluation,
normalization, fixed/default/constraint and canonical-binding checks.

ConservativeConnectivity remains an independent trust-boundary check rather than
calling a whole-model/Interface verifier or an evaluator. It requires the existing
selected topology and literal child defaults, exact ordinary parent reference,
unique symbolic formal and no equal or unequal literal/SSA collision. Canonical
Verilog-A expression rendering and direct scalar materialization remain the
existing downstream owners. The additive header declaration changes no existing
signature, layout, inline definition, include or macro. The matched targeted native
rebuild/relink retains existing exports and excludes stale archive implementations;
this is checked object/header lineage, not a blanket claim that any header edit
permits partial rebuilding. Eight inherited ParameterModel unused-variable warnings
remain; the build is not warning-clean. No performance improvement is claimed.

The maintained source selection includes six production paths, four changed/new
Scala fixtures, the selected portable runner and guide, the extended existing
probe and its guide, and a sibling ten-fixture/twelve-invocation native supplement.
The other eight Scala fixtures are byte-identical to checkpoint 67. Existing
readiness text is preserved, and no mixed-worktree source is substituted. See the
[selected parameter guide](../../tests/scala/domain-hierarchy/SELECTED_CONSERVATIVE_PARAMETERS.md)
and [native supplement](../../tests/compiler/fixtures/increment58/parent-real-forwarding/README.md)
for reproduction and the explicit matching-artifact contract.

Focused evidence is composed, not a complete corrected runner pass. Eight retained
Scala mains and 49 native prefix calls passed in the candidate-2 replay before a
harness diagnostic mismatch. Those exact preserved classes were freshly compiled in the earlier
V3 run, not recompiled for candidate 2. Eight corrected selected refusals now pass
with exact stage diagnostics, nonzero nonsignal status and empty target output,
completing the 57-call selected semantic composition. A separate native supplement
passes all twelve invocations, including general non-Interface forwarding, causal
default constraint fallthrough, generated-Integer predecessors and deterministic
named/materialized scalar forwarding. One additional diagnostic-only normalization
passes, separately counted from the 57 selected and twelve supplement calls
(69 semantic native checks overall).

The corrected tail directly loads the maintained selected runner's mutation
function, verifies exact retained bytes for seven inputs, and changes only the
ineffective ownership carrier for the eighth. Source comparison retains its other
semantics while correcting observed diagnostic stages and strengthening empty-target
assertions. The portable supplement changes only scratch-receipt provenance setup;
its CASES and full invocation/assertion tail equal the executed script. These
source-equivalence checks do not establish complete execution of either adapted
portable runner. No fresh candidate-2 Scala compilation or full C++ unit-suite
execution is inferred from retained classes or reused fixture sources.

Earlier failures remain evidence: initial source/plugin/expectation mismatches,
the real StructuralIf admission omission, later diagnostic-stage mismatches and
an ineffective forged-owner fixture. The last placed unknown ownership keys in
ConstParameterRef's inherent-properties dictionary, where generated conversion
discarded them; successful emission from that input is not counted as a refused
ownership mutation. The separate normalization witness confirms that both unknown
keys disappear while the same parent reference still feeds the override. The
corrected discardable-attribute carrier is retained in the actual override operand
source and now rejects at selected Interface admission with empty target output.

The source positives preserve actual symbolic Verilog-A forwarding for parent 5
versus child 2, two reused children with parent defaults 5/9, and distinct parent
identities with equal defaults 5/5. Constraints inspect defaults, not every legal
Real assignment. Parent expression defaults remain native-supported without new
Scala expression-default transport; expression actuals remain unsupported. The
block-argument case is an operation-profile refusal and symbolic fixed formals
fail selected Interface admission, while literal fixed transactional witnesses
remain separate. No unsupported case is relabeled as a deeper proof.

This bounded result preserves all earlier historical failures and checklist
states. Foundation 58, F-058.B.1/B.2 and broader dependent work remain open. No
ExtendedAPI, production-capacity/default-budget change, CIRCT comparator/firtool
pipeline, install, CI or full qualification is included. Publication remains
explicitly held; no denied operation is retried. Generated logs, receipts, targets
and archives remain ignored scratch artifacts.

The final corrected-test sample uses 29,474,816 owned bytes and 83,218,432 combined
bytes, with 404,107,264 bytes free. These are sampled observations, not continuous
peaks or the later source-finalization allocation. The frozen draft remains intact;
the final flat documentation successor is separately admitted at no more than
224 KiB within unchanged 32 MiB owned and 94 MiB combined stage caps and the existing
246,808,576-byte free reserve. The reviewed source increment is 1.5 MiB, including
both document versions and bounded finalization receipts; no production or fixture
source is duplicated.

## Checkpoint 69: bounded parent Real expression actuals

Local prototype acceptance passes for this existing-API F-058.B continuation.
The approved
[construction gate](../design-gates/NodalAnalogHierarchyConstruction-DG-v0.1.md)
covers the unchanged `Instance.param` spelling. Actuals now include parent-Module-owned,
dimensionless Real DAGs using `+`, `-`, `*` and unary minus. Leaves are finite
Real literals or exact ordinary parent Real parameters. Literal overrides and
direct references retain their carriers; standalone native SSA literals are not
a new override form. Division, unknown/runtime/stateful operations, foreign or
nested producers, generated-local parameters and type/unit changes remain outside
this expression profile. Generated-Area Real arithmetic uses the existing
Module-static classification, not a new capture or hoisting rule.

Architecture review retains canonical construction identity, `overrideEvidence`,
override policy, immutable bridge inventories and `emitStaticValue` transport.
The non-evaluating ParameterModel query remains the single native admission owner
shared with StructuralIf. It checks exact instance/formal linkage, parent-context
f64 identity, Module ownership, order before the family and strict producer order
on every operand edge. ConservativeConnectivity independently inspects selected
actuals; it retains the original scalar one-level topology, literal family bounds,
literal child defaults, unique symbolic formals and literal/SSA collision checks.

The necessary shared-layer correction memoizes completed results within each
invocation of the existing native constant evaluator. Its arithmetic body is
unchanged; there is no second evaluator or persistent cache. Active identities
and cached dependency peaks preserve cycle rejection and the existing 512-level
value-entry check, including the terminal-default distinction. Parent expression
defaults remain supported without a new default policy or global evaluator work
cap. Existing default agreement, fixed-formal rejection, normalization and
constraint checks remain authoritative. Defaults are not substituted for symbolic
identity, and no all-legal Real-envelope proof is claimed.

Each new actual-DAG admission query permits at most 65,536 root/operand visits
and 512 dependency nodes on a path, including shared incoming edges. Bounded
traversals and memoized heights prevent sharing from hiding a longer path. Native
parent-symbol inventories are indexed once per query. ParameterModel and the
renderer retain the separate 1 MiB expanded-text guard; ConservativeConnectivity's
graph admission does not promise renderability. Scala's selected helper remains
bounded recursive. No construction default-budget or production-capacity decision,
new public spelling, topology widening or measured performance improvement is
claimed.

The matched API/bridge builds, three changed native units and corrected probe
relink pass. Eight inherited ParameterModel unused-variable warnings remain;
this is not a warning-clean native build. The first probe compilation failed
because five calls used unavailable `OpBuilder.getSymbolRefAttr`; its source,
diagnostics and failed receipt are retained. A separately frozen v2 changes only
those five probe calls to the installed `FlatSymbolRefAttr` API. Production sources
and their compiled objects were not changed to accommodate the failure.

One complete focused run freshly compiles the selected fixtures with strict Scala
warnings and passes three Scala mains plus 36 native invocations. These include
14 independent PM/CC query controls, eight generic-evaluator sharing/depth/cycle
controls, actual arithmetic/default-constraint checks, generated-Integer
predecessors, direct scalar forwarding and compound MaterializedScalar refusal.
The controls distinguish admission from downstream contracts: fixed formals,
nonfinite evaluated arithmetic and selected child-expression defaults do not gain
permission merely because a lower-level query admits their representation.
Repeated arithmetic emission is byte-identical. This is not a rerun of the entire
checkpoint-68 selection, a full C++ unit suite or simulator qualification.

The source witness is
[ParentArithmeticSource.scala](../../tests/scala/domain-hierarchy/fixtures/selected-conservative-parameters/ParentArithmeticSource.scala).
Its relevant Scala excerpt is:

```scala
val parentFirst = param(5.0.real)
val parentSecond = param(5.0.real)
val shared = parentFirst + parentSecond
val amplified = shared * 2.0.real
val firstGain = amplified - (-parentFirst)
val secondGain = amplified - (-parentSecond)
```

Inside the existing `hdlRange(2, 8, 2)` Area, the two child instances use
`.param(_.gain, firstGain)` and `.param(_.gain, secondGain)`. The actual generated
named-hierarchy Verilog-A retains these exact parameter-association excerpts,
respectively, rather than either default or the folded value 25:

```verilog
.gain((((parentFirst + parentSecond) * 2.0) - (-parentFirst)))
.gain((((parentFirst + parentSecond) * 2.0) - (-parentSecond)))
```

Both parent declarations remain `parameter real ... = 5;`, the child declaration
remains `parameter real gain = 2;`, and the generated loop retains
`for (i = 2; i < 8; i = i + 2)`. The full output is retained at
`.work/f58-parent-real-expressions/test-output/focused-v1/main/parent-arithmetic-emit.stdout`
(SHA-256 `8f8aa78a2d00a3def4945118ed6f2fd579b832badf27ad16200bfb8fcca0bd08`).
It was generated by the matching `conservative-probe` with
`test-output/focused-v1/main/parent-arithmetic.mlir emit`, relative to that stage.
The [focused runner](../../tests/scala/domain-hierarchy/run_parent_arithmetic.py)
and retained `test-output/focused-v1/command.json` / `main/commands.json` record
its exact Scala compilation, artifact arguments, backend commands and outcomes.
Compound actuals remain unsupported by MaterializedScalar; its existing direct
substitution profile is deliberately unchanged.

For reproduction, use canonical checkpoint-69 sources, not a mixed worktree.
Rebuild the full matched Scala API and bridge using the repository's
[Scala recipe](../development/scala-build.md), with the matching plugin and
explicit annotation/support dependencies. Use the existing locked native
[build recipe](../development/native-compiler.md) to rebuild ParameterModel,
ConservativeConnectivity and StructuralIfModel against the matching header, then
compile and relink the canonical
[shared probe](../../tests/compiler/fixtures/increment58/generated-conservative-adapter/generated_conservative_adapter_probe.cpp)
against those objects and coherent native dependencies; exclude stale duplicate
implementations. There is no new native probe build target. The runner consumes
these explicit artifacts, not the local receipts above:

```sh
python3 -I -B tests/scala/domain-hierarchy/run_parent_arithmetic.py \
  --repository-root "$REPOSITORY" --java "$JAVA" \
  --compiler-classpath "$SCALA_COMPILER_CLASSPATH" \
  --runtime-classpath "$SCALA_RUNTIME_CLASSPATH" \
  --api-jar "$API_JAR" --bridge-jar "$BRIDGE_JAR" \
  --plugin-jar "$PLUGIN_JAR" --probe "$CONSERVATIVE_PROBE" \
  --output-root "$REPOSITORY/.work/f58-parent-real-expressions/test-output/fresh" \
  --timeout 120
```

Run from the canonical source root; the script locates fixtures relative to itself
and checks the unchanged HEAD68 `build.mill` marker. Classpaths contain explicit
files. If artifacts are overlays, supply their matched support using repeatable
`--api-support-jar` / `--bridge-support-jar`, as specified in the
[selected guide](../../tests/scala/domain-hierarchy/SELECTED_CONSERVATIVE_PARAMETERS.md).
Hold the existing Scala/native locks externally, retain the 246,808,576-byte free
reserve and require a fresh output directory. This is an input/rebuild contract,
not a claim that the local receipt paths or hashes are portable.

The source base is HEAD68 `18f3a7c7df11f73a0d315bd718b9cc436806cb8b`.
The stage's `reviewed-source-freeze.json` records the six reviewed production
hashes, `probe-v2-recipe.json` pins the corrected probe source, and
`focused-freeze.json` pins the executed tools and inputs (SHA-256
`294e592ee975890b8dd9eec34f1610b29ce8bbd90ba50949a27da9ba92131430`).
The accepted result has an empty failure aggregate and preserves raw outputs.
Its sampled owned/combined maxima are 20,500,480 / 105,021,440 bytes, with
382,287,872 bytes free at completion against the unchanged 246,808,576-byte
reserve. These are sampled run observations, not continuous or later
finalization peaks.

Foundation 58, F-058.B.1/B.2, the default 65,536-unit admission gap and automatic
pending-cell completion remain open. Diagnostic 131,072 is not production
capacity, and ExtendedAPI remains pending. No CI, full qualification, CIRCT
comparator/firtool pipeline, installation or publication retry ran. Publication
remains held; this focused acceptance does not complete Foundation 58.

## Checkpoint 70 — bounded symbolic conservative families

The approved existing-API continuation extends the selected scalar, one-level
child conservative Interface family from literal bounds to `hdlRange(0, LANES)`
with literal step 1. `LANES` must be an exact directly referenced, bounded,
parent-owned structural Integer. A selected literal collection ordinal must
exist over every legal parameter value, not merely at the default. The
[Interface gate amendment](../design-gates/NodalInterfaceHierarchy-DG-v0.1.md#f58-bounded-symbolic-conservative-family-amendment)
records this capability extension, not completion of the broader family work.

The prerequisite shared `ShapeModel` correction proves exact symbolic physical
extent identity and the original induction SSA in preflight. Equal defaults,
equal envelopes and rendered names are not identity evidence. Construction,
immutable bridge transport and `ConservativeConnectivity` retain independent
checks; the shape effect is committed only after boundary projection succeeds.
Reuse the existing shape/parameter owners and topology adapter, not a second
Integer evaluator, occurrence registry or per-lane expansion. Traversals remain
bounded by authored structure and existing budgets; no measured speedup is claimed.

Existing fixed/nonunit families and supported Real parameters/arithmetic remain
unchanged. Nested families, Interface arrays, symbolic steps, arithmetic upper
bounds and generated-local family expansion are not admitted by this slice.

Matched build passed: 73 API sources, the changed bridge owner, ten fresh
native objects and link. The first runtime attempt passed Scala compilation and
both replays, then exposed an uninitialized preflight budget residual. The
reviewed correction charges the symbolic proofs to the existing preflight work
counter and carries the true remaining budget into execution. No limit changed.
Only the materializer was rebuilt and relinked; original failure evidence remains.

The corrected native prefix emits symbolic families for defaults 3 and 4 and
unused selections, preserving four distinct child/leaf roots, original induction
and selected ordinal 1. Literal/nonunit, symbolic NodeArray and parent Real-DAG
predecessors pass. A later test-only OTHER-identity mutation omitted the separate
canonical range constraint; its refusal is correct, not a production regression.
The failed probe and output remain retained. The corrected maintained probe
copies the canonical constraint as well as its parameter. The valid independent
dual-parameter control exposed the native mapper's existing 512-unit limit:
its first source derivation charged 427 units, then destination derivation
exhausted the remaining 85. Exact baseline output and failure parity were retained.

The canonical `TypedGeometry` repair threads an immediately checked context for
the identical SSA value through private dimension/expression helpers. It removes
two duplicate context validations per cache miss, not a unique proof obligation.
Capture/order/cycle checks, exact cache keys, owned-expression resolution and all
limits remain unchanged. A separate native selection repair rejects an upper
reference that does not precede its generated family in the same block.

Focused acceptance composes the earlier Scala/bridge checks and unaffected native
controls with 15 repaired-native outcomes: six byte-identical Verilog-A outputs,
dual-parameter emission/adaptation, valid/identity/order queries, view/slice and
mutation freshness, and three existing mapper subsets. The repaired batch checked
its first 13 cases; its paired case exited zero with 305 checks and no failures,
but a receipt-headroom guard stopped before the runner assertion. Independent
inspection discharged that result, and a separately admitted capture tail passed
33 checks. The original failed batch is preserved, not claimed as a whole pass.
The view/slice helper passed 10 checks and the anchor subset passed 192 checks.

Actual dual-parameter output retains `OTHER`, not equal-default `LANES`, in all
four physical extents and the generated loop. Adapted IR uses the original loop
block argument in all four generated shape indices. This closes the observed
native failure without raising its 512-unit limit. Independent architecture and
exact-source review accepted this narrow prototype scope.

The authored witness uses existing APIs (see `SymbolicFamilySource.scala`):

```scala
val LANES = param(3.integer, range = 2 to 5)
val lanes =
  for lane <- hdlRange(0, LANES) yield new Area:
    val _ = lane
    val first =
      instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, 3.0.real)
// The fixture also authors a second child with gain 7 and four body connections.
val childA = lanes(1).first
val selectedA = childA(_.endpoint)
```

Actual named-hierarchy Verilog-A excerpt from `symbolic-3-emit.stdout` (unrelated
ports and connections omitted; physical spelling retained):

```verilog
  parameter integer LANES = 3 from [2:5];
  electrical [0:(LANES - 1)] nodal_i696e7374616e63655f305f696e746572666163655f33_6e;
    for (i = 0; i < LANES; i = i + 1) begin : generate_0
```

The actual two child instances retain `.gain(3)` and `.gain(7)` while the child
module default remains 2. This is symbolic generation, not expansion to the
default or maximum 5. Backend: Verilog-A only. Scoped implementation and focused
acceptance are recorded in checkpoint 70 (this commit). The maintained
`run_symbolic_family.py` preserves the original cases and adds the affected mapper
controls; this evidence does not claim a fresh end-to-end run of that whole runner.
The separate ordinary construction-work gap remains open at the unchanged
65,536-unit default; the 131,072 profile remains diagnostic, not default acceptance.
No Extended public policy API is introduced. Preserve historical results above.
Foundation 58 and its required unfinished children remain open; no CI, full qualification or held downstream
consumer/comparator/firtool pipeline is authorized by this continuation.

## Checkpoint 71 — statechart implementation reconciliation

This documentation-only reconciliation closes **F-058.B.3**, not its B parent,
B.1/B.2, A, C–G (including F), or Foundation 58. The original baseline statements
and historical checkpoint results above retain their original time and scope.
No production file, API, proof budget or construction capacity changes here.

### Required clause crosswalk

- **Immutable definitions/fragments and explicit bindings:** `FsmDefinitionApi`
  and `FsmDefinitionRuntime` retain immutable graph identity, exact slots and
  separately supplied runtime bindings. Checkpoint 1 includes separate compilation,
  accidental-capture negatives and actual independent two-instance execution.
- **Nested/parallel machines and typed completion/cancellation:** the existing
  `FsmChild`, composition owner and lifecycle executor retain independent storage,
  All/Any/ordered Priority joins and exact child/state ownership. Checkpoints 5, 7,
  12 and 13 establish lifecycle, reset/domain and actual nested composition output.
  Impossible joins reject; structural completion possibility is not a proof of
  arbitrary protocol liveness.
- **Timed/protocol-aware states:** the shared timing owner implements `after` and
  `awaitTransfer` with actual valid/ready observations, not ready-drive authority.
  Checkpoint 17 records five target simulations covering exact elapsed edges, stalls, pause,
  composition and mixed-reset occurrences.
- **Finite elaboration recursion:** `FsmDef.unroll` admits checked immutable
  expansion for 0–127 steps with bounded graph depth/occurrences/records. Checkpoint
  20 covers separate compilation, effect/capture/recursive-helper negatives and
  actual target execution. Arbitrary Scala recursion and nested unroll remain
  deliberately rejected; finite expansion is not a runtime call stack.
- **Explicit bounded runtime calls/returns and faults:** `FsmStackApi`, the graph
  contract and bridge/native FlatFSM owners implement capacity 1–128, exact resume
  states, pointer/frame ownership and overflow/underflow/invalid-frame policies.
  Checkpoint 24 records 43 source groups, 17 frontend checks, 164 native cases and
  three actual target profiles. Faults, reset, cancellation, recovery, domain
  ownership and causal malformed-storage negatives are implemented, not metadata
  placeholders.
- **Reports and proof facts:** `ReproducibilityContract` retains definition hashes,
  occurrence topology, structural completion, stack resources/policies and timing
  facts. Bridge/native owners independently verify actual ownership, bounded
  structure, storage and timing contracts. Definition hashes remain correlation
  metadata, not an independently recomputed native proof.

### Historical evidence and current owner review

Direct FSM API/graph/bridge/native files and `FlatFsmCapture` are unchanged from
stack checkpoint 24 (`6eff031`) through checkpoint 70. That statement does **not**
cover every frontend/shared owner. The historical stack source manifest includes
`DigitalCollectionCallbackPhase` hash `37202a65…`, matching checkpoint 25
(`44e21f9`), rather than checkpoint 24's older frontend blob. Its accepted source
span already includes `Trap`, `call` and `returnOn` frontend support.

Later shared callback reindexing/summary republication revalidates lowered bodies,
retains the distinct immutable profile and exact imported-summary compatibility.
ConstructionSession's changed reference allocation preserves canonical identities;
ordinary eager FSM profiles do not activate the pending-construction budget route.
SequentialModel's later external/generated-storage changes retain the existing
FSM verification/expansion chain. Independent source review found no concrete B.3
semantic gap. These facts and retained acceptance justify this child-only closure;
they are **not** a fresh checkpoint-70 frontend/native/target pass. Checkpoint 67
adds only its stated eager capture/storage/determinism evidence, not completion
of the broader scale requirement.

### Existing execution demonstration (not rerun)

The current increment does not affect generated Verilog-*: it reconciles accepted
implementation evidence and current checklist/gate status only. The maintained
source is `core/scala/testkit/test/src/nodal_external/Increment58StackFsmSource.scala`;
its existing graph includes:

```scala
FsmState(StackFsmMode.Main).active(active).exit(exit)
  .returnOn(emptyReturn, TransitionMode.Priority)
  .call(call, TransitionMode.Priority)(StackFsmMode.Sub, StackFsmMode.Resume)
```

The retained output is
`.work/f58-statecharts/stack/shared-accept/target/trap/stack.sv`, with its adjacent
`manifest.json` and `simulate.log`. This historical checkpoint-24 trap-profile
SystemVerilog contains separate two-bit
stack depth and three-bit frame registers. Actual generated frame updates below
omit only source-location comments:

```verilog
register_increment58_stack_fsm_sourc_874448ec <=
  _GEN_10 & register_increment58_stack_fsm_sourc_f63817c8 == 2'h0
    ? _GEN_11
    : register_increment58_stack_fsm_sourc_874448ec;
register_increment58_stack_fsm_sourc_f168dd0b <=
  _GEN_10 & register_increment58_stack_fsm_sourc_f63817c8 == 2'h1
    ? _GEN_11
    : register_increment58_stack_fsm_sourc_f168dd0b;
```

The retained trap simulation reports `STACK_TARGET_PASS` for nested continuation,
overflow/underflow, invalid pointer/frame, actions, pause, cancel/restart, recovery
and reset. Checkpoint 24 names the actual compiler and maintained
`tests/scala/reusable-fsm/run_stack_target.py` demonstration. Those historical
commands were not executed for this reconciliation. Stack Gray remains unsupported;
AssertHold exports its assertion intact, but the pinned Icarus cannot parse it,
so there is no AssertHold simulation-pass claim.

Only document/link/checklist consistency and independent evidence/source review
apply to this commit. No CI, full qualification, held CIRCT consumer, comparator,
firtool, installation or target replay ran. The default 65,536 construction gap,
diagnostic-only 131,072 profile and pending Extended API choice remain unchanged.

## Checkpoint 72 — nested conservative literal proof groundwork

This native-only checkpoint accepts literal two-axis proof groundwork, not
Interface-family integration or Foundation 58 completion. The mapper retains
rank-two NodeArrays, independent induction SSA, ordered zero/unit-step axes,
Module-owned roots and exact dimension identity. It checks complete source tuples,
root/flat consistency, bounds, inversion/roundtrip and destination mapping.
The actual `ConservativeConnectivity` caller uses independently proved images.
Module scalar anchors remain one-way; they receive no ad-hoc exact-map certificate.
The new certificate applies only to multiple shared roots, preserving fresh trees.

One private query-local checked-context owner stores successful exact-SSA facts.
Borrowers check its Module; insertion follows complete capture/header/scope
validation and hits charge one unit. Failures, partial captures, equal types,
caller summaries and unbudgeted pre-authentication cannot seed it. There is no
cross-query reuse. Existing dimension caches and actual-use verifiers remain.
The enhanced mapper reuses its same-query destination only through unchanged
completeness checks. No evaluator, occurrence registry, header layout, public API
or capacity change is introduced.

### Focused execution and measured boundary

The manually compiled and linked native probe passed **585 checks, zero failures**
(exit zero, empty stderr), running exactly:

```sh
.work/f58-nested-conservative-proof/literal/proof-probe --literal-groundwork
```

The maintained source is
[`NestedConservativeProofTest.cpp`](../../core/compiler/test/Unit/NestedConservativeProofTest.cpp).
Its standalone CMake target `nodal-nested-conservative-proof-unit-tests` registers
`nodal.native.nested-conservative-proof-unit` with `--literal-groundwork`, linking
only the existing native dialect and MLIR/LLVM support owners. This registration
was source-reviewed; **the CMake build/CTest route was not executed**. The exact
same probe bytes passed the manual build/link/run above. The broader no-argument
assertions remain intact, but were not run on the corrected candidate and are not
registered or reported as passing.

Actual topology checks cover repeated/equal tuples, either or both axes differing,
and shared-anchor convergence. Both mapper directions, root/flat contradictions,
malformed axes/bounds/steps, tiny budgets, transactional failures, distinct SSA,
between-query mutation/restoration, fresh-tree and ordinary nonunit predecessors
pass. Early verifier refusals are boundary tests, not cache-hit isolation.
Cross-root different-tuple comparisons remain `Unknown`.

Minimum sufficient **public allowances**, searched only within 512 at 64 states,
are 415 for paired forward/reverse mappings (old and enhanced), 223 for the
one-way anchor, and 161/203 for the old/enhanced ordinary nonunit predecessor.
These are not internal-work or CPU measurements. The semantic signed-64 product
witness `46341 * 46341` passes without narrowing Module NodeArray semantics to a
future signed-32 adapter profile. Existing mapper limits 512/64 and global caller
limits 16384/1024 remain unchanged.

### Provenance, preserved failures and remaining work

The source base is HEAD71 `d46bc353b107be6ada0de41377917da76405c7e6`.
Exact accepted SHA-256 inputs are:

- `ShapeModel.cpp`: `f8387569e9b1411c36e49daaf5e6b9b9170ef0a631dfcbad2bb633307371b2aa`.
- `ConservativeConnectivity.cpp`: `d582a3fd711066d9ef473bc289787ad6867932c5bf0c51fadc75e94481978842`.
- Maintained probe: `068f578f5eb7a3c3d4fff945456c92f11885dbe45e210a65cad102ef963ba66d`.

The retained literal runtime receipt is
`.work/f58-nested-conservative-proof/literal/runtime.result.json`, SHA-256
`c3615809834593fa30b6b71eadfcbeee602f778bcaab3d1ab978d580bf2e5098`;
it pins executable `43f0150dab2307da8a47dc272552f0d616df0c55c5741e53fddd53282bc3faee`,
source freeze, link receipt and outputs. Independent source/outcome review accepted
only this native literal scope.

The original broader run (512 checks, 80 failures) and its failed candidate remain
preserved. A same-budget diagnostic reproduced its semantic stdout/stderr and
exit status exactly, with six balanced, untruncated charged-unit ledgers. It
measured literal image work 419 (projection 283, dimensions 95), then destination
projection exhaustion; symbolic image projection exhausted before dimensions.
This justified canonical validation reuse, not a larger budget. The diagnostic
parity receipt is `diagnostic/runtime.result.json` under the same stage, SHA-256
`9860642c3debedcece264df8f40673d8f5259b76583580608ae30056c2caa56a`.

Symbolic two-axis completion remains blocked. A source-derived lower bound of
at least 609 charged units for the existing context-only symbolic route exposes
repeated canonical dimension/parameter proofs; it is **not a runtime pass or a
claim that the literal repair fits symbolic queries**. Canonical parameter-proof
reuse requires its own scoped review. Frontend/bridge/Interface-adapter widening,
generated target handoff, signed-32 product/stride checks and nested Verilog-A
emission remain pending. No new generated HDL demonstration is claimed. No
frontend replay, adapter/Verilog-A run, CMake/CTest run, full suite, qualification,
CI, held CIRCT consumer/comparator/firtool pipeline or installation ran here.
The default construction-work gap and diagnostic-only larger construction profile
are unchanged. F-058.B.1/B.2, their parents and Foundation 58 remain open.

## Checkpoint 73 — closed literal occurrence-path distinctness

This native-only prerequisite closes the checkpoint 72 cross-root `Unknown` gap
for one narrow topology, not Foundation 58 or nested Interface/Verilog-A integration.
`ConservativeConnectivity` admits two Module-owned literal rank-two carrier roots
in one fully certified, closed two-axis generated-family tree. Both original
ordered images must retain the existing exact-map certificate. It recovers the
unique path through actual relation edges, maps the original point along that
path, and compares coordinates only after reaching the actual target root. There
is no root rebasing or tuple-only electrical inference. Fresh scalar Node and
InstanceTerminal vertices may be degree-one pendants in the exact inner scope;
they cannot carry the path. Scalar anchors, child uncertainty, foreign scopes,
cycles/parallel paths, shifted/permuted/repeated maps and fresh arrays do not gain
this certificate. Mapping failure never becomes `Distinct`; the general traversal
remains conservative. Work and every discovered/enqueued path state carry through
fallback without reset.

### Focused result and authentication boundary

The manually compiled/linked exact native probe passed **732 checks, zero failures**
(exit zero, empty stderr):

```sh
.work/f58-nested-distinct-proof/proof-probe --literal-groundwork
```

Direct and genuine two-edge carrier paths, both directions and either/both tuple
coordinates differing, pass `Distinct`. Actual independently classified child
boundary pendants and ordinary scalar pendants pass. These positive fixtures also
pass whole-module verification. Same-tuple `Equal`, shared-anchor `Equal`, and
existing nonunit/fresh-tree predecessors remain covered.

Unsupported negative/budget fixtures explicitly fail the whole-module selected-pair
topology gate with only its exact `NODAL-SHAPE-043-009` unproved-identity or
resource-limit diagnostic. Original operation, scope, projection and endpoint
contracts are independently authenticated before direct queries assert `Unknown`
or `ResourceLimit`, never `Invalid`. These are not fully verified accepted modules.
IR transactionality and existing tiny-budget checks remain. Authentication calls
are separate from each bounded query, not a whole-route work measurement. Mapper
limits **512/64** and caller limits **16384/1024** are unchanged; no ShapeModel,
header, API or capacity changes are included.

### Exact provenance and open scope

The base is HEAD72 `9e9b522388fd31a0c9f0f422a93fd15bddd0f9b7`.
Accepted SHA-256 inputs are `ConservativeConnectivity.cpp`
`85b1e6af394fd420ce7f30114e586dcef912bc30485ee59403275d7a0447889b`
and the maintained `NestedConservativeProofTest.cpp`
`39daea9eba4ad512e08f6f4b7dd72fea2c2885198b29fd5719cbf322ec80c7b6`.
The retained `.work/f58-nested-distinct-proof/runtime.result.json` receipt is
`9ab21b2101140d77af27d5ec613617412bfd525d58f1256fdad692685b4f34f8`,
pinning executable
`1e0b1773c26604dcae2f8fe8deed977056959119fa0470db6d311159c414f5f6`
and its exact source/link/output chain. Independent source and outcome review
accepted this scope. Checkpoint 72's historical 585/0 and original 512/80 failure
with diagnostic parity remain preserved, not overwritten by this result.

Symbolic parameter-only and static-certificate proposals stopped at source-derived
whole-route lower bounds of 710 and 542 respectively; neither is a runtime pass
or authorization to enlarge native capacity. The CMake/CTest route and broader
no-argument suite were not run. Frontend/adapter/Verilog-A integration, signed-32
target handoff, full suites, CI, held CIRCT/comparator/firtool pipeline and
installation remain unrun. Foundation 58 and its open parents/children remain open.


## Checkpoint 74 — literal nested conservative target integration

This bounded existing-API slice connects checkpoint 72/73 native proofs to actual
Scala construction, bridge lowering, conservative boundary adaptation and
Verilog-A emission. It does not close F-058.B.1/B.2, their parents or Foundation 58.
The supported profile is exactly two original rectangular literal `hdlRange(0,
positiveLiteral)` axes, unit step, complete in-bounds literal ordered selections,
and scalar conservative Connect leaves with the existing role/domain/child proofs.
Nested child parameters use finite ordinary dimensionless Real literal defaults
and dictionary actuals only. Nested SSA overrides, symbolic axes, nonzero/nonunit
axes and arbitrary additional family consumers remain refused. Rank-one symbolic,
nonunit and parent-expression behavior is preserved rather than widened here.

### Canonical owner and implementation

`ConstructionSession`, `InterfaceMlir` and `DigitalValueMlir` admit only that
literal rank-two profile through existing APIs and the existing ordered selection
prefix. There is no public API, snapshot or MLIR schema change.
`ConservativeConnectivity` remains the sole conservative selection-admission owner:
its internal
selection result retains the original canonical ordered selection proof and an
equal-length ordered domain vector. The adapter consumes those facts, retains
both original Generate operations and induction SSA values, creates Module-owned
rank-two carriers, and emits one direct two-axis ShapeIndex per projection.
It does not introduce another evaluator, flatten source topology or clone each
lane. Exact inspected proof consumers are removed; unrelated surviving family
consumers are rejected. `ShapedValueBackend` remains the owner of row-major target
layout, with the adapter checking the signed32 product before materialization.

The new result changes internal native layout. All eight direct header users and
the focused probe were compiled against the changed header; retained archive
consumers were checked for compatible source/object/type lineage. This is not a
fresh whole-compiler rebuild. The frontend build compiled all 73 API sources and
the two changed bridge owners over pinned retained support layers, not a fresh
24-owner bridge build. No production proof or construction limits changed:
construction default 65536, native mapper 512/64 and topology caller 16384/1024.
The separately recorded larger construction-policy diagnostic and pending
Extended API question are not promoted by this slice.

### Actual source and Verilog-A demonstration

The maintained source is
`tests/scala/domain-hierarchy/fixtures/nested-conservative-target/Source.scala`.
Its actual nested body contains:

```scala
val rows =
  for row <- hdlRange(0, 2) yield new Area:
    val _ = row
    val columns =
      for column <- hdlRange(0, 3) yield new Area:
        val _ = column
        val first = instance(new NestedConservativeLeaf).domain(core).param(_.gain, 3.0.real)
        val second = instance(new NestedConservativeLeaf).domain(core).param(_.gain, 7.0.real)
```

The complete fixture connects p-to-p and n-to-n between the two child instances
and selects original tuples (0,0), (0,1), (1,0), plus the second child's (0,0).
The matched Verilog-A output actually contains these loop and address lines:

```verilog
    for (i = 0; i < 2; i = i + 1) begin : generate_0
      genvar i_1;
      for (i_1 = 0; i_1 < 3; i_1 = i_1 + 1) begin : generate_1
        localparam integer node_map_nodal_i696e7374616e63655f305f696e746572666163655f33_70_0_0 = ((i * 3) + i_1);
```

There are four Module-owned `electrical [0:5]` carriers, four actual child-port
routes, and separate p-to-p/n-to-n alias statements. Both authored child instances
retain literal gain overrides 3 and 7 while sharing one emitted child definition.
That definition retains `parameter real gain = 2;` and the actual private equation
`V(observed, reference) <+ (gain * 1);`. The private equation proves parameter
emission, not that gain drives the exported Interface leaves. Unused selected
projections need not survive target output; their exact original tuple/carrier
identity is checked in-process during adaptation. The separate AnchorSource
witness checks two visible Module connections for repeated tuple (0,0), using the
same canonical selected boundary and actual nested carrier routing.

### Focused results and reproduction boundary

Default-policy Source, Controls and Anchor replay passed with the full authoritative
source projection and exact maintained source paths. Controls cover 11 source
refusals, three forged-prefix snapshot refusals and positive recovery. The shared
`tests/scala/domain-hierarchy/source_projection.py` preflight now requires a regular
build marker and both core/scala and docs directories; focused regressions cover
missing/wrong markers, hash mismatch and bare/foreign emitted author locations.
Four existing focused runners use that same helper. Their full suites were not
rerun as part of this helper extraction.

The matched native executable passed all eight positive calls: 732 predecessor
checks with zero failures, exact nested adaptation, nested emission, anchor
adaptation/emission, and rank-one literal-nonunit, symbolic-family and
parent-arithmetic emission. The last three outputs are byte-identical to their
retained accepted references. Nested emission runs twice internally with identical
bytes and unchanged original IR. Adapter assertions retain both loop/induction
identities, four carriers, four body points and all eight selected leaf tuples,
with exact correspondence and no surviving Interface/family proof records.
The maintained `check_output.py` also passed on both actual emitted witnesses,
checking loops, actual port statements, ordered addresses, separate joins,
shared definition and anchor connections. No simulator or held consumer ran.

Four maintained native derivatives produced six actual calls. Forged family-prefix
provenance refused verification with absent-export-inventory and consequent
unchecked-family-view authentication diagnostics.
A correctly typed literal-backed nested Real SSA override, with its original
literal dictionary binding removed, refused selected-boundary admission through
`NODAL-INTERFACE-054-001`. The original runner expected the later generated-override
diagnostic and therefore recorded an expectation failure; source review confirms
the earlier canonical CC/Interface refusal is the intended unsupported-profile
boundary. That receipt remains a failure, not a whole-runner pass.

The untouched four-call tail used identical compiled code and input bytes. An
authentic unused family-prefix consumer and the balanced 46341-by-46341 extent
each passed ordinary native verification, then failed public backend emission
with their distinct unplanned-consumer/signed32-layout diagnostic,
`failure_is_transactional=1`, empty target and unchanged in-memory input.
The product 2147488281 exceeds signed32 target layout without altering semantic
proof budgets. `make_controls.py` reproduces these four derivative inputs from
the actual bridge output. Matched 732 predecessor checks separately cover
authenticated child coupling and conservative Unknown/topology refusal; they do
not establish an end-to-end coupled-child adapter transaction.

The exact successful Verilog-A SHA256 is
`87946987436dbee3dfdf5b9000ffdc683e2d845cb70418f1f01221ad524525c6`;
the anchor output is
`036605c5d698d8e0a5aacca39ad2b17e9b337a3582c8fdc37cb96fe350ae5ba0`.
Matched executable SHA256 is
`65555271026ad581b1157663ce404144e4ad911337962e2548ee6c106a7dfa83`.
The projected sources, exact compile/link recipes, raw outputs and concise
per-case receipts remain in ignored `.work/f58-nested-conservative-target`.
The final wrapper source is the retained `probe-v3` correction. Representative
commands using that matched build are:

```sh
.work/f58-nested-conservative-target/bin/conservative-probe-v3 --literal-groundwork
.work/f58-nested-conservative-target/bin/conservative-probe-v3 .work/f58-nested-conservative-target/test-output/scala-v2/nested.mlir nested-adapt
.work/f58-nested-conservative-target/bin/conservative-probe-v3 .work/f58-nested-conservative-target/test-output/scala-v2/nested.mlir nested-emit
python3 -I -B tests/compiler/fixtures/increment58/nested-conservative-target/check_output.py .work/f58-nested-conservative-target/test-output/native-v3/nested-emit.stdout .work/f58-nested-conservative-target/test-output/native-v3/anchor-emit.stdout
```

Earlier source-root fallback outputs, rejected header-provenance attempts,
lexical-path validation mismatches and the initial post-order test-wrapper failure
are retained unchanged. The authoritative source replay, canonical-header repair,
explicit dependency validation and two-line PreOrder wrapper correction are
separately recorded; none of those earlier attempts is relabeled as acceptance.
These are focused composed prototype results, not a fresh whole-runner/full-suite
qualification claim. Source review accepted canonical ownership, bounded proof
reuse and actual routing. Symbolic rank-two capacity, broader hierarchy contracts,
the separately recorded default-policy workload gap and remaining F-058 children
stay open. No capacity promotion, CI, CIRCT comparator/firtool pipeline, installation
or broader qualification was performed.


## Checkpoint 75 — fixed conservative InterfaceArray forwarding

**Status:** Focused prototype acceptance for the selected existing-API slice.
Foundation 58, F-058.B.1 and F-058.B.2 remain open.

Existing `InterfaceArray.forwardExact` now supports whole positive authored-Int
rank-one Electrical conservative arrays across ordinary immediate-child Module
boundaries. Both endpoints must select the same Connect role, complete ordered
member/nested-interface ABI and exact domain binding. One endpoint is a direct
local export and the other an authenticated child projection; every child member
must have exactly one complete whole-array forwarding map. The focused witnesses
include one and three lanes, reversed call orientation, two hierarchy levels and
two actual instances sharing one child definition. No new public spelling or
snapshot schema was added.

`ConstructionSession` authenticates live declaration, instance and scope identity;
`DigitalInterfaceContract` owns typed capture, and `InterfaceMlir` independently
validates snapshots before lowering shaped member accesses and existing
`connect_array`. Conservative edges are excluded from ordinary digital driver and
observation processing. The shared native Interface owner classifies the narrow
fixed conservative profile, while `ConservativeConnectivity` proves actual roots,
geometry and complete once-only child coverage. The existing boundary adapter
independently refuses missing physical incidence plans, including an entirely
unreferenced child, before mutation. It emits existing electrical terminal arrays
and compact whole-array bindings, not per-lane instances or digital assignments.

The actual two-level test exposed an adapter inventory bug: an internal child
boundary projection was being treated as a local exported declaration. The
reviewed two-line `InterfaceInstanceOp` filter fixes that inventory while retaining
the existing work charge and all genuine child incidence obligations. Only that
owner was rebuilt for the repair; the final link reuses exact accepted objects
with explicit additive-header ABI/dependency provenance. This is not a claim that
all header consumers were freshly rebuilt.

Focused evidence:

- Five source positives, eight capture refusals and seven forged-snapshot refusals
  compose the retained v2 prefix and corrected v3 tail under the unchanged default
  construction policy. The authoritative source projection uses the shared marker
  and emitted-location preflight. No original failed runner is relabeled as green.
- All five actual bridge witnesses emit verified compact Verilog-A. The maintained
  `check_outputs.py` checks executable vector declarations, full independent p/n
  mappings, actual top-to-middle-to-leaf references, shared-definition multiplicity,
  decoded semantic correspondence and adapted operation inventory. It is independent
  of module serialization order. Four direct target outputs and extent3 adaptation
  are byte-identical before/after the two-line repair; repeated extent3 emission is
  byte-identical. Generic positive emission does not claim an in-memory input-IR
  parity check that its probe does not perform.
- Eight native derivatives cover missing/unaccessed leaves, duplicate whole maps,
  projected children without members, zero parent projection, cross-member wiring,
  local-local relations, signed32 target overflow and unused rank-two declarations.
  Five reject verification with their exact cause; three preserve declaration-only
  verification and then reject emission transactionally, with empty target and
  unchanged in-memory input. All three transactions also pass with the repaired
  executable. `make_controls.py` reproduces all eight inputs and corrected
  expectations byte-for-byte without depending on private scratch guard modules.
- The matched executable passes the retained 732 checks at unchanged 512/64 mapper
  and 16384/1024 topology limits. Retained nested and anchor target outputs are
  byte-identical to checkpoint 74. The original anchor call accidentally selected
  the eight-point-specific wrapper mode; its exit89 remains retained, separate from
  the successful replay using the exact prior generic `emit` command.
- Four existing ordinary digital forwarding/drive/array/resolved captures pass;
  all eight snapshot/MLIR artifacts are byte-identical to retained accepted outputs,
  with actual emitted source-path postchecks. Five existing scalar conservative
  source/bridge groups pass. Those five groups discard their MLIR, so no retained
  scalar emitted-path or fresh scalar native-target claim is made. An initial
  ordinary replay omitted a fixture class directory; the preserved pre-case failure
  was corrected with a pinned isolated retained fixture directory, not a production
  change or broad stale classpath.

The final executable SHA256 is
`94f1e3442a4256065f4669e1ec90cd60367cf87f4464c5011795306b72465b84`.
The actual two-level Verilog-A SHA256 is
`0e8efd22829cfe15164b745d74abb4c43acdee6a4c37180c5278d864780b3f20`;
extent3 is `b7bcc4aae42e5b7917beb2f443ada03f50e2f21c9c2e0569c464c63ea2565870`.
Exact source projections, compile/link recipes, original failures, lossless retained
artifacts and concise receipts remain in ignored
`.work/f58-conservative-interface-array`. Representative focused replay commands:

```sh
.work/f58-conservative-interface-array/scalar-revision2/bin/conservative-interface-array-probe-v2 .work/f58-conservative-interface-array/test-output/scala-v2/two-level.mlir emit
python3 -I -B tests/compiler/fixtures/increment58/conservative-interface-array/check_outputs.py .work/f58-conservative-interface-array/test-output/native-v2 --two-level
python3 -I -B tests/compiler/fixtures/increment58/conservative-interface-array/make_controls.py ACTUAL_EXTENT3_MLIR FRESH_OUTPUT_DIRECTORY
```

Source authentication distinguishes authored Int counts, the actual Electrical
singleton and Area allocation ownership. Snapshots/native IR prove their canonical
represented dimensions, discipline/nature attributes and ownership, not erased
syntax or allocation distinctions. An Area member may merely alias a Module-allocated
instance, so it is not allocation evidence. Target binding metadata currently has
`source_location: unknown`; semantic identity survives, but authored file/line
provenance is not claimed for that target field.

Generated/symbolic/mixed/signal-flow arrays, rank-two forwarding, slices/partial
maps, scalar conservative `forwardExact` and broader hierarchy contracts remain
outside this slice. Existing scalar member operations, empty digital interfaces
and broader declaration-only admission are preserved. The default construction
capacity gap and symbolic native capacity questions remain separate and open.
Independent scoped source/outcome review accepted this increment. No limits were
raised, no full-suite qualification or CI ran, and no held CIRCT comparator/firtool
pipeline or installation was invoked.

## Checkpoint 76: additive Area contract reconciliation

On 2026-10-23, F-058.A.1 is accepted as a bounded public-contract and compile-
prototype reconciliation, reviewed against canonical source
`d8929f1942bf4f30f2d9047fa69a3a37a6761ad1` (checkpoint 75). The approved
[StructuralIf](../design-gates/NodalStructuralIf-DG-v0.1.md),
[AreasHierarchy](../design-gates/NodalAreasHierarchy-DG-v0.1.md) and
[GeneratedAreas](../design-gates/NodalGeneratedAreas-DG-v0.1.md) records compose
this contract. ADR0009 retains its historical decision and adds the bounded
extension; the public API reference and gate index now link the same owners.
Only A.1 closes here. F-058.A, B.2/B.2.2 and Foundation 58 remain open; the earlier
B.3 acceptance is unchanged.

### Contract and prototype crosswalk

- Exact Integer comparisons/equality, Boolean composition, stage/context
  diagnostics and read-only branch results: `tests/scala/structural-if/run.py`
  and its positive, negative, no-plugin, helper/base/consumer and host-control
  fixtures; `Increment55StructuralIfTests.scala` and
  `nodal_external/Increment55PublicStructuralIf.scala` supply semantic witnesses.
  The [F55 structural acceptance](increment55-readiness.md#final-structural-checkpoint-acceptance)
  records eight successful frontend commands and 19 expected compiler rejections,
  bytecode/default-evaluation controls and separately scoped native evidence.
  Its positive syntax amalgam is frontend typing evidence, not whole-model HDL
  acceptance. Invalid reversed/non-Integer operators retain Scala typing errors;
  symbolic stage is proved from ownership, not merely `Expr[Integer]` typing.
- Lexical/member rules and compatibility: `tests/scala/areas/run.py` compiles
  `AreaPositive.scala`, separate `ReusableArea.scala`, `AreaFactory.scala` and
  `AreaConsumer.scala`, seven lexical negatives and missing-plugin/metadata
  controls. Checkpoint 3 records the six Area groups within 52 combined groups,
  with ordering, ownership, failure poisoning and separately scoped native
  lexical/external-write witnesses. Ordinary lexical writes do not prove
  selected generated writes.
- Typed collections, ordinal/static references, guarded existence and common
  source: the same Area runner compiles separate generated producer/factory/
  consumer artifacts, five generated negatives and nine guarded effect/metadata
  cases. `Increment58GeneratedAreaSource.scala` covers `for`/`hdlRange`/`yield`/
  `new Area`, structural `if`, behavioral `when` and static member selection.
  `Increment58GeneratedLaneRegisters.scala`, checked by
  `Increment58GeneratedAreaTests.scala`, supplies the exact Bits, per-lane-enable
  and reset witness, rather than substituting the earlier UInt/shared-enable
  example. Selected-dependent fixtures cover nested/guarded reference ownership.
- Historical executed composition: checkpoint 7 retains its original diagnostic
  mismatch and subsequent successful 12-group correction, not a wholly passing
  original run. Checkpoint 23 records the accepted Area harness, 49 source groups
  and a later overlapping 13 artifact/guarded groups. Checkpoints 35 and 37 record
  body-local children and shared-source/native follow-up with their exact source
  and compiler identities. Counts are not added across overlapping runs or
  retroactively applied to subsequently extended maintained runners.

### Current lineage and acceptance boundary

Read-only review verified that
`core/scala/frontend/plugin/src/nodal/internal/frontend/compiler/StructuralIfCapture.scala`
still checks exact symbols, Integer typing and bounded direct Boolean grammar. Owners are not all
unchanged since checkpoint 23: structural lowering later re-owned lambda-local
symbols, member derivation gained Instance/Interface dispatch and Area capture
added InterfaceValueRef. Checkpoints 39, 47 and 53–54 record the corresponding
focused compilation and predecessor work. `git diff e3da389..d8929f1` is empty for
`core/scala/frontend/plugin` and the four files under `core/scala/api/src/nodal/`:
`AreaConstruction.scala`, `GeneratedAreaApi.scala`, `GeneratedAreaDerivation.scala`
and `StructuralIfRuntime.scala`. ConstructionSession changed substantially afterward; this lineage
review is not current runtime acceptance of every historical fixture. Retained
artifact and shared-source receipts were inspected separately, including the
checkpoint-23 artifact exit/manifest success and later 14/14 generated and 73/73
family-source results; they remain historical executions.

Architecture review reuses the approved gates and canonical construction,
iteration, hierarchy, effect and naming owners. No evaluator, registry, policy,
public spelling or mutable state is introduced. Thus no performance/scale claim
or benchmark is applicable. Focused validation checks the five-document diff,
relative links, exact fixture/owner references, and checklist consistency. No
compiler/runtime suite, CI or downstream consumer was run for this change.

Selected generated Instance-input and Interface writes still fail closed pending
ordinal-aware assignment/driver proof; they are required B.2.2 implementation,
not excluded from the accepted contract. Runtime hierarchy selection, capability
widening, general host effects and unbounded proof remain unsupported. Default
construction 65536, pending Extended-policy design and independent native proof
limits remain unchanged. Historical SystemVerilog/two-state evidence is not
portable Verilog-2005 or universal generated-hierarchy certification.

The current increment does not affect generated Verilog-*. It only reconciles
current contract references and composed historical prototype evidence; no new
output is claimed or required for this documentation-only child closure.

## Checkpoint 77: named downstream tool handoff

F-058.D.2 is accepted as a focused handoff, not downstream qualification. This
2026-10-23 documentation-only crosswalk was reviewed against checkpoint 76,
`ffb9ced603799bd74dd173e8501f8b5c7db9d79c`. It names the representative affected
consumer cases and preserves executed versus unexecuted boundaries. D.1, D,
B.1/B.2 and Foundation 58 remain open. No tool execution is added here.

### F65/66: hierarchy and stack parse/simulation cases

Use checkpoint 2's `shared` hierarchy case: one reusable child definition with
independently owned state. The adjacent `nested`, `material`, `resetless` and
`inherited-release` profiles cover forwarding, material clock/reset variants and
release behavior. Historical `HIERARCHY_TARGET_PASS shared` and the other four
Icarus results are retained under `.work/f58-hierarchy-target-checkpoint/`, with
its `manifest.json` and per-command logs. Exact shared witness SHA-256 pins:

- input: `6a1e1739dd75a2ee836b3d80fc5a7e0699a64ea51d2957864ce7fdf0efbfb287`;
- nodalc: `2125c01fdc146894cf5e4a2e9a58dfc52b9f56b6590022268a728a5442eacbe7`;
- generated `shared.sv`: `31abde5628cce3c1d7d014d8216ff42f559e1ea0e68f9f4a630bc74ce1c1bd46`.

The maintained recipe is `tests/scala/domain-hierarchy/run_target.py --case shared
--input .work/f58-hierarchy-source --nodalc .work/f58-native-checkpoint/nodalc
--firtool .work/circt-toolchain/bin/firtool --iverilog .work/tools/iverilog/bin/iverilog
--vvp .work/tools/iverilog/bin/vvp --out NEW_IGNORED_OUTPUT`. This records a
historical reproduction route, not authorization to invoke the held exporter.

The second named case is checkpoint 24/71's bounded-stack `trap`, with separately
retained `required` and `composed` companions. The maintained public model is
`core/scala/testkit/test/src/nodal_external/Increment58StackFsmSource.scala`.
Expected behavior includes continuation frames, overflow/underflow, invalid
pointer/frame handling, pause, actions, cancellation/restart, recovery and reset.
Historical `STACK_TARGET_PASS` is not a fresh checkpoint-76 source execution;
checkpoint 71 explains the checkpoint-24/25 source history. Exact SHA-256 pins:

- historical Scala source: `e813edb8e9054e76be7778f5cfd325bddb0cd23563de81c7c1ccf3b4eda00d5f`;
- `trap.mlir`: `26feb0be42b35882ce3f6e4dad806e5148d7077f77dd889e1d57fb16567748bf`;
- construction snapshot: `e24d01e1603195acac3603378bf33854c332cfa5dfb0ac714a84f31df25bbe84`;
- nodalc: `5ec53036d0ccc2c9f14898e5d6e21c76693651b0dab564a75e0b19236d99375b`;
- firtool: `fa6f67c3a291cb0dc097e87c7e68fe133113ab32e2c7f2f39da9c5057daee224`;
- iverilog: `422bcdaf5f4e4b07dd7a80c3f788e02f2e7960b3a7bf06b242e4bb6f89f94f8c`;
- vvp: `ed71d0d93fcad62222b1dc172453b895c1e8f2a6f60bd7a6203d953aa3c9898d`;
- generated `stack.sv`: `1a852dc06406db8237a9775d4f219686debbf2e1cea9deb061503402a9390290`.

The exact historical argv is retained in
`.work/f58-statecharts/stack/shared-accept/target-trap.command.json`: it invokes
`tests/scala/reusable-fsm/run_stack_target.py --profile trap`, the sibling
`replay/trap.mlir` and `replay/trap.construction.json`,
`.work/f58-statecharts/stack/native/nodalc3` and the tools pinned above. Any later
replay uses a fresh ignored output directory and first verifies retained inputs
and tool identities; this handoff does not execute it. Preserve the historical
AssertHold Icarus parse failure without removing its assertions and the unsupported
Stack Gray profile. These are SystemVerilog/two-state witnesses where documented,
not portable IEEE1364-2005 or broad four-state qualification.

### F67: named synthesis, equivalence and core-proof inputs

Hand off the exact `shared.sv` and stack `trap`/`required`/`composed` artifacts
above to the existing [digital verification plan](../roadmap/digital-verilog-open-source-verification-plan.md).
Its Yosys, equivalence and SBY owners supply capability-specific harnesses and
executable pins. Expected checks are:

- synthesis: resolved hierarchy and child references, no accidental latches,
  driver conflicts or combinational loops, independently owned stack/state storage;
- equivalence: compare preserved/lowered RTL and netlists by semantic state and
  lane identity, not an assumed common state encoding; retain reset/hold/action
  and nested completion behavior;
- core proof: legal state, stack-pointer/frame bounds, reset convergence,
  completion/return and overflow/underflow safety under explicit legal inputs.

Status: **not executed for this F58 handoff**. No Yosys, equivalence, SBY or other
formal result/tool pin is established by these F58 receipts. Native verifier
checks are not formal proofs. A future consumer's harness or parent completion
is not a reverse prerequisite for this named F58 handoff.

### F72: actual conservative AMS-output case

Hand off checkpoint 75's two-level fixed conservative InterfaceArray case from
`core/scala/testkit/test/src/nodal_external/Increment58ConservativeArraySource.scala`,
accepted in source commit `d8929f1942bf4f30f2d9047fa69a3a37a6761ad1`.
Expected structure is compact positive fixed rank-one Electrical Connect vectors,
exact independent p/n correspondence through top/middle/leaf, once-only complete
child coverage and shared definitions, not per-lane module expansion. Pins:

- actual source-input MLIR: `3e4d30dcc98039a2c1e21d634d76e22ff0be15af8afa90326e076736e3954bc9`;
- matched native probe: `94f1e3442a4256065f4669e1ec90cd60367cf87f4464c5011795306b72465b84`;
- actual Verilog-A: `0e8efd22829cfe15164b745d74abb4c43acdee6a4c37180c5278d864780b3f20`.

The accepted receipt is
`.work/f58-conservative-interface-array/test-output/native-v2/two-level-emit.result.json`.
The recorded command uses
`.work/f58-conservative-interface-array/scalar-revision2/bin/conservative-interface-array-probe-v2`
with `.work/f58-conservative-interface-array/test-output/scala-v2/two-level.mlir emit`;
`tests/compiler/fixtures/increment58/conservative-interface-array/check_outputs.py`
checks the native-v2 output directory with `--two-level`. Checkpoint 74's nested
literal output is an adjacent two-axis companion, with its exact commands/pins
retained there. Lossless retained-product archives may require verified restoration
before a future replay; no replay or restoration occurred for this handoff.

Status: actual Nodal Verilog-A generation and structural checks passed at the
recorded checkpoints. OpenVAF, ngspice, AMS simulation, numerical KCL/convergence
qualification and F72 completion are **not established**. Binding source_location
remains unknown in the checkpoint-75 target; semantic correspondence is retained.
Broader symbolic/rank-two forwarding, pending construction policy and independent
native proof limits remain separate open constraints.

### Focused review and applicability

Read-only receipt/command/hash inspection and independent handoff review cover
the named consumer mapping; focused document/link, diff and checklist checks
cover this two-document change. The existing owners and retained artifacts are
reused without another registry, harness or runtime component. No new scale or
performance claim applies. CIRCT comparator repair, firtool/exporter/check.py,
installation, CI and downstream qualification remain unrun under their existing
holds. D.2's handoff acceptance neither waives an implementation requirement nor
turns an unavailable consumer into a passing result.

The current increment does not affect generated Verilog-*. It only names existing
actual output witnesses and future consumer expectations; it generates no new HDL.
