# Digital HDL language standards

**Status:** Binding roadmap clarification
**Decision date:** 2026-09-01
**Applies to:** `Backend.Auto`, `Backend.Verilog`, and the future `Backend.SystemVerilog`

## Decision

Nodal uses two separate pure-digital HDL backends with explicit standards and capability profiles.

| Backend | Standard | Status | Purpose |
| --- | --- | --- | --- |
| `Backend.Verilog` | IEEE 1364-2005 Verilog | Required digital backend | Portable synthesizable RTL and the required open-source lint, simulation, synthesis, equivalence, and formal path |
| `Backend.SystemVerilog` | IEEE 1800-2023 SystemVerilog | Future, separately gated backend | Native SystemVerilog arrays, structs, interfaces/modports, enums, packages, assertions, and other explicitly supported SystemVerilog constructs |

`Backend.Verilog` emits a conservative, synthesizable, tool-qualified subset of IEEE 1364-2005. It must not emit SystemVerilog syntax or depend on a SystemVerilog parser. Unsupported behavior is rejected or represented through an explicitly supported Verilog-2005 lowering; it is never silently upgraded to SystemVerilog.

`Backend.SystemVerilog` is a distinct backend identity and capability profile. It targets IEEE 1800-2023 and is implemented only after its separate research, design-gate, implementation, and parity requirements are approved. Adding it does not weaken, replace, or redefine `Backend.Verilog`.

## Automatic backend selection

For a digital-only design, `Backend.Auto` selects `Backend.Verilog`. It does not select `Backend.SystemVerilog` merely because a SystemVerilog-capable tool is installed. Any future change to this default requires a separate versioned design decision.

The other existing automatic selections remain unchanged:

- analog-only design → `Backend.VerilogA`;
- mixed-signal design → `Backend.VerilogAMS`.

## Port and aggregate policy

The same logical Nodal ABI is preserved across both digital backends.

- IEEE 1364-2005 Verilog uses deterministic flattened ports and flat packed carriers where classic Verilog cannot express the native aggregate shape.
- IEEE 1800-2023 SystemVerilog may use native unpacked multidimensional arrays of packed elements, packed layouts, structs, interfaces/modports, and typed enums where the selected capability profile permits them.
- Native and flattened representations must preserve the same dimensions, row-major index mapping, signedness, parameterization, protocol ownership, and source-map identity.

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

The required `Backend.Verilog` profile is qualified with the pinned Verilator, Icarus Verilog, Yosys, and SBY matrix. The future `Backend.SystemVerilog` profile has its own declared simulator, synthesis, formal, feature, and version matrix and cannot claim support based only on successful parsing by one tool.

## Roadmap allocation

- Increment 65 implements the required IEEE 1364-2005 `Backend.Verilog` profile.
- Increments 66-67 qualify that profile through open-source lint, simulation, synthesis, equivalence, and formal flows.
- Increment 99 performs the separate IEEE 1800-2023 `Backend.SystemVerilog` research and design gate.
- Increment 130 implements the native SystemVerilog backend and proves native/flat ABI and behavior parity after Increment 99 approval.

SystemVerilog-AMS remains a separate research concern and is not implied by the IEEE 1800-2023 digital SystemVerilog backend.
