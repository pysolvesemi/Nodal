# Nodal register factory v0.1 plan

**Revision:** 0.1
**Created:** 2026-08-22
**Status:** Public API frozen by Increment 116; semantic implementation deferred
**Binding architecture:** [ADR 0020](../architecture/0020-canonical-register-factory-and-transport-adapters.md)

## Goal

Deliver a generic, scalable, reusable register facility whose programmer-visible semantics are independent of APB, AXI4-Lite, or any future/custom access transport.

The binding rule is:

> **Define the register ABI once, normalize every authoring source into one canonical Register IR, bind hardware explicitly, and adapt transports without changing register semantics.**

The design should retain the concise usability expected from a software-based HDL while making register ABI, clock/reset ownership, bus timing, generated RTL, software artifacts, and verification independent layers.

## Scope

The register-factory roadmap covers:

- immutable bus-neutral `RegisterMap` specifications;
- independent physical `RegisterBlock` instances;
- typed field handles and explicit hardware bindings;
- orthogonal software access, hardware update, collision, reset, side-effect, and multiword policies;
- a canonical committed-access endpoint;
- APB3/APB4 and AXI4-Lite adapters;
- a public custom-transport adapter contract and conformance suite;
- native Scala 3, SystemRDL 2.0, versioned Nodal YAML/JSON, and later IP-XACT register-view frontends;
- canonical Register IR, source maps, diagnostics, parameter-envelope checks, ABI manifests, and compatibility diff;
- portable Verilog lowering using non-overridable address constants by default;
- C/C++, Rust, CMSIS-SVD, SystemRDL, IP-XACT, UVM RAL/RALF, and documentation views;
- generated simulation/formal contracts and open-source RTL validation.

Increment 116 freezes the public Scala source contract through `NodalRegisterFactory-DG-v0.1`. Canonical Register IR, parsers, register behavior, adapters, RTL, and artifacts remain deferred to Increments 117-123.

## Frozen public API v0.1

The exact public surface is frozen by [`NodalRegisterFactory-DG-v0.1.md`](../design-gates/NodalRegisterFactory-DG-v0.1.md), [`register-factory-api-v0.1.json`](../../core/scala/api/register-factory-api-v0.1.json), and [`register-factory-diagnostics-v0.1.json`](../../core/scala/api/register-factory-diagnostics-v0.1.json).

The freeze selects immutable bus-neutral maps, map-owned path-dependent field/group handles, one-domain physical blocks, explicit typed hardware bindings, orthogonal policy enums, an opaque adapter endpoint, and Scala 3 `RegisterTransport[B]` contextual attachment. Positive fixtures compile while semantic implementation remains intentionally inert.

## Layered architecture

```text
Scala RegisterMap DSL ───────┐
SystemRDL 2.0 ───────────────┤
Nodal YAML/JSON ─────────────┼──> Canonical Register IR
IP-XACT register view ───────┘             │
                                           ├── semantic verification / ABI hash
                                           ├── physical RegisterBlock binding
                                           ├── canonical committed-access endpoint
                                           ├── APB / AXI4-Lite / custom adapters
                                           ├── portable Verilog RTL
                                           └── software / UVM / docs / interchange
```

For each map, project configuration identifies one authoritative authoring source. Generated outputs are views and are not silently treated as editable sources.

## Core concepts

### Immutable RegisterMap

A `RegisterMap` describes the software-visible ABI without live hardware signals or a concrete bus. It includes:

- stable block/register/field IDs and hierarchy paths;
- byte offsets, field ranges, widths, masks, alignment, and address units;
- reset values and reset priority;
- software access behavior;
- hardware update capability;
- simultaneous software/hardware collision policy;
- read/write side effects;
- reserved-bit and illegal-access policy;
- arrays, repeated submaps, sparse ranges, windows, aliases, snapshots, and commit groups;
- endianness and multiword access policy;
- source locations, documentation, security/protection metadata, and ABI version;
- explicit symbolic geometry and parameter constraints where intentionally supported.

Published register maps use explicit offsets and field positions by default. Automatic allocation is provisional or is locked into a canonical manifest before the map becomes a supported ABI.

### Physical RegisterBlock

