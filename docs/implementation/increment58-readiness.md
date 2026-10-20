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
