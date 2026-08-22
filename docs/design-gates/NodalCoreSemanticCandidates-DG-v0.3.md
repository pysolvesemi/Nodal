# Nodal Core Semantic Candidates — v0.3 Evaluation

**Status:** Candidate evaluated; not frozen

**Increment:** 13

**Freeze owner:** Increment 15

## Purpose

This document records the compile-only evaluation required by Increment 13. It does not freeze public API v0.3 and does not authorize frontend, scheduler, MLIR, backend, simulator, synthesis, or formal implementation.

The binding architecture remains ADR 0009 together with ADRs 0015-0019 and their linked roadmap surfaces.

## Evaluated direction

The candidate keeps Nodal source compact while making semantic staging explicit:

- ordinary Scala values and `for` loops are elaboration-only;
- symbolic target-visible replication uses `generate(...)`;
- bounded same-cycle hardware iteration uses a distinct `loop(...)` contract;
- `Bits`, `UInt`, and `SInt` remain distinct semantic categories;
- signedness conversion is separate from bit reinterpretation;
- narrowing requires an explicit operation such as checked resize, truncate, wrap, or saturation;
- structural multidimensional `Vec` is separate from addressable `Mem`;
- aggregates remain directionless and protocol wrappers remain reusable values;
- memory and external operations carry explicit latency, ordering, domain, effect, throughput, and model metadata;
- analog quantities carry dimensions independently of backend syntax;
- expression materialization, naming, quality checks, and waivers are policy inputs rather than accidental compiler-node artifacts;
- native Scala 3 enums may derive hardware enum metadata while explicit canonical encoding—not Scala ordinal—defines hardware ABI;
- FSM/statechart candidates are typed graphs with explicit initial state, encoding, illegal-state policy, transition mode, hierarchy, parallel composition, timed transitions, and bounded call-stack contracts.

## Architecture comparison

### Chisel-style strengths retained

Nodal retains typed Scala construction, reusable aggregate/value composition, explicit protocol wrappers, and compile-time rejection where Scala's type system can express the contract. Nodal does not adopt backend-driven width/signedness behavior or require ordinary synchronous source to expose clocked process syntax.

### SpinalHDL-style strengths retained

Nodal retains concise register/protocol construction, typed streams, explicit clock/reset-domain ownership, and reusable high-level control structures. Nodal separates structural shape from memory semantics and keeps staged loop categories explicit instead of relying on one Scala iteration mechanism to imply multiple hardware meanings.

### CIRCT/MLIR role

CIRCT/MLIR remains the implementation foundation after public construction closes. The Scala candidates intentionally avoid exposing CIRCT operation names or backend layout syntax. Target-visible shape, signedness, enum/FSM identity, memory/effect contracts, checks, and source provenance must survive lowering before any target-specific representation is selected.

## Positive compile coverage

`examples.coreSemanticsApi` covers:

- Scala elaboration values and loops;
- symbolic `generate` and bounded hardware `loop`;
- signed and unsigned arithmetic and shifts;
- explicit numeric conversion, reinterpretation, narrowing, wrap, and saturation;
- directionless aggregate candidates;
- rank-three parameterized `Vec`, indexing, flatten, reshape, map, zip, and reduce;
- plain values, `Valid`, and `Stream`;
- portable-Verilog and future-SystemVerilog layout policy candidates;
- explicit memory and external-operation contracts;
- dimension-safe quantities;
- temporary/naming/check-profile/waiver candidates;
- native Scala enum derivation and custom canonical encoding;
- safe enum decode;
- typed FSM, entry/active actions, exclusive/priority transitions, timed transitions, nested reuse, parallel composition, terminal states, and bounded call-stack metadata.

`examples.coreSemanticsExternal` proves that the reusable subset compiles from an independent source module depending only on the public API module.

## Negative compile coverage

Increment 13 executes independent Scala type-negative fixtures for:

- mixed `UInt`/`SInt` arithmetic without explicit conversion (`NODAL-NUM-013`);
- incompatible physical-dimension addition (`NODAL-UNIT-013`);
- a runtime hardware signal used as a structural `generate` bound (`NODAL-STAGE-013`).

The fixture manifest also records semantic-negative contracts that require the later construction/IR verifier: implicit narrowing, invalid parameter envelopes, shape mismatch, `Vec`/`Mem` confusion, unknown memory latency/effect, latches, combinational loops, multiple drivers, hierarchy ownership errors, invalid waivers, enum encoding collisions, non-exhaustive enum selection, overlapping exclusive FSM transitions, and unbounded FSM recursion.

## Deferred decisions

Increment 13 deliberately does not freeze:

- exact final spellings of `Vec`, aggregate, loop, quantity, memory, external-operation, naming/check, enum, or FSM APIs;
- exact diagnostic numbering for v0.3;
- target lowering;
- scheduler interaction;
- portable-Verilog or SystemVerilog syntax;
- MLIR operation names;
- implementation behavior.

Increment 14 evaluates automatic-pipeline syntax against these semantics. Increment 15 performs the unified v0.3 freeze after both candidate sets are available.

## Exit criteria

Increment 13 is complete only when:

1. both positive modules compile on the pinned Scala toolchain;
2. every Scala type-negative fixture fails independently for the intended source;
3. the external consumer uses no internal/frontend/compiler package;
4. the candidate checker confirms roadmap and architecture linkage;
5. formatting and repository Core CI remain green;
6. no frontend/backend implementation is introduced;
7. the roadmap marks only Increment 13 complete and leaves Increment 14 as the next unchecked item.
