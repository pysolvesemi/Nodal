# Nodal writable Real arrays design gate v0.1

**Status:** Approved
**Scope:** public-api
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and applicability

The owner authorized Foundation implementation on 2026-10-18:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

The signatures below are implementation decisions within that approved scope,
not separately owner-selected signatures. This gate supplements the existing
v0.3 freeze. The F-043 checklist and readiness record remain authoritative.
Prototype acceptance requires focused local checks and architecture review,
not CI or exhaustive qualification.

This is a bounded production contract for lexical `Variable[Vec[Real]]`, selected
reads/writes, writable views and shaped value initialization/copy. It contributes
to F-043.B.1/B.2/B.3 and their validation children. It does not complete F-043,
waive persistent root storage lowering, or remove other required analog-array,
conservative hierarchy, generation, source-map or target-layout work.

ADR 0017 remains binding: shape is semantic; row-major logical order is canonical;
structural storage does not silently become `Mem`; target syntax does not define
storage class. A `NodeArray` remains conservative topology, not numeric Real
storage. Read-only `Vec(Real)` digital ports do not become analog variables.

## 1. Existing signatures and compatibility

Preserve these existing source forms in `CandidateApi.scala`:

```scala
final class Variable[A <: Data] private[nodal] (...) extends Expr[A]:
  val dataType: DataType[A]
  def initialValue: Expr[A]
  def initialValueOption: Option[Expr[A]]
  infix def :=(value: Expr[A]): Unit

// On Module; these remain protected final methods.
def variable[A <: Data](dataType: DataType[A], initialValue: Expr[A]): Variable[A]
def variable[A <: Data](dataType: DataType[A]): Variable[A]
```

The public variable constructor is not exposed. `initialValue` continues to fail
when no declaration initializer exists; it is not a read of current storage.

Preserve all existing `Expr[Vec[A]]` shape methods and return types in
`CoreSemanticsCandidateApi.scala`, including `at`, `row`, `plane`, `slice`,
`flatten`, and `reshape`. Do not add `:=` to arbitrary Expr, shadow `Expr.at` with
a mutable return type, or add an implicit conversion from a read-only value to a
writable selection. Existing scalar assignments, event placement and diagnostics
retain their semantics.

## 2. Exact additive public surface

Add the member below to `Variable[A]`:

```scala
def writable: VariableSelection[A]
```

Add one private-constructor capability wrapper:

```scala
final class VariableSelection[A <: Data] private[nodal] (...):
  def value: Expr[A]
  infix def :=(value: Expr[A]): Unit
```

`VariableSelection` is not a Variable, Signal, Expr, DataType, or storage
allocation. Only an owned Variable root or a previously valid VariableSelection
can produce one. Its private implementation retains the existing root and shape
expression identity; it has no mutable declaration or view registry. Scalar
VariableSelection assignment delegates to the existing scalar procedural owner.

For the required Real-array capability, add exactly these extensions:

```scala
extension (selection: VariableSelection[Vec[Real]])
  def at(indices: Dimension*): VariableSelection[Real]
  def row(index: Dimension): VariableSelection[Vec[Real]]
  def plane(index: Dimension): VariableSelection[Vec[Real]]
  def slice(
      lower: Dimension,
      upperExclusive: Dimension,
      axis: Int = 0
  ): VariableSelection[Vec[Real]]
  def flatten: VariableSelection[Vec[Real]]
  def reshape(dimensions: Dimension*): VariableSelection[Vec[Real]]
```

Add the explicit immutable value producer to `Vec`:

```scala
def fill(
    dataType: DataType[Vec[Real]]
)(value: Expr[Real]): Expr[Vec[Real]]
```

Example: `Vec.fill(Vec(Real, lanes))(0.0.real)`. The existing initialized-variable
overload consumes it unchanged. A dimensioned value such as `0.0.V` carries its
existing canonical physical dimension into the shaped value and variable.
Uninitialized Real arrays use the existing uninitialized scalar Real dimension
rule; no new dimension inference from a later write is introduced.

This is deliberately a Real producer and Real selection extension set. Existing
scalar Integer/Boolean variables remain supported. No Integer/Boolean/digital,
nested-Vec, Struct, conservative-terminal, or arbitrary-Data array capability is
advertised by these additions. Such unsupported storage types diagnose through
the established procedural capability boundary.

