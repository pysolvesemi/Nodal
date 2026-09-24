# Lightweight hierarchy, unified HDL iteration and frontend scalability

**Revision:** 0.2
**Approved direction:** 2026-09-24
**Updated:** 2026-09-24
**Status:** Roadmap amendment only; implementation and qualification remain open.

## Authority, scope and progress

The owner approved this amendment after the constructor, connectivity, mixed-loop
lowering and performance discussion, then explicitly requested a direct-branch
roadmap commit without a new branch or CI. Read it together with the
[Foundation checklist](nodal-development-todo.md) and the
[versioned design gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md).

This is the current forward-looking amendment to the main roadmap's Public API
direction, Fixed project direction, staged-loop section and the named children
below. In those sections it supersedes the requirement that separate `genRange`
and `hwRange` names must choose all target loop forms. `hdlRange` explicitly
chooses target-visible iteration; typed effects and declared domains then decide
its legal target regions. Ordinary Scala loops still never change staging by
body inspection. This is not the earlier context-only proposal: one digital
source loop may contain structure, combinational logic and state updates.

The main roadmap retains all existing parent/child checkboxes, IDs, original
feature obligations, milestones and historical evidence. This companion owns
only the NEW descendant checkboxes below; do not copy existing parent states
here or maintain a second status ledger. Completing a parent in the main file
also requires its applicable descendants here. All new descendants start open.
Existing F-042.C-G, F-043.C-G and F-159 validation obligations remain required.
No historical API freeze, accepted artifact or executed result is rewritten.

The subsequent approved [Foundation 160 modularization plan](construction-frontend-modularization-v0.1-plan.md)
adds one bounded maintainability increment scheduled after completed 42 and
before 43. It owns its own parent/children and the added 43 prerequisite; this
file does not mirror those checkboxes. Revision 0.2 assigns initial construction
modularization to 160, while 96 keeps broader measured optimization and Rust
evaluation. No implementation or acceptance is claimed by this revision.

The main roadmap file itself is unchanged in this publication. This linked
amendment records the approved requirements without retranscribing that large
file. The [roadmap index](README.md) and Increment 42 readiness review link here.

## Public API direction and Increment 42

Prefer ordinary-looking Scala constructors, named arguments and child members.
Use one canonical hierarchy/parameter/connection model beneath both lightweight
and explicit APIs. This is the required target surface, NOT a compiled example:

```scala
class GainStage(gain: Param[Real] = 2.0) extends Module:
  val vin = in(Electrical)
  val vout = out(Electrical)
  analog:
    V(vout) <+ gain * V(vin)

class Top(topGain: Param[Real] = 4.0) extends Module:
  val vin = in(Electrical)
  val vout = out(Electrical)
  val amp = new GainStage(gain = topGain)
  vin <> amp.vin
  amp.vout <> vout
```

Required descendants of the existing **F-042.B.1**:

- [ ] **F-042.B.1.1 - Constructor-declared HDL parameters.** Implement typed constructor defaults, named argument overrides and automatic child attachment for `new GainStage(gain = topGain)`. Capture the declaration default independently of the supplied actual; the child owns a fresh parameter declaration while the parent owns the override DAG. Do not alias the parent's Param into the child, evaluate only defaults, specialize per value, or reevaluate Scala side effects. Prove constructor lifecycle, top-level construction, helper factories, separate compilation and failed-construction cleanup with pinned Scala compile prototypes.
- [ ] **F-042.B.1.2 - Direct typed child-port access.** Accept `amp.vin` and `amp.vout` on connection/binding paths with exact immediate-child instance and declaration identity. Reject non-port, private/internal, foreign, sibling-internal and detached endpoints where illegal. Ordinary legal Scala member access alone is not a topology permission; no reflection/name-based ownership bypass.
- [ ] **F-042.B.1.3 - Symmetric conservative connectivity.** Add `a <> b` as the same semantic operation as `connect(a, b)` for compatible conservative terminals. Operand reversal must preserve connection sets, potentials/flow orientation rules and normalized topology. Retain discipline, dimension and ownership checks. Do not make it implicit digital assignment, signal-flow conversion or a universal smart-connect operator.
- [ ] **F-042.B.1.4 - Override legality and explicit-form parity.** Enforce exact child parameter target, parent ownership, duplicate rejection, analysis-static DAG effects, type/width compatibility and physical dimensions. Reject `transition(parentBias)` even with parent-owned operands, as well as dynamic/unknown effects and forged ownership. Keep the existing typed `.param` and explicit `instance`/`connect` forms; prove equivalence against an equivalent declaration, not an invalid constructor call or a second semantic engine.
- [ ] **F-042.B.1.5 - Fixed Scala replication.** Qualify ordinary finite Scala ranges/collections producing scalar instances, including singleton/boundary and explicitly diagnosed invalid forms. Preserve stable caller/local/index/source identities. Plain `0 until 4` stays elaboration-time even if the body creates instances or updates registers; target-visible symbolic/shaped generation remains owned by 43/159.

