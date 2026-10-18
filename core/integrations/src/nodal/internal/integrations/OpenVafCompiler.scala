package nodal.internal.integrations

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration

import scala.util.boundary
import scala.util.control.NonFatal

/** A finite, no-cache compiler adapter. It never rewrites or flattens the supplied model. */
private[nodal] final class OpenVafCompiler(
    profiles: Vector[OpenVafProfile],
    process: ToolProcessExecutor
):
  import OpenVafCompiler.*

  def compile(request: OpenVafCompileRequest): OpenVafCompileResult =
    val modelDigest = OpenVafDiagnosticMapper.sha256(request.modelBytes)
    var receipt = OpenVafCompileResult(
      OpenVafOutcome.InvalidRequest,
      "request has not been executed",
      OpenVafIdentity(
        AdapterVersion,
        modelDigest,
        request.logicalFilename,
        request.languageProfile,
        request.profileId,
        None,
        None,
        request.executable.toString,
        None,
        None,
        Vector.empty,
        SemanticEnvironment,
        platform,
        request.versionTimeout.toString,
        request.compileTimeout.toString,
        request.sourceMap.map(_.modelSha256),
        None,
        request.provenance
      )
    )
    def finish(outcome: OpenVafOutcome, message: String): OpenVafCompileResult =
      receipt = receipt.copy(outcome = outcome, message = message)
      persist(receipt)
    try
      boundary {
        validate(request) match
          case Some(message) => boundary.break(finish(OpenVafOutcome.InvalidRequest, message))
          case None => ()
        Files.createDirectories(request.evidenceDirectory)
        val root = request.evidenceDirectory.toRealPath()
        val directory = Files.createTempDirectory(root, "openvaf-")
        receipt = receipt.copy(evidenceDirectory = Some(directory))
        val modelPath = directory.resolve(request.logicalFilename)
        val outputPath = directory.resolve("model.osdi")
        Files.write(modelPath, request.modelBytes.toArray, StandardOpenOption.CREATE_NEW)
        val mapping = request.sourceMap.map(OpenVafDiagnosticMapper.parse(request.modelBytes, _))
          .getOrElse(OpenVafSourceMapping.NotRequested)
        receipt = receipt.copy(sourceMapping = mapping)
        val model = OpenVafDiagnosticMapper.decodeUtf8(request.modelBytes) match
          case Left(message) => boundary.break(finish(OpenVafOutcome.MalformedModel, message))
          case Right(text) => text
        includePolicy(model) match
          case Some(message) => boundary.break(finish(OpenVafOutcome.InvalidRequest, message))
          case None => ()
        if request.languageProfile != "verilog-a" then
          boundary.break(finish(
            OpenVafOutcome.UnsupportedProfile,
            "only the verilog-a language profile is supported"
          ))
        val selected = profiles.filter(_.id == request.profileId)
        if selected.size != 1 then
          boundary.break(finish(
            OpenVafOutcome.UnsupportedProfile,
            "unknown or ambiguous pinned consumer profile"
          ))
        val profile = selected.head
        val profileDigest = hashStrings(Vector(
          profile.id,
          profile.version,
          profile.executableSha256,
          profile.platform,
          profile.catalogSha256
        ) ++ profile.compileArguments)
        receipt = receipt.copy(identity =
          receipt.identity.copy(
            profileSha256 = Some(profileDigest),
            catalogSha256 = Some(profile.catalogSha256),
            compileArguments = profile.compileArguments
          )
        )
        if profile.platform != platform || platform != "linux-x86_64" then
          boundary.break(finish(
            OpenVafOutcome.UnsupportedProfile,
            "consumer profile does not match this host platform"
          ))
        if profile.compileArguments != CompileArguments then
          boundary.break(finish(
            OpenVafOutcome.UnsupportedProfile,
            "consumer invocation is outside the finite adapter contract"
          ))
        if !Files.isRegularFile(request.executable) || !Files.isExecutable(request.executable) then
          boundary.break(finish(
            OpenVafOutcome.ToolUnavailable,
            "OpenVAF executable is missing, nonregular, or not executable"
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
            OpenVafOutcome.ProfileMismatch,
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
        receipt = receipt.copy(versionProbe = Some(version))
        processFailure(version) match
          case Some((outcome, message)) =>
            boundary.break(finish(outcome, s"version probe: $message"))
          case None => ()
        val versionText = version.standardOutput.trim
        receipt = receipt.copy(identity = receipt.identity.copy(version = Some(versionText)))
        if crashed(version) then
          boundary.break(finish(
            OpenVafOutcome.CompilerCrash,
            "OpenVAF crashed during version probing"
          ))
        if version.exitCode != Some(0) || versionText != profile.version then
          boundary.break(finish(
            OpenVafOutcome.ProfileMismatch,
            "version probe does not match the pinned profile"
          ))
        if hashFile(executable) != toolDigest then
          boundary.break(finish(
            OpenVafOutcome.ProfileMismatch,
            "executable changed during version probing"
          ))
        val historical = profile.observations.filter(o =>
          o.modelSha256 == modelDigest && o.executableSha256 == toolDigest &&
            o.version == versionText
        )
        val key = hashStrings(Vector(
          AdapterVersion,
          "required-linux-process-group",
          modelDigest,
          request.logicalFilename,
          request.languageProfile,
          profileDigest,
          executable.toString,
          toolDigest,
          versionText,
          platform,
          request.versionTimeout.toString,
          request.compileTimeout.toString,
          request.sourceMap.fold("none")(binding => s"some:${binding.modelSha256}"),
          request.provenance.sourceRevision.fold("none")(value => s"some:$value"),
          request.provenance.normalizedMlirSha256.fold("none")(value => s"some:$value")
        ) ++ profile.compileArguments ++ SemanticEnvironment.toVector.sorted.flatMap((key, value) =>
          Vector(key, value)
        ))
        receipt = receipt.copy(
          identity = receipt.identity.copy(requestSha256 = Some(key)),
          historicalObservations = historical
        )
        if Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS) then
          boundary.break(finish(
            OpenVafOutcome.InvalidOutput,
            "output existed before compilation in the fresh evidence directory"
          ))
        val arguments = Vector(request.logicalFilename) ++ profile.compileArguments ++
          Vector("-o", "model.osdi")
        val compilation = process.run(ToolProcessRequest(
          executable,
          arguments,
          directory,
          environment,
          request.compileTimeout,
          inheritEnvironment = false,
          containment = ToolProcessContainment.RequireProcessGroup
        ))
        receipt = receipt.copy(
          compilation = Some(compilation),
          diagnostics = OpenVafDiagnosticMapper.diagnostics(
            compilation.standardError + "\n" + compilation.standardOutput,
            modelPath,
            request.logicalFilename,
            mapping
          )
        )
        processFailure(compilation) match
          case Some((outcome, message)) => boundary.break(finish(outcome, message))
          case None => ()
        if crashed(compilation) then
          boundary.break(finish(
            OpenVafOutcome.CompilerCrash,
            "OpenVAF crashed; historical observations do not override this outcome"
          ))
        if compilation.exitCode != Some(0) then
          boundary.break(finish(
            OpenVafOutcome.CompilerError,
            "OpenVAF rejected the model; this alone does not classify Nodal language eligibility"
          ))
        if hashFile(executable) != toolDigest then
          boundary.break(finish(
            OpenVafOutcome.ProfileMismatch,
            "executable changed during compilation"
          ))
        if hashFile(modelPath) != modelDigest then
          boundary.break(finish(
            OpenVafOutcome.InvalidOutput,
            "the staged model changed during compilation"
          ))
        if !validElf(outputPath) then
          boundary.break(finish(
            OpenVafOutcome.InvalidOutput,
            "successful exit did not produce a fresh regular x86-64 ELF shared object"
          ))
        receipt = receipt.copy(artifact =
          Some(OpenVafArtifact(
            outputPath,
            hashFile(outputPath),
            Files.size(outputPath)
          ))
        )
        finish(
          OpenVafOutcome.Compiled,
          "OpenVAF compiled the exact supplied model; numerical execution was not performed"
        )
      }
    catch
      case NonFatal(error) => finish(
          OpenVafOutcome.EvidenceFailure,
          s"unable to stage or retain compile evidence: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        )

private[nodal] object OpenVafCompiler:
  private val AdapterVersion = "nodal-openvaf-v1"
  private val EvidencePlaceholder = "<fresh-evidence-directory>"
  private val CompileArguments = Vector(
    "--target",
    "x86_64-unknown-linux",
    "--target_cpu",
    "generic",
    "--interface",
    "OSDI",
    "-O",
    "3"
  )
  private val SemanticEnvironment = Map(
    "PATH" -> "/usr/bin:/bin",
    "LANG" -> "C",
    "LC_ALL" -> "C",
    "TZ" -> "UTC",
    "SOURCE_DATE_EPOCH" -> "0",
    "RUST_BACKTRACE" -> "0",
    "HOME" -> EvidencePlaceholder,
    "TMPDIR" -> EvidencePlaceholder,
    "XDG_CACHE_HOME" -> EvidencePlaceholder
  )
  private val ReservedFilenames = Set(
    ".",
    "..",
    "constants.vams",
    "disciplines.vams",
    "model.osdi",
    "receipt.json",
    "version.stdout.log",
    "version.stderr.log",
    "compile.stdout.log",
    "compile.stderr.log"
  )
  private val ForbiddenDirectives = Set(
    "define",
    "undef",
    "undefineall",
    "ifdef",
    "ifndef",
    "elsif",
    "else",
    "endif",
    "line",
    "pragma",
    "resetall",
    "timescale",
    "default_nettype",
    "begin_keywords",
    "end_keywords",
    "celldefine",
    "endcelldefine",
    "unconnected_drive",
    "nounconnected_drive"
  )

  def compile(request: OpenVafCompileRequest): OpenVafCompileResult =
    new OpenVafCompiler(OpenVafProfiles.all, ToolProcessRunner).compile(request)

  private def platform: String =
    val os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
    val arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
    if os == "linux" && Set("amd64", "x86_64").contains(arch) then "linux-x86_64"
    else s"$os-$arch"

  private def validate(request: OpenVafCompileRequest): Option[String] =
    def bounded(timeout: Duration): Boolean =
      !timeout.isNegative && !timeout.isZero && timeout.compareTo(Duration.ofHours(1)) <= 0
    if !request.executable.isAbsolute || !request.evidenceDirectory.isAbsolute then
      Some("executable and evidence directory must be absolute paths")
    else if request.logicalFilename.isEmpty || request.logicalFilename.startsWith("-") ||
      ReservedFilenames.contains(request.logicalFilename) ||
      request.logicalFilename.exists(c => Character.isISOControl(c) || c == '/' || c == '\\')
    then
      Some(
        "logical filename must be a safe nonreserved single filename without leading dash or control characters"
      )
    else if !bounded(request.versionTimeout) || !bounded(request.compileTimeout) then
      Some("version and compile timeouts must be positive and at most one hour")
    else None

  /** Includes are pinned to compiler built-ins. No external includes, defines, or conditional
    * directives.
    */
  private def includePolicy(model: String): Option[String] =
    var index = 0
    var failure: Option[String] = None
    var inString = false
    var lineComment = false
    var blockComment = false
    while index < model.length && failure.isEmpty do
      val char = model.charAt(index)
      val next = if index + 1 < model.length then model.charAt(index + 1) else '\u0000'
      if lineComment then
        if char == '\n' then lineComment = false
      else if blockComment then
        if char == '*' && next == '/' then
          blockComment = false
          index += 1
      else if inString then
        if char == '\\' then index += 1
        else if char == '"' then inString = false
      else if char == '/' && next == '/' then
        lineComment = true
        index += 1
      else if char == '/' && next == '*' then
        blockComment = true
        index += 1
      else if char == '"' then inString = true
      else if char == '`' then
        val start = index + 1
        var end = start
        while end < model.length && (model.charAt(end).isLetterOrDigit || model.charAt(end) == '_')
        do end += 1
        val name = model.substring(start, end)
        if name == "include" then
          var cursor = end
          while cursor < model.length &&
            (model.charAt(cursor) == ' ' || model.charAt(cursor) == '\t')
          do cursor += 1
          if cursor >= model.length || model.charAt(cursor) != '"' then
            failure = Some(
              "only literal built-in constants.vams and disciplines.vams includes are permitted"
            )
          val closing = model.indexOf('"', cursor + 1)
          if closing < 0 || !Set(
              "constants.vams",
              "disciplines.vams"
            ).contains(model.substring(cursor + 1, closing))
          then
            failure = Some(
              "only pinned built-in constants.vams and disciplines.vams includes are permitted"
            )
          index = closing
        else if ForbiddenDirectives.contains(name) then
          failure = Some(s"preprocessor directive `$name is outside the finite compile profile")
        else index = end - 1
      index += 1
    failure

  private def processFailure(result: ToolProcessResult): Option[(OpenVafOutcome, String)] =
    result.failure.map: failure =>
      val outcome = failure match
        case ToolProcessFailure.InvalidRequest => OpenVafOutcome.InvalidRequest
        case ToolProcessFailure.ToolUnavailable => OpenVafOutcome.ToolUnavailable
        case ToolProcessFailure.LaunchFailed => OpenVafOutcome.LaunchFailure
        case ToolProcessFailure.Timeout => OpenVafOutcome.Timeout
        case ToolProcessFailure.Interrupted => OpenVafOutcome.Interrupted
        case ToolProcessFailure.OutputLimit | ToolProcessFailure.CaptureFailed =>
          OpenVafOutcome.CaptureFailure
      outcome -> result.failureMessage.getOrElse(failure.toString)

  private def crashed(result: ToolProcessResult): Boolean =
    val crashHeader =
      "(?i)^(?:thread ['\"].*['\"] panicked at.*|LLVM ERROR:.*|(?:error: )?internal compiler error:.*|fatal error: error in backend:.*|segmentation fault(?: \\(core dumped\\))?)$".r
    val header = (result.standardError + "\n" + result.standardOutput).linesIterator
      .exists(line => crashHeader.matches(line.trim))
    result.exitCode.exists(code =>
      code == 101 || Set(132, 134, 135, 136, 137, 139, 143, 152, 153, 159).contains(code)
    ) || header

  private def hashStrings(values: Vector[String]): String =
    val bytes = new ByteArrayOutputStream()
    val output = new DataOutputStream(bytes)
    values.foreach: value =>
      val encoded = value.getBytes(StandardCharsets.UTF_8)
      output.writeInt(encoded.length)
      output.write(encoded)
    output.close()
    OpenVafDiagnosticMapper.sha256(bytes.toByteArray.toVector)

  private def hashFile(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](65536)
      var count = stream.read(buffer)
      while count != -1 do
        digest.update(buffer, 0, count)
        count = stream.read(buffer)
    finally stream.close()
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

  private def validElf(path: Path): Boolean =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) < 64 then false
    else
      val input = Files.newInputStream(path)
      val header = try input.readNBytes(64)
      finally input.close()
      header.length == 64 && header(0) == 0x7f.toByte && header(1) == 'E'.toByte &&
      header(2) == 'L'.toByte && header(3) == 'F'.toByte && header(4) == 2 &&
      header(5) == 1 && header(6) == 1 && header(16) == 3 && header(17) == 0 &&
      header(18) == 62 && header(19) == 0 && header(20) == 1 &&
      header(21) == 0 && header(22) == 0 && header(23) == 0 && header(52) == 64 && header(53) == 0

  private def persist(result: OpenVafCompileResult): OpenVafCompileResult =
    result.evidenceDirectory match
      case None => result
      case Some(directory) =>
        val interrupted = Thread.interrupted()
        try
          result.versionProbe.foreach: probe =>
            Files.write(
              directory.resolve("version.stdout.log"),
              IArray.genericWrapArray(probe.standardOutputBytes).toArray
            )
            Files.write(
              directory.resolve("version.stderr.log"),
              IArray.genericWrapArray(probe.standardErrorBytes).toArray
            )
          result.compilation.foreach: compilation =>
            Files.write(
              directory.resolve("compile.stdout.log"),
              IArray.genericWrapArray(compilation.standardOutputBytes).toArray
            )
            Files.write(
              directory.resolve("compile.stderr.log"),
              IArray.genericWrapArray(compilation.standardErrorBytes).toArray
            )
          Files.writeString(
            directory.resolve("receipt.json"),
            receiptJson(result),
            StandardCharsets.UTF_8
          )
          result
        catch
          case NonFatal(error) => result.copy(
              outcome = OpenVafOutcome.EvidenceFailure,
              message =
                s"${result.outcome}: ${result.message}; evidence retention failed: ${error.getMessage}",
              artifact = None
            )
        finally if interrupted then Thread.currentThread().interrupt()

  /** Stable JSON field order; raw compiler streams remain separate verbatim byte files. */
  def receiptJson(result: OpenVafCompileResult): String =
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
    def authored(span: OpenVafAuthoredSpan): String = obj(
      "semanticIdentity" -> str(span.semanticIdentity),
      "path" -> str(span.path),
      "startLine" -> span.startLine.toString,
      "startColumn" -> span.startColumn.toString,
      "endLine" -> span.endLine.toString,
      "endColumn" -> span.endColumn.toString,
      "precision" -> str("statement")
    )
    def execution(value: ToolProcessResult, prefix: String): String = obj(
      "command" -> arr(value.command.map(str)),
      "executedCommand" -> arr((if value.executedCommand.nonEmpty then value.executedCommand
                                else value.command).map(str)),
      "containment" -> str("required-linux-process-group"),
      "inheritEnvironment" -> "false",
      "workingDirectory" -> optional(result.evidenceDirectory)(p => str(p.toString)),
      "environment" -> obj(result.identity.environment.toVector.sortBy(_._1).map((key, value) =>
        key -> str(if value == EvidencePlaceholder then
          result.evidenceDirectory.map(_.toString).getOrElse(value)
        else value)
      )*),
      "exitCode" -> optional(value.exitCode)(_.toString),
      "failure" -> optional(value.failure)(f => str(f.toString)),
      "failureMessage" -> optional(value.failureMessage)(str),
      "elapsedMillis" -> value.elapsedMillis.toString,
      "stdout" -> str(s"$prefix.stdout.log"),
      "stderr" -> str(s"$prefix.stderr.log")
    )
    val identity = result.identity
    val mapping = result.sourceMapping match
      case OpenVafSourceMapping.NotRequested => obj("status" -> str("not-requested"))
      case OpenVafSourceMapping.Invalid(digest, errors) => obj(
          "status" -> str("invalid"),
          "diagnosticCode" -> str("NODAL-OPENVAF-MAP-001"),
          "modelSha256" -> str(digest),
          "errors" -> arr(errors.map(str))
        )
      case OpenVafSourceMapping.Valid(digest, statements) => obj(
          "status" -> str("valid"),
          "modelSha256" -> str(digest),
          "statements" -> arr(statements.map(s =>
            obj(
              "generatedLine" -> s.generatedLine.toString,
              "firstColumn" -> s.firstColumn.toString,
              "lastColumn" -> s.lastColumn.toString,
              "authored" -> authored(s.authored)
            )
          ))
        )
    obj(
      "schema" -> str(AdapterVersion),
      "outcome" -> str(result.outcome.toString),
      "diagnosticCode" -> str(result.diagnosticCode),
      "message" -> str(result.message),
      "evidenceDirectory" -> optional(result.evidenceDirectory)(p => str(p.toString)),
      "identity" -> obj(
        "adapterVersion" -> str(identity.adapterVersion),
        "modelSha256" -> str(identity.modelSha256),
        "logicalFilename" -> str(identity.logicalFilename),
        "languageProfile" -> str(identity.languageProfile),
        "profileId" -> str(identity.profileId),
        "profileSha256" -> optional(identity.profileSha256)(str),
        "catalogSha256" -> optional(identity.catalogSha256)(str),
        "executable" -> str(identity.executable),
        "executableSha256" -> optional(identity.executableSha256)(str),
        "version" -> optional(identity.version)(str),
        "compileArguments" -> arr(identity.compileArguments.map(str)),
        "environment" ->
          obj(identity.environment.toVector.sortBy(_._1).map((key, value) => key -> str(value))*),
        "platform" -> str(identity.platform),
        "versionTimeout" -> str(identity.versionTimeout),
        "compileTimeout" -> str(identity.compileTimeout),
        "sourceMapBindingSha256" -> optional(identity.sourceMapBindingSha256)(str),
        "requestSha256" -> optional(identity.requestSha256)(str),
        "sourceRevision" -> optional(identity.provenance.sourceRevision)(str),
        "normalizedMlirSha256" -> optional(identity.provenance.normalizedMlirSha256)(str)
      ),
      "versionProbe" -> optional(result.versionProbe)(execution(_, "version")),
      "compilation" -> optional(result.compilation)(execution(_, "compile")),
      "sourceMapping" -> mapping,
      "diagnostics" -> arr(result.diagnostics.map(d =>
        obj(
          "severity" -> str(d.severity),
          "message" -> str(d.message),
          "generated" -> optional(d.generated)(p =>
            obj(
              "path" -> str(p.path),
              "line" -> p.line.toString,
              "column" -> p.column.toString
            )
          ),
          "authored" -> optional(d.authored)(authored)
        )
      )),
      "artifact" -> optional(result.artifact)(a =>
        obj(
          "path" -> str(a.path.toString),
          "sha256" -> str(a.sha256),
          "size" -> a.size.toString
        )
      ),
      "historicalObservationScope" -> str(
        "Exact model/tool/version history only; invocation may differ and never determines this outcome"
      ),
      "historicalObservations" -> arr(result.historicalObservations.map(o =>
        obj(
          "id" -> str(o.id),
          "modelSha256" -> str(o.modelSha256),
          "executableSha256" -> str(o.executableSha256),
          "version" -> str(o.version),
          "outcome" -> str(o.outcome),
          "description" -> str(o.description),
          "compileArguments" -> optional(o.compileArguments)(args => arr(args.map(str)))
        )
      ))
    ) + "\n"
