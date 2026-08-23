# ADR 0021: Use unified Struct, Interface, Role, AMS-terminal, and resolved-inout semantics

- **Status:** Accepted
- **Date:** 2026-08-23
- **Scope:** Directionless value aggregates, connectivity interfaces, protocol roles, `Valid`/`Stream`, master/slave convenience, monitor access, digital `inout`, resolved nets, tri-state I/O, analog conservative terminals, signal-flow analog values, mixed-signal interfaces, backend flattening/native interfaces, ABI manifests, and verification

## Context

Nodal needs reusable structured values and reusable connectivity definitions across portable Verilog, Verilog-A, Verilog-AMS, and a possible future SystemVerilog/SystemVerilog-AMS backend.

Several concepts must not be conflated:

- a pixel, command, register payload, or memory element is a value that may be stored and copied;
- a ready/valid channel is a connectivity protocol with opposite ownership for some members;
- a bus may contain several request, response, interrupt, clock, reset, and sideband channels with different directions;
- an electrical terminal is a conservative physical connection governed by potential/flow and network equations, not a normal digital input or output;
- a digital bidirectional pin is a resolved net with read, drive, high-impedance, and contention semantics;
- a SystemVerilog `interface` is one possible backend representation, while portable Verilog requires deterministic flattened ports.

A single Bundle-like class with directions embedded in fields would reduce reuse, make nesting difficult, and leak target syntax into the public language. Conversely, calling every aggregate an interface would incorrectly make ordinary storable values behave like connectivity objects.

Nodal also requires first-class digital `inout` support. Restricting users to handwritten wrappers would break source provenance and make black-box, pad, hierarchical pass-through, protocol-interface, and mixed-signal integration inconsistent. General internal tri-state logic is nevertheless less portable than boundary I/O and must remain capability checked rather than silently rewritten.

## Decision

Nodal adopts the binding rule:

> **Values are directionless `Struct`s, connectivity is an `Interface`, access is selected by a named `Role`, physical bidirectionality is explicit, and every backend preserves one logical interface ABI.**

The exact Scala syntax is evaluated in Increment 14 and frozen in Increment 15. This ADR freezes the semantic separation and backend obligations, not every public spelling.

## `Struct`: directionless hardware value

A `Struct` is a product/record value. It may contain scalar values, enums, other `Struct`s, and structural `Vec`s. It may be:

- used in expressions and exact assignments;
- stored in `Reg` or `Mem` where its leaves are storable;
- used as a parameterized payload;
- carried by `Valid`, `Stream`, or a user protocol;
- flattened or represented as a target-native value aggregate according to a backend layout profile.

Candidate direction:

```scala
final case class Pixel(width: Int) extends Struct:
  val red   = UInt(width)
  val green = UInt(width)
  val blue  = UInt(width)
```

A `Struct` does not contain port directions, protocol roles, conservative terminals, or nested connectivity interfaces. Field order and identity are deterministic and source located; Scala reflection order or JVM identity never defines the ABI.

## `Interface`: connectivity contract

An `Interface` groups module-boundary connectivity. It may contain:

- plain digital value leaves;
- `Valid[T]` and `Stream[T]` channels;
- nested `Interface`s;
- arrays or symbolic repeated interface members;
- clock/reset requirements and domain metadata;
- digital resolved `inout` leaves;
- conservative analog terminals;
- signal-flow analog values;
- explicit mixed-signal bridge endpoints;
- protocol, ordering, latency, security, and documentation metadata.

An `Interface` is not a storable `Data` value. It cannot be placed directly in a register or memory and cannot participate in arithmetic. Its value-carrying members may use `Struct` payloads.

Candidate direction:

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

Every exported interface selects exactly one role. An internal interface may remain unbound while being assembled, but construction close rejects unresolved exported direction/access.

## Named `Role` system

`master` and `slave` are convenience roles, not the only architectural role names. The role model supports protocol-appropriate names such as:

- `master` / `slave`;
- `source` / `sink`;
- `initiator` / `target`;
- `controller` / `peripheral`;
- `device` / `environment`;
- `monitor`;
- user-defined stable named roles.

A role assigns access to leaves and nested interfaces. Initial access categories are conceptually:

