# Nodal Scalable Register Factory Roadmap

**Revision:** 0.1  
**Created:** 2026-08-22  
**Status:** Active companion roadmap  
**Parent roadmap:** `docs/roadmap/nodal-development-todo.md`

## Purpose

Add a generic, scalable and reusable control/status register architecture to Nodal. A register definition must be independent of its access transport so the same register map can be attached to APB, AXI4-Lite, future standard buses, or user-defined internal buses without redefining register semantics.

The architecture should retain the convenience demonstrated by SpinalHDL `BusSlaveFactory` and `RegIf`, while separating register specification, hardware binding, bus transport, compiler IR, generated RTL, software artifacts, documentation and verification more strongly.

## Fixed architectural direction

- Define the programmer-visible register ABI once and adapt transports around it.
- Keep an immutable, bus-neutral `RegisterMap` separate from each physical `RegisterBlock` instance.
- A `RegisterBlock` has exactly one owning clock/reset domain.
- Bus adapters communicate with the register block through a canonical request/response access endpoint. APB phases and AXI channel details must not leak into register semantics.
- A register side effect occurs exactly once per committed canonical access, never merely because an address or valid signal was observed.
- Allow one register definition to instantiate multiple independent physical banks.
- Attaching multiple access transports to one physical bank requires an explicit arbiter/router; implicit multi-bus attachment is rejected.
- Use Scala 3 enums, opaque types, path-dependent types, extension methods and `given`/`using` where they improve type safety without adding normal-user ceremony.
- Do not make frontend reflection order, Scala field order or JVM object identity part of the register ABI.
- Model software access, hardware update behavior and simultaneous-access collision policy as orthogonal concepts rather than encoding every combination in one giant access-mode enumeration.
- Preserve source locations and stable diagnostic codes through register elaboration and compiler lowering.

## External register-description formats

### Canonical semantic model

The authoritative in-memory/compiler representation is the Nodal canonical Register IR. Different authoring frontends must normalize into this IR before validation, RTL generation or artifact generation.

Nodal must support more than one authoring source without allowing divergent semantics:

```text
Scala RegisterMap DSL ───────┐
SystemRDL 2.0 ───────────────┼──> Canonical Register IR ──> validation / RTL / artifacts
Nodal register YAML ─────────┤
IP-XACT register view ───────┘
```

### SystemRDL 2.0

SystemRDL 2.0 is the primary standards-based register-description interchange and should receive first-class import/export support. It is specifically intended to capture register behavior, hierarchy and address allocation and to act as a single source for hardware, verification, software and documentation views.

Policy:

- Support SystemRDL 2.0 import into canonical Register IR.
- Support deterministic SystemRDL 2.0 export for the semantic subset representable by the standard.
- Diagnose Nodal-only semantics that cannot be represented losslessly instead of silently dropping them.
- Maintain round-trip semantic tests for the supported subset.

### Nodal register YAML

YAML is useful for teams that want a short human-editable data file and for integration with scripts, spreadsheets and existing configuration workflows, but YAML itself is not the register-behavior standard.

Nodal should therefore support an optional, versioned `nodal-registers` YAML schema as a convenience frontend, not as an independent semantic authority.

Example directional syntax:

