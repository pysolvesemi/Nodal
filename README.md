# Nodal

> **Status:** Pre-alpha. The project currently contains its architecture and incremental roadmap; no compiler or stable public API has been released.

Nodal is a modern Scala 3 hardware-construction language for analog and mixed-signal design and modeling. Its public language is intended to stay concise and close to Verilog-AMS terminology, while its compiler is built on MLIR and selected CIRCT infrastructure.

Nodal's first complete backend target is **Verilog-AMS 2023**. Models that use only the analog capability profile will also be able to emit **Verilog-A** for open-source compile and simulation flows.

## Mission

Nodal should let engineers construct reusable, parameterized analog and mixed-signal models in modern Scala without manually assembling Verilog-A or Verilog-AMS text. It should preserve the meaning of AMS constructs, produce readable deterministic HDL, and provide diagnostics at the original Scala source location.

The project is greenfield. It does not carry compatibility requirements for Scala 2, old JDKs, FIRRTL, Chisel, SpinalHDL, or an earlier Nodal implementation.

## Goals

- Provide a short public API that follows established Verilog-AMS names and semantics where Scala syntax permits.
- Use the latest stable Scala 3 line available at toolchain bootstrap, with no Scala 2 cross-build.
- Use MLIR as the authoritative compiler IR and define an out-of-tree Nodal AMS dialect.
- Reuse CIRCT hardware dialects where their digital semantics fit instead of rebuilding equivalent compiler infrastructure.
- Generate deterministic, readable, reviewable Verilog-AMS and analog-only Verilog-A.
- Detect invalid analog, digital, and cross-domain constructs before backend emission.
- Attach stable diagnostic codes and source locations to user-visible failures.
- Support hierarchy, parameters, generators, analysis-aware analog behavior, digital processes, and analog/digital interaction.
- Provide open-source validation and simulation for the analog-only profile where supported by OpenVAF and ngspice.
- Keep the mandatory language/compiler core independent from optional reusable model libraries.
- Preserve a target-neutral architecture so a future SystemVerilog-AMS backend can be evaluated without redesigning the public language.

## Non-goals

The initial Nodal core is not intended to:

- implement an analog or mixed-signal numerical simulator;
- replace SPICE, schematic capture, layout, extraction, PDK, or physical-design tools;
- replace Chisel or SpinalHDL as a general-purpose synthesizable digital RTL language;
- implement all SystemVerilog verification facilities or a separate UVM/UVM-MS methodology;
- use FIRRTL or `firtool` as the primary Nodal IR/compiler pipeline;
- preserve Scala 2, old-JDK, or legacy API compatibility;
- depend on simulator-specific extensions by default;
- bundle an official reusable device/model library before the core API and package contracts are stable;
- promise a SystemVerilog-AMS backend before the relevant standards and Nodal research gate are sufficiently mature.

## Planned abstraction levels

Nodal is centered on behavioral and structural AMS modeling. The following table defines the intended project boundary; it does not claim that these capabilities are implemented yet.

| Abstraction | Nodal direction |
| --- | --- |
| Analog behavioral | Primary core target: continuous equations, contributions, events, state, transfer functions, noise, and analysis-aware behavior. |
| Mixed-signal behavioral | Primary complete-language target: analog regions, digital processes, cross-domain conversion, connect semantics, and scheduling checks. |
| Structural hierarchy | Core target: modules, ports, disciplines, parameters, instances, connections, arrays, and Scala elaboration-time generation. |
| Digital behavioral / RTL-like constructs | Supported only to the extent needed for useful Verilog-AMS models; Nodal is not intended to duplicate a full digital RTL ecosystem. |
| Device and transistor-oriented models | May be represented through supported AMS constructs, external modules, and future optional libraries; PDK integration and physical implementation are outside the initial core. |
| System architecture / virtual prototypes | Possible through hierarchical behavioral models, but Nodal is not a replacement for SystemC-AMS or general software/system simulation frameworks. |
| Physical design | Out of scope. |

## Standards baseline

The project baseline was checked on **2026-08-20**.

### Verilog-AMS 2023

