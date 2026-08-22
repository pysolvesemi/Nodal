package contracts.registerfactory.negative

import contracts.registerfactory.positive.UartMap
import nodal.*

final class WrongCounterField extends Module:
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(UartMap)
    // diagnostic-anchor: NODAL-REG-BIND-003
    registers.incrementWhen(UartMap.irq, true.B)
