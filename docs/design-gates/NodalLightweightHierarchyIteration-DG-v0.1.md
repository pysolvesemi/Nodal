# Nodal lightweight hierarchy and unified iteration design gate v0.1

**Status:** Approved
**Scope:** public-api
**Approved direction date:** 2026-09-24
**Applies to:** Foundation 42, analog range integration 43 and staged iteration 159.

## Approval evidence and limit

The owner explicitly selected constructor-style modules, direct child ports and
conservative `<>`, then selected one `hdlRange` whose typed effects may lower to
both generate and procedural loops without requiring `clocked:`. The owner also
approved compatible-process fusion and benchmark-led frontend/Rust evaluation,
and requested: "Please update roadmap directly in branch as per your
recommendations and commit and push. don't create new branch or run CI for this
roadmap update."

That is approval of this forward design and documentation scope, not a claim of
compiled syntax, implemented semantics, independent review, test success or
increment acceptance. Required compile prototypes, integration tests and target
witnesses below remain outstanding. The detailed requirements and their existing
owners are in the [roadmap amendment](../roadmap/lightweight-hierarchy-iteration-v0.1-plan.md).

### Production ABI implementation record - 2026-09-26

The bounded constructor profile uses compiler-owned version-1
`ConstructorSchema` metadata and generated calls into the existing API
construction owner. The compiler plugin is a leaf on the pinned Scala 3.8.4
compiler: API and frontend compile without loading it, while supported producer
and non-inline factory source must enable it. Repository example producers and
testkit do so through one build trait. Separate definition, factory and consumer
JAR compilation is required evidence. A prebuilt factory compiled without the
plugin is rejected at runtime with `NODAL-CONSTRUCTOR-MISSING-016` rather than
accepted with missing attachment/default semantics.

The initial production profile captures direct top-level nongeneric Module
classes with one primary `Param[Real]` list whose defaults are finite Double
literals, plus the proven direct zero-argument shape. Host/generic/indirect
legacy explicit constructors remain on their prior path. General defaults,
other parameter types and unsupported Scala constructor shapes remain open and
must not be inferred from version-1 metadata. This is an implementation record
within the approved gate, not completion or acceptance evidence.

Captured construction reuses the existing transaction and canonical Instance.
An external root actual is retained separately from its authored declaration
default; it is not invented as a parent instance or forwarded through a child's
downstream use of the root parameter. Failures after construction effects poison
snapshot publication, while a clean rejection before `Module.begin` may be
caught. A fresh elaboration must remain clean in both cases.

This gate supplements the existing construction-policy gate; it does not delete
or relabel that gate's helper checkpoint or evidence. It supersedes only the
planned explicit-only common syntax and one-range-name/one-HDL-loop restriction.
Do not edit historical accepted API gates/receipts to suggest they already
implemented this contract.

## Exact hierarchy contract

The preferred target spelling is:

```scala
class GainStage(gain: Param[Real] = 2.0) extends Module:
  val vin = in(Electrical)
  val vout = out(Electrical)

class Top(topGain: Param[Real] = 4.0) extends Module:
  val vin = in(Electrical)
  val vout = out(Electrical)
  val amp = new GainStage(gain = topGain)
  vin <> amp.vin
  amp.vout <> vout
```

This is a specification example, not current compilable/generated evidence.
Prototype exact literal lifting, constructor lifecycle and separate compilation
before relying on the syntax. The child owns the parameter declaration/default;
the parent supplies a distinct symbolic override binding. Neither an ordinary
Scala reference to the parent's Param nor evaluating the supplied default alone
satisfies this contract. Preserve host argument evaluation count/order, failed
construction cleanup and deterministic instance/source paths.

`amp.vin` identifies a legal port of that exact child when used by the hardware
connection path; arbitrary Scala member visibility does not establish hardware
access legality. `<>` is the symmetric conservative connection-set operation,
equivalent to `connect` with the same endpoint/discipline/unit/ownership checks.
It is not directional digital assignment or implicit analog/digital conversion.

