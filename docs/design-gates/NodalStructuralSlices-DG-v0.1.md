# Nodal structural slices design gate v0.1

**Status:** Approved
**Scope:** public-api
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and compatibility

The owner authorized the Foundation implementation scope on 2026-10-18:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

The exact additive signature below is an implementation design decision within
that approved scope, not a separately owner-selected signature. F-043.B.1 and
ADR 0017 require fixed and symbolic slices. The historical v0.3 API inventory
has no slice method; this gate approves the necessary additive contract.

This new additive versioned gate supplements the v0.3 freeze. It changes none of
at, row, plane, flatten, reshape, existing shape diagnostics, or the hdlRange
staging contract. Update the maintained public API inventory/reference and
compile fixtures with implementation. Preserve historical acceptance evidence.

## Exact public surface

On Expr[Vec[A]] where A <: Data:

    def slice(lower: Dimension, upperExclusive: Dimension, axis: Int = 0): Expr[Vec[A]]

Dimension remains Int | Expr[Integer]. It is an endpoint type here, not a
positive-extent constructor. The literal 0 and 0.integer are legal lower bounds.
Negative literals compile as endpoints and reject during checked construction.
Axis is a host Int resolved at construction, zero-based, and must satisfy
0 <= axis < input rank. It is not a hardware or symbolic axis.

The interval is half-open and unit-stride: lower is included, upperExclusive is
excluded. No stride overload, descending range, clipping, implicit wrap or
runtime bound policy is introduced. Endpoint expressions are evaluated/captured
once in normal Scala argument order. Rank remains unchanged, including length-one
slices. Row and plane retain their distinct rank-reducing meaning.

For every legal parameter setting prove:

    0 <= lower < upperExclusive <= inputDimensions(axis)

Empty/reversed slices reject because zero-sized Vec is illegal. This does not
change the legal empty-domain behavior of hdlRange.

Result dimensions equal the input dimensions except that the chosen axis is
replaced by the canonical length upperExclusive - lower. Preserve exact element
type, signedness, domain/protocol obligations, module ownership and source
identity. This is a structural aliasing view, never allocation or a memory.
For an output coordinate q, the immediate input coordinate is identical except
inputCoordinate(axis) = lower + q(axis). Compose this coordinate mapping through
prior views; do not collapse it to an unrelated root-port identity or assume
physical contiguity. Flatten follows logical row-major element order even when
the source selection has a non-contiguous target representation.

## Supported checkpoint and retained obligations

The first implementation supports module input/output structural Vec values and
previously captured non-scalar shape results. It composes slice-to-slice and both
directions with row, plane, reshape, flatten and full-rank at. Scalar results and
uncaptured expressions are not structural carriers. A new slice on an internal
wire or unsupported owner must reject explicitly; there is no historical inert
slice surface to preserve.

Real/f64 is a required positive element capability. Any additional element type
is accepted only if the existing Vec declaration/bridge/native type contract
already accepts it; preserve the exact type, with a representative non-Real
positive and mismatched-element negative. This does not add shaped conservative
Terminal objects, storage, memory, connectivity adapters or conversions.

This is a read-only checkpoint. Writable slice/l-value selection, indexed writes,
alias-overlap/driver rules, storage and any remaining array obligations stay open
under the existing F-043 owners. Do not silently declare them out of scope for the
whole increment or mark a parent/required child complete on this checkpoint alone.

## Canonical construction and snapshot payload

Retain the existing session-owned shapeViews map as the only mutable structural
view registry. Add these immutable payloads (names may be made private as in the
existing records):

    ShapeSliceSelection(axis: Int, lower: Dimension, upperExclusive: Dimension)
    KernelShapeSliceSnapshot(axis: Int, lower: String, upperExclusive: String)

Append slice: Option[ShapeSliceSelection] = None to ShapeViewRecord and append
slice: Option[KernelShapeSliceSnapshot] = None to KernelShapeViewSnapshot. Existing
records without a selection retain their exact reshape/flatten interpretation.
The existing dimensions field remains the one authoritative result shape; its
selected axis is also the canonical length identity. Do not store a second
independently editable length field or create a third mutable shape registry.

