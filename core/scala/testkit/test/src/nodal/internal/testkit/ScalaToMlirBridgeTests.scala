package nodal.internal.testkit

import nodal.*
import nodal.increment42fixture.*
import nodal.internal.bridge.*

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import utest.*

final class BridgeLeaf extends Module:
  val core: ClockDomain = ClockDomain.required("core")
  val width: Param[UInt] = param(8.U(8))
  val input: Signal[UInt] = in(UInt(8))
  val output: Signal[UInt] = out(UInt(8))

  core:
    val state = Reg(0.U(8))
    state := input
    output := state

final class BridgeTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 250.MHz
  )
  val input: Signal[UInt] = in(UInt(8))
  val output: Signal[UInt] = out(UInt(8))
  val padOuter: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.FullResolvedSimulation,
    "padOuter"
  )
  val padInner: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.pushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.FullResolvedSimulation,
    "padInner"
  )
  val terminalA: TerminalView[Electrical.type, ConservativeAccess.Connect] =
    terminal(Electrical, "a").connectView
  val terminalB: TerminalView[Electrical.type, ConservativeAccess.Connect] =
    terminal(Electrical, "b").connectView

  passThrough(padOuter, padInner)
  terminalA.connectTo(terminalB)

  root:
    val child = instance(new BridgeLeaf)
    child.domain(root)
    child.param(_.width, 12.U(8))
    output := input

final class BridgeHierarchyLeaf(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)

final class BridgeHierarchyTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: BridgeHierarchyLeaf = new BridgeHierarchyLeaf(gain = rootGain)
  vin <> child.vin
  child.vout <> vout

final class BridgeHierarchyExpressionLeaf extends Module:
  val gain: Param[Real] = param(2.0.real)

final class BridgeHierarchyExpressionTop extends Module:
  val rootGain: Param[Real] = param(4.0.real)
  val child: Instance[BridgeHierarchyExpressionLeaf] = instance(new BridgeHierarchyExpressionLeaf)
  child.param(_.gain, (rootGain + 1.0.real) * 2.0.real)

final class BridgeBooleanExpressionLeaf extends Module:
  val enabled: Param[Bool] = param(false.B)

final class BridgeBooleanExpressionTop extends Module:
  val threshold: Param[Real] = param(4.0.real)
  val child: Instance[BridgeBooleanExpressionLeaf] = instance(new BridgeBooleanExpressionLeaf)
  child.param(_.enabled, (threshold > 1.0.real) && true.B)

final class BridgeNamedDisciplineLeaf extends Module:
  val declared: NamedDiscipline = discipline("leaf_electrical", Voltage, Current)
  val port: Node[NamedDiscipline] = in(declared)

final class BridgeNamedDisciplineTop extends Module:
  val declared: NamedDiscipline = discipline("top_electrical", Voltage, Current)
  val port: Node[NamedDiscipline] = in(declared)
  val child: BridgeNamedDisciplineLeaf = new BridgeNamedDisciplineLeaf
  port <> child.port

final class BridgeProceduralTop extends Module:
  val accumulator: Variable[Real] = variable(Real, 1.0.V)
  val scratch: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    scratch := accumulator
    scratch := 2.0.V
    when(true.B):
      accumulator := scratch

final class BridgeProceduralNestedChronology extends Module:
  analogProcedure:
    when(true.B):
      val scoped: Variable[Real] = variable(Real, 0.0.V)
      scoped := 1.0.V
    val later: Variable[Real] = variable(Real, 0.0.V)
    later := 2.0.V

final class BridgeProceduralInitializerDependency extends Module:
  analogProcedure:
    val source: Variable[Real] = variable(Real)
    source := 1.0.real
    val sink: Variable[Real] = variable(Real, source)
    source := sink

