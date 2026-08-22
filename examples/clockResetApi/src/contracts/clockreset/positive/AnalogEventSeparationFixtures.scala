package contracts.clockreset.positive

import nodal.*

final class AnalogEventSeparationFixture extends Module:
  val input = in(Electrical)
  val common = in(Electrical)
  val crossed = out(Bool)
  val threshold = param(0.5.V)
  val period = param(10.0.ns)
  val sampled = variable(Real, 0.0.V)

  initial:
    crossed := false.B

  analog:
    on(cross(V(input, common) - threshold, Edge.Rising)):
      crossed := true.B
    on(timer(0.0.ns, period)):
      sampled := V(input, common)

final class LowLevelEventFixture extends Module:
  val trigger = in(Bool)
  val observed = out(Bool)

  nodal.lowlevel.process(trigger.rising):
    observed := true.B
