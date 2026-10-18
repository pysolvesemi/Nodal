package nodal.internal.testkit

import nodal.*
import nodal.increment46structuralfixture.*
import utest.*

object TypedEquationRenderBudgetTests extends TestSuite:
  private final class SharedEquation(levels: Int, initializer: Boolean = false) extends Module:
    physicalComponent(StructuralEquationContract.concrete("bounded")):
      val state = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
      var value = state.read
      (0 until levels).foreach: _ =>
        value = value + value
      if initializer then
        analogState("y", PhysicalDimension.Voltage, StateInitialization.Guess(value))
      else
        equations:
          equation(value, 1.0.V, StructuralEquationContract.withId("explicit"))

  private def rejection(body: => Any): ConstructionException =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException]

  val tests = Tests:
    test("bounded shared DAG keeps exact F32 authored text"):
      val snapshot = ConstructionKernel.inspect(new SharedEquation(8))
      var expected = "candidate-analog-state(state:x)"
      (0 until 8).foreach: _ =>
        expected = s"analog_add($expected,$expected)"
      val equation = snapshot.analogSemantics.equations.head
      assert(equation.residual.authoredLeft.rendered == expected)
      assert(equation.residual.authoredRight.rendered == "1.0 V")
      assert(equation.residual.authoredLeft.root.nonEmpty)

    test("large shared DAG rejects before authored expansion even with explicit identity"):
      val error = rejection(ConstructionKernel.inspect(new SharedEquation(40)))
      assert(error.diagnostic.code == "NODAL-ANALOG-134-001")
      assert(error.diagnostic.message.contains("1048576-character"))

    test("state initializer uses the same bounded authored renderer"):
      val error = rejection(ConstructionKernel.inspect(new SharedEquation(40, initializer = true)))
      assert(error.diagnostic.code == "NODAL-ANALOG-134-001")
      assert(error.diagnostic.message.contains("1048576-character"))
