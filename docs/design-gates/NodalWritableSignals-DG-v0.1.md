# NodalWritableSignals-DG v0.1

**Status:** Approved
**Scope:** additive F55 writable digital shape selections
**Decision:** private capability over actual owned ShapeModel edges

## Authority and compatibility

The explicit Foundation implementation authority recorded in
[NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md) covers this
required public iteration prerequisite. This does not claim separate user choice
of a signature. Preserve readonly Expr.at/row/plane/slice/flatten/reshape and root
Signal assignment. Follow the existing Variable.writable/VariableSelection.value
capability pattern rather than giving arbitrary expressions assignment authority.

## Public contract

Signal[A].writable returns privately constructed SignalSelection[A]. Its value
property returns the actual readonly Expr[A]. Supported shape at, row, plane,
slice, flatten and reshape operations return another private selection using the
same canonical shape constructors and bounds/ownership checks. Selection := rhs
records an assignment to the actual selected expression, with exact complete RHS
ABI. The capability is neither a cast nor a user-supplied target path.

Only direct same-module ordinary wire/output roots are writable in this profile.
Source admission follows actual original expression edges and owned shape records;
bridge/native repeat their own scope/root/direction/type checks. Input/inout,
foreign/escaped/generated roots, computed aggregates, ordinary aliases and
unrecorded forged carriers do not acquire write authority. Obtaining a selection
cannot waive all-legal parameter bounds, nonempty shape or cardinality checks.

Registers expose no writable selection. Forged projected register_next, including
identity views, rejects independently in bridge/native. Existing direct root
register_next remains checked intent; partial state-update execution and priority
belong to F56. Pure collection callbacks still forbid assignment, including this
new spelling. No callback effect whitelist is widened.

## Shared owners and finite limits

Reuse canonical shape projections, source assignment records, bridge dependency
scheduling and native storage root verification. The shared DigitalAssignmentModel
proves coverage, disjointness and acyclic ordinary dependencies from these actual
selected SSA values. A capability establishes write eligibility, not a claim that
its writes are mutually legal. Unknown overlap and uncovered reads reject.

Readonly aliases and computed view roots remain conservative rather than gaining
implicit write authority. Current structural selectors use existing finite
all-legal static proofs. Loop induction requires the separate actual loop-domain
owner; this checkpoint does not invent dynamic-index or cross-iteration proofs.
Source and bridge target ancestry are bounded at 128 projections; native ShapeModel
retains its own independent proof limits. Selection construction does not expand
physical lanes. No HDL emission, register execution or completed F55 claim follows
from it.

## Alternatives and focused acceptance

Reject := on every Expr, generic casts, mutable exposed constructors, metadata-only
capabilities and a second shape algebra. Preserve exact scalar/enum/Struct ABI and
existing source-origin ownership.

Require actual public source→bridge→native positives for disjoint lanes, output
lanes, lane-read dependencies, row/plane/slice/column writes, composed views,
symbolic bounds/widths, whole Struct lanes and original root assignments. Exact
negative causes cover overlap, cycles, uncovered lanes, unknown symbolic overlap,
wrong direction/scope/ABI, forged records/edges and bounds. Native forgeries cover
register projections and identity views independently. Actual external compilation
must reject private construction, computed-value writable access and map/fold
writes. Run affected storage/driver, Struct, shape and callback predecessors;
record fresh integrated results separately from isolated stage evidence.
