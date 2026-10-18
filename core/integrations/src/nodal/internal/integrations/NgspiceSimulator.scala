package nodal.internal.integrations

import java.io.{ByteArrayOutputStream, DataOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration

import scala.util.boundary
import scala.util.control.NonFatal

/** Finite private OSDI execution. Every request probes and runs anew; no result cache exists. */
private[nodal] final class NgspiceSimulator(
    profiles: Vector[NgspiceProfile],
    process: ToolProcessExecutor
):
  import NgspiceSimulator.*

  def simulate(request: NgspiceSimulateRequest): NgspiceSimulationResult =
    collect(lower(request))

  def simulate(request: NgspiceCircuitSimulateRequest): NgspiceSimulationResult =
    collect(lower(request))

  /** Callbacks can see a prefix; only a successful receipt certifies the completed raw file. */
  def fold[A](request: NgspiceSimulateRequest)(
      initialize: NgspiceRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A])
      : NgspiceStreamingSimulationResult[A] =
    execute(lower(request), initialize, consume, (_, _) => None)

  def fold[A](request: NgspiceCircuitSimulateRequest)(
      initialize: NgspiceRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A])
      : NgspiceStreamingSimulationResult[A] =
    execute(lower(request), initialize, consume, (_, _) => None)

  private def collect(request: NgspiceRunRequest): NgspiceSimulationResult =
    execute(
      request,
      _ => Right(Vector.newBuilder[Vector[NgspiceComplex]]),
      (rows, _, row) => Right(rows.addOne(row)),
      (schema, rows) =>
        Some(NgspiceRawPlot(
          schema.title,
          schema.plotName,
          schema.isComplex,
          schema.variables,
          rows.result()
        ))
    ).receipt

  private def lower(request: NgspiceSimulateRequest): NgspiceRunRequest =
    NgspiceRunRequest(
      request.executable,
      request.evidenceDirectory,
      request.profileId,
      request.compiled,
      request.model,
      request.analysis,
      request.options,
      request.versionTimeout,
      request.runTimeout,
      () => NgspiceBench.generate(request.model, request.analysis, request.options),
      None
    )

  private def lower(request: NgspiceCircuitSimulateRequest): NgspiceRunRequest =
    NgspiceRunRequest(
      request.executable,
      request.evidenceDirectory,
      request.profileId,
      request.compiled,
      request.bench.model,
      request.bench.analysis,
      request.bench.options,
      request.versionTimeout,
      request.runTimeout,
      () => NgspiceBench.generate(request.bench),
      Some(request.bench)
    )

  private def execute[A](
      request: NgspiceRunRequest,
      initialize: NgspiceRawSchema => Either[String, A],
      consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A],
      retainedPlot: (NgspiceRawSchema, A) => Option[NgspiceRawPlot]
  ): NgspiceStreamingSimulationResult[A] =
    var completed = Option.empty[(NgspiceRawSchema, A)]
    var receipt = NgspiceSimulationResult(
      NgspiceOutcome.InvalidRequest,
      "request has not been executed",
      NgspiceIdentity(
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
        request.model,
        request.analysis,
        request.options,
        None,
        digest(Startup),
        Arguments,
        SemanticEnvironment,
        platform,
        request.versionTimeout.toString,
        request.runTimeout.toString,
        None,
        request.circuit
      ),
      request.compiled
    )
    def finish(
        outcome: NgspiceOutcome,
        message: String
    ): NgspiceStreamingSimulationResult[A] =
      receipt = receipt.copy(
        outcome = outcome,
        message = message,
        plot = if outcome == NgspiceOutcome.Simulated then receipt.plot else None,
        validatedPoints = if outcome == NgspiceOutcome.Simulated then receipt.validatedPoints
        else None
      )
      val saved = persist(receipt)
      if saved.outcome == NgspiceOutcome.Simulated then
        val (schema, aggregate) = completed.get
        NgspiceStreamingSimulationResult(saved, Some(schema), schema.pointCount, Some(aggregate))
      else NgspiceStreamingSimulationResult(saved)
    try
      boundary {
        validate(request) match
          case Some(message) => boundary.break(finish(NgspiceOutcome.InvalidRequest, message))
          case None => ()
        Files.createDirectories(request.evidenceDirectory)
        val directory =
          Files.createTempDirectory(request.evidenceDirectory.toRealPath(), "ngspice-")
        receipt = receipt.copy(evidenceDirectory = Some(directory))
        val bench = request.generate() match
          case Left(message) => boundary.break(finish(NgspiceOutcome.InvalidRequest, message))
          case Right(value) => value
        receipt =
          receipt.copy(identity = receipt.identity.copy(deckSha256 = Some(digest(bench.deck))))
        val modelPath = directory.resolve("model.osdi")
        val deckPath = directory.resolve("bench.cir")
        val startupPath = directory.resolve("spinit")
        val rawPath = directory.resolve("results.raw")
        Files.writeString(
          deckPath,
          bench.deck,
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW
        )
        Files.writeString(
          startupPath,
          Startup,
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW
        )
        stageArtifact(request.compiled, modelPath) match
          case Left(message) =>
            boundary.break(finish(NgspiceOutcome.InvalidCompileArtifact, message))
          case Right(_) => ()
        val selected = profiles.filter(_.id == request.profileId)
        if selected.size != 1 then
          boundary.break(finish(
            NgspiceOutcome.UnsupportedProfile,
            "unknown or ambiguous pinned simulator profile"
          ))
        val profile = selected.head
        val profileDigest = hashStrings(Vector(
          profile.id,
          profile.version,
          profile.executableSha256,
          profile.platform,
          profile.catalogSha256
        ))
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            profileSha256 = Some(profileDigest),
            catalogSha256 = Some(profile.catalogSha256)
          )
        )
        if profile.platform != platform || platform != "linux-x86_64" then
          boundary.break(finish(
            NgspiceOutcome.UnsupportedProfile,
            "simulator profile does not match this host platform"
          ))
        if !sha256(profile.executableSha256) || !sha256(profile.catalogSha256) ||
          profile.version.isEmpty
        then
          boundary.break(finish(
            NgspiceOutcome.UnsupportedProfile,
            "simulator profile has invalid identity fields"
          ))
        if !Files.isRegularFile(request.executable) || !Files.isExecutable(request.executable) then
          boundary.break(finish(
            NgspiceOutcome.ToolUnavailable,
            "ngspice executable is missing, nonregular, or not executable"
          ))
        val executable = request.executable.toRealPath()
        val toolDigest = hashFile(executable)
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            executable = executable.toString,
            executableSha256 = Some(toolDigest)
          )
        )
        if toolDigest != profile.executableSha256 then
          boundary.break(finish(
            NgspiceOutcome.ProfileMismatch,
            "executable SHA-256 does not match the pinned profile"
          ))
        val environment = SemanticEnvironment.map: (key, value) =>
          key -> (if value == EvidencePlaceholder then directory.toString else value)
        val version = process.run(ToolProcessRequest(
          executable,
          Vector("--version"),
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
            NgspiceOutcome.ProfileMismatch,
            "version probe does not match the pinned profile"
          ))
        if hashFile(executable) != toolDigest then
          boundary.break(finish(
            NgspiceOutcome.ProfileMismatch,
            "executable changed during version probing"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath, startupPath) then
          boundary.break(finish(
            NgspiceOutcome.InvalidCompileArtifact,
            "staged model, deck, or startup changed during version probing"
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
          digest(Startup)
        ) ++ Arguments ++
          SemanticEnvironment.toVector.sorted.flatMap((key, value) => Vector(key, value)))
        receipt = receipt.copy(identity = receipt.identity.copy(requestSha256 = Some(key)))
        if Files.exists(rawPath, LinkOption.NOFOLLOW_LINKS) then
          boundary.break(finish(
            NgspiceOutcome.InvalidRaw,
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
            NgspiceOutcome.SimulatorError,
            "ngspice failed without a recognized load, deck, or convergence diagnostic"
          ))
        if hashFile(executable) != toolDigest then
          boundary.break(finish(
            NgspiceOutcome.ProfileMismatch,
            "executable changed during simulation"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath, startupPath) then
          boundary.break(finish(
            NgspiceOutcome.InvalidCompileArtifact,
            "staged model, deck, or startup changed during simulation"
          ))
        val raw = readRaw(rawPath, bench, initialize, consume)
        receipt = receipt.copy(raw = raw.artifact)
        val (schema, aggregate) = raw.result match
          case Left((outcome, message)) => boundary.break(finish(outcome, message))
          case Right(value) => value
        // Callbacks are caller code; verify that retained evidence still identifies the run.
        if hashFile(executable) != toolDigest then
          boundary.break(finish(
            NgspiceOutcome.ProfileMismatch,
            "executable changed while raw output was being consumed"
          ))
        if !unchangedInputs(receipt.identity, modelPath, deckPath, startupPath) then
          boundary.break(finish(
            NgspiceOutcome.InvalidCompileArtifact,
            "staged model, deck, or startup changed while raw output was being consumed"
          ))
        completed = Some(schema -> aggregate)
        receipt = receipt.copy(
          plot = retainedPlot(schema, aggregate),
          validatedPoints = Some(schema.pointCount)
        )
        finish(
          NgspiceOutcome.Simulated,
          "ngspice produced the expected populated model-dependent analysis; numerical acceptance is separate"
        )
      }
    catch
      case NonFatal(error) => finish(
          NgspiceOutcome.EvidenceFailure,
          s"unable to stage or retain simulation evidence: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        )

private[nodal] object NgspiceSimulator:
  private val AdapterVersion = "nodal-ngspice-v1"
  private val EvidencePlaceholder = "<fresh-evidence-directory>"
  private val MaxArtifactBytes = 64L * 1024 * 1024
  private val MaxRawBytes = 16 * 1024 * 1024
  private val Arguments = Vector("-b", "-r", "results.raw", "bench.cir")
  private val Startup = "set stricterror\nosdi model.osdi\nset filetype=ascii\nset noaskquit\n"
  private val SemanticEnvironment = Map(
    "PATH" -> "/usr/bin:/bin",
    "LANG" -> "C",
    "LC_ALL" -> "C",
    "TZ" -> "UTC",
    "HOME" -> EvidencePlaceholder,
    "SPICE_SCRIPTS" -> EvidencePlaceholder
  )

  def simulate(request: NgspiceSimulateRequest): NgspiceSimulationResult =
    new NgspiceSimulator(NgspiceProfiles.all, ToolProcessRunner).simulate(request)

  def simulate(request: NgspiceCircuitSimulateRequest): NgspiceSimulationResult =
    new NgspiceSimulator(NgspiceProfiles.all, ToolProcessRunner).simulate(request)

  def fold[A](request: NgspiceSimulateRequest)(
      initialize: NgspiceRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A])
      : NgspiceStreamingSimulationResult[A] =
    new NgspiceSimulator(NgspiceProfiles.all, ToolProcessRunner).fold(request)(initialize)(consume)

  def fold[A](request: NgspiceCircuitSimulateRequest)(
      initialize: NgspiceRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A])
      : NgspiceStreamingSimulationResult[A] =
    new NgspiceSimulator(NgspiceProfiles.all, ToolProcessRunner).fold(request)(initialize)(consume)

  private def platform: String =
    val os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
    val arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
    if os == "linux" && Set("amd64", "x86_64").contains(arch) then "linux-x86_64" else s"$os-$arch"

  private def validate(request: NgspiceRunRequest): Option[String] =
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

  private def stageArtifact(
      compiled: OpenVafCompileResult,
      destination: Path
  ): Either[String, Unit] =
    def successful(value: Option[ToolProcessResult]): Boolean =
      value.exists(result => result.failure.isEmpty && result.exitCode.contains(0))
    if compiled.outcome != OpenVafOutcome.Compiled || !successful(compiled.compilation) ||
      !successful(compiled.versionProbe) || !compiled.identity.requestSha256.exists(sha256) ||
      !sha256(compiled.identity.modelSha256) || compiled.identity.languageProfile != "verilog-a"
    then
      Left("simulation requires a successful identified OpenVAF compile result")
    else
      compiled.artifact match
        case None => Left("successful compile result has no OSDI artifact")
        case Some(artifact) =>
          if !artifact.path.isAbsolute || artifact.size < 64 || artifact.size > MaxArtifactBytes ||
            !sha256(artifact.sha256) ||
            !Files.isRegularFile(artifact.path, LinkOption.NOFOLLOW_LINKS)
          then
            Left("compiled artifact must be a regular bounded OSDI file with a valid identity")
          else if Files.size(artifact.path) != artifact.size then
            Left("compiled artifact size changed")
          else
            val input = Files.newInputStream(artifact.path)
            val output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            try
              val buffer = new Array[Byte](65536)
              var read = input.read(buffer)
              while read != -1 && count <= artifact.size do
                count += read
                if count <= artifact.size then
                  hash.update(buffer, 0, read)
                  output.write(buffer, 0, read)
                if count <= artifact.size then read = input.read(buffer)
            finally
              try input.close()
              finally output.close()
            if count != artifact.size ||
              hash.digest().map(b => f"${b & 0xff}%02x").mkString != artifact.sha256
            then
              Left("compiled artifact content does not match the compile result")
            else Right(())

  private def unchangedInputs(
      identity: NgspiceIdentity,
      model: Path,
      deck: Path,
      startup: Path
  ): Boolean =
    Vector(model, deck, startup).forall(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS)) &&
      identity.artifactSize.contains(Files.size(model)) &&
      identity.artifactSha256.contains(hashFile(model, MaxArtifactBytes)) &&
      identity.deckSha256.contains(hashFile(deck, MaxRawBytes)) &&
      digest(Startup) == hashFile(startup, 1024)

  private def hashStrings(values: Vector[String]): String =
    val bytes = new ByteArrayOutputStream()
    val output = new DataOutputStream(bytes)
    values.foreach: value =>
      val encoded = value.getBytes(StandardCharsets.UTF_8)
      output.writeInt(encoded.length)
      output.write(encoded)
    output.close()
    OpenVafDiagnosticMapper.sha256(bytes.toByteArray.toVector)

  private def hashFile(path: Path, maximum: Long = Long.MaxValue): String =
    val hash = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    var total = 0L
    try
      val buffer = new Array[Byte](65536)
      var read = input.read(buffer)
      while read != -1 && total <= maximum do
        total += read
        if total <= maximum then hash.update(buffer, 0, read)
        if total <= maximum then read = input.read(buffer)
    finally input.close()
    if total > maximum then "" else hash.digest().map(b => f"${b & 0xff}%02x").mkString

  private final case class RawRead[A](
      artifact: Option[NgspiceRawArtifact],
      result: Either[(NgspiceOutcome, String), (NgspiceRawSchema, A)]
  )

  /** Counts and hashes the very bytes consumed by the parser, including bounded error draining. */
  private final class RawInput(source: InputStream) extends InputStream:
    private val hash = MessageDigest.getInstance("SHA-256")
    var count = 0L
    var ascii = true
    var ended = false
    override def read(): Int =
      val byte = new Array[Byte](1)
      if read(byte, 0, 1) == -1 then -1 else byte(0) & 0xff
    override def read(buffer: Array[Byte], offset: Int, length: Int): Int =
      if length == 0 then 0
      else if count > MaxRawBytes then -1
      else
        val size =
          source.read(buffer, offset, math.min(length.toLong, MaxRawBytes + 1L - count).toInt)
        if size == -1 then ended = true
        else
          count += size
          hash.update(buffer, offset, size)
          var index = offset
          while index < offset + size do
            if (buffer(index) & 0xff) > 127 then ascii = false
            index += 1
        size
    def drain(): Unit =
      val buffer = new Array[Byte](8192)
      while !ended && count <= MaxRawBytes do
        val _ = read(buffer, 0, buffer.length)
    def sha256: String = hash.digest().map(byte => f"${byte & 0xff}%02x").mkString
    override def close(): Unit = source.close()

  private def readRaw[A](
      path: Path,
      bench: NgspiceGeneratedBench,
      initialize: NgspiceRawSchema => Either[String, A],
      consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A]
  ): RawRead[A] =
    def invalid(message: String): RawRead[A] =
      RawRead(None, Left(NgspiceOutcome.InvalidRaw -> message))
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0 then
      invalid("successful exit did not produce a fresh nonempty regular raw output")
    else if Files.size(path) > MaxRawBytes then invalid(s"raw output exceeds $MaxRawBytes bytes")
    else
      val input = new RawInput(Files.newInputStream(path))
      val validator = new RequestedGrid(bench)
      var schema = Option.empty[NgspiceRawSchema]
      var aggregate = Option.empty[A]
      var consumerError = Option.empty[String]
      def accept(value: => Either[String, A]): Unit =
        try
          value match
            case Left(message) => consumerError = Some(message)
            case Right(result) => aggregate = Some(result)
        catch
          case NonFatal(error) => consumerError = Some(
              s"raw consumer failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
            )
      val parsed = try
        val result = NgspiceRaw.fold(input, bench.kind, asciiOnly = true)(value =>
          schema = Some(value)
          validator.initialize(value)
          if validator.error.isEmpty then accept(initialize(value))
          Right(())
        ): (_, index, row) =>
          validator.consume(index, row.head.real)
          if validator.error.isEmpty && consumerError.isEmpty then
            aggregate.foreach(value => accept(consume(value, index, row)))
          Right(())
        // A malformed suffix must never leave a successful prefix or a prefix-only artifact hash.
        input.drain()
        result
      finally input.close()
      if input.count == 0 || input.count > MaxRawBytes || !input.ended then
        invalid("raw output is empty or exceeds its byte bound")
      else if !input.ascii then invalid("raw output must use the pinned ASCII protocol")
      else
        val artifact = NgspiceRawArtifact(path, input.sha256, input.count)
        val result =
          for
            _ <- parsed.left.map(NgspiceOutcome.InvalidRaw -> _)
            _ <- validator.finish().toLeft(()).left.map(NgspiceOutcome.InvalidRaw -> _)
            _ <- Either.cond(
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(path) == artifact.size && hashFile(path, MaxRawBytes) == artifact.sha256,
              (),
              NgspiceOutcome.InvalidRaw -> "raw output changed while it was being parsed"
            )
            _ <- consumerError.toLeft(()).left.map(NgspiceOutcome.ConsumerFailure -> _)
          yield (schema.get, aggregate.get)
        RawRead(Some(artifact), result)

  private def processFailure(result: ToolProcessResult): Option[(NgspiceOutcome, String)] =
    result.failure.map: failure =>
      val outcome = failure match
        case ToolProcessFailure.InvalidRequest => NgspiceOutcome.InvalidRequest
        case ToolProcessFailure.ToolUnavailable => NgspiceOutcome.ToolUnavailable
        case ToolProcessFailure.LaunchFailed => NgspiceOutcome.LaunchFailure
        case ToolProcessFailure.Timeout => NgspiceOutcome.Timeout
        case ToolProcessFailure.Interrupted => NgspiceOutcome.Interrupted
        case ToolProcessFailure.OutputLimit | ToolProcessFailure.CaptureFailed =>
          NgspiceOutcome.CaptureFailure
      outcome -> result.failureMessage.getOrElse(failure.toString)

  /** Only observed finite diagnostic families receive specific outcomes. Unknown diagnostics fail.
    */
  private def diagnostics(result: ToolProcessResult): Option[(NgspiceOutcome, String)] =
    val lines = (result.standardError + "\n" + result.standardOutput).toLowerCase(
      java.util.Locale.ROOT
    )
    def contains(values: String*): Boolean = values.exists(lines.contains)
    if contains(
        "error opening osdi lib",
        "osdi libs have not been loaded",
        "couldn't be loaded",
        "unable to find definition of model",
        "unknown osdi",
        "osdi version mismatch"
      )
    then
      Some(NgspiceOutcome.LoadFailure -> "ngspice could not load or resolve the staged OSDI model")
    else if contains(
        "timestep too small",
        "singular matrix",
        "convergence failed",
        "no convergence",
        "iteration limit",
        "failed to converge",
        "transient op failed",
        "source stepping failed"
      )
    then
      Some(NgspiceOutcome.Nonconvergence -> "ngspice reported a convergence failure")
    else if contains(
        "error on line",
        "circuit not parsed",
        "unknown parameter",
        "unknown device",
        "syntax error",
        "no such command available"
      )
    then
      Some(NgspiceOutcome.DeckError -> "ngspice rejected the generated bench or startup commands")
    else if result.standardError.trim.nonEmpty ||
      "(?s).*\\b(?:error|fatal|failed|failure|aborted|panic|warning)\\b.*".r.matches(lines)
    then
      Some(NgspiceOutcome.SimulatorError ->
        "ngspice emitted an unrecognized diagnostic; raw output is not accepted")
    else None

  /** One O(1)-state native grid owner for collected and non-collecting consumers. */
  private final class RequestedGrid(bench: NgspiceGeneratedBench):
    private var schemaError = Option.empty[String]
    private var valid = true
    private var count = 0
    private var previous = Option.empty[Double]

    def initialize(schema: NgspiceRawSchema): Unit =
      if schema.variables.map(value => value.name -> value.kind) != bench.expectedVariables then
        schemaError = Some("raw variables do not match the generated bench")
      bench.axis match
        case NgspiceAxis.Transient(_, _) => valid &&= schema.pointCount >= 2
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
        case NgspiceAxis.Dc(start, _, step) =>
          monotonic(step > 0) && close(value, Math.fma(index.toDouble, step, start), step)
        case NgspiceAxis.AcLinear(points, start, stop) =>
          val step = if points == 1 then start else (stop - start) / (points - 1)
          monotonic(true) &&
          close(value, Math.fma(index.toDouble, if points == 1 then 0.0 else step, start), step)
        case NgspiceAxis.AcDecade(points, start, _) =>
          val factor = math.pow(10.0, 1.0 / points)
          val expected = start * math.pow(10.0, index.toDouble / points)
          monotonic(true) && close(value, expected, expected * (1 - 1 / factor))
        case NgspiceAxis.Transient(step, _) =>
          previous match
            case None => value >= 0 && value <= step * (1 + 1e-8)
            case Some(before) => value > before && value - before <= step * (1 + 1e-8)
      valid &&= pointValid
      previous = Some(value)
      count += 1

    def error: Option[String] = schemaError.orElse(Option.unless(valid)(
      "raw axis does not match the requested analysis grid or completed transient interval"
    ))

    def finish(): Option[String] =
      bench.axis match
        case NgspiceAxis.Transient(step, stop) =>
          valid &&= count >= 2 && previous.exists(close(_, stop, step))
        case _ => valid &&= count == bench.requestedPoints
      error

  private def persist(result: NgspiceSimulationResult): NgspiceSimulationResult =
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
              outcome = NgspiceOutcome.EvidenceFailure,
              message =
                s"${result.outcome}: ${result.message}; evidence retention failed: ${error.getMessage}",
              plot = None,
              validatedPoints = None
            )
        finally if interrupted then Thread.currentThread().interrupt()

  /** Raw streams and raw plot bytes are retained separately, without normalization. */
  def receiptJson(result: NgspiceSimulationResult): String =
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
    val (moduleName, terminals, parameters) = identity.model match
      case model: NgspiceModuleBinding =>
        (model.moduleName, model.terminals.map(_.toString), model.parameters)
      case model: NgspiceCircuitBinding =>
        (model.moduleName, model.terminals.map(_.toString), model.parameters)
    def circuitSummary(bench: NgspiceCircuitBench): String = obj(
      "nodeCount" -> bench.nodes.size.toString,
      "nodes" -> arr(bench.nodes.take(63).map(node => str(node.toString))),
      "sourceCount" -> bench.sources.size.toString,
      "sources" -> arr(bench.sources.take(63).map(source =>
        obj(
          "id" -> source.id.value.toString,
          "kind" -> str(source.kind.toString),
          "positive" -> str(source.positive.toString),
          "negative" -> str(source.negative.toString),
          "dc" -> str(java.lang.Double.toString(source.dc)),
          "ac" -> optional(source.ac)(value => str(value.toString)),
          "transient" -> optional(source.transient) {
            case NgspiceTransientWaveform.Pwl(points) => obj(
                "kind" -> str("Pwl"),
                "points" -> points.size.toString,
                "values" -> str("see bench.cir when deck generation succeeded")
              )
            case pulse => str(pulse.toString)
          }
        )
      )),
      "probeCount" -> bench.probes.size.toString,
      "probes" -> arr(bench.probes.take(63).map(probe => str(probe.toString))),
      "initialConditionCount" -> bench.initialConditions.size.toString,
      "initialConditions" -> arr(bench.initialConditions.take(63).map(value => str(value.toString)))
    )
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
        "moduleName" -> str(moduleName),
        "terminals" -> arr(terminals.map(str)),
        "parameters" -> arr(parameters.map((key, value) =>
          obj("name" -> str(key), "value" -> str(java.lang.Double.toString(value)))
        )),
        "analysis" -> str(identity.analysis.toString),
        "circuitBench" -> optional(identity.circuit)(circuitSummary),
        "solverOptions" -> str(identity.options.toString),
        "deckSha256" -> optional(identity.deckSha256)(str),
        "startupSha256" -> str(identity.startupSha256),
        "arguments" -> arr(identity.arguments.map(str)),
        "environment" -> environment(false),
        "platform" -> str(identity.platform),
        "versionTimeout" -> str(identity.versionTimeout),
        "runTimeout" -> str(identity.runTimeout),
        "requestSha256" -> optional(identity.requestSha256)(str)
      ),
      "compiled" -> OpenVafCompiler.receiptJson(result.compiled),
      "versionProbe" -> optional(result.versionProbe)(execution(_, "version")),
      "simulation" -> optional(result.simulation)(execution(_, "simulate")),
      "deck" -> str("bench.cir"),
      "startup" -> str("spinit"),
      "stagedArtifact" -> str("model.osdi"),
      "raw" -> optional(result.raw)(value =>
        obj(
          "path" -> str(value.path.toString),
          "sha256" -> str(value.sha256),
          "size" -> value.size.toString
        )
      ),
      "validatedPoints" ->
        optional(result.validatedPoints.orElse(result.plot.map(_.rows.size)))(_.toString)
    ) + "\n"