**F-042.A/B.2:** Reuse transaction-owned IDs, declarations and the existing bridge
and native verifier. Most new policy should be in focused private helpers; use
necessary real construction hooks rather than duplicate registries or an
uncalled policy facade. Constructor lowering may need frontend/macro lifecycle
work: no arbitrary promise that the integration is only a few lines.

**F-042.B.3/E:** Emit one definition per compatible module structure, retain
child defaults and instance override expressions, and preserve source identity.
The intended output contains `.gain(topGain)` on `amp`, not a `GainStage_gain_4`
clone. This sketch is a target requirement, not generated evidence. Material
structural/domain variants require explicit identity and capability contracts.

**F-042.C/F:** Add defaults/non-defaults, expression overrides, repeated same-type
children, direct-port rejection, duplicate/type/unit/effect failures, `<>` versus
`connect` and reversed operands, recursion, equations/events/functions and
constructor/explicit-form parity. A private helper test does not replace public
source, bridge, native or emitted-output tests.

**F-042.D/G:** Retain actual public Scala, normalized IR and generated Verilog-A,
strict internal reparse, reproduction commands and capability limits. Internal
reparse is not independent tool execution. Preserve 48/49/52 handoff obligations
without making their entire later parents reverse prerequisites for compiler-only
42 acceptance. No current feature or acceptance box is completed by this plan.

## One target-visible iteration domain

```scala
for i <- 0 until 4 do
  // ordinary Scala elaboration

for i <- hdlRange(0, 4) do
  // target-visible iteration with a concrete bound

for i <- hdlRange(0, count) do
  // target-visible iteration with a legal symbolic integer bound
```

`hdlRange(lower, upper, step = 1, maximum = ...)` is the preferred planned
constructor. Bounds are half-open. Literal versus symbolic bounds do not select
staging. Support positive static steps initially; reject zero, dynamic or
unsupported steps. Validate overflow, empty/singleton ranges and static bounds
under every legal parameter setting. Derive finite envelopes from concrete
bounds or declared parameter ranges; require and enforce `maximum` only when a
finite envelope is otherwise unavailable. Do not accept dynamic hardware trip
counts or silently create a sequential algorithm/FSM or extra latency.

A loop callback captures a symbolic induction value and typed hardware effects;
it is not an arbitrary Scala loop executed once per default parameter value.
Specify `foreach` and supported `yield` behavior, including shaped result/index
semantics, helper methods and separate compilation. Do not claim that a symbolic
result is an ordinary fixed Scala Vector. Reject unsupported host-language
branching or side effects instead of changing their evaluation count.

One digital loop may contain the following planned source pattern. Register
clock/reset domains are established by their declarations; no extra `clocked:`
block is required solely to make the backend partition this source:

```scala
for i <- hdlRange(0, count) do
  val lane = new Lane()
  driver(i) := receiver(i) * 2
  txState(i) := txNext(i)
  rxState(i) := rxNext(i)
```

Capture one logical iteration domain, then classify each recorded operation by
its typed semantic effect, ownership, dependencies and explicit domain metadata.
This is NOT guessing from names, a local `val`, or the presence of `Reg`/`Wire`.
Structural operations may lower to a `generate for`, combinational effects to
continuous assignments or legal combinational regions, and state updates to
procedural loops in their already-declared compatible processes. The same source
loop may therefore produce both generate and procedural loops.

