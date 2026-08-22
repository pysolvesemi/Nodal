package contracts.registerfactory.positive

import nodal.*

final class UartRegisterBlockFixture extends Module:
  val busyInput = in(Bool)
  val irqEvent = in(Bool)
  val countInput = in(UInt(64))
  val captureStatus = in(Bool)
  val commitControl = in(Bool)

  val enableOutput = out(Bool)
  val startPulse = out(Bool)

  val busDomain = ClockDomain.required("bus")

  busDomain:
    val registers = RegisterBlock(UartMap)

    enableOutput := registers.value(UartMap.enable)
    registers.input(UartMap.busy) := busyInput
    registers.input(UartMap.wideCount) := countInput
    registers.setWhen(UartMap.irq, irqEvent)
    registers.clearWhen(UartMap.irq, false.B)

    startPulse := registers.pulse(UartMap.start).value
    registers.capture(UartMap.statusSnapshot, captureStatus)
    registers.commit(UartMap.controlCommit, commitControl)

final class CounterRegisterBlockFixture extends Module:
  val increment = in(Bool)
  val decrement = in(Bool)

  val busDomain = ClockDomain.required("bus")

  busDomain:
    val registers = RegisterBlock(ChannelMap)
    registers.incrementWhen(ChannelMap.value, increment)
    registers.decrementWhen(ChannelMap.value, decrement)
