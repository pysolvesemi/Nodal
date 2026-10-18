# Nodal writable Real arrays design gate v0.4

**Status:** Approved
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing Foundation implementation authority is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This is an implementation-selected additive extension of writable-array gates v0.1,
v0.2 and v0.3. It adds no public signature, changes no existing scalar evaluation
contract, and closes no Foundation parent checklist. The existing storage roots,
static shape/projection proofs, exact physical dimensions, selected definite
assignment, declaration lifetimes and generated occurrence ownership remain binding.

This checkpoint makes scalar reads selected from writable Real arrays usable in
existing public Boolean conditions/case selectors and legal event arguments. It
also gives the existing native Integer loop-bound source grammar actual selected
reference operands. It does not invent a public Real-to-Integer cast. Public loop
composition remains explicitly open wherever the public API cannot express a
legal Integer bound; a native-only witness must not be reported as a public one.

## Evaluation and lifetime contract

A bound reference denotes current scalar storage, not an immutable captured value.
Use the existing shape_index/shape_view/shape_slice storage graph; the final handle
must be scalar. Constructing those pure aliases does not load any variable.

- Evaluate each if/else-if predicate only when reached in the first-match chain.
  Preserve bool_and, bool_or and analog_select short-circuit/conditional target
  structure. No numeric read is emitted as a preamble to the whole conditional.
- A case selector evaluates at its authored case statement, once per execution.
- The existing runtime analogLoop evaluates its bound once at each loop entry,
  assigns the existing private _limit, checks the declared finite envelope, and
  uses that limit for this execution. Do not change it to a while-style bound
  reevaluation on each iteration. A condition in its body remains reevaluated
  on every reached iteration. Reentering a loop reevaluates its bound.
- Event payload references stay in the actual cross/above/timer expression at the
  owning analog_on. Reusing an Event source creates independent existing monitor
  occurrences. Event OR retains its ordered monitor composition and one body;
  it is not Boolean short-circuit logic. Unused monitors still retain evaluation.
- Persistent arrays keep v0.3 declaration initialization. Event-local/nested
  declarations initialize on each scope entry. A local selection cannot escape
  its lexical scope, procedure, event body, or generated occurrence. Event writes
  still establish no unconditional post-event initialization.

These rules prohibit analog_variable_read snapshots for new control/event leaf
bindings, hoisting numeric loads outside short-circuit expressions or loops,
converting event payloads into assignment temporaries, and changing root/static
initializers into per-evaluation or initial_step resets.

## Exact additive native schema

Extend existing operations, with no new operation or storage registry:

- AnalogIfArmOp: add Variadic<Nodal_VariableType> references and optional String
  reference_form. In the new form reference_form is exactly "storage". Existing
  condition_value/kind/dimension/reads and stage attributes remain.
- AnalogCaseOp: the same additions; existing selector_* attributes remain.
- AnalogLoopOp: the same additions; existing bound_*, finite envelope and static
  trip-count attributes remain.
- Primitive Nodal_AnalogEventOp (cross/above/timer and lifecycle subclasses): the
  same additions; existing arguments dictionaries, event_reads and analyses
  remain. Lifecycle events must have no numeric/reference operands or new form.
- Add paired optional generated_owner/generated_induction attributes to the
  three control owners so an actual typed storage use can pass the existing
  independent generated-ownership validator. Primitive events already have them.
- AnalogIfOp, AnalogCaseArmOp, AnalogEventOrOp and AnalogOnOp need no new numeric
  operands. OR continues to accept only event operands; on keeps its one event.

Nodal_VariableType is scalar !nodal.variable<kind,dimension>. Reject shaped
handles, quantities, analog_variable_read results, fill/snapshot values, unrelated
SSA values, duplicate/ambiguous leaf bindings, unknown form strings, and operand
lists without the explicit new form. Else arms and statically selected controls
have no dynamic references. A runtime typed form with zero references is valid
only when the parsed expression really has no mutable reads.

The absent-form/zero-operand legacy path retains existing scalar semantics and
serialization. The bridge selects the new form when an expression needs selected
storage bindings and binds every mutable leaf in that expression, including any
ordinary scalar variables mixed with selected leaves. It must not silently fall
back from an incomplete typed form to textual mutable-declaration lookup.

