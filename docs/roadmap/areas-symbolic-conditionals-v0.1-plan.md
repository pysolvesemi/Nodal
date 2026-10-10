# Areas and symbolic structural conditionals

**Revision:** 0.1
**Date:** 2026-10-10
**Status:** Approved Foundation roadmap direction; compile prototypes, implementation and qualification pending.
**Initial output target:** Existing portable digital IEEE 1364-2005 profile.

## Authority and relationship to existing contracts

The owner approved lightweight `Area` objects, unified `hdlRange` collections,
ordinary-looking parameter/index-dependent `if/else`, and `when` accepting
parameter as well as runtime predicates, then requested a direct-branch roadmap
update without CI. This document records that forward scope. It does not claim
compiled syntax, executed output, API implementation or increment completion.

Read with the [Foundation roadmap](nodal-development-todo.md),
[roadmap index](README.md),
[unified iteration amendment](lightweight-hierarchy-iteration-v0.1-plan.md),
[portable Vec/constant preservation amendment](portable-verilog-vec-layout-v0.1-plan.md)
and [ADR 0009](../architecture/0009-core-semantic-contracts.md).

For this bounded future feature, this approved direction extends ADR 0009's
ordinary-Scala-control restriction and the main roadmap's explicit-generation
wording: a compiler-recognized symbolic `if` in a supported hardware-construction
context constructs structural IR. It does not convert a parameter to a Scala
Boolean or execute a branch using its default. Host-only Scala conditions and
ordinary Scala ranges retain their existing elaboration semantics. The
`hdlRange` amendment remains authoritative for iteration and mixed effects.

Do not rewrite historical gates, accepted evidence or existing API behavior to
claim this was already supported. Before implementation changes a protected API,
F-058.A.1 below owns the bounded additive versioned gate, exact compile
prototypes, current-contract updates and compatibility inventory. Existing
explicit forms retain their supported contracts. `staticIf`, a mandatory
`scope` wrapper, explicit export lists and user-authored result/view classes
are not required for the common case.

## Public source contract

### Area

`new Area { ... }` groups named hardware inside the enclosing module. Support
standalone and nested Areas, directly accessible legal members, helper-created
Areas, and reusable Area subclasses through qualified Scala compile prototypes.
An Area alone adds no module/port boundary, process, clock, register or latency.
Declarations inherit the enclosing domain unless an existing explicit domain
construct changes it. Areas may contain signals, registers, child modules,
connections and supported behavior; those objects keep their own semantics.

The compiler records Area/member identity, declaration ownership, source paths
and domain provenance independently of emitted names. Scala visibility does not
permit illegal module-internal access, foreign/detached references or bypasses
of hardware ownership. External writes to an exposed member remain subject to
normal driver, ordering and domain checks. Naming does not force materialization
of every expression or create an optimization barrier.

### Generated Area collections

`for i <- hdlRange(0, LANES) yield new Area { ... }` returns a typed indexed
collection of generated Area references. Extent and index expressions may remain
symbolic. It is not an ordinary fixed Scala Vector, a serializable hardware
Struct/Vec payload, or a Mem. Preserve index identity through helpers, slices,
child references, branches, nesting, bridge transport and native IR.

`lanes(0).stored` identifies a member of that generated element. Accept constant
or legal structural index expressions only when existence and bounds are
established under the applicable parameter envelope and structural guards.
Reject runtime hierarchical selectors. An explicit projection into a typed Vec
of exported values may support runtime selection through existing value/index
semantics; do not silently infer a memory, latency or a new hierarchy permission.
Empty iteration retains existing semantics but cannot justify `lanes(0)`.

### Conditions and value results

| Source condition/form | Required meaning |
| --- | --- |
| `if (hostBoolean)` | Ordinary Scala elaboration selects one branch. |
| `if (MODE == 0)` with a legal structural parameter | Capture a structural conditional and retain both alternatives. |
| `if (i == 0)` with a structural hdlRange index | Capture an index-dependent structural conditional within the generated scope. |
| `when(enable)` | Runtime behavioral guard; no conditional structural creation. |
| `when(MODE == 0)` | Parameter-conditioned behavioral guard; it does not become structural because the predicate is static. |
| `when(parameterPredicate && runtimePredicate)` | Behavioral guard retaining both dependencies and ordinary update priority. |

Support structural `if/else if/else`, nesting and value-producing expressions,
including `val selected = if (i == 0) ~laneData else laneData`.
Value alternatives must satisfy existing exact type/width/sign/shape rules.
Represent branch results and legal exports explicitly so branch-local values do
not escape without a valid merge. Branch-dependent Area members may be used only
where their existence is established; differing alternatives must not invent
missing members or default hardware values.

Predicate stage and explicit behavioral context determine semantics, never
whether a branch happens to contain Reg/Wire/instances. A parameter predicate in
`when` remains behavioral even if later constant simplification is legal.
Instances and conditional structural declarations belong in structural `if`,
not inside `when`. A runtime-dependent predicate cannot control structural
`if`; diagnose it and direct the user to `when` or existing value-selection
operations. A procedural iteration index is not a generate-time constant; do
not emit illegal generate conditions from mixed-loop partitioning.

