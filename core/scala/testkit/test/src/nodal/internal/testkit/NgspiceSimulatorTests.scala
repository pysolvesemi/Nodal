package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.internal.integrations.*
import utest.*

object NgspiceSimulatorTests extends TestSuite:
  private val Version = "test-ngspice 47"
  private val CompilerVersion = "test-openvaf 1"
  private val Model =
    "module scalar(p,n); inout p,n; electrical p,n; analog I(p,n) <+ V(p,n); endmodule\n"
  private val CompilerArguments = Vector(
    "--target",
    "x86_64-unknown-linux",
    "--target_cpu",
    "generic",
    "--interface",
    "OSDI",
    "-O",
    "3"
  )
  private val DcRaw = """Title: nodal finite bench
Plotname: DC transfer characteristic
Flags: real
No. Variables: 3
No. Points: 3
Variables:
0 v(v-sweep) voltage
1 v(p) voltage
2 i(vdrive) current
Values:
0 0
0
0
1 0.5
0.5
-0.0005
2 1
1
-0.001
"""
  private val AcRaw = """Title: nodal finite bench
Plotname: AC Analysis
Flags: complex
No. Variables: 3
No. Points: 3
Variables:
0 frequency frequency grid=3
1 v(p) voltage
2 i(vdrive) current
Values:
0 1,1e-310
1,0
-0.001,-0.001
1 2,1e-310
1,0
-0.001,-0.002
2 3,1e-310
1,0
-0.001,-0.003
"""
  private val TransientRaw = """Title: nodal finite bench
Plotname: Transient Analysis
Flags: real
No. Variables: 2
No. Points: 4
Variables:
0 time time
1 v(p) voltage
Values:
0 0.0001
0
1 0.001
0.1
2 0.002
0.2
3 0.003
0.3
"""

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

  private def result(
      request: ToolProcessRequest,
      code: Option[Int] = Some(0),
      stdout: String = "",
      stderr: String = "",
      failure: Option[ToolProcessFailure] = None
  ): ToolProcessResult =
    ToolProcessResult(
      request.executable.toString +: request.arguments,
      stdout,
      stderr,
      code,
      failure,
      1,
      failure.map(_.toString),
      IArray.unsafeFromArray(bytes(stdout).toArray),
      IArray.unsafeFromArray(bytes(stderr).toArray)
    )

  private def emit(request: ToolProcessRequest, raw: String = DcRaw): ToolProcessResult =
    Files.writeString(
      request.workingDirectory.resolve("results.raw"),
      raw,
      StandardCharsets.US_ASCII
    )
    result(request)

  private final class Fake(
      runAction: ToolProcessRequest => ToolProcessResult = request => emit(request),
      versionAction: ToolProcessRequest => ToolProcessResult =
        request => result(request, stdout = Version + "\n")
  ) extends ToolProcessExecutor:
    var requests = Vector.empty[ToolProcessRequest]
    def run(request: ToolProcessRequest): ToolProcessResult =
      requests :+= request
      if request.arguments == Vector("--version") then versionAction(request)
      else runAction(request)

  private final case class Fixture(
      root: Path,
      request: NgspiceSimulateRequest,
      profile: NgspiceProfile
  ):
    def run(
        fake: Fake = new Fake,
        selected: NgspiceSimulateRequest = request,
        profiles: Vector[NgspiceProfile] = Vector(profile)
    ): NgspiceSimulationResult =
      new NgspiceSimulator(profiles, fake).simulate(selected)

  private def fixture(): Fixture =
    val parent = Path.of(".work/increment49/adapter-tests").toAbsolutePath
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "fixture-")
    val executable = root.resolve("fake ngspice")
    Files.writeString(executable, "#!/bin/sh\nexit 99\n", StandardCharsets.US_ASCII)
    assert(executable.toFile.setExecutable(true))
    val executableHash = digest(Files.readAllBytes(executable).toVector)
    val compiler = new ToolProcessExecutor:
      def run(request: ToolProcessRequest): ToolProcessResult =
        if request.arguments == Vector("--version") then
          result(request, stdout = CompilerVersion + "\n")
        else
          Files.write(request.workingDirectory.resolve("model.osdi"), elf)
          result(request)
    val compiled = new OpenVafCompiler(
      Vector(OpenVafProfile(
        "compiler",
        CompilerVersion,
        executableHash,
        "linux-x86_64",
        CompilerArguments,
        "c" * 64
      )),
      compiler
    ).compile(
      OpenVafCompileRequest(
        bytes(Model),
        "model.va",
        executable,
        root,
        "compiler",
        provenance = OpenVafProvenance(Some("source-revision"), Some("d" * 64))
      )
    )
    assert(compiled.outcome == OpenVafOutcome.Compiled)
    val request = NgspiceSimulateRequest(
      executable,
      root,
      "ngspice-test",
      compiled,
      NgspiceModuleBinding(
        "scalar",
        Vector(NgspiceTerminal.Driven, NgspiceTerminal.Ground),
        Vector("R" -> 1000.0)
      ),
      NgspiceAnalysis.DcVoltageSweep(0, 1, 0.5)
    )
    Fixture(
      root,
      request,
      NgspiceProfile("ngspice-test", Version, executableHash, "linux-x86_64", "e" * 64)
    )

  val tests = Tests:
    test("fresh verified model-dependent run retains complete evidence"):
      val f = fixture()
      val fake = new Fake
      val actual = f.run(fake)
      assert(actual.outcome == NgspiceOutcome.Simulated, actual.plot.exists(_.rows.size == 3))
      assert(
        actual.identity.requestSha256.nonEmpty,
        actual.identity.modelSha256 == digest(bytes(Model))
      )
      assert(
        fake.requests.size == 2,
        fake.requests.last.arguments == Vector("-b", "-r", "results.raw", "bench.cir")
      )
      fake.requests.foreach: request =>
        assert(
          !request.inheritEnvironment,
          request.containment == ToolProcessContainment.RequireProcessGroup
        )
        assert(request.environment("HOME") == request.workingDirectory.toString)
        assert(request.environment("SPICE_SCRIPTS") == request.workingDirectory.toString)
        assert(request.environment.keySet ==
          Set("HOME", "SPICE_SCRIPTS", "PATH", "LANG", "LC_ALL", "TZ"))
      val directory = actual.evidenceDirectory.get
      assert(Files.readString(directory.resolve("spinit")) ==
        "set stricterror\nosdi model.osdi\nset filetype=ascii\nset noaskquit\n")
      assert(Files.readAllBytes(directory.resolve("model.osdi")).toVector == elf.toVector)
      assert(Files.readString(directory.resolve("results.raw")) == DcRaw)
      val json = Files.readString(directory.resolve("receipt.json"))
      assert(
        json.contains("source-revision"),
        json.contains("normalizedMlirSha256"),
        json.contains("executedCommand")
      )
      assert(json.contains("model.osdi"), json.contains(actual.raw.get.sha256))

    test("fresh directories have stable identities without reusing results"):
      val f = fixture()
      val fake = new Fake
      val first = f.run(fake)
      val second = f.run(fake)
      assert(first.outcome == NgspiceOutcome.Simulated, second.outcome == NgspiceOutcome.Simulated)
      assert(first.evidenceDirectory != second.evidenceDirectory, fake.requests.size == 4)
      assert(first.identity.requestSha256 == second.identity.requestSha256)
      val changed = f.run(selected = f.request.copy(options = f.request.options.copy(temp = 28)))
      assert(changed.outcome == NgspiceOutcome.Simulated)
      assert(changed.identity.requestSha256 != first.identity.requestSha256)
      val timeout = f.run(selected = f.request.copy(runTimeout = Duration.ofSeconds(20)))
      assert(timeout.identity.requestSha256 != first.identity.requestSha256)

    test("request and generated bench validation precede any process"):
      val f = fixture()
      val fake = new Fake
      Vector(
        f.request.copy(executable = Path.of("relative")),
        f.request.copy(evidenceDirectory = Path.of("relative")),
        f.request.copy(versionTimeout = Duration.ZERO),
        f.request.copy(runTimeout = Duration.ofHours(2)),
        f.request.copy(model = f.request.model.copy(moduleName = "scalar\n.control")),
        f.request.copy(analysis = NgspiceAnalysis.DcVoltageSweep(0, 1, 0))
      ).foreach: request =>
        assert(f.run(fake, request).outcome == NgspiceOutcome.InvalidRequest)
      assert(fake.requests.isEmpty)

    test("missing failed and incomplete compiler receipts cannot simulate"):
      val f = fixture()
      val fake = new Fake
      val compiled = f.request.compiled
      Vector(
        compiled.copy(outcome = OpenVafOutcome.CompilerError),
        compiled.copy(artifact = None),
        compiled.copy(compilation = None),
        compiled.copy(versionProbe = None),
        compiled.copy(identity = compiled.identity.copy(requestSha256 = None))
      ).foreach: incomplete =>
        assert(f.run(fake, f.request.copy(compiled = incomplete)).outcome ==
          NgspiceOutcome.InvalidCompileArtifact)
      assert(fake.requests.isEmpty)

    test("artifact size digest symlink missing and oversize are independently rejected"):
      val f = fixture()
      val fake = new Fake
      val compiled = f.request.compiled
      val artifact = compiled.artifact.get
      val alias = f.root.resolve("alias.osdi")
      Files.createSymbolicLink(alias, artifact.path)
      Vector(
        artifact.copy(path = f.root.resolve("missing.osdi")),
        artifact.copy(path = alias),
        artifact.copy(sha256 = "0" * 64),
        artifact.copy(size = artifact.size + 1),
        artifact.copy(size = 64L * 1024 * 1024 + 1)
      ).foreach: invalid =>
        assert(f.run(
          fake,
          f.request.copy(compiled = compiled.copy(artifact = Some(invalid)))
        ).outcome == NgspiceOutcome.InvalidCompileArtifact)
      Files.write(artifact.path, Array.fill[Byte](64)(1))
      assert(f.run(fake).outcome == NgspiceOutcome.InvalidCompileArtifact, fake.requests.isEmpty)

    test("tool availability pin profile and version mismatches are distinct"):
      val f = fixture()
      val fake = new Fake
      assert(f.run(fake, profiles = Vector.empty).outcome == NgspiceOutcome.UnsupportedProfile)
      assert(f.run(fake, profiles = Vector(f.profile, f.profile)).outcome ==
        NgspiceOutcome.UnsupportedProfile)
      assert(f.run(fake, profiles = Vector(f.profile.copy(platform = "other"))).outcome ==
        NgspiceOutcome.UnsupportedProfile)
      assert(f.run(
        fake,
        selected = f.request.copy(executable = f.root.resolve("missing"))
      ).outcome == NgspiceOutcome.ToolUnavailable)
      assert(f.run(fake, profiles = Vector(f.profile.copy(executableSha256 = "0" * 64))).outcome ==
        NgspiceOutcome.ProfileMismatch)
      assert(fake.requests.isEmpty)
      val wrong = new Fake(versionAction = request => result(request, stdout = "different"))
      assert(f.run(wrong).outcome == NgspiceOutcome.ProfileMismatch, wrong.requests.size == 1)
      val stderr = new Fake(versionAction =
        request => result(request, stdout = Version, stderr = "unexpected")
      )
      assert(f.run(stderr).outcome == NgspiceOutcome.ProfileMismatch, stderr.requests.size == 1)

    test("process failures remain typed at both version and simulation phases"):
      val f = fixture()
      Vector(
        ToolProcessFailure.InvalidRequest -> NgspiceOutcome.InvalidRequest,
        ToolProcessFailure.ToolUnavailable -> NgspiceOutcome.ToolUnavailable,
        ToolProcessFailure.LaunchFailed -> NgspiceOutcome.LaunchFailure,
        ToolProcessFailure.Timeout -> NgspiceOutcome.Timeout,
        ToolProcessFailure.Interrupted -> NgspiceOutcome.Interrupted,
        ToolProcessFailure.OutputLimit -> NgspiceOutcome.CaptureFailure,
        ToolProcessFailure.CaptureFailed -> NgspiceOutcome.CaptureFailure
      ).foreach: (failure, outcome) =>
        val action = (request: ToolProcessRequest) => result(request, None, failure = Some(failure))
        assert(f.run(new Fake(runAction = action)).outcome == outcome)
        val version = new Fake(versionAction = action)
        assert(f.run(version).outcome == outcome, version.requests.size == 1)

    test("finite diagnostics override exit zero and valid looking raw"):
      val f = fixture()
      Vector(
        "Error opening osdi lib model.osdi" -> NgspiceOutcome.LoadFailure,
        "Unable to find definition of model dut" -> NgspiceOutcome.LoadFailure,
        "Error on line 3 or its substitute:" -> NgspiceOutcome.DeckError,
        "doAnalyses: TRAN: Timestep too small" -> NgspiceOutcome.Nonconvergence,
        "Warning: singular matrix" -> NgspiceOutcome.Nonconvergence,
        "Error: a new unknown error" -> NgspiceOutcome.SimulatorError,
        "unrecognized diagnostic" -> NgspiceOutcome.SimulatorError
      ).foreach: (message, expected) =>
        val fake = new Fake(runAction = request =>
          emit(request)
          result(request, stderr = message))
        val actual = f.run(fake)
        assert(actual.outcome == expected, actual.plot.isEmpty)
      val stdout = new Fake(runAction = request =>
        emit(request)
        result(request, stdout = "Warning: unknown condition"))
      assert(f.run(stdout).outcome == NgspiceOutcome.SimulatorError)
      assert(f.run(new Fake(runAction = request => result(request, Some(17)))).outcome ==
        NgspiceOutcome.SimulatorError)

    test("no raw stale raw malformed raw binary and wrong columns fail closed"):
      val f = fixture()
      assert(f.run(new Fake(runAction = request => result(request))).outcome ==
        NgspiceOutcome.InvalidRaw)
      val stale = new Fake(versionAction = request =>
        emit(request)
        result(request, stdout = Version))
      assert(f.run(stale).outcome == NgspiceOutcome.InvalidRaw, stale.requests.size == 1)
      Vector(
        "",
        "not a raw plot",
        DcRaw.replace("i(vdrive)", "i(other)"),
        DcRaw.replace("2 1\n", "2 0.9\n"),
        DcRaw + DcRaw,
        DcRaw.replace("Values:", "Binary:")
      ).foreach: raw =>
        assert(f.run(new Fake(runAction = request => emit(request, raw))).outcome ==
          NgspiceOutcome.InvalidRaw)
      val symbolic = new Fake(runAction = request =>
        Files.createSymbolicLink(
          request.workingDirectory.resolve("results.raw"),
          f.request.compiled.artifact.get.path
        )
        result(request))
      assert(f.run(symbolic).outcome == NgspiceOutcome.InvalidRaw)
      val huge = new Fake(runAction = request =>
        val file =
          new java.io.RandomAccessFile(request.workingDirectory.resolve("results.raw").toFile, "rw")
        try file.setLength(16L * 1024 * 1024 + 1)
        finally file.close()
        result(request))
      assert(f.run(huge).outcome == NgspiceOutcome.InvalidRaw)

    test("AC grids and imaginary frequency slot preserve actual protocol"):
      val f = fixture()
      val request = f.request.copy(analysis = NgspiceAnalysis.AcLinearSweep(3, 1, 3))
      val actual = f.run(new Fake(runAction = request => emit(request, AcRaw)), request)
      assert(
        actual.outcome == NgspiceOutcome.Simulated,
        actual.plot.get.rows.head.head.imaginary == 1e-310
      )
      val wrong = AcRaw.replace("1 2,", "1 2.1,")
      assert(f.run(new Fake(runAction = request => emit(request, wrong)), request).outcome ==
        NgspiceOutcome.InvalidRaw)
      val decade = AcRaw.replace("1 2,", "1 10,").replace("2 3,", "2 100,")
      assert(f.run(
        new Fake(runAction = request => emit(request, decade)),
        f.request.copy(analysis = NgspiceAnalysis.AcDecadeSweep(1, 1, 100))
      ).outcome == NgspiceOutcome.Simulated)

    test("high offset DC and AC grids reject repeated reordered and displaced axes"):
      val f = fixture()
      val start = 1e10
      val dc = f.request.copy(analysis = NgspiceAnalysis.DcVoltageSweep(start, start + 2, 1))
      val ac = f.request.copy(analysis = NgspiceAnalysis.AcLinearSweep(3, start, start + 2))
      def shifted(raw: String, axes: Vector[Double], complex: Boolean): String =
        val point = if complex then "(?m)^([012]) [^,\\n]+,".r else "(?m)^([012]) [0-9][^\\n]*$".r
        point.replaceAllIn(
          raw,
          matched =>
            s"${matched.group(1)} ${axes(matched.group(1).toInt)}${if complex then "," else ""}"
        )
      Vector(false -> dc, true -> ac).foreach: (complex, request) =>
        val template = if complex then AcRaw else DcRaw
        val valid = shifted(template, Vector(start, start + 1, start + 2), complex)
        assert(f.run(new Fake(runAction = request => emit(request, valid)), request).outcome ==
          NgspiceOutcome.Simulated)
        Vector(
          Vector(start, start, start),
          Vector(start, start + 2, start + 1),
          Vector(start, start + 1.5, start + 2)
        ).foreach: axes =>
          val invalid = shifted(template, axes, complex)
          assert(f.run(new Fake(runAction = request => emit(request, invalid)), request).outcome ==
            NgspiceOutcome.InvalidRaw)

    test("adaptive transient grid must increase and reach the requested stop"):
      val f = fixture()
      val request = f.request.copy(analysis =
        NgspiceAnalysis.TransientCurrent(
          NgspiceCurrentStimulus.Ramp(0.001, 0, 0.001),
          0.001,
          0.003
        )
      )
      assert(f.run(new Fake(runAction = request => emit(request, TransientRaw)), request).outcome ==
        NgspiceOutcome.Simulated)
      Vector(
        TransientRaw.replace("3 0.003", "3 0.0029"),
        TransientRaw.replace("2 0.002", "2 0.001"),
        TransientRaw.replace("0 0.0001", "0 0.0011")
      ).foreach: wrong =>
        assert(f.run(new Fake(runAction = request => emit(request, wrong)), request).outcome ==
          NgspiceOutcome.InvalidRaw)

    test("changed executable model deck and startup cannot produce accepted output"):
      Vector("model.osdi", "bench.cir", "spinit").foreach: filename =>
        val f = fixture()
        val fake = new Fake(runAction = request =>
          emit(request)
          Files.writeString(request.workingDirectory.resolve(filename), "tampered")
          result(request))
        assert(f.run(fake).outcome == NgspiceOutcome.InvalidCompileArtifact)
      val f = fixture()
      val fake = new Fake(versionAction = request =>
        Files.writeString(request.executable, "changed")
        result(request, stdout = Version))
      assert(f.run(fake).outcome == NgspiceOutcome.ProfileMismatch, fake.requests.size == 1)

    test("raw process bytes remain verbatim and evidence failures remove success"):
      val f = fixture()
      val rawBytes = Array[Byte](0, -128, 65, 10)
      val fake = new Fake(runAction = request =>
        emit(request)
        result(request).copy(standardOutputBytes = IArray.unsafeFromArray(rawBytes)))
      val actual = f.run(fake)
      assert(actual.outcome == NgspiceOutcome.Simulated)
      assert(
        Files.readAllBytes(actual.evidenceDirectory.get.resolve("simulate.stdout.log")).toVector ==
          rawBytes.toVector
      )
      val broken = new Fake(runAction = request =>
        emit(request)
        Files.createDirectory(request.workingDirectory.resolve("receipt.json"))
        result(request))
      val failure = f.run(broken)
      assert(failure.outcome == NgspiceOutcome.EvidenceFailure, failure.plot.isEmpty)
