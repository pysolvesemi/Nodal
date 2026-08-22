# Register factory public API v0.1

**Status:** Frozen source contract; implementation deferred  
**Design gate:** [`NodalRegisterFactory-DG-v0.1.md`](../design-gates/NodalRegisterFactory-DG-v0.1.md)  
**Architecture:** [`ADR 0020`](../architecture/0020-canonical-register-factory-and-transport-adapters.md)

## Overview

The register factory separates the software-visible register ABI from physical storage and from the protocol used to access it:

```text
RegisterMap definition
    -> RegisterBlock instance
    -> RegisterTransport[B]
```

A map can later be reached through APB, AXI4-Lite, or a custom bus without rewriting its registers and fields. Increment 116 freezes the Scala source contract only. Register validation, bus transactions, storage, side effects, RTL, SystemRDL/YAML parsing, and generated artifacts are implemented by later increments.

## Import

```scala
import nodal.*
```

## Define a register map

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

  val start = control.field(
    name = "START",
    dataType = Bool,
    bits = 1,
    software = SoftwareAccess.WO,
    hardware = HardwareAccess.Pulse
  )

  control.reserved(31 downto 2)
```

`RegisterMap` is bus neutral. It contains no APB or AXI object and captures no live hardware signal.

### Address units and byte order

```scala
AddressUnit.Byte
AddressUnit.Word

Endianness.Little
Endianness.Big
```

Offsets are fixed elaboration-time values. `RegisterOffset` accepts Scala `Int`, `Long`, or `BigInt` constant forms directly, so a literal such as `0x04` needs no implicit conversion or language-feature import. A symbolic `Param[Integer]` is intentionally not a `RegisterOffset`.

### Bit selections

Use one bit:

```scala
bits = 0
```

or an explicit range:

```scala
bits = 15 downto 0
```

### Software access

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

### Hardware behavior

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

### Collision policy

```scala
CollisionPolicy.HardwareWins
CollisionPolicy.SoftwareWins
CollisionPolicy.SetDominatesClear
CollisionPolicy.ClearDominatesSet
CollisionPolicy.ErrorOnCollision
```

Example sticky interrupt:

```scala
val irq = status.field(
  name = "IRQ",
  dataType = Bool,
  bits = 1,
  software = SoftwareAccess.W1C,
  reset = false.B,
  hardware = HardwareAccess.Settable,
  collision = CollisionPolicy.SetDominatesClear
)
```

Software clearing and hardware setting are independent semantics. The collision policy states that a same-cycle set remains asserted.

### Partial writes

```scala
PartialWritePolicy.Allow
PartialWritePolicy.Reject
PartialWritePolicy.RequireWholeField
PartialWritePolicy.RequireWholeRegister
```

### Wide registers

```scala
val count = register(
  offset = 0x08,
  name = "COUNT",
  multiword = MultiwordAccess.SnapshotOnFirstRead
)
```

Available policies are:

```scala
MultiwordAccess.NonAtomic
MultiwordAccess.SnapshotOnFirstRead
MultiwordAccess.ShadowThenCommit
MultiwordAccess.ProtocolAtomic
MultiwordAccess.Rejected
```

## Compose maps

### Reserved address space

```scala
reserved(offset = 0x10, size = 0x10, doc = "Future ABI expansion")
```

### Submaps

```scala
val uart0 = submap(offset = 0x0000, map = UartMap, name = "uart0")
val uart1 = submap(offset = 0x1000, map = UartMap, name = "uart1")
```

### Repeated maps

```scala
final class DmaMap extends RegisterMap(name = "dma"):
  val channelCount = param(4.integer)

  val channels = array(
    base = 0x100,
    count = channelCount,
    stride = 0x20,
    element = ChannelMap,
    name = "channels"
  )
```

A symbolic count is legal because repetition geometry is explicit. It does not make fixed offsets such as `0x100` or `0x20` externally overridable automatically.

### Windows and aliases

```scala
val descriptors = window(
  offset = 0x800,
  size = 0x100,
  name = "descriptors"
)

val statusAlias = alias(
  offset = 0x20,
  target = status,
  name = "STATUS_ALIAS",
  software = SoftwareAccess.RO
)
```

### Snapshot and commit groups

```scala
val statusSnapshot = snapshot("STATUS_SNAPSHOT", busy, irq, countValue)
val controlCommit = commitGroup("CONTROL_COMMIT", enable)
```

The exact coherency and commit timing are semantic implementation work in later increments; the map-owned typed handles are frozen now.

## Instantiate a physical register block

A `RegisterBlock` belongs to one lexical clock/reset domain:

```scala
val busDomain = ClockDomain.required("bus")

