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

final class PublicAnalogDimensionlessMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := 0.0.real

final class PublicAnalogCompoundDimensionlessMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := 0.0.real + 1.0.real

final class PublicAnalogCompoundReadDimensionMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := voltage + 1.0.real

final class PublicAnalogNestedCompoundReadDimensionMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := (voltage + 1.0.real) + voltage

final class PublicAnalogBooleanComparisonAssignment extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)
  val flag: Variable[Bool] = variable(Bool, false.B)

  analogProcedure:
    flag := (voltage > 1.0.V) && true.B

final class PublicAnalogMultipleProceduralRegions extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := 1.0.V

  analogProcedure:
    voltage := 2.0.V

final class PublicAnalogIncompatibleComparisonDimensions extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)
  val current: Variable[Real] = variable(Real, 0.0.A)
  val flag: Variable[Bool] = variable(Bool, false.B)

  analogProcedure:
    flag := (voltage > current) && true.B

final class PublicAnalogCompoundVoltage extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := 1.0.V + 2.0.V

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

    test("public dimensionless assignment to a voltage variable is rejected"):
      val failure =
        scala.util.Try(
          ConstructionKernel.inspect(new PublicAnalogDimensionlessMismatch)
        ).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public compound dimensionless assignment to a voltage variable is rejected"):
      val failure =
        scala.util.Try(
          ConstructionKernel.inspect(new PublicAnalogCompoundDimensionlessMismatch)
        ).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public incompatible compound dimensions are rejected without read fallback"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(new PublicAnalogCompoundReadDimensionMismatch)
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public nested incompatible compound dimensions remain unknown"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(
            new PublicAnalogNestedCompoundReadDimensionMismatch
          )
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public comparison rejects incompatible operand dimensions through Boolean logic"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(
            new PublicAnalogIncompatibleComparisonDimensions
          )
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

    test("public Boolean comparison assignment retains result type and dimension"):
      val snapshot =
        ConstructionKernel.inspect(new PublicAnalogBooleanComparisonAssignment)
      val assignment = snapshot.analogProcedural.head.assignments.head
      assert(
        assignment.value.valueType == AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Boolean,
          "dimensionless"
        )
      )
      assert(assignment.value.reads.exists(_.identity.endsWith(".variable_0")))

    test("multiple top-level analogProcedure regions are rejected"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(new PublicAnalogMultipleProceduralRegions)
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-020")

    test("public compound voltage assignment retains its physical dimension"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogCompoundVoltage)
      val assignment = snapshot.analogProcedural.head.assignments.head
      assert(assignment.value.valueType.dimension == "voltage")
    test("public assignment outside analogProcedure is rejected"):
      val failure =
        scala.util.Try(
          ConstructionKernel.inspect(new PublicAnalogAssignmentOutsideProcedure)
        ).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-008")

    test("child procedural snapshot resolves to its authored instance path"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogParentWithChild)
      val procedural = snapshot.analogProcedural.find(_.assignments.nonEmpty).get
      assert(procedural.owner == "PublicAnalogParentWithChild.child")
      assert(
        procedural.assignments.head.identity ==
          "PublicAnalogParentWithChild.child.statement_0"
      )

    test("public cross-component variable assignment is rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new PublicAnalogCrossOwner)).failed.get
          .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-009")
      assert(
        failure.diagnostic.path.contains(
          "PublicAnalogCrossOwner.PublicAnalogChild_0.variable_0"
        )
      )
