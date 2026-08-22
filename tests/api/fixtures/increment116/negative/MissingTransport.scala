package contracts.registerfactory.negative

import contracts.registerfactory.positive.UartMap
import nodal.*

final class UnsupportedBus

final class MissingTransport extends Module:
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(UartMap)
    // diagnostic-anchor: NODAL-REG-TRANSPORT-001
    registers.attach(new UnsupportedBus)
