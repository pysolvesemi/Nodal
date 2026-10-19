import nodal.*

final class AnalogRangeContext extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  hdlRange(0, 2): _ =>
    analog:
      val temperature = EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
      val elapsed = AnalysisContext.time / 1.0.s
      V(positive, negative) <+ (temperature + elapsed) * 1.0.V
