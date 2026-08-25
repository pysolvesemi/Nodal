# Nodal Scala-to-MLIR bridge design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-bridge
**Scope:** public-api
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 20 connects the deterministic Scala construction snapshot to the
canonical private `nodal` MLIR model introduced by Increment 19. The bridge is
versioned, deterministic, source-correlated, and internal to Nodal. It does not
add public hardware syntax, select a backend, or implement whole-design passes.

The bounded `public-api` scope authorizes only private `[nodal]` construction
metadata required to preserve exact literal, clock/reset, relationship, and
instance-parameter facts across the bridge. It adds no public class, method,
constructor, enum case, import, or source spelling and does not revise public
API v0.3.

## Textual bridge contract

The binding bridge schema is `nodal.scala-to-mlir` version 1. One construction
transaction produces one UTF-8 MLIR document containing:

- the root identity and bridge schema/version;
- deterministic module, domain requirement/declaration, port, parameter,
  instance, logical Interface ABI, resolved-net, and conservative-terminal
  records where the Increment 19 schema has an exact representation;
- complete deterministic inventories for declarations, semantic names,
  origins, generated names, logical topology, and source-map spans;
- operation locations using MLIR `loc` syntax plus retained end positions in
  metadata.

Module paths, declaration paths, instance paths, parameter bindings, domain
bindings, and source identities come from the accepted construction snapshot.
Traversal counters, JVM identity, temporary-directory paths, and backend
spelling do not define bridge identity.

The bridge never invents a clock edge, reset policy, parameter type/default,
domain binding, or unsupported data type. An unrepresentable snapshot fails
with a stable bridge diagnostic before `nodalc` is started.

## Native process protocol

`nodalc` is invoked through a typed request and response contract:

- the executable, ordered arguments, working directory, environment additions,
  and timeout are explicit;
- one isolated UTF-8 `input.mlir` file is appended as the final argument;
- no shell, command string interpolation, inherited standard input, or implicit
  PATH lookup defines the invocation;
- standard output is normalized MLIR, standard error is diagnostics, and the
  exit code is retained;
- timeout terminates the child process and returns a stable diagnostic;
- non-zero exits remain distinguishable from launch failure and timeout;
- temporary input is removed on every success or failure path.

The process environment records bridge schema and protocol version but does not
change Nodal semantics.

## Verification boundary

Increment 20 proves deterministic text, source locations, schema metadata,
structural round-trip through the locked `nodalc`, mock-process success,
non-zero diagnostics, timeout handling, and recovery after failure.

Whole-design symbol resolution, driver/latch/cycle checks, hierarchy and domain
verification, normalized pass pipelines, and transactional accepted-state
management remain assigned to Increment 21.

## Explicitly deferred

- whole-design semantic and capability passes;
- expression, assignment, state, memory, analog-equation, and scheduling
  lowering beyond the exact Increment 19 representation;
- CIRCT conversion, backend layouts, HDL emission, and external simulator use;
- public compiler-process configuration or a public bridge API.
