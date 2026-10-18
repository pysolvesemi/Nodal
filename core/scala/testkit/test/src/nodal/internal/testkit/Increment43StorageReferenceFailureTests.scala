package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43StorageReferenceCoverageFailure(form: Int, consumer: Int) extends Module:
  val flag = variable(Bool, true.B)
  val cells = variable(Vec(Real, 2))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    if form == 0 then cells.writable.at(0) := 1.0.real
    else if form == 1 then
      analogConditional:
        analogWhen(flag):
          cells.writable.at(0) := 1.0.real
    else if form == 2 then
      on(initialStep):
        cells.writable.at(0) := 1.0.real
    val lane = if form == 0 then 1 else 0
    if consumer == 0 then
      analogConditional:
        analogWhen(cells.at(lane) > 0.0.real):
          sink := 1.0.real
    else if consumer == 1 then
      analogCase(cells.at(lane) > 0.0.real):
        analogCaseArm(true):
          sink := 1.0.real
    else
      on(cross(cells.at(lane))):
        cells.writable.at(lane) := 2.0.real

final class Increment43StorageReferenceScopeFailure(consumer: Int) extends Module:
  val flag = variable(Bool, true.B)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    var escaped: Option[Expr[Real]] = None
    analogConditional:
      analogWhen(flag):
        val local = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
        escaped = Some(local.at(0))
    if consumer == 0 then
      analogConditional:
        analogWhen(escaped.get > 0.0.real):
          sink := 1.0.real
    else if consumer == 1 then
      analogCase(escaped.get > 0.0.real):
        analogCaseArm(true):
          sink := 1.0.real
    else
      on(cross(escaped.get)):
        sink := 1.0.real

final class Increment43StorageReferencePlacementFailure(nested: Boolean) extends Module:
  val flag = variable(Bool, true.B)
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
  analogProcedure:
    if nested then
      on(initialStep):
        on(cross(cells.at(0))):
          cells.writable.at(1) := 2.0.real
    else
      analogConditional:
        analogWhen(flag):
          on(cross(cells.at(0))):
            cells.writable.at(1) := 2.0.real

final class Increment43StorageReferenceFillFailure(compound: Boolean, consumer: Int) extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val selected = Vec.fill(Vec(Real, 2))(1.0.real).reshape(1, 2).row(0).at(0)
    val value = if compound then (cells.at(0) + 2.0.real) * (selected + 3.0.real) else selected
    if consumer == 0 then
      analogConditional:
        analogWhen(value > 0.0.real):
          sink := 1.0.real
    else if consumer == 1 then
      analogCase(value > 0.0.real):
        analogCaseArm(true):
          sink := 1.0.real
    else
      on(cross(value)):
        sink := 1.0.real

final class Increment43StorageReferenceBridgeInput extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    analogConditional:
      analogWhen(cells.at(0) > 0.0.real):
        sink := 1.0.real
    analogCase(cells.at(1) > 0.0.real):
      analogCaseArm(true):
        sink := 2.0.real
    on(cross(cells.at(0))):
      sink := 3.0.real

