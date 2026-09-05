package nodal.internal.testkit

import nodal.*
import utest.*

final class TimeWaveformTop extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val maximum = param(4.0.ns)
  analog:
    val smoothed = transition(1.0.V, 0.0.real, 1.0.ns, 2.0.ns, 0.0.ns)
    val rate = 1.0.V / 1.0.ns
    val limited = slew(V(positive, negative), rate, -rate)
    val delayed = absdelay(limited, 1.0.ns, maximum)
    val now = abstime
    boundStep(now + 1.0.ns)
    V(positive, negative) <+ smoothed + smoothed + delayed

final class TimeWaveformDefaultsTop extends Module:
  analog:
    val _ = transition(1.0.V)
    val _ = transition(1.0.V, 0.0.s)
    val _ = transition(1.0.V, 0.0.s, 1.0.ns)
    val _ = transition(1.0.V, 0.0.s, 1.0.ns, 2.0.ns)
    val _ = slew(1.0.V)
    val _ = slew(1.0.V, 1.0.V / 1.0.s)
    val _ = absdelay(1.0.V, 2.0.ns)
    val _ = absdelay(1.0.V, abstime + 1.0.ns, 1.0.ns)
    boundStep(0.0.real)

final class TimeWaveformBodyTop(body: () => Unit) extends Module:
  analog:
    body()

final class TimeWaveformEquationTop extends Module:
  equations:
    val _ = slew(2.0.A, 1.0.A / 1.0.s)
    val _ = abstime

final class TimeWaveformContributionTop extends Module:
  contributions:
    val _ = absdelay(1.0.V, 2.0.ns)
    boundStep(1.0.ns)

final class TimeWaveformOutsideTop extends Module:
  val invalid = abstime

final class TimeWaveformInitialTop extends Module:
  initialEquations:
    val _ = transition(1.0.V)

final class TimeWaveformProcedureTop extends Module:
  analogProcedure:
    boundStep(1.0.ns)

object TimeWaveformConstructionTests extends TestSuite:
  private def code(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get
      .asInstanceOf[ConstructionException].diagnostic.code

  private def invalid(body: => Unit, expected: String): Unit =
    assert(code(new TimeWaveformBodyTop(() => body)) == expected)

  val tests: Tests = Tests:
    test("all five operators retain dimensions, states, effects and source maps"):
      val snapshot = ConstructionKernel.inspect(new TimeWaveformTop)
      val operators = snapshot.waveformOperators
      assert(operators.size == 5)
      assert(operators.map(_.path).distinct.size == 5)
      assert(operators.flatMap(_.stateId).size == 3)
      assert(operators.forall(op => op.path.startsWith(s"${op.owner}.")))
      assert(operators.forall(_.source.nonEmpty))
      assert(operators.filter(_.stateId.nonEmpty).forall(op =>
        op.stateId.contains(s"${op.path}.state")
      ))
      val rate = operators.find(_.operation == "analog_slew").get
      assert(rate.operandDimensions == Vector("voltage", "time^-1*voltage", "time^-1*voltage"))
      val task = operators.find(_.operation == "analog_bound_step").get
      assert(
        task.stateId.isEmpty,
        task.resultDimension == "none",
        task.analyses == Vector("transient")
      )
      val time = operators.find(_.operation == "analog_abstime").get
      assert(time.operands.isEmpty, time.resultDimension == "time", time.stateId.isEmpty)
      val other = ConstructionKernel.inspect(new TimeWaveformTop).waveformOperators
      assert(operators.map(_.path) == other.map(_.path))
      assert(operators.flatMap(_.stateId) == other.flatMap(_.stateId))

    test("omitted arguments remain omitted and dynamic delay is not clamped at elaboration"):
      val ops = ConstructionKernel.inspect(new TimeWaveformDefaultsTop).waveformOperators
      val transitions = ops.filter(_.operation == "analog_transition")
      assert(transitions.map(_.operands.size).sorted == Vector(1, 2, 3, 4))
      assert(ops.filter(_.operation == "analog_slew").map(_.operands.size).sorted == Vector(1, 2))
      assert(ops.filter(_.operation == "analog_absdelay").map(_.operands.size).sorted ==
        Vector(2, 3))
      assert(ops.filter(_.operation == "analog_slew").find(_.operands.size ==
        1).get.outputContinuity == "constant")

    test("declarative contexts remain distinct"):
      val equations = ConstructionKernel.inspect(new TimeWaveformEquationTop).waveformOperators
      val contributions =
        ConstructionKernel.inspect(new TimeWaveformContributionTop).waveformOperators
      assert(equations.forall(_.context == "equation"))
      assert(contributions.forall(_.context == "contribution"))

    test("illegal context is rejected including inert candidate event wrappers"):
      assert(code(new TimeWaveformOutsideTop) == "NODAL-ANALOG-036-001")
      assert(code(new TimeWaveformInitialTop) == "NODAL-ANALOG-036-001")
      assert(code(new TimeWaveformProcedureTop) == "NODAL-ANALOG-036-001")
      invalid(initial { val _ = abstime }, "NODAL-ANALOG-036-001")
      invalid(on(timer(1.0.ns)) { val _ = slew(1.0.V) }, "NODAL-ANALOG-037-001")

    test("physical units and signed slew rates are checked"):
      invalid(boundStep(1.0.V), "NODAL-ANALOG-036-003")
      invalid(boundStep(0.0.V), "NODAL-ANALOG-036-003")
      invalid({ val _ = slew(1.0.V, 1.0.V) }, "NODAL-ANALOG-036-003")
      invalid({ val _ = transition(1.0.V, 1.0.real) }, "NODAL-ANALOG-036-003")
      invalid({ val _ = slew(1.0.V, -1.0.V / 1.0.s) }, "NODAL-ANALOG-036-004")
      invalid({ val _ = slew(1.0.V, 1.0.V / 1.0.s, 1.0.V / 1.0.s) }, "NODAL-ANALOG-036-004")

    test("ranges, continuity and maximum-delay constness fail closed"):
      invalid(boundStep(-1.0.ns), "NODAL-ANALOG-036-004")
      invalid(boundStep(Double.PositiveInfinity.s), "NODAL-ANALOG-036-004")
      invalid({ val _ = absdelay(1.0.V, 0.0.s) }, "NODAL-ANALOG-036-004")
      invalid({ val _ = transition(abstime) }, "NODAL-ANALOG-036-005")
      invalid({ val _ = transition(slew(1.0.V, 1.0.V / 1.0.s)) }, "NODAL-ANALOG-036-005")
      invalid({ val _ = absdelay(1.0.V, 1.0.ns, abstime + 1.0.ns) }, "NODAL-ANALOG-036-007")
