package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43ThreeDimensionalGeneratedDdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val depths: Param[Integer] = param(6.integer, range = 4 to 8)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      hdlRange(-2, depths, 2): _ =>
        val firstTap = node(Electrical)
        val secondTap = node(Electrical)
        analog:
          I(firstTap, p) <+ (1.pF * ddt(V(firstTap, p)))
          I(secondTap, n) <+ (2.pF * ddt(V(secondTap, n)))

final class Increment43FourDimensionalGeneratedResistor extends Module:
  val p = inout(Electrical)

  hdlRange(-2, 1): _ =>
    hdlRange(1, 6, 2): _ =>
      hdlRange(2, 5): _ =>
        hdlRange(3, 8, 2): _ =>
          val tap = node(Electrical)
          analog:
            I(tap, p) <+ (V(tap, p) / 2.kOhm)

final class Increment43ThreeDimensionalPermutedDdt extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, 5, 2): _ =>
    hdlRange(2, 8, 3): _ =>
      hdlRange(-2, 2, 2): _ =>
        val firstTap = node(Electrical)
        val secondTap = node(Electrical)
        analog:
          I(secondTap, n) <+ (1.pF * ddt(V(firstTap, p)))
          I(firstTap, p) <+ (2.pF * ddt(V(secondTap, n)))