`fill` is a value expression, not a procedural effect, implicit broadcast or new
module-level assignment. The scalar Scala argument is evaluated/captured once.
No array is allocated and no mutable variable is read merely by constructing this
Expr. The chosen type descriptor is the authoritative result shape. An existing
Expr cannot acquire a writable capability through fill or `.value`.

### Read timing and scalar evaluation

Like an existing scalar Variable used as Expr, `.value`, ordinary `.at`, and
read-only views denote expressions evaluated by their consumer. Each consuming
initializer or assignment creates fresh immutable read snapshots at that
statement's evaluation point, before any destination write. Reusing the same
Scala Expr after an intervening write observes the new current value. Wrapper
construction is not a hidden early load.

For fill, the scalar RHS is evaluated once per consuming statement; its captured
value is repeated over the result shape. It must not be reevaluated per lane.
A fill description that references mutable storage cannot be treated as a static
module constant or persistent static initializer. Scope/read/placement checks
apply at every use, including a reused description.

## 3. Shapes, ownership, selection and exact assignment

Required semantic shape support is nonempty positive fixed or bounded symbolic
rank/dimensions under the existing signed-64-bit cardinality proof. Preserve
owned parameter and compound-expression identities; never substitute defaults.
Reuse `StructuralBoundAnalysis`, the current shape helpers and the parameter
expression inventory. Runtime dimensions and runtime indices are not admitted
by this finite profile. Generated induction-dependent dimensions/indices remain
explicitly unsupported here, rather than being mistaken for module parameters.

`at` supplies the full rank and yields a scalar handle. `row` requires rank two,
`plane` rank three, and each fixes the leading axis. Slice retains the current
half-open, unit-stride, nonempty, all-parameter bounds contract. Flatten and
reshape retain exact cardinality/element proofs. Index arguments are captured
once in Scala evaluation order.

The root is a real AnalogProceduralRuntime variable with component, lexical
scope, source identity, declaration order and generated context. A writable view
allocates no new state. Selections may be prepared while the root is visible;
reading or writing them after it leaves scope is invalid. All assignments still
require the existing active analogProcedure and allowed event/control context.
Cross-component, cross-procedure and cross-generated-occurrence access rejects.

The initial checkpoint admits shaped-storage reads in assignment/initializer
consumers with explicit typed read operands. Selected or whole-array-derived
reads in conditions, case selectors, procedural loop bounds and event payloads
reject explicitly: those existing owners currently have textual inventories
rather than typed selected-reference operands. Existing scalar predicates and
array operations inside their bodies remain supported. Completing these new
selected consumers requires pure typed reference operands on the existing
control/event owners, preserving delayed evaluation and short-circuit behavior;
neither eager snapshots nor a metadata-only projection lookup is acceptable.
This is an open implementation boundary, not a waiver of required F43 behavior.

A shaped initializer/assignment requires exact rank, canonical dimension
identities, Real element kind and physical dimension. Equal cardinalities alone
are insufficient; explicit reshape can establish the desired shape. No scalar
broadcast in `:=`, element narrowing, whole-array arithmetic, digital/analog
conversion or layout conversion is implicit. Supported shaped RHS forms are
fill and initialized Variable/view expressions, including their validated
read-only shape compositions. Previously inert map/zip/reduce are not producers.

### One composed coordinate mapping

Use the existing immediate-input shape graph. For an output coordinate tuple:

1. Prefix index/row/plane prepends the fixed input coordinates.
2. Slice adds its lower bound on the selected axis.
3. Reshape/flatten converts the result coordinate to a row-major logical ordinal,
   then unflattens that ordinal in the immediate input dimensions.
4. Continue to the actual storage root, then derive its row-major ordinal.

This order matters for a noncontiguous slice followed by flatten/reshape. Never
replace a view with an unrelated root identity or assume physical contiguity.
Rank and view depth, not possible element count, bound map construction.

Canonical keys use literal integer values, actual owned parameter declarations,
and exact captured compound roots. Derived coordinate maps have deterministic
operator/operand structure and coordinate-variable numbering. Fold literal-only
arithmetic with checked wide intermediates, remove identity maps, and recognize
whole-domain cardinality-preserving views. Do not introduce an algebraic theorem
prover or declare independent equal-default expressions identical. Canonical map
keys and native descriptors are derived caches, never authoritative registries.

