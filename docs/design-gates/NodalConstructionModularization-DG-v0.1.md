# Nodal construction modularization design gate v0.1

**Status:** Approved
**Scope:** public-api
**Public API:** unchanged at 0.3 and its accepted additive hierarchy contracts.
**Decision:** Bounded private construction refactor under Foundation 160.
**Authorization:** Owner instruction on 2026-09-29 to start the next Foundation
increment, applying the approved 2026-09-24 F-160 scope and existing frozen API
invariants. This gate authorizes that internal implementation only; it does not
approve a new public API or core/library boundary.

## Exact contract

The protected directory contains private construction implementation as well as
public declarations. Foundation 160 may move existing internal records,
snapshots and the transaction session into focused files and extract stateless
expression facts and Interface layout. Preserve all public declarations,
facade signatures, object evaluation counts, ownership, validation ordering,
source identities, snapshot fields/defaults, bridge schema, native semantics
and generated HDL bytes. Keep original package/private visibility; do not expose
mutable records to satisfy cross-file compilation.

`ConstructionSession` remains the sole canonical mutable registry and lifecycle
owner. `ConstructionKernel` retains immutable ScopedValue binding and the exact
constructor/procedural session nesting. The two helpers receive values or a
narrow type-rendering callback and add no cache, mutable owner or global state.
The existing semantic-origin filter may list the moved implementation files so
they remain internal stack frames; public caller/source identities must match.

No public syntax, macro/plugin contract, package boundary, MLIR representation,
backend capability, algorithmic optimization, Rust migration or new generation
feature is authorized by this gate. Any intentional externally visible change
requires its own explicit approval; a file relocation cannot conceal it.

## Alternatives and rationale

Retaining every responsibility in one file preserves existing coupling.
A new mutable service framework, duplicated registries, broad session views or
a complete snapshot rewrite add unnecessary integration and ordering risks.
Extracting existing stateless facts and layout while retaining coordinated
capture makes hierarchy/ownership reasoning independent of their detailed
semantics. Separate records and facade expose those boundaries without a new
public surface or arbitrary file-size goal.

## Validation and approval limits

The [readiness record](../implementation/increment160-readiness.md) pins the
accepted before-state, exact boundary map, full checklist applicability,
fixtures, methodology and regression budgets. Require identical complete
construction records, semantic/source paths, normalized IR, generated HDL and
diagnostic identities on identical public fixtures with pinned tools.
Preserve all existing failure-safety and predecessor tests, immutable historical
checkers and source-review rejection strength. Qualify actual production calls,
not unused helpers. Record scale samples and investigate material regressions.

This gate records authorization to implement the already selected bounded
internal work. It is not independent Codex review, executed parity/performance
evidence, a merge approval without required checks or completion of F-160.
