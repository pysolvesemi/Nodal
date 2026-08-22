package increment13negative

import nodal.*

object UnitMismatch:
  val voltage = 1.0.volts
  val current = 1.0.amps

  // diagnostic-anchor: NODAL-UNIT-013
  val illegal = voltage + current
