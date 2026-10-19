package nodal.internal.testkit

import nodal.*
import nodal.increment47fixture.AnalogCapabilityStorage
import nodal.internal.bridge.*
import nodal.sim.*
import utest.*

/** Straight-line updates remain executable beside an independent continuous contribution. */
final class Increment52ProceduralSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val held = variable(Real, 0.0.V)
  val scratch = variable(Real, 0.0.V)
  analogProcedure:
    scratch := held
    held := 1.0.V
    held := 2.0.V
  analog:
    I(positive, negative) <+ V(positive, negative) / 1000.0.Ohm

/** Generated procedures already own their assignments and must not gain generic effects. */
final class Increment52GeneratedSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val held = variable(Real, 0.0.V)
      on(initialStep):
        held := 1.0.V
  analog:
    I(positive, negative) <+ V(positive, negative) / 1000.0.Ohm

final class Increment52RejectedSource(effect: String) extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val held = variable(Real, 0.0.V)
  analog:
    I(positive, negative) <+ V(positive, negative) / 1000.0.Ohm
  effect match
    case "digital" =>
      val scratch = wire(Bool)
      scratch := true.B
    case "unrepresented" => solverHints(SolverHint.MaximumStep(1.0.ns))
    case "outside" => held := 1.0.V
    case "declarative" =>
      equations:
        held := 1.0.V
    case "continuous" =>
      analog:
        held := 1.0.V
    case "uninitialized" =>
      val uninitialized = variable(Real)
      val sink = variable(Real, 0.0.real)
      analogProcedure:
        sink := uninitialized
    case "conditional-initialization" =>
      val uninitialized = variable(Real)
      val sink = variable(Real, 0.0.real)
      analogProcedure:
        on(initialStep):
          uninitialized := 1.0.real
        sink := uninitialized
    case "dimension" =>
      analogProcedure:
        held := 1.0.A
    case "caught-dimension" =>
      analogProcedure:
        val rejected = scala.util.Try(held := 1.0.A)
        require(rejected.failed.get.getMessage.contains("NODAL-ANALOG-033-013"))
    case "event-contribution" =>
      analogProcedure:
        on(initialStep):
          V(positive, negative) <+ 1.0.V
    case _ => ()

object Increment52AdmissionTests extends TestSuite:
  private def stateSource(): Either[SimulationError, SimulationSource] =
    SimulationSource.capture(new AnalogCapabilityStorage)(model =>
      Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
    )

  val tests: Tests = Tests:
    test("public capture admits the F49 initial-step storage witness without private export"):
      val captured = stateSource()
      assert(captured.isRight)
      val source = captured.toOption.get
      val snapshot = ConstructionKernel.inspect(new AnalogCapabilityStorage)
      assert(source.canonicalConstruction == ReproducibilityContract.canonicalSnapshot(snapshot))
      assert(source.canonicalConstruction == stateSource().toOption.get.canonicalConstruction)
      val program = snapshot.analogProcedural.head
      assert(program.variables.size == 3, program.controlFlow.nonEmpty)
      assert(program.assignments.isEmpty)
      val events = program.controlFlow.get.root.statements.collect:
        case event: AnalogControlFlowRuntime.Statement.EventControl => event
      assert(events.size == 1, events.head.event.operation == "analog_initial_step")
      assert(program.controlExpressions.count(_.role == "assignment-value") == 5)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
      assert(document.text.contains("\"nodal.analog_initial_step\""))
      assert(document.text.contains("\"nodal.analog_assign\""))
      assert(document.text.contains("\"nodal.analog_held_read\""))

    test("ordinary analog attribution preserves ordered assignments and continuous equations"):
      val source = SimulationSource.capture(new Increment52ProceduralSource)(model =>
        Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
      )
      assert(source.isRight)
      val captured = ConstructionKernel.capture(new Increment52ProceduralSource)(_ => Vector.empty)
      assert(captured.operationKinds.contains("analog-assignment"))
      assert(captured.operationKinds.contains("analog-contribute"))
      assert(!captured.operationKinds.contains("assignment"))
      val program = captured.snapshot.analogProcedural.head
      assert(program.assignments.map(_.authoredOrder) == Vector(0, 1, 2))
      assert(program.assignments.map(_.target.identity) == Vector(
        "Increment52ProceduralSource.procedure.variable_1",
        "Increment52ProceduralSource.procedure.variable_0",
        "Increment52ProceduralSource.procedure.variable_0"
      ))
      assert(program.assignments.forall(_.source.nonEmpty))
      assert(captured.snapshot.analogRegions.flatMap(_.contributions).size == 1)
      assert(source.toOption.get.canonicalConstruction ==
        ReproducibilityContract.canonicalSnapshot(captured.snapshot))

    test("generated procedure attribution and ownership remain unchanged"):
      val source = SimulationSource.capture(new Increment52GeneratedSource)(model =>
        Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
      )
      assert(source.isRight)
      val captured = ConstructionKernel.capture(new Increment52GeneratedSource)(_ => Vector.empty)
      assert(!captured.operationKinds.contains("assignment"))
      assert(!captured.operationKinds.contains("analog-assignment"))
      val region = captured.snapshot.generatedRegions.head
      val program = captured.snapshot.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      assert(program.controlExpressions.count(_.role == "assignment-value") == 1)
      val document = ScalaToMlirBridge.fromSnapshot(captured.snapshot, Backend.VerilogA)
      assert(document.text.contains("\"nodal.generate\""))
      assert(document.text.contains("\"nodal.analog_initial_step\""))
      assert(document.text.contains(s"generated_owner = \"${region.path}\""))

    test("generic digital and unrepresented effects remain excluded from public capture"):
      val cases = Vector(
        "digital" -> "source operation has no supported simulator lowering: assignment",
        "unrepresented" -> "unrepresented source effect: candidate-solver-hints"
      )
      cases.foreach: (effect, expected) =>
        val source = SimulationSource.capture(new Increment52RejectedSource(effect))(model =>
          Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
        )
        assert(source.left.toOption.exists(error =>
          error.code == "SIM-CAPTURE" && error.message.contains(expected)
        ))

    test("public capture retains placement dimensions and definite-assignment checks"):
      val cases = Vector(
        "outside" -> "NODAL-ANALOG-033-008",
        "declarative" -> "NODAL-ANALOG-133-007",
        "continuous" -> "NODAL-ANALOG-033-008",
        "uninitialized" -> "NODAL-ANALOG-033-011",
        "conditional-initialization" -> "NODAL-ANALOG-034-004",
        "dimension" -> "NODAL-ANALOG-033-013",
        "caught-dimension" -> "NODAL-CONSTRUCTOR-LIFECYCLE-021",
        "event-contribution" -> "NODAL-ANALOG-037-007"
      )
      cases.foreach: (effect, expected) =>
        val source = SimulationSource.capture(new Increment52RejectedSource(effect))(model =>
          Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
        )
        assert(source.left.toOption.exists(error =>
          error.code == "SIM-CAPTURE" && error.message.contains(expected)
        ))
      assert(stateSource().isRight)
