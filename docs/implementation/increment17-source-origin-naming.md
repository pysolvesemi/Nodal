# Increment 17 source spans, semantic naming, and origin graph

## Status

Increment 17 implements semantic identity and source provenance beneath the frozen Nodal public API v0.3.
Public API v0.3 remains unchanged. The implementation enriches private construction snapshots and the
already-frozen `DesignReport.sourceMap`; it does not add source syntax or backend-visible classes.

## Source capture

Every module, domain, declaration, expression, instance, and construction operation captures a
transaction-local source site with `StackWalker`. Internal API frames are separated from the first user
Scala frame, so the record retains both the public operation that created the node and the user source
span that requested it.

The source locator resolves repository-relative paths and line/column ranges without storing absolute
checkout paths. It also reads the nearby Scala source binding when a construction call appears in a
`val`, `var`, or `lazy val`. This source binding complements lexicographically sorted member reflection:
member reflection finds stable class fields, while source binding captures constructor-local
declarations such as a register or child instance inside a lexical domain block.

All source lookup caches are owned by one construction transaction. No mutable global, thread-local, or
JVM identity value enters a stable result.

## Semantic naming

Normal names are selected in this order:

1. an explicit Nodal name such as `.named("pixel_sum")` or an endpoint name;
2. a captured Scala source binding or class member;
3. a shaped-view name derived from its source value and operation;
4. a sink-affinity name derived from a named assignment or connection target;
5. an operation/source-span stem plus a stable SHA-256 digest.

Collisions are resolved with a digest of semantic source material. Traversal ordinals are never emitted
as a normal name. An ordinal may participate only in the digest used to disambiguate two otherwise
identical source sites, so a name never degrades to `wire_3`, `expr_17`, or an equivalent counter-only
form.

Module paths use the Scala instance binding (`Top.child`) rather than a child class plus instance
counter. Declarations, domains, shaped elements/views, and expressions share a deterministic module
namespace. Generated names are recorded separately for clock/reset ports, synchronizers, FIFOs, reset
controllers, crossings, pipeline/FSM state, anonymous registers, and required temporaries.

## Origin and sink affinity

The private origin graph assigns every captured module, instance, domain, declaration, and expression a
stable origin ID. Each node records:

- its semantic path and construction operation;
- its repository-relative source span;
- parent semantic paths derived from operands or hierarchy;
- an optional assignment/connection sink;
- whether an expression may be inlined.

The graph is deterministic across repeated and parallel elaborations. Runtime object identity is used
only to join live Scala objects while the transaction is open; emitted paths, IDs, and digests contain no
identity hash, allocation address, reflection order, or mutable-global sequence.

## Source-map retention

`ConstructionSnapshot.sourceMap` and `DesignReport.sourceMap` now contain the sorted semantic-path to
`SourceSpan` mapping. Every captured expression receives an entry. Expressions marked as inlined retain
their original expression-level source-map entries, rather than inheriting only the eventual sink
location.

## Deliberate boundaries

Increment 17 does not lower the origin graph to MLIR or HDL, choose backend materialization policy,
schedule pipeline stages, or emit generated clock/reset and crossing structures. The generated-name
inventory is deterministic metadata for those later passes. Increment 18 begins the Nodal MLIR dialect
skeleton.

Traversal ordinals are never emitted as a normal name.

All expression-level source-map entries remain present when nodes are inlined.

Source files sharing a basename are resolved with owner package and top-level type context.
