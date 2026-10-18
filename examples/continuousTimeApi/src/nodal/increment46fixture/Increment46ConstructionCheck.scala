package nodal.increment46fixture

import nodal.*

/** Valid source risks remain authored mathematics for the read-only native lint. */
final class AnalogLintSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val divisor = param(2.0.real)
  analog:
    val scaled = 1.0.real / divisor
    val elapsed = AnalysisContext.time / 1.0.s
    val stepped = AnalogMath.floor(elapsed)
    val ceiling = AnalogMath.ceil(elapsed)
    V(positive, negative) <+ (scaled + stepped + ceiling) * 1.0.V

/** Neighboring literal division, smooth runtime math and constant rounding stay valid. */
final class AnalogLintSafeSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  analog:
    val scaled = 1.0.real / 2.0.real
    val elapsed = AnalysisContext.time / 1.0.s
    val smooth = AnalogMath.sin(elapsed)
    val rounded = AnalogMath.floor(1.25.real) + AnalogMath.ceil(1.25.real)
    V(positive, negative) <+ (scaled + smooth + rounded) * 1.0.V

/** Inactive monitors and vendor filters remain explicit controls, including empty bodies. */
final class AnalogLintEventSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val held = variable(Real, 0.0.V)
  analogProcedure:
    on(initialStep("tran", "vendorPhase")):
      held := 0.0.V
    on(cross(1.0.V)):
      held := 1.0.V
    on(above(-1.0.V)):
      held := 2.0.V
    on(timer(0.0.s, 1.0.s, 0.0.s, 0.integer)):
      held := 3.0.V
    on(finalStep("tran", "vendorPhase")):
      ()
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 1.0.ns)

/** Positive above and one-shot timers are useful; defaults are not runtime enable proofs. */
final class AnalogLintSafeEventSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val parameterEnable = param(0.integer)
  val runtimeEnable = variable(Integer, 0.integer)
  val held = variable(Real, 0.0.V)
  analogProcedure:
    on(initialStep("dc", "tran")):
      held := 0.0.V
    on(cross(AnalysisContext.time - 1.0.s)):
      held := 1.0.V
    on(above(1.0.V)):
      held := 2.0.V
    on(timer(0.0.s, 0.0.s, 0.0.s, 1.integer)):
      held := 3.0.V
    on(timer(0.0.s, -1.0.s)):
      held := 4.0.V
    on(timer(0.0.s, 1.0.s, 0.0.s, parameterEnable)):
      held := 5.0.V
    on(timer(0.0.s, 1.0.s, 0.0.s, runtimeEnable)):
      held := 6.0.V
    on(finalStep("tran")):
      ()
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 1.0.ns)

/** Deliberately invalid target witness: native mandatory zero-divisor checking must still fail. */
final class AnalogLintZeroDivisorSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  analog:
    val invalid = 1.0.V / 0.0.real
    V(positive, negative) <+ invalid

object Increment46ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new AnalogLintSource)
    val _ = Nodal.emit(new AnalogLintSafeSource)
    val _ = Nodal.emit(new AnalogLintEventSource)
    val _ = Nodal.emit(new AnalogLintSafeEventSource)
    println("Increment 46 public analog lint witness construction passed")
