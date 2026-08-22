# ADR 0020: Use a canonical register model with transport adapters

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** Reusable control/status register maps, register-block instances, canonical Register IR, Scala/SystemRDL/YAML/IP-XACT frontends, APB/AXI4-Lite/custom transports, generated Verilog constants, software artifacts, ABI compatibility, CDC/RDC, and verification

## Context

Nodal requires a scalable register facility comparable in convenience to mature software-based HDLs while avoiding a bus-first architecture. A reusable register definition must not be rewritten when an IP moves between APB, AXI4-Lite, a debug fabric, or a user-defined internal control bus. Register behavior must also remain consistent across RTL, firmware headers, UVM register models, documentation, integration metadata, and verification.

A conventional bus-slave factory often creates registers directly from a concrete bus interface. That is convenient for a small peripheral, but it couples the programmer-visible ABI, physical storage, transport timing, documentation, and generated artifacts. It also makes it harder to define exact semantics for software/hardware collisions, byte enables, multiword access, multiple frontends, and long-lived compatibility.

Register descriptions are authored in several forms in industry. SystemRDL 2.0 is a dedicated standards-based register-description language. IEEE 1685-2022 IP-XACT contains memory-map/register metadata as part of broader IP packaging and integration. Many teams also use YAML, JSON, CSV, spreadsheets, or native HDL/Scala/Python DSLs. Nodal must interoperate without creating multiple semantic authorities.

Generated Verilog needs readable address symbols, but individual register offsets and field positions are normally part of the software-visible ABI. Making every address an overridable Verilog `parameter` would permit RTL and software artifacts to disagree.

## Decision

Nodal adopts a **register-definition-first, transport-neutral architecture**.

The binding rule is:

> **Define the register ABI once, normalize every authoring source into one canonical Register IR, bind hardware explicitly, and adapt transports without changing register semantics.**

The architecture separates five concerns:

```text
Authoring source
  -> canonical Register IR
  -> physical RegisterBlock binding
  -> canonical committed-access endpoint
  -> APB / AXI4-Lite / custom transport adapter
  -> RTL and non-RTL artifacts
```

No authoring frontend, bus adapter, or generated artifact becomes a second semantic authority.

## RegisterMap and RegisterBlock

`RegisterMap` is an immutable programmer-visible specification. It owns stable identities, offsets, fields, reset values, software access, hardware-update capability, collision policy, reserved behavior, documentation, source locations, hierarchy, arrays, windows, aliases, ABI version, and compatibility metadata. It captures no live hardware signal and instantiates no transport.

`RegisterBlock` is one physical realization of a `RegisterMap`. Each instance:

- owns independent state;
- belongs to exactly one lexical clock/reset domain;
- binds fields to local hardware behavior;
- exposes one canonical committed-access endpoint;
- may attach one transport directly;
- requires an explicit arbiter/router for multiple access paths.

The same `RegisterMap` may instantiate any number of independent `RegisterBlock`s.

## Orthogonal field semantics

Software access, hardware behavior, and simultaneous-update priority are independent axes. Nodal must not encode every combination in one unbounded access-mode enumeration.

Initial software behaviors include read-only, read/write, write-only, write-one/zero clear, set or toggle, read-clear/read-set, write-once, and reserved. Hardware behaviors include input/status observation, direct update, set, clear, increment, decrement, pulse, and command/snapshot participation. A separate collision policy defines hardware-wins, software-wins, set-dominates-clear, clear-dominates-set, or error-on-collision.

Reset priority, software access, hardware updates, side effects, and collision ordering must be frozen precisely before implementation.

## Canonical committed-access endpoint

Bus protocols lower into one bus-neutral request/response contract containing at least:

- operation and relative byte address;
- write data and byte enables;
- protection/security attributes where available;
- request identity required for deterministic response pairing;
- response data and normalized success/error status.

A side effect occurs exactly once when a canonical request commits. Address observation, APB setup, AXI address acceptance, or partial channel progress must not independently trigger read-clear, write-one-clear, command, counter, or pulse behavior.

The canonical endpoint is not a public promise that every adapter has identical throughput. Adapter capability metadata declares ordering, outstanding depth, error signaling, byte strobes, protection attributes, and backpressure behavior.

## Scala 3 transport abstraction

Transport binding uses a Scala 3 type-class/extension architecture conceptually equivalent to `RegisterTransport[B]` with `given`/`using`. The exact public spelling is deferred to a versioned API design gate.

Built-in and external adapters must consume only the public transport and canonical-access contracts. Path-dependent or opaque field/map identities should prevent binding a field from one map to an unrelated block. Scala reflection order, declaration discovery order, and JVM object identity must never define the ABI.

## Authoring sources