## 4. Ordered writes and RHS snapshot semantics

Retain every authored assignment and operation order. Multiple scalar writes to
the same or possibly aliased element are legal sequential updates. Later writes
occur later; this is not a parallel-driver conflict or conservative net identity
problem. Node-array distinctness rejection must not leak into procedural storage.

For every whole/view assignment, evaluate and capture the complete logical RHS
before writing any destination element. This includes exact self-copy, distinct
views of the same root, partial overlap, and aliases whose overlap is unknown.
Overlapping copy is supported by snapshots, not blanket rejection. Example:
copying `[0,2)` to `[1,3)` in values `[1,2,3]` produces `[1,1,2]`.

Different selected reads of one root remain distinct accesses. Repeated equal
reads within one statement may share a snapshot only when no effect intervenes;
no snapshot crosses statement boundaries. A fully initialized destination does
not excuse an uninitialized RHS. Shape/type/owner checks precede the write and
failed construction must leave no successful assignment/view registration.

## 5. Definite assignment without lane enumeration

Extend the existing procedural/structured dataflow owner, not a parallel array
analysis. Every storage root has either `Whole` or a finite set of canonical
initialized selection facts. A scalar is the existing whole-root special case.
The root key includes the declaration, owning procedure and actual generated
context; the generated ancestry is derived from existing ownership records.

Required transfer/query rules:

- A valid full initializer or whole-root fill/copy establishes Whole directly.
- Validate all source reads against incoming coverage before adding a write fact.
- An indexed write establishes that element only. A selected-view write
  establishes that selection only.
- A read succeeds for Whole, an equal normalized initialized selection, or a
  proven descendant of a fully initialized selection through the existing
  validated shape mapping. Otherwise it rejects as unproved initialization.
- A whole-domain reshape/flatten of a root is Whole for coverage purposes.
- Branch join intersects canonical facts; Whole intersect X is X. Preserve the
  existing reachability, condition, loop, break/continue and zero-trip rules.
- Event-controlled writes do not establish unconditional initialization after
  the event body, including initialStep. The outgoing state is the incoming
  state. Facts learned within the handler apply to later statements in that
  handler only. Re-entered lexical declarations reset their own coverage.
- Unchanged previously initialized storage stays initialized after writes.

An optional bounded promotion supports authored fixed-element initialization:
for an exact finite root cardinality C, count distinct proven in-bounds literal
root-flat offsets contributed by authored writes. Count==C proves Whole. Do not
enumerate C lanes or count symbolic keys as distinct offsets. If the two-scalar-
write-to-whole-copy witness is selected, this promotion becomes required for
that witness and must exist independently at every verification boundary.

No generic symbolic box union/intersection/coalescer is required. Complementary
symbolic slices do not establish Whole without an implemented proof. They must
reject a later unproved whole read; the documented limitation cannot override
an existing Whole fact. Branches with semantically equal but unrecognized
selection unions may reject conservatively. This is a proof-profile limit, not
permission to accept uninitialized reads or delete required array semantics.

Coverage storage grows with authored distinct accesses, not dimension maxima.
Set insertion/intersection and view normalization must be bounded by retained
facts and mapping depth. Naive branch-state copying may cost facts per branch;
record that honestly. More sophisticated region coalescing or persistent-set
optimization is optional and needs a concrete reason.

## 6. Source records and bridge ownership

`AnalogProceduralConstruction` and `AnalogProceduralRuntime` remain the storage,
read and ordered-statement owners. Keep `VariableRecord`, `AssignmentRecord`,
`Value` and control-expression records as the immutable transport. Extend value
shape and access payloads there; source-shaped RHS must distinguish a fill from
a selected read with typed fields, not infer semantics from a rendered string.
Existing scalar records retain their interpretation. The minimal immutable
payload is: ValueType gains logical dimensions; Value has a typed source form
(scalar expression with typed read leaves, Fill of a scalar Value, or Read of a
StorageSelection); Assignment.target gains a StorageSelection retaining its
canonical root Variable. A selection carries its typed projection chain, result
shape/dimension and lexical/generated ownership using existing owned static
expression references. Authored use paths remain provenance and are not physical
coverage keys. Equal separately authored row/slice selections must normalize to
the same region key. These are fields of existing records, not new registries.

