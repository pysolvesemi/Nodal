package nodal.internal.bridge

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import scala.jdk.CollectionConverters.*

import nodal.internal.integrations.ToolProcessFailure
import nodal.internal.integrations.ToolProcessRequest
import nodal.internal.integrations.ToolProcessRunner

private[nodal] final case class NativeCompilerRequest(
    executable: Path,
    arguments: Vector[String],
    workingDirectory: Path,
    environment: Map[String, String] = Map.empty,
    timeout: Duration = Duration.ofSeconds(30)
)

private[nodal] sealed trait NativeCompilerResult:
  def command: Vector[String]
  def standardOutput: String
  def standardError: String

private[nodal] final case class NativeCompilerSuccess(
    command: Vector[String],
    normalizedMlir: String,
    standardError: String
) extends NativeCompilerResult:
  def standardOutput: String = normalizedMlir

private[nodal] final case class NativeCompilerFailure(
    diagnostic: BridgeDiagnostic,
    command: Vector[String],
    standardOutput: String,
    standardError: String,
    exitCode: Option[Int]
) extends NativeCompilerResult

private[nodal] object NativeCompilerClient:
  private val ProtocolVersion = "1"

  def run(
      document: NodalMlirDocument,
      request: NativeCompilerRequest
  ): NativeCompilerResult =
    validateRequest(request) match
      case Some(failure) => failure
      case None => execute(document, request)

  private def validateRequest(
      request: NativeCompilerRequest
  ): Option[NativeCompilerFailure] =
    val command = request.executable.toString +: request.arguments
    if !request.executable.isAbsolute then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-001",
          "native compiler executable must be an absolute path",
          command
        )
      )
    else if !Files.isRegularFile(request.executable) ||
      !Files.isExecutable(request.executable)
    then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-002",
          "native compiler executable is missing or not executable",
          command
        )
      )
    else if !Files.isDirectory(request.workingDirectory) then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-003",
          "native compiler working directory does not exist",
          command
        )
      )
    else if request.timeout.isZero || request.timeout.isNegative then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-004",
          "native compiler timeout must be positive",
          command
        )
      )
    else None

  private def execute(
      document: NodalMlirDocument,
      request: NativeCompilerRequest
  ): NativeCompilerResult =
    var temporary: Option[Path] = None
    var result: Option[NativeCompilerResult] = None
    try
      val directory = Files.createTempDirectory(
        request.workingDirectory,
        "nodal-scala-mlir-"
      )
      temporary = Some(directory)
      val input = directory.resolve("input.mlir")
      val _ = Files.writeString(
        input,
        document.text,
        StandardCharsets.UTF_8
      )
      val process = ToolProcessRunner.run(
        ToolProcessRequest(
          executable = request.executable,
          arguments = request.arguments :+ input.toString,
          workingDirectory = request.workingDirectory,
          environment = request.environment ++ Map(
            "NODAL_BRIDGE_SCHEMA" -> document.schema,
            "NODAL_BRIDGE_VERSION" -> document.version.toString,
            "NODAL_PROCESS_PROTOCOL" -> ProtocolVersion
          ),
          timeout = request.timeout
        )
      )
      val standardOutput = normalize(process.standardOutput)
      val standardError = normalize(process.standardError)
      process.failure match
        case Some(kind) =>
          val diagnostic = kind match
            case ToolProcessFailure.Timeout =>
              BridgeDiagnostic(
                "NODAL-BRIDGE-PROCESS-006",
                s"native compiler timed out after ${request.timeout.toMillis} ms"
              )
            case ToolProcessFailure.LaunchFailed | ToolProcessFailure.ToolUnavailable =>
              BridgeDiagnostic(
                "NODAL-BRIDGE-PROCESS-005",
                s"native compiler launch failed: ${process.failureMessage.getOrElse(kind.toString)}"
              )
            case _ =>
              BridgeDiagnostic(
                "NODAL-BRIDGE-PROCESS-010",
                s"native compiler process failed: ${process.failureMessage.getOrElse(kind.toString)}"
              )
          result = Some(
            NativeCompilerFailure(
              diagnostic,
              if kind == ToolProcessFailure.LaunchFailed ||
                kind == ToolProcessFailure.ToolUnavailable
              then
                request.executable.toString +: request.arguments
              else process.command,
              standardOutput,
              standardError,
              process.exitCode
            )
          )
        case None if process.exitCode.contains(0) =>
          result = Some(NativeCompilerSuccess(process.command, standardOutput, standardError))
        case None =>
          val exitCode = process.exitCode.getOrElse(-1)
          val mapped = NativeDiagnosticMapper.classify(standardError, exitCode)
          val diagnostic =
            if mapped.code == "NODAL-DIAGNOSTIC-EXTERNAL-001" then
              BridgeDiagnostic(
                "NODAL-BRIDGE-PROCESS-007",
                s"native compiler exited with status $exitCode"
              )
            else mapped
          result = Some(
            NativeCompilerFailure(
              diagnostic,
              process.command,
              standardOutput,
              standardError,
              process.exitCode
            )
          )
    catch
      case exception: java.io.IOException =>
        result = Some(
          NativeCompilerFailure(
            BridgeDiagnostic(
              "NODAL-BRIDGE-PROCESS-008",
              s"bridge input staging failed: ${exception.getMessage}"
            ),
            request.executable.toString +: request.arguments,
            "",
            "",
            None
          )
        )
    finally
      temporary.foreach: directory =>
        try deleteRecursively(directory)
        catch
          case exception: java.io.IOException =>
            val exitCode = result.collect:
              case failed: NativeCompilerFailure => failed.exitCode
            result = Some(
              NativeCompilerFailure(
                BridgeDiagnostic(
                  "NODAL-BRIDGE-PROCESS-009",
                  s"temporary bridge input cleanup failed: ${exception.getMessage}"
                ),
                result.map(_.command).getOrElse(
                  request.executable.toString +: request.arguments
                ),
                result.map(_.standardOutput).getOrElse(""),
                result.map(_.standardError).getOrElse(""),
                exitCode.flatten
              )
            )
    result.getOrElse(
      failure(
        "NODAL-BRIDGE-PROCESS-010",
        "native compiler transaction produced no result",
        request.executable.toString +: request.arguments
      )
    )

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.iterator().asScala.foreach(deleteRecursively)
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def normalize(text: String): String =
    if text.isEmpty then ""
    else text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing() + "\n"

  private def failure(
      code: String,
      message: String,
      command: Vector[String]
  ): NativeCompilerFailure =
    NativeCompilerFailure(
      BridgeDiagnostic(code, message),
      command,
      "",
      "",
      None
    )
