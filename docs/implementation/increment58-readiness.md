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
