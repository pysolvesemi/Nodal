package contracts.registerfactory.positive

import nodal.*

object ChannelMap extends RegisterMap(
      name = "channel",
      dataWidth = 32,
      addressUnit = AddressUnit.Byte,
      endianness = Endianness.Little
    ):
  val valueRegister = register(0x00, "VALUE", doc = "Channel value")
  val value = valueRegister.field(
    name = "VALUE",
    dataType = UInt(16),
    bits = 15 downto 0,
    software = SoftwareAccess.RW,
    reset = 0.U(16),
    hardware = HardwareAccess.Write,
    collision = CollisionPolicy.HardwareWins,
    partialWrite = PartialWritePolicy.RequireWholeField,
    doc = "Software and hardware visible channel value"
  )
  valueRegister.reserved(bits = 31 downto 16)

object UartMap extends RegisterMap(
      name = "uart",
      dataWidth = 32,
      illegalAccess = IllegalAccessPolicy.ErrorResponse
    ):
  val control = register(0x00, "CONTROL")
  val enable = control.field(
    name = "ENABLE",
    dataType = Bool,
    bits = 0,
    software = SoftwareAccess.RW,
    reset = false.B,
    doc = "Enable the UART"
  )
  val start = control.field(
    name = "START",
    dataType = Bool,
    bits = 1,
    software = SoftwareAccess.WO,
    hardware = HardwareAccess.Pulse,
    doc = "Emit one command pulse per committed write"
  )
  control.reserved(bits = 31 downto 2)

  val status = register(0x04, "STATUS")
  val busy = status.field(
    name = "BUSY",
    dataType = Bool,
    bits = 0,
    software = SoftwareAccess.RO,
    hardware = HardwareAccess.Input
  )
  val irq = status.field(
    name = "IRQ",
    dataType = Bool,
    bits = 1,
    software = SoftwareAccess.W1C,
    reset = false.B,
    hardware = HardwareAccess.Settable,
    collision = CollisionPolicy.SetDominatesClear
  )
  status.reserved(bits = 31 downto 2)

  val wide = register(
    offset = 0x08,
    name = "WIDE_COUNT",
    multiword = MultiwordAccess.SnapshotOnFirstRead
  )
  val wideCount = wide.field(
    name = "COUNT",
    dataType = UInt(64),
    bits = 63 downto 0,
    software = SoftwareAccess.RO,
    hardware = HardwareAccess.Input,
    partialWrite = PartialWritePolicy.Reject
  )

  val statusAlias = alias(
    offset = 0x20,
    target = status,
    name = "STATUS_ALIAS",
    software = SoftwareAccess.RO
  )

  val statusSnapshot = snapshot("STATUS_SNAPSHOT", busy, irq, wideCount)

  val controlCommit = commitGroup("CONTROL_COMMIT", enable)

  reserved(offset = 0x10, size = 0x10, doc = "Reserved ABI expansion region")

final class DmaMap extends RegisterMap(name = "dma", dataWidth = 32):
  val channelCount = param(4.integer)
  val channels = array(
    base = 0x100,
    count = channelCount,
    stride = 0x20,
    element = ChannelMap,
    name = "channels"
  )
  val descriptorWindow = window(offset = 0x800, size = 0x100, name = "descriptors")

object HierarchicalMap extends RegisterMap(name = "subsystem", dataWidth = 32):
  val uart0 = submap(offset = 0x0000, map = UartMap, name = "uart0")
  val uart1 = submap(offset = 0x1000, map = UartMap, name = "uart1")
  val dma = submap(offset = 0x2000, map = new DmaMap, name = "dma")