A project selects exactly one authoritative source for each register map. Supported frontends normalize into the same canonical Register IR.

### Native Scala RegisterMap DSL

The Scala DSL is the preferred Nodal-native authoring experience. It provides static types, reusable definitions, parameter integration, source locations, and compile-positive/negative contracts. Published maps use explicit offsets and bit ranges by default. Automatic allocation is limited to provisional/private use or is locked immediately into a canonical ABI manifest.

### SystemRDL 2.0

SystemRDL 2.0 is the primary standards-based register interchange and receives first-class import/export support. Nodal must:

- import the supported semantic subset into canonical Register IR;
- export deterministic SystemRDL where semantics are representable;
- preserve source locations;
- diagnose unsupported or lossy mappings;
- never silently discard Nodal-only semantics;
- maintain supported-subset semantic round-trip tests.

### Versioned Nodal YAML/JSON

Nodal supports a convenience schema such as `nodal-registers/v1` in YAML and equivalent JSON. YAML is an authoring frontend, not the semantic standard or canonical serialized IR.

The schema must:

- be explicitly versioned and validated;
- use deterministic canonicalization independent of key order;
- preserve file, line, column, and span diagnostics;
- define address units, widths, numeric forms, endianness, reset values, access policies, and hierarchy explicitly;
- use safe parsing with no arbitrary object tags or executable templates;
- provide explicit imports with controlled roots, cycle detection, and deterministic resolution rather than implicit `!include` behavior;
- reject unknown semantic keys unless an explicit namespaced extension policy permits them;
- provide migration diagnostics between schema revisions;
- produce the same canonical Register IR and ABI hash as equivalent Scala/SystemRDL input.

CSV and spreadsheets are not compiler contracts. They may be converted by explicit tools into the versioned YAML/JSON or SystemRDL frontend and then validated normally.

### IEEE 1685-2022 IP-XACT

IP-XACT register/memory-map import/export is an integration and packaging view built after canonical Register IR stabilizes. Vendor extensions remain explicit and namespaced. IP-XACT is not required for ordinary Nodal authoring and must not silently override the authoritative source.

## Generated Verilog address policy

The default portable-Verilog policy is:

> **Fixed register offsets and field positions are non-overridable constants; only intentional architectural variability becomes an HDL parameter.**

For a fixed register map, readable generated RTL should use width-safe `localparam`s or equivalent canonical constants, for example:

```verilog
localparam [ADDR_WIDTH-1:0] REG_CONTROL_OFFSET = 12'h000;
localparam [ADDR_WIDTH-1:0] REG_STATUS_OFFSET  = 12'h004;
```

The backend may inline a constant when that is simpler, but deterministic named `localparam`s are the default review/debug profile. It must avoid unsized literals and must preserve exact address width and signedness.

Individual register offsets, field positions, reset values, masks, and access encodings must not be externally overridable Verilog `parameter`s merely for convenience.

An HDL `parameter` is permitted only when it corresponds to intentional Nodal architectural configuration, such as a symbolic channel count, data width, optional feature set, or deliberately configurable layout geometry. Such parameters must carry declared bounds, overlap/range proofs, configuration identity, and matching software/verification artifact obligations.

The register block normally decodes **relative offsets**. SoC placement owns the absolute base address. A standalone absolute-decode wrapper may expose a `BASE_ADDR` parameter only as an explicit integration policy; the internal map offsets remain `localparam`s. The generated manifest must distinguish block-relative ABI from integration placement.

## Parameterized register geometry

Parameterized channel counts or repeated maps are allowed only through explicit Nodal `Param`-visible geometry. The canonical IR must retain formulas, constraints, and source provenance. Elaboration and backend validation must prove, over the declared parameter envelope where feasible:

- alignment and non-overlap;
- address-width sufficiency;
- finite array count and stride;
- no reserved-region violation;
- deterministic artifact configuration;
- no disagreement between RTL and firmware/verification views.

A fixed Scala offset does not become a Verilog parameter. A symbolic Nodal parameter may shape repetition or layout only when intentionally declared.

## Bus adapters

### APB

The APB adapter owns setup/access phases, wait states, byte strobes where supported, protection attributes, error responses, and conversion of one completed transfer into one canonical commit.

### AXI4-Lite

The AXI4-Lite adapter owns independent address/data channel buffering and pairing, response backpressure, write strobes, protection attributes, ordering, and declared outstanding depth. Accepting only an address or only data never commits a register write.

### Custom buses

A custom adapter must pass a public conformance suite covering exactly-once commit, response stability, byte enables, error normalization, backpressure, ordering, reset, and side-effect safety. Register semantics cannot depend on concrete adapter classes.

## Clock/reset and multiple access paths

