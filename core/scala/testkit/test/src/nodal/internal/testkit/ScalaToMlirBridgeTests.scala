package nodal.internal.testkit

import nodal.*
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
      assert(first.text.contains("nodal.bridge.declarations"))
      assert(first.text.contains("nodal.bridge.origins"))
      assert(first.text.contains("loc(\""))
      assert(first.text.endsWith("\n"))
      assert(!first.text.contains("\r"))

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
            assert(success.normalizedMlir.contains("nodal.bridge.schema"))
            assert(success.normalizedMlir.contains("\"nodal.module\""))
          finally delete(directory)