Capture fill as a typed KernelExpr in the existing expression owner. Freeze its
shape/scalar source into the consuming procedural record. Do not add a global
fill registry. `ConstructionSession.shapeIndices` and `.shapeViews` continue to
own the existing module-level immutable structural selections. They are not an
authoritative registry for lexical storage projections. Procedure-local storage
projections are typed immutable descriptors retained in the existing procedural
Value/Assignment/control-expression records; they reuse canonical shape
arithmetic, bound proofs, coordinate rules and static expression references.
The existing module structural scheduler remains unchanged for those lexical
operations. No new global storage-view registry is introduced. The root variable
owns storage and element physical dimension; descriptors cannot independently
change them.

`AnalogControlFlowRuntime` consumes selected accesses/coverage within its
existing tree. Root-only `Set[String]` and the straight-line initialized Boolean
are not sufficient for a shaped target. Do not maintain those old fields as a
second, independently authoritative shaped initialization truth.

`AnalogProceduralMlir` emits storage aliases and snapshots in the proper
procedural lexical scope with real SSA dominance, following their retained typed
projection dependencies. `ScalaToMlirBridge` keeps the module structural
scheduler for its existing immutable consumers and shares static-expression
resolution/proofs with the procedural emitter. It must not add lexical storage
projections to that module scheduler, hoist aliases of a nested variable to the
module body, or borrow a port carrier.
A consumed RHS gets a fresh read group before its write/initializer. Any
independent source provenance on the expression remains available separately.

Scalar expressions containing selected reads bind each leaf to its distinct
actual captured projection/read SSA identity, crosschecked by the native
verifier. Two reads of different elements of one root cannot collapse to that
root's spelling. Repeated equal physical selections retain source occurrences
separately from their canonical storage key. These expressions bind to typed
read projections. Their existing canonical source-expression parser may bind a read
identity, but may not manufacture an index/slice or storage root from metadata
text. Shape coordinates, types and owner proofs come from the actual SSA access
graph. Do not add a raw HDL string-rewriting path.

## 7. Native contract and independent verification

Reuse the existing type constructors:

```text
scalar storage: !nodal.variable<"real", physical-dimension>
array storage:  !nodal.shaped<dimensions, !nodal.variable<"real", physical-dimension>>
array snapshot: !nodal.shaped<dimensions, !nodal.quantity<"real", physical-dimension>>
```

A snapshot is a value, never a writable handle. The handle shape has one
AnalogVariableOp root. Neither `!nodal.shaped<...,f64>` from a digital port nor
arbitrary metadata can create analog storage capability.

The selected shared native owner is a derived `AnalogStorageModel` descriptor
and resolver, used by procedural verification, event checks and target lowering.
It resolves an actual root, lexical/generated owners, element type/dimension,
logical shape and composed coordinate map from SSA. It owns no mutable storage
registry and reuses `ShapeModel.h` static-dimension/index/cardinality proofs.

Required existing-op extensions:

- `analog_variable` admits scalar or shaped storage results. Existing scalar
  initializer forms remain unchanged. A shaped initializer has a real typed
  shaped value operand; an uninitialized shaped declaration has none. A direct
  selected scalar Real read may likewise initialize a scalar variable through
  exactly one typed immutable scalar read result. Its kind/unit must match, and
  competing legacy initializer text is forbidden. The initialized flag, shape
  and physical dimension must agree with the actual operand. These initializers
  occur at scope entry; do not emulate them by declaration followed by assignment.
  Compound selected scalar initializer expressions require the existing source
  grammar with explicit read bindings or an explicit capability rejection; this
  addition does not justify a parallel scalar SSA framework.
- `shape_index`, `shape_view`, `shape_slice` gain a typed storage branch rooted
  at AnalogVariableOp, with lexical/generated ownership and real SSA dominance.
  Full indexing returns a scalar variable handle; prefix/view/slice returns a
  shaped variable handle. Existing immutable direct-module/read_only checks
  remain intact. Pure shaped snapshot/fill compositions must likewise retain
  value type and can never enter the storage branch.
