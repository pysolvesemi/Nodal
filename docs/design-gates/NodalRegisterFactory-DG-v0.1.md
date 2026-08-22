# NodalRegisterFactory-DG v0.1

**Status:** Approved  
**Scope:** public-api  
**API version:** register-factory 0.1  
**Decision:** Freeze a register-definition-first API with map-owned field handles and transport adapters  
**Approved by:** Repository owner instruction to continue with Increment 116 on 2026-08-22  
**API manifest SHA-256:** `dadaef41722c7e799cf760178e62045d127778f50513cae09c9ded64e8789396`  
**Diagnostics manifest SHA-256:** `df17fbf2c8377fd6a25bfb3b5700c629bf8c50bb433d278a6bce19c4d4c2a7b4`

## Decision

Nodal register-factory public API v0.1 is frozen by this gate. The authoritative inventories are:

- [`register-factory-api-v0.1.json`](../../core/scala/api/register-factory-api-v0.1.json);
- [`register-factory-diagnostics-v0.1.json`](../../core/scala/api/register-factory-diagnostics-v0.1.json);
- [`register-factory-v0.1-surface.json`](../roadmap/register-factory-v0.1-surface.json);
- [`tests/api/fixtures/increment116/manifest.json`](../../tests/api/fixtures/increment116/manifest.json).

The binding architectural rule remains:

> **Define the register ABI once, normalize every authoring source into one canonical Register IR, bind hardware explicitly, and adapt transports without changing register semantics.**

The Scala declarations and positive fixtures compile, while all construction, validation, canonical Register IR, bus timing, RTL lowering, and artifact generation remain intentionally inert. This gate freezes source forms, type distinctions, map ownership, policy names, transport extension boundaries, diagnostic identities, and semantic obligations. It must not be read as a claim that APB, AXI4-Lite, YAML, SystemRDL, IP-XACT, Verilog generation, or software artifact generation is implemented.

## Comparison baseline and selected direction

The comparison baseline used for this gate is Scala 3.8.4 and SpinalHDL 1.14.2. Mature SpinalHDL facilities demonstrate two valuable capabilities:

- a common register mapping interface over many concrete bus protocols;
- a higher-level register interface with address allocation, overlap checking, many software access modes, and documentation generation.

Nodal retains those goals but rejects a bus-first public model in which a concrete APB/AXI object creates the programmer-visible register schema. The selected API instead separates:

```text
immutable RegisterMap
    -> physical RegisterBlock
    -> opaque committed-access endpoint
    -> RegisterTransport[B]
```

The resulting differences are deliberate:

- the register ABI exists before any bus is selected;
- concise literal forms use Scala 3 union types rather than implicit conversions or mandatory compiler flags;
- field handles are owned by one exact map instance/type and cannot be mixed accidentally;
- software access, hardware update behavior, and collision priority are independent axes;
- the canonical access endpoint is visible to transport adapters but cannot be constructed by ordinary users;
- APB/AXI channel and phase semantics are adapter responsibilities;
- generated files are views of one future canonical Register IR, never alternative authorities.

## Default import and package boundary

Normal source uses:

```scala
import nodal.*
```

The frozen surface is in the public `nodal` package. Ordinary register-map authors and transport-adapter authors do not import frontend, compiler, backend, simulator, bootstrap, or `nodal.internal` packages.

## Immutable RegisterMap

A map is declared independently of live hardware and transport timing:

```scala
object UartMap extends RegisterMap(
  name = "uart",
  dataWidth = 32,
  addressUnit = AddressUnit.Byte,
  endianness = Endianness.Little,
  illegalAccess = IllegalAccessPolicy.ErrorResponse
):
  val control = register(0x00, "CONTROL")

  val enable = control.field(
    name = "ENABLE",
    dataType = Bool,
    bits = 0,
    software = SoftwareAccess.RW,
    reset = false.B
  )
```

`RegisterMap` is an immutable declaration contract. Its construction methods are protected so definitions are authored inside a map rather than mutated by unrelated code after publication.

The frozen map-level settings are:

