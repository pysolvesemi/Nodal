# NodalSequentialState-DG v0.3 — finite CIRCT sequential profile

**Status:** Implemented finite profile; focused native and public target checks pass
**Scope:** compiler-boundary
**Baseline:** `18acaa6b1443607908babfbc6f4fec28999062c4`

## Authority and allocation

The Foundation implementation authority in [v0.1](NodalSequentialState-DG-v0.1.md)
and the [flat-FSM contract](NodalSequentialState-DG-v0.2.md) authorize this required
F-056.B.3 implementation. The prototype policy applies. This selects a finite
actual Nodal-to-CIRCT profile, not completion of F56 or every later HDL backend.

F-056.B.2 still requires real typed memory-port semantics and verification.
F-056.B.3 lowers the approved sequential profile. This revision clarifies the v0.1
phrase "memory ports and their sequential lowering": Nodal memory-port ownership,
effects, latency/collision/ordering semantics remain required, while this CIRCT
subset covers scalar registers and flat FSMs. Memory-backed designs reject at
this target boundary until implemented; their ports cannot be dropped. F63
scheduling and F65 complete portable memory emission remain their original owners.

## Actual accepted target boundary

Implement one transactional whole-module pass over the verified normalized Nodal
model, using HW/Comb/Seq and Verif. The initial data carriers are fixed scalar
Bool/Bits/UInt/SInt and fixed canonical enum ABI carriers, with direct input,
wire and output mappings and actual local-domain Clock/Reset bindings. Admit the
following checked scalar operations: literals and aliases; sequential/digital
selects; Boolean and/or/not; scalar and/or/xor/not, add/sub/mul/neg, equality/order
comparisons, min/max and concatenation; explicit extend/truncate/wrap/checked-resize
and signed/unsigned reinterpretation/conversions. Fixed enum literals, equality, membership
(`enum_is_one_of`), validity (`enum_is_valid`), toBits and canonical observation
selections retain the shared enum contract. Validity expands over actual verified
canonical codes with preflight case-count cost; width-fitting nonmember codes
remain invalid.
Static shifts/extracts, division/remainder, saturation and any unlisted operation
remain explicit capability rejections until their implementation is added.

Do not specialize a parameter default. Parameter-dependent expressions, symbolic
widths, aggregates, hierarchy, Interface/resolved nets, memory, asynchronous reset,
async-assert/sync-release and other unimplemented operations reject explicitly
before any output is committed. Their existing Nodal acceptance remains distinct.
The complete builtin-module candidate is either transformed and verified or left
unchanged; one unsupported sibling cannot leave partial target output.

Consume existing digital type/value/layout, assignment/dependency/materialization
and sequential facts. Exact widths/signs come from verified owners; target casts
and arithmetic must preserve those facts rather than infer alternate rules.
Read actual output drivers and old register values. Do not leaf-extract a design,
rewrite generated text, infer missing drivers or introduce a competing scheduler.
Explicit required materialization/observability intent must be honored through
the canonical analyzer, or rejected if unsupported; an optional quality-report
request is not permission to silently discard an authored requirement.
Canonical enum output values retain their external codes and source identities;
private FSM storage codes do not become a new external ABI.

## Clock, reset, feedback and assertions

The installed pinned CIRCT is 1.154.0, commit
`87898a876f730a2ebc607dc9b83da487cba49119`. Its `seq.compreg` is positive-edge state
and has no initialization when initialValue is absent. Its reset field is abstract;
the pinned Seq-to-SV conversion chooses synchronous active-high behavior. Therefore
use reset-free compreg plus an explicit synchronous data mux for Nodal reset:
`next = resetActive ? initializer : normalizedNext`. Never set initialValue or
substitute firreg randomized initialization. An uninitialized register opts out of
reset exactly as its checked Nodal owner specifies.

Use the actual typed clock, with `seq.clock_inv` for falling-edge state. Preserve
normalized priority, enable and hold; do not reconstruct a different enable policy.
HW graph regions and typed BackedgeBuilder references represent real register
feedback without relaxing Nodal dominance or admitting combinational cycles.

