# ADR 0001: Split the Scala frontend from the native compiler

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** Nodal core compiler architecture

## Context

Nodal is embedded in Scala 3 so users can use the official Scala parser, type system, metaprogramming facilities, and normal JVM ecosystem. Nodal also needs MLIR/CIRCT infrastructure, native compiler passes, dialect verification, and HDL translation.

Putting every concern into one runtime would couple the public construction language to native compiler implementation details and make each side harder to test, upgrade, and distribute independently.

## Decision

Nodal core is split into two principal implementation domains:

```text
Scala 3 source
    ↓
official Scala 3 compiler and JVM execution
    ↓
Nodal Scala frontend
    ↓
versioned bridge
    ↓
native `nodalc`
    ↓
MLIR/CIRCT passes and HDL backends
```

The Scala side owns:

- the concise public construction API;
- elaboration-time module construction and hierarchy;
- source-location capture;
- deterministic declaration and naming inputs;
- frontend diagnostics that can be decided without native compiler semantics;
- invocation of the versioned bridge.

The native side owns:

- parsing and verification of authoritative Nodal IR;
- Nodal dialect definitions;
- semantic analyses and compiler passes;
- selective CIRCT integration;
- capability-profile validation;
- Verilog-A and Verilog-AMS translation;
- native compiler diagnostics and exit status.

The Scala elaboration model is intentionally small and transient. It must not become a second authoritative compiler IR with independent optimization or backend semantics.

The initial integration is an external process boundary. JNI, JNA, an embedded native library, or Scala Native may be evaluated later only after the textual process contract is stable and profiling proves a material need.

## Invariants

- Public Scala code does not depend on C++ classes or compiler-private schemas.
- Native compiler code does not depend on JVM object identity, reflection order, or frontend traversal order.
- A Nodal model has one semantic meaning after it crosses the bridge.
- Semantic checks may be duplicated for faster feedback only when the native compiler remains authoritative and consistency tests cover both implementations.
- Each side can be built and tested independently.

## Consequences

### Positive

- Scala and LLVM/CIRCT toolchains can evolve on controlled schedules.
- Compiler crashes are isolated from the user JVM process.
- The bridge payload can be captured as reproducible debug evidence.
- Native compiler tests can operate directly on textual IR.
- A future non-Scala frontend can target the same versioned compiler contract.

### Costs

- A process launch and serialization boundary add overhead.
- Diagnostics must be mapped back across the boundary.
- Shared concepts require an explicit compatibility/version policy.
- End-to-end tests must cover both runtimes.

## Rejected alternatives

- **All compiler logic in Scala:** rejects the chosen MLIR/CIRCT foundation and duplicates mature native compiler infrastructure.
- **Fork or embed the Scala compiler:** unnecessary; Nodal is a Scala library/frontend, not a Scala language fork.
- **JNI from the first release:** reduces observability and couples release packaging before the semantic contract is proven.
- **Standalone custom Nodal parser:** loses the intended Scala-embedded construction model.

## Follow-up increments

- Increment 4 bootstraps the Scala modules.
- Increment 6 bootstraps `nodalc`.
- Increment 17 implements the first bridge.
- Increment 19 maps diagnostics across the boundary.
