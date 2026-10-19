# NodalSequentialState-DG v0.1 — owned register updates and behavioral guards

**Status:** Approved; first checkpoint implemented and focused checks passed
**Scope:** public-api
**Baseline:** `b0fa301533b52bc3e0f150c54d3af498ced3f3b5`

## Authority and original scope

The repository owner requested: “start implementing all foundation unchecked
increment items in nodal repo from increment 43 and continue until all foundation
checklists are completed. Don't start another track just complete foundation
track.” The owner subsequently approved public commits/pushes and completed
increment integration with `[skip ci]`. This is implementation authority for the
necessary additive F56 contract, not a claim that the owner individually selected
each signature below. The 2026-10-18 prototype policy governs validation.

This version implements the first shared prerequisite of F-056.B.1–B.3 and the
behavioral-guard amendment F-056.B.1.1. It does not close the increment: exhaustive
action switches, flat FSM graph/actions/observations, local encoding/illegal-state
policies, memory ports and their sequential lowering remain required. Their
missing public additions receive explicit subsequent gate revisions before use.

The frozen [clock/reset v0.2](NodalClockResetApi-DG-v0.2.md), unified v0.3,
[ADR 0007](../architecture/0007-implicit-clock-reset-domains.md) and
[ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md) remain
authoritative. Legacy F19 FSM strings and F54 register-next intents are not
retroactively described as executable sequential models.

## Preserved API and additive predicate forms

Preserve `Reg(init)`, `Reg.uninitialized(type)`, both `RegNext` forms,
`when(Expr[Bool])(body: => Unit)`, `elsewhen(Expr[Bool])(body: => Unit)` and
`otherwise(body: => Unit)`, including their result types. Preserve exact recursive
register types and canonical enum ABI. No writable projected-register capability
is introduced.

Add compiler-only Boolean overloads of `when` and `elsewhen`, plus uniquely named
mixed Boolean/`Expr[Bool]` `&&` and `||` overloads, solely to admit the exact typed
closed predicate syntax. The Boolean-left mixed markers return Boolean; the
`Expr[Bool]`-left mixed markers return `Expr[Bool]`. Both remain compile-time-only
and must be consumed by the guard capture. This asymmetry preserves overload
resolution for nested runtime expressions and negation in pinned Scala 3.8.4; it
does not make an escaped mixed marker executable. Representative syntax:

```scala
when(MODE == 0):
  state := next
elsewhen((MODE != 0) && enabled):
  state := alternative
otherwise:
  state := fallback
```

The frontend consumes only owned Integer comparison symbols and closed logical
composition, with actual runtime Bool leaves. As in the structural-if gate,
actual Integer Expr-to-Expr equality is recognized only at this captured typed
boundary; ordinary host equality elsewhere is unchanged. Both mixed operand
orders retain every actual operand. Whole host Boolean `when` conditions are
rejected, and ordinary host Scala `if` retains its prior behavior. There is no
general Expr-to-Boolean conversion, name-based recognition, parameter-default
substitution or runtime evaluation of compiler-only markers.

The shared Expr[Bool] guard spellings also retain their existing analog-procedure
lexical-block behavior. Frontend classification uses the exact analogProcedure
owner or actual Variable/VariableSelection capability types before digital effect
validation; every analog-owned call checks the active procedure before evaluating
its predicate or callback, including deferred local helpers that escape their
lexical definition. The successful check has no construction effect and preserves
the prior predicate-before-body order. Runtime dispatch confirms the actual owner. Pure compiled
helpers retain this dispatch too. A context-free helper containing only host
mutation has no analog identity and remains rejected by the digital effect
profile. Names, packages and failed digital validation never infer analog context.
These compatibility paths do not introduce analog conditional semantics.

Pure comparison ownership/proofs are shared with F55. A parameter used only in a
behavioral guard is not classified as topology-changing: its state declaration
exists for every legal parameter value. Structural `if` remains structural.

## Capture, priority and lifetime

Each `when`/`elsewhen`/`otherwise` chain is one update root. A continuation must
immediately follow the preceding chain arm in the same source block; an orphan,
completed or interrupted chain rejects. Nested chains have their own lexical
frame and retain the outer update-root identity. All callbacks are checked before
execution, then captured once without choosing a parameter default or runtime
arm. Capture failure poisons the construction transaction.

The first true arm is selected. Within its reached sequential statement order,
the last assignment to a register wins. A nested selected update overrides an
earlier same-root update only when reached. No selected assignment means hold.
Two unrelated roots assigning one register reject, even if incidental expressions
look exclusive; one explicit chain expresses priority. Reset dominates enables
and ordinary next-state selection. Every RHS reads the pre-edge register value;
assignment does not mutate an elaboration-time register value.

