# Nodal Interface, Role, digital inout, and AMS connectivity v0.1 plan

**Revision:** 0.1
**Created:** 2026-08-23
**Status:** Architecture accepted; public API and implementation staged
**Binding architecture:** [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md)
**Public freeze owner:** Increment 15

## Goal

Deliver one scalable connectivity architecture that supports:

- directionless storable value aggregates;
- reusable digital protocol interfaces;
- generic named roles with `master`/`slave` convenience;
- complete `Valid` and `Stream` direction/handshake semantics;
- first-class digital `inout`, tri-state, open-drain, black-box, pad, and hierarchy use;
- conservative analog terminals and directional analog signal-flow values;
- mixed-signal interfaces containing both digital channels and analog terminals;
- deterministic flattening for Verilog and Verilog-AMS;
- optional native SystemVerilog interface/modport lowering after its separate backend gate;
- stable interface ABI manifests, wrappers, verification metadata, and external reuse.

The binding rule is:

> **Values are directionless `Struct`s, connectivity is an `Interface`, access is selected by a named `Role`, physical bidirectionality is explicit, and every backend preserves one logical interface ABI.**

The exact Scala syntax is evaluated in Increment 14 and frozen with the rest of core public API v0.3 in Increment 15.

## Scope boundaries

This plan covers public semantics, canonical IR, role expansion, digital resolved nets, analog connectivity, backend layouts, wrappers, ABI evidence, and qualification.

It does not:

- make SystemVerilog the required backend;
- treat every aggregate as an interface;
- treat conservative analog terminals as digital inout;
- permit implicit protocol, width, domain, discipline, or analog/digital conversion;
- claim all internal tri-state structures are portable to every synthesis flow;
- replace the explicit AMS-to-FPGA approximation architecture in ADR 0011;
- freeze public spelling before compile-candidate evaluation.

## Layered model

```text
Directionless values
  Struct / enum / Vec
          │
          ▼
Connectivity definitions
  Interface + named Role
          │
          ├── plain digital leaves
          ├── Valid / Stream / nested protocol interfaces
          ├── digital resolved inout endpoints
          ├── conservative AMS terminals
          ├── directional analog signal-flow values
          └── explicit mixed-signal bridges
          │
          ▼
Canonical Interface IR
          │
          ├── role expansion and connection verification
          ├── domain / net-resolution / topology analysis
          ├── logical Interface ABI manifest
          ├── wrapper / adapter generation
          └── backend capability verification
          │
          ├── portable Verilog flattened ports
          ├── Verilog-A analog terminals
          ├── Verilog-AMS flattened mixed-signal ports
          └── future SystemVerilog interface + modport
```

## Public semantic candidates

### Directionless `Struct`

Candidate shape:

```scala
final case class Pixel(width: Int) extends Struct:
  val red   = UInt(width)
  val green = UInt(width)
  val blue  = UInt(width)
```

Required properties:

- may be stored in `Reg`/`Mem` when every member is storable;
- may be nested in another `Struct` or structural `Vec`;
- may be used as a protocol payload;
- has deterministic field identity/order independent of reflection order;
- contains no boundary direction or role;
- cannot contain an `Interface` or conservative terminal.

Increment 14 compares the name `Struct` against retaining/refining the existing `Aggregate`/`Bundle` candidate. Increment 15 selects one stable public spelling while preserving the semantic distinction.

### Connectivity `Interface`

Candidate shape:

```scala
final case class VideoInterface(width: Int) extends Interface:
  val pixels = Stream(Pixel(width))
  val start  = Bool()

  role source:
    master(pixels)
    out(start)

  role sink:
    slave(pixels)
    in(start)

  role monitor:
    observe(pixels, start)
```

Required properties:

- cannot be stored or used in arithmetic;
- may nest interfaces and value aggregates;
- exported instances select exactly one role;
- internal construction may defer role selection until connection/export closure;
- role expansion is deterministic, source located, and represented in IR;
- interface arrays and symbolic repetition preserve stable logical paths and parameter formulas;
- exact connections require complete compatible roles and members.

### Generic named roles

The core role model supports arbitrary stable names. Built-in convenience includes:

```text
master / slave
source / sink
initiator / target
controller / peripheral
device / environment
monitor
```

