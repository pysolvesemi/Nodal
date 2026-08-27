# Increment 26 — deterministic output and reproducibility contract

Increment 26 adds `ReproducibilityContract` inside the private Scala compiler bridge. It consumes
one closed `ConstructionSnapshot`, renders canonical source MLIR, optionally runs the mandatory
native semantic gate and Verilog-A translator, and publishes a `ReproducibilityBundle` only after
all requested stages succeed.

## Canonical artifacts

The bundle retains four normalized artifacts:

- `construction.json` — a canonical serialization of the complete construction snapshot;
- `source.mlir` — deterministic Scala-to-Nodal MLIR output;
- `normalized.mlir` — accepted native pipeline output;
- `output.va` — transactionally verified Verilog-A output for the current RC slice.

All text is UTF-8 with LF endings. Each artifact contributes SHA-256 and byte length to the
canonical manifest. The manifest itself has a SHA-256 computed over its complete canonical text.

## Inventories

The manifest derives ordered evidence directly from accepted compiler state:

- shape, layout, and structural-storage classification per declaration;
- expression inline/materialize decisions and stable reason codes;
- declared/generated semantic names and provenance;
- expression origin, source span, parent, sink, and inline state;
- `nodal.verify.*` mandatory check identities present in source or normalized MLIR;
- explicit waiver origins;
- clock/reset-domain declarations, bindings, policies, and attributes;
- CDC/RDC origins and generated synchronizer/crossing/reset-controller names.

Empty inventories are written as `[]`. They never disappear and are never interpreted as proof
that a future feature is supported.

## Ordering rules

Snapshot modules, declarations, domains, instances, ABI entries, resolved nets, topology edges,
names, origins, generated names, source maps, regions, and contributions are serialized under
stable semantic keys. JSON object keys are sorted. Semantic sequences such as analog expression
operands remain in their original order.

`ReproducibilityContractTests` reverses every semantically unordered collection and compares the
result with independently reconstructed snapshots. With `NODAL_NODALC` and `NODAL_TRANSLATE`
configured, it also runs the RC snapshot and its valid permutation through separate working
directories and requires byte-identical normalized MLIR, Verilog-A, and manifest output.

## Failure behavior

The contract reuses the Increment 20 process protocol and Increment 23 transactional backend.
Construction, native verification, translation, target verification, reparse, timeout, launch, and
nonzero-exit failures return `NativeCompilerFailure`; no accepted reproducibility bundle is
returned. Temporary compiler inputs remain excluded from the manifest.

Public API v0.3 is unchanged. Increment 27 and later language features remain unstarted.