Partitioning must preserve induction bindings, symbolic bounds, local state,
instance multiplicity, source paths, guard priority, dependencies, clock/reset,
latency, signedness/widths and update ordering. Do not clone instances/state when
splitting regions. A procedural induction variable must not become an illegal
runtime hierarchical instance selector. Export typed per-lane signals/arrays or
retain a legal process inside the generate scope when needed; reject an illegal
partition rather than manufacture cross-scope references or unsupported arrays.

Same clock does not alone imply fusion. For tx/rx with compatible event/edge,
reset, update phase and dependency rules, normally emit one procedural loop with
both updates. Different enables can remain per-update guards inside it. Different
clock domains or incompatible reset/process semantics require separate legal
processes. Preserve reset polarity/value/priority and blocking/nonblocking
behavior; do not introduce a race or alter overlapping-write/loop-carried order.
CDC/RDC rules still apply even when effects originated in one source loop.

Fusion is a safe output-quality optimization, not language semantics or a
correctness requirement. Prefer compact legal output; retain a verified unfused
fallback when equivalence is not proven. Do not promise a globally minimal
number of processes. Analog lowering remains subject to conservative topology,
state/event storage, operator restrictions, index legality and backend profile;
ordinary procedural `for` is not automatically legal for every analog body.

## Existing owners and new iteration descendants

**43** owns analog shapes/generated objects, analog legality and the early shared
range-capture checkpoint. **55-58** retain digital expression/state/domain and
structural-generation semantics. **159** owns the unified staged-range frontend
and mixed digital iteration integration against those prerequisites. Reuse the
same shared range model. Implementation is scheduled as **42 -> 160 -> 43**:
43 starts after the accepted construction-modularization checkpoint in 160,
without waiting for the whole later 159, digital backend or 96 study. Neither
42 nor 160 waits for `hdlRange`, broader generation or Rust. Preserve 159's
existing 55/58 and 153-157 prerequisites and 65-67/72 qualification owners.

- [ ] **F-043.B.1.1 - Shared range capture and analog objects.** Implement/reuse the neutral typed iteration domain for literal/symbolic bounds and capture legal analog generated objects with stable index/owner identity. Make the shared checkpoint independently consumable by 159; no duplicate range engine or reverse parent dependency.
- [ ] **F-043.B.2.1 - Analog iteration verification.** Verify bounds, shapes, generated lexical state, conservative terminal indexing and effects through bridge/native IR. Distinguish topology replication from procedural evaluation and reject unsupported analog operator placement or illegal flattening/memory inference.
- [ ] **F-043.B.3.1 - Analog target lowering.** Lower each accepted iteration profile into legal target constructs with symbolic bounds, per-instance storage and source maps. Retain explicit unsupported diagnostics and actual scalar/generated counterpart witnesses; preserve 48/49/52 applicability limits.
- [ ] **F-159.A.6 - Unified iteration contract and migration.** Replace the planned two-name-only/common-case requirement with `hdlRange`; retain existing `genRange`, `hwRange`, `generate` and `loop` contracts as explicit compatibility forms where already exposed. Audit actual APIs, ADRs, language references, surfaces and tests, record migration/versioning, and never remove an existing API or rewrite historical freeze evidence merely because this roadmap changes.
- [ ] **F-159.B.4 - Typed mixed-effect iteration and partitioning.** Capture one source domain and lower its structural, combinational and sequential effects to existing canonical representations with explicit bindings. Derive processes from register/domain declarations, not a new user-required `clocked:` syntax. Prove legal cross-partition paths, stable source indices and no duplicated state/instances or hidden scheduling.
- [ ] **F-159.B.5 - Process compatibility.** Group compatible state updates using event/edge, reset, update phase, guard/dependency and assignment semantics. Carry separate enables as guards when safe; split incompatible clock/reset groups and retain CDC/RDC and multiple-driver checks.
- [ ] **F-159.C.3 - Mixed-loop regression and equivalence.** Cover literals and symbolic envelopes, instances plus assignments plus state, same-domain tx/rx, different enables/domains/resets, overlapping targets, loop-carried dependencies, nested loops, empty/singleton bounds, scoped temporaries, generated child outputs and unknown effects. Compare fused/unfused and equivalent explicit-form behavior with source/IR/HDL identities; retain required simulation, synthesis and equivalence/property evidence through existing owners.
- [ ] **F-159.E.3 - Safe fusion and output quality.** Prefer one loop for compatible same-domain updates where proven; otherwise keep separate verified processes. Check deterministic semantic names and symbolic bounds, avoid clone-per-default output, and document why unfused output is necessary when scope or effects prevent safe fusion.

