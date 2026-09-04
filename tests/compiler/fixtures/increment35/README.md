# Increment 35 fixtures

This directory records the implementation boundary for differential and integral operators.

The native fixtures cover:

- typed `ddt` dimension derivation and safe time-invariant-zero annotation;
- fixed-initialized and solver-selected `idt` state ownership;
- preservation of authored `idt` operations through constant simplification;
- Verilog-A `ddt(...)` and `idt(...)` rendering;
- owner-qualified and module-unique operator identity;
- exact analysis applicability and legacy `ddt` diagnostic compatibility;
- rejection of illegal contexts, missing contracts, owner mismatch, invalid dimensions, invalid analyses, mismatched initial conditions, invalid state identity, duplicate operator identity, forged `ddt` simplification, and attempted `idt` folding.

The Scala construction and bridge witnesses verify the same contract before native compilation. They cover legacy, equation, and contribution contexts, typed non-zero initialization, exact analysis inventory, source correlation, owner qualification, deterministic unique state identity, and deterministic serialization.

Increment 35 remains open until its implementation merge, exact post-merge validation, and separate evidence-closure change are complete.