The public API gate must freeze:

- role declaration and selection syntax;
- role identity and compatibility;
- recursive nested-role application;
- exact inverse declaration rules;
- monitor/read-only behavior;
- unresolved role and missing-member diagnostics;
- external-library extension rules;
- role metadata and ABI hashing.

Automatic inversion is permitted only for fully complementary digital access. Analog access, monitor access, shared endpoints, and non-complementary roles require explicit definitions.

### `Valid` and `Stream`

Canonical transport semantics:

```text
Valid[T]
  master: valid/payload out
  slave:  valid/payload in

Stream[T]
  master: valid/payload out, ready in
  slave:  valid/payload in, ready out
```

Required public operations include source/sink construction, exact connection, transfer/fire observation, stall/bubble behavior, domain metadata, monitor view, and explicit conversion/adaptation. Exact spellings are selected by compile prototypes.

`Valid` remains the canonical valid-only type. `Flow` is not a second core semantic type.

## Digital inout architecture

### Public endpoint candidate

Preferred shape:

```scala
val gpio = inout(Bits(8))

val sampled = gpio.read
gpio.drive(writeData, enable = outputEnable)
```

Alternative candidate spellings may use a `DigitalInOut[T]`, `Resolved[T]`, or pad endpoint wrapper, but the gate must retain separate read and drive semantics.

The endpoint records:

- exact leaf type and width/shape;
- resolved-net identity;
- readback view;
- driver values and enables;
- drive mode;
- hierarchy provenance;
- backend capability requirements;
- source maps and logical ABI identity.

Direct `:=` assignment to an inout endpoint is rejected or restricted because it does not express `Z`/enable behavior explicitly.

### Initial drive modes

The semantic set includes:

- push-pull tri-state;
- open-drain/open-collector;
- open-source where supported;
- read-only observation;
- hierarchy pass-through;
- explicit pad/IO-cell adapter.

Open-drain may drive only the active level or `Z`. Pull-up/down and technology-specific electrical details are explicit metadata/adapters.

### Initial value and resolution model

Simulation and verification support at least:

```text
0, 1, Z, X/contention
```

Resolution must be deterministic and profile documented. Multiple drivers are allowed only on declared resolved nets. Ordinary signals and protocol leaves remain single-driver.

### Portable implementation rule

Portable digital design should normally use a split tri-state carrier internally:

```text
read data
write data
drive enable / drive state
```

and collapse it to a physical inout only at a top-level, black-box, pad, or explicit resolved-net boundary.

The initial portable Verilog synthesis profile may restrict native tri-state resolution to those boundaries. Unsupported internal resolved nets are errors. No silent conversion to a mux is allowed.

### Required inout use cases

- top-level FPGA/ASIC pins;
- black-box inout pins;
- hierarchical pass-through;
- GPIO;
- I2C/open-drain buses;
- MDIO and similar management buses;
- memory DQ/DQS or selected PHY/pad groups;
- mixed-signal wrappers;
- interface members with read/drive/connect role permissions.

### Inout compile-negative contracts

- direct assignment without explicit drive semantics;
- driving a monitor/read-only role;
- open-drain active-high drive;
- multiple drivers on an ordinary signal;
- implicit conversion between resolved inout and `Stream`/`Valid`;
- implicit conversion between digital inout and conservative analog terminal;
- unsupported internal tri-state under a portable synthesis profile;
- mismatched widths/shapes or net kinds;
- unbound inout hierarchy path;
- role inversion that loses read/drive permissions.

## AMS connectivity architecture

### Conservative terminals

The compiler distinguishes:

```text
Terminal[D]  module/interface boundary physical connection
Node[D]      internal topological connection
Branch[D]    ordered potential/flow relation
```

Exact public names are selected by the v0.3 gate. Conservative terminals carry discipline/nature, potential/flow dimensions, topology, connect-rule eligibility, access permissions, and source provenance.

They are not digital inout values and do not use tri-state enables.

### Directional analog signal flow

Directional information-flow values use a separate typed category, conceptually:

```scala
AnalogSignal[Voltage]
AnalogSignal[Temperature]
AnalogSignal[Frequency]
```

