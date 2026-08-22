package contracts.registerfactory.negative

import contracts.registerfactory.positive.UartMap
import nodal.*

final class MissingRegisterDomain extends Module:
  // diagnostic-anchor: NODAL-REG-DOMAIN-001
  val registers = RegisterBlock(UartMap)
