package nodal.increment38fixture

import nodal.*

/** A public-only consumer exercises continuous expressions and event-controlled queries. */
final class AnalogMathSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val gain = param(2.0.real)
  analog:
    val input = V(positive, negative) / 1.0.V
    val nonlinear = AnalogMath.tanh(input * gain)
    val scaled = AnalogMath.sqrt(AnalogMath.abs(nonlinear)) * 1.0.V
    V(positive, negative) <+ scaled
    val _ = AnalogMath.log10(100.0.real)
    val _ = AnalogMath.sqrt(4.0.V * 4.0.V)
    val _ = AnalysisContext.active(AnalysisKind.Transient) &&
      !AnalysisContext.active(AnalysisKind.Ac) && true.B

final class AnalogMathEventSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val held = variable(Real, AnalogMath.sqrt(4.0.V * 4.0.V))
  analogProcedure:
    on(initialStep or timer(0.0.ns, 1.0.ns)):
      analogConditional:
        analogWhen(AnalysisContext.active(AnalysisKind.Transient)):
          held := AnalogMath.abs(V(positive, negative))
        analogOtherwise:
          held := 0.0.V
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 1.0.ns)

object Increment38ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new AnalogMathSource)
    val _ = Nodal.emit(new AnalogMathEventSource)
    println("Increment 38 public mathematical-function and analysis-query construction passed")
