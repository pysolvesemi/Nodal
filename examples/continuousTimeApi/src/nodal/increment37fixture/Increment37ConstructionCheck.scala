package nodal.increment37fixture

import nodal.*

/** Separately compiled consumer: only public APIs, no compiler or private testkit dependency. */
final class AnalogEventSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val threshold = param(0.5.V)
  val start = param(1.0.ns)
  val parameterEnable = param(0.integer)
  val held = variable(Real, 0.0.V)
  val enabled = variable(Integer, 1.integer)
  val select = variable(Bool, true.B)
  val signal = V(positive, negative) - threshold
  // A physical load makes this a complete standalone analog fixture, not a floating observer.
  analog:
    I(positive, negative) <+ (V(positive, negative) / 1.0.kOhm)
  val events = Vector(
    cross(signal),
    cross(signal, Edge.Rising),
    cross(signal, Edge.Falling, 0.0.ns),
    cross(signal, Edge.Either, 1.0.ns, 0.01.V),
    cross(signal, Edge.Rising, 0.0.real, 0.0.V, enabled),
    above(signal),
    above(signal, 0.0.ns),
    above(signal, 1.0.ns, 0.01.V),
    above(signal, 1.0.ns, 0.01.V, enabled),
    timer(start),
    timer(start, 2.0.ns),
    timer(start, -1.0.ns, 0.0.ns),
    timer(start, 0.0.ns, 0.0.ns, parameterEnable),
    initialStep,
    initialStep("dc", "tran"),
    finalStep,
    finalStep("tran"),
    crossing(signal, EventTolerance(0.01.V, 1.0.ns), name = "threshold_crossing")
  )
  analogProcedure:
    events.foreach: event =>
      on(event):
        analogConditional:
          analogWhen(select):
            held := 1.0.V
          analogOtherwise:
            held := 0.0.V
    on(initialStep or above(signal) or timer(start)):
      held := 0.0.V
    // Empty controls must remain in IR because their monitors still request time points.
    on(finalStep("tran")):
      ()

object Increment37ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "this public witness does not accept arguments")
    val _ = Nodal.emit(new AnalogEventSource)
    println("Increment 37 public analog-event construction passed")