The frontend must capture the agreed `==` and comparison/Boolean expressions
as typed symbolic predicates in these supported contexts. Scala object equality,
implicit Boolean conversion, representative-index execution and parameter-default
evaluation must never silently select a branch. Qualify compiler phase ordering,
helpers, separate producer/consumer compilation, source-unavailable libraries,
nested capture, host evaluation order and transactional failed construction.
Diagnose unsupported host side effects/capture shapes rather than executing both
branches' arbitrary host effects or changing their evaluation count unnoticed.
There is no claim that operator overloading or an Area class alone implements
ordinary Scala `if` capture.

The existing analog procedural builders are unchanged. In particular,
`analogStaticWhen` taking a host Boolean is not a symbolic generate conditional.
Reuse the semantic representation for future qualified AMS consumers, but do not
infer analog operator/topology legality from the digital profile.

## Canonical ownership and target lowering

Reuse the shared iteration model, target-neutral structural regions, expression/
effect DAGs, state/domain ownership and naming infrastructure; do not add a second
mutable construction engine. Keep declaration, Area, iteration and branch IDs
through the bridge and authoritative MLIR. Never reconstruct semantics from HDL
names or postprocess emitted Verilog to recover generation.

Structural alternatives may drive one enclosing lane-owned register only when
their guards are exclusive. Preserve procedural first/last-assignment priority
according to Nodal's existing update contract, reset priority, holds, clock/reset
domains and overlapping-write/loop-carried order. Reject conflicting drivers
within branches or across iterations. Guard-aware existence/driver checks must
consider legal parameter settings rather than only defaults or sampled lanes.

A mixed-effect hdlRange may still partition into multiple target regions.
Partitioning/fusion must not duplicate registers or child instances. Keep
processes inside generated scopes when necessary for legal references, or use
existing typed per-lane value projections. Same clock alone does not prove
fusion safe. Preserve semantic source/member paths under every legal lowering.

With `preserveConstantLoops=true`, preserve eligible concrete hdlRange loops;
symbolic ranges retain target-visible parameterization with all settings.
Ordinary Scala `0 until 3` remains elaboration-only. Respect existing permitted
dead-code/inlining rules and explicit-preservation diagnostics. Verify both the
requested generated structure and behavior; behavioral equivalence alone does
not prove a structure-preservation requirement was honored.

## Required public acceptance example

The following is proposed source and illustrative target output, not an executed
compiler demonstration. LANES is a symbolic integer parameter with a declared
finite legal envelope whose minimum is 1. The input/output data are shaped
8-bit elements with existing flat packed port lowering. Use an enclosing
rising-edge clock and synchronous active-high reset domain.

```scala
val lanes =
  for i <- hdlRange(0, LANES) yield new Area {
    val stored = Reg(Bits(8)) init 0

    if (i == 0) {
      when(enable(i)) {
        stored := ~dataIn(i)
      }
    } else {
      when(enable(i)) {
        stored := dataIn(i)
      }
    }

    dataOut(i) := stored
  }

firstLane := lanes(0).stored
```

The additive gate must qualify the exact surrounding declarations and expression
spellings with pinned Scala; the lightweight Area/range/if/when/member-access
shape above is the required ergonomic target.

```verilog
module LaneRegisters #(
    parameter integer LANES = 3  // Legal configurations require LANES >= 1.
) (
    input  wire              clk,
    input  wire              reset,
    input  wire [LANES-1:0]   enable,
    input  wire [8*LANES-1:0] dataIn,
    output wire [8*LANES-1:0] dataOut,
    output wire [7:0]         firstLane
);
    genvar i;
    generate
        for (i = 0; i < LANES; i = i + 1) begin : lanes
            reg [7:0] stored;
            if (i == 0) begin : first
                always @(posedge clk) begin
                    if (reset)
                        stored <= 8'h00;
                    else if (enable[i])
                        stored <= ~dataIn[i*8 +: 8];
                end
            end else begin : remaining
                always @(posedge clk) begin
                    if (reset)
                        stored <= 8'h00;
                    else if (enable[i])
                        stored <= dataIn[i*8 +: 8];
                end
            end
            assign dataOut[i*8 +: 8] = stored;
        end
    endgenerate
    assign firstLane = lanes[0].stored;
endmodule
```

Require one lane-owned register and one active procedural driver per elaborated
lane, enable-low hold, reset priority, lane-zero inversion, other-lane copying,
flat port mapping and firstLane aliasing. Whitespace and incidental branch-label
spelling are not ABI; retain the meaningful lane/member path and legal static
reference in the structure-preserving witness. Also qualify the concrete bound
3 with preservation enabled. The parameter-envelope contract must reject illegal
configurations; the comment in this illustrative RTL is not an enforcement test.

Then qualify:

- parameter-dependent structural branches outside and inside Areas;
- value-producing structural branches, then child instances with symbolic
  overrides and static generated-child port access;
