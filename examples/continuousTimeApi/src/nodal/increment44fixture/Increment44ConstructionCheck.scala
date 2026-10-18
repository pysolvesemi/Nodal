package nodal.increment44fixture

import nodal.*

/** Public simulator context queries retain physical units and runtime evaluation. */
final class AnalogContextSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  analog:
    val normalizedTemperature = EnvironmentContext.temperature /
      EnvironmentContext.nominalTemperature
    val elapsed = AnalysisContext.time / 1.0.s
    V(positive, negative) <+ (normalizedTemperature + elapsed) * 1.0.V
    val _ = AnalogMath.abs(EnvironmentContext.temperature)
    val _ = AnalysisContext.active(AnalysisKind.Transient)

/** The same queries remain live in event arguments, control conditions and fill projections. */
final class AnalogContextEventSource extends Module:
  val observed = variable(Real, 0.0.real)
  val elapsed = variable(Real, 0.0.s)
  analogProcedure:
    val relative = Vec.fill(Vec(Real, 2))(
      EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
    )
    val clock = Vec.fill(Vec(Real, 2))(AnalysisContext.time)
    on(initialStep("tran", "vendorPhase")):
      val localTemperature = variable(Real, EnvironmentContext.temperature)
      analogConditional:
        analogWhen(localTemperature > EnvironmentContext.nominalTemperature):
          observed := relative.at(1)
        analogOtherwise:
          observed := relative.at(0)
      elapsed := clock.at(0)
    on(cross(clock.at(1) - 1.0.s)):
      observed := relative.at(0)
    on(finalStep("tran", "vendorPhase")):
      elapsed := AnalysisContext.time

final class AnalogContextGeneratedSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  hdlRange(0, 2): _ =>
    analog:
      val relative = EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
      V(positive, negative) <+ relative * 1.0.V
    analogProcedure:
      val elapsed = variable(Real, 0.0.s)
      on(initialStep("tran")):
        val temperature = variable(Real, EnvironmentContext.temperature)
        val samples = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(temperature))
        samples.writable := Vec.fill(Vec(Real, 2))(EnvironmentContext.nominalTemperature)
        elapsed := AnalysisContext.time
      on(finalStep("tran")):
        elapsed := AnalysisContext.time

final class AnalogContextQueryOnlyGeneratedSource extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val _ = EnvironmentContext.temperature
      val _ = EnvironmentContext.nominalTemperature

/** Shared fills with no storage reads still execute once per reached control/event consumer. */
final class AnalogContextSharedSource extends Module:
  val observed = variable(Real, 0.0.real)
  analogProcedure:
    val temperature = Vec.fill(Vec(Real, 2))(
      EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
    )
    val clock = Vec.fill(Vec(Real, 2))(AnalysisContext.time)
    analogConditional:
      analogWhen(temperature.at(0) > temperature.at(1)):
        observed := 1.0.real
      analogOtherwise:
        observed := 0.0.real
    on(cross(clock.at(0) - 1.0.s) or above(clock.at(1) - 1.0.s)):
      observed := 2.0.real

final class AnalogContextGeneratedContinuousSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  hdlRange(0, 2): _ =>
    analog:
      val relative = EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
      V(positive, negative) <+ relative * 1.0.V

final class AnalogContextGeneratedEventSource extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val elapsed = variable(Real, 0.0.s)
      on(initialStep("tran")):
        val temperature = variable(Real, EnvironmentContext.temperature)
        val samples = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(temperature))
        samples.writable := Vec.fill(Vec(Real, 2))(EnvironmentContext.nominalTemperature)
        elapsed := AnalysisContext.time
      on(finalStep("tran")):
        elapsed := AnalysisContext.time

object Increment44ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new AnalogContextSource)
    val _ = Nodal.emit(new AnalogContextEventSource)
    val _ = Nodal.emit(new AnalogContextGeneratedSource)
    val _ = Nodal.emit(new AnalogContextQueryOnlyGeneratedSource)
    val _ = Nodal.emit(new AnalogContextSharedSource)
    val _ = Nodal.emit(new AnalogContextGeneratedContinuousSource)
    val _ = Nodal.emit(new AnalogContextGeneratedEventSource)
    println("Increment 44 public simulator-context construction passed")
