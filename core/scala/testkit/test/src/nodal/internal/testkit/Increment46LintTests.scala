package nodal.internal.testkit

import nodal.*
import nodal.increment46fixture.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class AnalogLintInvalidFunctionSource extends Module:
  val invalid = AnalogFunction("invalidDivide", Real): f =>
    val input = f.input("signal", Real)
    val zero = f.local("zero", 2.0.real - 2.0.real)
    input / zero

/** These checks preserve source semantics; native warning assertions belong to native tests. */
object Increment46LintTests extends TestSuite:
  private def operations(text: String, name: String): Vector[String] =
    text.linesIterator.filter(_.contains(s"\"nodal.$name\"(")).toVector

  private def events(snapshot: ConstructionSnapshot)
      : Vector[AnalogControlFlowRuntime.Statement.EventControl] =
    snapshot.analogProcedural.head.controlFlow.get.root.statements.collect:
      case event: AnalogControlFlowRuntime.Statement.EventControl => event

  val tests: Tests = Tests:
    test("symbolic denominator and runtime rounding reach native lint without rewrites"):
      val snapshot = ConstructionKernel.inspect(new AnalogLintSource)
      val region = snapshot.analogRegions.head
      val scaled = region.expressions.find(value =>
        value.operation == "analog_div" && value.operands.last == "AnalogLintSource.divisor"
      ).get
      assert(scaled.operation == "analog_div")
      assert(scaled.operands.last == "AnalogLintSource.divisor", scaled.literal.isEmpty)
      val elapsed = region.expressions.find(value =>
        value.operation == "analog_div" && value.path != scaled.path
      ).get
      assert(elapsed.operation == "analog_div", elapsed.literal.isEmpty)
      Vector("stepped" -> "floor", "ceiling" -> "ceil").foreach: (name, function) =>
        val rounding = region.expressions.find(value =>
          value.operation == AnalogFunctionRegistry.FunctionPrefix + function
        ).get
        assert(rounding.path.startsWith(s"AnalogLintSource.$name"))
        assert(rounding.operation == AnalogFunctionRegistry.FunctionPrefix + function)
        assert(rounding.operands == Vector(elapsed.path), rounding.literal.isEmpty)
      assert(region.contributions.size == 1)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(document.text, "parameter_ref").size == 1)
      assert(operations(document.text, "analog_div").size == 2)
      assert(operations(document.text, "analog_abstime").size == 1)
      assert(operations(document.text, "analog_function").size == 2)
      assert(operations(document.text, "contribute").size == 1)
      assert(!document.text.contains("nodal.lint"))
      assert(document.text.contains("Increment46ConstructionCheck.scala"))

    test("safe neighboring arithmetic preserves constant rounding and smooth runtime function"):
      val snapshot = ConstructionKernel.inspect(new AnalogLintSafeSource)
      val expressions = snapshot.analogRegions.head.expressions
      val functions =
        expressions.filter(_.operation.startsWith(AnalogFunctionRegistry.FunctionPrefix))
      assert(functions.map(_.operation) == Vector("sin", "floor", "ceil").map(
        AnalogFunctionRegistry.FunctionPrefix + _
      ))
      val literals = expressions.filter(_.literal.nonEmpty).map(value => value.path -> value).toMap
      val rounded = functions.tail
      assert(rounded.forall(value => literals(value.operands.head).literal.contains("1.25")))
      val scaled = expressions.find(value =>
        value.operation == "analog_div" && literals.get(value.operands.last)
          .exists(_.literal.contains("2.0"))
      ).get
      assert(scaled.operation == "analog_div")
      assert(literals(scaled.operands.last).literal.contains("2.0"))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(document.text, "parameter_ref").isEmpty)
      assert(operations(document.text, "analog_function").size == 3)
      assert(operations(document.text, "contribute").size == 1)

    test("inactive monitors and vendor lifecycle filters retain controlled writes and state"):
      val snapshot = ConstructionKernel.inspect(new AnalogLintEventSource)
      val controls = events(snapshot)
      assert(controls.map(_.event.operation) == Vector(
        "analog_initial_step",
        "analog_cross",
        "analog_above",
        "analog_timer",
        "analog_final_step"
      ))
      assert(controls.head.event.analyses == Vector("tran", "vendorPhase"))
      assert(controls.last.event.analyses == Vector("tran", "vendorPhase"))
      assert(controls(1).event.arguments.head.constant.contains(1.0))
      assert(controls(2).event.arguments.head.constant.contains(-1.0))
      assert(controls(3).event.arguments.last.constant.contains(0.0))
      assert(controls.dropRight(1).forall(_.body.statements.nonEmpty))
      assert(controls.last.body.statements.isEmpty)
      assert(snapshot.analogProcedural.head.assignments.isEmpty)
      val transition = snapshot.waveformOperators.find(_.operation == "analog_transition").get
      assert(transition.operands.head == "AnalogLintEventSource.held")
      assert(transition.stateId.nonEmpty)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(document.text, "analog_on").size == 5)
      assert(operations(document.text, "analog_transition").size == 1)
      assert(operations(document.text, "contribute").size == 1)
      assert(document.text.contains("vendorPhase"))

    test("positive above one-shot timers and unknown enables remain distinct from inactivity"):
      val snapshot = ConstructionKernel.inspect(new AnalogLintSafeEventSource)
      val controls = events(snapshot)
      assert(controls.size == 8)
      assert(controls(1).event.arguments.head.constant.isEmpty)
      assert(controls(2).event.arguments.head.constant.contains(1.0))
      assert(controls(3).event.arguments(1).constant.contains(0.0))
      assert(controls(3).event.arguments.last.constant.contains(1.0))
      assert(controls(4).event.arguments(1).constant.contains(-1.0))
      assert(controls(5).event.arguments.last.constant.isEmpty)
      assert(controls(6).event.arguments.last.constant.isEmpty)
      assert(controls(5).event.arguments.last.value.rendered.contains("parameterEnable"))
      val runtimeEnable = snapshot.analogProcedural.head.variables.find(
        _.authoredPath.contains("AnalogLintSafeEventSource.runtimeEnable")
      ).get
      assert(controls(6).event.arguments.last.value.allReads == Vector(runtimeEnable.variable))
      assert(controls.head.event.analyses == Vector("dc", "tran"))
      assert(controls.last.event.analyses == Vector("tran"))
      assert(controls.last.body.statements.isEmpty)
      assert(snapshot.waveformOperators.count(_.operation == "analog_transition") == 1)

    test("known-zero native witness remains a division and existing construction error survives"):
      val snapshot = ConstructionKernel.inspect(new AnalogLintZeroDivisorSource)
      val expressions = snapshot.analogRegions.head.expressions
      val invalid = expressions.find(_.operation == "analog_div").get
      assert(invalid.operation == "analog_div")
      val denominator = expressions.find(_.path == invalid.operands.last).get
      assert(denominator.literal.contains("0.0"))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(document.text, "analog_div").size == 1)
      val failure = scala.util.Try(ConstructionKernel.inspect(new AnalogLintInvalidFunctionSource))
        .failed.get.asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-041-008")

    test("fresh public witnesses export identical VA and AMS profile documents"):
      val sources = Vector[() => Module](
        () => new AnalogLintSource,
        () => new AnalogLintSafeSource,
        () => new AnalogLintEventSource,
        () => new AnalogLintSafeEventSource,
        () => new AnalogLintZeroDivisorSource
      )
      sources.foreach: source =>
        val firstSnapshot = ConstructionKernel.inspect(source())
        val secondSnapshot = ConstructionKernel.inspect(source())
        assert(firstSnapshot == secondSnapshot)
        Vector(Backend.VerilogA, Backend.VerilogAMS).foreach: backend =>
          val first = ScalaToMlirBridge.fromSnapshot(firstSnapshot, backend)
          val second = ScalaToMlirBridge.fromSnapshot(secondSnapshot, backend)
          assert(first == second)
