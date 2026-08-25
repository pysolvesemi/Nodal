# Increment 20 — Scala-to-MLIR bridge

Increment 20 connects the deterministic construction snapshot to the canonical
private Nodal MLIR model without changing public API v0.3.

## Delivered boundary

`ScalaToMlirBridge` serializes one closed construction transaction into the
versioned `nodal.scala-to-mlir` schema. Stable semantic paths, source spans,
module/domain/port/parameter/instance records, logical Interface ABI entries,
resolved nets, conservative terminals, topology, names, origins, generated
names, and source-map spans are emitted in deterministic order.

Only exact Increment 19 representations become dialect operations. Complete
inventories remain attached to the builtin module so later native passes can
validate information whose executable lowering is intentionally deferred.
Missing clock/reset metadata, unsupported types, invalid widths, and unsupported
resolved modes fail before a native process is launched.

`NativeCompilerClient` accepts an absolute executable, ordered arguments,
working directory, environment additions, and timeout. It stages one
`input.mlir` as the final argument, closes standard input, captures output
concurrently, distinguishes launch/nonzero/timeout/cleanup failures, removes
temporary input on every path, and never promotes partial output after failure.

## Verification

Scala tests prove repeatability, insertion-order independence, source
locations, bridge inventories, pre-launch rejection, argv-safe success,
non-zero diagnostics, timeout cleanup, recovery, and optional locked-`nodalc`
round-trip. The dedicated workflow builds the native compiler and runs the
round-trip with `NODAL_NODALC` configured.