```scala
AddressUnit.Byte
AddressUnit.Word

Endianness.Little
Endianness.Big

IllegalAccessPolicy.ErrorResponse
IllegalAccessPolicy.ReadZeroIgnoreWrite
IllegalAccessPolicy.ReadOnesIgnoreWrite
```

Published maps use explicit register offsets and field positions by default. `RegisterOffset` is a Scala 3 union of `Int`, `Long`, and `BigInt` constant forms, so literals require no implicit conversion, language-feature import, or project compiler flag. It deliberately excludes symbolic `Param` values. Symbolic variation is expressed only through the separately typed `RegisterCount = Int | Expr[Integer]` geometry contract.

### Register and field declarations

The frozen forms are:

```scala
register(offset, name, doc = "", multiword = MultiwordAccess.NonAtomic)
register.field(
  name,
  dataType,
  bits,
  software,
  reset = FieldReset.Unspecified,
  hardware = HardwareAccess.None,
  collision = CollisionPolicy.HardwareWins,
  partialWrite = PartialWritePolicy.RequireWholeField,
  doc = ""
)
register.reserved(bits, doc = "")
reserved(offset, size, doc = "")
```

A one-bit selection may use an `Int`; a range uses the concise form:

```scala
15 downto 0
```

Reset values use ordinary typed Nodal expressions without requiring `Option` ceremony. The semantic verifier introduced later must require reset expressions to be elaboration/static values permitted by the register contract; a dynamic hardware reset value carries `NODAL-REG-SOURCE-001`.

### Orthogonal policy axes

Software access is frozen as:

```scala
SoftwareAccess.RO
SoftwareAccess.RW
SoftwareAccess.WO
SoftwareAccess.W1C
SoftwareAccess.W1S
SoftwareAccess.W1T
SoftwareAccess.W0C
SoftwareAccess.W0S
SoftwareAccess.RC
SoftwareAccess.RS
SoftwareAccess.WriteOnce
SoftwareAccess.Reserved
```

Hardware behavior is frozen independently:

```scala
HardwareAccess.None
HardwareAccess.Input
HardwareAccess.Write
HardwareAccess.Settable
HardwareAccess.Clearable
HardwareAccess.Increment
HardwareAccess.Decrement
HardwareAccess.Pulse
```

Collision priority is frozen independently:

```scala
CollisionPolicy.HardwareWins
CollisionPolicy.SoftwareWins
CollisionPolicy.SetDominatesClear
CollisionPolicy.ClearDominatesSet
CollisionPolicy.ErrorOnCollision
```

Partial-write policy is:

```scala
PartialWritePolicy.Allow
PartialWritePolicy.Reject
PartialWritePolicy.RequireWholeField
PartialWritePolicy.RequireWholeRegister
```

Wide-register policy is:

```scala
MultiwordAccess.NonAtomic
MultiwordAccess.SnapshotOnFirstRead
MultiwordAccess.ShadowThenCommit
MultiwordAccess.ProtocolAtomic
MultiwordAccess.Rejected
```

The presence of a name does not imply that every combination is legal. Increment 117 owns legality matrices and stable diagnostics. In particular, reserved software fields cannot silently acquire active hardware storage semantics.

## Hierarchy, repetition, windows, aliases, and groups

The frozen map-composition forms are:

```scala
submap(offset, map, name)
array(base, count, stride, element, name)
window(offset, size, name)
alias(offset, target, name, software)
snapshot(name, fields*)
commitGroup(name, fields*)
```

A repeated array count may be either a static `Int` or an explicit symbolic `Expr[Integer]`, including a map `Param[Integer]`. The count has a different type from `RegisterOffset`; therefore a symbolic count cannot be passed accidentally as a fixed register offset.

Submap, array, window, alias, snapshot, and commit objects remain map-owned typed handles. Their exact lowering, allocation, coherence, and artifact representation are deferred to Increments 117-123.

## Physical RegisterBlock and map-owned handles

A physical realization is created in one lexical clock/reset domain:

```scala
busDomain:
  val registers = RegisterBlock(UartMap)
```

`RegisterBlock[M]` retains its exact map value `M`. All field and group arguments are path dependent:

```scala
field: registers.map.Field[A]
group: registers.map.SnapshotGroup
```

