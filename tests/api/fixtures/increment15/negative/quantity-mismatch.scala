package contracts.v03negative

import nodal.*

val voltage = 1.0.volts
val current = 1.0.amps

// diagnostic-anchor: NODAL-UNIT-015
val illegalQuantity = voltage + current