Endpoint snapshot strings use existing canonical static-bound serialization:
signed decimal literals or full owned parameter/expression paths. Result dimension
strings keep existing dimension serialization: positive decimal literal, owned
parameter name, or owned expression path. Normalize those forms to canonical
identities only for comparison; do not infer equality from spelling fragments,
equal ranges or equal defaults.

Canonical length forms, in this priority order:
1. Both endpoints are authored integer literals: their exact positive difference
   is the literal result extent. Use checked/wide intermediates.
2. Lower is the literal zero: reuse upperExclusive's canonical identity directly
   as the result extent (including a direct parameter or compound expression).
3. Otherwise: retain one normal Integer analog_sub expression with ordered
   operands (upperExclusive, lower). Its captured identity is the result extent.

A singleton inferred interval is not an authored literal for rules 1 or 2.
Different parameter or compound-root identities are not interchangeable merely
because they currently have equal bounds/defaults. Literal identity compares
exact integer values; parameter identity compares the owned declaration;
compound identity compares the actual captured expression root. No general
algebraic-equivalence engine is introduced.

CandidateRuntime constructs the result type and canonical length through the
existing Integer expression path, then passes the result, input and typed
selection to ConstructionKernel/session validation through a typed optional
selection argument on the existing shape-view hook and shared structuralShapeInput
owner. A separate shapeSlice hook was an acceptable alternative but is unnecessary.
Do not register a successful view until validation succeeds. Slice endpoint roots join the existing parameter-expression
inventory, in addition to the already retained result-dimension roots. Mark all
participating parameter dependencies with the structural shape effect.

## Bounds and trust-boundary proof

Construction reuses StructuralBoundAnalysis and IterationDomain.arithmetic.
Bridge reuses shapeStaticBounds and canonical shape-dimension resolution.
Native reuses inferParameterIntegerBounds and the static shape-dependency checks.
Do not use IterationDomain.structural/halfOpen trip-count metadata for the slice
length: that is a 32-bit loop-count contract and accepts empty domains; this
contract uses the existing signed-64-bit positive shape envelope.

At each boundary independently check:
- owned static Integer endpoints and length, with finite signed-64-bit bounds;
- lower minimum >= 0, exact canonical length relation, and length minimum > 0;
- upper maximum <= source-axis minimum, OR exact canonical identity between
  upper and the selected source extent; the identity case admits slice(0, lanes)
  over an extent lanes without substituting a parameter default;
- exact source/result rank and all untouched axes, exact element type, and exact
  selected result extent identity;
- positive finite source/result dimensions and signed-64-bit worst-case cardinality;
- source identity, owner and expression-inventory validity, missing/cyclic input
  rejection, and the established deterministic structural dependency order.

This finite profile is conservative. General correlated inequalities such as
extent p versus upper p - 1 need not be accepted solely because a human can prove
them; unproven cases reject. Reuse canonical identity and interval arithmetic,
not default evaluation or a new symbolic theorem prover.

## Native interface, exact contract

Add Nodal_ShapeSliceOp, operation name nodal.shape_slice, with these operands:

    input: AnyType (verifier requires !nodal.shaped)
    lower: I64
    upperExclusive: I64
    length: I64

and these attributes:

    axis: I64Attr
    dimensions: StrAttr
    origin: StrAttr
    observability: StrAttr
    metadata: DictionaryAttr

One result: AnyType, required by the verifier to be !nodal.shaped. Restrict the
initial operation to the owning module body (HasParent<ModuleOp> is appropriate).
No materialization tag or legacy escape branch is needed: this operation is
intrinsically a structural slice. Existing shape_view remains cardinality-
preserving and its verifier is not relaxed.