Binding labels are root identities for direct scalar variables and verified
projection semantic_paths for selected handles. They identify supplied SSA
operands, never encode coordinates. Distinct selected accesses to one root remain
distinct. Equal physical accesses may share an operand within a consumer when
source occurrences remain represented separately. No binding crosses consumers.

## Existing owners and narrow interfaces

Source capture remains AnalogProceduralConstruction.semanticValue/collectReads
and existing StorageReference/StorageSelection descriptors. Condition.selectedReads,
Selector.selectedReads, Loop.boundSelectedReads and event Argument.value already
carry direct storage access descriptions. Preserve their remap and source fields.
Replace requireScalarControl's blanket root-array rejection with scalar-result
and complete typed-source validation for the selected contract. Value shape must
be empty at these scalar consumers; the storage root itself may be shaped.

AnalogProceduralMlir.StorageEmitter adds a references(...) helper parallel to
reads(...). It calls the existing reference(...) projection emitter, returns
actual storage handles/types plus old-label-to-emitted-label bindings, and never
creates analog_variable_read. Reuse replaceLeaves for canonical source spelling.
For if arms, emit all pure aliases before the enclosing analog_if, not inside its
arms-only region. Each arm takes only its own reference operands. Case/loop
aliases precede that owner. Event aliases precede the primitive event occurrence
in the existing block. Actual root dominance and lexical visibility still apply.

Keep source truth in the existing immutable records. Do not add a module
projection registry or attach lexical array aliases to the structural scheduler.
Add role-based reference occurrences to storageOccurrences/source maps, using
paths such as .condition_0.reference_0.storage.projection_0, .selector.reference_0,
.loop-bound.reference_0, and .event.argument_0.reference_0. Name these as references,
not snapshots. Retain authored projection/root paths and generated lineage.

AnalogEvents.h/.cpp remain the canonical scalar source grammar owner. Add a small
derived per-owner binding helper, e.g. resolveAnalogSourceStorageBindings(op),
returning actual scalar handle/path/type information from operands. Extend
AnalogSourceExpression with an optional actual mlir::Value storageReference leaf
(or an equivalently explicit reference-kind field); keep existing declaration
and captured-read behavior intact. The parser resolves supplied storage labels
before function syntax, which matters for labels containing parentheses. Types
come from the actual handle and AnalogStorageModel, not kind/unit attributes.
Parameters and terminal accesses retain their existing resolution rules. In new
form any mutable source leaf absent from the supplied binding set rejects.

verifyAnalogProceduralExpressions compares parsed kind/unit and the exact parsed
mutable leaf set with both operands and existing control inventory. For primitive
events each argument's reads must equal its parsed leaves, and event_reads must
be their exact union and equal all operand bindings. No unused binding is allowed.
Direction, arity, unit/tolerance and finite-constant event validation is unchanged.

AnalogStorageModel remains the sole derived root/projection/owner resolver. No
new alias engine, arbitrary expression SSA framework or mutable semantic registry
is introduced. Binding-table construction is per consumer and derived, not truth.

## Independent native placement and definite assignment

NodalOps.cpp's verifyStructuredReferences must use actual handles for typed
control/event owners, via structuredVariableIdentity(..., requireInitialized=false),
instead of interpreting a selected leaf path as a declaration identity. Legacy
root-only inventories continue through their existing checks. Inventory agreement
is independently checked by the source grammar owner, not accepted as capability.

The existing StructuredInitializedSet and selected coverage resolver answer DA:

- Reachable if-arm predicates read the incoming conditional state, never writes
  in an earlier successful arm. Case selectors read incoming state before arms.
- Loop bounds read incoming state before body transfer, including a zero-trip
  possibility; a first-iteration write cannot justify its bound or earlier read.
- Primitive event payloads read incoming state; handler writes cannot justify
  their monitor. Handler-only writes do not initialize following statements.
- Each bound handle is checked with structuredVariableIdentity(..., true, input),
  which invokes isStructuredStorageInitialized for its exact selection. No
  requirement to initialize an entire root merely to read an initialized lane.
- Preserve branch intersection, reachability, finite literal promotion, lexical
  reset/removal, zero-trip paths, and nearest-loop break/continue behavior.

