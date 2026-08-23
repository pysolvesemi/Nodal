# Nodal public API v0.2 to v0.3 migration

Public API v0.3 retains the complete v0.2 clock/reset surface and adds frozen core semantics,
connectivity, pipeline, and backend selection. Most v0.2 source compiles unchanged.

## Backend default

The only intentional default change is emission selection:

```scala
// v0.2 default behavior
val legacy = EmitOptions(backend = Backend.VerilogAMS)

// v0.3 default behavior
val automatic = EmitOptions() // Backend.Auto
```

Pin `Backend.VerilogAMS` when an existing application requires the prior default. `Backend.Auto`
classifies the design and chooses portable Verilog, Verilog-A, or Verilog-AMS without changing language
semantics.

## Directionless payloads and connectivity

Use `Struct` for storable records and protocol payloads. Use `Interface` plus a named `Role` for
connectivity. Do not store an Interface endpoint or place one inside `Valid`/`Stream`.

Direct connections use `connectExact`. Any width, field, protocol, latency, or domain conversion is an
explicit adapter `Module`; v0.3 does not freeze generic implicit or view-based adaptation.

## Arithmetic and shaped values

Mixed signed/unsigned arithmetic now requires `toSigned`, `toUnsigned`, or an explicit reinterpretation.
Narrowing uses `truncate`, `wrap`, `saturate`, or `resizeChecked`. Structural multidimensional data uses
`Vec`; addressable storage uses `Mem`.

## Pipeline

All dynamic values read by a `pipe` transform must be members of its input transaction. Parameters and
constants remain static. Published fixed-rate boundaries use exact or bounded latency.

## No clock/reset migration required

`ClockDomain`, `Reg`, `RegNext`, `when`, `Cdc`, `Rdc`, `ClockGate`, and `ClockMux` retain their v0.2
spellings and contracts. Ordinary `always(event)` remains removed.
