package nodal.iteration.fixture
import nodal.*
final class GeneratedChild extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ V(p, n) / 2.kOhm
final class RangePositive extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    val tap = node(Electrical)
    val child = instance(new GeneratedChild)
    child(_.p) <> tap
    child(_.n) <> p
    analog:
      I(tap, n) <+ 1.pF * ddt(V(tap, n))
  hdlRange(0, 2): _ =>
    hdlRange(0, 2): _ =>
      val tap = node(Electrical)
      analog:
        V(tap, p) <+ idt(V(tap, p) / 1.s, 0.V)
  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ laplaceNd(V(p, n), Seq(1.real), Seq(1.real))
      I(p, n) <+ whiteNoise(NoiseId("source"), 1.A * 1.A * 1.s)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.V)
      on(cross(1.V)):
        state := state + 1.V

final class ParameterChild(val resistance: Param[Real] = 2.0) extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ V(p, n) / (resistance * 1.Ohm)

final class RangeConstructorDefault extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    val child = instance(new ParameterChild())
    child(_.p) <> p
    child(_.n) <> n

final class ShapedAnalogChild extends Module:
  val count = param(2.integer, range = 2 to 4)
  val p = inout(Electrical, count)
  analogProcedure:
    val values = variable(Vec(Real, count), Vec.fill(Vec(Real, count))(1.real))
    on(cross(values.at(0), Edge.Rising) or initialStep):
      values.writable.at(0) := AnalogMath.abs(values.at(1))

final class RangeShapedAnalog extends Module:
  val p = inout(Electrical, 2)
  hdlRange(0, 2): _ =>
    val child = instance(new ShapedAnalogChild)
    child(_.p.slice(0, 2)) <> p
