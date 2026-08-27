# Nodal RC end-to-end vertical slice design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-vertical-slice
**Scope:** public-api
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 25 activates the already-frozen Scala analog surface only for one
strict RC subset. A Scala module containing two electrical terminals, finite
real parameters, `analog`, `V(p, n)`, `I(p, n)`, real arithmetic, `ddt`, and one
flow contribution must pass through the construction kernel, source-correlated
Nodal MLIR, the mandatory native verification transaction, and deterministic
Verilog-A emission.

The `public-api` scope approval covers implementation changes beneath the
already frozen v0.3 Scala source files. It does not add, remove, rename, or
retype a public symbol; the external API contract and machine-readable v0.3
surface remain unchanged.

## Binding boundary

- Public API spellings and types remain exactly v0.3.
- Construction records operation identity and ordered operands; the bridge never
  reconstructs an equation from binder or parameter names.
- The initial slice requires two-terminal potential/flow access.
- Parameter units are retained as metadata while MLIR arithmetic remains typed
  f64 under the Increment 24 boundary.
- Verilog-A emission is transactional and exact-golden checked.
- The generated module, port, parameter, branch, and expression order is
  deterministic.
- Unsupported analog operators and branch forms fail before target publication.

## Explicitly deferred

General units/dimensions, single-ended ground semantics, `idt`, analog control,
events, analyses, noise, complete hierarchy, Verilog-AMS mixed-signal bodies,
OpenVAF validation, and public compiler-command orchestration remain assigned
to later increments. No unsupported construct is approximated.
