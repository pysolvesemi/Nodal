# NodalPublicApiCandidates-DG v0.1

**Status:** Approved  
**Scope:** public-api  
**Decision:** Authorize compile-only candidate prototypes for Increment 10  
**Approved by:** Repository owner instruction to implement Increment 10 on 2026-08-21

## Decision boundary

This gate authorizes a deliberately non-functional public API experiment under `core/scala/api`. It is not an API freeze, compatibility promise, semantic specification, or approval to build substantial frontend behavior behind these names.

Increment 11 must review the evidence from these prototypes, resolve the open choices, publish `NodalPublicApi-DG-v0.1.md`, and freeze only the accepted subset. Any candidate may be renamed, reshaped, rejected, or removed before that gate.

## Candidate import and construction style

Representative user sources use one import:

```scala
import nodal.*
```

Modules use the short unprefixed base name and direct declarations:

```scala
final class Resistor extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(1.0.kOhm)

  analog:
    V(p, n) <+ resistance * I(p, n)
```

The compile experiment covers these candidate names and shapes:

- `Module`, `Param`, `Expr`, `Real`, `Integer`, `Bool`, `Bits`, and `UInt`;
- `Nature`, `Discipline`, `Electrical`, `nature`, and `discipline`;
- `input`, `output`, `inout`, `node`, `wire`, `variable`, `instance`, and `connect`;
- `analog`, `initial`, `always`, `on`, `V`, `I`, `ddt`, `idt`, `cross`, `timer`, and `transition`;
- contribution operator `<+`, assignment operator `:=`, typed instance selectors, and selector-based parameter overrides.

## Required prototype matrix

Increment 10 must compile representative resistor, capacitor/RC filter, comparator, ADC, DAC, hierarchy, parameter override, analog event, mixed-signal, and external reusable-module examples. The external reusable module must compile in a separate Mill module that depends only on `core.scala.api`.

The candidate implementation must remain inert: no elaboration graph, source capture, naming, diagnostics, MLIR construction, backend invocation, or simulation behavior belongs in this increment.

## Accepted candidate choices for evaluation

The following choices are approved for compilation and comparison, not frozen:

1. `Module` instead of branded names such as `NodalComponent` or `NodalModule`.
2. `Param` instead of the longer `Parameter` in ordinary model code.
3. Indentation blocks such as `analog:` instead of callback builders or explicit block objects.
4. Verilog-AMS contribution syntax `<+` instead of a primary `contribute(...)` API.
5. `V(...)` and `I(...)` instead of verbose potential/flow accessor objects.
6. Direct `input(...)`, `output(...)`, and `inout(...)` declarations instead of mandatory port-wrapper bundles.
7. Typed instance selectors and `.param(_.field, value)` instead of string-keyed parameter maps.
8. `on(cross(...))` and `on(timer(...))` instead of a generalized event-builder hierarchy in normal source.

## Rejected prototype alternatives

The compile prototype must not use:

- a mandatory `Nodal` prefix on ordinary language constructs;
- frontend or compiler internal imports in user or external reusable-module sources;
- backend-specific Verilog-A or Verilog-AMS objects in the source API;
- string-keyed node connections or parameter overrides;
- a reusable package under `libraries/` before the public core contract is frozen;
- operational semantics hidden behind placeholders that could be mistaken for implemented behavior.

## Compatibility and follow-up

No source compatibility is promised for the Increment 10 candidates. Increment 11 is the first point where a versioned compatibility policy may be approved. Increment 12 will convert the frozen subset into positive and negative compile contracts with stable diagnostics.

This gate expires as the controlling public-API decision when Increment 11's freeze gate is approved.
