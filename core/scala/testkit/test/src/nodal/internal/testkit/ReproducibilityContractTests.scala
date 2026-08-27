package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import utest.*

final class ReproducibilityDomainFixture extends Module:
  val core: ClockDomain = ClockDomain.external(
    "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )
  val bit: Signal[Bool] = in(Bool)
  val resetInput: Signal[Reset] = in(Reset)
  val output: Signal[Bool] = out(Bool)

  core:
    val synchronized = Cdc.sync(bit, core)
    val waived = Cdc.waive(
      synchronized,
      core,
      CdcWaiver(
        id = "fixture-cdc-waiver",
        reason = "exercise deterministic waiver inventory",
        relation = ClockRelation.Asynchronous
      )
    )
    val resetSynchronized = Rdc.sync(resetInput, core)
    output := waived
    CandidateRuntime.statement(resetSynchronized)

object ReproducibilityContractTests extends TestSuite:
  private def repositoryRoot(): Path =
    def locate(current: Path): Path =
      if Files.isRegularFile(current.resolve("build.mill")) then current
      else
        Option(current.getParent) match
          case Some(parent) => locate(parent)
          case None =>
            scala.util.Failure[Path](
              new IllegalStateException("could not locate the Nodal repository root")
            ).get
    locate(Path.of("").toAbsolutePath)

  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-reproducibility-")

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def permute(snapshot: ConstructionSnapshot): ConstructionSnapshot =
    snapshot.copy(
      modules = snapshot.modules.reverse.map: module =>
        module.copy(
          domains = module.domains.reverse,
          declarations = module.declarations.reverse,
          instances = module.instances.reverse
        ),
      interfaceAbi = snapshot.interfaceAbi.reverse,
      resolvedNets = snapshot.resolvedNets.reverse.map(net =>
        net.copy(operations = net.operations.reverse)
      ),
      topology = snapshot.topology.reverse,
      names = snapshot.names.reverse,
      origins = snapshot.origins.reverse.map(origin =>
        origin.copy(parents = origin.parents.reverse)
      ),
      generatedNames = snapshot.generatedNames.reverse,
      sourceMap = snapshot.sourceMap.reverse,
      analogRegions = snapshot.analogRegions,
      waivers = snapshot.waivers.reverse
    )

  private def success(
      value: Either[NativeCompilerFailure, ReproducibilityBundle]
  ): ReproducibilityBundle =
    value.fold(
      failure =>
        scala.util.Failure[Nothing](
          new java.lang.AssertionError(failure.toString)
        ).get,
      identity
    )

  private def topLevelArrayObjectCount(text: String, key: String): Int =
    val marker = s"\"$key\":"
    val keyIndex = text.indexOf(marker)
    assert(keyIndex >= 0)
    val open = text.indexOf('[', keyIndex + marker.length)
    assert(open >= 0)

    var arrayDepth = 1
    var objectDepth = 0
    var inString = false
    var escaped = false
    var objects = 0
    var index = open + 1
    while index < text.length && arrayDepth > 0 do
      val character = text.charAt(index)
      if inString then
        if escaped then escaped = false
        else
          character match
            case '\\' => escaped = true
            case '"' => inString = false
            case _ => ()
      else
        character match
          case '"' => inString = true
          case '[' => arrayDepth += 1
          case ']' => arrayDepth -= 1
          case '{' =>
            if arrayDepth == 1 && objectDepth == 0 then objects += 1
            objectDepth += 1
          case '}' => objectDepth -= 1
          case _ => ()
      index += 1

    assert(arrayDepth == 0)
    assert(objectDepth == 0)
    objects

  val tests: Tests = Tests:
    test("construction failures use declared result channel"):
      val directory = workDirectory()
      try
        ReproducibilityContract.capture(
          new UnboundKernelRoot,
          directory.resolve("missing-nodalc"),
          directory.resolve("missing-translate"),
          directory
        ) match
          case Left(failure) =>
            assert(failure.diagnostic.code == "NODAL-ROOT-DOMAIN-016")
            assert(failure.command.isEmpty)
            assert(failure.standardOutput.isEmpty)
            assert(failure.standardError.isEmpty)
            assert(failure.exitCode.isEmpty)
          case Right(_) => assert(false)
      finally delete(directory)

    test("canonical artifacts survive repeated construction and valid traversal orders"):
      val firstSnapshot = ConstructionKernel.inspect(
        new RcFilter,
        EmitOptions(backend = Backend.VerilogA)
      )
      val secondSnapshot = ConstructionKernel.inspect(
        new RcFilter,
        EmitOptions(backend = Backend.VerilogA)
      )
      val first = ReproducibilityContract.describe(firstSnapshot)
      val second = ReproducibilityContract.describe(secondSnapshot)
      val permuted = ReproducibilityContract.describe(permute(firstSnapshot))

      assert(first.construction == second.construction)
      assert(first.construction == permuted.construction)
      assert(first.sourceMlir == second.sourceMlir)
      assert(first.sourceMlir == permuted.sourceMlir)
      assert(first.manifest == second.manifest)
      assert(first.manifest == permuted.manifest)
      assert(first.manifest.sha256.matches("[0-9a-f]{64}"))

    test("manifest retains deterministic inventories and empty-or-explicit reports"):
      val snapshot = ConstructionKernel.inspect(
        new ReproducibilityDomainFixture,
        EmitOptions(backend = Backend.VerilogA)
      )
      val report = ReproducibilityContract.describe(snapshot).manifest.text

      assert(report.contains("\"shape_layout_storage\""))
      assert(report.contains("\"materialization\""))
      assert(report.contains("\"semantic_names\""))
      assert(report.contains("\"expression_source_map\""))
      assert(report.contains("\"check_inventory\""))
      assert(report.contains("\"waivers\""))
      assert(report.contains("\"domain_manifest\""))
      assert(report.contains("\"cdc_rdc_report\""))
      val waiverEntries = topLevelArrayObjectCount(report, "waivers")
      val crossingEntries = topLevelArrayObjectCount(report, "cdc_rdc_report")
      assert(waiverEntries > 0)
      assert(crossingEntries > 0)
      assert(report.contains("\"id\": \"fixture-cdc-waiver\""))
      assert(report.contains("\"reason\": \"exercise deterministic waiver inventory\""))
      assert(report.contains("\"relation\": \"asynchronous\""))
      assert(report.contains("core"))
      assert(!report.contains("nodal-scala-mlir-"))

    test("verified MLIR HDL and manifest are byte-identical across work directories"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val firstDirectory = workDirectory()
          val secondDirectory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(
              new RcFilter,
              EmitOptions(backend = Backend.VerilogA)
            )
            val first = success(
              ReproducibilityContract.captureSnapshot(
                snapshot,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                firstDirectory,
                Duration.ofSeconds(60)
              )
            )
            val second = success(
              ReproducibilityContract.captureSnapshot(
                permute(snapshot),
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                secondDirectory,
                Duration.ofSeconds(60)
              )
            )
            val golden = Files.readString(
              repositoryRoot().resolve(
                "tests/compiler/fixtures/increment25/golden/rc-filter.va"
              )
            )

            assert(first.construction == second.construction)
            assert(first.sourceMlir == second.sourceMlir)
            assert(first.normalizedMlir == second.normalizedMlir)
            assert(first.hdl == second.hdl)
            assert(first.hdl == golden)
            assert(first.manifest == second.manifest)
            assert(first.normalizedMlir.contains("nodal.pipeline.normalized"))
            assert(first.manifest.text.contains("construction.json"))
            assert(first.manifest.text.contains("normalized.mlir"))
            assert(first.manifest.text.contains("output.va"))
          finally
            delete(firstDirectory)
            delete(secondDirectory)
        case _ => assert(true)
