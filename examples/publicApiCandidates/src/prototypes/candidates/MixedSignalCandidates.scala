package prototypes.candidates

import nodal.*

final class Adc extends Module:
  val width = param(12.integer)
  val analogInput = in(Electrical)
  val common = in(Electrical)
  val code = out(UInt(width))
  val fullScale = param(1.0.V)
  val sampleDomain = ClockDomain.required("sample")

  sampleDomain:
    val sampledCode = Reg(0.U(width))
    sampledCode := toUInt(V(analogInput, common) / fullScale, width)
    code := sampledCode

final class Dac(val width: Int = 12) extends Module:
  val code = in(UInt(width))
  val analogOutput = out(Electrical)
  val common = in(Electrical)
  val fullScale = param(1.0.V)

  analog:
    V(analogOutput, common) <+ transition(
      toReal(code) * fullScale / ((1 << width) - 1).real,
      0.0.ns,
      1.0.ns,
      1.0.ns
    )

final class MixedSignalHold(val width: Int = 10) extends Module:
  val analogInput = in(Electrical)
  val common = in(Electrical)
  val code = out(UInt(width))
  val threshold = param(0.25.V)
  val captureDomain = ClockDomain.required("capture")
  private val held = variable(Real, 0.0.V)

  analog:
    on(cross(V(analogInput, common) - threshold, Edge.Either)):
      held := V(analogInput, common)

  captureDomain:
    val capturedCode = Reg(0.U(width))
    capturedCode := toUInt(held, width)
    code := capturedCode

final class HierarchyAndOverride extends Module:
  val input = inout(Electrical)
  val output = inout(Electrical)
  val common = inout(Electrical)
  val cutoffResistance = param(2.0.kOhm)
  val cutoffCapacitance = param(22.0.pF)

  private val filter =
    instance(new RcFilter)
      .param(_.resistance, cutoffResistance)
      .param(_.capacitance, cutoffCapacitance)

  connect(input, filter(_.input))
  connect(output, filter(_.output))
  connect(common, filter(_.common))