A `RegisterBlock` realizes a `RegisterMap` as hardware:

- one owning lexical clock/reset domain;
- independent state for each instance;
- typed hardware bindings for control, status, events, counters, pulses, snapshots, and commits;
- one canonical access endpoint;
- one directly attached transport by default;
- explicit access arbitration when more than one transport reaches the same physical bank.

The same map may instantiate multiple independent banks.

### Orthogonal policies

Do not create a giant combined enumeration for every software/hardware pairing.

Candidate software axis:

```text
RO, RW, WO, W1C, W1S, W1T, W0C, W0S, RC, RS, WriteOnce, Reserved
```

Candidate hardware axis:

```text
None, Input, Write, Settable, Clearable, Increment, Decrement, Pulse
```

Candidate collision axis:

```text
HardwareWins, SoftwareWins, SetDominatesClear,
ClearDominatesSet, ErrorOnCollision
```

The public API gate must freeze exact combinations, legality, reset ordering, and diagnostics.

## External register-description formats

### SystemRDL 2.0: primary standards-based register interchange

SystemRDL 2.0 is the first standards-based import/export target because it is dedicated to register descriptions and multi-view generation.

Requirements:

- support a documented semantic subset on import;
- preserve source locations and hierarchy;
- lower into canonical Register IR before any other action;
- export deterministic SystemRDL for the representable subset;
- diagnose unsupported and lossy mappings;
- never silently drop Nodal-only semantics;
- provide semantic round-trip fixtures for supported constructs;
- publish a mapping table for access types, resets, arrays, address allocation, aliases, and user-defined properties/extensions.

### Nodal YAML/JSON: convenient, versioned frontend

Support an explicitly versioned schema such as:

```yaml
schema: nodal-registers/v1
block: uart
bus_width: 32
address_unit: byte
endianness: little
registers:
  - name: CONTROL
    offset: 0x00
    fields:
      - name: ENABLE
        bits: 0
        reset: 0
        sw: rw

  - name: STATUS
    offset: 0x04
    fields:
      - name: BUSY
        bits: 0
        sw: ro
        hw: input
      - name: IRQ
        bits: 1
        reset: 0
        sw: w1c
        hw: settable
        collision: set_dominates_clear
```

YAML/JSON requirements:

- YAML and JSON are equivalent serializations of one schema;
- the schema version is mandatory;
- parsing uses safe data-only loaders with no arbitrary tags or code execution;
- unknown semantic keys are errors unless explicitly namespaced;
- key order has no semantic meaning;
- addresses, ranges, units, widths, endianness, and numeric forms are explicit;
- every diagnostic carries file, line, column, and source span where available;
- canonicalization is deterministic and produces the same Register IR/hash as equivalent Scala or SystemRDL;
- imports use explicit declarations, controlled search roots, cycle detection, and deterministic resolution;
- implicit YAML `!include`, Jinja/template execution, environment-dependent substitution, and arbitrary scripts are outside the compiler contract;
- schema upgrades provide migration diagnostics and deterministic conversion tools;
- the canonical JSON manifest generated from Register IR is distinct from the convenience input schema.

CSV and spreadsheets may be supported by separate conversion tools. Their layout is not part of the compiler contract; conversion output must pass normal YAML/SystemRDL validation.

### IEEE 1685-2022 IP-XACT: integration/packaging interchange

Plan register/memory-map import/export after canonical Register IR is stable.

Requirements:

- treat IP-XACT as broader IP integration metadata rather than the required register-authoring format;
- support the register/memory-map subset explicitly;
- preserve vendor extensions only through namespaced extension records;
- report unsupported/lossy mappings;
- keep integration base placement separate from block-relative register ABI;
- provide round-trip and schema-validation tests for supported IEEE 1685-2022 content.

### Other generated views

Generate from canonical Register IR:

- canonical JSON manifest and semantic ABI hash;
- C/C++ headers;
- Rust metadata or peripheral-access-crate input;
- CMSIS-SVD where the target ecosystem fits;
- SystemRDL 2.0;
- IEEE 1685-2022 IP-XACT register/memory-map views;
- UVM RAL/RALF;
- Markdown/HTML documentation;
- verification metadata and test vectors.

