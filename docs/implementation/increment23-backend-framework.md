# Increment 23 — Backend framework and capability profiles

Increment 23 adds the first native output-backend framework while deliberately
keeping language lowering inert.

## Registered translations

`nodal-translate` exposes:

```text
--nodal-to-verilog-a
--nodal-to-verilog-ams
```

Both translations parse the same authoritative Nodal MLIR. The selected
translation resolves an immutable built-in profile rather than inferring policy
from installed tools.

## Profile contract

`verilog-a` accepts analog designs, owns the `scalar-or-flat` shaped-value
layout, uses `safe-inline` expression materialization, and requires semantic
names. `verilog-ams` accepts analog or mixed-signal designs, owns the
`flat-packed` layout, uses `readable` materialization, and also requires
semantic names.

`nodal.backend.check_profile` may be `fast`, `default`, or `release`. Optional
module attributes that repeat profile, layout, materialization, or naming must
match the selected profile exactly.

The machine-readable contract is
`core/compiler/backend-profiles-v0.1.json`.

## Transaction

`emitBackend` clones the module, resolves configuration, runs the mandatory
semantic pipeline, checks implemented capability, orders definitions by
semantic symbol, renders privately, invokes target verification and structural
reparse hooks, and only then publishes the complete candidate.

The caller's output stream remains unchanged on configuration, semantic,
capability, naming, target-verification, or target-reparse failure.

## Initial output boundary

The framework emits deterministic standard includes and empty module shells.
It rejects every nested Nodal operation with
`NODAL-BACKEND-CAPABILITY-001`. This prevents a backend skeleton from being
mistaken for implementation of analog expressions or contributions, which begin
in Increment 24.

## Successor compatibility

Historical checkers determine successor completion from the successor evidence
manifest and roadmap checkbox together. A global roadmap revision may advance
for planning-only changes and therefore never proves that a compiler increment
has completed.

## Target parser edge contracts

Portable module identifiers exclude Verilog-family and AMS reserved words. Target
verification counts complete module declaration and `endmodule` lines rather
than substrings, so legal names such as `myendmoduleBlock` remain valid. Every
profile-owned module attribute is type-checked before defaults are considered;
a present non-string attribute is malformed configuration, not an absent value.

## Validation

The native unit test exercises the direct API and injected rejecting hooks.
CLI tests compare byte-exact Verilog-A and Verilog-AMS goldens, prove repeated
output is identical, and verify stable failure codes. The permanent Increment 23
workflow is read-only and runs the complete native and Scala core regression.