```yaml
schema: nodal-registers/v1
block: uart
bus_width: 32
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

Requirements:

- Version the schema explicitly.
- Require deterministic parsing and canonicalization.
- Preserve source filename and line/column diagnostics.
- Support JSON as an equivalent machine-oriented serialization of the same schema where practical.
- Never infer semantics merely from YAML key ordering.
- Provide migration diagnostics between schema revisions.
- Permit import from simple CSV/spreadsheet flows only through an explicit converter into the YAML/SystemRDL/canonical model; spreadsheet layout must not become a compiler contract.

### IEEE 1685-2022 IP-XACT

IP-XACT is broader IP packaging/integration metadata and includes memory maps/registers. Nodal should support register-view import/export after the canonical register model is stable.

Policy:

- Prefer SystemRDL for dedicated register authoring.
- Use IP-XACT primarily for SoC/IP integration interchange and packaging.
- Keep vendor extensions explicit and namespaced.
- Do not require XML/IP-XACT in ordinary Nodal register authoring.

### Generated downstream views

From one canonical Register IR, plan generators for:

- deterministic canonical JSON manifest;
- C/C++ headers;
- Rust register metadata/PAC input;
- CMSIS-SVD where applicable;
- SystemRDL 2.0;
- IEEE 1685-2022 IP-XACT register/memory-map views;
- UVM RAL/RALF;
- Markdown/HTML documentation;
- machine-readable ABI compatibility reports.

Generated formats are views. They must not independently redefine register behavior.

## Register address policy

### Fixed offsets are constants, not module parameters

For a normal published register map, explicit register offsets and field positions are part of the software-visible ABI. They should therefore lower to compile-time constants and readable HDL `localparam`/constant forms where useful.

Directional generated Verilog:

```verilog
localparam integer CONTROL_ADDR = 12'h000;
localparam integer STATUS_ADDR  = 12'h004;
```

Do **not** generate one externally overrideable HDL parameter for every register address by default:

```verilog
// Not the default Nodal policy
parameter CONTROL_ADDR = 'h000;
parameter STATUS_ADDR  = 'h004;
```

Making every offset overrideable weakens ABI stability, complicates verification and software generation, can create illegal overlaps after elaboration, and exposes implementation flexibility that most IP integrations do not need.

### What may be parameterized

Register geometry may remain symbolic only when variability is intentional in the source contract. Examples:

- register-block base address at a parent/interconnect integration boundary;
- number of channels/register-array elements;
- register-array stride;
- optional feature banks;
- data width when a reusable IP truly supports multiple widths;
- address-space size/range;
- explicitly parameterized submap placement.

Example:

```scala
val ChannelCount = Param[Int](4, min = 1, max = 16)

object DmaMap extends RegisterMap("dma"):
  array(
    base = 0x100,
    count = ChannelCount,
    stride = 0x20,
    element = ChannelMap
  )
```

The compiler must prove non-overlap and legal address ranges over the declared parameter envelope. If that proof cannot be made, elaboration must reject the map or require stronger constraints.

### Base addresses versus offsets

Keep reusable block definitions primarily offset-based. A block's internal offsets remain stable while system integration assigns the block base address.

```text
absolute address = integration base + register-map offset
```

This allows the same IP register map to be placed at different SoC addresses without turning every internal register into a parameter.

### ABI lock

Once a register map is published, Nodal should be able to emit and consume an ABI lock/manifest containing stable block/register/field identities, offsets, widths, policies and an ABI hash. CI should detect accidental register movement or semantic changes.

## Core API direction

Directional Scala API; exact spelling is frozen by a design gate before implementation:

```scala
object UartMap extends RegisterMap(
  name = "uart",
  dataWidth = 32,
  addressUnit = AddressUnit.Byte
):
  val control = register(0x00, "CONTROL")

  val enable = control.field(
    name = "ENABLE",
    dataType = Bool,
    bit = 0,
    reset = false,
    software = SoftwareAccess.RW
  )

  val status = register(0x04, "STATUS")

  val busy = status.field(
    name = "BUSY",
    dataType = Bool,
    bit = 0,
    software = SoftwareAccess.RO,
    hardware = HardwareAccess.Input
  )

  val irq = status.field(
    name = "IRQ",
    dataType = Bool,
    bit = 1,
    reset = false,
    software = SoftwareAccess.W1C,
    hardware = HardwareAccess.Settable,
    collision = Collision.SetDominatesClear
  )
```

A module binds the immutable definition to hardware state:

```scala
busDomain:
  val csr = RegisterBlock(UartMap)

  io.enable := csr.value(UartMap.enable)
  csr.input(UartMap.busy) := io.busy
  csr.setWhen(UartMap.irq, irqEvent)

  csr.attach(io.apb)
```

The same definition can be attached through another supported transport in another design:

```scala
busDomain:
  val csr = RegisterBlock(UartMap)
  csr.attach(io.axiLite)
```

## Software, hardware and collision semantics

Model independent axes.

Directional enums:

```scala
enum SoftwareAccess:
  case RO, RW, WO
  case W1C, W1S, W1T
  case W0C, W0S
  case RC, RS
  case WriteOnce
  case Reserved

enum HardwareAccess:
  case None
  case Input
  case Write
  case Settable
  case Clearable
  case Increment
  case Decrement
  case Pulse

enum Collision:
  case HardwareWins
  case SoftwareWins
  case SetDominatesClear
  case ClearDominatesSet
  case ErrorOnCollision
