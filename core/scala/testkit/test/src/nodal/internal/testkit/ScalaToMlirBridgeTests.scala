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

object ScalaToMlirBridgeTests extends TestSuite:
  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-bridge-test-")

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

    test("locked nodalc parses and normalizes bridge MLIR when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new BridgeTop)
            val result = NativeCompilerClient.run(
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
                throw new java.lang.AssertionError(failure.toString)
          finally delete(directory)