Constructor and existing explicit `instance`/typed-selector/`.param`/`connect`
forms lower to the same canonical hierarchy model for equivalent declarations.
Keep parent-owned analysis-static DAGs, exact target/type/unit checks and
rejection of duplicates and stateful operations such as `transition(parentBias)`.
Retain one reusable module definition per compatible structure and symbolic
instance bindings. Do not specialize module names per default or override value.
Ordinary fixed Scala replication remains elaboration-only.

## Exact iteration contract

`hdlRange(lower, upper, step = 1, maximum = ...)` chooses target-visible iteration
for both literal and legal symbolic integer bounds. Use half-open bounds,
positive static steps initially and an enforced finite legal envelope. A declared
finite parameter range removes the need for a redundant maximum. Dynamic trip
counts, invalid steps, overflow and unsupported host effects require diagnostics.
Ordinary Scala ranges remain Scala elaboration regardless of body contents.

A single iteration domain may record structural instances, combinational effects
and state updates. Typed operation/effect and declared domain metadata determine
legal lowering regions after capture; no syntax/name heuristic infers staging.
The backend may emit both a generate-for and procedural loops for one source
loop. Existing register/domain declarations provide clocks/resets; users need
not add `clocked:` solely to partition that loop.

Preserve bounds, induction references, dependencies, per-instance state, local
bindings, guard priority, update ordering, width/sign, reset/clock semantics and
latency across partitions. Export legal typed per-lane signals or retain a legal
process within a generated scope when procedural indexing cannot address its
structure. Do not duplicate state/instances or invent illegal cross-scope paths.

Compatible same-domain tx/rx updates should normally share a procedural loop.
Different enables may remain separate guards within that loop. Different clocks
or incompatible event/reset/update semantics require separate legal processes.
Fusion must preserve races/driver rules, reset priority and assignment semantics;
it is optional output optimization with a verified unfused fallback, not part of
the observable language semantics or a global minimality promise.

Analog placement, conservative indexing, generated event-local storage and
operator legality are capability-checked by 43; digital synthesis/formal semantics
remain with 55-58/159 and their validation owners. No universal analog procedural
loop or simulator support is implied.

## Alternatives and compatibility

Accepted: existing explicit hierarchy APIs as compatibility/advanced forms;
existing constrained `genRange`/`hwRange` and canonical generate/loop forms where
exposed; shared typed construction records; small private helpers and necessary
real kernel hooks; legal per-generate processes when cross-scope partitioning is
not valid. Equivalent simple forms must have semantic parity, not necessarily
identical textual HDL after an independently validated optional optimization.

Rejected: forcing every user to split one logical loop into structural and
`clocked:` loops; inferring staging from local variable names or Reg/Wire presence;
reclassifying ordinary Scala loops; treating parent Params as child declarations;
clone-per-value specialization; universal smart-connect; unproved process fusion;
hidden FSMs/latency; a duplicate mutable hierarchy engine or Rust rewrite as a
prerequisite for these features.

No current API is removed by this documentation commit. Before a compatibility
transition, inventory the actual public surface, aliases, language reference,
ADRs, manifests, examples and tests; implement supported aliases/deprecations or
explicit versioned migration. Preserve historical freezes and acceptance states.
The earlier two-range proposals remain historical/explicit forms, not the sole
preferred spelling of the new mixed-effect contract.

## Required validation and implementation boundaries

Compile positive/negative constructor and operator examples with pinned Scala,
including factories, separate compilation, literal/default and symbolic bindings,
child ownership, duplicate/type/unit/effect errors and failure cleanup. Exercise
existing UInt and analog parameter-binding predecessors and equivalent explicit
forms. Test `<>` reversal/connect parity and invalid digital/different-discipline
usage. Retain actual Scala/normalized IR/Verilog-A witnesses for 42.