```

The semantic model must also cover reset priority, sticky fields, counters, saturating behavior, command/write pulses, read side effects, shadow registers, commit groups, coherent snapshots, aliases and set/clear/toggle aliases.

## Canonical access endpoint

The register block consumes a bus-neutral access protocol approximately equivalent to:

```scala
final case class RegisterRequest(
  operation: RegisterOperation,
  address: RegisterAddress,
  writeData: Bits,
  byteEnable: Bits,
  protection: Protection,
  transactionId: TransactionId
)

final case class RegisterResponse(
  readData: Bits,
  status: RegisterResponseStatus,
  transactionId: TransactionId
)
```

Exact API is deferred to the design gate.

Required semantic rule: read/write side effects occur only when the adapter marks a request committed exactly once.

## Transport adapter architecture

Directional Scala 3 type class:

```scala
trait RegisterTransport[B]:
  def capabilities(bus: B): RegisterTransportCapabilities
  def connect(bus: B, endpoint: RegisterAccessPort): Unit
```

Built-in implementations should eventually include APB3/APB4 and AXI4-Lite. Additional transports are plugins using only public conformance contracts.

### APB responsibilities

The APB adapter owns:

- setup/access phase sequencing;
- `PREADY` and wait states;
- `PSTRB` where present;
- `PSLVERR`;
- `PPROT`;
- conversion of one completed APB transfer into exactly one canonical committed access.

### AXI4-Lite responsibilities

The AXI4-Lite adapter owns:

- independent AW and W channel buffering/pairing;
- AR/R and B channel backpressure;
- `WSTRB`;
- `ARPROT`/`AWPROT`;
- `RRESP`/`BRESP`;
- outstanding-request policy;
- conversion of each legal completed transaction into exactly one canonical committed access.

A register write must not occur simply because AW or W was independently accepted.

## Partial-write and multiword policy

The design gate must freeze:

- byte-enable semantics for every software access type;
- behavior of fields crossing byte lanes;
- reserved-bit read/write policy;
- behavior when a transport lacks required byte enables or error signalling;
- endianness/address-unit rules;
- unsupported access response behavior.

For registers wider than the transport, support explicit policies such as:

```scala
MultiwordAccess.NonAtomic
MultiwordAccess.SnapshotOnFirstRead
MultiwordAccess.ShadowThenCommit
MultiwordAccess.AtomicProtocol
MultiwordAccess.Rejected
```

Never silently promise atomicity.

## Clock/reset and CDC rules

- Every physical `RegisterBlock` belongs to one Nodal clock/reset domain.
- Hardware status arriving from another domain requires an explicit CDC primitive.
- Commands leaving the register domain require explicit pulse, handshake or FIFO CDC semantics.
- Multi-bit asynchronous status should use coherent snapshot/handshake structures when coherence matters.
- The compiler should diagnose direct unsafe cross-domain register bindings early.

## Multiple transports to one physical block

Reject implicit double attachment:

```scala
csr.attach(io.apb)
csr.attach(io.debugAxi) // error by default
```

Require an explicit access arbiter/router:

```scala
val access = RegisterAccessArbiter(
  primary = ApbAdapter(io.apb),
  debug = Axi4LiteAdapter(io.debugAxi),
  priority = AccessPriority.Primary
)

csr.attach(access)
```

Arbitration, security, starvation, ordering and same-cycle collision semantics must therefore be deliberate and reviewable.

## Hierarchical register maps

Support nested maps, register files, arrays, repeated channels, sparse spaces, memories/windows, aliases and relocatable submaps.

Directional example:

```scala
val map = RegisterMap("soc"):
  submap(0x0000, UartMap, name = "uart0")
  submap(0x1000, UartMap, name = "uart1")
  submap(0x2000, TimerMap, name = "timer")

  array(
    base = 0x4000,
    count = channels,
    stride = 0x100,
    element = ChannelMap
  )