- `analog_variable_read` accepts a scalar or shaped resolved handle and returns
  its exact quantity value or shaped quantity snapshot. It is an ordered read,
  not a freely movable/CSE-able pure shape operation.
- `analog_assign` accepts the corresponding resolved scalar or shaped target.
  Scalar expression/read behavior remains compatible. A shaped assignment has
  exactly one typed shaped RHS value and requires exact compatible shape and
  physical dimension. Shape metadata is never a replacement for that operand.

The only new producer operation required is a narrow shaped Real fill value,
proposed as `nodal.analog_array_fill`. It has one shaped quantity result whose
type owns dimensions; owner/source identity and paired generated attributes;
one canonical scalar source expression with its Real physical dimension; and
explicit typed scalar read operands when that source reads variables. Its scalar
expression is checked by the existing analog source-expression machinery, bound
to the real captured read operands, exactly as a scalar assignment. It evaluates
that scalar source once for each consuming statement. It is not a storage
operation or static-parameter declaration. The bridge must emit it at the
consumer evaluation point; a supplied source string alone proves nothing.

Implementation may make the existing op's scalar-versus-shaped operand forms
explicit with an optional form discriminator, but the actual types/operands must
agree. An unrecognized/ambiguous form rejects. Do not change old scalar textual
forms silently. Public signatures above are exact; private ODS field spelling
may follow existing conventions without changing these operand/type semantics.

At every native boundary independently verify:

- real AnalogVariableOp root, unique identity, legal declaration order, module,
  lexical scope, SSA dominance, procedure and generated ownership;
- finite positive symbolic shapes, exact element type/dimension, checked
  cardinality, indices/slice bounds, reshape proof and composed source mapping;
- no missing/cyclic/foreign static expression roots or forged access capability;
- exact selected read identities, complete RHS read binding, and per-access
  definite assignment through the structured tree before the write;
- fill scalar expression legality, dimension and complete read inventory;
- assignment order, analyses, event placement and persistent initializer rules;
- all read snapshots precede their consuming write and cannot be mistaken for
  aliases or moved across writes by normalization/optimization.

Use existing scalar/type/scope/definite-assignment diagnostics where applicable;
use the established F43 shape and bridge families for malformed shape/owner
contracts. Any genuinely new diagnostic is added to the existing catalog with
source path, not an uncatalogued success/failure escape.

## 8. Generated storage and persistent initialization boundary

Every generated declaration/read/write retains the existing paired generated
owner and induction identity and the actual lexical nesting. Native checks
compare these to the enclosing GenerateOp/AnalogOp, not strings alone. A root,
view or snapshot cannot escape to a sibling occurrence or enclosing procedure.
Array dimensions may use valid module static parameters; this does not grant
access to an enclosing mutable Variable. Ordinary procedural repetition is not
structural generation and cannot manufacture independent histories.

All target array storage, RHS snapshots and copy/fill counters for a generated
procedure live inside that generated occurrence's block. They must not be
hoisted to a shared module temporary. Source/index maps retain root/view paths,
logical coordinates and generated lineage separately from target names.

Initialized procedure-root variables are persistent under the existing event
contract. Their initializer must remain static, excluding mutable reads,
terminal samples and time-varying expressions. A static fill can meet that source
contract, but source acceptance alone does not prove target initialization.
Lexical declarations inside an event or nested scope initialize at their authored
execution point; full initialized-variable copies are supported there.

For the first target checkpoint, persistent root **shaped initializers reject
explicitly** until declaration-equivalent initialization is implemented and
validated. Do not emit an illegal scalar array declaration assignment, convert
it into an initialStep reset, reset the array every analog evaluation, or label
an analog-initial implementation equivalent without deciding its lifecycle and
sweep semantics. Existing scalar root initializers remain unchanged.

This is an open required F43 target obligation, not a permanent exclusion or a
completion claim. This gate permits the event-local/writable/copy checkpoint
to land with that truthful capability boundary. No F43 parent or required
persistent-storage deliverable can close on that partial checkpoint.

## 9. Target layout, copying and integer limits

