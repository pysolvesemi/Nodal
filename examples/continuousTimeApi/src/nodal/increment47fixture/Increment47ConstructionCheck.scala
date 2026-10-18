package nodal.increment47fixture

import nodal.*

/** Runtime queries remain analog when their contribution lives in a child definition. */
final class AnalogCapabilityQueryChild extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val resistance = param(1.0.kOhm)
  analog:
    val relativeTemperature = EnvironmentContext.temperature /
      EnvironmentContext.nominalTemperature
    I(positive, negative) <+ V(positive, negative) / resistance * relativeTemperature

final class AnalogCapabilityHierarchy extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val child = instance(new AnalogCapabilityQueryChild)
  positive <> child(_.positive)
  negative <> child(_.negative)

/** Analog Boolean and Integer storage must not be mistaken for digital signal types. */
final class AnalogCapabilityStorage extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val enabled = variable(Bool, false.B)
  val mode = variable(Integer, 0.integer)
  val held = variable(Real, 0.0.V)
  analogProcedure:
    on(initialStep):
      enabled := true.B
      mode := 1.integer
      analogConditional:
        analogWhen(enabled):
          analogCase(mode):
            analogCaseArm(1):
              held := 1.0.V
            analogCaseDefault:
              held := 0.0.V
        analogOtherwise:
          held := 0.0.V
  analog:
    V(positive, negative) <+ held

/** A real digital port is retained even when only analog declarations exist at the root. */
final class DigitalCapabilityLeaf extends Module:
  val clock = ClockDomain.external(
    "clock",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )
  val trigger = in(Bool)

final class MixedCapabilityChild extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val digital = instance(new DigitalCapabilityLeaf)
  analog:
    I(positive, negative) <+ V(positive, negative) / 1.0.kOhm

final class AnalogRootWithDigitalDescendant extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val child = instance(new MixedCapabilityChild)
  positive <> child(_.positive)
  negative <> child(_.negative)

object Increment47ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val analog = Nodal.emit(new AnalogCapabilityHierarchy)
    val storage = Nodal.emit(new AnalogCapabilityStorage)
    val mixed = Nodal.emit(new AnalogRootWithDigitalDescendant)
    assert(analog.report.designKind == DesignKind.AnalogOnly)
    assert(storage.report.designKind == DesignKind.AnalogOnly)
    assert(mixed.report.designKind == DesignKind.MixedSignal)
    println("Increment 47 public language-capability witness construction passed")