- `in` / `out` for ordinary digital or directional signal-flow values;
- `master` / `slave` for nested protocol interfaces;
- `observe` for read-only digital/protocol monitoring;
- `connect`, `sense`, and `contribute` for conservative analog terminals;
- `read`, `drive`, and `connect` for digital resolved inout endpoints.

The exact public vocabulary is frozen by compile candidates. The IR records role identity and per-member access independently of source sugar.

A role may be declared as the exact inverse of another only when every member has a well-defined complementary digital direction. Conservative analog access, monitor access, shared/open-drain endpoints, and other non-complementary permissions require explicit role definitions and are not automatically inverted.

## `Valid` and `Stream`

Nodal keeps the canonical transport names:

- `Valid[T]`: payload plus validity; bubbles are permitted; no backpressure;
- `Stream[T]`: ordered ready/valid transport with backpressure.

For `master(Stream[T])`:

- `valid` and `payload` are driven outward;
- `ready` is received inward.

For `slave(Stream[T])`, those directions reverse.

For `master(Valid[T])`, `valid` and `payload` are driven outward; `slave(Valid[T])` receives them.

`Flow` is not a second core semantic type. A compatibility alias may be provided by an optional library if justified, but canonical manifests and IR use `Valid`.

Plain, `Valid`, and `Stream` connections are exact. Protocol conversion, buffering, CDC, latency insertion, or field adaptation requires an explicit adapter or primitive.

## Digital `inout` and resolved nets

Nodal supports first-class digital bidirectional ports and nets.

The preferred source model separates sensing from driving:

```scala
val gpio = inout(Bits(8))

val sampled = gpio.read
gpio.drive(writeData, enable = outputEnable)
```

The exact handle name is deferred, but the semantic endpoint contains:

- an exact typed read view;
- zero or more explicit drivers;
- a drive-enable or drive-state contract;
- a resolved net identity;
- source and hierarchy provenance;
- optional I/O technology metadata;
- a backend capability requirement.

Direct variable-style assignment to an `inout` endpoint is not the default because it hides high-impedance and enable behavior.

### Initial resolved values

The digital inout model includes at least `0`, `1`, `Z`, and contention/unknown behavior. Resolution is deterministic:

- no active driver yields high impedance unless a declared pull/resolution model applies;
- one active compatible driver yields its value;
- conflicting active drivers produce the declared unknown/contention result in simulation and verification;
- ordinary single-driver signals do not acquire resolved-net semantics.

Drive strengths, charge storage, arbitrary switch-level primitives, and vendor I/O electrical behavior require separate capability contracts and are not inferred.

### Drive modes

The architecture distinguishes at least:

- push-pull tri-state drive;
- open-drain/open-collector drive;
- open-source drive where supported;
- read-only observation;
- pure hierarchical pass-through.

Open-drain endpoints may drive only the active level or `Z`. Illegal active-high drive is rejected before backend emission. Pull-up/pull-down intent and pad-cell mapping are explicit metadata or adapters.

### Boundary and internal use

Native digital inout is required for:

- top-level chip/FPGA pins;
- black-box inout pins;
- deterministic hierarchical pass-through to a top-level pin or black box;
- mixed-signal wrappers;
- protocol interfaces such as GPIO, I2C, MDIO, memory DQ/DQS, and selected PHY/pad interfaces.

For portable synthesizable internal design, Nodal prefers a split tri-state carrier containing read data, write data, and output enable, then an explicit boundary adapter that collapses it to the physical inout net.

Internal resolved nets and multiple internal tri-state drivers remain legal language concepts only under an explicit backend/tool capability profile. The portable synthesis profile may initially restrict native tri-state resolution to top-level/black-box/pad boundaries. It must reject unsupported internal resolved nets rather than silently convert them to a mux. A separately selected transform may convert mutually exclusive drivers only with explicit semantics and retained proof evidence.

### Type and shape rules

Initial digital inout support covers scalar and packed finite-width digital leaves with an exact resolved representation. Packed enum or `Struct` views may be supported through deterministic flattening, but every physical leaf remains a resolved net. Unpacked memories, arbitrary dynamic arrays, protocol objects, and conservative analog terminals are not digital inout values.

Multiple drivers are legal only on declared resolved nets or conservative analog connectivity. Multiple drivers on ordinary `Signal`, `Reg`, `Valid`, or `Stream` leaves remain errors.