Flat FSMs normalize through the v0.2 owner before target conversion. Consume actual
reset_asserted and sequential_assert edges. Error retains the actual property
`resetActive || validEncoding`, owning clock and edge in verif.clocked_assert;
there is no reset-seen latch, implicit callback epoch, hidden boot or action-enable
mask. The pinned Verif-to-SV conversion must retain the clock/edge/assertion label
in focused checks. Assertion failure is not a guaranteed simulator termination.
Recovery, entry/active/exit/transition ordering and reset suppression remain the
same shared normalized dataflow, including uninitialized action registers.

## Resource, source and compatibility contract

Admit widths 1–4096, at most 65,536 authored operations per builtin module and
262,144 emitted operations, retaining existing native proof limits. Preflight
target expansion before allocating it. Preserve deterministic
source-correlated module/port/state/assertion identities and inspectable enum ABI
metadata; target name allocation cannot alter the connection graph. No claim of
whole-pipeline linear complexity or synthesis optimization is made.

Legacy declaration-only or string-FSM fixtures are not executable inputs.
Unsupported target combinations get precise capability diagnostics. The actual
pass/profile and accepted scalar-operation inventory will be recorded with the
focused results; syntax or handwritten target probes alone do not establish this
compiler path or an emitted HDL result.

## Focused obligations and authoritative sources

Test actual public source through Nodal normalization into CIRCT for resetless and
synchronous state, both edge/polarity controls, reset-over-enable, hold/priority,
uninitialized state, feedback/current-state outputs, enum ABI, flat-FSM ordered
actions/self-edges/terminal observations and actual Error/recovery. Independently
mutate unsupported domain/type/operation/driver inputs and require transactional
failure. Verify target IR with the pinned tool and retain actual generated output
excerpts for any demonstrated downstream conversion. A future backend is not an
executed tool result; no full simulation/equivalence campaign is a prerequisite.

Primary pinned sources:

- [Seq semantics and reset rationale](https://github.com/llvm/circt/blob/87898a876f730a2ebc607dc9b83da487cba49119/docs/Dialects/Seq/RationaleSeq.md).
- [Seq-to-SV implementation](https://github.com/llvm/circt/blob/87898a876f730a2ebc607dc9b83da487cba49119/lib/Conversion/SeqToSV/SeqToSV.cpp).
- [Seq operation definitions](https://github.com/llvm/circt/blob/87898a876f730a2ebc607dc9b83da487cba49119/include/circt/Dialect/Seq/SeqOps.td).
- [HW graph module definitions](https://github.com/llvm/circt/blob/87898a876f730a2ebc607dc9b83da487cba49119/include/circt/Dialect/HW/HWStructure.td).
- [Typed backedge utility](https://github.com/llvm/circt/blob/87898a876f730a2ebc607dc9b83da487cba49119/include/circt/Support/BackedgeBuilder.h).

The initial five handwritten probes established feasibility only. The implemented
`--nodal-sequential-to-circt` pass now passes 62 native cases and four actual
public-source target exports using that pinned tool. Final integrated commands,
artifacts and limitations are recorded in
[increment56 readiness](../implementation/increment56-readiness.md#flat-fsm-and-fixed-scalar-target-integration).
Full F56 acceptance remains open for its other required children.

The admitted scalar inventory is: literals; alias/select; Boolean and/or/not;
scalar and/or/xor/not; add/subtract/multiply/negate; comparisons; min/max;
concatenation; extend/truncate/wrap/resize_checked; signed/unsigned conversion and
reinterpretation; canonical enum literal/equality/as_bits/is_one_of/is_valid;
and the verified normalized sequential operations. Shifts, extracts, division,
remainder, saturation and any unlisted target operation reject explicitly even
where their canonical Nodal semantics are independently supported. This finite
adapter does not weaken those source/native contracts.