object Increment43DeepGenerationTests extends TestSuite:
  private def withNativeTools(body: (Path, Path, Path) => Unit): Unit =
    (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
      case (Some(nodalc), Some(translator)) =>
        val compilerPath = Path.of(nodalc).toAbsolutePath
        val translatorPath = Path.of(translator).toAbsolutePath
        assert(Files.isExecutable(compilerPath), Files.isExecutable(translatorPath))
        val directory = Files.createTempDirectory("nodal-increment43-deep-")
        try body(compilerPath, translatorPath, directory)
        finally delete(directory)
      case _ => ()

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def compile(
      create: () => Module,
      nodalc: Path,
      translator: Path,
      directory: Path
  ): VerilogAVerticalSliceResult =
    ScalaToMlirBridge.compileToVerilogA(
      create(),
      nodalc,
      translator,
      directory,
      Duration.ofSeconds(60)
    ).fold(
      failure => throw new java.lang.AssertionError(failure.toString),
      identity
    )

  private def generatedInductionSymbol(document: NodalMlirDocument, regionPath: String): String =
    val marker = s"region_id = \"$regionPath\""
    val lines = document.text.linesIterator.filter(line =>
      line.contains("\"nodal.generate\"") && line.contains(marker)
    ).toVector
    assert(lines.size == 1)
    "induction = \"([^\"]+)\"".r.findFirstMatchIn(lines.head).get.group(1)

  private def assertLoopChain(
      document: NodalMlirDocument,
      verilogA: String,
      regions: Vector[KernelGeneratedRegionSnapshot],
      bounds: Vector[(String, String, String)]
  ): Vector[String] =
    val symbols = regions.map(region => generatedInductionSymbol(document, region.path))
    assert(symbols.distinct.size == regions.size)
    assert(verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == regions.size)
    val loops = verilogA.linesIterator.filter(_.trim.startsWith("for (")).map(_.trim).toVector
    val expected = regions.zip(symbols).zip(bounds).map:
      case ((region, symbol), (lower, upper, step)) =>
        assert(verilogA.linesIterator.count(_.trim == s"genvar $symbol;") == 1)
        s"for ($symbol = $lower; $symbol < $upper; $symbol = $symbol + $step) " +
          s"begin : ${region.path.split('.').last}"
    assert(loops == expected)
    symbols

  private def assertLeafOwnership(
      snapshot: ConstructionSnapshot,
      document: NodalMlirDocument,
      dimensions: Int,
      nodes: Int
  ): Vector[KernelGeneratedRegionSnapshot] =
    val regions = snapshot.generatedRegions.sortBy(_.path.count(_ == '.'))
    assert(regions.size == dimensions)
    assert(regions.head.parent.isEmpty)
    regions.sliding(2).foreach: pair =>
      assert(pair(1).parent.contains(pair.head.path))
    assert(regions.dropRight(1).forall(_.declarations.isEmpty))
    val leaf = regions.last
    assert(leaf.declarations.size == nodes)
    assert(leaf.declarations.distinct.size == nodes)
    assert(snapshot.analogRegions.size == 1)
    val analog = snapshot.analogRegions.head
    assert(analog.generatedOwner.contains(leaf.path))
    assert(analog.generatedInduction.contains(leaf.induction))
    assert(analog.contributions.size == nodes)
    analog.contributions.foreach: contribution =>
      assert(contribution.generatedOwner.contains(leaf.path))
      assert(contribution.generatedInduction.contains(leaf.induction))
    snapshot.continuousOperators.foreach: operator =>
      assert(operator.generatedOwner.contains(leaf.path))
      assert(operator.generatedInduction.contains(leaf.induction))

    val paths = regions.flatMap(region => Vector(region.induction) ++ region.declarations) ++
      snapshot.continuousOperators.map(_.path)
    paths.foreach: path =>
      assert(snapshot.sourceMap.count(_.semanticPath == path) == 1)
    val sourceMapStart = document.text.indexOf("nodal.bridge.source_map")
    val sourceMapEnd = document.text.indexOf("nodal.bridge.topology", sourceMapStart)
    assert(sourceMapStart >= 0, sourceMapEnd > sourceMapStart)
    val sourceMap = document.text.substring(sourceMapStart, sourceMapEnd)
    (paths ++ regions.map(_.path)).foreach: path =>
      val marker = s"semantic_path = \"$path\""
      assert(sourceMap.sliding(marker.length).count(_ == marker) == 1)
    regions

  private def expectNativeFailure(
      document: NodalMlirDocument,
      text: String,
      translator: Path,
      directory: Path,
      expectedCode: String,
      expectedMessage: Option[String]
  ): Unit =
    assert(text != document.text)
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x").mkString
    NativeCompilerClient.run(
      document.copy(text = text, sha256 = hash),
      NativeCompilerRequest(
        executable = translator,
        arguments = Vector("--nodal-to-verilog-a"),
        workingDirectory = directory,
        timeout = Duration.ofSeconds(30)
      )
    ) match
      case failure: NativeCompilerFailure =>
        assert(failure.diagnostic.code == expectedCode)
        expectedMessage.foreach(message => assert(failure.standardError.contains(message)))
      case success: NativeCompilerSuccess =>
        throw new java.lang.AssertionError(s"forged deep generated ownership accepted: $success")

  val tests: Tests = Tests:
    test("three-dimensional symbolic ddt retains compact row-major leaf ownership"):
      val create = () => new Increment43ThreeDimensionalGeneratedDdt
      val snapshot = ConstructionKernel.inspect(create())
      assert(snapshot == ConstructionKernel.inspect(create()))
      val document = ScalaToMlirBridge.lower(create())
      assert(document == ScalaToMlirBridge.lower(create()))
      val regions = assertLeafOwnership(snapshot, document, 3, 2)
      assert(regions.map(_.maximumTripCount) == Vector(5, 4, 5))
      assert(snapshot.continuousOperators.map(_.operation) == Vector.fill(2)("analog_ddt"))
      assert(snapshot.continuousOperators.map(_.path).distinct.size == 2)
      withNativeTools: (nodalc, translator, directory) =>
        val result = compile(create, nodalc, translator, directory)
        assert(result == compile(create, nodalc, translator, directory))
        val symbols = assertLoopChain(
          document,
          result.verilogA,
          regions,
          Vector(("1", "rows", "2"), ("2", "columns", "3"), ("-2", "depths", "2"))
        )
        val rowCount = "((((rows - 1) + 2) - 1) / 2)"
        val columnCount = "((((columns - 2) + 3) - 1) / 3)"
        val depthCount = "((((depths - -2) + 2) - 1) / 2)"
        val firstTwo =
          s"((((${symbols(0)} - 1) / 2) * $columnCount) + ((${symbols(1)} - 2) / 3))"
        val flatIndex = s"(($firstTwo * $depthCount) + ((${symbols(2)} - -2) / 2))"
        val totalCount = s"(($rowCount * $columnCount) * $depthCount)"
        val names = regions.last.declarations.map(_.split('.').last)
        names.zip(Vector(("p", "1e-12"), ("n", "2e-12"))).foreach:
          case (name, (terminal, capacitance)) =>
            assert(result.verilogA.linesIterator.count(
              _.trim == s"electrical $name[0:($totalCount - 1)];"
            ) == 1)
            val branch = s"$name[$flatIndex], $terminal"
            assert(result.verilogA.linesIterator.count(
              _.trim == s"I($branch) <+ ($capacitance * ddt(V($branch)));"
            ) == 1)
        assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 3)
        assert(result.verilogA.linesIterator.count(_.contains("<+")) == 2)
        assert(result.verilogA.sliding(4).count(_ == "ddt(") == 2)
        val leaf = regions.last
        val intermediate = regions(1)
        Vector(
          "nodal.node" -> "NODAL-ITERATION-043-004",
          "nodal.branch" -> "NODAL-BACKEND-CAPABILITY-001",
          "nodal.analog_ddt" -> "NODAL-ITERATION-043-004"
        ).foreach: (operation, expectedCode) =>
          val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
          val forged = line.replace(
            s"generated_owner = \"${leaf.path}\"",
            s"generated_owner = \"${intermediate.path}\""
          )
          assert(forged != line)
          expectNativeFailure(
            document,
            document.text.patch(document.text.indexOf(line), forged, line.length),
            translator,
            directory,
            expectedCode,
            Option.when(operation == "nodal.branch")(
              "generated analog-node branch ownership does not match its range"
            )
          )

    test("four-dimensional literal equations preserve every authored ordinal"):
      val create = () => new Increment43FourDimensionalGeneratedResistor
      val snapshot = ConstructionKernel.inspect(create())
      assert(snapshot == ConstructionKernel.inspect(create()))
      val document = ScalaToMlirBridge.lower(create())
      assert(document == ScalaToMlirBridge.lower(create()))
      val regions = assertLeafOwnership(snapshot, document, 4, 1)
      assert(regions.map(_.maximumTripCount) == Vector.fill(4)(3))
      assert(snapshot.continuousOperators.isEmpty)
      withNativeTools: (nodalc, translator, directory) =>
        val result = compile(create, nodalc, translator, directory)
        assert(result == compile(create, nodalc, translator, directory))
        val symbols = assertLoopChain(
          document,
          result.verilogA,
          regions,
          Vector(("-2", "1", "1"), ("1", "6", "2"), ("2", "5", "1"), ("3", "8", "2"))
        )
        val counts = Vector(
          "(1 - -2)",
          "((((6 - 1) + 2) - 1) / 2)",
          "(5 - 2)",
          "((((8 - 3) + 2) - 1) / 2)"
        )
        val ordinals = Vector(
          s"(${symbols(0)} - -2)",
          s"((${symbols(1)} - 1) / 2)",
          s"(${symbols(2)} - 2)",
          s"((${symbols(3)} - 3) / 2)"
        )
        val totalCount = counts.reduce((previous, count) => s"($previous * $count)")
        val flatIndex = counts.zip(ordinals).tail.foldLeft(ordinals.head):
          case (previous, (count, ordinal)) => s"(($previous * $count) + $ordinal)"
        val name = regions.last.declarations.head.split('.').last
        assert(result.verilogA.linesIterator.count(
          _.trim == s"electrical $name[0:($totalCount - 1)];"
        ) == 1)
        val branch = s"$name[$flatIndex], p"
        assert(result.verilogA.linesIterator.count(
          _.trim == s"I($branch) <+ (V($branch) / 2000);"
        ) == 1)
        assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
        assert(result.verilogA.linesIterator.count(_.contains("<+")) == 1)

    test("three-dimensional ddt rejects cross-branch destinations with equal totals"):
      val create = () => new Increment43ThreeDimensionalPermutedDdt
      val document = ScalaToMlirBridge.lower(create())
      assert(document == ScalaToMlirBridge.lower(create()))
      val accessBranch = "\"nodal.access\"\\((%[^,)]+)".r
      val contributeBranch = "\"nodal.contribute\"\\((%[^,)]+)".r
      val accessed = document.text.linesIterator.filter(_.contains("kind = \"potential\""))
        .flatMap(line => accessBranch.findFirstMatchIn(line).map(_.group(1))).toVector
      val contributed = contributeBranch.findAllMatchIn(document.text).map(_.group(1)).toVector
      assert(accessed.distinct.size == 2, contributed.distinct.size == 2)
      assert(accessed.toSet == contributed.toSet)
      assert(accessed.zip(contributed).forall((source, destination) => source != destination))
      withNativeTools: (nodalc, translator, directory) =>
        ScalaToMlirBridge.compileToVerilogA(
          create(),
          nodalc,
          translator,
          directory,
          Duration.ofSeconds(60)
        ) match
          case Left(failure) =>
            assert(failure.diagnostic.code == "NODAL-BACKEND-CAPABILITY-001")
            assert(failure.standardError.contains(
              "must feed one memoryless contribution on its branch"
            ))
          case Right(success) =>
            throw new java.lang.AssertionError(s"cross-branch deep ddt accepted: $success")
