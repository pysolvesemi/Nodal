package external.registers

import nodal.*

object GpioRegisterMap extends RegisterMap(name = "gpio", dataWidth = 32):
  val direction = register(0x00, "DIRECTION")
  val outputEnable = direction.field(
    name = "OUTPUT_ENABLE",
    dataType = UInt(16),
    bits = 15 downto 0,
    software = SoftwareAccess.RW,
    reset = 0.U(16)
  )

  val input = register(0x04, "INPUT")
  val inputValue = input.field(
    name = "VALUE",
    dataType = UInt(16),
    bits = 15 downto 0,
    software = SoftwareAccess.RO,
    hardware = HardwareAccess.Input
  )

final class GpioRegisterBlock extends Module:
  val inputValue = in(UInt(16))
  val outputEnable = out(UInt(16))
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(GpioRegisterMap)
    registers.input(GpioRegisterMap.inputValue) := inputValue
    outputEnable := registers.value(GpioRegisterMap.outputEnable)