No generated view may become a hidden second source of truth.

## Generated Verilog address and parameter policy

### Default: fixed ABI symbols are local constants

Fixed register offsets, field positions, masks, reset values, and access encodings are part of the register ABI. The portable-Verilog backend therefore emits width-safe, non-overridable `localparam`s or equivalent direct constants.

Preferred readable form:

```verilog
localparam [ADDR_WIDTH-1:0] REG_CONTROL_OFFSET = 12'h000;
localparam [ADDR_WIDTH-1:0] REG_STATUS_OFFSET  = 12'h004;
localparam [DATA_WIDTH-1:0] REG_IRQ_MASK       = 32'h0000_0002;
```

Rules:

- do not emit individual register addresses as overridable `parameter`s;
- avoid unsized numeric literals;
- preserve exact address/data widths and signless address semantics;
- use deterministic semantic names;
- allow an explicit compact/inlining emission profile, but keep named `localparam`s as the default review/debug form;
- field bit positions may be represented as local constants/masks or folded directly, never as accidental external configuration.

### Parameters only for intentional architectural variability

An HDL `parameter` is emitted only when it corresponds to an explicit Nodal symbolic parameter that intentionally changes hardware or register geometry, for example:

- number of repeated channels;
- optional register groups/features;
- bus/data width when the IP is deliberately width-configurable;
- repeated-map stride or dimensions when intentionally symbolic;
- an explicitly selected standalone wrapper base address.

Parameterized geometry must retain formulas and constraints through canonical Register IR. Validation must prove alignment, finite bounds, non-overlap, address-width sufficiency, and deterministic artifact configuration over the declared parameter envelope where feasible.

A Scala literal offset remains a constant in generated Verilog. It does not become a parameter merely because the backend can expose it.

### Relative offsets by default

The register block decodes relative addresses. SoC/interconnect integration owns absolute placement:

```text
absolute address = integration base + stable block-relative offset
```

The default generated peripheral contains only relative-offset localparams. A separate absolute-decode wrapper may expose `BASE_ADDR` as an intentional integration parameter, but internal register offsets remain fixed localparams. The manifest and ABI diff distinguish integration placement from block-relative ABI.

### Artifact consistency for parameterized configurations

When register geometry depends on Nodal parameters:

- generated RTL, headers, UVM models, docs, and manifests identify the same parameter configuration;
- symbolic exports are allowed only when the target format represents the formulas losslessly;
- concrete software artifacts must state their resolved parameter values;
- cache keys include register semantic hash and parameter configuration;
- CI rejects RTL/artifact configuration mismatches.

## Canonical access endpoint

The transport-neutral request/response model must include enough information for APB, AXI4-Lite, and custom buses without leaking protocol phases:

```text
request:
  operation
  relative address
  write data
  byte enables
  protection/security metadata where available
  transaction identity when needed

response:
  read data
  normalized success/error status
  transaction identity when needed
```

A register effect occurs exactly once on canonical commit. This is mandatory for read-clear, write-one-clear, command/pulse fields, counters, snapshots, and multiword commits.

## Transport adapters

### APB3/APB4

Cover setup/access phases, wait states, PSTRB where available, PPROT, PSLVERR, reset, and exactly-one commit per completed transfer.

### AXI4-Lite

Cover independent AW/W buffering and deterministic pairing, AR/R and B-channel backpressure, WSTRB, ARPROT/AWPROT, response codes, configurable outstanding depth, ordering, reset, and no effect from incomplete transactions.

### Custom transport

A public Scala 3 type-class/extension contract conceptually similar to `RegisterTransport[B]` lets external buses connect through the canonical endpoint. A conformance suite checks capabilities, commit, response stability, strobes, errors, ordering, backpressure, and reset.

Multiple buses attached to one physical bank require an explicit arbiter/router with visible priority, fairness, security, starvation, ordering, and simultaneous-access policy.

## Clock/reset and CDC/RDC

A physical register block belongs to exactly one clock/reset domain.

- local controls and status bind directly;
- cross-domain levels use explicit typed CDC only when safe;
- pulses/commands use pulse or handshake transfer;
- coherent multi-bit status uses snapshot/handshake/FIFO semantics;
- reset-domain behavior uses explicit RDC policy;
- the register factory never silently inserts independent bit synchronizers for a multi-bit value.