For iteration, cover empty/singleton/literal/symbolic bounds, enforced envelopes,
nested loops, mixed instances/combinational/state, same-domain tx/rx, separate
enables, incompatible clocks/resets, overlapping writes, loop-carried dependencies,
generated-port indexing and illegal analog effects. Compare legal fused/unfused
behavior with required simulation/synthesis/equivalence evidence from the existing
owners. Keep unsupported execution blocked; internal reparse is not an independent
compiler and roadmap approval is not an executed test.

43 can implement an independently usable shared range/analog checkpoint without
waiting for all digital 159 dependencies. 159 retains its digital/naming
prerequisites. 42 is not blocked by 43/159 or by the later 96 profiling/Rust
assessment. MLIR remains the authoritative compiler IR; compact frontend records
and a selective native service are not a competing compiler semantic source.

Use focused helpers and minimum necessary transaction integration, without
promising a fixed line count. Any later Rust adoption or broader boundary change
needs its own measured rationale and applicable gate. `AGENTS.md` and historical
qualification/evidence policies are unchanged.


## Foundation 43 static-domain checkpoint - 2026-10-01

The first implementation checkpoint adds private concrete half-open domain
arithmetic and integrates exact repetition into the existing control-flow
construction owner. It uses wide intermediates and an explicit 32-bit trip-count
limit matching the current loop metadata. Empty iteration and invalid negative
repetition counts remain distinct; a maximum is checked, never used to clamp.
The first excluded induction value remains wide instead of wrapping an Int.

The public `analogRepeat` signature and retained procedural staging are unchanged.
Its body is still captured once, including a zero-count loop; it is not unrolled
and does not allocate generated topology. The builder rejects invalid static
counts and conflicting supplied metadata before invoking the body. The existing
immutable runtime, bridge and native verifiers remain independent and unchanged.
No private factory can be used to repair or accept a conflicting static witness.

This is a bounded implementation of the already-approved range direction, not a
new public API approval, complete `hdlRange` capture, shape support or F-043
acceptance. Symbolic parameter domains, induction/generated-state ownership,
analog layouts and actual generated target witnesses remain required. The
[readiness record](../implementation/increment43-readiness.md) records that
boundary; the existing roadmap and amendment own all unfinished checkboxes.

## Foundation 43 typed Integer expression checkpoint - 2026-10-02

The next prerequisite adds `+`, binary `-`, `*`, `/` and unary `-` on
`Expr[Integer]`. Both operands stay typed Integer expressions; no implicit
Real, UInt or host-Int conversion is introduced. The implementation records
existing `KernelExpr` arithmetic operations with the Integer result descriptor
through the canonical construction session. It does not evaluate operands,
copy shared subexpressions, substitute parameter defaults or add a second
arithmetic representation. Existing Real and UInt operators are unchanged.

The production consumer for this checkpoint is the existing static parameter
override path: construction ownership/effect checks, parameter-expression
snapshots, bridge `nodal.const_expr` serialization and native parameter
verification. The tests exercise source-level composition, shared DAG identity,
determinism across changed defaults, type/ownership/dynamic-source rejection,
and configured native arithmetic against independent literal binding oracles.
Wrong-result controls and zero-divisor/overflow cases must reject normally;
a process failure or an unconfigured conditional branch is not native evidence.

This is an implementation record within the approved typed symbolic-integer
direction, not completed review or acceptance. In particular, `hdlRange` still
admits only its implemented literal/direct bounded-parameter profile at this
checkpoint. Arithmetic range transport, bound inference over captured DAGs,
shapes/indexing/slices, generated-instance storage and legal target generation
remain required F-043 work. The new syntax does not silently unroll or accept
an expression-bound range by evaluating its default.

No native verifier, historical test, API freeze, required gate or roadmap
checkbox is weakened or relabelled. The existing main checklist and amendment
remain the only progress owners. Fresh targeted Core, configured Bridge20 and
parameter qualification must establish this source checkpoint; exact results
and any repairs belong in the PR checkpoint rather than being predeclared here.