Required metadata/attribute checks:
- dimensions exactly matches the result type's dimensions string;
- observability is source_mapped;
- metadata.storage is structural;
- metadata.access is read_only;
- metadata.semantic_path is the slice's canonical source-correlated identity;
- origin and metadata.input_path agree with the immediate input defining
  operation's metadata.semantic_path, and all belong to the same module.

Use nodal.const_literal, nodal.const_parameter_ref and nodal.const_expr for all
three i64 static operands. The length operand is the same cached static value
used to justify the result-axis token; its inclusion avoids accepting a forged
result type justified only by a coincident interval. For literals the type token
must be the canonical positive decimal value; for a parameter, its resolved
owned parameter symbol; for a compound root, its unique semantic_path.

Native canonical-length verification follows the three construction rules:
- literal endpoints require an equal literal length (checked wide difference);
- literal-zero lower permits the same canonical upper identity as length;
- otherwise length must be a directly owned const_expr with operator_name=sub,
  exactly two operands, canonically matching upperExclusive and lower in order.
For literal operands compare exact values; for parameter refs compare resolved
owned declaration identity; for compound roots require the actual unique static
SSA root. Do not accept distinct expression DAGs with coincident intervals.

Static dependencies must be direct-module, finite Integer expressions with
structural shape parameter envelopes. Check all three operands and the source
extent independently; metadata is not proof. Reuse/extract a narrow native
shape-dimension bounds helper if needed rather than copy the reshape signature
engine. Retain existing depth/cycle/overflow defenses.

Diagnostics:
- construction/native: NODAL-SHAPE-043-004;
- snapshot bridge: NODAL-BRIDGE-046.
Keep existing diagnostics for independently invalid predecessor operations.

## Bridge integration and target layout

The unified StructuralShapeOperation traversal continues to consume the existing
shapeViews inventory. A view with a slice payload emits nodal.shape_slice and
checks range/length rather than reshape cardinality equality. It still receives
a unique ordinal and source identity and publishes its shaped SSA result for
later consumers. Reuse the existing static value cache and owned expression
inventory. No root-port reread, element enumeration or extra semantic registry.

For this checkpoint, normalize and verify actual structural IR. Any target
backend without an implemented faithful slice/layout path must reject explicitly;
it must not silently drop the operation, flatten a Real array into a packed
analog scalar, allocate memory, or claim that generic MLIR acceptance proves
Verilog-A array lowering. A later target implementation must preserve the exact
coordinate/alias mapping and source/index map, including non-contiguous slices.
No complete analog-array or generated-HDL acceptance is claimed here. Applicable
existing target-boundary rejection witnesses belong in the focused validation.
Vec(Real) is a digital structural port in this checkpoint; a mixed-signal model
without an explicit analog/digital bridge can reject before shape/layout lowering.
Such a rejection is not a dedicated slice-layout diagnostic.

## Focused validation and architecture review

Positive public/native witnesses: fixed first and last axes, singleton length,
zero lower, bounded symbolic endpoints, full symbolic source-extent identity,
Real elements, another already-supported element type, slice-to-slice and
slice/row/plane/reshape/flatten/at chains, and deterministic repeated lowering.

Negative witnesses: negative/out-of-range axis; negative/empty/reversed/unsafe
endpoints; dynamic/unbounded/foreign endpoints; overflow; scalar/fake/foreign
input; forged remaining axes, rank, element type, result length, subtraction
operator/order/identity, structural effects, source identity and dependency
cycles. Native controls must mutate real emitted SSA rather than accept claimed
metadata. Preserve index/reshape/flatten composition predecessors.

Use focused local filters and representative native witnesses only. No CI or
full qualification. Preserve source-mapped output, unsupported capability cases,
and the existing F-043 remaining obligations. Report actual results, commands and
limits after execution rather than predeclaring success.

The existing view registry and static-expression/interval owners remain canonical.
The dependency scheduler stays iterative. Existing declaration scans and per-edge
proof costs are not a linear-time whole-bridge claim; measure only a concrete
new scaling concern. A single-axis operation composes for multidimensional
selection without eager element expansion or a speculative slice framework.
