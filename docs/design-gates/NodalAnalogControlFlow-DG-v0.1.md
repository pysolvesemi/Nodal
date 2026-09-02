# Nodal Analog Control Flow Design Gate v0.1

**Status:** Approved for staged implementation  
**Scope:** public-api  
**Scope:** source-semantics  
**Scope:** definite-assignment analysis  
**Increment:** 34  
**Predecessor:** Increment 33 analog variables and ordered procedural assignment  
**Public compatibility base:** Nodal API v0.3

## Purpose

This gate defines structured control flow inside an `analogProcedure` without
weakening the semantic separation established by Increments 32 and 33.

Control flow determines which ordered procedural statements may execute. It does
not turn assignment into an unordered equation, additive contribution,
conservative connection, solver equation, or backend text fragment.

The first implementation tranche freezes the source-semantic statement tree and
branch-sensitive definite-assignment rules. Public construction integration,
first-class MLIR operations, native verifier coverage, source-map serialization,
and target lowering remain mandatory follow-on work in the same increment.

## Structured statement model

An analog procedural region retains a nested statement tree containing:

- lexical blocks;
- ordered variable declarations, reads, and assignments inherited from Increment
  33;
- `if` / `else-if` / `else` selection;
- non-fall-through `case` selection with exact labels and at most one default;
- statically exact loops;
- runtime loops with an explicit finite maximum;
- `break` and `continue` scoped to the nearest supported loop.

Every block and control statement has a non-empty stable identity. Identities are
unique within one procedural region and are independent of source coordinates,
tree traversal counters, or backend rendering order.

Authored statement order remains authoritative inside each block. Source file,
line, and column are provenance and deterministic tie-breakers only.

## Conditions and static/runtime staging

Every conditional expression is a dimensionless Boolean value.

A condition is classified explicitly as either:

1. **static**, with a compile-time Boolean result; or
2. **runtime**, with no compile-time selected result.

The compiler must not infer staging from the statements inside a branch. A static
condition may remove unreachable alternatives only after source-semantic
validation. A runtime condition remains a first-class control-flow operation.

An invalid or unknown physical dimension cannot be converted into a valid
Boolean condition merely because the result is used by control flow.

## Conditional selection

An `if` chain uses first-match semantics.

For a fully static chain, only the first statically true branch is reachable. For
a runtime chain, every branch that can be selected is analyzed from the same
incoming definite-assignment state. If no final `else` exists, the incoming state
is also a reachable path.

Control flow does not create implicit priority between assignments outside the
structured source order.

## Case selection

The initial case contract accepts dimensionless integer and Boolean selectors.

- Labels are exact constants of the selector kind.
- Duplicate or overlapping labels are illegal.
- Each arm is independent and there is no fall-through.
- A default arm is optional and unique.
- Without a default arm, the unmatched incoming path remains reachable.
- Real-valued, quantity-valued, wildcard, masked, range, and pattern case labels
  remain deferred until a separate capability contract is approved.

A statically known selector may select one reachable arm after all labels and
statement structure have been validated.

## Bounded loops

Every retained loop has an explicit finite envelope:

- `minimumIterations >= 0`;
- `maximumIterations >= minimumIterations`;
- a runtime-bounded loop has `maximumIterations > 0`;
- a static loop has one exact compile-time trip count, so minimum and maximum are
  equal.

Unbounded `while`, data-dependent loops without a proven finite maximum,
recursion used as iteration, and hidden FSM or latency inference are illegal.

A loop with `minimumIterations == 0` cannot make a variable definitely
initialized after the loop solely through assignments in its body.

A loop with at least one guaranteed iteration may propagate initialization only
when every reachable first-iteration exit path—including normal completion,
`break`, and `continue`—has initialized the variable.

The compiler may later unroll a static loop only as an explicit,
semantics-preserving transformation. Source-semantic identity and source maps
must survive that transformation.

## Break and continue

`break` and `continue` are legal only inside the nearest runtime-bounded loop in
the initial contract.

- `break` exits that loop.
- `continue` skips the rest of the current iteration and reaches the next loop
  test or bounded termination point.