Scala AnalogControlFlowRuntime already carries selected coverage through these
sites; retain that owner and verify complete typed inputs rather than adding a
second DA truth. Event reads should derive from value.allReads recursively so a
malformed flattened inventory cannot erase a typed leaf.

resolveAnalogStorageReference must run with the actual consuming owner, so it
checks real lexical dominance, declaration order, component/procedure identity
and exact enclosing generated owner/induction. Both root and each projection
retain existing static bounds and type proofs. Constructing a pure alias does
not require initialized data; evaluating the consuming expression does.

The current event relocation guard compares event_reads to assignment root
identities. With selected labels this must instead derive observed root
operations from typed handles and compare to actual intervening assignment root
operations, through the ultimate OR/on consumer. Conservatively reject any
intervening write to an observed root; no new region-overlap theorem prover is
needed. Preserve legacy scalar behavior and same-block occurrence/owning-use rules.

## Target map and expression strategy

Reuse ShapedValueBackend.renderShapedProjectionProgram(handle, "0", privateIndex)
for each selected scalar reference. It already preserves immediate-input
noncontiguous mapping and bounds every static Integer intermediate. Keep its
linear sequence of map steps; recursively expanding prior formulas inline can
cause exponential source growth for deep view chains.

Allocate a distinct private Integer map temporary per selected leaf occurrence,
not one shared consumer target_map: two leaves must not both read the final map
value of the second leaf. These are derived target temporaries using existing
name reservation, scoped inside the actual generated occurrence where applicable.
No Real read temporary or whole-array snapshot is allocated for these consumers.

Emit only proven parameter/literal coordinate-map assignments as setup. For if,
setup goes immediately before the enclosing if group; otherwise it would break
else-if syntax. For case/loop, setup is immediately before that statement. For
used events/OR, setup belongs immediately before the ultimate analog_on, while
an unused primitive/OR uses its own emitted empty event statement. Traverse event
composition with an actual-operation visited set, deduplicating map work only by
(actual consumer operation, actual reference Value), never by label spelling. Never emit a
new event merely to initialize coordinates. No coordinate step may read mutable
storage, time, terminals, calls with state or history; the current static-index
profile is what makes this movement safe. Recompute this setup on each enclosing
statement execution, including loop iterations or handler invocations.

Render each actual storageReference leaf as rootTargetName[privateIndex] in the
canonical expression at its evaluation site. Scalar roots render as their
existing target names. The canonical operators still render as &&, ||, ?: and
the existing arithmetic/functions; do not linearize short-circuit numeric work.
Event monitor array values therefore remain live in @(cross(...)) etc. Runtime
loop bound values remain live until the existing _limit assignment at entry.

prepareEventStorage/verifyAnalogProcedureTarget must admit and validate these
reference layouts before writing output. Preserve transactional target rejection,
signed-32 target arithmetic, scalar output sentinels, and generated storage scope.
The generated target's ownedRootIntegerRead textual checks need a typed-form
branch that consumes verified kind/bindings rather than rejecting every selected
Boolean case or native Integer bound. Preserve the separately declared finite
control nesting/loop/history profile; do not treat additional read operands as
permission to add histories or illegal placement.

AnalogEventReparse already accepts indexed Real RHS expressions and the compact
Integer map assignments. Add focused controls for their occurrence in predicates,
case selectors, ternary loop-limit RHS and all legal event slots; retain strict
symbol/array/index checks. No broader raw HDL grammar is authorized.

## Legal compositions and unchanged exclusions

Required here: scalar .at after legal row/plane/slice/reshape/flatten chains on
owned writable Real storage; compound Real arithmetic/comparison/Boolean
expressions using those leaves; legal pure registered math functions; public
Boolean case; cross/above monitored values and matching Real tolerances; timer
Real time quantities; ordinary, handler-local, and supported generated bodies.
Reused expression handles must observe intervening writes at later consumers.

A pure shaped fill/view projected to a scalar is a different value-source form,
not a storage reference. Do not fake a Variable root or eagerly materialize that
form as a control read. Any such currently unbound source composition must remain
an explicit capability boundary/open required work until its scalarization or
typed deferred value contract is independently implemented. This gate does not
claim all shaped-value expressions are completed merely by direct storage refs.

