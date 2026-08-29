package examples.continuoustime

import nodal.*

final class RealStructuralParameter extends Module:
  val gain = param(1.0.real)

  // diagnostic-anchor: NODAL-ANALOG-133-TYPE-004
  val invalid = structuralParameter(
    gain,
    StructuralEnvelope(minimum = 1, maximum = 4),
    Set(StructuralEffect.EquationCount)
  )
