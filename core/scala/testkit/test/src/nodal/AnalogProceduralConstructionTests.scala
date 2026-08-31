package nodal.internal.testkit

import nodal.*

import utest.*

final class PublicAnalogProceduralTop extends Module:
  val previous: Variable[Real] = variable(Real, 0.0.V)
  val scratch: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    scratch := previous
    previous := 1.25.V
    previous := 2.50.V

final class PublicAnalogReadBeforeWrite extends Module:
  val uninitialized: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    sink := uninitialized

final class PublicAnalogDimensionMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := 1.0.A

final class PublicAnalogAssignmentOutsideProcedure extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)
  voltage := 1.0.V

final class PublicAnalogChild extends Module:
  val local: Variable[Real] = variable(Real, 0.0.V)

final class PublicAnalogCrossOwner extends Module:
  val childModule = new PublicAnalogChild
  val child = instance(childModule)

  analogProcedure:
    childModule.local := 1.0.V

final class PublicAnalogNestedChild extends Module:
  val local: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    local := 1.0.V

final class PublicAnalogParentWithChild extends Module:
  val childModule = new PublicAnalogNestedChild
  val child = instance(childModule)

object AnalogProceduralConstructionTests extends TestSuite:
  val tests: Tests = Tests:
    test("public variable and assignment APIs retain authored order"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogProceduralTop)
      assert(snapshot.analogProcedural.size == 1)
      val procedural = snapshot.analogProcedural.head
      assert(procedural.owner == "PublicAnalogProceduralTop")
      assert(procedural.variables.size == 2)
      assert(procedural.assignments.size == 3)
      assert(procedural.assignments.map(_.authoredOrder) == Vector(0, 1, 2))
      assert(procedural.assignments.map(_.identity) == Vector(
        "PublicAnalogProceduralTop.statement_0",
        "PublicAnalogProceduralTop.statement_1",
        "PublicAnalogProceduralTop.statement_2"
      ))
      assert(procedural.assignments.head.target.identity.endsWith(".variable_1"))
      assert(procedural.assignments(1).target.identity.endsWith(".variable_0"))
      assert(procedural.assignments(2).target.identity.endsWith(".variable_0"))

    test("public uninitialized variable read is rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new PublicAnalogReadBeforeWrite)).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-011")

    test("public assignment dimension mismatch is rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new PublicAnalogDimensionMismatch)).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public assignment outside analogProcedure is rejected"):
      val failure =
        scala.util.Try(
          ConstructionKernel.inspect(new PublicAnalogAssignmentOutsideProcedure)
        ).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-008")

    test("child procedural source capture uses its provisional instance path"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogParentWithChild)
      val procedural = snapshot.analogProcedural.find(_.assignments.nonEmpty).get
      assert(
        procedural.owner ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0"
      )
      assert(
        procedural.assignments.head.identity ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0.statement_0"
      )

    test("public cross-component variable assignment is rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new PublicAnalogCrossOwner)).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-009")
