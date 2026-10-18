# Nodal writable Real arrays design gate v0.3

**Status:** Approved
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing Foundation implementation authority is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected extension of
[v0.1](NodalWritableRealArrays-DG-v0.1.md) and
[v0.2](NodalWritableRealArrays-DG-v0.2.md) adds no public signature and closes no
Foundation parent checklist. It implements ordinary persistent shaped root
initialization without changing the existing native static-fill, exact-shape,
quantity, ownership or snapshot contracts.

## Language contract and consumer boundary

Use the Verilog-AMS 2.4 analog-language declaration/constant-assignment-pattern
contract. [VAMS 2.4](https://accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2-4.pdf)
section 3.4.8, pages 32–33, demonstrates mutable Real array replicated declaration
initialization; section 4.2.14, pages 60–61, specifies replication. This is not
SystemVerilog substitution or a new 2023-only feature.

The supported output form is:

```verilog
real state[0:COUNT-1] = '{COUNT{STATIC_VALUE}};
```

`COUNT` is the actual positive structural destination cardinality and
`STATIC_VALUE` is the native-verified scalar fill expression. Preserve symbolic
parameter references rather than substitute defaults. Both language profiles
Verilog-A and Verilog-AMS use the same analog declaration semantics here.

Language-structural acceptance is not independent consumer acceptance. Focused
executed controls show OpenVAF 23.5.0 and OpenVAF-reloaded
20260616-3-g0e83f1ed reject even uninitialized Real arrays at the declaration
bracket, while scalar controls compile. Neither supports a successful array
compile in these checks. The existing F43 implementation note carries exact
versions, hashes, actual public failing witnesses and the F48 handoff. No
simulator or numerical result is claimed. F43 D.1/D.2 explicitly separates
compiler structural work from later tool execution.

Current backend selection exposes language, not an enforced OpenVAF tool
profile. Do not invent a tool attribute and assume it grants compatibility.
Future explicit consumer selection must fail closed through pinned capability
classification before execution; generic language emission does not certify it.

## Exact ownership and lifetime contract

Admit initialized shaped Real variables whose immediate owner is a non-generated
analog procedure and whose declarations are emitted at module scope. This
includes roots in ordinary or event-containing procedures. The existing native
validator must continue to require an actual immutable static fill producer,
zero mutable-read operands, matching shape/rank/quantity units and an owned
static scalar expression. Pure shape views of a uniform fill do not change its
scalar value; replicate the destination count, not the producer's original
shape.

Emit a declaration initializer at the language-defined initialization phase.
Do not emit an initial_step wrapper, analog-initial loop or ordinary assignment
as a substitute. Initialization is not repeated on every analog evaluation;
"persistent" does not mean once forever across independent elaborations,
analyses or parameter sweeps. Preserve the standard's initialization lifecycle
rather than inventing a simulator-specific reset rule.

Derive the initializer from actual SSA producer/projection edges. Do not look up
source text by metadata name. Suppress runtime initializer-only fill/view
carriers only when their actual use graph is exclusively declaration
initialization; any shared ordinary consumer keeps valid materialization and
statement order. No array lane enumeration is permitted.

Generated root initializers remain an explicit target capability rejection.
Ordinary module-scope declaration support does not establish generated
per-occurrence identity/lifetime, induction-dependent initialization or nested
sweep behavior. Lexical/event-local initializers retain their existing
scope-entry snapshot/copy execution and are not converted to declaration
initialization.

## Target validation and compatibility

Retain checked signed-32 target dimensions, count and every retained static
expression intermediate, in addition to native signed-64 semantic proofs. A
native-valid but unsupported target must reject transactionally before output.

Extend the finite target reparser with replicated declaration-assignment-pattern
grammar, checked static Integer count and static scalar value references.
Reject malformed, empty, zero/negative or overflowing counts and proven
count/extent mismatches. Preserve actual symbolic identity/canonical emitted
shape relations, never parameter-default equality. Native proofs remain
authoritative for correlated symbolic cardinality; do not introduce a general
algebra engine or claim independent bounds proofs that were not performed.

Existing uninitialized arrays, scalar declarations, event behavior, ordinary
execution order, source mappings and generated capabilities retain their
semantics. Remove only the unsupported ordinary-root initializer rejection;
retain native mutable-read rejection and the generated-root boundary.

## Alternatives

Chosen: compact constant replicated declaration initialization using already
verified static fill and destination layout.

Rejected: literal lane expansion; resetting in initial_step or analog initial;
performing the initialization on every ordinary evaluation; substituting default
parameter values; silently selecting a newer language edition; claiming tested
OpenVAF success; dropping generated lifetime obligations; requiring later F48
qualification as a reverse prerequisite to F43 language implementation.

## Focused acceptance and review

Positive public/native/target witnesses cover nonzero fixed roots, symbolic
parameter count/value and quantity units, uniform view-backed fill, ordinary
roots in event-containing procedures and reuse by subsequent writes/copies.
Assert declaration placement and absence of a synthetic runtime reset. Repeated
CLI/bridge output must remain identical and both language translations reparse.

Negative and boundary cases cover native mutable-read initialization, malformed
pattern syntax, scalar/array role mismatch, count/extent identity mismatch,
zero/negative/overflow count, unsupported generated initialization and unchanged
output sentinels. Retain directly affected scalar/array/event/generated
predecessors. Review actual producer-use ownership, initializer-only suppression,
standard lifecycle, compactness and the explicit language/tool distinction.
Keep outputs under `.work`, concise commands/results in the existing F43 note,
and leave all unimplemented F43 work open. No CI/full qualification is required.