Existing F-159.A.1/A.3 finite-bound and ordinary-Scala rules remain. A.2/A.5 and
B.1-B.3 are read with the unified target-visible stage/typed-effect partitioning
contract above rather than the superseded one-range-name/one-target-loop rule.
Existing C.1/C.2 retain their negatives for explicit constrained forms and add
`hdlRange` cases; an explicitly procedural `hwRange` must not silently gain
structural creation. This plan changes no current implementation or fixture.

## Later frontend scalability and Rust evaluation

Use [Foundation 160](construction-frontend-modularization-v0.1-plan.md) for the
bounded behavior-preserving modularization and its before/after regression
baseline, after 42 and before 43. Keep the comprehensive benchmark program,
further profile-guided optimization and Rust evaluation with **Increment 96**;
do not duplicate 160's initial extraction checklist here or block 42/43 on 96.
Keep Scala for syntax, lexical construction and source capture, and keep the
current native MLIR/C++ path. Construction records
may be compact, versioned and language-neutral, but MLIR remains authoritative
for compiler semantics; do not create a competing IR truth or a second hierarchy
engine. Rust is an evaluation option, not a required rewrite or a speed promise.

New descendants of the existing **F-096.B.2**:

- [ ] **F-096.B.2.1 - Reproducible frontend/native baseline.** Measure representative 10K/100K/1M declaration tiers, deep/wide/repeated hierarchy, shared expression DAGs and symbolic combinations. Separate Scala compile/startup, cold/warm elaboration, snapshot/serialization, bridge, native verification/lowering and tool time; retain peak memory, allocations/GC, artifact sizes, hardware/tool versions and repeated-run distributions. Report unsupported/resource-limited tiers, not invented measurements.
- [ ] **F-096.B.2.2 - Follow-on profile-guided optimization.** Consume the accepted F-160 component boundaries and baseline instead of repeating its initial modularization. Identify actual bottlenecks across the broader benchmark families, and make further justified algorithmic, data-layout or component refinements with behavior parity, or record why no further refactor is needed. Preserve transaction invariants and narrow interfaces; file size or transport inconvenience is not performance evidence. This stable ID remains open and owns only the later performance-driven work.
- [ ] **F-096.B.2.3 - Native-boundary and Rust prototype comparison.** Evaluate coarse-grained graph/index/cache/incremental-analysis services against optimized Scala and existing C++ baselines using identical semantic workloads. Include serialization, FFI/process crossings, allocation, cleanup, determinism, parallel safety and build/distribution cost; do not assume Rust is faster or call native code once per tiny DSL operation by default.
- [ ] **F-096.B.2.4 - Evidence-based adoption decision.** Record throughput/memory/latency, safety and maintenance tradeoffs with correctness parity. Keep, refactor or selectively move a service only when evidence justifies it and the applicable architecture/API gate is approved. No mandatory Rust dependency, broad kernel rewrite or frontier-performance claim without measurements; no reverse prerequisite for 42/43.

## Delivery and acceptance

This is a documentation-only roadmap approval, not implementation, compilation,
performance evidence, a generated-HDL demonstration or parent completion.
Implement in the existing owning increments, keep actual source/IR/HDL witnesses,
and retain targeted-first/full qualification, review, verified integration and
separate accepted-evidence closure. `AGENTS.md` is unchanged. The user waived CI
for this roadmap publication only; compiler changes still require qualification.