object Increment43StorageReferenceFailureTests extends TestSuite:
  import AnalogControlFlowRuntime.Statement
  import AnalogProceduralRuntime.{ShapedSource, StorageReference, StorageSelection}

  private def failure(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.getMessage

  private def bridgeFailure(snapshot: ConstructionSnapshot, message: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.getMessage.contains("NODAL-BRIDGE-049"), error.getMessage.contains(message))

  private def rewrite(
      snapshot: ConstructionSnapshot
  )(transform: AnalogControlFlowConstruction.Snapshot => AnalogControlFlowConstruction.Snapshot)
      : ConstructionSnapshot =
    assert(snapshot.analogProcedural.size == 1)
    val program = snapshot.analogProcedural.head
    snapshot.copy(analogProcedural =
      Vector(program.copy(controlFlow = program.controlFlow.map(transform)))
    )

  private def malformed(reference: StorageReference, form: Int): StorageReference = reference match
    case selection: StorageSelection =>
      if form == 0 then selection.copy(root = selection.root.copy(owner = "Foreign"))
      else
        selection.copy(projections = selection.projections.map:
          case index: KernelShapeIndexSnapshot => index.copy(indices = Vector("2"))
          case other => other)
    case _ => throw new java.lang.AssertionError("expected a selected storage leaf")

  private def malformedReferences(references: Vector[StorageReference], form: Int)
      : Vector[StorageReference] = form match
    case 2 => Vector.empty
    case 3 => references ++ references
    case _ => references.map(malformed(_, form))

  val tests: Tests = Tests:
    test("control and event leaves require incoming selected initialization"):
      for form <- 0 to 2; consumer <- 0 to 2 do
        val error = failure(new Increment43StorageReferenceCoverageFailure(form, consumer))
        assert(
          error.contains("NODAL-ANALOG-034-004"),
          error.contains("before definite initialization")
        )
        assert(error.contains("Increment43StorageReferenceCoverageFailure."))
      val ownHandlerWrite = failure(new Increment43StorageReferenceCoverageFailure(3, 2))
      assert(
        ownHandlerWrite.contains("NODAL-ANALOG-034-004"),
        ownHandlerWrite.contains("Increment43StorageReferenceCoverageFailure.")
      )

    test("selected leaves preserve lexical scope and event placement"):
      for consumer <- 0 to 2 do
        val error = failure(new Increment43StorageReferenceScopeFailure(consumer))
        assert(error.contains("NODAL-ANALOG-033-010"), error.contains("outside its lexical scope"))
      val nested = failure(new Increment43StorageReferencePlacementFailure(true))
      assert(nested.contains("NODAL-ANALOG-037-007"), nested.contains("cannot be nested"))
      val conditional = failure(new Increment43StorageReferencePlacementFailure(false))
      assert(
        conditional.contains("NODAL-ANALOG-037-001"),
        conditional.contains("runtime conditional")
      )

    test("pure fill scalar projections remain unsupported even inside compound expressions"):
      for compound <- Vector(false, true); consumer <- 0 to 2 do
        val error = failure(new Increment43StorageReferenceFillFailure(compound, consumer))
        assert(error.contains("NODAL-SHAPE-043-007"), error.contains("deferred shaped values"))

    test("bridge checks selected if and case roots bounds and complete inventories"):
      val snapshot = ConstructionKernel.inspect(new Increment43StorageReferenceBridgeInput)
      val valid = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(
        valid.contains("reference_form = \"storage\""),
        !valid.contains("nodal.analog_variable_read")
      )
      val messages = Vector(
        "forged or missing root",
        "not in bounds",
        "incomplete or ambiguous",
        "incomplete or ambiguous"
      )
      for consumer <- 0 to 1; form <- messages.indices do
        val forged = rewrite(snapshot): control =>
          control.copy(root = control.root.copy(statements = control.root.statements.map:
            case branch: Statement.IfThenElse if consumer == 0 =>
              branch.copy(branches = branch.branches.map: arm =>
                arm.copy(condition =
                  arm.condition.copy(selectedReads =
                    malformedReferences(arm.condition.selectedReads, form)
                  )
                ))
            case selection: Statement.CaseStatement if consumer == 1 =>
              selection.copy(selector =
                selection.selector.copy(selectedReads =
                  malformedReferences(selection.selector.selectedReads, form)
                )
              )
            case other => other))
        bridgeFailure(forged, messages(form))

    test("event typed sources retain selected leaves when flattened reads are omitted"):
      val snapshot = ConstructionKernel.inspect(new Increment43StorageReferenceBridgeInput)
      val withoutFlattenedReads = rewrite(snapshot): control =>
        control.mapEvents: event =>
          val changed = event.copy(arguments = event.arguments.map: argument =>
            assert(argument.value.source.exists(_.isInstanceOf[ShapedSource.Read]))
            argument.copy(value = argument.value.copy(reads = Vector.empty)))
          assert(changed.reads == event.reads, changed.reads.nonEmpty)
          changed
      val valid = ScalaToMlirBridge.fromSnapshot(withoutFlattenedReads).text
      assert(
        valid.contains("reference_form = \"storage\""),
        !valid.contains("nodal.analog_variable_read")
      )
      for (form, message) <- Vector(0 -> "forged or missing root", 1 -> "not in bounds") do
        val forged = rewrite(snapshot): control =>
          control.mapEvents: event =>
            event.copy(arguments = event.arguments.map: argument =>
              val reference = argument.value.allReads.distinct.head
              argument.copy(value =
                argument.value.copy(
                  reads = Vector.empty,
                  source = Some(ShapedSource.Read(malformed(reference, form)))
                )
              ))
        bridgeFailure(forged, message)