Choose one compact unpacked Real carrier per root/snapshot, indexed by canonical
row-major ordinal: `real name[0:cardinality-1]`. This is an element-layout choice,
not a packed analog scalar, inferred Mem, digital signal or conservative node.
Keep dimensions/cardinality symbolic and source-mapped. No host enumeration of
lanes, parameter defaults or maximum envelope is allowed.

The initial array target is the already admitted event-containing procedural
profile and its legal generated procedures. Ordinary event-free procedure
lowering remains explicitly gated unless implemented in the same change. Event
restrictions on contributions, operators and nested controls remain intact.

Use a narrow shared `ShapedValueBackend` helper to factor reusable target shape
arithmetic from `AnalogVerticalSlice`. It consumes the actual native verified
storage projection chain and preserves existing node-array target output. It is
a derived layout renderer, not a second storage/shape registry or proof engine.
Extend `AnalogEventBackend.prepareEventStorage` and `renderStatement` through
that shared descriptor/helper; the existing event storage allocator remains the
owner of arrays, read snapshots and counters. Reuse name reservation, read capture
and procedural loop emission; do not add a parallel array backend. A fill consumes one scalar
snapshot and writes the destination in one compact loop. A whole/view copy uses
two loops: capture `temporary[k] = source[sourceMap(k)]`, then assign
`destination[destinationMap(k)] = temporary[k]`. A lexical declaration initializer
uses the same ordered fill/copy path at its authored location. Never emit a
noncontiguous view as a contiguous carrier without its map.

Known-disjoint copy elision and temporary-lifetime reuse are optional
optimizations. Unconditional snapshots are the simple correct baseline for alias
safety. The generated text grows with authored operations and mapping depth;
target copy execution and temporary capacity necessarily grow with selected
element count. Do not claim constant runtime/memory or a measured speedup.

The portable counter/index profile uses signed 32-bit target integers. Prove
1 <= each emitted carrier/copy cardinality <= 2147483647 for all legal parameter
settings, and every intermediate ordinal/map/count arithmetic expression fits
that representation. Semantic signed-64-bit shape acceptance does not bypass
this target check. Reject overflow/unproved target expressions, including forged
native inputs. Use actual symbolic counts in emitted conditions, never their
maximum or default. Compiler counters must not collide with authored names,
generated genvars, or surrounding loop-control variables.

The repository's structural target parser must recognize and validate the new
array declarations, scalar indexed loads/stores on both RHS and LHS, compact
loop forms, and integer remainder used by the composed maps. It must retain
bounds/owner/name checks and reject malformed output. Generated admission checks
must use verified semantic storage/operator ownership rather than expand old
fixture-specific operation-count patterns to recognize new examples. Internal reparse is
not independent OpenVAF/simulator acceptance.

Language evidence is the [Accellera VAMS 2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf),
sections 3.3, 5.7, 5.9, 5.2.1 and 8.2. The language permits compatible unpacked
array assignment; this target deliberately uses scalar loops and snapshots.
Analog-initial blocks have their own initialization ordering and analysis/sweep
behavior, so they are not an automatic substitute for declaration initialization.
This paragraph is language evidence only, not evidence of compiler/tool support.

## 10. Required public examples and focused checks

These are proposed-source fixtures, not existing generated-output evidence.
The no-argument event spelling is `initialStep`, without parentheses.

### Fixed event-local scalar writes and initialized-variable copy

```scala
final class WritableFixed extends Module:
  analogProcedure:
    on(initialStep):
      val source = variable(Vec(Real, 2))
      source.writable.at(0) := 1.0.real
      source.writable.at(1) := 2.0.real
      val copy = variable(Vec(Real, 2), source)
      val readAgain = copy.at(0)
      copy.writable.at(0) := 3.0.real
      copy.writable.at(1) := readAgain
```

This example requires the bounded literal-coverage promotion. The final read
must observe 3, not the value when `readAgain` was constructed. A missing write
to source element 1 must reject the initializer copy.

### Actual symbolic fill producer and overlap snapshot

```scala
final class WritableSymbolic extends Module:
  val lanes = param(default = 4.integer, range = 3 to 8)
  analogProcedure:
    on(initialStep):
      val values = variable(Vec(Real, lanes),
        Vec.fill(Vec(Real, lanes))(0.0.real))
      values.writable.at(0) := 1.0.real
      values.writable.at(1) := 2.0.real
      values.writable.at(2) := 3.0.real
      values.writable.slice(1, 3) := values.slice(0, 2)
      val copy = variable(Vec(Real, lanes), values)
```

