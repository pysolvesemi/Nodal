package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43SymbolicGeneratedNode extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43LiteralGeneratedNode extends Module:
  hdlRange(0, 3): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43MissingRange extends Module:
  val lanes: Param[Integer] = param(2.integer)

  hdlRange(0, lanes): _ =>
    ()

final class Increment43AmbiguousDirection extends Module:
  val upper: Param[Integer] = param(1.integer, range = -1 to 3)

  hdlRange(0, upper): _ =>
    ()

final class Increment43MaximumTooSmall extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes, 1, 2): _ =>
    ()

final class Increment43UnsupportedGeneratedWire extends Module:
  hdlRange(0, 2): _ =>
    val generatedWire = wire(UInt(1))
    val _ = generatedWire
    ()

final class Increment43UnsupportedGeneratedVariable extends Module:
  hdlRange(0, 2): _ =>
    val state = variable(Real, 0.0.V)
    val _ = state
    ()

final class Increment43ForgedGeneratedVariableCarrier extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      val forged = new Object
      CandidateRuntime.declare(
        forged,
        KernelSignalKind.Variable,
        dataType = Some(Real)
      )
      state := state + 1.0.V

final class Increment43ForgedGeneratedAssignmentCarrier extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      ConstructionKernel.operation("assignment", state, state)

final class Increment43GeneratedLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      state := state + 1.0.V

final class Increment43GeneratedStructuredLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogConditional:
        analogWhen(select):
          state := state + 1.0.real
        analogOtherwise:
          state := state + 2.0.real
      analogCase(mode):
        analogCaseArm(0):
          state := state + 3.0.real
        analogCaseDefault:
          state := state + 4.0.real
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        state := state + 5.0.real

final class Increment43GeneratedEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        state := state + 1.0.V

final class Increment43UnsupportedGeneratedContinuousOperator extends Module:
  hdlRange(0, 2): _ =>
    val state = ddt(1.0.V)
    val _ = state
    ()

final class Increment43GeneratedContinuousOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val derivative = ddt(1.0.V)
      val integral = idt(1.0.V)
      val _ = (derivative, integral)

final class Increment43GeneratedWaveformOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val smoothed = transition(1.0.V)
      val limited = slew(1.0.V)
      val delayed = absdelay(1.0.V, 1.0.ns)
      val _ = (smoothed, limited, delayed)

final class Increment43GeneratedTransferOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val continuous = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))
      val sampled = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 1.0.ns)
      val _ = (continuous, sampled)

final class Increment43UnsupportedGeneratedTransferOperator extends Module:
  hdlRange(0, 2): _ =>
    val filtered = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))
    val _ = filtered

final class Increment43GeneratedNoiseOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      val white = whiteNoise(NoiseId("generated white"), density)
      val flicker = flickerNoise(NoiseId("generated flicker"), density, 1.0)
      val table = tableNoise(
        NoiseId("generated table"),
        Seq(NoisePoint(1.0.real / 1.0.s, density))
      )
      val _ = (white, flicker, table)

final class Increment43GeneratedContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ 1.0.V
      I(p, n) <+ 1.0.A

final class Increment43GeneratedDdtContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val voltage = V(p, n)
      I(p, n) <+ (1.pF * ddt(voltage))

final class Increment43GeneratedIdtContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val voltageRate = 1.0.V / 1.0.s
      V(p, n) <+ idt(voltageRate, 0.0.V)

final class Increment43GeneratedTransitionContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ transition(level)

final class Increment43GeneratedSlewContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ slew(level)

final class Increment43GeneratedAbsdelayContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ absdelay(level, 1.0.ns)

final class Increment43GeneratedLaplaceContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ laplaceNd(V(p, n), Seq(1.0.real), Seq(1.0.real))

final class Increment43GeneratedZiContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ ziNd(V(p, n), Seq(1.0.real), Seq(1.0.real), 1.0.ns)

final class Increment43GeneratedWhiteNoiseContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      I(p, n) <+ whiteNoise(NoiseId("generated white"), density)

final class Increment43GeneratedFlickerNoiseContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      I(p, n) <+ flickerNoise(NoiseId("generated flicker"), density, 1.0)

