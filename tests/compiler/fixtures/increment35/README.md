# Increment 35 fixtures

This directory records the implementation boundary for differential and integral operators.

The native fixtures cover:

- typed `ddt` dimension derivation and safe time-invariant-zero annotation;
- fixed-initialized and solver-selected `idt` state ownership;
- preservation of authored `idt` operations through constant simplification;
- Verilog-A `idt(...)` rendering;
- rejection of illegal contexts, mismatched initial conditions, invalid state identity, forged `ddt` simplification, and attempted `idt` folding.

The Scala construction and bridge witnesses verify the same contract before native compilation, including source correlation and deterministic serialization.

Increment 35 remains open until its implementation merge, exact post-merge validation, and separate evidence-closure change are complete.