Consequently, this is rejected by Scala before semantic lowering:

```scala
val uart = RegisterBlock(UartMap)
uart.value(ChannelMap.value)
```

The frozen binding operations are:

```scala
registers.value(field)
registers.input(field) := value
registers.setWhen(field, condition)
registers.clearWhen(field, condition)
registers.incrementWhen(field, condition)
registers.decrementWhen(field, condition)
registers.pulse(field)
registers.writeEvent(field)
registers.capture(group, condition)
registers.commit(group, condition)
```

These forms freeze typed intent only. They do not yet allocate state, insert CDC, define APB/AXI transactions, or emit RTL.

`value` reads software-visible storage/control state. `input` binds a hardware-observed value. Set/clear/increment/decrement operations express explicit hardware update capability. `pulse` and `writeEvent` return the existing Nodal `Pulse` category so later domain provenance and CDC rules remain applicable.

## Transport extension boundary

A transport is selected through a Scala 3 type class:

```scala
trait RegisterTransport[B]:
  def capabilities(bus: B): RegisterTransportCapabilities
  def connect(bus: B, endpoint: RegisterAccessPort): Unit
```

A transport declares:

```scala
RegisterTransportCapabilities(
  dataWidth,
  addressWidth,
  byteEnable,
  errorResponse,
  protection,
  backpressure,
  maxOutstanding = 1,
  inOrder = true
)
```

A block attaches through contextual lookup:

```scala
given RegisterTransport[MyBus] = ...
registers.attach(myBus)
```

No string bus name, concrete adapter lookup, reflection scan, or implicit first matching protocol is frozen. Missing transport evidence is a Scala type error.

`RegisterAccessPort` is a public type only because an external transport implementation must name it. Its constructor remains inaccessible, and ordinary register authors do not drive or inspect it. Increment 120 freezes and implements the complete committed request/response protocol and adapter conformance behavior.

One direct attachment is the semantic default. A second direct attachment is not another independent access port; it requires the explicit arbiter/router planned for Increment 120 and carries `NODAL-REG-TRANSPORT-002` otherwise.

## Clock/reset and crossing contract

Each physical `RegisterBlock` belongs to exactly one lexical `ClockDomain`. Creating one outside a resolved domain carries `NODAL-REG-DOMAIN-001` once the semantic frontend is implemented.

The register factory never silently crosses domains. Hardware values entering or leaving another domain continue to use the frozen `Cdc`/`Rdc`, pulse, handshake, FIFO, and snapshot semantics. A cross-domain binding that lacks an explicit legal transfer carries `NODAL-REG-CDC-001`.

## Authoring files and generated HDL

This public Scala gate does not freeze parser APIs for SystemRDL or YAML. It freezes their relationship to the Scala surface:

- Scala, supported SystemRDL 2.0, and versioned `nodal-registers/v1` YAML/JSON are alternative authoring frontends;
- one source is authoritative for one map;
- all normalize into the same future canonical Register IR;
- equivalent maps must produce equivalent ABI hashes;
- IP-XACT remains a later integration view;
- spreadsheets and CSV remain explicit converter inputs.

Fixed register offsets, field positions, masks, reset values, and access encodings remain non-overridable generated-Verilog `localparam`s/constants. Only explicit Nodal symbolic architecture may become an HDL `parameter`. The default register block decodes relative offsets; an absolute-base wrapper is explicit.

## Diagnostic contract

Every semantic diagnostic requires a primary source span with path, line, column, and span. Cross-hierarchy and transport errors additionally retain register-map/block/field paths and transport or domain identity when available.