final class Increment43SymbolicGeneratedContribution extends Module:
  val lanes: Param[Integer] = param(4.integer, range = 1 to 8)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, lanes): _ =>
    analog:
      V(p, n) <+ 1.0.V

final class Increment43CompoundGeneratedContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 1 to 4)
  val extra: Param[Integer] = param(1.integer, range = 1 to 2)
  val limit: Expr[Integer] = lanes + extra
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, limit): _ =>
    analog:
      V(p, n) <+ 1.0.V

final class Increment43UnsupportedGeneratedNoiseOperator extends Module:
  hdlRange(0, 2): _ =>
    val source = whiteNoise(NoiseId("unsupported generated"), 1.0.A * 1.0.A * 1.0.s)
    val _ = source

final class Increment43UnsupportedGeneratedAbstime extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val currentTime = abstime
      val _ = currentTime

final class Increment43UnsupportedGeneratedBoundStep extends Module:
  hdlRange(0, 2): _ =>
    analog:
      boundStep(1.0.ns)

final class Increment43UnsupportedGeneratedEvent extends Module:
  hdlRange(0, 2): _ =>
    val event = cross(1.0.V)
    val _ = event
    ()

final class Increment43UnsupportedGeneratedAnalogRegion extends Module:
  hdlRange(0, 2): _ =>
    analog:
      ()

final class Increment43UnsupportedGeneratedEquationRegion extends Module:
  hdlRange(0, 2): _ =>
    equations:
      ()

final class Increment43UnsupportedGeneratedInitialEquationRegion extends Module:
  hdlRange(0, 2): _ =>
    initialEquations:
      ()

final class Increment43UnsupportedGeneratedContributionRegion extends Module:
  hdlRange(0, 2): _ =>
    contributions:
      ()

final class Increment43UnsupportedGeneratedBareProceduralRegion extends Module:
  hdlRange(0, 2): _ =>
    CandidateRuntime.analogSemanticBlock(AnalogEquationRuntime.RegionKind.Procedural, ())

final class Increment43UnsupportedGeneratedProcedure extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      ()

final class Increment43RepeatedSymbolicGeneratedNodes extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes): _ =>
    val first = node(Electrical)
    val _ = first
    ()

  hdlRange(0, lanes): _ =>
    hdlRange(0, lanes): _ =>
      val nested = node(Electrical)
      val _ = nested
      ()

