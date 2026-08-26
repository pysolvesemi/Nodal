# Generated testbench projections feasibility v0.1

- **Status:** Accepted roadmap feasibility result
- **Date:** 2026-08-26
- **Architecture decision:** [ADR 0025](../architecture/0025-generated-verilog-family-testbench-projections.md)
- **Roadmaps affected:** Foundation, Digital Verification, Analog/Mixed-Signal Verification

## Requested execution model

The requested single-source model is retained with one qualification correction:

```text
Nodal HVL source
  |-- native Nodal verification runtime -> open-source simulator/solver adapters
  |-- generated portable Verilog testbench -> open-source digital simulators
  |-- generated digital UVM testbench -> commercial simulators first
  |-- generated Verilog-AMS testbench -> capable AMS simulators;
  |                                      open-source only after subset qualification
  `-- generated UVM-MS testbench -> commercial mixed-signal simulators
```

The Nodal HVL source and Verification Semantic IR remain canonical. Generated testbenches are exchangeable artifacts, not separately authored sources.

## Feasibility matrix

| Projection | Generation feasibility | Intended initial execution | Result |
| --- | --- | --- | --- |
| Native Nodal HVL | Already reserved by ADR 0023 | Verilator/Icarus and available open mixed-signal adapters | Feasible; remains primary open workflow |
| Portable Verilog testbench | High for a deliberately bounded IEEE 1364-2005 procedural subset | Icarus and Verilator | Feasible and suitable for roadmap implementation |
| Digital UVM | High from Verification IR through Verification SystemVerilog IR | VCS/Questa/Xcelium-family profiles first | Feasible; future open UVM profile remains possible but separately qualified |
| Verilog-AMS testbench | High as standards-oriented source generation | Capable commercial AMS tools; open candidates only after conformance | Language/output feasible; general open-source execution not yet qualified |
| UVM-MS | High as a standards-oriented projection using UVM-MS 1.0 concepts | Qualified commercial mixed-signal profiles | Feasible with vendor adapter work |

## Why portable Verilog testbench generation is feasible

A plain Verilog testbench can instantiate generated Verilog RTL and express clocks, resets, timed stimulus, tasks/functions, file-driven replay values, monitors, procedural checks, bounded scoreboards, timeout/termination, and waveform requests. Icarus provides an event-driven Verilog simulator, and Verilator supports most Verilog-2001/2005 features with an independent compiled execution model.

The limitation is language expressiveness, not architectural feasibility. Plain Verilog cannot faithfully represent every HVL feature such as classes, UVM phasing, covergroups, general constrained randomization, dynamic containers, or every temporal property. Nodal therefore needs a declared portable subset, recorded value-stream lowering where semantics remain exact, and a capability error for everything else.

## Why Verilog-AMS is a valid testbench language

Verilog-AMS is a unified analog/mixed-signal language derived from Verilog. The standard permits analog and digital signals in one module and permits `initial`, `always`, and `analog` procedural blocks in the same module. It supports top-level mixed-signal structural and behavioral modeling, so a generated Verilog-AMS testbench can legitimately bind digital Verilog, Verilog-A, and Verilog-AMS design units when the selected simulator supports that composition.

The generated testbench can therefore represent disciplines/natures, conservative nodes, analog sources and contributions, digital control, crossings/events, bridges/connect rules, analysis/environment setup, measurements, and termination. It must not convert conservative analog behavior into `real`/`wreal` or discretized RNM merely to fit a weaker tool.

## Open-source Verilog-AMS boundary

Current open-source evidence does not justify a blanket “generated Verilog-AMS testbenches run with open-source tools” promise:

- Verilator documents only a very small Verilog-AMS subset with near-equivalent digital/SystemVerilog constructs and `wreal`; it is not a general continuous-time AMS solver.
- Icarus exposes a Verilog-AMS language flag for supported features, but that flag is not a full-language or solver conformance claim.
- OpenVAF compiles Verilog-A models to OSDI-compatible shared objects; it is a valuable model path, not a complete mixed-signal testbench runtime.
- Gnucap and its Verilog model generator describe partial Verilog-AMS implementation and are promising candidates for bounded qualification rather than proof of full support.

Accordingly, the roadmap separates **Verilog-AMS testbench generation** from **open-source Verilog-AMS subset conformance**. The generated artifact remains useful with capable commercial tools even when no production open profile is enabled. An open profile becomes supported only after pinned compile, elaboration, and simulation fixtures pass for every advertised construct and analysis.

## UVM and UVM-MS boundary

Digital UVM generation remains standards-oriented and commercial-profile-first. Verilator has an active UVM adaptation repository, but it explicitly describes support as still in development; the architecture therefore leaves open UVM possible without making it an initial release dependency.

UVM-MS 1.0 provides a standardized mixed-signal extension to UVM with class/structural interaction and mixed-signal bridges. It is an appropriate generated commercial-tool projection, not the canonical runtime and not a substitute for native open-source simulation.

## Required architecture reservation

Existing Foundation Increments 147-149 reserve Verification Semantic IR, native execution, Verification SystemVerilog IR, and UVM/UVM-MS. They do not fully reserve self-contained procedural Verilog and Verilog-AMS testbench artifacts.

Only one additional minimal Foundation item is required:

- **Foundation Increment 150** freezes projection identities, a projection seam, one common DUT/testbench ABI and artifact manifest, capability negotiation, deterministic names/source maps/replay, normalized results, and parity identities.

Foundation 150 explicitly does not implement renderers, runners, UVM libraries, analog solvers, or vendor adapters. All implementation stays in the dependent verification tracks.

## Roadmap result

The Digital Verification track gains separate increments for portable Verilog generation and open-source simulator qualification before UVM generation. The AMS Verification track gains separate increments for Verilog-AMS testbench generation and open-source subset conformance before UVM-MS generation. Both tracks then compare all supported projections, qualify reusable VIP across them, and close with capability/release matrices.

This structure satisfies the requested future flexibility without increasing the current Foundation implementation burden beyond one architecture-only increment and without making an unsupported open-source AMS claim.

## Primary references

- [Accellera Verilog-AMS 2023](https://www.accellera.org/downloads/standards/v-ams)
- [Accellera: About Verilog-AMS](https://www.accellera.org/activities/working-groups/systemverilog-ams/verilog-ams/about)
- [Accellera UVM](https://www.accellera.org/downloads/standards/uvm)
- [Accellera UVM-MS 1.0](https://www.accellera.org/downloads/standards/uvm-ms)
- [Verilator input-language support](https://verilator.org/guide/latest/languages.html)
- [Verilator UVM repository](https://github.com/verilator/uvm)
- [Icarus Verilog command-line language profiles](https://steveicarus.github.io/iverilog/usage/command_line_flags.html)
- [OpenVAF](https://github.com/pascalkuthe/OpenVAF)
- [Gnucap](https://github.com/gnucap/gnucap)
- [Gnucap modelgen-Verilog](https://github.com/gnucap/gnucap-modelgen-verilog)
