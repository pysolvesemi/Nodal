package contracts.registerfactory.negative

import contracts.registerfactory.positive.{ChannelMap, UartMap}
import nodal.*

final class CrossMapField extends Module:
  val domain = ClockDomain.required("registers")

  domain:
    val registers = RegisterBlock(UartMap)
    // diagnostic-anchor: NODAL-REG-BIND-001
    val invalid = registers.value(ChannelMap.value)
