# ADR 0003: Reuse CIRCT selectively; do not make FIRRTL the Nodal pipeline

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** CIRCT dependency and dialect reuse

## Context

CIRCT provides reusable hardware compiler dialects and infrastructure. Its `hw` dialect is a generic hardware representation, while dialects such as `comb`, `seq`, and `sv` cover digital operations and SystemVerilog-oriented constructs.

Nodal needs those digital capabilities for Verilog-AMS models, but it also has continuous-time and cross-domain semantics that cannot be represented faithfully by digital-only operations.

## Decision

Nodal depends on CIRCT infrastructure but reuses individual CIRCT dialects only after semantic comparison.

Initial candidates are:

- `hw` for compatible module, instance, symbol, parameter, and hardware type concepts;
- `comb` for compatible pure digital combinational operations;
- `seq` for compatible discrete sequential state;
- `sv` for target-near digital/SystemVerilog constructs when retaining those semantics is intentional;
- supporting CIRCT analyses, interfaces, and emit infrastructure where they remain target-correct.

The Nodal dialect retains ownership whenever:

- analog or continuous-time semantics are involved;
- Verilog-AMS behavior differs from the existing CIRCT operation contract;
- a target-neutral construct would otherwise be contaminated by SystemVerilog syntax;
- cross-domain verification requires information that a digital lowering would erase.

FIRRTL and `firtool` are not Nodal's primary IR or compiler pipeline. Chisel is not a Nodal dependency.

Reuse is decided operation by operation and recorded in tests or a design note before the first production lowering. Similar names are not sufficient proof of semantic equivalence.

Opaque `sv.verbatim`-style text is not an ordinary representation for missing Nodal semantics. Any escape hatch must be isolated, capability-gated, excluded from transformations that cannot reason about it, and approved separately.

## Invariants

- Analog and mixed-signal semantics are never forced into FIRRTL, `comb`, `seq`, or `sv`.
- Lowering into a CIRCT dialect must preserve Nodal's type, scheduling, connectivity, and source-mapping requirements.
- A CIRCT upgrade cannot silently change generated AMS behavior; compatibility tests must detect differences.
- Backend-neutral IR remains above target-specific `sv` operations unless the source construct is itself intentionally SystemVerilog-specific.
- The Nodal compiler can reject a reuse candidate and provide its own operation without changing the public Scala API.

## Consequences

### Positive

- Nodal avoids rebuilding proven digital hardware infrastructure.
- Digital parts of mixed-signal models can participate in existing CIRCT passes where semantics match.
- The compiler remains able to evolve toward future SystemVerilog-aligned AMS output.
- Analog semantics remain explicit and verifiable.

### Costs

- Mixed-dialect legality and lowering boundaries require careful verification.
- CIRCT version changes must be pinned and qualified.
- Some concepts may temporarily exist in Nodal form before lowering to CIRCT.

## Rejected alternatives

- **Use all CIRCT dialects by default:** dialect availability does not guarantee Verilog-AMS semantic compatibility.
- **Use FIRRTL because Chisel uses it:** Nodal is not a digital RTL frontend and requires different foundational semantics.
- **Reimplement all digital operations in Nodal:** duplicates useful CIRCT infrastructure and increases backend work.
- **Represent unsupported operations as raw HDL text:** blocks analysis and weakens diagnostics.

## References

- [CIRCT dialect index](https://circt.llvm.org/docs/Dialects/)
- [CIRCT HW dialect](https://circt.llvm.org/docs/Dialects/HW/)
- [CIRCT SV dialect](https://circt.llvm.org/docs/Dialects/SV/)

## Follow-up increments

- Increment 5 pins the CIRCT/LLVM pair.
- Increment 16 records the first `hw` reuse decision.
- Increments 51–55 introduce digital constructs and their CIRCT lowerings.
