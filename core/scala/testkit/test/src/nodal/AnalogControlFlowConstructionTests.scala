package nodal.internal.testkit

import nodal.*

import utest.*

final class PublicAnalogConditionalComplete extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        value := 1.0.real
      analogOtherwise:
        value := 2.0.real
    sink := value

final class PublicAnalogConditionalMissingElse extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        value := 1.0.real
    sink := value

final class PublicAnalogCaseComplete extends Module:
  val mode: Variable[Integer] = variable(Integer, 0.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogCase(mode):
      analogCaseArm(0):
        value := 1.0.real
      analogCaseArm(1, 2):
        value := 2.0.real
      analogCaseDefault:
        value := 3.0.real
    sink := value

final class PublicAnalogCaseMissingDefault extends Module:
  val mode: Variable[Integer] = variable(Integer, 0.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogCase(mode):
      analogCaseArm(0):
        value := 1.0.real
      analogCaseArm(1):
        value := 2.0.real
    sink := value

final class PublicAnalogStaticSelection extends Module:
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogStaticWhen(false):
        sink := value
      analogStaticElseWhen(true):
        value := 4.0.real
    sink := value

final class PublicAnalogGuaranteedLoop extends Module:
  val iterations: Variable[Integer] = variable(Integer, 1.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      value := 1.0.real
    sink := value

final class PublicAnalogZeroMinimumLoop extends Module:
  val iterations: Variable[Integer] = variable(Integer, 0.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogLoop(iterations, maximumIterations = 4):
      value := 1.0.real
    sink := value

final class PublicAnalogContinuePath extends Module:
  val iterations: Variable[Integer] = variable(Integer, 1.integer)
  val skip: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      analogConditional:
        analogWhen(skip):
          analogContinue()
        analogOtherwise:
          value := 1.0.real
    sink := value

final class PublicAnalogBreakAfterAssignment extends Module:
  val iterations: Variable[Integer] = variable(Integer, 1.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      value := 1.0.real
      analogBreak()
    sink := value

final class PublicAnalogIllegalStaticBreak extends Module:
  analogProcedure:
    analogRepeat(1):
      analogBreak()

final class PublicAnalogBranchLocalDeclaration extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        val local = variable(Real, 1.0.real)
        sink := local
      analogOtherwise:
        sink := 2.0.real

final class PublicAnalogControlChild extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        value := 1.0.real
      analogOtherwise:
        value := 2.0.real
    sink := value

final class PublicAnalogControlParent extends Module:
  val childModule = new PublicAnalogControlChild
  val child = instance(childModule)

final class PublicAnalogControlForeignChild extends Module:
  val local: Variable[Real] = variable(Real, 0.0.real)

final class PublicAnalogControlCrossOwner extends Module:
  val childModule = new PublicAnalogControlForeignChild
  val child = instance(childModule)
  val select: Variable[Bool] = variable(Bool, false.B)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        childModule.local := 1.0.real
      analogOtherwise:
        sink := 1.0.real

object AnalogControlFlowConstructionTests extends TestSuite:
  private def controlFailure(top: => Module): AnalogControlFlowRuntime.Failure =
    scala.util
      .Try(AnalogControlFlowInspection.inspect(top))
      .failed
      .get
      .asInstanceOf[AnalogControlFlowRuntime.Failure]

  val tests: Tests = Tests:
    test("public conditional retains branches and establishes definite assignment"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogConditionalComplete)
      assert(inspection.controlFlow.size == 1)
      val snapshot = inspection.controlFlow.head
      assert(snapshot.owner == "PublicAnalogConditionalComplete")
      assert(snapshot.analysis.definitelyInitialized.exists(_.endsWith(".variable_1")))
      assert(snapshot.root.statements.exists(
        _.isInstanceOf[AnalogControlFlowRuntime.Statement.IfThenElse]
      ))
      assert(inspection.construction.analogProcedural.head.assignments.isEmpty)

    test("public conditional missing else preserves the unmatched incoming path"):
      val failure = controlFailure(new PublicAnalogConditionalMissingElse)
      assert(failure.diagnostic.code == "NODAL-ANALOG-034-004")

    test("public case with default establishes definite assignment"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogCaseComplete)
      val snapshot = inspection.controlFlow.head
      assert(snapshot.analysis.definitelyInitialized.exists(_.endsWith(".variable_1")))
      val selection = snapshot.root.statements.collectFirst:
        case value: AnalogControlFlowRuntime.Statement.CaseStatement => value
      assert(selection.exists(_.arms.size == 2))
      assert(selection.flatMap(_.default).nonEmpty)

    test("public case without default preserves the unmatched incoming path"):
      val failure = controlFailure(new PublicAnalogCaseMissingDefault)
      assert(failure.diagnostic.code == "NODAL-ANALOG-034-004")

    test("static false branch remains retained but does not read on a reachable path"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogStaticSelection)
      val conditional = inspection.controlFlow.head.root.statements.collectFirst:
        case value: AnalogControlFlowRuntime.Statement.IfThenElse => value
      assert(conditional.exists(_.branches.size == 2))
      assert(
        conditional.exists(_.branches.head.condition.staticValue.contains(false))
      )
      assert(
        inspection.controlFlow.head.analysis.definitelyInitialized.exists(
          _.endsWith(".variable_0")
        )
      )

    test("runtime loop with a guaranteed iteration establishes initialization"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogGuaranteedLoop)
      assert(
        inspection.controlFlow.head.analysis.definitelyInitialized.exists(
          _.endsWith(".variable_1")
        )
      )

    test("zero-minimum runtime loop does not establish initialization"):
      val failure = controlFailure(new PublicAnalogZeroMinimumLoop)
      assert(failure.diagnostic.code == "NODAL-ANALOG-034-004")

    test("continue path participates in conservative loop exit intersection"):
      val failure = controlFailure(new PublicAnalogContinuePath)
      assert(failure.diagnostic.code == "NODAL-ANALOG-034-004")

    test("break after assignment preserves the assigned exit state"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogBreakAfterAssignment)
      assert(
        inspection.controlFlow.head.analysis.definitelyInitialized.exists(
          _.endsWith(".variable_1")
        )
      )

    test("break is rejected in an exact static loop"):
      val failure = controlFailure(new PublicAnalogIllegalStaticBreak)
      assert(failure.diagnostic.code == "NODAL-ANALOG-034-010")

    test("branch-local declaration remains nested in the structured tree"):
      val inspection = AnalogControlFlowInspection.inspect(
        new PublicAnalogBranchLocalDeclaration
      )
      val conditional = inspection.controlFlow.head.root.statements.collectFirst:
        case value: AnalogControlFlowRuntime.Statement.IfThenElse => value
      val declarations = conditional.toVector.flatMap(_.branches).flatMap: branch =>
        branch.body.statements.collect:
          case value: AnalogControlFlowRuntime.Statement.Declare => value
      assert(declarations.size == 1)
      assert(declarations.head.local)
      assert(declarations.head.variable.contains(".if_"))

    test("child control-flow snapshot resolves to the authored instance path"):
      val inspection = AnalogControlFlowInspection.inspect(new PublicAnalogControlParent)
      assert(inspection.controlFlow.size == 1)
      assert(inspection.controlFlow.head.owner == "PublicAnalogControlParent.child")
      assert(
        inspection.controlFlow.head.root.identity ==
          "PublicAnalogControlParent.child.procedure"
      )

    test("structured branch assignment rejects a foreign component variable"):
      val failure = scala.util
        .Try(AnalogControlFlowInspection.inspect(new PublicAnalogControlCrossOwner))
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-009")