[Verilog-AMS 2023](https://www.accellera.org/downloads/standards/v-ams) is the primary complete backend target and the current released Accellera Verilog-AMS standard at this baseline. Nodal will model both analog and digital/mixed-signal semantics in its own target-neutral IR, then lower supported capability profiles to Verilog-AMS.

The standard document is a language reference, not source code for Nodal. Nodal will not copy restricted specification text into the repository. Conformance work will use independently written tests, permitted examples, public clarifications, and simulator evidence.

### Verilog-A analog-only profile

Verilog-A output is an analog-only capability profile of Nodal, not a separate frontend language. A model that contains only supported analog constructs may emit `.va`; a model containing digital or AMS-only constructs must be rejected explicitly by the Verilog-A backend rather than silently changed.

The planned open-source path is:

```text
Nodal Scala source
        ↓
Nodal/MLIR compiler pipeline
        ↓
generated Verilog-A
        ↓
OpenVAF validation / OSDI compilation
        ↓
ngspice simulation
```

Open-source tool support defines a validation profile, not the complete semantics of Nodal or Verilog-AMS.

### SystemVerilog and CIRCT reuse

IEEE SystemVerilog is relevant to Nodal's digital semantics and to the CIRCT dialects that may be reused. Nodal will reuse CIRCT `hw`, `comb`, `seq`, `sv`, and related infrastructure only where the semantics match. It will not force analog equations, disciplines, branches, contributions, or continuous-time events into FIRRTL or digital-only dialects.

### Future SystemVerilog-AMS

At the project baseline, Accellera's [SystemVerilog-AMS Working Group](https://www.accellera.org/activities/working-groups/systemverilog-ams) is working on alignment of Verilog-AMS with IEEE 1800 SystemVerilog or inclusion of AMS capabilities in a new SystemVerilog-AMS standard.

SystemVerilog-AMS is therefore a **future backend research target**, not an initial language or toolchain dependency. Increment 78 will reassess the then-current standard, map it to Nodal IR, and decide whether implementation is justified. No speculative target syntax should leak into the stable Nodal API before that gate.

### UVM-MS

[UVM-MS 1.0](https://www.accellera.org/downloads/standards/uvm-ms) is a mixed-signal verification methodology, not Nodal's design-language backend. Future Nodal work may generate interoperability metadata or wrappers, but Nodal will not implement a competing verification methodology inside the core language.

## Compiler direction

The intended compiler flow is:

```text
Nodal source embedded in Scala 3
               ↓
official Scala 3 compiler and JVM execution
               ↓
Nodal frontend elaboration
               ↓
versioned Scala-to-MLIR bridge
               ↓
`nodalc`
               ↓
Nodal MLIR dialect + selected CIRCT dialects
               ↓
verification, analysis and lowering passes
               ↓
Verilog-A / Verilog-AMS backends
               ↓
external compilers and simulators
```

Key boundaries:

- The official Scala 3 compiler handles Scala parsing, typing, metaprogramming, and JVM code generation.
- The Scala frontend handles Nodal construction, hierarchy, naming, and elaboration.
- MLIR is the authoritative compiler representation for semantic passes and HDL emission.
- Nodal defines analog and mixed-signal operations that CIRCT does not currently provide.
- CIRCT is reused selectively for digital hardware constructs; Chisel and FIRRTL are not Nodal dependencies.
- External tools perform numerical simulation. Nodal generates models, testbench inputs, commands, and assertions around those tools.

Exact tool versions and the Scala/native process boundary will be pinned by later bootstrap and architecture-decision increments.

## Public API direction

The public API is intentionally **not frozen yet**. Increments 10–12 will prototype, approve, and enforce the v0.1 API contract before substantial language implementation.

The API gate will favor concise AMS-native terms such as:

```text
Module
Param
Electrical
Real
Integer
Bool
Bits
UInt
analog
initial
always
nature
discipline
V
I
ddt
idt
cross
timer
transition
<+
```

Normal user code should not require branding-heavy names such as `NodalComponent`. The frontend must also avoid exposing backend-specific names or compiler internals as ordinary model APIs.

Until the API design gate is approved, examples are exploratory and are not compatibility commitments.

## Core and reusable libraries

Nodal separates mandatory language/compiler infrastructure from optional reusable design content:

```text
Nodal/
├── core/        # Mandatory language, compiler, backends and simulation infrastructure
└── libraries/   # Future optional reusable packages
```

The dependency direction is strictly one-way:

```text
future libraries ──► published Nodal core APIs
Nodal core       ──X──► future libraries
```

### Core

The top-level `core/` path owns:

- the public language API;
- elaboration, hierarchy, naming, and diagnostics;
- the Scala-to-MLIR bridge;
- the Nodal MLIR dialect and compiler passes;
- Verilog-A and Verilog-AMS backends;
- command-line and simulation APIs;
- simulator adapters and core conformance/regression infrastructure.

A user project must be able to use Nodal core without any Nodal library checkout or artifact.

### Future libraries

The top-level `libraries/` path is reserved for optional packages such as:

- reusable behavioral and device models;
- interface, discipline, and connect packages;
- reusable analog and mixed-signal blocks;
- verification helpers and domain-specific model collections.

No official reusable library is planned in the initial core roadmap. The repository will not add empty library directories merely as placeholders.

When libraries are introduced, each one must:

- depend only on published core APIs and approved extension contracts;
- have no privileged access to frontend, MLIR, compiler, or native implementation internals;
- own its tests, documentation, artifact identity, semantic version, license metadata, and declared core compatibility range;
- remain independently buildable and publishable;
- be movable to a separate repository without requiring a Nodal core redesign.

Language features required to express or compile Verilog-A/Verilog-AMS belong in core. Optional reusable model content belongs in libraries.

## Terminology

| Term | Meaning in Nodal |
| --- | --- |
| Nodal | The Scala-embedded AMS construction language and its toolchain. |
| Module | A hierarchical Nodal design/model unit; the intended short public name is subject to the API gate. |
| Core | Mandatory language, elaboration, compiler, backend, diagnostics, and simulation infrastructure. |
| Library | An optional reusable package authored only against published core contracts. |
| Frontend | Scala-side construction, hierarchy, naming, source-location capture, and elaboration. |
| Nodal IR | The target-neutral semantic representation of Nodal programs; authoritative compiler form will be the Nodal MLIR dialect plus selected compatible dialects. |
| Nodal dialect | The out-of-tree MLIR dialect for analog and mixed-signal concepts not supplied by existing CIRCT dialects. |
| `nodalc` | The planned native compiler driver for parsing, verifying, transforming, and translating Nodal MLIR. |
| Backend | A translation from verified Nodal IR to an output language/profile. |
| Capability profile | A declared subset of Nodal features supported by a backend or simulator flow. |
| Verilog-A profile | The analog-only backend profile. |
| Verilog-AMS profile | The first complete analog-and-digital mixed-signal backend target. |
| SystemVerilog-AMS | A future standards-alignment/backend research target, not an initial dependency. |
| External module | A model written outside Nodal and referenced through a declared interoperability boundary. |

## Development model

Development follows the checked roadmap in [`docs/roadmap/nodal-development-todo.md`](docs/roadmap/nodal-development-todo.md).

- Work proceeds one increment at a time on a dedicated branch.
- An increment is complete only with implementation or documentation, tests where applicable, reproducible evidence, and its roadmap checkbox updated in the same change.
- New work discovered outside an increment must be recorded as a new increment or an approved scope change.
- Public API and semantic changes require versioned design gates.
- Backend capability gaps must produce explicit diagnostics rather than silent fallback.

The current milestone is **M0 — Foundation**. After this charter, the next roadmap item is **Increment 2 — Architecture decision records**.

## Roadmap

See the full incremental plan:

- [`docs/roadmap/nodal-development-todo.md`](docs/roadmap/nodal-development-todo.md)

## Authoritative public references

- [Accellera Verilog-AMS standards](https://www.accellera.org/downloads/standards/v-ams)
- [Accellera SystemVerilog-AMS Working Group](https://www.accellera.org/activities/working-groups/systemverilog-ams)
- [Accellera UVM-MS standard](https://www.accellera.org/downloads/standards/uvm-ms)
- [Scala releases](https://www.scala-lang.org/download/)
- [MLIR dialect definition guide](https://mlir.llvm.org/docs/DefiningDialects/)
- [CIRCT dialect documentation](https://circt.llvm.org/docs/Dialects/)
