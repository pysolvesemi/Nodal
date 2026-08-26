# Increment 22 — Cross-layer diagnostic mapping

Increment 22 maps private construction, bridge, parser, verifier, pass, and tool
failures into one stable diagnostic contract without changing public API v0.3.

## Native mapping

`NodalDiagnostics` owns `emitMappedFailure`. It preserves the originating stable
code and appends deterministic context collected from the failing operation and
its ancestors:

- `metadata.semantic_path`;
- explicit or symbol-derived hierarchy path;
- explicit or path-derived multidimensional index path; and
- MLIR file/line/column plus bridge-retained end line/column.

Inventory-only checks resolve their semantic path back to an operation or the
Increment 20 `nodal.bridge.source_map` inventory. Missing context is omitted and
never guessed.

The Increment 21 pass pipeline delegates every existing `NODAL-VERIFY-*` error
to this mapper and runs the private `nodal-verify-cross-layer-diagnostics` pass
before target-capability validation.

## Stable diagnostic families

The new pass checks Interface storage, role compatibility, monitor behavior,
role inversion, member identity, and emitted-layout collisions. It checks
ordinary multiple drivers, open-drain/open-source intent, resolution support,
and hierarchical inout pass-through. It also checks conservative-discipline and
access compatibility and rejects implicit analog/digital or
conservative/signal-flow conversion.

The complete machine-readable inventory is
`core/compiler/diagnostics-v0.1.json`.

## Scala process mapping

`NativeDiagnosticMapper` preserves a native `NODAL-*` code and parses mapped
semantic, hierarchy, index, and source-range suffixes. Uncoded native failures
receive parser, verifier, pass, backend, or external-tool fallback families.
Temporary bridge staging paths are normalized before fallback diagnostics are
published. The Increment 20 generic nonzero-process contract remains unchanged
for an unclassified external process failure.

## Predecessor compatibility

The Increment 21 structural checker remains binding at roadmap revision 1.25,
where Increment 22 must be unchecked. At revision 1.26 and later it requires the
completed Increment 22 state instead, so historical validation remains strict
without rejecting valid successor revisions.

## Deferred boundary

CIRCT conversion, backend legalization, target reparse, HDL generation, and
simulator-specific remediation remain outside Increment 22.