- real selector-style defaults and overlapping priority assignments, nested
  Areas/ranges, different enables/resets/domains and legal guarded member access;
- a register updated by `when(MODE == 0)`, plus a mixed parameter/runtime guard,
  without allocating conditional instances or extra latency.

Use focused boundary cases and independent expected behavior, not an unbounded
cross-product of every Foundation feature.

## Existing owners and new descendants

This plan solely owns the following new unchecked descendants. Existing
main-roadmap/amendment checkboxes, original obligations and accepted evidence
remain authoritative and unchanged; parent completion includes these additions.
No new numbered increment or F-043 prerequisite is introduced. F-043 retains
its shared analog generation/storage scope; the complete digital feature follows
55-58/153-157/159 prerequisites. Later 65-67/72 qualification must not become a
reverse prerequisite for earlier compiler-boundary acceptance.

- [ ] **F-058.A.1 - Additive Area and symbolic-if gate.** Freeze the bounded public contract, exact comparison/equality capture, stage/context diagnostics, Area/member/collection rules and compatibility through pinned compile-positive/negative prototypes and a versioned gate. Update current language contracts to reference the approved extension while preserving historical acceptance; keep common source free of staticIf/export/view boilerplate.
- [ ] **F-058.B.2.2 - Area ownership and generated references.** Implement standalone/nested/reusable/helper Areas and generated Area collections on canonical hierarchy/iteration ownership. Carry typed member/index/branch identities through bridge/native verifiers, with guarded existence, legal static references and explicit runtime value projections.
- [ ] **F-055.B.1.1 - Structural predicates and branch results.** Capture parameter/index comparisons, Boolean composition and statement/value-producing if/else chains without host equality/default evaluation. Preserve exact result types, widths, signs, shapes, branch-local lifetime and legal merge/export semantics.
- [ ] **F-056.B.1.1 - Parameter-aware behavioral guards.** Accept parameter, runtime and mixed predicates in when/elsewhen/otherwise as behavioral updates. Retain register existence, holds, reset/update priority and domain checks; reject structural creation and diagnose conflicting assignments.
- [ ] **F-159.A.6.2 - Area yield and staged-if integration.** Extend the approved hdlRange contract with typed generated Area results and structural index conditions. Keep host loops unchanged, finite bounds enforced and explicit compatible forms supported; specify supported helper/separate-compilation and host-effect capture boundaries.
- [ ] **F-159.B.4.2 - Guard-preserving mixed-effect lowering.** Integrate Areas, structural branches and branch results into the shared iteration partitioner. Preserve symbolic indices in slices/references, unique state/instances and legal generated/procedural scope paths; never turn procedural indices into generate constants.
- [ ] **F-159.C.3.2 - Area and conditional regression matrix.** Cover the ordered examples above plus representative-index/default-substitution mutations, equality miscapture, branch-local escape, missing members, invalid/empty indices, runtime hierarchy selection, host-side-effect failures, overlapping drivers and nested/helper/separate-compiled cases. Preserve predecessor behavior and public-source/bridge/native evidence.
- [ ] **F-153.B.1 - Area source identity.** Extend existing lexical-name ownership to Area/member/branch/index paths and returned aliases independently of materialization; use 154-157 capture/transport consumers without a parallel naming ledger or emitted-name reconstruction.
- [ ] **F-065.B.3.4 - Portable Area and structural-if emission.** Emit legal Verilog-2005 named generate scopes, conditional branches, per-lane storage/processes and static member references, with flat ports and existing preservation settings. Retain actual source/IR/RTL witnesses and deterministic names; no SystemVerilog-only workaround or clone-per-default specialization.
- [ ] **F-066.D.2.2 - Independent Area/conditional behavior.** Use the existing strict Verilog-2005 parse/elaboration lanes and independent simulation expectations for reset, holds, lane mapping, parameter overrides, branch results and assignment priority. Qualify preserved/unrolled cases and applicable four-state behavior; report unavailable lanes honestly.
- [ ] **F-067.D.4.2 - Structure, drivers and equivalence.** Use existing synthesis/driver checks and applicable equivalence/property lanes for conditional exclusivity, per-lane state/instance counts, alias mapping and preserved/unrolled/optimized behavior under legal parameter/domain assumptions. Include deliberate wrong-branch/duplicate-driver controls and distinguish bounded/two-state evidence from broader claims.

F-058/F-159 existing scale and acceptance obligations include representative
nested scopes, larger legal lane envelopes, deterministic output and construction
time/memory observations; no universal speedup is promised. F-065 supplies actual
examples and capability limits to F-092 documentation; F-097 consumes the accepted
API through its existing refinement work. F-083-088 preserve these identities,
guard semantics and explicit emission requests in later optimization.
F-072 separately qualifies any reuse in Verilog-AMS; current analog procedural
semantics and historical F-034 acceptance are not changed.

All 11 new descendants remain unchecked. This documentation publication starts
no implementation, CI, monitor or closure and supplies no simulated, synthesized,
formal or generated-output execution credit.
