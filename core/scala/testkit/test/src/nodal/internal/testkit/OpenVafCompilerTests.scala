package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Duration
import java.util.Base64

import nodal.internal.integrations.*
import utest.*

object OpenVafCompilerTests extends TestSuite:
  private val Arguments = Vector(
    "--target",
    "x86_64-unknown-linux",
    "--target_cpu",
    "generic",
    "--interface",
    "OSDI",
    "-O",
    "3"
  )
  private val Model =
    "`include \"constants.vams\"\n`include \"disciplines.vams\"\nmodule scalar(p,n);\ninout p,n;\nelectrical p,n;\nanalog I(p,n) <+ V(p,n);\nendmodule\n"
  private val Version = "test-openvaf 1"

  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector
  private def digest(value: Vector[Byte]): String = OpenVafDiagnosticMapper.sha256(value)
  private def elf: Array[Byte] =
    val data = new Array[Byte](64)
    Vector(
      0 -> 127,
      1 -> 69,
      2 -> 76,
      3 -> 70,
      4 -> 2,
      5 -> 1,
      6 -> 1,
      16 -> 3,
      18 -> 62,
      20 -> 1,
      52 -> 64
    ).foreach((index, value) => data(index) = value.toByte)
    data
  private def compiled(request: ToolProcessRequest): ToolProcessResult =
    Files.write(request.workingDirectory.resolve("model.osdi"), elf)
    processResult(request)
  private def processResult(
      request: ToolProcessRequest,
      code: Option[Int] = Some(0),
      stdout: String = "",
      stderr: String = "",
      failure: Option[ToolProcessFailure] = None
  ): ToolProcessResult = ToolProcessResult(
    request.executable.toString +: request.arguments,
    stdout,
    stderr,
    code,
    failure,
    1,
    failure.map(_.toString),
    standardOutputBytes = IArray.unsafeFromArray(stdout.getBytes(StandardCharsets.UTF_8)),
    standardErrorBytes = IArray.unsafeFromArray(stderr.getBytes(StandardCharsets.UTF_8))
  )

  private final class Fake(
      compileAction: ToolProcessRequest => ToolProcessResult = compiled,
      versionAction: ToolProcessRequest => ToolProcessResult =
        r => processResult(r, stdout = Version + "\n")
  ) extends ToolProcessExecutor:
    var requests = Vector.empty[ToolProcessRequest]
    def run(request: ToolProcessRequest): ToolProcessResult =
      requests :+= request
      if request.arguments == Vector("--version") then versionAction(request)
      else compileAction(request)

  private final case class Fixture(
      root: Path,
      request: OpenVafCompileRequest,
      profile: OpenVafProfile
  ):
    def run(
        fake: Fake = new Fake,
        selected: OpenVafCompileRequest = request,
        profiles: Vector[OpenVafProfile] = Vector(profile)
    ): OpenVafCompileResult =
      new OpenVafCompiler(profiles, fake).compile(selected)

  private def fixture(model: String = Model): Fixture =
    val parent = Path.of(".work/increment48/adapter-tests").toAbsolutePath
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "fixture-")
    val executable = root.resolve("fake openvaf")
    Files.writeString(executable, "#!/bin/sh\nexit 99\n", StandardCharsets.UTF_8)
    assert(executable.toFile.setExecutable(true))
    val profile = OpenVafProfile(
      "fixture",
      Version,
      digest(Files.readAllBytes(executable).toVector),
      "linux-x86_64",
      Arguments,
      "catalog-fixture"
    )
    Fixture(
      root,
      OpenVafCompileRequest(bytes(model), "model with spaces.va", executable, root, profile.id),
      profile
    )

  private def marker(
      semantic: String = "model.r",
      path: String = "src/路径 with spaces.scala",
      positions: String = "3|2|3|25"
  ): String =
    def encoded(value: String): String =
      Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))
    s"// nodal-source-v1: ${encoded(semantic)}|${encoded(path)}|$positions"

  val tests: Tests = Tests:
    test("exact bytes controlled invocation fresh output and repeat identity"):
      val f = fixture()
      val fake = new Fake
      val first = f.run(fake)
      val second = f.run()
      assert(first.outcome == OpenVafOutcome.Compiled, first.artifact.nonEmpty)
      assert(first.identity.requestSha256 == second.identity.requestSha256)
      assert(first.evidenceDirectory != second.evidenceDirectory)
      assert(first.artifact.map(_.sha256) == second.artifact.map(_.sha256))
      assert(Files.readAllBytes(
        first.evidenceDirectory.get.resolve(f.request.logicalFilename)
      ).toVector == f.request.modelBytes)
      assert(fake.requests.size == 2, fake.requests.forall(!_.inheritEnvironment))
      assert(fake.requests.forall(_.containment == ToolProcessContainment.RequireProcessGroup))
      assert(fake.requests.last.arguments ==
        Vector(f.request.logicalFilename) ++ Arguments ++ Vector("-o", "model.osdi"))
      assert(fake.requests.last.environment("LANG") == "C")
      assert(!fake.requests.last.environment.contains("LD_LIBRARY_PATH"))
      assert(Files.readString(
        first.evidenceDirectory.get.resolve("receipt.json")
      ).contains(first.identity.modelSha256))
      assert(Files.readString(first.evidenceDirectory.get.resolve("version.stdout.log")) ==
        Version + "\n")

    test("pins and unknown profiles fail before execution"):
      val f = fixture()
      val fake = new Fake
      assert(f.run(fake, f.request.copy(profileId = "unknown")).outcome ==
        OpenVafOutcome.UnsupportedProfile)
      assert(f.run(fake, f.request.copy(languageProfile = "verilog-ams")).outcome ==
        OpenVafOutcome.UnsupportedProfile)
      assert(f.run(fake, profiles = Vector(f.profile.copy(executableSha256 = "wrong"))).outcome ==
        OpenVafOutcome.ProfileMismatch)
      assert(f.run(
        fake,
        profiles = Vector(f.profile.copy(compileArguments = Vector("--batch")))
      ).outcome == OpenVafOutcome.UnsupportedProfile)
      assert(fake.requests.isEmpty)

    test("missing executable and changed version are distinct"):
      val f = fixture()
      val missing = f.run(selected = f.request.copy(executable = f.root.resolve("missing")))
      assert(missing.outcome == OpenVafOutcome.ToolUnavailable)
      val fake = new Fake(versionAction = r => processResult(r, stdout = "other tool\n"))
      val mismatch = f.run(fake)
      assert(mismatch.outcome == OpenVafOutcome.ProfileMismatch, fake.requests.size == 1)
      assert(mismatch.identity.version.contains("other tool"))

    test("validation rejects unsafe filenames and unbounded timeouts"):
      val f = fixture()
      val fake = new Fake
      Vector(
        f.request.copy(logicalFilename = "../model.va"),
        f.request.copy(logicalFilename = "-Dmacro.va"),
        f.request.copy(logicalFilename = "constants.vams"),
        f.request.copy(logicalFilename = "receipt.json"),
        f.request.copy(logicalFilename = "model.osdi"),
        f.request.copy(logicalFilename = "a\nb.va"),
        f.request.copy(executable = Path.of("relative")),
        f.request.copy(evidenceDirectory = Path.of("relative")),
        f.request.copy(compileTimeout = Duration.ZERO),
        f.request.copy(versionTimeout = Duration.ofNanos(-1)),
        f.request.copy(compileTimeout = Duration.ofHours(2))
      ).foreach(request => assert(f.run(fake, request).outcome == OpenVafOutcome.InvalidRequest))
      assert(fake.requests.isEmpty)
      assert(f.run(selected = f.request.copy(logicalFilename = "historical.va.direct")).outcome ==
        OpenVafOutcome.Compiled)

    test("external includes and directives cannot enter the compile profile"):
      val bad = Vector(
        "`include \"/tmp/unpinned.vams\"",
        "`include \"../constants.vams\"",
        "`include `MACRO",
        "`define FILE \"arbitrary\"",
        "`ifdef OTHER"
      )
      bad.foreach: input =>
        val f = fixture(input + "\n" + Model)
        val fake = new Fake
        assert(f.run(fake).outcome == OpenVafOutcome.InvalidRequest, fake.requests.isEmpty)
      val f = fixture("// `include \"ignored\"\n/* `define ignored */\n" + Model)
      assert(f.run().outcome == OpenVafOutcome.Compiled)

    test("invalid UTF8 is malformed but ordinary exit65 is not inferred unsupported"):
      val f = fixture()
      assert(f.run(selected = f.request.copy(modelBytes = Vector(0xc3.toByte, 0x28))).outcome ==
        OpenVafOutcome.MalformedModel)
      Vector(65, 1, 17).foreach: code =>
        val fake = new Fake(compileAction =
          r => processResult(r, Some(code), stderr = "error: unexpected token '('\n")
        )
        val result = f.run(fake)
        assert(result.outcome == OpenVafOutcome.CompilerError, result.artifact.isEmpty)
        assert(result.compilation.flatMap(_.exitCode).contains(code))

    test("launch timeout interruption and capture failure stay distinct"):
      val f = fixture()
      Vector(
        ToolProcessFailure.LaunchFailed -> OpenVafOutcome.LaunchFailure,
        ToolProcessFailure.Timeout -> OpenVafOutcome.Timeout,
        ToolProcessFailure.Interrupted -> OpenVafOutcome.Interrupted,
        ToolProcessFailure.OutputLimit -> OpenVafOutcome.CaptureFailure,
        ToolProcessFailure.CaptureFailed -> OpenVafOutcome.CaptureFailure
      ).foreach: (failure, outcome) =>
        val fake = new Fake(compileAction = r => processResult(r, None, failure = Some(failure)))
        assert(f.run(fake).outcome == outcome)
      val version = new Fake(versionAction =
        r => processResult(r, None, failure = Some(ToolProcessFailure.Timeout))
      )
      assert(f.run(version).outcome == OpenVafOutcome.Timeout, version.requests.size == 1)

    test("panic signal and LLVM crash override historical observations"):
      val f = fixture()
      val observation = OpenVafObservation(
        "known",
        digest(f.request.modelBytes),
        f.profile.executableSha256,
        Version,
        "compiled",
        "historical invocation"
      )
      val profile = f.profile.copy(observations = Vector(observation))
      Vector(
        (101, ""),
        (139, ""),
        (1, "thread 'main' panicked at compiler.rs:1"),
        (65, "LLVM ERROR: intrinsic missing")
      ).foreach: (code, stderr) =>
        val result = f.run(
          new Fake(compileAction = r => processResult(r, Some(code), stderr = stderr)),
          profiles = Vector(profile)
        )
        assert(result.outcome == OpenVafOutcome.CompilerCrash, result.artifact.isEmpty)
        assert(result.historicalObservations == Vector(observation))

    test("historical observations require exact model tool and version"):
      val f = fixture()
      val exact = OpenVafObservation(
        "exact",
        digest(f.request.modelBytes),
        f.profile.executableSha256,
        Version,
        "compiler-error",
        "historical default CPU invocation"
      )
      val profile = f.profile.copy(observations =
        Vector(
          exact,
          exact.copy(id = "wrong-model", modelSha256 = "wrong"),
          exact.copy(id = "wrong-tool", executableSha256 = "wrong"),
          exact.copy(id = "wrong-version", version = "wrong")
        )
      )
      val result = f.run(profiles = Vector(profile))
      assert(
        result.outcome == OpenVafOutcome.Compiled,
        result.historicalObservations == Vector(exact)
      )
      val changed = f.run(
        selected = f.request.copy(modelBytes = bytes(Model + "\n")),
        profiles = Vector(profile)
      )
      assert(
        changed.historicalObservations.isEmpty,
        changed.identity.requestSha256 != result.identity.requestSha256
      )

    test("zero exit requires a fresh regular ELF and never exposes failed output"):
      val f = fixture()
      val actions: Vector[ToolProcessRequest => ToolProcessResult] = Vector(
        r => processResult(r),
        r => {
          Files.write(r.workingDirectory.resolve("model.osdi"), Array.emptyByteArray);
          processResult(r)
        },
        r => {
          Files.writeString(r.workingDirectory.resolve("model.osdi"), "not ELF"); processResult(r)
        },
        r => { Files.createDirectory(r.workingDirectory.resolve("model.osdi")); processResult(r) },
        r => {
          Files.createSymbolicLink(r.workingDirectory.resolve("model.osdi"), f.request.executable);
          processResult(r)
        }
      )
      actions.foreach: action =>
        val result = f.run(new Fake(compileAction = action))
        assert(result.outcome == OpenVafOutcome.InvalidOutput, result.artifact.isEmpty)
      val failed = f.run(new Fake(compileAction = r => { compiled(r); processResult(r, Some(65)) }))
      assert(failed.outcome == OpenVafOutcome.CompilerError, failed.artifact.isEmpty)

    test("stale external output is not reused and probe-created output is rejected"):
      val f = fixture()
      Files.write(f.root.resolve("model.osdi"), elf)
      val result = f.run(new Fake(compileAction = r => processResult(r)))
      assert(result.outcome == OpenVafOutcome.InvalidOutput, result.artifact.isEmpty)
      val fake = new Fake(versionAction = r => { compiled(r); processResult(r, stdout = Version) })
      assert(f.run(fake).outcome == OpenVafOutcome.InvalidOutput, fake.requests.size == 1)

    test("tool and model mutation cannot publish an artifact"):
      val f = fixture()
      val changedTool = f.run(new Fake(compileAction = r =>
        Files.writeString(r.executable, "changed tool")
        compiled(r)))
      assert(changedTool.outcome == OpenVafOutcome.ProfileMismatch, changedTool.artifact.isEmpty)
      val g = fixture()
      val changedModel = g.run(new Fake(compileAction = r =>
        Files.writeString(r.workingDirectory.resolve(g.request.logicalFilename), "changed model")
        compiled(r)))
      assert(changedModel.outcome == OpenVafOutcome.InvalidOutput, changedModel.artifact.isEmpty)

    test("authored map binds exact bytes and maps only the next physical statement"):
      val source = marker() + "\n  parameter real r = 1.0;\n  parameter real other = 2.0;\n"
      val f = fixture(source)
      val request = f.request.copy(sourceMap = Some(OpenVafSourceMapBinding(digest(bytes(source)))))
      val fake = new Fake(compileAction =
        r =>
          processResult(
            r,
            Some(65),
            stderr =
              s"error: bad parameter\n --> ${r.workingDirectory.resolve(request.logicalFilename)}:2:4\n" +
                s"error: unrelated line\n --> ${request.logicalFilename}:3:4\n" +
                "error: unrelated input\n --> input.mlir:2:4\n" +
                s"error: out of line\n --> ${request.logicalFilename}:2:999\n"
          )
      )
      val result = f.run(fake, request)
      assert(result.outcome == OpenVafOutcome.CompilerError, result.diagnostics.size == 4)
      val mapped = result.diagnostics.head
      assert(
        mapped.generated.exists(_.column == 4),
        mapped.authored.exists(_.path == "src/路径 with spaces.scala")
      )
      assert(mapped.authored.exists(_.startColumn == 2))
      assert(result.diagnostics.tail.forall(_.authored.isEmpty))
      assert(result.sourceMapping.isInstanceOf[OpenVafSourceMapping.Valid])

    test("invalid mapping never replaces compiler outcome or raw logs"):
      val sources = Vector(
        marker().replace("v1:", "v2:") + "\nparameter real r = 1;\n",
        "// nodal-source-v1: !!!|!!!|1|1|1|1\nparameter real r = 1;\n",
        marker(positions = "3|2|2|1") + "\nparameter real r = 1;\n",
        marker() + "\n\nparameter real r = 1;\n",
        marker() + "\nparameter real r = 1; parameter real x = 2;\n",
        marker() + "\nparameter real r = 1;\n" + marker(positions = "4|1|4|3") +
          "\nparameter real x = 2;\n"
      )
      sources.foreach: source =>
        val f = fixture(source)
        val request =
          f.request.copy(sourceMap = Some(OpenVafSourceMapBinding(digest(bytes(source)))))
        val result = f.run(selected = request)
        assert(result.outcome == OpenVafOutcome.Compiled, result.artifact.nonEmpty)
        assert(result.sourceMapping.isInstanceOf[OpenVafSourceMapping.Invalid])
        val json = Files.readString(result.evidenceDirectory.get.resolve("receipt.json"))
        assert(json.contains("NODAL-OPENVAF-MAP-001"))
      val f = fixture()
      val mismatch =
        f.run(selected = f.request.copy(sourceMap = Some(OpenVafSourceMapBinding("wrong"))))
      assert(mismatch.outcome == OpenVafOutcome.Compiled)
      assert(mismatch.sourceMapping.isInstanceOf[OpenVafSourceMapping.Invalid])

    test("model profile options and mapping identity are never reused"):
      val f = fixture()
      val first = f.run()
      val renamed = f.run(selected = f.request.copy(logicalFilename = "renamed.va"))
      val remapped = f.run(selected =
        f.request.copy(sourceMap = Some(OpenVafSourceMapBinding(digest(f.request.modelBytes))))
      )
      val catalog = f.run(profiles = Vector(f.profile.copy(catalogSha256 = "changed catalog")))
      val timed = f.run(selected = f.request.copy(compileTimeout = Duration.ofSeconds(1)))
      val revision = f.run(selected =
        f.request.copy(provenance = OpenVafProvenance(sourceRevision = Some("abc")))
      )
      val mlir = f.run(selected =
        f.request.copy(provenance = OpenVafProvenance(normalizedMlirSha256 = Some("def")))
      )
      assert(Vector(
        first,
        renamed,
        remapped,
        catalog,
        timed,
        revision,
        mlir
      ).flatMap(_.identity.requestSha256).distinct.size == 7)
      val copied = f.root.resolve("another executable")
      Files.copy(f.request.executable, copied, StandardCopyOption.COPY_ATTRIBUTES)
      val relocated = f.run(selected = f.request.copy(executable = copied))
      assert(first.identity.requestSha256 != relocated.identity.requestSha256)

    test("identical semantic spans may annotate distinct emitted statements"):
      val source = marker() + "\nparameter real r = 1;\n" + marker() + "\nparameter real x = 2;\n"
      val parsed =
        OpenVafDiagnosticMapper.parse(bytes(source), OpenVafSourceMapBinding(digest(bytes(source))))
      parsed match
        case OpenVafSourceMapping.Valid(_, statements) =>
          assert(statements.map(_.generatedLine) == Vector(2, 4))
          assert(statements.map(_.authored).distinct.size == 1)
        case _ => assert(false)

    test("raw log bytes survive nonUTF8 output without rewriting"):
      val f = fixture()
      val raw = IArray[Byte](0xff.toByte, 0x00.toByte, 0x0a.toByte)
      val fake = new Fake(compileAction =
        r =>
          processResult(r, Some(65), stderr = "decoded replacement view").copy(standardErrorBytes =
            raw
          )
      )
      val result = f.run(fake)
      assert(result.outcome == OpenVafOutcome.CompilerError)
      assert(
        Files.readAllBytes(result.evidenceDirectory.get.resolve("compile.stderr.log")).toVector ==
          raw.toVector
      )

    test("crash phrases inside source excerpts are ordinary compiler errors"):
      val f = fixture()
      val transcript =
        "error: expected token\n  --> model with spaces.va:6:3\n  |\n6 | \"thread 'main' panicked at source\";\n7 | \"LLVM ERROR: test text\";\n"
      val result =
        f.run(new Fake(compileAction = r => processResult(r, Some(65), stderr = transcript)))
      assert(result.outcome == OpenVafOutcome.CompilerError)
      assert(result.compilation.exists(_.standardError == transcript))

    test("interruption retains evidence and preserves the caller interrupt flag"):
      val f = fixture()
      val fake = new Fake(compileAction = r =>
        Thread.currentThread().interrupt()
        processResult(
          r,
          None,
          stderr = "partial stderr",
          failure = Some(ToolProcessFailure.Interrupted)
        ))
      try
        val result = f.run(fake)
        assert(result.outcome == OpenVafOutcome.Interrupted, Thread.currentThread().isInterrupted)
        assert(Files.exists(result.evidenceDirectory.get.resolve("receipt.json")))
      finally Thread.interrupted()