```

Backend decode strategy may evolve independently of the visible ABI.

## ABI compatibility classification

At minimum classify changes such as:

| Change | Default classification |
| --- | --- |
| Add register in intentionally reserved space | compatible when constraints remain satisfied |
| Add field in explicitly reserved bits | policy-dependent |
| Move existing register | breaking |
| Change field bit range | breaking |
| Change reset value | behavioral change |
| Change software access policy | API/security change |
| Change hardware/software collision policy | behavioral change |
| Reuse removed address | breaking unless explicitly versioned |

Plan CLI support similar to:

```text
nodal registers diff old.json new.json
```

## Verification contract

Generate reusable checks for:

- reset values;
- legal/illegal addresses;
- reserved bits;
- software read/write policies;
- partial writes and byte enables;
- response stability under backpressure;
- exactly one side effect per committed transfer;
- no side effect from incomplete APB/AXI transactions;
- W1C/W1S/RC/etc. semantics;
- command pulse width;
- software/hardware collision policy;
- multiword snapshots/commit behavior;
- custom transport adapter conformance;
- parameter-envelope address collision proofs.

## Planned increments

These are independently schedulable roadmap increments. Their exact placement in the parent roadmap may be renumbered if intervening work is added; IDs below are the proposed current sequence after Increment 78.

- [ ] **Increment 79 — Register-block architecture gate and public API contracts**
  - Freeze `RegisterMap` versus `RegisterBlock`, typed field handles, software/hardware/collision/reset semantics, address/bit/byte-enable/endianness/error/multiword rules, hierarchical maps, access endpoint, `RegisterTransport[B]`, clock/reset ownership, CDC requirements, multi-transport arbitration, fixed-versus-parameterized address policy, ABI lock rules, and positive/negative Scala fixtures.
  - Record explicit comparisons against current SpinalHDL `BusSlaveFactory`/`RegIf` concepts without taking a dependency on SpinalHDL.

- [ ] **Increment 80 — Canonical Register IR, verifier and elaboration**
  - Implement canonical register/block/field IR; address expressions; stable identities; source maps; software/hardware operations; storage/side-effect semantics; overlap/range checks; typed hardware binding; parameter-envelope proofs; clock-domain validation; deterministic canonical JSON manifest; and ABI hash.

- [ ] **Increment 81 — SystemRDL 2.0 and Nodal YAML frontends**
  - Implement first-class SystemRDL 2.0 import for the agreed semantic subset plus deterministic export; implement versioned Nodal register YAML/JSON parsing into the same canonical Register IR; retain source diagnostics; validate equivalent Scala/SystemRDL/YAML descriptions normalize to semantically identical IR; provide explicit loss diagnostics for unsupported round trips.

- [ ] **Increment 82 — APB and AXI4-Lite register transports with RTL lowering**
  - Implement APB3/APB4 and AXI4-Lite adapters, canonical committed-access conversion, read/write decode, storage and side effects, partial writes, protection/error mapping, AXI AW/W pairing, response backpressure, configurable outstanding policy, Verilog generation, open-source simulation/lint/synthesis checks, and protocol/register semantic verification.

- [ ] **Increment 83 — Register artifact generation, IP-XACT and ABI compatibility**
  - Generate C/C++ headers, Rust metadata, CMSIS-SVD where applicable, SystemRDL, IEEE 1685-2022 IP-XACT register/memory-map views, UVM RAL/RALF, Markdown/HTML, and canonical manifests from the same IR; implement `nodal registers diff`; add ABI lock enforcement and golden artifact tests.

- [ ] **Increment 84 — Register hierarchy scale, custom transports and conformance kit**
  - Implement large hierarchical maps, arrays/repeated channels, windows/memories, explicit multi-transport arbitration, one example external custom-bus adapter using public APIs only, transport capability negotiation, reusable adapter conformance tests, register-map authoring conformance suites, large-map performance benchmarks, and compatibility regressions.

## Acceptance principles

The register factory track is not complete merely when APB or AXI4-Lite RTL works. It is complete only when:

1. the same canonical map can be authored or imported through supported frontends and yields the same semantics;
2. APB/AXI/custom transport details do not leak into register definitions;
3. address/field collisions and unsupported transport capabilities are rejected deterministically;
4. software and verification artifacts derive from the same canonical IR as RTL;
5. fixed published offsets remain stable unless an explicit ABI-breaking change is accepted;
6. parameterized geometry is proven safe over its declared parameter envelope;
7. register side effects occur exactly once per committed bus transaction;
8. CDC and multi-access ownership are explicit rather than inferred silently.

## Standards and implementation references

- Accellera SystemRDL 2.0, Register Description Language.
- IEEE 1685-2022 IP-XACT and Accellera supplemental material/user guidance.
- Arm CMSIS-SVD for applicable software/debug descriptions.
- Current SpinalHDL `BusSlaveFactory` and `RegIf` as capability/API comparison references only; Nodal remains independent.
- Current stable Scala 3 language features as the frontend type-system/tooling baseline.
