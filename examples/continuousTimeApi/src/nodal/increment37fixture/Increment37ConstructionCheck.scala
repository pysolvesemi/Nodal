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

  // Persistent sampled storage may feed a continuously evaluated filter.
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 1.0.ns)

final class AnalogSampleHoldSource extends Module:
  val sampleIn = inout(Electrical)
  val sampleOut = inout(Electrical)
  val ground = inout(Electrical)
  val initialVoltage = param(0.25.V)
  val samplePeriod = param(2.0.ns)
  val held = variable(Real, initialVoltage)
  analogProcedure:
    on(initialStep or timer(0.0.ns, samplePeriod)):
      held := V(sampleIn, ground)
  analog:
    V(sampleOut, ground) <+ transition(held, 0.0.ns, 0.5.ns)

final class AnalogControlledEventsSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val trips = param(2.integer)
  val choose = variable(Integer, 1.integer)
  val active = variable(Bool, true.B)
  val held = variable(Real, 0.0.V)
  analogProcedure:
    on(initialStep or timer(0.0.ns, 1.0.ns)):
      analogCase(choose):
        analogCaseArm(0, 1):
          held := 1.0.V
        analogCaseDefault:
          held := 0.0.V
      analogLoop(trips, maximumIterations = 4):
        analogConditional:
          analogWhen(active):
            analogContinue()
          analogOtherwise:
            held := 0.5.V
        analogBreak()
      analogRepeat(2):
        held := 0.25.V
    analogConditional:
      analogStaticWhen(true):
        on(cross(V(positive, negative), Edge.Rising)):
          held := 1.0.V
      analogOtherwise:
        on(initialStep):
          held := 0.0.V
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 0.5.ns)

object Increment37ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "this public witness does not accept arguments")
    val _ = Nodal.emit(new AnalogEventSource)
    println("Increment 37 public analog-event construction passed")
