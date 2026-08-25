package nodal.internal.testkit

import nodal.*
import nodal.bridge.*

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
      assert(first.text.contains("parameter_bindings"))
      assert(first.text.contains("nodal.bridge.source_map"))
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

    test("argv-safe process success and nonzero failure"):
      val directory = workDirectory()
      try
        val document = ScalaToMlirBridge.lower(new BridgeTop)
        val success = NativeCompilerClient
          .run(
            document,
            NativeCompilerRequest(
              executable = Path.of("/bin/cat"),
              arguments = Vector.empty,
              workingDirectory = directory,
              timeout = Duration.ofSeconds(5)
            )
          )
          .asInstanceOf[NativeCompilerSuccess]
        assert(success.normalizedMlir == document.text)

        val failure = NativeCompilerClient
          .run(
            document,
            NativeCompilerRequest(
              executable = Path.of("/bin/sh"),
              arguments = Vector("-c", "exit 7"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(5)
            )
          )
          .asInstanceOf[NativeCompilerFailure]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-PROCESS-007")
        assert(failure.exitCode.contains(7))

        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()
      finally delete(directory)

    test("timeout is distinct and cleanup is transactional"):
      val directory = workDirectory()
      try
        val document = ScalaToMlirBridge.lower(new BridgeTop)
        val failure = NativeCompilerClient
          .run(
            document,
            NativeCompilerRequest(
              executable = Path.of("/bin/sh"),
              arguments = Vector("-c", "exec sleep 5"),
              workingDirectory = directory,
              timeout = Duration.ofMillis(100)
            )
          )
          .asInstanceOf[NativeCompilerFailure]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-PROCESS-006")
        assert(failure.exitCode.isEmpty)

        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()
      finally delete(directory)

    test("locked nodalc parses bridge MLIR when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            NativeCompilerClient.run(
              ScalaToMlirBridge.lower(new BridgeTop),
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--mlir-print-op-generic"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("nodal.bridge.schema"))
                assert(success.normalizedMlir.contains("\"nodal.module\""))
              case failure: NativeCompilerFailure =>
                throw AssertionError(failure.toString)
          finally delete(directory)
