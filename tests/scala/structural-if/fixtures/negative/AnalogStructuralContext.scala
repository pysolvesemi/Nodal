import nodal.*

final class AnalogStructuralContext extends Module:
  val MODE = param(0.integer)
  val selected =
    if MODE == 0 then EnvironmentContext.temperature else EnvironmentContext.nominalTemperature
