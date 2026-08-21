package prototypes.candidates

import nodal.*

final class Resistor(defaultResistance: Expr[Real] = 1.0.kOhm) extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(defaultResistance)

  analog:
    V(p, n) <+ resistance * I(p, n)

final class Capacitor(defaultCapacitance: Expr[Real] = 1.0.pF) extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val capacitance = param(defaultCapacitance)

  analog:
    I(p, n) <+ capacitance * ddt(V(p, n))

final class RcFilter extends Module:
  val input = inout(Electrical)
  val output = inout(Electrical)
  val common = inout(Electrical)
  val resistance = param(1.0.kOhm)
  val capacitance = param(10.0.pF)

  private val resistor = instance(new Resistor()).param(_.resistance, resistance)
  private val capacitor = instance(new Capacitor()).param(_.capacitance, capacitance)

  connect(input, resistor(_.p))
  connect(resistor(_.n), output)
  connect(output, capacitor(_.p))
  connect(capacitor(_.n), common)

final class Comparator extends Module:
  val positive = in(Electrical)
  val negative = in(Electrical)
  val result = out(Bool)
  val offset = param(0.0.V)

  analog:
    on(cross(V(positive, negative) - offset)):
      result := V(positive, negative) > offset

final class AnalogEventSampler extends Module:
  val input = in(Electrical)
  val common = in(Electrical)
  val crossed = out(Bool)
  val threshold = param(0.5.V)
  val samplePeriod = param(10.0.ns)
  private val sampled = variable(Real, 0.0.V)

  initial:
    crossed := false.B

  analog:
    on(cross(V(input, common) - threshold, Edge.Rising)):
      crossed := true.B
    on(timer(0.0.ns, samplePeriod)):
      sampled := V(input, common)