The first three elements after the overlapping assignment must be 1,1,2. The
source/target cardinalities retain lanes symbolically. A generated counterpart
places the same event-local declarations inside an existing admitted hdlRange
procedure; each occurrence must own independent carriers, snapshots and counters.

Focused positives:

- Frozen scalar constructors/assignments and read-only Expr methods unchanged;
  private l-value capability; exact new fill signature and named arguments.
- Fixed/symbolic/compound positive shape, singleton, full-rank scalar selection,
  row/plane, first/last-axis slice, reshape/flatten and noncontiguous composition.
- Dimensionless and dimensioned Real fill/copy; scalar evaluated once; matching
  physical dimension; fresh read snapshots when an Expr is reused after a write.
- Whole initialization, exact selected initialization, branch both-arm writes,
  previously initialized root preserved, fixed coverage promotion if selected.
- Sequential equal/possibly overlapping scalar writes, exact self-copy,
  partially overlapping view copy, two different reads from one root.
- Public Scala -> normalized native -> actual deterministic Verilog-A event-local
  output, symbolic loops and source/index maps; generated per-instance locals.

Focused negatives:

- Writes through ports, immutable fill/snapshot values, forged wrappers or
  arbitrary Expr; unsupported array element types; cross-owner/scope/generation.
- Zero/negative/overflow shape, wrong rank, bad index/slice, unsafe static bounds,
  dynamic dimensions/indices, equal-default but unrelated shape identities.
- Scalar fill type/dimension mismatch, array shape mismatch without explicit
  reshape, digital port masquerading as analog source, unregistered fill source.
- Read-before-write sibling element, RHS before target initialization, missing
  branch write, zero-trip write, event initialization escape, unsupported union.
- Forged native root/type/access metadata, projection source, read inventory,
  generated owner/induction, missing initializer operand or cyclic view graph.
- Selected shaped-storage reads in control/event payloads without typed bindings.
- Persistent root shaped initializer target rejection, nonstatic root fill,
  ordinary event-free target rejection, target count/index overflow.

Run selected Scala construction/API/bridge tests, native semantic/backend cases,
public generated witnesses and directly affected scalar/event/shape predecessors.
No CI, unrelated full suites or exhaustive simulator matrix. Keep generated
outputs under `.work`; record commands, source revision, actual results and
limits in the existing F43 implementation note and PR. Update API inventory and
reference alongside adopted implementation. The actual final implementation
must receive the same narrow-owner/complexity review as this proposal.

## 11. Alternatives and bounded implementation sequence

Chosen: explicit writable wrapper plus a shaped Real fill value, shared
procedural storage/dataflow, existing shape maps, derived AnalogStorageModel,
ordered read snapshots and compact target loops.

Rejected: writable methods on arbitrary Expr; overloads that silently change
existing at results; implicit scalar broadcast; a second shape/storage/coverage
registry; eager lane expansion; node-array alias policy for procedural writes;
metadata-only handle proofs; a general symbolic coverage engine; a new scalar
SSA framework merely to make fill work; unsupported root initializer reset
rewrites; relying on target syntax to infer Mem.

The fill-value alternative is preferred over a writable.fill effect because it
serves the existing initialized-variable overload and ordinary exact assignment,
while keeping module-level expression capture pure. Explicit fill effects could
be additive later but are unnecessary here.

Bounded implementation order:

1. Adopt gate; preserve frozen fixtures; implement wrapper/fill capture, typed
   shaped records, selected reads/writes and the small coverage lattice.
2. Implement shared native resolver, typed storage branches and independent
   coverage/source/owner validation through the real bridge.
3. Implement event-local initializers, mapped scalar accesses, overlapping
   snapshot copies, generated local carriers and target integer/syntax checks.
4. Run the focused witnesses, document the actual supported matrix and pending
   persistent root target work, then review production ownership and scale.

Persistent root initializer support remains an explicit next required F43 target
step. It requires a lifecycle-correct compact initialization layout and real
witnesses before its capability rejection can be removed. This gate does
not move that work to another Foundation increment or another roadmap track.