## Conservative AMS terminals

A conservative physical terminal is distinct from digital `inout`.

Nodal retains nature/discipline semantics and refines the model around:

- `Terminal[D]`: a module/interface boundary physical connection;
- `Node[D]`: an internal topological connection;
- `Branch[D]`: an ordered relation between nodes/terminals;
- potential and flow access functions;
- contributions/equations and network conservation.

Exact public names remain subject to the v0.3 gate, but boundary terminals and internal nodes must remain distinguishable in IR and diagnostics.

An electrical terminal is not a four-state digital wire and is not read or driven through a tri-state enable. Its topology, access, contribution permissions, resolution, and connect-rule behavior are governed by its discipline.

## Directional analog signal flow

Nodal separately supports directional analog or real-number signal-flow values, such as sampled voltage, temperature, frequency, or model-control quantities. These may use ordinary `in`/`out` roles because they are information-flow signals rather than conservative network terminals.

No implicit conversion is permitted among:

- conservative terminals;
- analog signal-flow values;
- discrete real/wreal-like nets;
- finite-width digital values.

Typed bridges own sampling, quantization, threshold, hysteresis, delay, transition, hold, interpolation, event, resolution, and domain semantics.

## Mixed-signal interfaces

One logical `Interface` may contain digital channels and conservative terminals:

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

A mixed-signal role explicitly declares analog access. Analog access is not inferred by reversing a digital role.

## Continuous-time islands and explicit bridges

The compiler builds a mixed-domain graph containing:

- continuous-time conservative islands;
- directional analog signal-flow regions;
- digital clock/reset domains;
- explicit sampling, event, ADC, DAC, connect-module, and connect-rule bridges.

Every island and bridge preserves source locations, physical dimensions, domain provenance, event ordering, latency, and backend capability requirements.

Analog/digital bridges are scheduling and optimization barriers unless a separately approved transformation contract proves movement or approximation safe. `Backend.Auto` never replaces authoritative AMS semantics with an FPGA approximation; ADR 0011 remains the only AMS-to-FPGA approximation path.

## Canonical Interface IR and ABI

Nodal IR records the logical interface independently of target layout:

- stable interface type and member identities;
- `Struct` versus `Interface` kind;
- selected role and per-member access;
- nested role applications;
- type, shape, signedness, and encoding;
- protocol, ordering, latency, and capacity metadata;
- clock/reset domain provenance;
- digital net kind and resolution/drive mode;
- analog discipline, terminal, branch, sense, and contribution metadata;
- source paths and spans;
- parameter formulas and legal envelopes;
- backend layout and wrapper mappings.

The compiler emits a deterministic interface ABI manifest mapping logical members to target ports/interfaces/terminals. This manifest supports wrappers, simulation, cocotb/UVM integration, IP-XACT, waveform correlation, source maps, compatibility checking, and cross-backend equivalence.

Changing a logical member, role access, direction, type, shape, protocol, domain, discipline, drive mode, or flattening ABI is classified explicitly rather than hidden by generated names.

## Backend lowering

### Portable Verilog

All `Struct` and `Interface` members are deterministically flattened according to canonical ABI rules. `Stream` and `Valid` become named leaf ports. Supported digital inout endpoints emit net-typed `inout` ports and explicit width-safe tri-state assignments. Analog members are rejected by the digital-only profile.

Example concept:

```verilog
inout  wire [7:0] gpio;
assign gpio = gpio_output_enable ? gpio_write_data : {8{1'bz}};
assign gpio_read_data = gpio;
```

### Verilog-A

Analog-only interfaces flatten to discipline-qualified terminals and supported directional signal-flow declarations. Digital-only members are rejected unless the selected analog profile explicitly represents them.

### Verilog-AMS

Digital leaves, resolved inouts, conservative terminals, signal-flow values, domains, bridges, connect modules, and connect rules lower into one deterministic mixed-signal hierarchy while preserving the logical interface manifest.

### SystemVerilog

After the separate future-backend research/design gate, a selected SystemVerilog profile may emit native `interface` definitions and `modport`s. The native representation must remain semantically and ABI equivalent to deterministic flattened ports. A per-instance flatten override and wrapper generation may be provided, but native SystemVerilog syntax never defines Nodal semantics.

