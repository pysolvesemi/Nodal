package nodal.internal.testkit

import nodal.*
import nodal.increment40fixture.TransferFilters
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class TransferBody(body: () => Unit) extends Module:
  analog:
    body()

final class TransferOutside extends Module:
  val value = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))

final class TransferEquation extends Module:
  equations:
    val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))

final class TransferProcedure extends Module:
  analogProcedure:
    val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))

final class TransferSymbolicD0 extends Module:
  val denominator = param(1.0.real)
  analog:
    val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(denominator))

final class TransferSymbolicTiming extends Module:
  val period = param(1.0e-3.s)
  analog:
    val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), period)

object AnalogTransferTests extends TestSuite:
  private def code(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get
      .asInstanceOf[ConstructionException].diagnostic.code

  private def reject(body: => Unit, diagnostic: String): Unit =
    assert(code(new TransferBody(() => body)) == diagnostic)

  private def rejectSnapshot(snapshot: ConstructionSnapshot): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.getMessage.contains("NODAL-ANALOG-040-002"))

  val tests: Tests = Tests:
    test("public source preserves six owned states, symbolic coefficients and source maps"):
      val source = ScalaToMlirBridge.lower(new TransferFilters)
      assert(source == ScalaToMlirBridge.lower(new TransferFilters))
      val snapshot = ConstructionKernel.inspect(new TransferFilters)
      val states = snapshot.transferOperators
      assert(states.size == 6, states.map(_.path).distinct.size == 6)
      assert(states.forall(_.resultDimension == "voltage"), states.forall(_.source.nonEmpty))
      assert(states.forall(value => value.path.startsWith(value.owner + ".")))
      assert(source.text.contains("nodal.bridge.transfer_operators"))
      assert(source.text.contains("nodal.parameter_ref"))
      assert(source.text.contains("coefficient_order = \"ascending_s\""))
      assert(source.text.contains("coefficient_order = \"ascending_z_inverse\""))
      assert(states.filter(_.kind == "zi_nd").map(value =>
        value.operands.size - 1 - value.numeratorSize - value.denominatorSize
      ).sorted == Vector(1, 2, 3))

    test("coefficient order and dimensions extend to arbitrary polynomial degree"):
      // Expr values belong to the active elaboration, including array elements.
      val snapshot = ConstructionKernel.inspect(new TransferBody(() =>
        val coefficients = (0 to 8).map: power =>
          (0 until power).foldLeft(1.0.real: Expr[Real])((value, _) => value * 1.0e-3.s)
        val _ = laplaceNd(1.0.A, coefficients, coefficients)
      ))
      assert(snapshot.transferOperators.head.numeratorSize == 9)
      assert(snapshot.transferOperators.head.denominatorSize == 9)
      assert(snapshot.transferOperators.head.resultDimension == "current")
      val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(source.contains("numerator_size = 9 : i64"))
      assert(source.contains("denominator_size = 9 : i64"))
      reject(
        { val _ = laplaceNd(1.0.V, Seq(1.0.real, 1.0.real), Seq(1.0.real)) },
        "NODAL-ANALOG-040-003"
      )
      reject({ val _ = ziNd(1.0.V, Seq(1.0.s), Seq(1.0.real), 1.0e-3.s) }, "NODAL-ANALOG-040-003")
      reject(
        { val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real, 0.0.V)) },
        "NODAL-ANALOG-040-003"
      )
      reject(
        { val _ = laplaceNd(true.B.asInstanceOf[Expr[Real]], Seq(1.0.real), Seq(1.0.real)) },
        "NODAL-ANALOG-040-003"
      )

    test("empty arrays, zero denominators and nonfinite constants fail closed"):
      reject({ val _ = laplaceNd(1.0.V, Seq.empty, Seq(1.0.real)) }, "NODAL-ANALOG-040-002")
      reject({ val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq.empty) }, "NODAL-ANALOG-040-002")
      reject(
        { val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(0.0.real, 1.0.s)) },
        "NODAL-ANALOG-040-004"
      )
      reject(
        { val _ = ziNd(1.0.V, Seq(1.0.real), Seq(-0.0.real), 1.0e-3.s) },
        "NODAL-ANALOG-040-004"
      )
      Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach: value =>
        reject({ val _ = laplaceNd(1.0.V, Seq(value.real), Seq(1.0.real)) }, "NODAL-ANALOG-040-004")
      val _ = ConstructionKernel.inspect(new TransferBody(() =>
        val _ = laplaceNd(0.0.V, Seq(0.0.real), Seq(-2.0.real, 0.0.s))
      ))

    test("coefficients are analysis-static, never sampled runtime values"):
      reject(
        { val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real, abstime)) },
        "NODAL-ANALOG-040-005"
      )
      reject(
        { val _ = ziNd(1.0.V, Seq(abstime / 1.0.s), Seq(1.0.real), 1.0e-3.s) },
        "NODAL-ANALOG-040-005"
      )
      assert(code(new TransferSymbolicD0) == "NODAL-ANALOG-040-006")
      assert(code(new TransferSymbolicTiming) == "NODAL-ANALOG-040-006")

    test("sample timing distinguishes omitted defaults from unsafe explicit zero"):
      reject({ val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 0.0.s) }, "NODAL-ANALOG-040-004")
      reject(
        { val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s, 0.0.s) },
        "NODAL-ANALOG-040-004"
      )
      reject(
        { val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s, 1.0e-6.s, -1.0.s) },
        "NODAL-ANALOG-040-004"
      )
      reject(
        { val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 1.0.real) },
        "NODAL-ANALOG-040-003"
      )
      reject(
        { val _ = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), abstime + 1.0e-3.s) },
        "NODAL-ANALOG-040-006"
      )

    test("stateful filters cannot be created in conditional or declarative contexts"):
      assert(code(new TransferOutside) == "NODAL-ANALOG-040-001")
      assert(code(new TransferEquation) == "NODAL-ANALOG-040-001")
      assert(code(new TransferProcedure) == "NODAL-ANALOG-040-001")
      reject(
        initial { val _ = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real)) },
        "NODAL-ANALOG-040-001"
      )

    test("bridge refuses missing duplicate orphaned and corrupted transfer inventory"):
      val snapshot = ConstructionKernel.inspect(new TransferFilters)
      val first = snapshot.transferOperators.head
      rejectSnapshot(snapshot.copy(transferOperators = snapshot.transferOperators.tail))
      rejectSnapshot(snapshot.copy(transferOperators = snapshot.transferOperators :+ first))
      for replacement <- Vector(
          first.copy(owner = "foreign"),
          first.copy(numeratorSize = 0),
          first.copy(denominatorSize = Int.MaxValue),
          first.copy(path = "orphan"),
          first.copy(kind = "laplace_zp"),
          first.copy(operands = first.operands.reverse)
        )
      do
        rejectSnapshot(snapshot.copy(transferOperators =
          replacement +: snapshot.transferOperators.tail
        ))
