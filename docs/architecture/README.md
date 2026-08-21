# Nodal Architecture Decisions

This directory contains the accepted and proposed architectural decisions for Nodal core.

Architecture decision records (ADRs) define durable boundaries that later increments must implement and test. They are not substitutes for the public API design gates or detailed language semantics.

## Status values

- **Proposed:** under review and not binding.
- **Accepted:** binding for subsequent increments.
- **Superseded:** replaced by a newer ADR that links back to the earlier decision.
- **Deprecated:** retained for history but no longer recommended.

Accepted ADRs must not be edited to reverse their decision. A material change requires a new ADR that supersedes the old one. Clarifications that do not alter the decision may be added with a dated amendment.

## Accepted records

| ADR | Decision |
| --- | --- |
| [0001](0001-scala-frontend-native-compiler-split.md) | Split the Scala 3 construction frontend from the native MLIR/CIRCT compiler. |
| [0002](0002-mlir-authoritative-ir-and-nodal-dialect.md) | Make MLIR the authoritative compiler IR and define Nodal as an out-of-tree dialect. |
| [0003](0003-selective-circt-reuse.md) | Reuse CIRCT selectively where its digital semantics match; do not use FIRRTL as Nodal's primary IR. |
| [0004](0004-versioned-textual-mlir-bridge.md) | Start with a versioned textual-MLIR process boundary between Scala and `nodalc`. |
| [0005](0005-backend-capability-profiles.md) | Use explicit backend capability profiles with no silent semantic fallback. |
| [0006](0006-core-library-boundary.md) | Separate mandatory core from optional reusable libraries with one-way dependencies. |
| [0007](0007-implicit-clock-reset-domains.md) | Use implicit local clock/reset domains for ordinary state, explicit CDC/RDC crossings, and explicit emitted HDL. |
| [0009](0009-core-semantic-contracts.md) | Distinguish elaboration/symbolic/runtime stages, use lossless numeric defaults, directionless protocol payloads, exact connections, physical dimensions, and declared effects. |
| [0010](0010-digital-verilog-open-source-verification.md) | Infer the narrowest compatible backend, emit portable Verilog for digital-only designs, and verify generated RTL with open-source lint, simulation, synthesis, equivalence, and formal tools. |
| [0011](0011-ams-fpga-approximation-validation.md) | Validate supported AMS behavior on FPGA only through an explicit discrete-time, finite-precision approximation with separate reference, quantization, RTL, and hardware evidence. |
| [0012](0012-versioned-capability-plugin-architecture.md) | Use explicit manifests, a typed capability graph, deterministic phases, local design hosts, separate Scala/native/process boundaries, lockfiles, trust policy, and retained plugin provenance. |
| [0013](0013-structured-hdl-optimization-pass-architecture.md) | Use structured digital/Verilog-A/Verilog-AMS target IR, explicit locked pass pipelines, declared semantic effects, mandatory re-verification, and digital/AMS-appropriate proof evidence. |
| [0014](0014-target-neutral-formal-verification.md) | Preserve future user-authored formal properties in target-neutral, domain-aware IR and execute them through capability-checked proof-engine adapters with retained evidence. |
| [0015](0015-native-scala-enum-and-hierarchical-fsm.md) | Use native Scala 3 enums with stable canonical HDL encoding and typed reusable hierarchical/parallel FSM graphs with explicit reset, priority, recursion bounds, reports, and proof contracts. |
| [0016](0016-signed-types-and-staged-loops.md) | Preserve signed finite-width type semantics and distinguish Scala elaboration, symbolic structural generate, and bounded hardware-iteration loops through verified Verilog-family lowering. |
| [0017](0017-semantic-multidimensional-values-and-target-layouts.md) | Use semantic parameterized multidimensional shaped values, explicit structural-versus-memory intent, deterministic flattening, and target-aware Verilog/SystemVerilog layouts. |
| [0018](0018-expression-materialization-and-semantic-naming.md) | Inline safe pure expressions, materialize only for declared reasons, and derive deterministic semantic names and expression-level source maps for required objects. |
| [0019](0019-mandatory-pre-emission-hardware-quality-gates.md) | Require transactional staged internal verification and independent target lint/synthesis evidence before generated HDL is accepted. |

ADR 0012 owns general plugin discovery, resolution, loading, trust, lifecycle, and provenance. ADR 0013 layers the target-HDL-specific structured representations, pass profiles, preservation rules, and proof obligations on that common plugin foundation. Installing either a plugin or pass is inert until the project resolves and explicitly selects it in a locked plan.

ADR 0014 specializes ADR 0010 and ADR 0012 for deferred user-authored formal verification: Nodal owns property/domain/reset/task semantics, while CIRCT lowering, SBY/Yosys, and future engines remain capability-checked implementation and adapter layers.

## Proposed records

| ADR | Recommendation |
| --- | --- |
| [0008](0008-automatic-pipeline-architecture.md) | Use protocol-typed automatic pipeline regions with compiler scheduling, automatic alignment, explicit latency/throughput contracts, parameter-envelope safety, and bounded retiming. |

## Decision hierarchy

When two documents appear to conflict, use this order:

1. an approved, versioned public API or semantic design gate;
2. the newest accepted ADR;
3. the project charter in the root `README.md`;
4. the incremental roadmap.

An ADR may constrain a later design gate, but it must not invent public syntax unless the ADR explicitly owns that syntax and the public API gate approves it.
