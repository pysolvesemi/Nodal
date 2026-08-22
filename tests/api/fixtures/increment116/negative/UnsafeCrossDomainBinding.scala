package contracts.registerfactory.negative

import contracts.registerfactory.positive.UartMap
import nodal.*

final class UnsafeCrossDomainBinding extends Module:
  val sourceDomain = ClockDomain.required("source")
  val registerDomain = ClockDomain.required("registers")
  val sourceValue = wire(Bool)

  sourceDomain:
    sourceValue := true.B

  registerDomain:
    val registers = RegisterBlock(UartMap)
    // diagnostic-anchor: NODAL-REG-CDC-001
    registers.input(UartMap.busy) := sourceValue