Every physical `RegisterBlock` has one owning clock/reset domain. Cross-domain status and commands require explicit CDC/RDC primitives, coherent snapshots, pulse/handshake transfer, or FIFOs as appropriate. The register factory must not silently synchronize multi-bit state.

Attaching two transports directly to one bank is rejected. An explicit access arbiter/router defines priority, fairness, security, ordering, starvation behavior, and simultaneous-access policy.

## Multiword and partial-write semantics

The API/design gate must freeze:

- byte-enable legality and fields crossing byte lanes;
- reserved-bit read/write behavior;
- interactions of strobes with W1C/W1S/toggle/pulse fields;
- illegal-access response policy;
- endianness and address units;
- 64-bit or wider registers on narrower buses;
- non-atomic, snapshot, shadow-then-commit, protocol-atomic, or rejected multiword policies;
- aliases, set/clear/toggle aliases, arrays, windows, coherent snapshots, and commit groups.

No adapter may silently emulate unsupported semantics.

## One canonical artifact graph

Canonical Register IR drives:

- register storage, decode, read muxes, and side-effect RTL;
- APB/AXI4-Lite/custom adapters;
- canonical JSON manifest and ABI hash;
- C/C++ headers;
- Rust register metadata or PAC input;
- CMSIS-SVD where applicable;
- SystemRDL 2.0;
- IEEE 1685-2022 IP-XACT register/memory-map views;
- UVM RAL/RALF;
- Markdown/HTML documentation;
- generated verification contracts and adapter tests.

Generated files are views. They are never silently re-imported as competing sources.

## ABI compatibility

The canonical manifest records stable block/register/field IDs, paths, relative offsets, widths, reset and access policies, collision policy, parameter configuration, source locations, generator/toolchain versions, and a semantic hash.

A compatibility command classifies at least:

- register or field movement as breaking;
- bit-range/width changes as breaking;
- access/security/collision changes as behavioral or breaking;
- reset changes as behavioral;
- additions in declared reserved space according to explicit policy;
- address reuse after removal as breaking unless versioned deliberately;
- integration-base movement separately from block-relative ABI changes.

## Verification

Generated verification must cover reset values, legal/illegal addresses, reserved bits, read/write policies, byte enables, exactly-once side effects, no effect from incomplete APB/AXI transactions, response stability under backpressure, read-clear/write-one-clear, pulses, software/hardware collisions, multiword snapshots/commits, hierarchy/arrays, adapter conformance, and ABI equivalence across authoring frontends.

Concurrent and temporal properties generated for verification remain verification-only under ADR 0014 and Increment 114. They do not enter synthesizable DUT RTL. Any explicitly synthesized immediate checker follows the separate assertion-synthesis policy.

## Consequences

Benefits:

- one register definition works with multiple buses and artifacts;
- software, RTL, verification, and documentation share one semantic source;
- generated Verilog remains readable without exposing the ABI to accidental overrides;
- SystemRDL and IP-XACT interoperability do not dictate Nodal's public API;
- YAML remains easy to author while retaining strict schema and compiler diagnostics;
- adapters and artifact generators can grow independently around stable IR.

Costs:

- canonical semantics and capability negotiation require more initial design work than a direct bus factory;
- lossless import/export subsets must be documented and tested;
- parameterized geometry and multiword behavior need strong verifier coverage;
- external custom adapters require conformance infrastructure.

## Rejected alternatives

### Create registers directly from APB/AXI objects

Rejected because it makes transport timing part of the register definition and weakens reuse and artifact consistency.

### Make YAML the canonical semantic model

Rejected because YAML is a serialization syntax, not a register-behavior standard, and has weak typing/extension semantics without an external canonical model.

### Make SystemRDL the internal compiler IR

Rejected because Nodal needs typed hardware/domain bindings, adapter capabilities, source integration, and Nodal-specific semantics while still supporting SystemRDL interchange.

### Emit every register address as an overridable Verilog parameter

Rejected because it permits accidental software/RTL ABI disagreement and complicates verification. Fixed offsets are `localparam`s/constants; only intentional Nodal configuration becomes a parameter.

### Infer addresses from Scala declaration/reflection order

Rejected because source refactoring or compiler/JVM behavior could silently move the ABI.

### Allow implicit multiple-bus attachment

Rejected because arbitration, security, ordering, and collision semantics would be hidden.

## Follow-up roadmap

The detailed staged plan and machine-readable architecture candidate are:

- `docs/roadmap/register-factory-v0.1-plan.md`
- `docs/roadmap/register-factory-v0.1-surface.json`

Increment 115 records this architecture and roadmap contract. Later increments freeze the exact public API and implement canonical IR, frontends, transports, lowering, artifacts, compatibility, and verification.
