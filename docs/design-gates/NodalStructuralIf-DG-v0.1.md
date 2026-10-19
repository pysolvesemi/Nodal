# NodalStructuralIf-DG v0.1 — bounded F55/F58 checkpoint

**Status:** Approved, implemented and integrated by PR151 (`eee3d7ed9c9fb39e3bf79ff7c554cc0a90da632e`)
**Scope:** public-api
**Baseline source revision:** `538fbc9cce950c62eaf56234f8db450860539d0a`
**Compiler:** Scala 3.8.4, pinned JDK 25.0.2

## Authority

The standing all-unchecked-Foundation implementation and public publication
approval recorded in [NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md)
authorizes this necessary F55 amendment and its minimum F58 gate dependency.
It does not assert that the user separately selected each signature or internal
representation. The isolated pinned syntax probes establish feasibility; approval
of this contract does not establish production implementation or acceptance.

## Relationship and scope

This is the bounded gate needed before implementing mandatory
F-055.B.1.1 under the approved Areas/symbolic-conditionals amendment. It supplies
only the structural-predicate/ordinary-if prerequisite portion of F-058.A.1.
The implemented checkpoint completes F-055.B.1.1 at its compiler boundary. It does
not complete full F-058.A.1, Area, generated Area collections,
conditional child hierarchy, F56 behavioral guards or F159. Focused results and
the actual public-source demonstration are recorded in
[increment55-readiness](../implementation/increment55-readiness.md#final-structural-checkpoint-acceptance).

The existing callback-form `hdlRange(0, LANES) { i => ... }` supplies the structural
index in this checkpoint. The amendment's `for i <- hdlRange(...) yield new Area`
syntax remains its existing later owner; no substitute Area/result wrapper is
introduced or required here. Current explicit forms and ordinary host loops stay.

## Exact additive typing decision

Add only these equality overloads to covariant `Expr[+A <: Data]`:

```scala
@compileTimeOnly("... requires compiler-captured structural if")
final def ==(right: Int)(using A <:< Integer): Boolean = failUncaptured()
@compileTimeOnly("... requires compiler-captured structural if")
final def !=(right: Int)(using A <:< Integer): Boolean = failUncaptured()
```

The body must fail closed if reached through an unusual non-Scala path. `<:<`
is variance-correct for `Expr[+A]`. This does not lift an expression to Boolean;
the return type exists solely so Scala can type its ordinary If/Boolean tree.
The plugin must remove the exact owned markers, never execute their bodies.

Add `<`, `<=`, `>`, `>=` as exact `Expr[Integer]` extensions, each taking
`Int | Expr[Integer]`, each compileTimeOnly, with unique target names. Do not add
constrained order members to generic Expr: members risk shadowing the existing
Real/UInt/SInt extension owners. Do not add general `Conversion[Expr, Boolean]`,
CanEqual instances or fallback default/reference evaluation.

Do not add Expr-RHS equality overloads. Existing `Expr == Expr` and `!=` remain
Scala reference equality outside supported structural If. Inside a supported
construction If, the plugin recognizes the actual typed inherited equality
symbol and both statically Integer expression operands, then captures or rejects
based on their actual static ownership. It never runs Any equality to decide an
alternative. Matching a method spelling alone is insufficient.

Supported direct predicate grammar: Integer equality/inequality; Integer order;
closed `&&`, `||`, `!` composition of those predicates; parentheses; ordinary
nested `if/else if/else`. The initial profile rejects mixed arbitrary host Boolean
and symbolic conditions rather than guessing short-circuit effects. A wholly
host-Boolean If remains ordinary Scala, with ordinary one-branch execution.

### Compatibility limits

- Int-left `0 == MODE` and `0 < MODE` are not admitted. Use `MODE == 0` and
  `MODE > 0`. No reversed-operand rewrite or widening CanEqual is introduced.
- Int-RHS equality on Real/Bool rejects. Existing Real and UInt/SInt comparison
  extensions and Bool/Real/Integer Expr-RHS reference equality remain unchanged.
- Marker predicate aliases, Boolean-returning predicate helpers, and marker use
  outside a recognized structural If fail compileTimeOnly. A closed helper with
  a structural If returning a read-only Expr can be admitted through the shared
  effect-validation contract, not arbitrary unavailable code.
- Untouched legacy Expr-RHS Any equality has no plugin-absent fail-closed promise.
  `if (MODE == 0.integer)` still compiles without the plugin as historical host
  equality. Boolean values previously obtained from such legacy equality are
  host Booleans, not automatically a structural predicate carrier. Do not claim
  global prevention of deliberately erased/widened Any equality.
- This gate does not make `when(MODE == 0)` work. F56 must admit the exact source
  shape in its explicit behavioral context and reuse predicate ownership. It
  must not infer structural meaning merely from the predicate's static stage.
- Public marker signature changes require recompilation against the matching API
  and plugin version. Historical compiled code is not retroactively transformed.

## Exact value and statement contract

A statement branch returns Unit. Retain an explicit empty false region for a
missing else; it creates no value, default assignment or coverage proof.

A value branch returns a real read-only `Expr[A]`, after exact complete hardware
ABI agreement: data kind, width, sign, dimensions, Struct field order/types and
enum identity/encoding. Scala type unification alone does not prove that ABI.
Both alternatives and their actual yield edges remain in canonical IR. Never
return the first captured branch as the result, cast a fabricated generic A, or
manufacture a Signal/Selection/Area capability from a value join.

The required common example is supported without an ascription:

```scala
hdlRange(0, LANES) { i =>
  val selected = if (i == 0) ~laneData else laneData
}
```

The minimal safe two-Signal spelling is also supported:

```scala
val selected: Expr[Bits] = if (MODE == 0) firstSignal else secondSignal
```

A narrow expected-result propagation from the immediate typed ValDef, method
return, or type ascription can provide Expr[A] to its If/else-if/Block-result
subtrees. The prototype uses compiler-owned typed-tree sticky attachments. It
never propagates through an arbitrary Apply or Select. Widen singleton unions
before deciding the read-only carrier. This preserves actual typed contexts,
without changing downstream use of a writable capability.

An inferred `val selected = if (...) signalA else signalB` has a Scala Signal
result. Reject it with the source diagnostic
`NODAL-STRUCTURAL-055-001: structural if result must be a read-only Expr; add an explicit Expr[A] ascription`;
likewise reject `(if (...) signalA else signalB).writable`. Branch
ascriptions `(signalA: Expr[Bits])` are an alternative. Host values, arbitrary
Scala objects and heterogeneous capability-valued branches are outside this gate.

## Capture and stage obligations

The syntax proof is not construction implementation. The production source path
must validate the closed predicate and both branch bodies before invoking any
callback. Allow only the already-owned pure hardware expressions and explicitly
supported continuous assignments/local immutable values for this F55 profile.
Use the shared closed-dispatch, mutable/lazy state, singleton initialization,
helper/call-graph, recursion/budget, exception/nonlocal-control and unavailable
body checks. Do not run both branches' println, host mutation, arbitrary calls or
initializers to discover their effects.

A captured branch callback is invoked once to record its region, in deterministic
true/false source order, never once per lane or for sampled/default settings.
Predicate operands retain actual reference edges; there is no source-string
parser, equality execution, representative index or default substitution. Failed
capture poisons the same construction transaction and cannot publish half a join.

`Expr[Integer]` is a typing surface, not proof of static stage. Construction and
native checks must establish owned parameters/pure static arithmetic or a live
structural hdlRange induction value. A procedural `LoopIndex.value`, despite its
Expr[Integer] type, is not a generate-time constant. Reject runtime, foreign,
detached, escaped or unadmitted formals and unsupported physical units. Reuse
ParameterModel/IterationDomain proofs and finite envelopes; do not invent a new
solver or serialized caller-asserted proof authority.

A parameter-only predicate remains static inside an ordinary bounded procedural
loop. Admit that finite combination through the same nested scope/capture and
effect-ordinal scheduler: the loop remains procedural, and its induction may be
captured for arm-local value indexing but never used as a structural predicate.
Retain such nested-control loops conservatively when the unroller cannot preserve
their complete region contract. This is not conditional state or hierarchy support.

Predicate operands and equality evidence use the shared pure effect profile,
including separately compiled helper dependencies and the same traversal budget.
Only branch bodies receive the structural continuous-write profile. Erased Scala
evidence must not carry writes or other effects that lowering would silently drop.

## Actual shared owners and implementation direction

1. `CandidateApi.scala`: public Expr equality markers and exact Integer extensions.
   Param is not a parallel owner: parameters, generated indices and compositions
   all use the same Expr/static Integer contract.
2. `ConstructorCapturePlugin.scala`: existing allocation/transaction ownership and
   phase registration. `DigitalCollectionCallbackPhase.scala`: shared typed
   symbol/body/effect validation, callback attestation and compilation summaries.
   Prefer adding the structural If profile to this existing owner, with a small
   factored helper if necessary, instead of copying another validator. Scan and
   reject authored attestation factories before injecting compiler calls. Then
   preserve the existing constructor-before-callback/before-pickler ordering.
   The isolated extra phase proves feasibility only; it is not the demanded
   production module boundary.
3. `ConstructionSession.scala`, `ElaborationConstructionKernel.scala`,
   `ConstructionRecords.scala`: the one mutable transaction, actual region/value
   identities, captures, lexical lifetime and rollback/poisoning. Extend the
   common lexical-region interface currently used by collections/generated
   regions to represent conditional alternatives. Do not create a second session,
   force a conditional into a fake collection kind, or copy capture registries.
   Add a conditional record with predicate identity, true/false region identities,
   explicit captures, zero/one yields, result type and actual enclosing scope.
4. `StaticIntegerExpression.scala`, `StructuralParameterBinding.scala`,
   `IterationDomain.scala` and native `ParameterModel.{h,cpp}` remain the integer
   DAG, exact identity, scope/envelope and arithmetic-proof owners. A bounded
   static Boolean predicate DAG is the small new representation; comparison
   leaves consume these checked Integer operands. Its native verifier remains
   independent of source attestation. No predicate text is semantic authority.
5. `DigitalValueConstruction.scala`, `DigitalValueContract.scala`,
   `DigitalEnumContract.sameType`, native DigitalType/Value/Collection/Shape models:
   exact yielded value ABI and capture/dominance checks. Add an isolated canonical
   conditional operation with two real regions and explicit zero/one yields;
   operation spelling is a private implementation detail, not a new public API.
   Reuse current type queries and lifetime admission instead of duplicating them.
6. `ScalaToMlirBridge.scala` and `DigitalValueMlir.scala`: extend the existing
   scope-aware SSA dependency schedule and bridge records. Keep branch-local
   expressions local; retain actual source IDs and parent/arm ancestry.
7. `DigitalAssignmentModel.cpp` and canonical ShapeModel: branch-aware dependency,
   conflicts and per-alternative physical coverage. Opposite arms of the same
   actual conditional are exclusive only for the same generated occurrence.
   Different generated lanes can take opposite arms. Before using exclusivity,
   prove that overlapping write images identify the same occurrence through the
   shared injectivity/physical-image owner. Textually complementary predicates in
   unrelated conditionals are not an assumed proof. Duplicate writes within an
   arm, unconditional plus conditional overlap, and missing-arm required coverage
   still fail. No default-overwrite or procedural priority is added to continuous
   assignments. Check reads/coverage on every legal alternative, not their union.

The published bounded-loop plugin owns `ConstructorEffectSummary` and the shared
closed callback validator. Extend that owner with the required branch profile,
preserving versioned signature/profile/dependency validation. Do not introduce a
second summary protocol or infer purity from allocation-only metadata.
Source-unavailable helpers require a valid supported summary or reject. The
separate producer/consumer prototype below proves typed transport only, not that
future branch-effect profile.

## Modularity and scale

Record predicates and branch bodies once. Retain finite budgets for actual typed
predicate/body work, captures, lexical depth and guard contexts. Guard-qualified
coverage can grow with combinations of authored choices; reject exhausted budgets
rather than claim a universally linear algorithm. Never enumerate legal parameter
settings or generated lanes. Reuse query-local memoization/budgets in
integer/type/shape owners. Use actual conditional ancestor IDs for exclusivity;
avoid full parameter enumeration, copied transitive capture sets and eagerly
cross-producting all nested alternatives. General Boolean theorem solving,
correlated guard refinement and full Area/member existence stay with their
existing later owners unless a concrete required case makes them prerequisites.
No speedup has been measured or claimed by these compile prototypes.

## Canonical native and generated-scope contract

Use a closed non-runtime static-predicate DAG over actual checked Integer SSA,
and an isolated structural conditional with explicit captures in each arm and
zero/one exact typed yields. The private operation spelling is not public API.
Both authored arms remain independently well formed even when checked facts prove
one inactive. A join is never a physical writable root. Ordinary Boolean host
conditions continue to execute as ordinary Scala.

The hdlRange index case requires actual Generate ownership in the native path.
A hardware_loop induction, matching bounds or generated-region metadata cannot
promote a procedural index to structural stage. Route the supported conditional
body through the canonical Generate scope while retaining ordinary bounded-loop
semantics elsewhere. Do not duplicate a mixed body into two loop nests.

For generated conditional writes, the initial profile proves a common injective
per-occurrence image in both alternatives. Unsupported guard-correlated cross-lane
images reject. Driver coverage checks every compatible alternative; opposite-arm
partial writes cannot collectively cover an output. Dependency/cycle analysis
retains actual guard contexts so it neither drops feasible cycles nor fabricates
a cycle by stitching incompatible alternatives. Missing else creates no value or
driver. Existing region, proof and graph budgets remain checked, with an additional
finite guard-context budget before expansion.

## Evidence and acceptance boundary

The pinned Scala 3.8.4 syntax probes retained under ignored
`.work/increment55/structural-if-design` establish marker typing, the ordinary-if
phase ordering, host-if preservation and the explicit Expr-ascription boundary.
Their prototype runtime intentionally throws; it is not the production engine.
Production source capture, effect attestation, exact ABI/lifetime, bridge SSA,
independent native stage checks and guard-qualified coverage remain required.

Focused acceptance must include actual public parameter and generated-index
statement/value cases, changed-default controls, host Boolean behavior, explicit
Expr joins, branch-local escape and effect rejection, exact type/shape/enum
negatives, complementary write coverage, same-arm/independent-if conflicts,
missing-arm coverage, feasible/infeasible cycle controls and cross-lane opposite-
arm duplicate-driver rejection. Preserve predecessor collections, reductions,
loops, shape maps and source identities. Check malformed/deep/resource-bound IR
without assuming source attestation. Record a concise shared-owner/scale review.
No target HDL execution, F55 completion, or full F58/Area completion is implied.
No CI or broad qualification is required under the current prototype policy.
