# ADR 0002: Use MLIR as the authoritative IR and define an out-of-tree Nodal dialect

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** Native compiler representation

## Context

Analog and mixed-signal models require concepts that digital-only IRs do not natively represent: natures, disciplines, electrical nodes and branches, potential and flow access, analog contributions, continuous-time operators, analog events, connect semantics, and cross-domain rules.

Nodal also needs verified operations and types, source locations, canonicalization, rewrite passes, textual inspection, diagnostics, and future IR versioning.

## Decision

After Scala elaboration crosses the bridge, the authoritative compiler representation is MLIR.

Nodal will define a compiled, out-of-tree MLIR dialect with the namespace `nodal`. It will be implemented under `core/compiler` using normal MLIR dialect infrastructure and declarative TableGen/ODS definitions where suitable.

The Nodal dialect owns analog and mixed-signal semantics not accurately supplied by existing MLIR/CIRCT dialects. Candidate concepts include:

```text
nodal.module-domain metadata
nodal.nature
nodal.discipline
nodal.node
nodal.branch
nodal.potential
nodal.flow
nodal.analog
nodal.contribute
nodal.ddt
nodal.idt
nodal.cross
nodal.transition
nodal.connect
```

This list describes architectural ownership, not frozen operation names. Operation/type names and exact shapes are introduced incrementally and tested by dialect verifiers.

MLIR builtin and common dialects may be reused for universal concepts such as source locations, symbols, attributes, regions, control-flow infrastructure, and compatible arithmetic. CIRCT reuse is governed separately by ADR 0003.

The Scala frontend may maintain a temporary elaboration graph, but no semantic compiler pass or HDL backend may treat that graph as authoritative after MLIR generation.

Nodal remains out-of-tree. A CIRCT fork is not permitted unless a later design gate demonstrates that an upstream extension cannot meet the requirement and records the maintenance cost.

## Invariants

- Every authoritative Nodal construct has an MLIR representation or a documented lowering into a semantically compatible dialect.
- Invalid IR is rejected by verifiers before target translation.
- Source locations survive frontend lowering and compiler transformations where possible.
- Core semantic operations are target-neutral; backend syntax is introduced only in target-specific lowering/translation layers.
- Textual IR is deterministic enough for golden tests and diagnosis.
- Dialect changes are versioned before published compatibility is promised.

## Consequences

### Positive

- Nodal gains standard operation/type definition, verification, diagnostics, rewrite, pass, and testing infrastructure.
- Different abstraction dialects can coexist in one module.
- The compiler can reuse compatible MLIR/CIRCT components without flattening analog semantics into strings.
- IR dumps become reviewable evidence independent of generated HDL.
- Future frontends and backends can share one semantic compiler core.

### Costs

- Nodal must maintain native C++/TableGen code and a compatible LLVM/MLIR/CIRCT toolchain lock.
- Dialect evolution requires explicit compatibility planning.
- Some Verilog-AMS constructs may require new analyses or interfaces not present in CIRCT.

## Rejected alternatives

- **FIRRTL as the authoritative IR:** FIRRTL is centered on digital hardware and does not provide Nodal's analog equation semantics.
- **A custom Scala AST as the permanent compiler IR:** would require rebuilding verifier, pass, rewrite, diagnostics, and serialization infrastructure.
- **Direct Verilog-AMS string generation from elaboration:** prevents robust semantic passes, capability checks, and target-neutral evolution.
- **Immediate upstream CIRCT modification:** creates avoidable coupling before Nodal's semantics are proven.

## References

- [MLIR: Defining Dialects](https://mlir.llvm.org/docs/DefiningDialects/)
- [MLIR Language Reference](https://mlir.llvm.org/docs/LangRef/)
- [MLIR Diagnostic Infrastructure](https://mlir.llvm.org/docs/Diagnostics/)

## Follow-up increments

- Increment 5 locks compatible LLVM/MLIR/CIRCT revisions.
- Increment 15 creates the dialect skeleton.
- Increment 16 introduces the first structural operations.
- Increment 68 establishes published IR compatibility/version handling.