They may use `in`/`out` roles. Conversion to/from conservative terminals, discrete real nets, or finite-width digital values is explicit.

### Analog role access

Mixed-signal roles use explicit conservative access categories:

- connect: participate in physical topology;
- sense: observe potential/flow without contributing;
- contribute: add equations/contributions where legal;
- monitor: read-only observation.

The public syntax may combine these where standards semantics require it, but IR keeps them distinct. Analog roles are never automatically inferred by digital role inversion.

### Mixed-signal interface candidate

```scala
final case class AdcInterface(width: Int) extends Interface:
  val vinP   = terminal(Electrical)
  val vinN   = terminal(Electrical)
  val sample = Bool()
  val code   = Valid(UInt(width))

  role device:
    connect(vinP, vinN)
    in(sample)
    master(code)

  role environment:
    connect(vinP, vinN)
    out(sample)
    slave(code)

  role monitor:
    sense(vinP, vinN)
    observe(sample, code)
```

### Explicit mixed-signal bridges

Bridge contracts include:

- source and destination type/domain;
- physical dimensions;
- sampling/update time;
- threshold and hysteresis;
- quantization, rounding, saturation, and range;
- delay, transition, hold, interpolation, and event semantics;
- connect-rule or resolution behavior;
- simulation/synthesis/formal/approximation model availability;
- source and generated artifact mapping.

No implicit analog/digital conversion is permitted.

## Canonical Interface IR

The IR must represent:

- stable interface and role definitions;
- stable member IDs and logical paths;
- `Struct` versus `Interface` kind;
- selected role and expanded per-member access;
- plain/`Valid`/`Stream` protocol identity;
- type, shape, width, signedness, enum encoding, and parameter formulas;
- clock/reset domain provenance;
- digital net kind, resolution, drive mode, driver inventory, and readback;
- analog discipline/nature, terminal/node/branch identity, and access;
- mixed-signal bridges and conversion provenance;
- source spans and hierarchy paths;
- backend capability and physical-layout mappings;
- interface ABI hash and compatibility metadata.

The verifier rejects incomplete roles, incompatible connections, illegal storage, multiple ordinary drivers, unsupported resolution, discipline mismatch, implicit conversions, parameter-envelope layout conflicts, and profile-incompatible target forms.

## Logical Interface ABI manifest

Every accepted backend emits a canonical manifest containing at least:

```json
{
  "interface": "AdcInterface[12]",
  "role": "device",
  "logicalPath": "code.payload",
  "kind": "stream-payload",
  "direction": "output",
  "type": "UInt[12]",
  "domain": "sample",
  "backendPath": "adc_code_payload"
}
```

For inout and AMS leaves the manifest also records net/drive mode or discipline/access.

The manifest drives:

- wrapper generation;
- cocotb/Scala/UVM agents;
- waveform/source correlation;
- IP-XACT and other integration views;
- native-interface versus flattened equivalence;
- ABI compatibility reports;
- cache/provenance keys.

## Backend plans

### Portable Verilog

- flatten all interfaces deterministically;
- flatten `Struct` payloads using canonical layout;
- emit `Valid`/`Stream` leaf ports with stable names;
- emit supported digital inout as net-typed `inout` ports plus width-safe tri-state assignments;
- preserve black-box/hierarchy pass-through;
- reject analog terminals and unsupported internal resolved nets;
- emit interface ABI/source-map manifests.

### Verilog-A

- flatten analog-only interfaces to discipline-qualified terminals and supported directional analog values;
- reject unsupported digital protocol members through capability verification;
- preserve logical interface/source mapping.

### Verilog-AMS

- flatten digital protocols, resolved inouts, conservative terminals, directional analog values, clock/reset/domain members, and explicit bridges;
- retain connect-module/rule and discipline semantics;
- preserve one logical interface ABI across mixed-signal hierarchy.

### Future SystemVerilog

After Increment 99 approves a backend:

- emit native `interface` and `modport` definitions where selected;
- support nested native interfaces where the tool profile permits;
- provide deterministic flatten fallback and per-instance flatten override;
- generate wrappers between native and flattened forms;
- prove semantic/ABI parity with portable flattened representation;
- retain compile-order and package/interface dependency manifests.

## Existing roadmap integration

