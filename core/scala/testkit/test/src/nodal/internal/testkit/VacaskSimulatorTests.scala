package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.internal.integrations.*
import utest.*

object VacaskSimulatorTests extends TestSuite:
  private val Version = "test-vacask 0.3.4"
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
Plotname: Operating Point
Flags: real
No. Variables: 3
No. Points: 3
Variables:
0 axis notype
1 n0 notype
2 vs0:flow(br) notype
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
      request.workingDirectory.resolve("result.raw"),
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
      if request.arguments == Vector("--help") then versionAction(request)
      else runAction(request)

  private final case class Fixture(
      root: Path,
      request: VacaskCircuitSimulateRequest,
      profile: VacaskProfile
  ):
    def run(
        fake: Fake = new Fake,
        selected: VacaskCircuitSimulateRequest = request,
        profiles: Vector[VacaskProfile] = Vector(profile)
    ): VacaskSimulationResult =
      new VacaskSimulator(profiles, fake).simulate(selected)

    def fold(
        fake: Fake = new Fake,
        selected: VacaskCircuitSimulateRequest = request
    ): VacaskStreamingSimulationResult[Double] =
      new VacaskSimulator(Vector(profile), fake).fold(selected)(_ => Right(0.0))((total, _, row) =>
        Right(total + row(1).real)
      )

  private def fixture(): Fixture =
    val parent = Path.of(".work/increment53/adapter-tests").toAbsolutePath
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "fixture-")
    val executable = root.resolve("fake vacask")
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
    val request = VacaskCircuitSimulateRequest(
      executable,
      root,
      "vacask-test",
      compiled,
      AnalogCircuitBench(
        AnalogCircuitBinding("scalar", Vector(AnalogNode.Node(0), AnalogNode.Ground)),
        Vector(AnalogNode.Node(0)),
        Vector(AnalogSource(
          AnalogSourceId(0),
          AnalogSourceKind.Voltage,
          AnalogNode.Node(0),
          AnalogNode.Ground
        )),
        AnalogCircuitAnalysis.DcSweep(AnalogSourceId(0), 0, 1, 0.5),
        Vector(AnalogProbe.Voltage(AnalogNode.Node(0)), AnalogProbe.Current(AnalogSourceId(0)))
      )
    )
    Fixture(
      root,
      request,
      VacaskProfile("vacask-test", Version, executableHash, "linux-x86_64", "e" * 64)
    )

  val tests = Tests:
    test("fresh verified runs and complete folds retain exact evidence"):
      val f = fixture()
      val fake = new Fake
      val result = f.run(fake)
      assert(result.outcome == VacaskOutcome.Simulated, result.plot.exists(_.rows.size == 3))
      assert(
        result.raw.exists(_.sha256 == digest(bytes(DcRaw))),
        result.identity.requestSha256.nonEmpty
      )
      assert(fake.requests.size == 2, fake.requests.forall(!_.inheritEnvironment))
      assert(fake.requests.last.arguments.contains("--tomlfile"))
      assert(fake.requests.last.arguments.contains("--skip-embed"))
      assert(fake.requests.last.environment("HOME") == result.evidenceDirectory.get.toString)
      assert(Files.readString(
        result.evidenceDirectory.get.resolve("receipt.json")
      ).contains("startupPolicy"))
      val next = f.run()
      assert(next.evidenceDirectory != result.evidenceDirectory)
      assert(next.identity.requestSha256 == result.identity.requestSha256)
      val folded = f.fold()
      assert(folded.receipt.outcome == VacaskOutcome.Simulated, folded.aggregate.contains(1.5))
      assert(folded.receipt.plot.isEmpty, folded.pointCount == 3)

    test("invalid requests and unsupported capability do not launch"):
      val f = fixture()
      val fake = new Fake
      assert(f.run(fake, f.request.copy(runTimeout = Duration.ZERO)).outcome ==
        VacaskOutcome.InvalidRequest)
      val changed = f.request.bench.copy(initialConditions =
        Vector(AnalogInitialCondition(AnalogNode.Node(0), 1))
      )
      assert(f.run(fake, f.request.copy(bench = changed)).outcome != VacaskOutcome.Simulated)
      val unsupported = f.request.bench.copy(
        analysis = AnalogCircuitAnalysis.AcDecadeSweep(10, 10, 20),
        sources = f.request.bench.sources.map(_.copy(ac = Some(AnalogAcExcitation(1))))
      )
      assert(f.run(fake, f.request.copy(bench = unsupported)).outcome ==
        VacaskOutcome.UnsupportedCapability)
      assert(fake.requests.isEmpty)

    test("artifact and tool pins are mandatory"):
      val f = fixture()
      val fake = new Fake
      assert(f.run(fake, profiles = Vector.empty).outcome == VacaskOutcome.UnsupportedProfile)
      assert(f.run(fake, profiles = Vector(f.profile.copy(executableSha256 = "0" * 64))).outcome ==
        VacaskOutcome.ProfileMismatch)
      assert(f.run(
        fake,
        f.request.copy(compiled = f.request.compiled.copy(outcome = OpenVafOutcome.CompilerError))
      ).outcome == VacaskOutcome.InvalidCompileArtifact)
      assert(fake.requests.isEmpty)
      val mismatch = new Fake(versionAction = request => result(request, stdout = "other version"))
      assert(f.run(mismatch).outcome == VacaskOutcome.ProfileMismatch, mismatch.requests.size == 1)

    test("startup and model integrity are rechecked around execution"):
      val f = fixture()
      val startup = new Fake(runAction = request =>
        Files.writeString(request.workingDirectory.resolve(".vacaskrc.toml"), "[Paths]\n")
        emit(request))
      assert(f.run(startup).outcome == VacaskOutcome.StartupConfiguration)
      val model = new Fake(versionAction = request =>
        Files.writeString(request.workingDirectory.resolve("model.osdi"), "changed")
        result(request, stdout = Version))
      assert(f.run(model).outcome == VacaskOutcome.InvalidCompileArtifact, model.requests.size == 1)
      val deck = new Fake(runAction = request =>
        Files.writeString(request.workingDirectory.resolve("bench.sim"), "changed")
        emit(request))
      assert(f.run(deck).outcome == VacaskOutcome.InvalidCompileArtifact)

    test("failure classes remain distinct and suppress successful-looking raw output"):
      val f = fixture()
      Vector(
        "Cannot open shared object" -> VacaskOutcome.LoadFailure,
        "Device 'dut_master' not found." -> VacaskOutcome.LoadFailure,
        "Syntax error" -> VacaskOutcome.DeckError,
        "Failed to converge" -> VacaskOutcome.Nonconvergence,
        "unclassified warning" -> VacaskOutcome.SimulatorError
      ).foreach: (diagnostic, expected) =>
        val actual = f.run(new Fake(runAction = request =>
          emit(request)
          result(request, stderr = diagnostic)))
        assert(actual.outcome == expected, actual.plot.isEmpty, actual.validatedPoints.isEmpty)
      Vector(
        ToolProcessFailure.Timeout -> VacaskOutcome.Timeout,
        ToolProcessFailure.Interrupted -> VacaskOutcome.Interrupted,
        ToolProcessFailure.OutputLimit -> VacaskOutcome.CaptureFailure,
        ToolProcessFailure.LaunchFailed -> VacaskOutcome.LaunchFailure
      ).foreach: (failure, expected) =>
        assert(f.run(new Fake(runAction =
          request => result(request, failure = Some(failure))
        )).outcome == expected)

    test("malformed or incomplete raw never returns aggregate"):
      val f = fixture()
      Vector(
        DcRaw + "junk\n",
        DcRaw.replace("1 0.5", "1 0.6"),
        DcRaw.replace("2 1\n1\n-0.001", "2 0.5\n1\n-0.001")
      ).foreach: raw =>
        val folded = f.fold(new Fake(runAction = request => emit(request, raw)))
        assert(
          folded.receipt.outcome == VacaskOutcome.InvalidRaw,
          folded.aggregate.isEmpty,
          folded.schema.isEmpty
        )
      val missing = f.run(new Fake(runAction = request => result(request)))
      assert(missing.outcome == VacaskOutcome.InvalidRaw)

    test("consumer failure and evidence mutation discard folds"):
      val f = fixture()
      val simulator = new VacaskSimulator(Vector(f.profile), new Fake)
      val denied = simulator.fold(f.request)(_ => Right(0))((_, _, _) => Left("stop"))
      assert(denied.receipt.outcome == VacaskOutcome.ConsumerFailure, denied.aggregate.isEmpty)
      val mutated = simulator.fold(f.request)(_ => Right(0)): (_, _, _) =>
        val dirs = Files.list(f.root)
        try
          import scala.jdk.CollectionConverters.*
          dirs.iterator().asScala.filter(path =>
            path.getFileName.toString.startsWith("vacask-")
          ).foreach: directory =>
            val raw = directory.resolve("result.raw")
            if Files.exists(raw) then Files.writeString(raw, "changed")
        finally dirs.close()
        Right(0)
      assert(mutated.receipt.outcome == VacaskOutcome.InvalidRaw, mutated.aggregate.isEmpty)

    test("explicit loader libraries are exact pinned manifests"):
      val f = fixture()
      val libs = Files.createDirectory(f.root.resolve("libs"))
      val library = Files.writeString(libs.resolve("libtest.so.1"), "library-v1")
      val hash = digest(Files.readAllBytes(library).toVector)
      val profile = f.profile.copy(runtimeLibraries = Vector("libtest.so.1" -> hash))
      val selected = f.request.copy(runtimeLibraryDirectories = Vector(libs))
      val fake = new Fake
      assert(f.run(fake, selected, Vector(profile)).outcome == VacaskOutcome.Simulated)
      assert(fake.requests.last.environment("LD_LIBRARY_PATH") == libs.toRealPath().toString)
      Files.writeString(libs.resolve("unexpected.so"), "injected")
      val denied = new Fake
      assert(
        f.run(denied, selected, Vector(profile)).outcome == VacaskOutcome.ProfileMismatch,
        denied.requests.isEmpty
      )

    test("late evidence failure suppresses aggregate and retained plot"):
      val f = fixture()
      val fake = new Fake(runAction = request =>
        Files.createDirectory(request.workingDirectory.resolve("receipt.json"))
        emit(request))
      val folded = f.fold(fake)
      assert(
        folded.receipt.outcome == VacaskOutcome.EvidenceFailure,
        folded.aggregate.isEmpty,
        folded.receipt.plot.isEmpty
      )

    test("library mutation during run and callbacks is rejected"):
      val f = fixture()
      val libs = Files.createDirectory(f.root.resolve("runtime"))
      val library = Files.writeString(libs.resolve("libtest.so.1"), "library-v1")
      val profile = f.profile.copy(runtimeLibraries =
        Vector("libtest.so.1" -> digest(Files.readAllBytes(library).toVector))
      )
      val selected = f.request.copy(runtimeLibraryDirectories = Vector(libs))
      val fake = new Fake(runAction = request =>
        Files.writeString(library, "library-v2")
        emit(request))
      val outcome = f.run(fake, selected, Vector(profile))
      assert(outcome.outcome == VacaskOutcome.ProfileMismatch)
      Files.writeString(library, "library-v1")
      val callback = new VacaskSimulator(Vector(profile), new Fake).fold(selected)(_ => Right(0)):
        (count, _, _) =>
          Files.writeString(library, "library-v2")
          Right(count + 1)
      assert(callback.receipt.outcome == VacaskOutcome.ProfileMismatch, callback.aggregate.isEmpty)

    test("transient evidence includes the exact initial domain point"):
      val f = fixture()
      val bench =
        f.request.bench.copy(analysis = AnalogCircuitAnalysis.Transient(0.001, 0.002, false))
      val request = f.request.copy(bench = bench)
      val raw =
        DcRaw.replace("Operating Point", "Transient Analysis").replace("axis notype", "time notype")
          .replace("1 0.5", "1 0.001").replace("2 1\n", "2 0.002\n")
      assert(f.run(new Fake(runAction = req => emit(req, raw)), request).outcome ==
        VacaskOutcome.Simulated)
      val missingInitial = raw.replace("0 0\n", "0 0.0001\n")
      val rejected = f.fold(new Fake(runAction = req => emit(req, missingInitial)), request)
      assert(rejected.receipt.outcome == VacaskOutcome.InvalidRaw, rejected.aggregate.isEmpty)
