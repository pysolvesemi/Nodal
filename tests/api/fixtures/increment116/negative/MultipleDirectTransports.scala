package contracts.registerfactory.negative

import contracts.registerfactory.positive.{DemoControlBus, UartMap}
import contracts.registerfactory.positive.DemoControlBus.given
import nodal.*

final class MultipleDirectTransports extends Module:
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(UartMap)
    registers.attach(new DemoControlBus)
    // diagnostic-anchor: NODAL-REG-TRANSPORT-002
    registers.attach(new DemoControlBus)