## Partial writes, side effects, and multiword registers

The API gate must define:

- legality of partial writes per field/register;
- fields crossing byte lanes;
- W1C/W1S/W1T/pulse behavior under byte strobes;
- reserved-bit read/write policy;
- illegal address/access response;
- endianness and address units;
- read-clear/read-set timing;
- command pulse width and retry/backpressure behavior;
- hardware/software simultaneous updates;
- counters, sticky bits, saturation, aliases, set/clear/toggle aliases;
- wide-register policies: non-atomic, snapshot-on-first-read, shadow-then-commit, protocol-atomic, or rejected;
- snapshot and commit groups;
- arrays, windows, sparse maps, and hierarchical submaps.

No adapter may silently approximate unsupported behavior.

## ABI locking and compatibility

The canonical manifest contains stable identities and semantic metadata. A future command such as:

```text
nodal registers diff old.json new.json
```

classifies at least:

- moved register/field: breaking;
- changed bit range or width: breaking;
- changed access, collision, security, or side-effect policy: behavioral/breaking;
- changed reset: behavioral;
- addition inside explicitly reserved space: policy-dependent compatible;
- reused removed address: breaking unless deliberately versioned;
- changed integration base: placement change, separate from block-relative ABI;
- changed parameter envelope or resolved configuration: explicit compatibility classification.

## Verification and quality gates

Generated verification covers:

- reset values;
- legal/illegal addresses and accesses;
- reserved bits;
- every software/hardware access policy;
- byte enables and partial writes;
- exactly-once side effects;
- no side effects from incomplete APB/AXI transactions;
- stable responses under backpressure;
- command pulses, counters, sticky status, and collisions;
- multiword snapshot/commit policies;
- arrays/hierarchy/aliases/windows;
- adapter conformance;
- semantic equivalence across Scala/SystemRDL/YAML input;
- deterministic manifests, ABI hashes, and generated artifacts;
- Verilator/Icarus simulation and Yosys synthesis/equivalence for supported digital RTL.

Generated concurrent/temporal properties are verification-only. They never become synthesizable DUT RTL under Increment 114.

## Increment plan

- [x] **Increment 115 — Register factory architecture and roadmap contract**
  - Accept ADR 0020 and this staged roadmap.
  - Freeze register-definition-first separation, canonical Register IR, one authoritative source per map, transport-neutral committed access, explicit multi-bus arbitration, and one clock/reset domain per physical block.
  - Record SystemRDL 2.0 as the primary standards-based register interchange, versioned Nodal YAML/JSON as a convenience frontend, and IEEE 1685-2022 IP-XACT as later integration interchange.
  - Freeze generated-Verilog policy: fixed register ABI symbols use width-safe `localparam`s/constants; only intentional Nodal architectural variability becomes an HDL `parameter`; relative-offset decode is the default.
  - Add a machine-readable architecture candidate and update the main roadmap to revision 1.14 without claiming API/compiler/backend implementation.

- [x] **Increment 116 — Register factory public API candidates and design gate**
  - Freeze Scala 3 `RegisterMap`, `RegisterBlock`, register/field definitions, map-owned handles, hardware bindings, arrays/submaps/windows/aliases, snapshots/commits, orthogonal policies, and contextual transport attachment.
  - Record the Scala 3.8.4 and SpinalHDL 1.14.2 comparison while preserving the stronger register-definition-first separation.
  - Publish `NodalRegisterFactory-DG-v0.1`, API/diagnostic manifests, language reference, external-consumer fixtures, executable type-negative fixtures, and semantic-contract fixtures.
  - Keep semantic lowering, canonical Register IR, parsers, bus adapters, RTL, and artifacts deferred.
  - Evidence: [`NodalRegisterFactory-DG-v0.1.md`](../design-gates/NodalRegisterFactory-DG-v0.1.md), [`register-factory-api-v0.1.json`](../../core/scala/api/register-factory-api-v0.1.json), [`register-factory-diagnostics-v0.1.json`](../../core/scala/api/register-factory-diagnostics-v0.1.json), and [`tests/api/fixtures/increment116/manifest.json`](../../tests/api/fixtures/increment116/manifest.json).

