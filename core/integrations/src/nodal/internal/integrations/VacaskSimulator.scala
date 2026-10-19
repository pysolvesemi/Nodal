package nodal.internal.integrations

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.time.Duration

import scala.jdk.CollectionConverters.*
import scala.util.boundary
import scala.util.control.NonFatal

/** Finite private OSDI execution. Every request probes and runs anew; no result cache exists. */
private[nodal] final class VacaskSimulator(
    profiles: Vector[VacaskProfile],
    process: ToolProcessExecutor
):
  import VacaskSimulator.*

  def simulate(request: VacaskCircuitSimulateRequest): VacaskSimulationResult =
    execute(
      request,
      _ => Right(Vector.newBuilder[Vector[AnalogComplex]]),
      (rows, _, row) => Right(rows.addOne(row)),
      (schema, rows) =>
        Some(AnalogRawPlot(
          schema.title,
          schema.plotName,
          schema.isComplex,
          schema.variables,
          rows.result()
        ))
    ).receipt

  /** Callback side effects may observe prefixes; only complete success returns an aggregate. */
  def fold[A](request: VacaskCircuitSimulateRequest)(
      initialize: AnalogRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[AnalogComplex]) => Either[String, A])
      : VacaskStreamingSimulationResult[A] =
    execute(request, initialize, consume, (_, _) => None)

  private def execute[A](
      request: VacaskCircuitSimulateRequest,
      initialize: AnalogRawSchema => Either[String, A],
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A],
      retainedPlot: (AnalogRawSchema, A) => Option[AnalogRawPlot]
  ): VacaskStreamingSimulationResult[A] =
    var completed = Option.empty[(AnalogRawSchema, A)]
    var receipt = VacaskSimulationResult(
      VacaskOutcome.InvalidRequest,
      "request has not been executed",
      VacaskIdentity(
        AdapterVersion,
        request.profileId,
        None,
        None,
        request.executable.toString,
        None,
        None,
        request.compiled.identity.requestSha256,
        request.compiled.identity.modelSha256,
        request.compiled.artifact.map(_.sha256),
        request.compiled.artifact.map(_.size),
        request.bench,
        None,
        StartupPolicy,
        Vector.empty,
        Arguments,
        SemanticEnvironment,
        platform,
        request.versionTimeout.toString,
        request.runTimeout.toString,
        None
      ),
      request.compiled
    )
    def finish(
        outcome: VacaskOutcome,
        message: String
    ): VacaskStreamingSimulationResult[A] =
      receipt = receipt.copy(
        outcome = outcome,
        message = message,
        plot = if outcome == VacaskOutcome.Simulated then receipt.plot else None,
        validatedPoints = if outcome == VacaskOutcome.Simulated then receipt.validatedPoints
        else None
      )
      val saved = persist(receipt)
      if saved.outcome == VacaskOutcome.Simulated then
        val (schema, aggregate) = completed.get
        VacaskStreamingSimulationResult(saved, Some(schema), schema.pointCount, Some(aggregate))
      else VacaskStreamingSimulationResult(saved)
    try
      boundary {
        validate(request) match
          case Some(message) => boundary.break(finish(VacaskOutcome.InvalidRequest, message))
          case None => ()
        Files.createDirectories(request.evidenceDirectory)
        val directory =
          Files.createTempDirectory(request.evidenceDirectory.toRealPath(), "vacask-")
        receipt = receipt.copy(evidenceDirectory = Some(directory))
        AnalogCircuitValidation.validate(request.bench) match
          case Left(message) => boundary.break(finish(VacaskOutcome.InvalidRequest, message))
          case Right(_) => ()
        val bench = VacaskBench.generate(request.bench) match
          case Left(message) => boundary.break(finish(VacaskOutcome.UnsupportedCapability, message))
          case Right(value) => value
        receipt =
          receipt.copy(identity = receipt.identity.copy(deckSha256 = Some(digest(bench.deck))))
        val modelPath = directory.resolve("model.osdi")
        val deckPath = directory.resolve("bench.sim")
        val rawPath = directory.resolve("result.raw")
        Files.writeString(
          directory.resolve("startup.toml"),
          "",
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW
        )
        Files.writeString(
          deckPath,
          bench.deck,
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW
        )
        OsdiArtifact.stage(request.compiled, modelPath) match
          case Left(message) =>
            boundary.break(finish(VacaskOutcome.InvalidCompileArtifact, message))
          case Right(_) => ()
        val selected = profiles.filter(_.id == request.profileId)
        if selected.size != 1 then
          boundary.break(finish(
            VacaskOutcome.UnsupportedProfile,
            "unknown or ambiguous pinned simulator profile"
          ))
        val profile = selected.head
        val profileDigest = hashStrings(Vector(
          profile.id,
          profile.version,
          profile.executableSha256,
          profile.platform,
          profile.catalogSha256
        ) ++ profile.runtimeLibraries.flatMap((name, hash) => Vector(name, hash)))
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            profileSha256 = Some(profileDigest),
            catalogSha256 = Some(profile.catalogSha256)
          )
        )
        if profile.platform != platform || platform != "linux-x86_64" then
          boundary.break(finish(
            VacaskOutcome.UnsupportedProfile,
            "simulator profile does not match this host platform"
          ))
        if !sha256(profile.executableSha256) || !sha256(profile.catalogSha256) ||
          profile.version.isEmpty
        then
          boundary.break(finish(
            VacaskOutcome.UnsupportedProfile,
            "simulator profile has invalid identity fields"
          ))
        if !Files.isRegularFile(request.executable) || !Files.isExecutable(request.executable) then
          boundary.break(finish(
            VacaskOutcome.ToolUnavailable,
            "VACASK executable is missing, nonregular, or not executable"
          ))
        val executable = request.executable.toRealPath()
        val toolDigest = hashFile(executable, 128L * 1024 * 1024)
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            executable = executable.toString,
            executableSha256 = Some(toolDigest)
          )
        )
        if toolDigest != profile.executableSha256 then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "executable SHA-256 does not match the pinned profile"
          ))
        val runtime = runtimeManifest(request.runtimeLibraryDirectories) match
          case Left(message) => boundary.break(finish(VacaskOutcome.ProfileMismatch, message))
          case Right(value) => value
        val runtimeDirectories = request.runtimeLibraryDirectories.map(_.toRealPath())
        if runtime != profile.runtimeLibraries.sortBy(_._1) then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "runtime library manifest does not match pinned profile"
          ))
        val semanticEnvironment = SemanticEnvironment ++
          Option.when(request.runtimeLibraryDirectories.nonEmpty)(
            "LD_LIBRARY_PATH" -> runtimeDirectories.map(_.toString).mkString(":")
          )
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            runtimeLibraries = runtime,
            environment = semanticEnvironment
          )
        )
        if !startupControlled(directory) then
          boundary.break(finish(
            VacaskOutcome.StartupConfiguration,
            "VACASK owned startup configuration is not intact"
          ))
        val environment = semanticEnvironment.map: (key, value) =>
          key -> (if value == EvidencePlaceholder then directory.toString else value)
        val version = process.run(ToolProcessRequest(
          executable,
          VersionArguments,
          directory,
          environment,
          request.versionTimeout,
          inheritEnvironment = false,
          containment = ToolProcessContainment.RequireProcessGroup
        ))
        receipt = receipt.copy(
          versionProbe = Some(version),
          identity = receipt.identity.copy(version = Some(version.standardOutput.trim))
        )
        processFailure(version) match
          case Some((outcome, message)) =>
            boundary.break(finish(outcome, s"version probe: $message"))
          case None => ()
        if version.exitCode != Some(0) || version.standardOutput.trim != profile.version ||
          version.standardError.trim.nonEmpty
        then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "version probe does not match the pinned profile"
          ))
        if hashFile(executable, 128L * 1024 * 1024) != toolDigest then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "executable changed during version probing"
          ))
        if !startupControlled(directory) then
          boundary.break(finish(
            VacaskOutcome.StartupConfiguration,
            "startup configuration changed during execution"
          ))
        if runtimeManifest(runtimeDirectories) != Right(runtime) then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "runtime library manifest changed during execution"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath) then
          boundary.break(finish(
            VacaskOutcome.InvalidCompileArtifact,
            "staged model or deck changed during version probing"
          ))
        val key = hashStrings(Vector(
          AdapterVersion,
          "required-linux-process-group",
          receipt.identity.compileRequestSha256.get,
          receipt.identity.modelSha256,
          receipt.identity.artifactSha256.get,
          receipt.identity.artifactSize.get.toString,
          profileDigest,
          executable.toString,
          toolDigest,
          version.standardOutput.trim,
          platform,
          request.versionTimeout.toString,
          request.runTimeout.toString,
          digest(bench.deck),
          StartupPolicy
        ) ++ Arguments ++
          semanticEnvironment.toVector.sorted.flatMap((key, value) => Vector(key, value)))
        receipt = receipt.copy(identity = receipt.identity.copy(requestSha256 = Some(key)))
        if Files.exists(rawPath, LinkOption.NOFOLLOW_LINKS) then
          boundary.break(finish(
            VacaskOutcome.InvalidRaw,
            "raw output existed before simulation in the fresh attempt directory"
          ))
        val simulation = process.run(ToolProcessRequest(
          executable,
          Arguments,
          directory,
          environment,
          request.runTimeout,
          inheritEnvironment = false,
          containment = ToolProcessContainment.RequireProcessGroup
        ))
        receipt = receipt.copy(simulation = Some(simulation))
        processFailure(simulation) match
          case Some((outcome, message)) => boundary.break(finish(outcome, message))
          case None => ()
        diagnostics(simulation) match
          case Some((outcome, message)) => boundary.break(finish(outcome, message))
          case None => ()
        if simulation.exitCode != Some(0) then
          boundary.break(finish(
            VacaskOutcome.SimulatorError,
            "VACASK failed without a recognized load, deck, or convergence diagnostic"
          ))
        if hashFile(executable, 128L * 1024 * 1024) != toolDigest then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "executable changed during simulation"
          ))
        if !startupControlled(directory) then
          boundary.break(finish(
            VacaskOutcome.StartupConfiguration,
            "startup configuration changed during execution"
          ))
        if runtimeManifest(runtimeDirectories) != Right(runtime) then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "runtime library manifest changed during execution"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath) then
          boundary.break(finish(
            VacaskOutcome.InvalidCompileArtifact,
            "staged model or deck changed during simulation"
          ))
        val raw = readRaw(rawPath, bench, initialize, consume)
        receipt = receipt.copy(raw = raw.artifact)
        val (schema, aggregate) = raw.result match
          case Left((outcome, message)) => boundary.break(finish(outcome, message))
          case Right(value) => value
        // Callbacks are caller code; verify that retained evidence still identifies the run.
        if hashFile(executable, 128L * 1024 * 1024) != toolDigest then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "executable changed while raw output was being consumed"
          ))
        if !startupControlled(directory) then
          boundary.break(finish(
            VacaskOutcome.StartupConfiguration,
            "startup configuration changed during execution"
          ))
        if runtimeManifest(runtimeDirectories) != Right(runtime) then
          boundary.break(finish(
            VacaskOutcome.ProfileMismatch,
            "runtime library manifest changed during execution"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath) then
          boundary.break(finish(
            VacaskOutcome.InvalidCompileArtifact,
            "staged model or deck changed while raw output was being consumed"
          ))
        completed = Some(schema -> aggregate)
        receipt = receipt.copy(
          plot = retainedPlot(schema, aggregate),
          validatedPoints = Some(schema.pointCount)
        )
        finish(
          VacaskOutcome.Simulated,
          "VACASK produced the expected populated model-dependent analysis; numerical acceptance is separate"
        )
      }
    catch
      case NonFatal(error) => finish(
          VacaskOutcome.EvidenceFailure,
          s"unable to stage or retain simulation evidence: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        )

private[nodal] object VacaskSimulator:
  private val AdapterVersion = "nodal-vacask-v1"
  private val EvidencePlaceholder = "<fresh-evidence-directory>"
  private val MaxArtifactBytes = 64L * 1024 * 1024
  private val MaxRawBytes = 16 * 1024 * 1024
  private val VersionArguments = Vector("--help")
  private val Arguments = Vector(
    "--skip-embed",
    "--skip-postprocess",
    "--quiet-progress",
    "--ncpu",
    "1",
    "--blas-ncpu",
    "1",
    "--tomlfile",
    "startup.toml",
    "bench.sim"
  )
  private val StartupPolicy =
    "explicit-empty-startup.toml;require-absent-inputdir-.vacaskrc.toml;isolated-HOME;exact-osdi;no-embed-postprocess"
  private val SemanticEnvironment = Map(
    "PATH" -> "/usr/bin:/bin",
    "LANG" -> "C",
    "LC_ALL" -> "C",
    "TZ" -> "UTC",
    "HOME" -> EvidencePlaceholder,
    "SIM_MODULE_PATH" -> EvidencePlaceholder,
    "SIM_INCLUDE_PATH" -> EvidencePlaceholder,
    "SIM_OPENVAF" -> "/nonexistent/nodal-no-compiler",
    "OMP_NUM_THREADS" -> "1",
    "OPENBLAS_NUM_THREADS" -> "1"
  )

  def simulate(request: VacaskCircuitSimulateRequest): VacaskSimulationResult =
    new VacaskSimulator(VacaskProfiles.all, ToolProcessRunner).simulate(request)

  def fold[A](request: VacaskCircuitSimulateRequest)(initialize: AnalogRawSchema => Either[
    String,
    A
  ])(
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): VacaskStreamingSimulationResult[A] =
    new VacaskSimulator(VacaskProfiles.all, ToolProcessRunner).fold(request)(initialize)(consume)

  private def platform: String =
    val os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
    val arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
    if os == "linux" && Set("amd64", "x86_64").contains(arch) then "linux-x86_64" else s"$os-$arch"

  private def validate(request: VacaskCircuitSimulateRequest): Option[String] =
    def bounded(value: Duration): Boolean =
      !value.isZero && !value.isNegative && value.compareTo(Duration.ofHours(1)) <= 0
    if !request.executable.isAbsolute || !request.evidenceDirectory.isAbsolute then
      Some("executable and evidence directory must be absolute paths")
    else if !bounded(request.versionTimeout) || !bounded(request.runTimeout) then
      Some("version and run timeouts must be positive and at most one hour")
    else None

  private def sha256(value: String): Boolean = value.matches("[0-9a-f]{64}")
  private def digest(value: String): String =
    OpenVafDiagnosticMapper.sha256(value.getBytes(StandardCharsets.UTF_8).toVector)

  private def unchangedInputs(
      identity: VacaskIdentity,
      model: Path,
      deck: Path
  ): Boolean =
    Vector(model, deck).forall(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS)) &&
      identity.artifactSize.contains(Files.size(model)) &&
      identity.artifactSha256.contains(hashFile(model, MaxArtifactBytes)) &&
      identity.deckSha256.contains(hashFile(deck, MaxRawBytes))

  private def hashStrings(values: Vector[String]): String =
    val bytes = new ByteArrayOutputStream()
    val output = new DataOutputStream(bytes)
    values.foreach: value =>
      val encoded = value.getBytes(StandardCharsets.UTF_8)
      output.writeInt(encoded.length)
      output.write(encoded)
    output.close()
    OpenVafDiagnosticMapper.sha256(bytes.toByteArray.toVector)

  private def hashFile(path: Path, maximum: Long): String =
    AnalogRawEvidence.hashFile(path, maximum)

  private def startupControlled(directory: Path): Boolean =
    val startup = directory.resolve("startup.toml")
    Files.isRegularFile(startup, LinkOption.NOFOLLOW_LINKS) && Files.size(startup) == 0 &&
    !Files.exists(directory.resolve(".vacaskrc.toml"), LinkOption.NOFOLLOW_LINKS)

  /** Explicit added loader paths are bounded and fully pinned; system libc is a platform premise.
    */
  private def runtimeManifest(directories: Vector[Path]): Either[String, Vector[(String, String)]] =
    if directories.size > 8 || directories.exists(path =>
        !path.isAbsolute ||
          path.toString.exists(char => char == ':' || char < ' ') || !Files.isDirectory(path)
      )
    then
      Left("runtime library directories must be bounded absolute existing directories")
    else
      val entries = directories.flatMap: directory =>
        val stream = Files.list(directory.toRealPath())
        try stream.iterator().asScala.take(65).toVector
        finally stream.close()
      if entries.size > 64 || entries.exists(path =>
          !Files.isRegularFile(path) ||
            Files.size(path) == 0 || Files.size(path) > 128L * 1024 * 1024 ||
            !path.getFileName.toString.matches("[A-Za-z0-9_.+-]+")
        ) ||
        entries.map(_.getFileName.toString).distinct.size != entries.size
      then
        Left("runtime libraries must be unique bounded regular files with safe names")
      else if entries.map(Files.size).sum > 256L * 1024 * 1024 then
        Left("runtime libraries exceed the total 256 MiB byte bound")
      else
        Right(entries.map(path =>
          path.getFileName.toString -> hashFile(path, 128L * 1024 * 1024)
        ).sortBy(_._1))

  private final case class RawRead[A](
      artifact: Option[VacaskRawArtifact],
      result: Either[(VacaskOutcome, String), (AnalogRawSchema, A)]
  )

  private def readRaw[A](
      path: Path,
      bench: AnalogGeneratedBench,
      initialize: AnalogRawSchema => Either[String, A],
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): RawRead[A] =
    val validator = new RequestedGrid(bench)
    val read = AnalogRawEvidence.read(
      path,
      (input, init, next) => VacaskRaw.fold(input, bench.kind)(init)(next),
      schema => { validator.initialize(schema); validator.error },
      (index, row) => { validator.consume(index, row.head.real); validator.error },
      () => validator.finish(),
      initialize,
      consume
    )
    RawRead(
      read.artifact.map(value => VacaskRawArtifact(value.path, value.sha256, value.size)),
      read.result.left.map((failure, message) =>
        (if failure == AnalogRawEvidence.Failure.InvalidRaw then VacaskOutcome.InvalidRaw
         else VacaskOutcome.ConsumerFailure) -> message
      )
    )

  private def processFailure(result: ToolProcessResult): Option[(VacaskOutcome, String)] =
    result.failure.map: failure =>
      val outcome = failure match
        case ToolProcessFailure.InvalidRequest => VacaskOutcome.InvalidRequest
        case ToolProcessFailure.ToolUnavailable => VacaskOutcome.ToolUnavailable
        case ToolProcessFailure.LaunchFailed => VacaskOutcome.LaunchFailure
        case ToolProcessFailure.Timeout => VacaskOutcome.Timeout
        case ToolProcessFailure.Interrupted => VacaskOutcome.Interrupted
        case ToolProcessFailure.OutputLimit | ToolProcessFailure.CaptureFailed =>
          VacaskOutcome.CaptureFailure
      outcome -> result.failureMessage.getOrElse(failure.toString)

  /** Only observed finite diagnostic families receive specific outcomes. Unknown diagnostics fail.
    */
  private def diagnostics(result: ToolProcessResult): Option[(VacaskOutcome, String)] =
    val lines = (result.standardError + "\n" + result.standardOutput).toLowerCase(
      java.util.Locale.ROOT
    )
    def contains(values: String*): Boolean = values.exists(lines.contains)
    if contains(
        "failed to load",
        "cannot load",
        "could not load",
        "cannot open shared object",
        "invalid elf header",
        "file too short",
        "undefined symbol",
        "wrong elf class",
        "osdi version",
        "osdi_version",
        "osdi interface version",
        "osdi descriptor",
        "osdi file contains no models",
        "cannot find module",
        "failed to open",
        "cannot open osdi"
      ) ||
      "(?s).*device '[^\n]*' not found\\..*".r.matches(lines)
    then
      Some(VacaskOutcome.LoadFailure -> "VACASK could not load or resolve the staged OSDI model")
    else if contains(
        "timestep too small",
        "singular matrix",
        "convergence failed",
        "no convergence",
        "iteration limit",
        "failed to converge",
        "convergence failure",
        "minimum timestep"
      )
    then
      Some(VacaskOutcome.Nonconvergence -> "VACASK reported a convergence failure")
    else if contains(
        "syntax error",
        "parse error",
        "unknown parameter",
        "unknown device",
        "unknown model",
        "unknown command",
        "unexpected token",
        "error parsing"
      )
    then
      Some(VacaskOutcome.DeckError -> "VACASK rejected the generated bench")
    else if result.standardError.trim.nonEmpty ||
      "(?s).*\\b(?:error|fatal|failed|failure|aborted|panic|warning)\\b.*".r.matches(lines)
    then
      Some(VacaskOutcome.SimulatorError ->
        "VACASK emitted an unrecognized diagnostic; raw output is not accepted")
    else None

  /** VACASK shared-profile lattice and complete transient validation, using native variable names.
    */
  private final class RequestedGrid(bench: AnalogGeneratedBench):
    private var schemaError = Option.empty[String]
    private var valid = true
    private var count = 0
    private var previous = Option.empty[Double]

    def initialize(schema: AnalogRawSchema): Unit =
      if schema.variables.map(value => value.name -> value.kind) != bench.expectedVariables then
        schemaError = Some("raw variables do not match the generated bench")
      bench.axis match
        case AnalogAxis.Transient(_, _) => valid &&= schema.pointCount >= 2
        case _ => valid &&= schema.pointCount == bench.requestedPoints

    private def close(actual: Double, expected: Double, spacing: Double): Boolean =
      // Offset-relative tolerance must never swallow adjacent requested grid points.
      val tolerance = math.min(
        math.max(math.abs(expected) * 1e-8, math.abs(spacing) * 1e-8),
        math.abs(spacing) * 0.25
      )
      math.abs(actual - expected) <= tolerance

    def consume(index: Int, value: Double): Unit =
      def monotonic(increasing: Boolean): Boolean = previous.forall(before =>
        if increasing then value > before else value < before
      )
      val pointValid = bench.axis match
        case AnalogAxis.Dc(start, _, step) =>
          monotonic(step > 0) && close(value, Math.fma(index.toDouble, step, start), step)
        case AnalogAxis.AcLinear(points, start, stop) =>
          val step = if points == 1 then start else (stop - start) / (points - 1)
          monotonic(true) &&
          close(value, Math.fma(index.toDouble, if points == 1 then 0.0 else step, start), step)
        case AnalogAxis.AcDecade(points, start, _) =>
          val factor = math.pow(10.0, 1.0 / points)
          val expected = start * math.pow(10.0, index.toDouble / points)
          monotonic(true) && close(value, expected, expected * (1 - 1 / factor))
        case AnalogAxis.Transient(step, _) =>
          previous match
            case None => value == 0.0
            case Some(before) => value > before && value - before <= step * (1 + 1e-8)
      valid &&= pointValid
      previous = Some(value)
      count += 1

    def error: Option[String] = schemaError.orElse(Option.unless(valid)(
      "raw axis does not match the requested analysis grid or completed transient interval"
    ))

    def finish(): Option[String] =
      bench.axis match
        case AnalogAxis.Transient(step, stop) =>
          valid &&= count >= 2 && previous.exists(close(_, stop, step))
        case _ => valid &&= count == bench.requestedPoints
      error

  private def persist(result: VacaskSimulationResult): VacaskSimulationResult =
    result.evidenceDirectory match
      case None => result
      case Some(directory) =>
        val interrupted = Thread.interrupted()
        try
          Vector("version" -> result.versionProbe, "simulate" -> result.simulation).foreach:
            (prefix, process) =>
              process.foreach: value =>
                Files.write(
                  directory.resolve(s"$prefix.stdout.log"),
                  IArray.genericWrapArray(value.standardOutputBytes).toArray
                )
                Files.write(
                  directory.resolve(s"$prefix.stderr.log"),
                  IArray.genericWrapArray(value.standardErrorBytes).toArray
                )
          Files.writeString(
            directory.resolve("receipt.json"),
            receiptJson(result),
            StandardCharsets.UTF_8
          )
          result
        catch
          case NonFatal(error) => result.copy(
              outcome = VacaskOutcome.EvidenceFailure,
              message =
                s"${result.outcome}: ${result.message}; evidence retention failed: ${error.getMessage}",
              plot = None,
              validatedPoints = None
            )
        finally if interrupted then Thread.currentThread().interrupt()

  /** Separate raw/log files preserve exact tool bytes; receipt records their execution context. */
  def receiptJson(result: VacaskSimulationResult): String =
    def str(value: String): String = "\"" + value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' => f"\\u${char.toInt}%04x"
      case char => char.toString
    } + "\""
    def obj(fields: (String, String)*): String =
      fields.map((key, value) => s"${str(key)}:$value").mkString("{", ",", "}")
    def arr(values: Iterable[String]): String = values.mkString("[", ",", "]")
    def optional[A](value: Option[A])(encode: A => String): String =
      value.map(encode).getOrElse("null")
    val identity = result.identity
    def environment(actual: Boolean): String = obj(identity.environment.toVector.sorted.map(
      (key, value) =>
        key -> str(if actual && value == EvidencePlaceholder then
          result.evidenceDirectory.fold(value)(_.toString)
        else value)
    )*)
    def execution(value: ToolProcessResult, prefix: String): String = obj(
      "command" -> arr(value.command.map(str)),
      "executedCommand" -> arr((if value.executedCommand.nonEmpty then value.executedCommand
                                else value.command).map(str)),
      "containment" -> str("required-linux-process-group"),
      "inheritEnvironment" -> "false",
      "workingDirectory" -> optional(result.evidenceDirectory)(p => str(p.toString)),
      "environment" -> environment(true),
      "exitCode" -> optional(value.exitCode)(_.toString),
      "failure" -> optional(value.failure)(f => str(f.toString)),
      "failureMessage" -> optional(value.failureMessage)(str),
      "elapsedMillis" -> value.elapsedMillis.toString,
      "stdout" -> str(s"$prefix.stdout.log"),
      "stderr" -> str(s"$prefix.stderr.log")
    )
    obj(
      "schema" -> str(AdapterVersion),
      "outcome" -> str(result.outcome.toString),
      "diagnosticCode" -> str(result.diagnosticCode),
      "message" -> str(result.message),
      "evidenceDirectory" -> optional(result.evidenceDirectory)(p => str(p.toString)),
      "identity" -> obj(
        "adapterVersion" -> str(identity.adapterVersion),
        "profileId" -> str(identity.profileId),
        "profileSha256" -> optional(identity.profileSha256)(str),
        "catalogSha256" -> optional(identity.catalogSha256)(str),
        "executable" -> str(identity.executable),
        "executableSha256" -> optional(identity.executableSha256)(str),
        "version" -> optional(identity.version)(str),
        "compileRequestSha256" -> optional(identity.compileRequestSha256)(str),
        "modelSha256" -> str(identity.modelSha256),
        "artifactSha256" -> optional(identity.artifactSha256)(str),
        "artifactSize" -> optional(identity.artifactSize)(_.toString),
        "bench" -> str(identity.bench.toString),
        "deckSha256" -> optional(identity.deckSha256)(str),
        "startupPolicy" -> str(identity.startupPolicy),
        "startupSha256" -> str(digest("")),
        "runtimeLibraries" -> arr(identity.runtimeLibraries.map((name, hash) =>
          obj("name" -> str(name), "sha256" -> str(hash))
        )),
        "arguments" -> arr(identity.arguments.map(str)),
        "versionArguments" -> arr(VersionArguments.map(str)),
        "environment" -> environment(false),
        "platform" -> str(identity.platform),
        "versionTimeout" -> str(identity.versionTimeout),
        "runTimeout" -> str(identity.runTimeout),
        "requestSha256" -> optional(identity.requestSha256)(str)
      ),
      "compiled" -> OpenVafCompiler.receiptJson(result.compiled),
      "versionProbe" -> optional(result.versionProbe)(execution(_, "version")),
      "simulation" -> optional(result.simulation)(execution(_, "simulate")),
      "deck" -> str("bench.sim"),
      "startup" -> str("startup.toml"),
      "stagedArtifact" -> str("model.osdi"),
      "raw" -> optional(result.raw)(value =>
        obj(
          "path" -> str(value.path.toString),
          "sha256" -> str(value.sha256),
          "size" -> value.size.toString
        )
      ),
      "validatedPoints" -> optional(result.validatedPoints)(_.toString)
    ) + "\n"
