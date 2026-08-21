package prototypes.candidates

import nodal.*

final class Adc(val width: Int = 12) extends Module:
  val analogInput = input(Electrical)
  val common = input(Electrical)
  val sampleClock = input(Bool)
  val code = output(UInt(width))
  val fullScale = param(1.0.V)

  always(sampleClock.rising):
    code := toUInt(V(analogInput, common) / fullScale, width)

final class Dac(val width: Int = 12) extends Module:
  val code = input(UInt(width))
  val analogOutput = output(Electrical)
  val common = input(Electrical)
  val fullScale = param(1.0.V)

  analog:
    V(analogOutput, common) <+ transition(
      toReal(code) * fullScale / ((1 << width) - 1).real,
      0.0.ns,
      1.0.ns,
      1.0.ns
    )

final class MixedSignalHold(val width: Int = 10) extends Module:
  val analogInput = input(Electrical)
  val common = input(Electrical)
  val capture = input(Bool)
  val code = output(UInt(width))
  val threshold = param(0.25.V)
  private val held = variable(Real, 0.0.V)

  analog:
    on(cross(V(analogInput, common) - threshold, Edge.Either)):
      held := V(analogInput, common)

  always(capture.rising):
    code := toUInt(held, width)

final class HierarchyAndOverride extends Module:
  val source = inout(Electrical)
  val sink = inout(Electrical)
  val common = inout(Electrical)
  val cutoffResistance = param(2.0.kOhm)
  val cutoffCapacitance = param(22.0.pF)

  private val filter =
    instance(new RcFilter)
      .param(_.resistance, cutoffResistance)
      .param(_.capacitance, cutoffCapacitance)

  connect(source, filter(_.source))
  connect(sink, filter(_.sink))
  connect(common, filter(_.common))