A future SystemVerilog-AMS profile follows the same rule and receives a separate capability gate.

## Connection and hierarchy rules

- direct connections require the same interface type or an approved exact-compatible contract;
- complementary roles must match; monitor endpoints cannot drive;
- nested interfaces apply roles recursively and deterministically;
- field dropping, renaming, resizing, signedness conversion, protocol conversion, domain crossing, latency insertion, discipline conversion, and analog/digital conversion are never implicit;
- hierarchical digital inout pass-through preserves one resolved-net identity when legal;
- conservative connectivity preserves topology rather than copying values;
- unresolved required members, role conflicts, illegal inversions, multiple ordinary drivers, invalid inout modes, incompatible disciplines, and unapproved conversions are source-located errors;
- interface arrays and symbolic dimensions preserve one parameterized ABI where the selected backend supports it.

## Verification and quality gates

Required checks include:

- role completeness and complementary connection;
- monitor read-only enforcement;
- nested role expansion and stable flattening;
- exact type/shape/protocol/domain compatibility;
- no accidental storage of interfaces;
- one-driver enforcement for ordinary leaves;
- resolved-net driver inventory and contention scenarios;
- high-impedance/readback/open-drain behavior;
- no unsupported internal tri-state in portable synthesis;
- hierarchy pass-through and black-box inout connectivity;
- conservative terminal discipline/topology/access legality;
- no implicit analog/digital or conservative/signal-flow conversion;
- backend-native versus flattened interface equivalence;
- stable logical-to-target ABI manifests and source maps;
- Verilator/Icarus simulation and Yosys synthesis/equivalence where the selected profile supports the construct;
- AMS compile/simulation and differential fixtures for mixed-signal interfaces;
- external-library use through public contracts only.

Generated temporal properties remain verification-only under ADR 0014 and Increment 114. Explicitly synthesized immediate assertions follow the separate assertion policy.

## Consequences

### Positive

- value types remain reusable across registers, memories, protocols, and libraries;
- master/slave convenience scales to arbitrary multi-role protocols;
- one logical interface can target flattened Verilog, native SystemVerilog, or Verilog-AMS;
- digital inout, black-box pins, pad wrappers, and hierarchy pass-through are first-class and source mapped;
- conservative AMS terminals remain physically correct instead of being treated as digital directions;
- mixed-signal wrappers, verification agents, IP packaging, and ABI checking share one model;
- backend syntax and tool limitations do not leak into the source API.

### Costs

- the compiler needs a separate interface kind, role expansion, resolution graph, topology graph, and ABI manifest;
- digital inout requires four-state/resolution-aware simulation and capability checking;
- native SystemVerilog interfaces add wrapper and compile-order concerns;
- AMS roles and bridges require richer verification than simple input/output direction;
- users must state adapters and conversions that legacy HDLs often leave implicit.

## Rejected alternatives

### Call every aggregate an Interface

Rejected because storable/copyable values and connectivity objects have different semantics.

### Embed directions inside Struct fields

Rejected because it reduces reuse and makes nested protocol composition brittle.

### Support only master/slave

Rejected because monitors, request/response subchannels, controller/peripheral protocols, conservative terminals, and mixed-signal roles need more than one reversible pair.

### Treat analog terminals as digital inout

Rejected because conservative potential/flow topology and four-state tri-state resolution are different semantic systems.

### Require handwritten wrappers for digital inout

Rejected because it loses type safety, provenance, ABI metadata, hierarchy checks, and deterministic backend behavior.

### Silently lower every internal inout to a mux

Rejected because multiple-driver resolution and contention behavior are not generally equivalent to priority or exclusive muxing.

### Make SystemVerilog interface syntax the source model

Rejected because portable Verilog and Verilog-AMS remain mandatory targets and SystemVerilog support is separately capability gated.

## Follow-up roadmap

The detailed staged plan and machine-readable architecture candidate are:

- `docs/roadmap/interface-role-inout-ams-v0.1-plan.md`
- `docs/roadmap/interface-role-inout-ams-v0.1-surface.json`

Increment 124 records this architecture and roadmap contract. Increment 14 compiles candidate public forms and Increment 15 freezes their v0.3 integration. Existing core/digital/AMS increments implement the foundational semantics, while Increments 125-131 provide cross-layer implementation and qualification closure.