# NodalDigitalCollections-DG v0.1

**Status:** Approved
**Scope:** public-api
**API version:** additive to frozen core v0.3, with explicit inert-zip migration
**Decision:** capture typed combinational collection regions without lane expansion

## Authority and compatibility

The owner's request to implement all unchecked Foundation work from increment 43
covers the required F55 collection implementation and its necessary shared owners.
The exact request and publication authority are recorded in the
[scalar expression gate](NodalDigitalExpressions-DG-v0.1.md). This does not claim
that the owner separately chose each signature below.

Preserve `map(f)` and `reduce(f)` source signatures. Add the following methods on
`Expr[Vec[A]]`, with `A`, `B` and `C` extending `Data`:

- `zipWith[B, C](other: Expr[Vec[B]])(f: (Expr[A], Expr[B]) => Expr[C]): Expr[Vec[C]]`
- `fold[B](initial: Expr[B])(f: (Expr[B], Expr[A]) => Expr[B]): Expr[B]`
- `scan[B](initial: Expr[B])(f: (Expr[B], Expr[A]) => Expr[B]): Expr[Vec[B]]`

The frozen inert `zip(other): Expr[Vec[A]]` cannot represent both A and B.
Retain its source signature as a source-located migration diagnostic directing
callers to `zipWith`. It must not return only the left side, discard B, or label a
pair as A. This corrects an unimplemented candidate, not working pair semantics.
No source-warning annotation is required to preserve existing compile-only
compatibility fixtures. A future actual pair-returning `zip` would require its
own explicit versioned source migration.

## Exact collection contract

`map` preserves every input dimension and changes only the element type.
`zipWith` requires exactly equal dimensions, including symbolic identity, and
retains both input operands. Equal cardinality with different shapes is not
sufficient. Whole Struct and nested Vec elements remain single typed values.

`fold` traverses elements in canonical row-major order, applying the callback to
the previous accumulator and next element. `scan` uses the same order and returns
the accumulator after each element. It excludes the initial seed and preserves
all input dimensions: N input elements produce N outputs. Its final logical
output equals `fold` with the same seed and callback. This is intentionally not
Scala `scanLeft`'s N+1 convention.

Fold and scan require exactly the seed's complete type at every step. Explicit
conversions in the callback may establish that type. No hidden truncation,
max-width padding, sign change or heterogeneous-vector coercion is permitted.
Existing positive Vec dimensions remain required; this does not introduce empty
shapes. Widening left and balanced reduction remain required separate F55 work;
this homogeneous fold/scan contract does not replace or waive them.

## Capture, effects and native ownership

Capture the callback once as a typed region template with real scoped argument
identities. Never invoke it per lane, specialize a parameter default, manufacture
an element-zero surrogate, or infer a callback from its source text. Retain the
actual value DAG, captures, yielded value and exact result type. Nested regions
may capture only visible parent values through explicit operand/argument edges.
Escaped arguments, sibling/descendant references and cross-module captures reject.

Only supported combinational hardware operations belong in collection bodies.
Structural declarations, state, assignments, analog effects, external latency and
unknown hardware effects reject. Ambient mutable Scala effects and unverifiable
callback effects require typed frontend rejection before any purity claim;
runtime recording alone does not prove arbitrary Scala code pure. Callback
validation must preserve helper ownership and source locations, rather than
recognizing example names or source strings.

One native `collection` operation with closed kind, actual input/seed/capture
operands, one result and an isolated single-block body carries the region.
An explicit one-value `collection_yield` terminates it. The native verifier
independently checks operand segments, types, arguments, yield, shape cardinality,
lexical ownership, dominance and the closed pure body profile. Existing digital
value, shape, parameter and type owners remain authoritative. One scope-aware
bridge dependency schedule renders both module values and body-local SSA; it
must not hoist an argument-dependent expression outside its region.

## Alternatives, scope and focused checks

Rejected: host lane unrolling, default-dependent counts, duplicate value engines,
field-by-field callback execution, fake pair carriers and fixed-width reduction
accumulators that lose required precision. A typed region is the smallest shared
representation for later ordered reduction, bounded iteration and scheduling.
Target emission/execution is reported only for actual implemented consumers;
verified native IR alone does not establish HDL or simulator success.

Focused acceptance includes map/zip shape and width changes, whole aggregates,
actual right-operand use, ordered fold/scan, singleton and symbolic dimensions,
explicit conversion, nested captures, scope escape/effect/type/yield mutations,
large count with body size independent of lanes, deterministic repeated capture,
and affected F43/F54 shape/type/ownership regressions. All F55 parent and linked
reduction requirements remain open until their own implementation and checks.
