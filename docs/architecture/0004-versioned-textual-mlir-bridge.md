# ADR 0004: Start with a versioned textual-MLIR process bridge

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** Scala-to-native compiler transport

## Context

The Scala frontend and native compiler need a stable, observable boundary. The first implementation must favor correctness, debuggability, reproducibility, and independent testing over minimum invocation overhead.

MLIR supports a textual form suitable for inspection and test fixtures. MLIR also has a bytecode format, but dialect compatibility still requires explicit dialect version management.

## Decision

The first Scala-to-`nodalc` bridge uses deterministic textual MLIR transferred through a child-process boundary.

The Scala frontend will:

1. elaborate a model;
2. emit versioned Nodal MLIR with source locations;
3. invoke `nodalc` through a file or standard-input contract;
4. capture exit status, diagnostics, normalized IR, and requested backend outputs.

The bridge must carry explicit protocol and IR version metadata. Exact attribute spelling is finalized during implementation, but the payload must distinguish at least:

- bridge protocol version;
- Nodal dialect/IR version;
- requested compiler/backend profile;
- source identity needed for diagnostic mapping.

`nodalc` owns parsing and authoritative verification. Transport or parse failures must not be reported as successful elaboration.

Textual MLIR is the debug and conformance reference form for the initial compiler. Normalized payloads may be retained as test artifacts.

MLIR bytecode, an in-process native library, JNI/JNA, or a long-lived compiler daemon may be added later only when:

- the textual contract and IR version policy are stable;
- compatibility tests cover upgrades;
- measurement identifies process or text serialization as a meaningful bottleneck;
- the alternative preserves equivalent diagnostic and debug evidence.

## Process contract principles

- The process exit code is authoritative for success/failure.
- Diagnostics are emitted on a channel separate from generated HDL.
- Machine-readable diagnostic transport may be added, but human-readable diagnostics remain available.
- Partial or stale output files are not treated as successful results.
- Compiler and frontend versions are recorded in reproducibility evidence.
- Temporary files and captured payloads avoid host-specific absolute paths in deterministic comparisons.
- Unknown future bridge or IR versions are rejected explicitly.

## Consequences

### Positive

- Frontend/native failures are isolated.
- IR can be inspected, minimized, replayed, and tested without Scala elaboration.
- Native tests can use `lit`/FileCheck-style fixtures.
- Toolchains can be packaged independently.
- The bridge forms a possible contract for future frontends.

### Costs

- Process startup and text parsing add overhead.
- Version fields and diagnostic mapping must be designed early.
- Large models may later justify bytecode or a persistent compiler service.

## Rejected alternatives

- **JNI/JNA initially:** hides the boundary and creates packaging complexity before semantics are stable.
- **Ad hoc JSON as the semantic IR:** duplicates MLIR's operation/type/attribute representation and parser.
- **Unversioned textual MLIR:** makes frontend/compiler skew ambiguous and unsafe.
- **MLIR bytecode only from day one:** reduces immediate inspectability and does not remove the need for dialect version policy.
- **Direct HDL text from Scala:** bypasses the authoritative native compiler.

## References

- [MLIR Language Reference](https://mlir.llvm.org/docs/LangRef/)
- [MLIR Bytecode Format](https://mlir.llvm.org/docs/BytecodeFormat/)
- [MLIR Diagnostic Infrastructure](https://mlir.llvm.org/docs/Diagnostics/)

## Follow-up increments

- Increment 17 defines and implements bridge protocol v0.
- Increment 19 implements cross-layer diagnostic mapping.
- Increment 23 proves deterministic IR/output generation.
- Increment 68 defines published bridge and IR compatibility.
