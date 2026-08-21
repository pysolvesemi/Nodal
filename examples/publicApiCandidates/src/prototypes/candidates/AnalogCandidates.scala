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
  val source = inout(Electrical)
  val sink = inout(Electrical)
  val common = inout(Electrical)
  val resistance = param(1.0.kOhm)
  val capacitance = param(10.0.pF)

  private val resistor = instance(new Resistor()).param(_.resistance, resistance)
  private val capacitor = instance(new Capacitor()).param(_.capacitance, capacitance)

  connect(source, resistor(_.p))
  connect(resistor(_.n), sink)
  connect(sink, capacitor(_.p))
  connect(capacitor(_.n), common)

final class Comparator extends Module:
  val positive = input(Electrical)
  val negative = input(Electrical)
  val result = output(Bool)
  val offset = param(0.0.V)

  analog:
    on(cross(V(positive, negative) - offset)):
      result := V(positive, negative) > offset

final class AnalogEventSampler extends Module:
  val analogInput = input(Electrical)
  val common = input(Electrical)
  val crossed = output(Bool)
  val threshold = param(0.5.V)
  val samplePeriod = param(10.0.ns)
  private val sampled = variable(Real, 0.0.V)

  initial:
    crossed := false.B

  analog:
    on(cross(V(analogInput, common) - threshold, Edge.Rising)):
      crossed := true.B
    on(timer(0.0.ns, samplePeriod)):
      sampled := V(analogInput, common)