- Neither operation may target an outer labeled loop.
- Neither operation is legal in a static loop.
- Statements after an unconditional reachable `break` or `continue` do not
  contribute to the normal path.

Labeled exits and multi-level exits remain deferred.

## Branch-sensitive definite assignment

Increment 33 straight-line initialization state becomes a control-flow dataflow
fact.

For a conditional or runtime case, the state after the statement is the
intersection of the definitely initialized sets from all reachable normal exit
paths. A branch that exits through `break` or `continue` does not reach the
following statement and therefore does not participate in the normal-path
intersection; its state is propagated to the enclosing loop.

A missing `else` or missing case default contributes the incoming state as an
unmatched path.

Reads in a condition, selector, loop bound, assignment value, or explicit read
must be definitely initialized on the path where the read occurs.

The analysis is monotonic: assignment can add a definitely initialized variable,
but control-flow merging cannot invent initialization that is absent from any
reachable path.

## Static and unreachable code

Static selection may prove a branch unreachable for dataflow. The compiler still
validates the unreachable branch's syntax, identities, scalar kinds, dimensions,
case labels, loop bounds, ownership, and structural legality.

Read-before-write diagnostics are required only on reachable source-semantic
paths in this initial contract. A later warning policy may report suspicious
unreachable reads without changing legality.

## Stable diagnostics

Increment 34 reserves `NODAL-ANALOG-034-*` for at least:

- `NODAL-ANALOG-034-001` empty or duplicate control-flow identity;
- `NODAL-ANALOG-034-002` invalid condition kind or dimension;
- `NODAL-ANALOG-034-003` inconsistent static/runtime condition metadata;
- `NODAL-ANALOG-034-004` read before definite initialization on a reachable
  control-flow path;
- `NODAL-ANALOG-034-005` invalid case selector;
- `NODAL-ANALOG-034-006` duplicate case label;
- `NODAL-ANALOG-034-007` case label and selector kind mismatch;
- `NODAL-ANALOG-034-008` invalid or unbounded loop envelope;
- `NODAL-ANALOG-034-009` invalid static-loop trip count;
- `NODAL-ANALOG-034-010` illegal `break`;
- `NODAL-ANALOG-034-011` illegal `continue`;
- `NODAL-ANALOG-034-012` unsupported case pattern or fall-through request;
- `NODAL-ANALOG-034-013` unsupported labeled or multi-level loop exit;
- `NODAL-ANALOG-034-014` invalid control-flow read or assignment target;
- `NODAL-ANALOG-034-015` empty conditional, case, or case arm.

Diagnostics retain the stable control statement path and source location whenever
available.

## Semantic retention and lowering boundary

Increment 34 must ultimately retain the structured statement tree through:

- public Scala construction;
- compiler-owned source snapshots;
- deterministic reproducibility evidence;
- Scala-to-MLIR serialization;
- first-class native Nodal IR;
- native verification and compiler-boundary diagnostics;
- source-map round-trip tests.

The first tranche supplies the executable source-semantic analyzer and exact
contract. It does not claim those remaining integration layers are complete.

No control-flow operation is executable solver code in this increment. Verilog-A
or Verilog-AMS emission, event scheduling, residual/DAE construction, and solver
execution remain deferred to their owning increments.

## Deliberately deferred

This gate does not enable:

- multiple independent top-level analog procedural regions;
- unbounded loops or runtime loops without a finite maximum;
- labeled or multi-level `break` and `continue`;
- case fall-through, wildcard, masked, range, or real-valued case labels;
- arbitrary inference from ordinary Scala `if`, `match`, `while`, or collection
  iteration;
- residual/DAE construction or solver-state allocation;
- solver execution or analysis scheduling;
- target legalization;
- Verilog-A or Verilog-AMS procedural emission.

## Acceptance

Increment 34 is complete only when the approved semantics are integrated through
the public construction path, source snapshots, first-class compiler IR, native
verifiers, stable diagnostics, deterministic serialization, source maps,
positive and negative fixtures, mutation tests, Core CI, and every inherited
workflow on one exact head.

The roadmap item remains unchecked until the implementation is merged into
`dev`, post-merge validation passes, and immutable evidence is recorded in a
separate closure pull request.