object Increment43RangeConstructionTests extends TestSuite:
  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-increment43-range-")

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def constructionFailure(body: => Any): ConstructionException =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("bounded symbolic hdlRange captures stable generated ownership"):
      val first = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val second = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.generatedRegions.size == 1)
      val region = first.generatedRegions.head
      assert(region.owner == first.root)
      assert(region.parent.isEmpty)
      assert(region.lower == "0")
      assert(region.upperExclusive.endsWith(".lanes"))
      assert(region.step == "1")
      assert(region.maximum.isEmpty)
      assert(region.maximumTripCount == 4)
      assert(region.declarations.size == 1)
      assert(region.declarations.head.endsWith(".tap"))

      val parameter = first.modules.head.declarations.find(_.name == "lanes").get
      val attributes = parameter.attributes.toMap
      assert(attributes("integer_range_lower") == "1")
      assert(attributes("integer_range_upper") == "4")
      assert(attributes("classification") == "structural")
      assert(attributes("structural_effects") == "topology")

    test("literal hdlRange retains a concrete finite envelope"):
      val snapshot = ConstructionKernel.inspect(new Increment43LiteralGeneratedNode)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "0")
      assert(region.upperExclusive == "3")
      assert(region.step == "1")
      assert(region.maximumTripCount == 3)

    test("bridge emits native parameter range envelope and generated analog node"):
      val first = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
      val second = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.text.contains("\"nodal.parameter_constraint\""))
      assert(first.text.contains("\"nodal.parameter_envelope\""))
      assert(first.text.contains("classification = \"structural\""))
      assert(first.text.contains("policy = \"static_generate\""))
      assert(first.text.contains("effects = [\"topology\"]"))
      assert(!first.text.contains("effects = [\"generate\"]"))
      assert(first.text.contains("\"nodal.generate\""))
      assert(first.text.contains("\"nodal.node\""))
      assert(first.text.contains("maximum_trip_count = 4 : i64"))
      assert(first.text.contains("upper = @lanes"))
      assert(first.text.indexOf("\"nodal.generate\"") < first.text.indexOf("\"nodal.node\""))

    test("repeated nested generation retains one canonical topology effect"):
      val first = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
      val second = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
      assert(first == second)
      assert(first.generatedRegions.size == 3)
      assert(first.generatedRegions.count(_.parent.nonEmpty) == 1)
      assert(first.generatedRegions.flatMap(_.declarations).distinct.size == 2)
      val parameter = first.modules.head.declarations.find(_.name == "lanes").get
      assert(parameter.attributes.toMap.apply("structural_effects") == "topology")
      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.contains("effects = [\"topology\"]"))
      assert(!document.text.contains("effects = [\"generate\"]"))

    test("bridge rejects missing empty and unsupported structural effects"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val parameter = snapshot.modules.head.declarations.find(_.name == "lanes").get
      val invalidEffects = Vector(
        None,
        Some(""),
        Some(" , "),
        Some("generate"),
        Some("topology,generate")
      )
      for effect <- invalidEffects do
        val declarations = snapshot.modules.head.declarations.map: declaration =>
          if declaration.path != parameter.path then declaration
          else
            declaration.copy(
              attributes = declaration.attributes.filterNot(_._1 == "structural_effects") ++
                effect.toVector.map(value => "structural_effects" -> value)
            )
        val forged = snapshot.copy(
          modules = snapshot.modules.updated(
            0,
            snapshot.modules.head.copy(declarations = declarations)
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
        assert(failure.diagnostic.semanticPath.contains(parameter.path))

    test("bridge rejects forged generated ownership and parent cycles"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val region = snapshot.generatedRegions.head
      val forgeries = Vector(
        region.copy(owner = "ForeignModule"),
        region.copy(induction = "ForeignModule.expr_0"),
        region.copy(path = region.path + " "),
        region.copy(induction = region.induction + " "),
        region.copy(declarations = Vector("ForeignModule.tap")),
        region.copy(parent = Some(region.path))
      )
      for forgedRegion <- forgeries do
        val forged = snapshot.copy(generatedRegions = Vector(forgedRegion))
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

    test("symbolic generation rejects missing finite parameter ranges"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MissingRange)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("symbolic generation rejects direction that changes over legal settings"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43AmbiguousDirection)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-003")

    test("explicit maximum is enforced against the proven parameter envelope"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MaximumTooSmall)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("unsupported generated object kinds fail closed"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43UnsupportedGeneratedWire)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")

    test("generated lexical storage retains immediate region and induction ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
      assert(first == second)
      assert(first.analogProcedural.size == 1)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      assert(program.variables.size == 1)
      assert(program.assignments.size == 1)

      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.contains("\"nodal.generate\""))
      assert(document.text.contains("\"nodal.analog_procedure\""))
      assert(document.text.contains("\"nodal.analog_variable\""))
      assert(document.text.indexOf("\"nodal.generate\"") <
        document.text.indexOf("\"nodal.analog_procedure\""))
      assert(document.text.contains(s"generated_owner = \"${region.path}\""))
      assert(document.text.contains(s"generated_induction = \"${region.induction}\""))

      val forgedPrograms = Vector(
        program.copy(generatedOwner = None),
        program.copy(generatedOwner = Some(region.path + ".forged")),
        program.copy(generatedInduction = Some(region.induction + ".forged")),
        program.copy(variables = program.variables.map(_.copy(authoredPath = None))),
        program.copy(variables =
          program.variables.map(
            _.copy(authoredPath = Some(region.path + ".forged"))
          )
        )
      )
      forgedPrograms.foreach: forgedProgram =>
        val forged = first.copy(analogProcedural = Vector(forgedProgram))
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
          .asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      val missingVariableDeclaration = first.copy(
        generatedRegions = Vector(region.copy(declarations = Vector.empty))
      )
      val missingFailure = scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(missingVariableDeclaration)
      ).failed.get.asInstanceOf[BridgeException]
      assert(missingFailure.diagnostic.code == "NODAL-BRIDGE-043")

    test("generated structured lexical storage preserves one owned control tree"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedStructuredLexicalStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedStructuredLexicalStorage)
      assert(first == second)
      assert(first.analogProcedural.size == 1)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      assert(program.variables.size == 4)
      assert(program.assignments.isEmpty)
      assert(program.controlFlow.nonEmpty)

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector(
          "nodal.analog_if",
          "nodal.analog_case",
          "nodal.analog_loop",
          "nodal.analog_variable",
          "nodal.analog_variable_read",
          "nodal.analog_assign"
        )
      do assert(document.text.contains(s"\"$operation\""))
      val ownerMarker = s"generated_owner = \"${region.path}\""
      val inductionMarker = s"generated_induction = \"${region.induction}\""
      assert(document.text.sliding(ownerMarker.length).count(_ == ownerMarker) > 8)
      assert(document.text.sliding(inductionMarker.length).count(_ == inductionMarker) > 8)

      val forgedProgram = program.copy(generatedInduction = Some(region.induction + ".forged"))
      val failure = scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(first.copy(analogProcedural = Vector(forgedProgram)))
      ).failed.get.asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(failure.diagnostic.message.contains("ownership"))

    test("generated analog event retains exact occurrence ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
      assert(first == second)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      val events = program.controlFlow.get.root.statements.collect:
        case event: AnalogControlFlowRuntime.Statement.EventControl => event
      assert(events.size == 1)
      assert(events.head.event.operation == "analog_cross")

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector("nodal.analog_cross", "nodal.analog_on") do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))

    test("generated continuous operators retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedContinuousOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedContinuousOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      val analog = first.analogRegions.head
      assert(analog.generatedOwner.contains(generated.path))
      assert(analog.generatedInduction.contains(generated.induction))
      assert(first.continuousOperators.map(_.operation) == Vector("analog_ddt", "analog_idt"))
      assert(first.continuousOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.continuousOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.indexOf("\"nodal.generate\"") <
        document.text.indexOf("\"nodal.analog\""))
      for operation <- Vector("nodal.analog", "nodal.analog_ddt", "nodal.analog_idt") do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.continuousOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        continuousOperators = forgedOperator +: first.continuousOperators.tail
      )
      val forgeries = Vector(
        forged,
        first.copy(analogRegions = Vector(analog.copy(generatedOwner = None))),
        first.copy(analogRegions =
          Vector(
            analog.copy(generatedOwner = Some(generated.path + ".forged"))
          )
        )
      )
      forgeries.foreach: candidate =>
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(candidate)).failed.get
          .asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

    test("generated waveform operators retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.waveformOperators.map(_.operation) ==
        Vector("analog_transition", "analog_slew", "analog_absdelay"))
      assert(first.waveformOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.waveformOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector(
          "nodal.analog",
          "nodal.analog_transition",
          "nodal.analog_slew",
          "nodal.analog_absdelay"
        )
      do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.waveformOperators.head.copy(generatedInduction = None)
      val forged = first.copy(
        waveformOperators = forgedOperator +: first.waveformOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                for operation <- Vector(
                    "nodal.analog_transition",
                    "nodal.analog_slew",
                    "nodal.analog_absdelay"
                  )
                do
                  val line = success.normalizedMlir.linesIterator
                    .find(_.contains(s"\"$operation\""))
                    .get
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(s"generated_induction = \"${generated.induction}\""))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val operationOffset = document.text.indexOf("\"nodal.analog_slew\"")
            val ownerOffset = document.text.indexOf(marker, operationOffset)
            assert(operationOffset >= 0, ownerOffset >= operationOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated waveform accepted: $success")
          finally delete(directory)

    test("generated transfer operators retain exact region and state ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedTransferOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedTransferOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.transferOperators.map(_.kind) == Vector("laplace_nd", "zi_nd"))
      assert(first.transferOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.transferOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val transferLines = document.text.linesIterator
        .filter(_.contains("\"nodal.analog_transfer\""))
        .toVector
      assert(transferLines.size == 2)
      transferLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.transferOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        transferOperators = forgedOperator +: first.transferOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalizedTransfers = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.analog_transfer\""))
                  .toVector
                assert(normalizedTransfers.size == 2)
                normalizedTransfers.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val transferOffset = document.text.indexOf("\"nodal.analog_transfer\"")
            val ownerOffset = document.text.indexOf(marker, transferOffset)
            assert(transferOffset >= 0, ownerOffset >= transferOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated transfer accepted: $success")

            val firstPath = first.transferOperators.head.path
            val secondPath = first.transferOperators(1).path
            val duplicateIdentityText = document.text.replace(
              transferLines(1),
              transferLines(1).replace(secondPath, firstPath)
            )
            NativeCompilerClient.run(
              document.copy(text = duplicateIdentityText),
              request
            ) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ANALOG-040-002")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"duplicate generated transfer accepted: $success")
          finally delete(directory)

    test("generated noise operators retain exact region and source ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedNoiseOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedNoiseOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.noiseOperators.map(_.kind) == Vector("white", "flicker", "table"))
      assert(first.noiseOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.noiseOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val noiseLines = document.text.linesIterator
        .filter(_.contains("\"nodal.analog_noise\""))
        .toVector
      assert(noiseLines.size == 3)
      noiseLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.noiseOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        noiseOperators = forgedOperator +: first.noiseOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalizedNoise = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.analog_noise\""))
                  .toVector
                assert(normalizedNoise.size == 3)
                normalizedNoise.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val noiseOffset = document.text.indexOf("\"nodal.analog_noise\"")
            val ownerOffset = document.text.indexOf(marker, noiseOffset)
            assert(noiseOffset >= 0, ownerOffset >= noiseOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated noise accepted: $success")

            val firstPath = first.noiseOperators.head.path
            val secondPath = first.noiseOperators(1).path
            val duplicateIdentityText = document.text.replace(
              noiseLines(1),
              noiseLines(1).replace(secondPath, firstPath)
            )
            NativeCompilerClient.run(
              document.copy(text = duplicateIdentityText),
              request
            ) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ANALOG-039-002")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"duplicate generated noise accepted: $success")
          finally delete(directory)

    test("generated additive contributions retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedContribution)
      val second = ConstructionKernel.inspect(new Increment43GeneratedContribution)
      assert(first == second)
      val generated = first.generatedRegions.head
      val analog = first.analogRegions.head
      assert(analog.generatedOwner.contains(generated.path))
      assert(analog.generatedInduction.contains(generated.induction))
      assert(analog.contributions.map(_.kind) == Vector("potential", "flow"))
      assert(analog.contributions.forall(_.generatedOwner.contains(generated.path)))
      assert(analog.contributions.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val contributionLines = document.text.linesIterator
        .filter(_.contains("\"nodal.contribute\""))
        .toVector
      assert(contributionLines.size == 2)
      contributionLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedContribution = analog.contributions.head.copy(generatedOwner = None)
      val forged = first.copy(
        analogRegions = Vector(
          analog.copy(contributions = forgedContribution +: analog.contributions.tail)
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(failure.diagnostic.message.contains("contribution ownership"))

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalized = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.contribute\""))
                  .toVector
                assert(normalized.size == 2)
                normalized.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val contributionOffset = document.text.indexOf("\"nodal.contribute\"")
            val ownerOffset = document.text.indexOf(marker, contributionOffset)
            assert(contributionOffset >= 0, ownerOffset >= contributionOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated contribution accepted: $success")
          finally delete(directory)

    test("unsupported generated effects still fail at their capture boundary"):
      val cases = Vector[(String, String, () => Module)](
        ("variable", "generate_0", () => new Increment43UnsupportedGeneratedVariable),
        ("variable", "variable_1", () => new Increment43ForgedGeneratedVariableCarrier),
        ("assignment", "generate_0", () => new Increment43ForgedGeneratedAssignmentCarrier),
        (
          "continuous-time operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedContinuousOperator
        ),
        (
          "analog waveform operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedAbstime
        ),
        (
          "analog waveform operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedBoundStep
        ),
        (
          "analog transfer operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedTransferOperator
        ),
        (
          "analog noise operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedNoiseOperator
        ),
        ("analog event", "generate_0", () => new Increment43UnsupportedGeneratedEvent),
        (
          "continuous region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedAnalogRegion
        ),
        (
          "equation region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedEquationRegion
        ),
        (
          "initialequation region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedInitialEquationRegion
        ),
        (
          "contribution region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedContributionRegion
        ),
        (
          "procedural region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedBareProceduralRegion
        ),
        (
          "procedural region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedProcedure
        )
      )
      cases.foreach: (label, path, construct) =>
        val failure = constructionFailure(ConstructionKernel.inspect(construct()))
        assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
        assert(failure.diagnostic.message.contains(label))
        assert(failure.diagnostic.semanticPath.exists(_.contains(path)))

    test("locked nodalc verifies public symbolic hdlRange when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
            NativeCompilerClient.run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.generate\""))
                assert(success.normalizedMlir.contains("\"nodal.parameter_envelope\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
          finally delete(directory)

    test("public fixed generated contributions compile to one Verilog-A loop when configured"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.generate\""))
            assert(result.mlir.contains("\"nodal.contribute\""))
            assert(
              result.mlir.contains(
                "generated_owner = \"Increment43GeneratedContribution.generate_0\""
              )
            )
            assert(result.verilogA.contains("  genvar generate_0_index;"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < 2; " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
            assert(result.verilogA.linesIterator.count(_.trim == "I(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("public generated ddt contribution retains one owned target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedDdtContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_ddt\""))
            val ddtLine = result.mlir.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
            assert(ddtLine.contains("generated_owner"))
            assert(ddtLine.contains("generated_induction"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") && line.contains("ddt(V(p, n))")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("public generated idt contribution retains one owned stateful target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedIdtContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_idt\""))
            val idtLine = result.mlir.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
            assert(idtLine.contains("generated_owner"))
            assert(idtLine.contains("generated_induction"))
            assert(idtLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("idt(") && line.contains(", 0)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("public generated transition retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedTransitionContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transition\""))
            val transitionLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transition\"")).get
            assert(transitionLine.contains("generated_owner"))
            assert(transitionLine.contains("generated_induction"))
            assert(transitionLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("transition(level)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated slew retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedSlewContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_slew\""))
            val slewLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_slew\"")).get
            assert(slewLine.contains("generated_owner"))
            assert(slewLine.contains("generated_induction"))
            assert(slewLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("slew(level)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated absdelay retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedAbsdelayContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_absdelay\""))
            val absdelayLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_absdelay\"")).get
            assert(absdelayLine.contains("generated_owner"))
            assert(absdelayLine.contains("generated_induction"))
            assert(absdelayLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("absdelay(level, 1e-09)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated laplaceNd retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLaplaceContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transfer\""))
            val transferLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transfer\"")).get
            assert(transferLine.contains("transfer_kind = \"laplace_nd\""))
            assert(transferLine.contains("generated_owner"))
            assert(transferLine.contains("generated_induction"))
            assert(transferLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") &&
                  line.contains("laplace_nd(V(p, n), '{1}, '{1})")
              ) == 1
            )
            assert(!result.verilogA.contains("real transfer_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated ziNd retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedZiContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transfer\""))
            val transferLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transfer\"")).get
            assert(transferLine.contains("transfer_kind = \"zi_nd\""))
            assert(transferLine.contains("generated_owner"))
            assert(transferLine.contains("generated_induction"))
            assert(transferLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") &&
                  line.contains("zi_nd(V(p, n), '{1}, '{1}, 1e-09)")
              ) == 1
            )
            assert(!result.verilogA.contains("real transfer_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated whiteNoise retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedWhiteNoiseContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_noise\""))
            val noiseLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_noise\"")).get
            assert(noiseLine.contains("noise_kind = \"white\""))
            assert(noiseLine.contains("generated_owner"))
            assert(noiseLine.contains("generated_induction"))
            assert(noiseLine.contains("source_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") &&
                  line.contains("white_noise(1, \"generated white\")")
              ) == 1
            )
            assert(!result.verilogA.contains("real noise_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated flickerNoise retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedFlickerNoiseContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_noise\""))
            val noiseLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_noise\"")).get
            assert(noiseLine.contains("noise_kind = \"flicker\""))
            assert(noiseLine.contains("generated_owner"))
            assert(noiseLine.contains("generated_induction"))
            assert(noiseLine.contains("source_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") &&
                  line.contains("flicker_noise(1, 1, \"generated flicker\")")
              ) == 1
            )
            assert(!result.verilogA.contains("real noise_"))
          finally delete(directory)
        case _ => assert(true)

    test("public symbolic generated contributions retain one parameterized Verilog-A loop"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43SymbolicGeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("upper = @lanes"))
            assert(result.verilogA.contains("parameter integer lanes = 4 from [1:8];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("public compound generated contributions retain one structural Verilog-A loop"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43CompoundGeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("operator_name = \"add\""))
            assert(result.mlir.contains("\"nodal.generate\"(%generated_bound_value_"))
            assert(result.verilogA.contains("parameter integer lanes = 3 from [1:4];"))
            assert(result.verilogA.contains("parameter integer extra = 1 from [1:2];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < (lanes + extra); " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("native verifies generated lexical storage ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_variable\""))
                assert(success.normalizedMlir.contains(region.path))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            val variableOffset = document.text.indexOf("\"nodal.analog_variable\"")
            val ownerOffset = document.text.indexOf(marker, variableOffset)
            assert(variableOffset >= 0)
            assert(ownerOffset >= variableOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${region.path}.forged\"",
              marker.length
            )
            val forged = document.copy(text = forgedText)
            NativeCompilerClient.run(forged, request) match
              case _: NativeCompilerSuccess =>
                scala.Predef.assert(false, "forged generated storage ownership was accepted")
              case failure: NativeCompilerFailure =>
                assert(failure.standardError.contains("NODAL-ITERATION-043-004"))
          finally delete(directory)

    test("native verifies generated structured lexical storage when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(
              new Increment43GeneratedStructuredLexicalStorage
            )
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_if\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_case\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_loop\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            val assignmentOffset = document.text.indexOf("\"nodal.analog_assign\"")
            val ownerOffset = document.text.indexOf(marker, assignmentOffset)
            assert(assignmentOffset >= 0)
            assert(ownerOffset >= assignmentOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${region.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged structured ownership accepted: $success")
          finally delete(directory)

    test("native verifies generated event occurrence ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_cross\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_on\""))
                for operation <- Vector("nodal.analog_cross", "nodal.analog_on") do
                  val line = success.normalizedMlir.linesIterator
                    .find(_.contains(s"\"$operation\""))
                    .get
                  assert(line.contains(s"generated_owner = \"${region.path}\""))
                  assert(line.contains(s"generated_induction = \"${region.induction}\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            for operation <- Vector("nodal.analog_cross", "nodal.analog_on") do
              val operationOffset = document.text.indexOf(s"\"$operation\"")
              val ownerOffset = document.text.indexOf(marker, operationOffset)
              assert(operationOffset >= 0)
              assert(ownerOffset >= operationOffset)
              val forgedText = document.text.patch(
                ownerOffset,
                s"generated_owner = \"${region.path}.forged\"",
                marker.length
              )
              NativeCompilerClient.run(document.copy(text = forgedText), request) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged generated event ownership accepted: $success"
                  )
          finally delete(directory)

    test(
      "native preserves public nested generation and rejects aliased identities when configured"
    ):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val roots = snapshot.generatedRegions.filter(_.parent.isEmpty).sortBy(_.path)
            val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
            assert(roots.size == 2)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                snapshot.generatedRegions.foreach(region =>
                  assert(success.normalizedMlir.contains(region.path))
                )
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val forgeries = Vector(
              roots(1).path -> roots.head.path,
              roots(1).induction -> roots.head.induction,
              child.path -> s"${snapshot.root}.orphan"
            )
            for (original, replacement) <- forgeries do
              val text = document.text.replace(original, replacement)
              assert(text != document.text)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(document.copy(text = text, sha256 = hash), request) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"aliased ownership accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)

    test("native independently rejects forged envelope effects when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
            assert(document.text.contains("effects = [\"topology\"]"))
            for replacement <- Vector("effects = [\"generate\"]", "effects = []") do
              val text = document.text.replace("effects = [\"topology\"]", replacement)
              assert(text != document.text)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              val forged = document.copy(text = text, sha256 = hash)
              NativeCompilerClient.run(
                forged,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-PARAMETER-ENVELOPE-001")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"invalid envelope accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)

    test("native independently rejects forged generated ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val forgeries = Vector(
              s"""generated_owner = "${region.path}"""" ->
                s"""generated_owner = "${region.path}.forged"""",
              s"""generated_induction = "${region.induction}"""" ->
                s"""generated_induction = "${region.induction}.forged""""
            )
            for (needle, replacement) <- forgeries do
              assert(document.text.contains(needle))
              val text = document.text.replace(needle, replacement)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              val forged = document.copy(text = text, sha256 = hash)
              NativeCompilerClient.run(
                forged,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged generated ownership accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)