Predicate operands and erased typing evidence use the shared Pure effect profile.
Behavioral bodies admit checked sequential updates and supported pure expressions,
not structural creation, mutable host effects, escaped branch locals or arbitrary
unattested callbacks. Separate compilation uses the shared checked attestation
owner; allocation metadata or a familiar method name is not an effect proof.

## Local domains and reset semantics

An executable local binding retains actual typed Clock and optional Reset operands,
the exact owned domain, edge, policy and polarity. Clock and Reset remain distinct
from Bool. Metadata strings cannot substitute for actual producer edges. Bound
domains apply sequential validation to their register updates; legacy unbound
declaration-only IR remains separately identifiable and does not claim execution.

`Reg(init)` and initialized `RegNext` use the initializer as a reset value, not a
power-up value. A resetless executable binding with such an initializer rejects.
`Reg.uninitialized` and uninitialized `RegNext` explicitly opt that register out of
reset, including within a reset-bearing domain; no constant, random initialization
or hidden boot state is supplied. Direct synchronous and asynchronous reset
policies retain their distinct semantics. Async-assert/sync-release requires the
actual F57-owned release structure; this checkpoint must reject unsupported
execution rather than reinterpret it as ordinary asynchronous reset.

The first executable binding profile uses direct, owned scalar input Clock/Reset
values with unambiguous local-domain provenance, including actual compiler-owned inputs for an external root domain.
Descriptor-only static projections and readable Interface capability references
retain construction compatibility; unsupported execution rejects explicitly before
output. Source capture alone is not an executed projected-binding result.

Local bindings are the minimum F56 prerequisite. Full generated-clock structure,
CDC/RDC, reset controllers and hierarchy binding remain F57/F58 responsibilities.
Unsupported binding combinations are diagnosed explicitly, not replaced by dummy
clocks, reset values or assumed-safe cross-domain operands.

## Canonical representation and lowering

Reuse the construction transaction, explicit lexical capture records, shared
digital type/value/region/static-predicate/domain owners and physical register SSA
identity. Behavioral alternatives carry actual predicate and capture operands,
owned regions and actual target/RHS edges. Static-to-behavioral predicates are
checked conversions, not textual conditions.

One native sequential owner validates binding, scope, types, update roots and
priority independently of frontend attestation. It normalizes checked updates to
actual next-value selection with register-hold fallback and explicit clock/reset
update semantics. The combinational dependency verifier still checks consumed
guard/RHS values; registers remain graph cuts. A report-only plan is insufficient.
Normalization is transactional, bounded before expansion, deterministic and
source-correlated. Exact budgets and the accepted native/CIRCT subset are recorded
with executed results before acceptance. No target HDL result is implied here.

## Alternatives and compatibility

Rejected: executing host Boolean branches; topology specialization for behavioral
parameters; a second expression emitter; text-only conditions; treating legacy
FSM descriptors as executable; implicit randomized preset; invented reset release
synchronizers; arbitrary last-writer priority across unrelated roots.

Selected: additive compiler markers over frozen source forms, the existing checked
callback machinery and canonical capture/value owners, explicit same-root lexical
priority and separate executable binding. Old compile-only candidate acceptance
does not prove runtime semantics. Unsupported future FSM forms remain source
compatible where frozen, but must reject clearly when executed until implemented.

## Focused acceptance obligations

- Actual public Reg/RegNext capture through bridge and native next-state lowering;
  hold, reset-versus-enable, both edges/polarities and explicit uninitialized state.
- Nested first-selected chains and same-root last-write witnesses; unrelated roots,
  orphan/interrupted continuations and escaped values reject.
- Parameter-only and both mixed guard orders preserve source parameter identity
  across changed defaults, with no change in register existence.
- Predicate/evidence write, structural declaration, host mutation, stale/missing
  separate-library attestation and foreign domain/type negatives.
- Independent native mutations of condition/capture/target/RHS/binding/reset edges,
  exact recursive enum/Struct/Vec compatibility and bounded deterministic replay.
- Directly affected F54 storage/enum and F55 region/predicate/driver predecessors.

These checkpoint obligations passed the focused checks recorded in
[increment56 readiness](../implementation/increment56-readiness.md#focused-checkpoint-evidence).
Full F56 checkboxes remain open until its remaining original features and focused
checks are implemented and integrated.