| Code | Meaning |
| --- | --- |
| `NODAL-REG-MAP-001` | Two register objects overlap at one address range. |
| `NODAL-REG-MAP-002` | Two fields overlap inside one register. |
| `NODAL-REG-BIND-001` | A field from another map is used with this block. |
| `NODAL-REG-BIND-002` | A hardware binding value has the wrong data type. |
| `NODAL-REG-BIND-003` | A hardware operation is applied to an incompatible field type. |
| `NODAL-REG-DOMAIN-001` | A physical register block has no resolved clock/reset domain. |
| `NODAL-REG-TRANSPORT-001` | No transport adapter exists for the selected bus type. |
| `NODAL-REG-TRANSPORT-002` | Multiple transports attach directly without an arbiter/router. |
| `NODAL-REG-PARAM-001` | A symbolic value is used where a fixed register offset is required. |
| `NODAL-REG-SOURCE-001` | A register ABI property depends on a dynamic hardware value. |
| `NODAL-REG-POLICY-001` | Software, hardware, collision, partial-write, or wide-access policy combination is illegal. |
| `NODAL-REG-CDC-001` | A hardware binding crosses domains without an explicit legal transfer. |

Type-level fixtures prove the map ownership, transport evidence, fixed-offset staging, and typed hardware-operation rules now. Semantic-contract fixtures freeze conditions that require the later complete construction graph and domain analysis.

## External-library subset

An external reusable register package may use:

- `RegisterMap`, register/field declarations, hierarchy/repetition/window/alias/group forms;
- all frozen policy values;
- `RegisterBlock` and local-domain hardware bindings;
- `RegisterTransport` and capabilities when publishing an adapter;
- ordinary public `ClockDomain`, `Cdc`, `Rdc`, `Pulse`, and data APIs.

It may not depend on `nodal.internal`, frontend/compiler packages, backend internals, bootstrap tools, or private canonical-IR classes. Root integration still owns root domain construction, absolute placement, transport selection, and emission.

## Rejected alternatives

- create the register schema directly from an APB or AXI bus object;
- combine every software/hardware/collision combination into one giant enum;
- use untyped integer field handles across unrelated maps;
- accept string-keyed field lookup as the normal hardware-binding API;
- expose the canonical committed endpoint for ordinary user manipulation;
- attach two buses directly and leave arbitration/order implicit;
- make individual fixed register addresses HDL parameters;
- make YAML key order, Scala reflection order, or JVM object identity define the ABI;
- silently drop unsupported SystemRDL, YAML, or IP-XACT semantics;
- insert implicit CDC for register status or command values;
- claim bus, RTL, parser, or artifact implementation in this gate.

## Compatibility policy

- Source compatibility is required across register-factory v0.1 patch releases.
- Removing, renaming, retyping, or materially changing a frozen type, policy, constructor, method, or map-ownership rule requires a new versioned design gate and migration note.
- Additive overloads are allowed only when existing source remains unambiguous and register, staging, domain, or transport safety is not weakened.
- Diagnostic codes cannot be reused for another condition.
- Binary compatibility is not promised before Nodal 1.0.
- The register-factory version is independently tracked from the clock/reset and future unified core-semantics API versions.

## Freeze exit evidence

1. Native and external positive register maps compile using only `import nodal.*`.
2. Fixed offsets reject symbolic `Param` values at Scala type checking.
3. Map-owned field handles reject cross-map binding at Scala type checking.
4. Typed status, Boolean set/clear, and unsigned counter operations reject incompatible data types.
5. Missing `RegisterTransport[B]` evidence rejects bus attachment at Scala type checking.
6. Hierarchy, arrays, symbolic counts, windows, aliases, snapshots, commits, wide-register, and orthogonal policy forms compile.
7. Every semantic negative fixture carries one stable source anchor and diagnostic code.
8. The gate, API manifest, diagnostic manifest, fixture manifest, language reference, and roadmap agree.
9. The dedicated Increment 116 workflow and complete `./nodal check` gate pass before merge.
10. No canonical Register IR, bus adapter, RTL, or generated artifact is presented as implemented.

## Follow-up implementation

- Increment 117 implements canonical Register IR, verifier, source maps, ABI manifest, and compatibility diff.
- Increment 118 implements SystemRDL 2.0 and safe versioned YAML/JSON frontends.
- Increment 119 implements canonical committed access and APB3/APB4.
- Increment 120 implements AXI4-Lite, custom adapter conformance, and explicit multi-access arbitration.
- Increment 121 implements portable Verilog lowering and parameterized geometry.
- Increment 122 implements software/UVM/documentation/SystemRDL/IP-XACT artifacts.
- Increment 123 completes semantic verification, scale, and external qualification.
