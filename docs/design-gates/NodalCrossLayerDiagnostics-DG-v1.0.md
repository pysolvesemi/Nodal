# Nodal cross-layer diagnostics design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-diagnostics
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 22 defines one stable diagnostic identity across Scala construction,
the textual bridge, native parsing, local operation verification, mandatory
semantic passes, future backends, and external tools. Raw MLIR, CIRCT, C++, JVM,
or simulator wording never defines the user-visible diagnostic contract.

Every native semantic failure retains a stable `NODAL-*` code and appends the
best available semantic path, hierarchy path, optional multidimensional index
path, and Scala source range. Mapping uses operation metadata and MLIR locations
emitted by Increment 20. It never derives identity from traversal order, JVM
identity, a temporary input path, or target-specific names.

## Context contract

The native mapper searches the failing operation and its ancestors for:

- `metadata.semantic_path` as the primary source-semantic identity;
- `metadata.hierarchy_path` and `metadata.index_path` when explicitly retained;
- symbol names as a deterministic hierarchy fallback;
- `FileLineColLoc` for the source start location; and
- `metadata.source_end_line` plus `metadata.source_end_column` for the end.

Diagnostics use stable suffixes such as:

```text
[semantic-path=Top.stream.payload]
[hierarchy-path=Top.child]
[index-path=[row=1,col=2]]
[source-range=src/Top.scala:42:9-42:31]
```

Absent context is omitted rather than invented. Parser failures without an
operation receive a stable parser family code and retain the parser location.
Process launch, timeout, nonzero exit, and cleanup failures remain distinct.

## Binding diagnostic families

Increment 22 adds or freezes stable codes for:

- unstorable Interface values;
- unknown, incompatible, or incomplete Interface roles and members;
- monitor-drive and invalid role inversion;
- deterministic Interface layout collisions;
- multiple ordinary drivers;
- illegal open-drain/open-source drive intent;
- unsupported resolved-net capability;
- invalid hierarchical inout pass-through;
- conservative-discipline and access mismatches;
- implicit analog/digital or conservative/signal-flow conversion;
- parser, verifier, pass, backend, external-tool, and process fallback classes.

The machine-readable catalog is `core/compiler/diagnostics-v0.1.json`. Existing
`NODAL-VERIFY-*`, construction, bridge, and process codes remain valid and are
mapped through the same context formatter.

## Verification boundary

Native fixtures prove mapped source/hierarchy context and each new
Interface/inout/AMS family. Scala tests prove preservation of native codes,
parser fallback classification, and nonzero external-tool classification.
Mutation tests prevent writable CI, missing catalog families, unmapped native
verifier failures, and premature roadmap closure.

## Explicitly deferred

- CIRCT conversion and legalization;
- backend-specific target diagnostics and target reparse;
- HDL emission and simulator-specific remediation;
- public diagnostic customization or suppression;
- waiver implementation beyond retaining an explicit waiver identity.
