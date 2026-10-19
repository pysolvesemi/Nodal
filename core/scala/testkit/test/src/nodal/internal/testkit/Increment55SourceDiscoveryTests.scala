package nodal.internal.testkit

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalScratchDiscoveryChild extends Module, DigitalCollectionClock:
  val input = in(UInt(4))
  val result = input + 1.U(4)

final class DigitalScratchDiscoveryParent extends Module:
  val first = instance(new DigitalScratchDiscoveryChild)
  val second = instance(new DigitalScratchDiscoveryChild)

object DigitalSourceDiscoveryProbe:
  def main(arguments: Array[String]): Unit =
    val expectedPath = arguments(0)
    val snapshot = ConstructionKernel.inspect(new DigitalScratchDiscoveryParent)
    val sources =
      snapshot.sourceMap.filter(_.source.path.endsWith("Increment55SourceDiscoveryTests.scala"))
    assert(sources.nonEmpty && sources.forall(_.source.path == expectedPath))
    assert(sources.exists(_.semanticPath == snapshot.root))
    if arguments(1) == "missing" then
      assert(sources.forall(entry =>
        entry.source.line > 0 && entry.source.column == 1 &&
          entry.source.endLine == entry.source.line && entry.source.endColumn == 1
      ))
    println("source lookup verified")

object Increment55SourceDiscoveryTests extends TestSuite:
  private val filename = "Increment55SourceDiscoveryTests.scala"
  private val relative = s"core/scala/testkit/test/src/nodal/internal/testkit/$filename"

  private def repository: Path =
    Iterator.iterate(Option(Path.of("").toAbsolutePath.normalize()))(
      _.flatMap(path => Option(path.getParent))
    ).takeWhile(_.nonEmpty).flatten.find(root =>
      Files.isRegularFile(root.resolve("build.mill")) && Files.isRegularFile(root.resolve(relative))
    ).get

  val tests: Tests = Tests:
    test(
      "ignored scratch duplicates cannot change authored spans identities or child normalization"
    ):
      val root = repository
      val duplicate = root.resolve(".work/increment55-source-discovery").resolve(relative)
      Files.createDirectories(duplicate.getParent)
      val _ = Files.deleteIfExists(duplicate)
      // Each inspect constructs a fresh SemanticOriginBuilder and source lookup cache.
      val baseline = ConstructionKernel.inspect(new DigitalScratchDiscoveryParent)
      val before = ScalaToMlirBridge.fromSnapshot(baseline).text
      val authored = baseline.sourceMap.filter(entry => entry.source.path.endsWith(filename))
      assert(authored.nonEmpty, authored.forall(_.source.path == relative))
      Files.copy(root.resolve(relative), duplicate, StandardCopyOption.REPLACE_EXISTING)
      try
        val repeated = ConstructionKernel.inspect(new DigitalScratchDiscoveryParent)
        val after = ScalaToMlirBridge.fromSnapshot(repeated).text
        val observed = repeated.sourceMap.filter(entry => entry.source.path.endsWith(filename))
        assert(observed.nonEmpty, observed.forall(_.source.path == relative))
        assert(repeated.sourceMap == baseline.sourceMap)
        assert(repeated.names == baseline.names)
        assert(repeated.digitalExpressions == baseline.digitalExpressions)
        assert(after == before)
        assert(after.linesIterator.count(_.contains("\"nodal.instance\"")) == 2)
      finally
        val _ = Files.deleteIfExists(duplicate)

    test(
      "explicit external roots retain authored nested work paths and truthful unavailable spans"
    ):
      val root = repository
      val authored = root.resolve(relative)
      for available <- Vector(true, false) do
        val external = Files.createTempDirectory(root.resolve(".work"), "f55-explicit-source-")
        Files.writeString(external.resolve("build.mill"), "// Source discovery fixture root\n")
        Files.createDirectories(external.resolve("core/scala"))
        Files.createDirectories(external.resolve("docs"))
        val expected =
          if available then s"external/.work/nodal/internal/testkit/$filename" else filename
        if available then
          val destination = external.resolve(expected)
          Files.createDirectories(destination.getParent)
          Files.copy(authored, destination)
        val log = external.resolve("probe.log")
        val builder = new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", "java").toString,
          "-cp",
          System.getProperty("java.class.path"),
          "nodal.internal.testkit.DigitalSourceDiscoveryProbe",
          expected,
          if available then "available" else "missing"
        ).directory(external.toFile).redirectErrorStream(true).redirectOutput(log.toFile)
        val _ = builder.environment().put("NODAL_WORKSPACE", external.toString)
        val process = builder.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if !finished then
          val _ = process.destroyForcibly()
        val output = Files.readString(log)
        assert(finished, process.exitValue() == 0, output.contains("source lookup verified"))
