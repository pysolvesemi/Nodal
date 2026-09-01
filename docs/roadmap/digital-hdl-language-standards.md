# Digital HDL language standards

**Status:** Binding roadmap clarification  
**Decision date:** 2026-09-01  
**Applies to:** `Backend.Auto`, `Backend.Verilog`, and the future `Backend.SystemVerilog`  
**RNM/UDN reuse plan:** [`systemverilog-rnm-udn-digitized-modeling-plan.md`](systemverilog-rnm-udn-digitized-modeling-plan.md)

## Decision

Nodal uses two separate pure-digital HDL backends with explicit standards and capability profiles.

| Backend | Standard | Status | Purpose |
| --- | --- | --- | --- |
| `Backend.Verilog` | IEEE 1364-2005 Verilog | Required digital backend | Portable synthesizable RTL and the required open-source lint, simulation, synthesis, equivalence, and formal path |
| `Backend.SystemVerilog` | IEEE 1800-2023 SystemVerilog | Future, separately gated backend | Native SystemVerilog arrays, structs, interfaces/modports, enums, packages, assertions, RNM/UDN profiles, and other explicitly supported SystemVerilog constructs |

`Backend.Verilog` emits a conservative, synthesizable, tool-qualified subset of IEEE 1364-2005. It must not emit SystemVerilog syntax or depend on a SystemVerilog parser. Unsupported behavior is rejected or represented through an explicitly supported Verilog-2005 lowering; it is never silently upgraded to SystemVerilog.

`Backend.SystemVerilog` is a distinct backend identity and capability profile. It targets IEEE 1800-2023 and is implemented only after its separate research, design-gate, implementation, and parity requirements are approved. Adding it does not weaken, replace, or redefine `Backend.Verilog`.

## Automatic backend selection

For a digital-only design, `Backend.Auto` selects `Backend.Verilog`. It does not select `Backend.SystemVerilog` merely because a SystemVerilog-capable tool or RNM library is installed. Any future change to this default requires a separate versioned design decision.

The other existing automatic selections remain unchanged:

- analog-only design → `Backend.VerilogA`;
- mixed-signal design → `Backend.VerilogAMS`.

`Backend.Auto` never converts a continuous-time model into an RNM or fixed-point digitized model. Those are explicit transformations with separate capability and validation contracts.

## RNM, UDN, and external-model reuse

SystemVerilog RNM and UDN support is provided through explicit capability profiles of `Backend.SystemVerilog`; RNM is not a separate backend identity.

Nodal follows a reuse-first rule:

> **When an approved standard, vendor, project, or user-supplied RNM/UDN library already provides a model, type, nettype, resolver, electrical payload, connect module, or bridge, Nodal binds to that implementation instead of copying or reimplementing its algorithm.**

Nodal may generate imports, declarations, parameter mappings, thin wrappers, adapters, compile-order manifests, source maps, diagnostics, and validation harnesses. It must not copy, translate, lightly rename, or silently modify an existing RNM electrical-model or resolution algorithm.

Each external binding records the library symbols, version, hash, license/provenance, simulator profile, compile order, parameter and quantity mapping, direction and driver rules, resolution/update semantics, limitations, and evidence. The source may remain external and proprietary source is never bundled merely to support Nodal.

A Nodal-owned resolver may be emitted only when its semantics are explicitly Nodal-owned, no approved external implementation is selected, standalone generation is explicit, and the resolver is deterministic, capability-checked, source-mapped, and validated. It must not duplicate a known selected library algorithm.

The full binding and transformation policy is defined by [`systemverilog-rnm-udn-digitized-modeling-plan.md`](systemverilog-rnm-udn-digitized-modeling-plan.md).

## Fixed-point digitized modeling boundary

Fixed-point digitization is separate from external RNM-library binding.

For an explicit Nodal projection, Nodal owns model normalization, recurrence generation, sampling/rate contracts, range and binary-point analysis, rounding/overflow policy, quantization/error budgets, bit-accurate references, and RTL/model parity evidence. Existing fixed-point, DSP, or operator libraries may be reused when their exact width, scale, rounding, overflow, latency, and reset semantics are declared and verified.

Using such a library must not hide or change the frozen Nodal numeric transformation. Fixed-point projection is never selected by `Backend.Auto`.

## Port and aggregate policy

The same logical Nodal ABI is preserved across both digital backends.

- IEEE 1364-2005 Verilog uses deterministic flattened ports and flat packed carriers where classic Verilog cannot express the native aggregate shape.
- IEEE 1800-2023 SystemVerilog may use native unpacked multidimensional arrays of packed elements, packed layouts, structs, interfaces/modports, typed enums, real values, UDTs, UDNs, and nettypes where the selected capability profile permits them.
- Native and flattened representations must preserve the same dimensions, row-major index mapping, signedness, parameterization, protocol ownership, physical-quantity mapping, and source-map identity.

For example, a parameterized multidimensional Nodal value may lower as follows.

IEEE 1364-2005 Verilog:

```verilog
wire [(WIDTH * SIZE1 * SIZE2)-1:0] pixels;
```

IEEE 1800-2023 SystemVerilog:

```systemverilog
logic [WIDTH-1:0] pixels [SIZE1][SIZE2];
```

These are target layouts of one Nodal value; the target syntax does not change whether the value is structural `Vec` data or addressable `Mem` storage.

## Tool qualification

The required `Backend.Verilog` profile is qualified with the pinned Verilator, Icarus Verilog, Yosys, and SBY matrix.

The future `Backend.SystemVerilog` profile has its own declared simulator, synthesis, formal, feature, and version matrix. RNM/UDN profiles additionally record required external libraries, packages, compile options, scheduling/resolution behavior, vendor extensions, and conformance evidence. Successful parsing by one tool is not sufficient evidence of semantic support or portability.

## Roadmap allocation

- Increment 65 implements the required IEEE 1364-2005 `Backend.Verilog` profile.
- Increments 66-67 qualify that profile through open-source lint, simulation, synthesis, equivalence, and formal flows.
- Increment 68 owns target-neutral discrete-real/resolved-quantity semantics and external RNM/UDN binding contracts.
- Increment 69 owns typed logic/real/UDN bridges and standard/vendor connect behavior.
- Increments 87-88 own tool-adapter, packaging, trust, license, provenance, and external-library conformance support.
- Increment 99 performs the separate IEEE 1800-2023 `Backend.SystemVerilog`, RNM, UDN, and model-reuse research and design gate.
- Increments 100-106 retain the explicit Nodal-owned fixed-point digitization and validation flow while permitting exact bindings to existing arithmetic or model libraries.
- Increment 130 implements the native SystemVerilog backend, RNM/UDN imports and wrappers, and native/flat ABI and behavior parity after Increment 99 approval.
- AMS Verification Increments 8-10 reuse the same bound RNM models in generated UVM-MS and commercial simulator profiles without copying model algorithms.

SystemVerilog-AMS remains a separate research concern and is not implied by the IEEE 1800-2023 digital SystemVerilog backend.