Unchanged exclusions: runtime dimensions/indices, generated-induction-dependent
indices, non-Real arrays, bare shaped control values, implicit Real-to-Integer
casts, arbitrary map/zip/reduce producers, generated persistent root initialization
(v0.3 boundary), cross-component/procedure/sibling-occurrence references, nested
events, history-bearing monitors under runtime if/case/loop placement, and
contributions/ddt/idt/noise/transfer/waveform operators in prohibited procedural
contexts. In particular, adding scalar array reads does not expand the approved
user-function placement contract: user-defined calls remain excluded from
procedural/event bodies. Do not use such a call to fabricate a public loop witness.
Compound selected scalar initializers are not closed by this control/event
contract and remain separately required work.

## Focused acceptance witnesses (planned, not executed)

Public positives:
1. Initialized ordinary persistent Real array: if and Boolean case on two distinct
   selected lanes; mix a scalar flag with selected comparisons and &&/||; reuse
   a source expression before and after a selected write.
2. Event-local array initialized in initialStep: predicate and Boolean case read
   initialized selections, with another uninitialized sibling never read.
3. Ordinary nested runtime loops with existing scalar Integer bounds update a
   lane and reevaluate an array-based predicate inside each reached inner
   iteration; include break/continue order. Do not turn this into a generated
   nested-loop positive, because the existing generated profile is narrower.
4. Cross/above/timer use selected Real/time lanes and legal matching tolerances;
   OR and reuse create distinct monitor occurrences without value snapshots.
5. Symbolic module-static shape/index, noncontiguous slice->flatten->at, and a
   supported generated occurrence with locally initialized storage. Generated
   root initialized arrays remain a negative, not a fabricated positive.

Native positives: all of the above operand forms plus an Integer runtime bound
analog_select(real_gt(selectedLeaf,0.0),1,2), evaluating once at each entry; mixed
scalar/selected operands; two projections of one root; equal physical selections
with distinct provenance; ordinary and generated current-profile ownership.

Negative mutations: omit/duplicate/swap/forge a binding; extra unused operand;
wrong result kind/unit; pass a quantity snapshot, bare array or port carrier;
label an array root as a scalar; forge event per-slot reads/union; uninitialized
selected sibling; branch-only/event-only writes used to justify subsequent control;
loop-body write used to justify entry bound; foreign/out-of-scope/non-dominating
projection; generated pair absent or wrong; event moved across a same-root write;
runtime index/dimension; illegal nested/history-bearing event/operator placement;
mutable crossing direction; static control with mutable references.

Boundaries: singleton shapes, last valid static index versus end index, parameter
bounds rather than defaults, distinct expressions with equal defaults, signed64
semantic versus signed32 target limit, deep composed noncontiguous maps without
lane or expression-tree expansion, deterministic name collision avoidance and
source maps. Keep a native-reachable expression behind bool_and/bool_or and an
else-if whose numeric leaf remains inline, proving no inserted Real snapshot.
DA may remain conservatively all-leaves; this is not new value-sensitive DA.

Run only focused public/native/parser cases and directly affected scalar/event/
generated predecessors after implementation. Record actual source and generated
Verilog-A/AMS excerpts then; none are claimed by this pre-implementation gate. No CI,
full qualification or simulator/numerical claim is required or made.

## Alternatives and review

Chosen: additive direct scalar storage-reference operands on current owners,
existing canonical source grammar, one selected-coverage analysis, and compact
static coordinate setup plus live target array leaves.

Rejected: metadata-only projection lookup; eager analog_variable_read snapshots;
hoisted numeric predicates/monitor values; broad scalar-expression SSA rewrite;
a separate storage/control registry; flattening every map inline; enumerating
lanes/defaults; moving event-local initialization; silently admitting stateful
operators or user-function calls; claiming native-only loop tests cover a missing
public expression path.

Costs grow with authored references, expression size and projection depth, not
array cardinality. Existing module declaration scans and duplicate/projection
validation scans remain known costs; no speedup is claimed. Derived per-consumer
binding tables may avoid repeated operand scans without becoming a second source
of truth. Review these exact owners again against implemented production paths
before selecting completion checkboxes.
