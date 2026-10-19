# NodalDigitalStructValues-DG v0.1

**Status:** Approved
**Scope:** additive typed public API and native value contract
**Decision:** canonical semantic schema handles over the existing digital value DAG

## Authority and compatibility

The explicit all-unchecked-Foundation implementation authority recorded in
[NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md) covers the
required F55/BT record-value prerequisite. This is not a claim of separate user
selection of the signature. Preserve existing Struct/StructField type construction
and all exact assignment/layout rules. Equivalent reconstructed semantic schemas
remain equivalent; no generative host-object identity is introduced.

## Public surface

`DataType[Struct].member(StructField[A])` returns a privately constructed
`StructMember[A]`. `Expr[Struct].field(member)` reads the actual receiver field.
`member.bind(Expr[A])` returns a private immutable `StructBinding[A]`;
`schema.value(bindings*)` constructs one actual complete record value. These are
typed handles and real DAG operations, not string-to-generic casts or fake operands.

## Exact contract

1. A member is bound to its exact semantic schema and declared ordinal/name. Member creation requires the exact field descriptor, including its scalar-width parameter identity. Schema identity includes nominal name, ordered field names, leaf kinds, enum ABI identity, recursively ordered structure, and exact inner Vec dimensions/rank/parameter identities.
2. Define the construction-only width-family relation by erasing only valid `Bits`, `UInt`, and `SInt` scalar width leaves while preserving all other identities. Each complete type is validated independently; no parameter defaults, coincident ranges, host identity, or unchecked metadata establish membership. Enum widths are part of enum ABI and are not erased. Inner Vec dimensions remain exact.
3. Projection may use a handle from that width family, but its result type is the selected field descriptor from the actual receiver. It never returns the handle's original width as a substitute.
4. Construction requires every field exactly once in declared order, with handles from an exact equivalent schema and values from each corresponding width family. All actual scalar width expressions/parameter identities are retained in the resulting complete Struct ABI. Width specialization is a different complete type. Existing exact `sameType`, bridge exact comparison, and native `equivalentDigitalStorageTypes` remain exact for assignment, selection, storage, and all existing consumers.
5. The current shared value DAG admits finite digital roots (Bool, Bits/UInt/SInt, enum, Vec, Struct). Whole-record values may preserve existing opaque Real/Integer fields, and digital fields can be projected from such a record. Standalone Real/Integer projection, binding, and make operands fail explicitly at the applicable source/bridge/native boundary. No analog/static runtime producer is introduced and opaque fields are never dropped. Clock/Reset and unrelated capabilities remain excluded.
6. Source and bridge preserve Bool as a distinct kind. Existing native Bool ABI is `!nodal.bits<1>`; native verifies that actual representable canonical ABI, not an invented distinction. This prerequisite does not migrate Bool or add tags.
7. Existing bounded structural verification and DAG limits remain applicable. Struct schemas retain the existing 65,536-field type bound; `value` is additionally bounded by the shared 4,098-operand expression budget. No reduction callback type algebra, formal-width lexical environment, global registry, or backend lowering is added.


## Architecture and alternatives

Reuse canonical source/bridge/native structural comparisons and the shared value,
callback, scope and capture owners. The width-family relation is construction-only;
ordinary ABI equality is never relaxed. `digital_struct_get` and
`digital_struct_make` preserve real operands and shared producers. Reject a parallel
schema registry, per-field callback invocation, arbitrary metadata attestations,
default specialization and silent opaque-leaf loss. No backend or reduction
formal-width proof is invented by this prerequisite.

## Focused acceptance

Exercise reconstructed schemas, independent symbolic widths and dimensions, exact
ABI mismatch, field ordering/identity/enum/kind negatives and opaque capabilities.
Use actual whole-record score-winner and shared-predicate cross-field construction,
both directly and inside collection callbacks. Verify source/bridge/native
correspondence, lexical SSA sharing and normalization idempotence; retain tracked
source maps and affected F54/F55 predecessors. Isolated staged tests are preparation
only; integrated focused replay is required before claiming this deliverable.
