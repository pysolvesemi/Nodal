package nodal.internal.testkit

import nodal.internal.bridge.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import utest.*

object CrossLayerDiagnosticTests extends TestSuite:
  private val emptyDocument =
    val text = "module {}\n"
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x")
      .mkString
    NodalMlirDocument("nodal.scala-to-mlir", 1, text, digest)

  private def temporaryDirectory(): Path =
    Files.createTempDirectory("nodal-diagnostic-test-")

  private def remove(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do remove(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  val tests: Tests = Tests:
    test("native stable code and mapped context survive process normalization"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "src/Top.scala:12:7: error: NODAL-INTERFACE-ROLE-002: incompatible roles " +
          "[semantic-path=Top.link] [hierarchy-path=Top] " +
          "[index-path=[lane=1]] [source-range=src/Top.scala:12:7-12:24]\n",
        1
      )

      assert(diagnostic.code == "NODAL-INTERFACE-ROLE-002")
      assert(diagnostic.semanticPath.contains("Top.link"))
      assert(diagnostic.hierarchyPath.contains("Top"))
      assert(diagnostic.indexPath.contains("[lane=1]"))
      assert(diagnostic.sourceRange.contains("src/Top.scala:12:7-12:24"))
      assert(diagnostic.toString.contains("[semantic-path=Top.link]"))

    test("parser and pass failures receive distinct fallback families"):
      val parser = NativeDiagnosticMapper.classify(
        "input.mlir:1:4: error: expected operation name\n",
        1
      )
      val pass = NativeDiagnosticMapper.classify(
        "error: failed to run pass pipeline 'nodal-gate-default'\n",
        1
      )

      assert(parser.code == "NODAL-DIAGNOSTIC-PARSER-001")
      assert(pass.code == "NODAL-DIAGNOSTIC-PASS-001")

    test("native compiler client preserves mapped native diagnostics"):
      val directory = temporaryDirectory()
      try
        val result = NativeCompilerClient.run(
          emptyDocument,
          NativeCompilerRequest(
            executable = Path.of("/bin/sh"),
            arguments = Vector(
              "-c",
              "printf '%s\\n' 'error: NODAL-AMS-BRIDGE-001: explicit bridge required [semantic-path=Top.sample] [source-range=src/Top.scala:20:3-20:28]' >&2; exit 9",
              "nodal-diagnostic"
            ),
            workingDirectory = directory,
            timeout = Duration.ofSeconds(5)
          )
        ).asInstanceOf[NativeCompilerFailure]

        assert(result.exitCode.contains(9))
        assert(result.diagnostic.code == "NODAL-AMS-BRIDGE-001")
        assert(result.diagnostic.semanticPath.contains("Top.sample"))
        assert(result.diagnostic.sourceRange.contains("src/Top.scala:20:3-20:28"))
      finally remove(directory)

    test("unclassified external failure retains Increment 20 process contract"):
      val directory = temporaryDirectory()
      try
        val result = NativeCompilerClient.run(
          emptyDocument,
          NativeCompilerRequest(
            executable = Path.of("/bin/sh"),
            arguments = Vector("-c", "printf 'plain failure' >&2; exit 7", "nodal"),
            workingDirectory = directory,
            timeout = Duration.ofSeconds(5)
          )
        ).asInstanceOf[NativeCompilerFailure]

        assert(result.diagnostic.code == "NODAL-BRIDGE-PROCESS-007")
      finally remove(directory)
