package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43DeclaredMaximumGeneratedNode extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes, 1, 4): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

object Increment43GenerateCountTests extends TestSuite:
  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private val fixtures: Vector[() => Module] = Vector(
    () => new Increment43SymbolicGeneratedNode,
    () => new Increment43LiteralGeneratedNode,
    () => new Increment43DeclaredMaximumGeneratedNode
  )

  private def replaceContract(
      document: NodalMlirDocument,
      path: String,
      needle: String,
      replacement: String
  ): NodalMlirDocument =
    val lines = document.text.split("\n", -1).toVector
    val identity = s"""region_id = "$path""""
    val selected = lines.zipWithIndex.filter: (line, _) =>
      line.trim.startsWith("\"nodal.generate\"(") && line.contains(identity)
    assert(selected.size == 1)
    val (line, index) = selected.head
    assert(line.sliding(needle.length).count(_ == needle) == 1)
    val changed = line.replace(needle, replacement)
    assert(changed != line)
    val text = lines.updated(index, changed).mkString("\n")
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x").mkString
    document.copy(text = text, sha256 = hash)

  val tests: Tests = Tests:
    test("count mutations select the actual generated region without changing other lines"):
      for factory <- fixtures do
        val snapshot = ConstructionKernel.inspect(factory())
        assert(snapshot.generatedRegions.size == 1)
        val region = snapshot.generatedRegions.head
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val needle = s"maximum_trip_count = ${region.maximumTripCount} : i64"
        val changed = replaceContract(document, region.path, needle, "maximum_trip_count = 0 : i64")
        val originalLines = document.text.split("\n", -1).toVector
        val changedLines = changed.text.split("\n", -1).toVector
        assert(originalLines.size == changedLines.size)
        assert(originalLines.head == changedLines.head)
        assert(originalLines.zip(changedLines).count((left, right) => left != right) == 1)
        assert(changed.sha256 != document.sha256)

    test("native independently rejects forged counts from public symbolic and literal ranges"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-generate-count-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            for factory <- fixtures do
              val snapshot = ConstructionKernel.inspect(factory())
              assert(snapshot.generatedRegions.size == 1)
              val region = snapshot.generatedRegions.head
              val document = ScalaToMlirBridge.fromSnapshot(snapshot)
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("\"nodal.generate\""))
                case rejected: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${rejected.diagnostic}\n${rejected.standardError}")

              val count = region.maximumTripCount
              val needle = s"maximum_trip_count = $count : i64"
              val countForgeries = Vector(
                s"maximum_trip_count = ${count - 1} : i64",
                s"maximum_trip_count = ${count + 1} : i64",
                "maximum_trip_count = -1 : i64",
                s"maximum_trip_count = $count : i32",
                "maximum_trip_count = true"
              ).map(replacement => needle -> replacement)
              val limitForgery = region.maximum match
                case Some(limit) =>
                  s"declared_maximum = $limit : i64" ->
                    s"declared_maximum = ${count - 1} : i64"
                case None =>
                  needle -> s"$needle, declared_maximum = ${count - 1} : i64"
              for (original, replacement) <- countForgeries :+ limitForgery do
                val forged = replaceContract(document, region.path, original, replacement)
                NativeCompilerClient.run(forged, request) match
                  case rejected: NativeCompilerFailure =>
                    assert(rejected.exitCode.contains(1))
                    assert(rejected.diagnostic.code == "NODAL-ITERATION-043-001")
                  case success: NativeCompilerSuccess =>
                    scala.Predef.assert(
                      false,
                      s"forged generate count accepted: ${success.normalizedMlir}"
                    )
          finally delete(directory)
