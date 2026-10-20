# Executable Gray/pulse target witnesses

`Increment57GrayPulseSource` public fixtures are captured by
`nodal.internal.testkit.Increment57GrayPulseReplay`. The replay writes actual
Scala-to-bridge MLIR, not replacement test designs. `run_target.py` lowers those
files with nodalc, exports SystemVerilog with firtool, and executes Icarus checks.
Generated files and per-command logs must stay under ignored `.work`.

The harness accepts `--input`, `--out`, `--nodalc`, `--firtool`, `--iverilog` and
`--vvp` paths. `--include-aggregate` additionally exercises fixed Vec/Struct
payloads once that target path is available. Source/backend implementation
checks separately cover bounded symbolic type admission and malformed IR.

Positive witnesses exercise both clock edges and reset polarities, resetless
first-sample monitoring, stage boundaries, exact scalar storage and an Enum with
no zero encoding. Gray payload startup must remain unspecified, not initialized
by invented reset values. Pulse reconstruction must produce one destination
cycle per legal event, including consecutive events after returned acknowledgement.

Negative simulations must terminate unsuccessfully with the expected emitted
Nodal assertion marker, not merely compile an assertion or fail for an unrelated
reason. They exercise multi-bit Gray transitions, consecutive high source pulse
samples, a pulse while acknowledgement is outstanding, early simultaneous clock
edges and use before a known common reset epoch. Testbench fallback failures do
not count as successful assumption checking.

Physical Gray skew/stability and reset assertion duration remain environment
obligations. These digital simulations do not prove metastability behavior.
This is focused local prototype validation, not CI or exhaustive qualification.
