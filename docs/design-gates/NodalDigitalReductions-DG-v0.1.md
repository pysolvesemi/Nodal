# NodalDigitalReductions-DG v0.1

**Status:** Approved
**Scope:** F55/BT ordered widening left and balanced whole-value reductions
**Decision:** one typed callback DAG with independently checked finite width transfer

## Authority and compatibility

The explicit all-unchecked-Foundation authority recorded in
[NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md) covers the
original F55 and linked BT-01/02/03 obligations. This records the selected additive
contract under that authority, not a claim that the user separately selected each
signature. Preserve reduce((Expr[A], Expr[A]) => Expr[A]): Expr[A] and add
reduceBalancedTree with the same callback type. Consume the approved
[Struct value API](NodalDigitalStructValues-DG-v0.1.md), without another schema or
member registry. Existing homogeneous map/zipWith/fold/scan contracts remain.

## Observable semantics

Reduce is strict row-major left reduction. Balanced reduction combines adjacent
pairs left-first, carries an odd final value unchanged, and repeats. There is no
padding, implicit identity, seed, state or arithmetic reassociation. The outer
semantic Vec shape selects the reduced population; inner Vec/Struct shapes remain
exact. Explicit flatten/view operations, not flat-carrier arithmetic, change that
population. Every legal count is positive and finite.

A proven singleton returns its actual whole element and invokes no callback. A
symbolic envelope including larger counts captures one pure callback template;
the singleton specialization bypasses the combine. One whole-value callback DAG
preserves shared predicates and cross-field computation. No callback replay per
field or per lane occurs. Existing callback effect restrictions still apply.

The complete kind/nominal Struct/ordered field/enum/inner-shape skeleton remains
fixed. Only selected Bits/UInt/SInt width leaves vary. Exact complete storage ABI
never becomes width-family equality. Source Bool and enum leaves remain invariant.
Native Bool's existing Bits<1> ABI is preserved: exact literal Bits<1> may have an
invariant template, while parameter/expression widths merely proved to be one do
not gain this exemption. Actual paired typed templates determine native slots;
no role/profile/slot-count metadata is a proof.

## Finite transfer and universal validation

For each selected scalar width, preserve the raw actual L/R formal DAG and infer
only invariant, fixed C, max(L,R)+c, or L+R+c transfer, with independently checked
formal-independent C/c and nonnegative growth. Reject genuinely coupled width
recurrences outside this finite profile. Cross-field value predicates remain
legal when their actual width obligations are independently satisfied.

Derived prefix/tree width formulas use the shared checked static Integer owner,
including ceil_log2 and choose_zero, without parameter-default specialization.
Fixed-width profiles preserve the original singleton width. Odd carries preserve
the actual child type. Every reachable intermediate complete type and every
retained body operation must satisfy ordinary width, literal, conversion,
comparison/select, enum, shape, lexical and ownership rules. Checking only first
and final types or sampling parameter defaults is insufficient.

Private immutable environments admit actual selected formal identities as exact
substitutions or conservatively derived domains. Equal intervals do not establish
semantic equality. The real collection admission owner derives contexts from the
actual signature, transfer and cardinality/topology; ParameterModel independently
checks identities, bounds, scope and expression composition. No public assumeBounds
API, mutable global registry, trusted serialized certificate or second evaluator
is introduced. Exact prefix/tree/body-type queries return instantiated canonical
types only after complete admission. Invalid or absent nodes reject.

Raw L/R operands must survive temporary first-combine proof substitution: proving
max(L,R)+1 as W+1 must not rewrite the captured or emitted template into W+1.
Independent source, bridge and native checks retain that distinction.

## Scope, resources and downstream boundary

Explicit captured static operands retain actual argument segments, lexical
provenance and the checked enclosing environment. Permit ordinary homogeneous
collections inside one reducer and a reducer inside ordinary collections; reject
a reducer nested within another reducer in this initial finite profile. Nested
Vec/Struct type skeletons are unaffected by that lexical limit.

Preflight known paired-signature/schema/context costs against the shared 4,098
expression/argument bound before constructing formals or invoking the callback.
Guard actual callback expression allocations incrementally during its single
capture; unknown closure body size is not claimed pre-counted. A proven singleton
bypasses paired-signature allocation as well as callback invocation. Preserve
existing type/static depth/node caps
and bound actual node-context analysis work at 65,536; no unbounded Cartesian
proof expansion. Balanced topology uses at most 63 checked rounds, without
constructing an absent signed-2^63 subtree. Physical count never expands the
retained callback DAG. Conservative domain overapproximation may reject an
unproved case; it must not skip an attainable context or forge equality.

Module orchestration must make actual immutable contexts available to ordinary
verifiers without recursive full-verifier cycles. Candidate contexts are not
published verified facts. Caches include the relevant use/environment identity.
Actual materialization operands and alias intent remain intact. Repeated proof
queries can have non-linear cost within finite budgets; no whole-pipeline
linearity or comparative speedup claim is implied.

This is combinational source/bridge/native semantics and exact type-query support.
F58/F65 consumers retain hierarchy/layout/lowering ownership; actual target HDL,
registered bridges, pipeline insertion and target execution are not fabricated by
this gate. The existing bounded iteration ownership remains shared, not a fourth
public loop category.

## Alternatives and focused acceptance

Reject lane expansion, per-field callbacks, default specialization, hidden
saturation, endpoint-only validation, nominal-name-only equality, opaque metadata
proofs and a parallel width arithmetic engine.

Require actual public source→bridge→native witnesses for counts 1/2/3/5/6,
symbolic shapes/widths, widening sum/product/ordered subtraction/concatenation,
fixed-width singleton and odd carries, whole-record winner and shared cross-field
predicate with enum/Bool/nested Vec fields. Inspect distinct raw L/R SSA edges and
exact final/prefix/tree/body types. Measure small/large count graph size and
callback counts without unsupported performance claims.

Causal negatives cover first-valid/later-invalid bitwise and conversions,
interior-overflow despite valid endpoints, forged final/intermediate types,
slot/order/identity/capture mutations, false singleton/default proofs, unsupported
coupled recurrence, zero-capable count and resource exhaustion. Retain directly
affected parameter/type/value/enum/shape/collection/materialization and public
callback predecessors. Design approval and isolated checks do not close F55;
fresh coordinated replay and the remaining bounded-loop obligations are required.