This architecture is integrated into existing increments rather than creating a second compiler path.

### Increment 14

Add compile candidates for:

- `Struct` versus `Interface`;
- named roles and role inversion rules;
- `master`/`slave`/`monitor` on `Valid`/`Stream`;
- nested request/response interfaces;
- digital inout read/drive/open-drain/pass-through;
- mixed-signal interfaces with conservative terminals;
- flattened/native target layout candidates;
- positive external-library and negative role/inout/AMS fixtures.

### Increment 15

Freeze the exact v0.3 public spelling, imports, type relationships, role/access model, diagnostics, migration, backend-neutral ABI, and external-library contract.

### Increments 16, 19, and 22

Implement construction ownership/closure, canonical Interface IR, role expansion, source spans, diagnostics, and logical ABI mapping.

### Increments 54-58

Implement digital port/net/`Struct`/`Interface`/role types, full `Valid`/`Stream`, resolved inout, tri-state/pass-through, hierarchy, domains, and driver checks.

### Increments 59-64

Use protocol-role semantics and interface sidebands in automatic pipeline scheduling without changing logical roles or interface ABI.

### Increments 65-67

Implement portable Verilog flattening, inout lowering, open-source simulation/synthesis/equivalence, protocol agents, and resolved-net verification.

### Increments 68-72

Implement discrete real/signal-flow types, conservative terminals, bridges, connect modules/rules, mixed-domain verification, and complete Verilog-AMS interface lowering.

### Increments 73-77

Qualify ADC/DAC/PLL/comparator mixed-signal interfaces, simulator adapters, portable/full AMS profiles, and UVM-MS metadata.

### Increment 99

Research and gate native SystemVerilog/SystemVerilog-AMS interface and modport emission. It cannot change Nodal source semantics.

## Cross-cutting closure increments

- [x] **Increment 124 — Interface, Role, AMS, and digital inout architecture roadmap contract**
  - Accept ADR 0021 and this plan.
  - Freeze the semantic separation among `Struct`, `Interface`, `Role`, digital resolved inout, conservative terminals, signal-flow analog values, and explicit bridges.
  - Record `master`/`slave` as convenience roles over a generic role model and `Valid` as the canonical valid-only transport.
  - Record backend-neutral logical Interface ABI, deterministic Verilog/Verilog-AMS flattening, and future native SystemVerilog interface/modport parity.
  - Update Increment 14/15 and implementation increments without claiming public API/compiler/backend implementation.

- [ ] **Increment 125 — Canonical Interface IR, role expansion, source maps, and ABI manifest**
  - Implement interface/role definitions, member identity, recursive role expansion, exact connection compatibility, interface storage prohibition, parameterized member paths, source maps, diagnostics, canonical manifests, ABI hashes, and compatibility classification.
  - Integrate with construction close, Nodal MLIR, cross-layer diagnostic mapping, plugin metadata, caches, and deterministic parse/print.

- [ ] **Increment 126 — Digital Struct/Interface/Role and full Valid/Stream implementation**
  - Implement directionless `Struct`, nested digital interfaces, named roles, complementary role derivation, monitor views, plain/`Valid`/`Stream` channels, transfer/stall/bubble semantics, exact connection, typed adapters, domain provenance, and hierarchy propagation.
  - Add external-library and protocol-interface conformance suites.

- [ ] **Increment 127 — Digital inout, resolved nets, tri-state, open-drain, pads, and black-box hierarchy**
  - Implement typed read/drive endpoints, driver enables/states, `0/1/Z/X` resolution, push-pull/open-drain/open-source modes, readback, pull/pad metadata, hierarchy pass-through, black-box connectivity, and split-tristate boundary adapters.
  - Add profile-aware restrictions for internal tri-state synthesis, contention diagnostics/properties, Verilator/Icarus behavior, Yosys synthesis/equivalence where supported, and negative fixtures.

- [ ] **Increment 128 — Conservative AMS terminals, signal-flow values, and mixed-signal roles**
  - Implement boundary `Terminal`, internal `Node`, `Branch`, role access, discipline/nature/dimension checks, directional analog signal-flow values, mixed digital/analog interfaces, and explicit bridge endpoints.
  - Integrate connect modules/rules, continuous-time island graphs, domain/event provenance, and no-implicit-conversion verification.

