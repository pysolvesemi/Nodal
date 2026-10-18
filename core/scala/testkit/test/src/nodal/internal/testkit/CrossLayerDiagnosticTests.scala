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

    test("native warnings cannot replace the error code or its context"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "src/Warning.scala:2:3: warning: NODAL-ANALOG-LINT-001: suspicious contribution " +
          "[semantic-path=Warning.branch] [hierarchy-path=Warning] " +
          "[index-path=[lane=0]] [source-range=src/Warning.scala:2:3-2:9]\r\n" +
          "src/Error.scala:4:5: error: NODAL-AMS-BRIDGE-001: explicit bridge required " +
          "[semantic-path=Error.sample] [hierarchy-path=Error] " +
          "[index-path=[lane=1]] [source-range=src/Error.scala:4:5-4:20]\r\n",
        1
      )

      assert(diagnostic.code == "NODAL-AMS-BRIDGE-001")
      assert(diagnostic.message == "explicit bridge required")
      assert(diagnostic.semanticPath.contains("Error.sample"))
      assert(diagnostic.hierarchyPath.contains("Error"))
      assert(diagnostic.indexPath.contains("[lane=1]"))
      assert(diagnostic.sourceRange.contains("src/Error.scala:4:5-4:20"))

    test("uncoded parser errors take precedence over coded warnings and notes"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "warning: NODAL-ANALOG-LINT-001: backend warning [semantic-path=Top.warning]\n" +
          "note: NODAL-ANALOG-LINT-001: error: preceding warning [hierarchy-path=Other]\n" +
          "/tmp/nodal-scala-mlir-123456/input.mlir:1:4: error: expected operation name\n",
        1
      )

      assert(diagnostic.code == "NODAL-DIAGNOSTIC-PARSER-001")
      assert(diagnostic.message.contains("<bridge-input>:1:4: error: expected operation name"))
      assert(diagnostic.semanticPath.isEmpty)
      assert(diagnostic.hierarchyPath.isEmpty)
      assert(diagnostic.indexPath.isEmpty)
      assert(diagnostic.sourceRange.isEmpty)

    test("multiple native errors never combine diagnostic contexts"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "error: NODAL-AMS-BRIDGE-001: explicit bridge required [semantic-path=Top.first]\n" +
          "error: NODAL-INTERFACE-ROLE-002: incompatible roles " +
          "[semantic-path=Other.second] [hierarchy-path=Other] " +
          "[index-path=[lane=2]] [source-range=src/Other.scala:8:1-8:9]\n",
        1
      )

      assert(diagnostic.code == "NODAL-AMS-BRIDGE-001")
      assert(diagnostic.message == "explicit bridge required")
      assert(diagnostic.semanticPath.contains("Top.first"))
      assert(diagnostic.hierarchyPath.isEmpty)
      assert(diagnostic.indexPath.isEmpty)
      assert(diagnostic.sourceRange.isEmpty)

    test("legacy stable codes and context survive without native severity prefixes"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "NODAL-INTERFACE-ROLE-002: incompatible roles [semantic-path=Top.link] " +
          "[hierarchy-path=Top] [index-path=lane=1] " +
          "[source-range=src/Top.scala:12:7-12:24]\n",
        1
      )

      assert(diagnostic.code == "NODAL-INTERFACE-ROLE-002")
      assert(diagnostic.message == "incompatible roles")
      assert(diagnostic.semanticPath.contains("Top.link"))
      assert(diagnostic.hierarchyPath.contains("Top"))
      assert(diagnostic.indexPath.contains("lane=1"))
      assert(diagnostic.sourceRange.contains("src/Top.scala:12:7-12:24"))

    test("multiple legacy diagnostics never combine diagnostic contexts"):
      val diagnostic = NativeDiagnosticMapper.classify(
        "NODAL-AMS-BRIDGE-001: explicit bridge required\n" +
          "NODAL-INTERFACE-ROLE-002: incompatible roles " +
          "[semantic-path=Other.second] [hierarchy-path=Other] " +
          "[index-path=[lane=2]] [source-range=src/Other.scala:8:1-8:9]\n",
        1
      )

      assert(diagnostic.code == "NODAL-AMS-BRIDGE-001")
      assert(diagnostic.message == "explicit bridge required")
      assert(diagnostic.semanticPath.isEmpty)
      assert(diagnostic.hierarchyPath.isEmpty)
      assert(diagnostic.indexPath.isEmpty)
      assert(diagnostic.sourceRange.isEmpty)

    test("warning-only and empty abnormal exits remain external failures"):
      for output <- Vector(
          "warning: NODAL-ANALOG-LINT-001: backend warning [semantic-path=Top.warning]\n" +
            "note: NODAL-AMS-BRIDGE-001: expected operation [hierarchy-path=Top]\n",
          ""
        )
      do
        val diagnostic = NativeDiagnosticMapper.classify(output, 7)

        assert(diagnostic.code == "NODAL-DIAGNOSTIC-EXTERNAL-001")
        assert(diagnostic.message == "native compiler exited with status 7")
        assert(diagnostic.semanticPath.isEmpty)
        assert(diagnostic.hierarchyPath.isEmpty)
        assert(diagnostic.indexPath.isEmpty)
        assert(diagnostic.sourceRange.isEmpty)

    test("parser and pass failures receive distinct fallback families"):
      val parser = NativeDiagnosticMapper.classify(
        "/tmp/nodal-scala-mlir-123456/input.mlir:1:4: error: expected operation name\n",
        1
      )
      val pass = NativeDiagnosticMapper.classify(
        "error: failed to run pass pipeline 'nodal-gate-default'\n",
        1
      )

      assert(parser.code == "NODAL-DIAGNOSTIC-PARSER-001")
      assert(parser.message.contains("<bridge-input>:1:4"))
      assert(!parser.message.contains("nodal-scala-mlir-123456"))
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
              "printf '%s\\n' 'warning: NODAL-ANALOG-LINT-001: suspicious contribution [semantic-path=Top.warning]' 'error: NODAL-AMS-BRIDGE-001: explicit bridge required [semantic-path=Top.sample] [source-range=src/Top.scala:20:3-20:28]' >&2; exit 9",
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
        for output <- Vector(
            "plain failure\n",
            "",
            "warning: NODAL-ANALOG-LINT-001: backend warning [semantic-path=Top.warning]\n"
          )
        do
          val result = NativeCompilerClient.run(
            emptyDocument,
            NativeCompilerRequest(
              executable = Path.of("/bin/sh"),
              arguments = Vector("-c", "printf '%s' \"$1\" >&2; exit 7", "nodal", output),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(5)
            )
          ).asInstanceOf[NativeCompilerFailure]

          assert(result.diagnostic.code == "NODAL-BRIDGE-PROCESS-007")
          assert(result.exitCode.contains(7))
          assert(result.standardError == output)
      finally remove(directory)