- [ ] **Increment 117 — Canonical Register IR, source maps, verifier, and ABI manifest**
  - Implement target-neutral Register IR for blocks, registers, fields, hierarchy, geometry, policies, side effects, hardware bindings, domains, accesses, and transport capabilities.
  - Add deterministic IDs, source locations, canonical JSON, semantic hashing, overlap/alignment/address-width checks, reserved-region checks, and parameter-envelope verification.
  - Implement ABI lock/diff classification and mutation tests.

- [ ] **Increment 118 — SystemRDL 2.0 and Nodal YAML/JSON frontends**
  - Implement safe, versioned YAML/JSON parsing and schema validation with deterministic imports and source diagnostics.
  - Implement the supported SystemRDL 2.0 import subset and deterministic export.
  - Prove equivalent Scala/SystemRDL/YAML definitions normalize to equivalent Register IR and ABI hashes.
  - Publish unsupported/lossy mapping diagnostics and round-trip matrices.

- [ ] **Increment 119 — Canonical access endpoint and APB3/APB4 adapter**
  - Implement the committed request/response endpoint, register storage/update semantics, decode/read mux, byte enables, errors, side effects, and reset behavior.
  - Implement APB3/APB4 adapters with setup/access, wait states, strobes/protection where supported, and exactly-once commit.
  - Add open-source simulation, formal protocol/semantic checks, lint, and synthesis evidence.

- [ ] **Increment 120 — AXI4-Lite and custom transport conformance**
  - Implement AXI4-Lite channel buffering/pairing, backpressure, strobes, protection, response codes, ordering, and declared outstanding policy.
  - Freeze and implement the public custom-transport adapter capability contract and conformance suite.
  - Add an explicit multi-access arbiter/router reference implementation and negative tests for implicit dual attachment.

- [ ] **Increment 121 — Portable Verilog register lowering and parameterized geometry**
  - Emit deterministic relative-offset decode, width-safe named `localparam`s, masks, reset/access constants, storage, side effects, and readable hierarchy.
  - Emit HDL parameters only for explicit Nodal symbolic architecture/configuration; never make fixed offsets externally overridable.
  - Implement optional absolute-decode wrappers with explicit base-address policy.
  - Validate parameterized repeated maps over declared envelopes and prove RTL/artifact configuration consistency.

- [ ] **Increment 122 — Artifact generators, IP-XACT, and software ABI flows**
  - Generate C/C++, Rust metadata/PAC input, CMSIS-SVD, UVM RAL/RALF, Markdown/HTML, SystemRDL, canonical JSON, and IEEE 1685-2022 IP-XACT register/memory-map views.
  - Preserve stable IDs, source provenance, semantic hashes, parameter configuration, and loss diagnostics in every artifact.
  - Add artifact equivalence tests and release-facing ABI compatibility reports.

- [ ] **Increment 123 — Register factory verification, scale, and reusable adapter/library qualification**
  - Add generated semantic properties/tests for access modes, side effects, collisions, byte enables, multiword policies, arrays, hierarchy, snapshots, commits, and illegal accesses.
  - Add large-map decode/read-mux performance benchmarks, deterministic output tests, and Yosys quality/equivalence gates.
  - Qualify one external custom transport and one reusable external register-map package using only public contracts.
  - Publish user, adapter-author, SystemRDL/YAML migration, and integration documentation.

## Exit criteria for the complete track

- One map can be authored in Scala or imported from supported SystemRDL/YAML and produce semantically equivalent Register IR.
- The same map attaches to APB and AXI4-Lite without redefining fields or access behavior.
- Fixed offsets are non-overridable constants in generated Verilog.
- Intentional symbolic geometry is parameterized consistently across RTL and every generated artifact.
- Software/hardware collisions, byte enables, side effects, multiword access, illegal access, and reset behavior are deterministic and verified.
- Custom adapters use only public contracts and pass conformance.
- ABI changes are classified deterministically.
- Generated RTL passes the mandatory Nodal quality gates and open-source simulation/synthesis matrix.
