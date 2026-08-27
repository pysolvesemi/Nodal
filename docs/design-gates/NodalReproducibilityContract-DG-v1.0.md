# Nodal deterministic output and reproducibility contract design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-reproducibility
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 26 defines one private, canonical artifact contract over accepted construction state,
source-correlated Nodal MLIR, mandatory native verification, and transactionally emitted HDL.
The contract proves that repeated construction, valid permutations of semantically unordered
collections, and different native-process working directories produce byte-identical artifacts
and one byte-identical machine-readable manifest.

The first executable proof uses the Increment 25 Scala RC vertical slice and Verilog-A profile.
The contract is backend-extensible, but it does not claim that later analog, digital, or
mixed-signal features are already implemented.

## Canonical identity

- Text artifacts use UTF-8 and LF line endings.
- No timestamp, process ID, random value, temporary-directory path, runner path, map iteration
  order, filesystem enumeration order, or JVM identity participates in artifact identity.
- JSON object keys and semantically unordered inventories are sorted deterministically.
- Ordered expression operands, contribution order, protocol order, and other semantic sequences
  are preserved and are never sorted merely to obtain reproducibility.
- Each accepted artifact records SHA-256 and byte length.
- A manifest digest is computed over the complete canonical manifest text, excluding no required
  section and avoiding a self-referential digest field.

## Required manifest sections

The v1 contract records:

1. canonical construction snapshot and source/normalized MLIR and HDL artifact identities;
2. shape/layout and structural-storage classifications;
3. expression materialization decisions and reasons;
4. declared and generated semantic names;
5. expression-level source maps and origin/sink relationships;
6. mandatory check inventory and explicit waiver inventory;
7. clock/reset-domain declarations and bindings;
8. CDC/RDC, synchronizer, crossing, reset-controller, and waiver-origin evidence.

An unavailable category is represented by a deterministic empty array, not omission, inferred
success, or host-dependent text. A waiver inventory entry records the stable origin and available
source context; later increments may add typed waiver payload fields without changing v1 identity.

## Transactional boundary

The native pipeline remains:

```text
construction -> canonical MLIR -> mandatory semantic gate -> target translation
             -> target verify/reparse -> private buffer -> publish
```

A failed construction, bridge, verifier, translator, target verifier, or reparse step publishes no
accepted HDL or reproducibility manifest. The manifest describes only a completed accepted
transaction.

## Verification obligations

- Repeated Scala RC construction produces identical construction JSON, MLIR, and manifest bytes.
- Reversing every semantically unordered snapshot collection produces identical bytes.
- Native verification and Verilog-A emission in distinct working directories produce identical
  normalized MLIR, HDL, artifact hashes, inventories, and manifest bytes.
- Domain, CDC/RDC, generated-name, materialization, check, and waiver inventories have explicit
  positive or empty coverage.
- Mutation tests reject missing sections, writable permanent CI, premature roadmap closure,
  semantic operand sorting, and absent evidence.

## Explicitly deferred

Cross-host/toolchain equivalence beyond the pinned supported toolchain, remote-cache protocols,
release signing, SBOM/provenance attestations, generalized project-root remapping, digital HDL,
Verilog-AMS bodies, simulator output reproducibility, and reproducibility of features not yet
implemented remain assigned to later increments.
