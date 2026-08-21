package prototypes.candidates

import nodal.*

final class Adc extends Module:
  val width = param(12.integer)
  val analogInput = input(Electrical)
  val common = input(Electrical)
  val sampleClock = input(Bool)
  val code = output(UInt(width))
  val fullScale = param(1.0.V)

  always(sampleClock.rising):
    code := toUInt(V(analogInput, common) / fullScale, width)

final class Dac extends Module:
  val width = param(12.integer)
  val code = input(UInt(width))
  val analogOutput = output(Electrical)
  val common = input(Electrical)
  val fullScale = param(1.0.V)
  private val levels = (2.integer ** width) - 1.integer

  analog:
    V(analogOutput, common) <+ transition(
      code.toReal * fullScale / levels.toReal,
      0.0.ns,
      1.0.ns,
      1.0.ns
    )

final class MixedSignalHold extends Module:
  val width = param(10.integer)
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

final class ParameterizedAmsChain extends Module:
  val width = param(10.integer)
  val analogInput = inout(Electrical)
  val analogOutput = inout(Electrical)
  val common = inout(Electrical)
  val sampleClock = input(Bool)

  private val adc = instance(new Adc).param(_.width, width)
  private val dac = instance(new Dac).param(_.width, width)

  connect(analogInput, adc(_.analogInput))
  connect(common, adc(_.common))
  connect(sampleClock, adc(_.sampleClock))
  connect(adc(_.code), dac(_.code))
  connect(dac(_.analogOutput), analogOutput)
  connect(dac(_.common), common)
