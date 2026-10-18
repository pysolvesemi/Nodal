package nodal.internal.testkit

import nodal.*
import nodal.increment44fixture.*
import nodal.internal.bridge.{BridgeException, ScalaToMlirBridge}
import utest.*

final class ContextContinuousBody(body: () => Unit) extends Module:
  analog:
    body()

final class ContextModuleBody(body: () => Unit) extends Module:
  body()

final class ContextPersistentInitializer(shaped: Boolean) extends Module:
  val initializer = EnvironmentContext.temperature / EnvironmentContext.nominalTemperature
  val scalar = variable(Real, if shaped then 0.0.real else initializer)
  val cells =
    variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(if shaped then initializer else 0.0.real))
  analogProcedure:
    on(initialStep):
      scalar := cells.at(0)

final class ContextParameterInitializer extends Module:
  val value = param(EnvironmentContext.temperature)

final class ContextWaveformEvent extends Module:
  val value = variable(Real, 0.0.s)
  analogProcedure:
    on(initialStep):
      value := absdelay(AnalysisContext.time, 1.0.s)

final class ContextAliasSource(useAlias: Boolean) extends Module:
  analog:
    val _ = if useAlias then AnalysisContext.time else abstime

final class ContextInvalidFilter(filter: String, duplicate: Boolean) extends Module:
  analogProcedure:
    on(if duplicate then initialStep(filter, filter) else finalStep(filter)):
      ()

