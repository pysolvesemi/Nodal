package contracts.registerfactory.negative

import contracts.registerfactory.positive.UartMap
import nodal.*

final class WrongBindingType extends Module:
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(UartMap)
    // diagnostic-anchor: NODAL-REG-BIND-002
    registers.input(UartMap.busy) := 1.U(1)