busDomain:
  val registers = RegisterBlock(UartMap)
```

The same map can create independent physical banks:

```scala
val uart0 = RegisterBlock(UartMap)
val uart1 = RegisterBlock(UartMap)
```

## Bind hardware

### Read a software-controlled field

```scala
enableOutput := registers.value(UartMap.enable)
```

### Provide hardware status

```scala
registers.input(UartMap.busy) := busyInput
```

### Hardware set and clear

```scala
registers.setWhen(UartMap.irq, irqEvent)
registers.clearWhen(UartMap.irq, clearEvent)
```

### Counters

```scala
registers.incrementWhen(ChannelMap.value, increment)
registers.decrementWhen(ChannelMap.value, decrement)
```

### Command and write events

```scala
startPulse := registers.pulse(UartMap.start).value
val anyWrite = registers.writeEvent(UartMap.enable)
```

### Snapshot and commit actions

```scala
registers.capture(UartMap.statusSnapshot, captureStatus)
registers.commit(UartMap.controlCommit, commitControl)
```

Field and group handles are owned by one map. Passing `ChannelMap.value` to a `RegisterBlock(UartMap)` is a Scala type error.

## Define and attach a transport

A bus adapter supplies a Scala 3 `given` instance:

```scala
final class MyControlBus

object MyControlBus:
  given RegisterTransport[MyControlBus] with
    def capabilities(bus: MyControlBus) =
      RegisterTransportCapabilities(
        dataWidth = 32,
        addressWidth = 16,
        byteEnable = true,
        errorResponse = true,
        protection = false,
        backpressure = true
      )

    def connect(bus: MyControlBus, endpoint: RegisterAccessPort): Unit =
      // Adapter implementation is deferred to Increment 120.
      ()
```

Attachment remains concise:

```scala
import MyControlBus.given
registers.attach(new MyControlBus)
```

`RegisterAccessPort` can be named by an adapter but cannot be constructed by normal user code. A bus with no `RegisterTransport[B]` evidence fails Scala compilation.

APB and AXI4-Lite implementations are later built-in adapters using this same boundary; they do not redefine fields or register policies.

## Multiple access paths

One physical block has one direct transport attachment by default. Two direct calls are not an implicit multi-master implementation:

```scala
registers.attach(apb)
registers.attach(debugAxi) // semantic error
```

Use the explicit arbiter/router introduced by Increment 120 so priority, fairness, ordering, security, and starvation policy are visible.

## Clock-domain crossings

The register block owns one domain. A status value from another domain must be transferred explicitly before binding:

```scala
busDomain:
  val synchronizedBusy = Cdc.sync(pixelBusy, to = busDomain)
  registers.input(UartMap.busy) := synchronizedBusy
```

Commands crossing outward use pulse, handshake, or FIFO semantics as appropriate. The register factory does not insert independent bit synchronizers silently.

## Generated Verilog policy

Fixed ABI symbols are generated as width-safe local constants:

```verilog
localparam [ADDR_WIDTH-1:0] REG_CONTROL_OFFSET = 12'h000;
localparam [ADDR_WIDTH-1:0] REG_STATUS_OFFSET  = 12'h004;
```

They are not ordinary overridable `parameter`s. Only explicit Nodal symbolic architecture—such as channel count or an explicitly selected wrapper base address—may become an HDL parameter.

The register block decodes relative offsets by default. SoC integration owns absolute placement.

## File-based authoring

SystemRDL 2.0 and versioned `nodal-registers/v1` YAML/JSON are planned alternative authoring frontends. They normalize into the same canonical Register IR as this Scala DSL. Parser APIs and semantics are implemented in Increment 118 rather than being invented in this Scala gate.

## Diagnostics

The stable diagnostic inventory is in [`register-factory-diagnostics-v0.1.json`](../../core/scala/api/register-factory-diagnostics-v0.1.json). Type-level errors already cover cross-map field use, wrong binding types, missing transports, wrong hardware operations, and symbolic fixed offsets. Construction-graph, domain, policy, multiple-transport, and CDC diagnostics are semantic contracts until their verifier is implemented.