object Increment44ContextTests extends TestSuite:
  private def code(body: => Any): String =
    scala.util.Try(body).failed.get match
      case error: ConstructionException => error.diagnostic.code
      case error: BridgeException => error.diagnostic.code
      case error => scala.util.Failure[String](error).get

  val tests: Tests = Tests:
    test("shared registry closes query kinds and preserves physical results"):
      val entries = AnalogFunctionRegistry.contextEntries
      assert(entries.map(_.id).distinct.size == entries.size)
      assert(AnalogContextContract.entry("temperature").dimension == "temperature")
      assert(AnalogContextContract.entry("nominal_temperature").resultKind == "real")
      assert(AnalogContextContract.Version == "1")
      assert(code(AnalogContextContract.entry("time")) == "NODAL-ANALOG-044-001")
      assert(code(AnalogContextContract.entry("$temperature")) == "NODAL-ANALOG-044-001")

    test("public continuous query IR is typed and deterministic"):
      val first = ScalaToMlirBridge.lower(new AnalogContextSource)
      assert(first == ScalaToMlirBridge.lower(new AnalogContextSource))
      assert(first.text.contains("\"nodal.analog_context\""))
      assert(first.text.contains("query_kind = \"temperature\""))
      assert(first.text.contains("query_kind = \"nominal_temperature\""))
      assert(first.text.contains("!nodal.quantity<\"real\", \"temperature\">"))
      assert(first.text.contains("\"nodal.analog_abstime\""))
      assert(first.text.contains("\"nodal.analog_analysis\""))
      assert(!first.text.contains("candidate-environment"))

    test("time alias reuses the exact abstime operation contract"):
      val direct = ConstructionKernel.inspect(new ContextAliasSource(false))
      val alias = ConstructionKernel.inspect(new ContextAliasSource(true))
      val left = direct.waveformOperators.head
      val right = alias.waveformOperators.head
      assert(left.copy(path = right.path, source = None) == right.copy(source = None))
      assert(right.operation == "analog_abstime", right.stateId.isEmpty)
      assert(right.resultDimension == "time")
      assert(alias.analogRegions.head.expressions.map(_.operation).contains("analog_abstime"))
      assert(
        !alias.analogRegions.head.expressions.map(_.operation).contains("candidate-analysis-time")
      )

    test("event control local initialization and live fill retain runtime queries"):
      val first = ScalaToMlirBridge.lower(new AnalogContextEventSource)
      assert(first == ScalaToMlirBridge.lower(new AnalogContextEventSource))
      assert(first.text.contains("analog_context_v1_temperature()"))
      assert(first.text.contains("analog_context_v1_nominal_temperature()"))
      assert(first.text.contains("analog_abstime()"))
      assert(first.text.contains("\"nodal.analog_initial_step\""))
      assert(first.text.contains("\"nodal.analog_final_step\""))
      assert(first.text.contains("vendorPhase"))
      assert(first.text.indexOf("\"nodal.analog_initial_step\"") <
        first.text.indexOf("\"nodal.analog_final_step\""))
      assert(!first.text.contains("@event_reference_"))
      val time = ConstructionKernel.inspect(new AnalogContextEventSource).waveformOperators
      assert(time.forall(_.context == "procedure"), time.forall(_.stateId.isEmpty))

    test("generated continuous and event scopes retain exact owners"):
      val snapshot = ConstructionKernel.inspect(new AnalogContextGeneratedSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document == ScalaToMlirBridge.lower(new AnalogContextGeneratedSource))
      val queries =
        document.text.linesIterator.filter(_.contains("\"nodal.analog_context\"")).toVector
      assert(queries.size == 2)
      assert(queries.forall(line =>
        line.contains("generated_owner") && line.contains("generated_induction")
      ))
      assert(snapshot.analogProcedural.forall(_.generatedOwner.nonEmpty))
      assert(document.text.contains("analog_abstime()"))
      val onlyQueries = ScalaToMlirBridge.lower(new AnalogContextQueryOnlyGeneratedSource)
      assert(onlyQueries.text.contains("\"nodal.analog_context\""))

    test("context facts never become waveform constants or maximum-delay defaults"):
      assert(code(ConstructionKernel.inspect(new ContextContinuousBody(() =>
        val value = EnvironmentContext.temperature / EnvironmentContext.nominalTemperature * 1.0.s
        val _ = absdelay(1.0.V, 1.0.s, value)
      ))) == "NODAL-ANALOG-036-007")
      assert(code(ConstructionKernel.inspect(new ContextContinuousBody(() =>
        val _ = transition(EnvironmentContext.temperature)
      ))) == "NODAL-ANALOG-036-005")

    test("temperature dimension cannot be erased or compared with Celsius literals"):
      assert(code(ConstructionKernel.inspect(new ContextContinuousBody(() =>
        val _ = AnalogMath.max(EnvironmentContext.nominalTemperature, 273.15.real)
      ))) == "NODAL-ANALOG-038-003")
      assert(code(ConstructionKernel.inspect(new ContextContinuousBody(() =>
        val _ = AnalogMath.sin(EnvironmentContext.temperature)
      ))) == "NODAL-ANALOG-038-003")

    test("persistent scalar fill and parameter initializers cannot read simulator context"):
      assert(code(ScalaToMlirBridge.lower(new ContextPersistentInitializer(false))) ==
        "NODAL-ANALOG-044-004")
      assert(code(ScalaToMlirBridge.lower(new ContextPersistentInitializer(true))) ==
        "NODAL-ANALOG-044-004")
      assert(code(ScalaToMlirBridge.lower(new ContextParameterInitializer)) ==
        "NODAL-ANALOG-044-004")

    test("pure functions cannot capture temperature nominal temperature or time"):
      Vector[() => Expr[Real]](
        () => EnvironmentContext.temperature,
        () => EnvironmentContext.nominalTemperature,
        () => AnalysisContext.time
      ).foreach: query =>
        assert(code(ConstructionKernel.inspect(new ContextModuleBody(() =>
          val _ = AnalogFunction("invalid", Real): f =>
            val input = f.input("x", Real)
            val _ = query()
            input
        ))) == "NODAL-ANALOG-041-001")

    test("allowing time in events does not admit stateful waveform operators"):
      assert(code(ConstructionKernel.inspect(new ContextWaveformEvent)) == "NODAL-ANALOG-037-007")

    test("unavailable provider queries reject explicitly and validate selectors"):
      Vector[() => Any](
        () => AnalysisContext.frequency,
        () => EnvironmentContext.operatingCondition("supply", PhysicalDimension.Voltage),
        () => EnvironmentContext.corner("slow"),
        () => EnvironmentContext.sweepCoordinate("bias", PhysicalDimension.Voltage),
        () => EnvironmentContext.randomSeed("local-mismatch")
      ).foreach(query => assert(code(query()) == "NODAL-ANALOG-044-005"))
      assert(code(EnvironmentContext.corner("$simparam(\"x\")")) == "NODAL-ANALOG-044-001")
      assert(code(EnvironmentContext.randomSeed("")) == "NODAL-ANALOG-044-001")
      assert(code(EnvironmentContext.operatingCondition("bias", PhysicalDimension("invalid"))) ==
        "NODAL-ANALOG-044-001")

    test("forged source query arity type and constant annotations fail closed"):
      def forged(
          operands: Vector[Any] = Vector.empty,
          kind: String = "Real",
          literal: Option[KernelLiteral] = None
      ): String =
        code(ConstructionKernel.inspect(new ContextContinuousBody(() =>
          ConstructionKernel.expression(new KernelExpr[Real](
            operands,
            Some(KernelTypeDescriptor(kind)),
            literal,
            Some(AnalogContextContract.Prefix + "temperature")
          ))
        )))
      assert(forged(Vector(1)) == "NODAL-ANALOG-044-002")
      assert(forged(kind = "Bool") == "NODAL-ANALOG-044-003")
      assert(forged(literal = Some(KernelLiteral("real", "300.0", KernelTypeDescriptor("Real")))) ==
        "NODAL-ANALOG-044-004")

    test("independent bridge rejects forged query kind arity constant and units"):
      val snapshot = ConstructionKernel.inspect(new AnalogContextSource)
      def altered(change: KernelAnalogExpressionSnapshot => KernelAnalogExpressionSnapshot)
          : String =
        val region = snapshot.analogRegions.head
        val index =
          region.expressions.indexWhere(_.operation.startsWith(AnalogContextContract.Prefix))
        val updated = region.copy(expressions =
          region.expressions.updated(index, change(region.expressions(index)))
        )
        code(ScalaToMlirBridge.fromSnapshot(snapshot.copy(analogRegions = Vector(updated))))
      assert(altered(_.copy(operation = "analog_context_v1_frequency")) == "NODAL-ANALOG-044-001")
      assert(altered(value => value.copy(operands = Vector(value.path))) == "NODAL-ANALOG-044-002")
      assert(altered(_.copy(literal = Some("300.0"))) == "NODAL-ANALOG-044-004")
      assert(altered(_.copy(unit = Some("V"))) == "NODAL-ANALOG-044-004")

    test("lifecycle filter malformed and duplicate names remain rejected"):
      assert(code(ConstructionKernel.inspect(new ContextInvalidFilter("tran", true))) ==
        "NODAL-ANALOG-037-008")
      assert(code(ConstructionKernel.inspect(new ContextInvalidFilter("tran\")", false))) ==
        "NODAL-ANALOG-037-008")

    test("repeated query fills with no storage reads retain live per-consumer sharing"):
      val snapshot = ConstructionKernel.inspect(new AnalogContextSharedSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val live =
        document.text.linesIterator.filter(_.contains("\"nodal.analog_live_fill\"")).toVector
      assert(live.size == 2)
      assert(live.forall(!_.contains("reads =")))
      assert(live.exists(_.contains("analog_context_v1_temperature()")))
      assert(live.exists(_.contains("analog_abstime()")))
      assert(document.text.contains("reference_form = \"live\""))
      assert(!document.text.contains("\"nodal.analog_array_fill\""))
      assert(document == ScalaToMlirBridge.lower(new AnalogContextSharedSource))