final class BridgeStructuredProceduralTop extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val mode: Variable[Integer] = variable(Integer, 0.integer)
  val iterations: Variable[Integer] = variable(Integer, 1.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogCase(mode):
      analogCaseArm(0):
        value := 1.0.real
      analogCaseDefault:
        value := 2.0.real
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      analogConditional:
        analogWhen(select):
          value := 3.0.real
          analogContinue()
        analogOtherwise:
          value := 4.0.real
          analogBreak()
    sink := value

object ScalaToMlirBridgeTests extends TestSuite:
  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-bridge-test-")

  private def occurrences(text: String, token: String): Int =
    text.sliding(token.length).count(_ == token)

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  val tests: Tests = Tests:
    test("deterministic source-correlated textual MLIR"):
      val first = ScalaToMlirBridge.lower(new BridgeTop)
      val second = ScalaToMlirBridge.lower(new BridgeTop)

      assert(first == second)
      assert(first.schema == "nodal.scala-to-mlir")
      assert(first.version == 1)
      assert(first.sha256.matches("[0-9a-f]{64}"))
      assert(first.text.startsWith("module attributes"))
      assert(first.text.contains("\"nodal.module\""))
      assert(first.text.contains("\"nodal.parameter\""))
      assert(first.text.contains("parameter_bindings"))
      assert(first.text.contains("domain_bindings = {core = @root}"))
      assert(!first.text.contains("\"nodal.domain_bind\""))
      assert(first.text.contains("nodal.bridge.declarations"))
      assert(first.text.contains("nodal.bridge.origins"))
      assert(first.text.contains("loc(\""))
      assert(first.text.endsWith("\n"))
      assert(!first.text.contains("\r"))

    test("hierarchy bridge retains typed root actuals, symbolic overrides, and child ports"):
      val snapshot = ConstructionKernel.inspect(new BridgeHierarchyTop(rootGain = 6.0))
      val first = ScalaToMlirBridge.fromSnapshot(snapshot)
      val second = ScalaToMlirBridge.fromSnapshot(snapshot)

      assert(first == second)
      assert(snapshot.rootParameterBindings == Vector("rootGain" -> "6.0"))
      assert(snapshot.topology.forall(_.owner == snapshot.root))
      assert(first.text.contains("nodal.root.module = @BridgeHierarchyTop"))
      assert(first.text.contains("nodal.root.parameter_bindings = {rootGain = 6.0 : f64}"))
      assert(occurrences(first.text, "\"nodal.nature\"") == 2)
      assert(occurrences(first.text, "\"nodal.discipline\"") == 1)
      assert(first.text.contains("sym_name = \"electrical\""))
      assert(first.text.contains("potential = @Voltage"))
      assert(first.text.contains("flow = @Current"))
      assert(first.text.contains("parameter_bindings = {}"))
      assert(first.text.contains("\"nodal.const_parameter_ref\""))
      assert(first.text.contains("parameter = @rootGain"))
      assert(first.text.contains("\"nodal.parameter_override\""))
      assert(first.text.contains("parameter = @gain"))
      assert(occurrences(first.text, "\"nodal.instance_terminal\"") == 2)
      assert(occurrences(first.text, "\"nodal.connect\"") == 2)
      assert(occurrences(first.text, "allow_floating = true") == 2)
      assert(first.text.contains("module = @child"))
      assert(first.text.contains("instance = @child_instance"))
      assert(first.text.contains("port = \"vin\""))
      assert(first.text.contains("port = \"vout\""))
      assert(!first.text.contains("NODAL-BRIDGE"))

    test("hierarchy composes deterministically with equations events and local functions"):
      val equation = ScalaToMlirBridge.lower(new HierarchyEquationTop)
      val event = ScalaToMlirBridge.lower(new HierarchyEventTop)
      val function = ScalaToMlirBridge.lower(new HierarchyFunctionTop)

      Vector(
        equation -> ScalaToMlirBridge.lower(new HierarchyEquationTop),
        event -> ScalaToMlirBridge.lower(new HierarchyEventTop),
        function -> ScalaToMlirBridge.lower(new HierarchyFunctionTop)
      ).foreach: (first, second) =>
        assert(first == second)
        assert(occurrences(first.text, "\"nodal.instance_terminal\"") == 2)
        assert(occurrences(first.text, "\"nodal.connect\"") == 2)
        assert(first.text.contains("\"nodal.parameter_override\""))
        assert(first.text.contains("parameter = @gain"))
        assert(first.text.contains("module = @child"))
        assert(!first.text.contains("NODAL-BRIDGE"))

      assert(equation.text.contains("nodal.bridge.analog_semantics"))
      assert(equation.text.contains("lhs-minus-rhs-equals-zero"))
      assert(event.text.contains("\"nodal.analog_initial_step\""))
      assert(event.text.contains("\"nodal.analog_on\""))
      assert(function.text.contains("nodal.bridge.analog_functions"))
      assert(function.text.contains("\"nodal.analog_user_call\""))
      assert(function.text.contains("callee = @scaleSignal"))

    test("hierarchy scale witnesses retain repeated identities and bounded depth"):
      val repeated = ScalaToMlirBridge.lower(new HierarchyRepeatedTop)
      val nested = ScalaToMlirBridge.lower(new HierarchyNestedTop)

      assert(repeated == ScalaToMlirBridge.lower(new HierarchyRepeatedTop))
      assert(nested == ScalaToMlirBridge.lower(new HierarchyNestedTop))
      assert(occurrences(repeated.text, "\"nodal.module\"") == 2)
      assert(occurrences(repeated.text, "\"nodal.instance\"") == 4)
      assert(occurrences(repeated.text, "\"nodal.instance_terminal\"") == 8)
      assert(occurrences(repeated.text, "\"nodal.connect\"") == 8)
      assert(occurrences(repeated.text, "\"nodal.parameter_override\"") == 2)
      Vector("first", "second", "third", "fourth").foreach: name =>
        assert(repeated.text.contains(s"child_path = \"HierarchyRepeatedTop.$name\""))
      assert(occurrences(repeated.text, "module = @first") == 4)
      assert(!repeated.text.contains("sym_name = \"second\""))
      assert(!repeated.text.contains("sym_name = \"third\""))
      assert(!repeated.text.contains("sym_name = \"fourth\""))
      assert(repeated.text.contains("3.0 : f64"))
      assert(repeated.text.contains("5.0 : f64"))

      assert(occurrences(nested.text, "\"nodal.module\"") == 3)
      assert(occurrences(nested.text, "\"nodal.instance\"") == 3)
      assert(occurrences(nested.text, "\"nodal.instance_terminal\"") == 6)
      assert(occurrences(nested.text, "\"nodal.connect\"") == 6)
      assert(occurrences(nested.text, "module = @firstBranch") == 2)
      assert(nested.text.contains("module = @leaf"))
      assert(nested.text.contains("child_path = \"HierarchyNestedTop.firstBranch\""))
      assert(nested.text.contains("child_path = \"HierarchyNestedTop.secondBranch\""))
      assert(
        nested.text.contains("child_path = \"HierarchyNestedTop.firstBranch.leaf\"")
      )
      assert(!nested.text.contains("sym_name = \"secondBranch\""))

      val repeatedSnapshot = ConstructionKernel.inspect(new HierarchyRepeatedTop)
      val distinctDefault = repeatedSnapshot.copy(
        modules = repeatedSnapshot.modules.map: module =>
          if module.path == "HierarchyRepeatedTop.second" then
            module.copy(
              declarations = module.declarations.map: declaration =>
                if declaration.kind == "parameter" then
                  declaration.copy(
                    attributes = declaration.attributes.map:
                      case ("default", _) => "default" -> "7.0"
                      case entry => entry
                  )
                else declaration
            )
          else module
      )
      val distinct = ScalaToMlirBridge.fromSnapshot(distinctDefault)
      assert(occurrences(distinct.text, "\"nodal.module\"") == 3)
      assert(distinct.text.contains("module = @second"))
      assert(distinct.text.contains("sym_name = \"second\""))

    test("hierarchy bridge canonicalizes compatible named conservative disciplines"):
      val document = ScalaToMlirBridge.lower(new BridgeNamedDisciplineTop)

      assert(occurrences(document.text, "\"nodal.instance_terminal\"") == 1)
      assert(occurrences(document.text, "\"nodal.connect\"") == 1)
      assert(document.text.contains("!nodal.terminal<\"electrical\">"))
      assert(!document.text.contains("!nodal.terminal<\"top_electrical\">"))
      assert(!document.text.contains("!nodal.terminal<\"leaf_electrical\">"))
      assert(document.text.contains("declared_discipline = \"top_electrical\""))
      assert(document.text.contains("declared_discipline = \"leaf_electrical\""))

    test("hierarchy bridge serializes parent-owned static override expression DAGs"):
      val arithmeticSnapshot = ConstructionKernel.inspect(new BridgeHierarchyExpressionTop)
      val arithmetic = ScalaToMlirBridge.fromSnapshot(arithmeticSnapshot)
      val operations = arithmeticSnapshot.parameterExpressions.map(_.operation)

      assert(operations == Vector("real_literal", "analog_add", "real_literal", "analog_mul"))
      assert(arithmeticSnapshot.parameterExpressions.forall(
        _.owner == "BridgeHierarchyExpressionTop"
      ))
      assert(arithmetic.text.contains("\"nodal.const_parameter_ref\""))
      assert(occurrences(arithmetic.text, "\"nodal.const_literal\"") == 2)
      assert(occurrences(arithmetic.text, "\"nodal.const_expr\"") == 2)
      assert(arithmetic.text.contains("operator_name = \"add\""))
      assert(arithmetic.text.contains("operator_name = \"mul\""))
      assert(arithmetic.text.contains(
        s"source_value = \"${arithmeticSnapshot.parameterExpressions.last.path}\""
      ))

      val booleanSnapshot = ConstructionKernel.inspect(new BridgeBooleanExpressionTop)
      val boolean = ScalaToMlirBridge.fromSnapshot(booleanSnapshot)
      assert(booleanSnapshot.parameterExpressions.map(_.operation) == Vector(
        "real_literal",
        "real_gt",
        "boolean",
        "bool_and"
      ))
      assert(boolean.text.contains("operator_name = \"gt\""))
      assert(boolean.text.contains("operator_name = \"and\""))
      assert(boolean.text.contains("-> !nodal.bits<1>"))
      assert(!arithmetic.text.contains("NODAL-BRIDGE"))
      assert(!boolean.text.contains("NODAL-BRIDGE"))

    test("analog procedural IR retains order, source locations, and serialization"):
      val first = ScalaToMlirBridge.lower(new BridgeProceduralTop)
      val second = ScalaToMlirBridge.lower(new BridgeProceduralTop)

      assert(first == second)
      assert(first.text.contains("\"nodal.analog_procedure\""))
      assert(first.text.contains("\"nodal.analog_variable\""))
      assert(first.text.contains("\"nodal.analog_variable_read\""))
      assert(first.text.contains("\"nodal.analog_assign\""))
      assert(first.text.contains("\"nodal.analog_scope\""))
      assert(first.text.contains("!nodal.variable<\"real\", \"voltage\">"))
      assert(first.text.contains("nodal.bridge.analog_procedural"))
      assert(first.text.contains("authored_order = 0 : i64"))
      assert(first.text.contains("authored_order = 1 : i64"))
      assert(first.text.contains("authored_order = 2 : i64"))
      assert(first.text.contains("ScalaToMlirBridgeTests.scala"))
      assert(first.text.contains("loc(\""))
      assert(first.sha256 == second.sha256)

      val snapshot = ConstructionKernel.inspect(new BridgeProceduralTop)
      val program = snapshot.analogProcedural.head
      assert(program.variables.forall(_.source.nonEmpty))
      assert(program.assignments.forall(_.source.nonEmpty))
      val wrapperPaths = Vector(
        s"${program.owner}.analogProcedural",
        s"${program.owner}.analogProcedure"
      )
      val variablePaths = program.variables.map(_.variable.identity)
      val assignmentPaths = program.assignments.map(_.identity)
      val readPaths = program.assignments.flatMap: record =>
        record.value.reads.indices.map(index => s"${record.identity}.read_$index")
      val authoredScopes =
        program.variables.map(_.variable.declarationScope) ++ program.assignments.map(_.scope)
      val scopePaths = authoredScopes
        .flatMap: scope =>
          val canonical =
            if scope.headOption.contains("procedure") then scope
            else Vector("procedure") ++ scope
          (2 to canonical.size).map(size =>
            s"${program.owner}.${canonical.take(size).mkString(".")}"
          )
        .distinct
      val expectedSourcePaths =
        (wrapperPaths ++ scopePaths ++ variablePaths ++ assignmentPaths ++
          readPaths).distinct.sorted

      assert(readPaths.nonEmpty)
      assert(scopePaths.nonEmpty)
      assert(first.text.contains("nodal.bridge.source_map"))
      assert(
        expectedSourcePaths.forall(path =>
          occurrences(first.text, s"semantic_path = \"$path\"") >= 2
        )
      )

    test("analog procedural rendering prefers authored order to provenance"):
      val snapshot = ConstructionKernel.inspect(new BridgeProceduralTop)
      val program = snapshot.analogProcedural.head
      val invertedSources = program.assignments.zipWithIndex.map:
        case (record, 0) =>
          record.copy(
            source = Some(AnalogProceduralRuntime.Source("z-helper.scala", 200, 1))
          )
        case (record, 1) =>
          record.copy(
            source = Some(AnalogProceduralRuntime.Source("a-helper.scala", 10, 1))
          )
        case (record, _) => record
      val modified = snapshot.copy(
        analogProcedural = snapshot.analogProcedural.updated(
          0,
          program.copy(assignments = invertedSources)
        )
      )

      val document = ScalaToMlirBridge.fromSnapshot(modified)
      val first = document.text.indexOf("authored_order = 0 : i64")
      val second = document.text.indexOf("authored_order = 1 : i64")
      assert(first >= 0)
      assert(second > first)

    test("nested procedural scopes preserve declaration and assignment chronology"):
      val snapshot = ConstructionKernel.inspect(new BridgeProceduralNestedChronology)
      val program = snapshot.analogProcedural.head
      assert(program.variables.map(_.declarationOrder) == Vector(0, 1))
      assert(program.assignments.map(_.authoredOrder) == Vector(0, 1))

      val rendered = AnalogProceduralMlir.renderModule(snapshot, program.owner).head
      val declaration0 = rendered.indexOf("declaration_order = 0 : i64")
      val assignment0 = rendered.indexOf("authored_order = 0 : i64")
      val declaration1 = rendered.indexOf("declaration_order = 1 : i64")
      val assignment1 = rendered.indexOf("authored_order = 1 : i64")
      assert(declaration0 >= 0)
      assert(assignment0 > declaration0)
      assert(declaration1 > assignment0)
      assert(assignment1 > declaration1)

      val document = ScalaToMlirBridge.fromSnapshot(snapshot)

      sys.env.get("NODAL_NODALC").foreach: executable =>
        val directory = workDirectory()
        try
          val success = NativeCompilerClient
            .run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--mlir-print-op-generic"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            )
            .asInstanceOf[NativeCompilerSuccess]
          assert(success.normalizedMlir.contains("authored_order"))
          assert(success.normalizedMlir.contains("declaration_order"))
        finally delete(directory)
    test("initializing assignments precede dependent declarations independent of provenance"):
      val snapshot = ConstructionKernel.inspect(new BridgeProceduralInitializerDependency)
      val program = snapshot.analogProcedural.head
      assert(program.variables.map(_.operationOrder) == Vector(0, 2))
      assert(program.assignments.map(_.operationOrder) == Vector(1, 3))

      val invertedVariables = program.variables.map: record =>
        val source =
          if record.operationOrder == 0 then
            AnalogProceduralRuntime.Source("z-helper.scala", 400, 1)
          else AnalogProceduralRuntime.Source("a-helper.scala", 1, 1)
        record.copy(source = Some(source))
      val invertedAssignments = program.assignments.map: record =>
        val source =
          if record.operationOrder == 1 then
            AnalogProceduralRuntime.Source("z-helper.scala", 300, 1)
          else AnalogProceduralRuntime.Source("a-helper.scala", 2, 1)
        record.copy(source = Some(source))
      val inverted = program.copy(
        variables = invertedVariables,
        assignments = invertedAssignments
      )
      val modified = snapshot.copy(
        analogProcedural = snapshot.analogProcedural.updated(0, inverted)
      )
      val rendered = AnalogProceduralMlir.renderModule(modified, program.owner).head
      val declaration0 = rendered.indexOf("operation_order = 0 : i64")
      val assignment0 = rendered.indexOf("operation_order = 1 : i64")
      val declaration1 = rendered.indexOf("operation_order = 2 : i64")
      val assignment1 = rendered.indexOf("operation_order = 3 : i64")
      assert(declaration0 >= 0)
      assert(assignment0 > declaration0)
      assert(declaration1 > assignment0)
      assert(assignment1 > declaration1)

      val document = ScalaToMlirBridge.fromSnapshot(modified)
      sys.env.get("NODAL_NODALC").foreach: executable =>
        val directory = workDirectory()
        try
          val success = NativeCompilerClient
            .run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--mlir-print-op-generic"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            )
            .asInstanceOf[NativeCompilerSuccess]
          assert(success.normalizedMlir.contains("operation_order"))
        finally delete(directory)

    test("structured analog control flow serializes without flattening"):
      val first = ScalaToMlirBridge.lower(new BridgeStructuredProceduralTop)
      val second = ScalaToMlirBridge.lower(new BridgeStructuredProceduralTop)

      assert(first == second)
      assert(first.text.contains("\"nodal.analog_if\""))
      assert(first.text.contains("\"nodal.analog_if_arm\""))
      assert(first.text.contains("\"nodal.analog_case\""))
      assert(first.text.contains("\"nodal.analog_case_arm\""))
      assert(first.text.contains("\"nodal.analog_loop\""))
      assert(first.text.contains("\"nodal.analog_break\""))
      assert(first.text.contains("\"nodal.analog_continue\""))
      assert(first.text.contains("static_trip_count_present"))
      assert(first.text.contains("minimum_iterations = 1 : i64"))
      assert(first.text.contains("maximum_iterations = 4 : i64"))
      assert(first.text.contains("nodal.bridge.analog_procedural"))
      assert(first.text.contains("semantic_path = \"BridgeStructuredProceduralTop.case_"))
      assert(first.text.contains("semantic_path = \"BridgeStructuredProceduralTop.loop_"))
      val snapshot = ConstructionKernel.inspect(new BridgeStructuredProceduralTop)
      val program = snapshot.analogProcedural.head
      assert(program.assignments.isEmpty)
      assert(program.controlFlow.nonEmpty)
      assert(program.controlExpressions.exists(_.role == "assignment-value"))
      assert(program.controlExpressions.exists(_.role == "loop-bound"))

    test("snapshot insertion order does not affect the bridge"):
      val snapshot = ConstructionKernel.inspect(new BridgeTop)
      val permuted = snapshot.copy(
        modules = snapshot.modules.reverse.map(module =>
          module.copy(
            domains = module.domains.reverse,
            declarations = module.declarations.reverse,
            instances = module.instances.reverse
          )
        ),
        interfaceAbi = snapshot.interfaceAbi.reverse,
        resolvedNets = snapshot.resolvedNets.reverse,
        topology = snapshot.topology.reverse,
        names = snapshot.names.reverse,
        origins = snapshot.origins.reverse,
        generatedNames = snapshot.generatedNames.reverse,
        sourceMap = snapshot.sourceMap.reverse
      )

      assert(
        ScalaToMlirBridge.fromSnapshot(snapshot).text ==
          ScalaToMlirBridge.fromSnapshot(permuted).text
      )

    test("unsupported exact type fails before process launch"):
      val snapshot = ConstructionKernel.inspect(new BridgeTop)
      val modules = snapshot.modules.map: module =>
        module.copy(
          declarations = module.declarations.map: declaration =>
            if declaration.kind == "parameter" then
              declaration.copy(dataType = Some("Unsupported(3)"))
            else declaration
        )
      val failure = scala.util
        .Try(ScalaToMlirBridge.fromSnapshot(snapshot.copy(modules = modules)))
        .failed
        .get
        .asInstanceOf[BridgeException]

      assert(failure.diagnostic.code == "NODAL-BRIDGE-019")

    test("argv-safe process success, cleanup, and recovery"):
      val directory = workDirectory()
      try
        val document = ScalaToMlirBridge.lower(new BridgeTop)
        val request = NativeCompilerRequest(
          executable = Path.of("/bin/sh"),
          arguments = Vector("-c", "cat \"$1\"", "nodal-bridge"),
          workingDirectory = directory,
          timeout = Duration.ofSeconds(5)
        )
        val success = NativeCompilerClient
          .run(document, request)
          .asInstanceOf[NativeCompilerSuccess]

        assert(success.normalizedMlir == document.text)
        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()

        val failure = NativeCompilerClient
          .run(
            document,
            request.copy(
              arguments = Vector(
                "-c",
                "printf 'intentional failure' >&2; exit 7",
                "nodal-bridge"
              )
            )
          )
          .asInstanceOf[NativeCompilerFailure]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-PROCESS-007")
        assert(failure.exitCode.contains(7))
        assert(failure.standardError.contains("intentional failure"))

        val recovered = NativeCompilerClient
          .run(document, request)
          .asInstanceOf[NativeCompilerSuccess]
        assert(recovered.normalizedMlir == document.text)
      finally delete(directory)

    test("timeout is distinct and leaves no partial accepted output"):
      val directory = workDirectory()
      try
        val document = ScalaToMlirBridge.lower(new BridgeTop)
        val result = NativeCompilerClient
          .run(
            document,
            NativeCompilerRequest(
              executable = Path.of("/bin/sh"),
              arguments = Vector("-c", "sleep 5", "nodal-bridge"),
              workingDirectory = directory,
              timeout = Duration.ofMillis(100)
            )
          )
          .asInstanceOf[NativeCompilerFailure]

        assert(result.diagnostic.code == "NODAL-BRIDGE-PROCESS-006")
        assert(result.exitCode.isEmpty)
        assert(result.standardOutput.isEmpty)
        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()
      finally delete(directory)

    test("locked nodalc parses procedural bridge MLIR when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new BridgeProceduralTop)
            val success = NativeCompilerClient
              .run(
                document,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--mlir-print-op-generic"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              )
              .asInstanceOf[NativeCompilerSuccess]
            assert(success.normalizedMlir.contains("\"nodal.analog_variable\""))
            assert(success.normalizedMlir.contains("\"nodal.analog_variable_read\""))
            assert(success.normalizedMlir.contains("\"nodal.analog_assign\""))
            assert(success.normalizedMlir.contains("nodal.bridge.source_map"))
            assert(success.normalizedMlir.contains(".read_0"))
            assert(success.normalizedMlir.contains("authored_order"))
          finally delete(directory)

    test("locked nodalc parses and normalizes bridge MLIR when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new BridgeTop)
            val result = NativeCompilerClient
              .run(
                document,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--mlir-print-op-generic"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              )
            result match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("nodal.bridge.schema"))
                assert(success.normalizedMlir.contains("\"nodal.module\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
          finally delete(directory)

    test("locked nodalc verifies static hierarchy override DAGs when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          Vector(
            ScalaToMlirBridge.lower(new BridgeHierarchyExpressionTop),
            ScalaToMlirBridge.lower(new BridgeBooleanExpressionTop)
          ).foreach: document =>
            val directory = workDirectory()
            try
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
                  assert(success.normalizedMlir.contains("\"nodal.parameter_override\""))
                  assert(success.normalizedMlir.contains("\"nodal.const_expr\""))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(
                    false,
                    s"${failure.diagnostic}\n${failure.standardError}"
                  )
            finally delete(directory)

    test("public Scala hierarchy compiles to reusable named Verilog-A instances when configured"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new BridgeHierarchyTop,
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
            assert(result.verilogA.contains("module child(vin, vout);"))
            assert(result.verilogA.contains("module BridgeHierarchyTop(vin, vout);"))
            assert(
              result.verilogA.contains(
                "child #(.gain(rootGain)) child_instance(.vin(vin), .vout(vout));"
              )
            )
            assert(occurrences(result.verilogA, "module child") == 1)
            assert(!result.verilogA.contains("module child_gain"))

            val combinations = Vector[(String, () => Module, String, String)](
              (
                "HierarchyEquationTop",
                () => new HierarchyEquationTop,
                "nodal.bridge.analog_semantics",
                "child #(.gain(rootGain)) child_instance(.vin(vin), .vout(vout));"
              ),
              (
                "HierarchyEventTop",
                () => new HierarchyEventTop,
                "\"nodal.analog_initial_step\"",
                "@(initial_step)"
              ),
              (
                "HierarchyFunctionTop",
                () => new HierarchyFunctionTop,
                "\"nodal.analog_user_call\"",
                "analog function real scaleSignal;"
              )
            )
            combinations.foreach: (name, construct, mlirWitness, targetWitness) =>
              val combinationDirectory = Files.createDirectory(directory.resolve(name))
              val combination = ScalaToMlirBridge
                .compileToVerilogA(
                  construct(),
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  combinationDirectory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
              assert(combination.mlir.contains(mlirWitness))
              assert(combination.verilogA.contains(s"module $name(vin, vout);"))
              assert(
                combination.verilogA.contains(
                  "child #(.gain(rootGain)) child_instance(.vin(vin), .vout(vout));"
                )
              )
              assert(combination.verilogA.contains(targetWitness))
              assert(occurrences(combination.verilogA, "module child") == 1)
              assert(!combination.verilogA.contains("module child_gain"))

            val repeatedDirectory = Files.createDirectory(directory.resolve("repeated"))
            val repeated = ScalaToMlirBridge
              .compileToVerilogA(
                new HierarchyRepeatedTop,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                repeatedDirectory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(occurrences(repeated.verilogA, "module first") == 1)
            assert(!repeated.verilogA.contains("module second"))
            assert(!repeated.verilogA.contains("module third"))
            assert(!repeated.verilogA.contains("module fourth"))
            assert(occurrences(repeated.verilogA, "first #(") == 4)
            assert(repeated.verilogA.contains(".gain(rootGain)"))
            assert(repeated.verilogA.contains(".gain(3)"))
            assert(repeated.verilogA.contains(".gain(5)"))

            val nestedDirectory = Files.createDirectory(directory.resolve("nested"))
            val nested = ScalaToMlirBridge
              .compileToVerilogA(
                new HierarchyNestedTop,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                nestedDirectory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(nested.verilogA.contains("module leaf(vin, vout);"))
            assert(nested.verilogA.contains("module firstBranch(vin, vout);"))
            assert(nested.verilogA.contains("module HierarchyNestedTop(vin0, vin1, vout0, vout1);"))
            assert(nested.verilogA.contains("leaf #(.gain(branchGain)) leaf_instance"))
            assert(occurrences(nested.verilogA, "firstBranch #(") == 2)
            assert(nested.verilogA.contains(".branchGain(rootGain)"))
            assert(nested.verilogA.contains(".branchGain(6)"))

            val rejectedDirectory = Files.createDirectory(directory.resolve("non-default-root"))
            ScalaToMlirBridge.compileToVerilogA(
              new BridgeHierarchyTop(rootGain = 6.0),
              Path.of(nodalc).toAbsolutePath,
              Path.of(translator).toAbsolutePath,
              rejectedDirectory,
              Duration.ofSeconds(60)
            ) match
              case Left(failure) =>
                assert(
                  s"${failure.diagnostic}\n${failure.standardError}".contains(
                    "NODAL-BACKEND-HIERARCHY-010"
                  )
                )
              case Right(_) =>
                scala.Predef.assert(false, "non-default root actual was silently discarded")
          finally delete(directory)
        case _ => assert(true)