- [ ] **Increment 129 — Flattened Verilog, Verilog-A, and Verilog-AMS interface lowering**
  - Emit deterministic flattened names/ports/terminals for nested interfaces, protocols, shaped payloads, resolved inouts, conservative terminals, and bridges.
  - Generate wrappers, interface ABI/source-map manifests, compile-order/dependency metadata, profile diagnostics, and exact golden fixtures.

- [ ] **Increment 130 — Native SystemVerilog interface/modport backend and wrapper parity**
  - After Increment 99 approval, emit native interfaces, modports, nested interfaces, parameters, monitor roles, inout nets, and per-instance flatten overrides.
  - Generate native/flat wrappers and prove logical ABI, simulation, synthesis, and source-map parity across supported tool profiles.
  - Keep the SystemVerilog backend optional and capability gated.

- [ ] **Increment 131 — Interface metadata, verification agents, scale, and external qualification**
  - Generate Scala simulation, cocotb, UVM/UVM-MS, waveform, IP-XACT, and documentation metadata from the logical Interface ABI.
  - Add role/inout/AMS protocol checkers, large nested-interface performance, parameter matrices, deterministic output, compatibility diff, custom-interface library qualification, and end-to-end external reusable interface examples.

## Required compile-positive matrix

Before Increment 15 freezes the surface, candidates must compile for:

- storable nested `Struct` payloads;
- `master` and `slave` `Valid`;
- `master` and `slave` `Stream`;
- request/response interface with opposite nested channel roles;
- monitor role;
- user-defined controller/peripheral roles;
- nested interfaces and symbolic arrays;
- exact role-compatible connection;
- top-level digital inout read/drive;
- open-drain interface;
- black-box inout and hierarchy pass-through;
- split internal tri-state plus physical boundary adapter;
- electrical-terminal-only interface;
- mixed ADC-style analog/digital interface;
- external reusable interface using public API only;
- portable flattened layout candidate;
- future native SystemVerilog interface/modport layout candidate.

## Required compile/semantic-negative matrix

- storing an `Interface` in `Reg` or `Mem`;
- embedding an `Interface` or direction in a `Struct`;
- exported interface without a role;
- incompatible or same-side role connection;
- missing required nested member;
- monitor driving a member;
- invalid automatic role inversion;
- direct `Valid`/`Stream` protocol mismatch;
- implicit protocol/domain/latency adaptation;
- direct assignment to an inout without explicit drive state;
- multiple ordinary drivers;
- open-drain driving the inactive level;
- unsupported internal resolved net for a selected profile;
- digital inout connected implicitly to an electrical terminal;
- incompatible analog disciplines;
- contribution through a sense-only role;
- implicit conservative/signal-flow/discrete-real conversion;
- backend profile unable to represent a required member;
- flattening collision or unstable logical path;
- parameter envelope causing duplicate/invalid physical port layout.

## Verification plan

Verification spans:

- Scala type-negative fixtures;
- construction/IR mutation tests;
- exact role and driver diagnostics;
- protocol transfer/stall/bubble tests;
- digital inout high-Z/readback/contention/open-drain tests;
- black-box and hierarchy pass-through tests;
- flattened-name/ABI goldens;
- native-versus-flat SystemVerilog equivalence after approval;
- AMS discipline/topology/bridge positive and negative suites;
- ADC/DAC/PLL mixed-signal vertical slices;
- external library conformance;
- parameterized/nested interface scale and determinism;
- source-map, wrapper, agent, and metadata consistency.

Concurrent and temporal properties are verification-only. Any explicitly synthesized immediate assertion follows Increment 114.

## Exit criteria for Increment 124

Increment 124 is complete when:

1. ADR 0021 is accepted;
2. this plan and the machine-readable surface are committed;
3. the main roadmap is revision 1.15 and includes Increments 124-131;
4. Increment 14 and 15 explicitly own compile-candidate and v0.3 freeze integration;
5. digital inout is recorded as first-class but profile-aware;
6. existing digital/AMS/backend increments are synchronized;
7. no public API, frontend, IR, or backend implementation is claimed;
8. Markdown, JSON, contribution-policy, and Core CI checks pass.