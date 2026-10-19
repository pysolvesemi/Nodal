# NodalBoundedDigitalLoops-DG v0.1

**Status:** Approved
**Scope:** F55 bounded combinational iteration and unified hdlRange digital effects

## Authority and compatibility

The standing all-unchecked-Foundation implementation request and public publication
approval recorded in [NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md)
authorize this additive
implementation of the original F55 and unified hierarchy/iteration gate. They do
not imply that the user separately chose each internal signature or resource cap.
Preserve loop(LoopBound)(LoopIndex => Unit), Static and Symbolic bounds, and actual
hdlRange callbacks. Add LoopIndex.value as an owned Expr[Integer], without a host
Int conversion or representative/default induction value. Existing structural and
analog range semantics remain with their shared owners.

## Finite semantics

Capture one checked callback template, including empty domains. Bounds are actual
owned, invariant Integer DAGs; use shared IterationDomain/ParameterModel facts.
Static(n) means [0,n); Symbolic(n,max) requires every legal value to lie in [0,max].
An explicit maximum is checked, never used as a clamp or as invented parameter
constraints. Positive invariant steps without overridable parameter atoms,
including nonunit strides, are supported; the finite trip envelope is at most
signed-i32 maximum.
Preserve legacy no-induction negative-step carriers without granting new digital
semantics. Empty domains suppress attained accesses/writes, not syntax, type,
ownership, supported-operation or step/overflow validation.

Explicit capture edges retain the original value/storage authority. The induction
participates in checked affine selectors, not implicit UInt conversion or varying
widths. Body types remain invariant. Pure nested collections use their existing
closed contract. Effectful nested loops, memories, events, register updates,
latency-bearing operations and hidden state are outside this initial profile.

Independent lane writes and seeded unit-step predecessor chains are supported.
Actual source order, unique destination images, complete base coverage and a strict
decrease in iteration/statement order establish a chain. Retain all other physical
root, expression, Interface and resolved-net edges in the shared assignment graph;
local recurrence evidence cannot hide a cross-family combinational cycle. There
are no arbitrary destructive accumulators or last-writer priorities.

Written outputs are externally observable even without internal SSA readers.
Every output participating in an authored executable write needs complete final
coverage, including empty-loop cases. Untouched declaration-only outputs retain
the established fixture/declaration contract.

## Construction and shared ownership

The compiler callback validator reuses its closed method-symbol/helper traversal
with explicit loop/range effect profiles. Attestation precedes execution, including
empty loops. Nested collection callbacks remain pure. Forged runtime carriers and
unsupported host effects reject; no package-name or test-name trust escape exists.
Existing negative semantic tests remain independently exercised when source-level
rejection moves earlier. Separately compiled supported Module constructors require
a versioned compiler-checked effect summary from the same shared validator;
allocation-only ConstructorSchema metadata is not evidence of permitted effects.
Consumers validate the summary contract, actual constructor identity and required
constructor/factory/singleton/default dependency summaries. Same-signature library
substitution cannot retain a stale transitive proof. Missing,
stale or hostile summaries reject before callback execution. Genuine separate-JAR
producer/consumer checks must preserve lawful library construction in ranges.
Previously compiled allocation-only definitions/factories must be rebuilt with the
current plugin/API before use inside attested ranges. Their existing outside-range
construction protocol remains supported. This is an explicit compiler-ABI migration,
not an assertion of binary compatibility or a security sandbox for arbitrary forged
classfiles. General unavailable helper/Function bodies are not inferred safe.

One actual unified range capture may yield structural and digital partitions only
when actual typed dependency edges permit the split. Preserve source order and
common source-domain identity. No callback replay, default specialization, runtime
hierarchical index or accidental N-by-N expansion is permitted. Richer generated
state/child-port fusion remains F58/F159 ownership.

Native hardware_loop carries actual lower/upper/step and explicit capture operands,
a real induction/capture block signature and exact terminator. The closed
bounded-digital-v1 contract is independently checked. Actual iteration_index add/sub
operands reuse canonical checked arithmetic. Shared ShapeModel proves attained
images, injectivity, overlap and coverage without lane or residue enumeration.
The shared assignment owner retains ordinary and loop driver/consumer relations.
The first physical-image profile has one varying axis and fixed positive stride.
Unknown mixed-residue partitions, multiple varying axes and unsupported
noncontiguous re-radix proofs reject rather than sample representative lanes.
Static/affine queries use at most 65,536 actual edge visits and 128 active recursive
frames; memo hits still validate actual ownership. This is an active-stack bound,
not a full-DAG height or whole-pipeline linearity claim.

## Planning, resources and acceptance

Target-neutral plans distinguish bounded unrolled expansion from a compact
procedural recipe. Any unrolled plan must preflight finite node/edge/byte and
iteration budgets, preserve typed operand mapping and source order, and verify
its private candidate before publication. A procedural recipe retains actual
induction/capture/index edges and seed prelude. Stale or forged plans reject;
planning is transactional and repeatable. The current unrolled candidate profile
requires at most eight exact invariant iterations with invariant selector offsets,
65,536 nodes, 262,144 edges and
eight MiB of checked estimate. Larger or symbolic domains retain compact procedural
plans; they are not expanded until a proof/resource failure. Focused evidence must
verify the exact limits and fallback before checkpoint acceptance. No target HDL execution or
optimization-equivalence claim is implied; F65 owns target lowering.

Focused cases cover zero/one/many and symbolic counts, positive nonunit strides,
actual last attained indices, exact maximum and overflow boundaries, disjoint
lanes, complete output coverage, seeded chains, same/global cycles, missing/late
seeds, forward reads, stride holes, forged capture/index/scope/type records,
callback host effects and escaped values. Preserve ordinary collections,
reductions, writable storage and lawful structural/analog hdlRange predecessors.
Record bounded small/large representation measurements and actual public
source→bridge→native examples. This gate alone does not close any F55 checklist.

## Alternatives and remaining ownership

Reuse the existing typed callback validator, construction session, static integer
facts, physical shape maps and assignment graph. A second capture/alias engine,
host unrolling, representative/default induction, package-based purity whitelist,
metadata-only induction proof and a textual plan masquerading as a verified
candidate were rejected. Private checked candidates are not emitted RTL.

The separate [structural-conditional amendment](../roadmap/areas-symbolic-conditionals-v0.1-plan.md)
F-055.B.1.1 remains open. This gate does not grant parameter/index-dependent Scala
`if` capture, Area objects or later mixed sequential partitioning. Existing analog
placement and target capability gates remain independently authoritative.
