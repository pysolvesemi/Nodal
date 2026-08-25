package nodal.bridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

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
    validate(request).getOrElse(execute(document, request))

  private def validate(
      request: NativeCompilerRequest
  ): Option[NativeCompilerFailure] =
    val command = request.executable.toString +: request.arguments
    if !request.executable.isAbsolute then
      Some(failure("NODAL-BRIDGE-PROCESS-001", "executable must be absolute", command))
    else if !Files.isRegularFile(request.executable) ||
        !Files.isExecutable(request.executable)
    then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-002",
          "executable is missing or not executable",
          command
        )
      )
    else if !Files.isDirectory(request.workingDirectory) then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-003",
          "working directory does not exist",
          command
        )
      )
    else if request.timeout.isZero || request.timeout.isNegative then
      Some(
        failure(
          "NODAL-BRIDGE-PROCESS-004",
          "timeout must be positive",
          command
        )
      )
    else None

  private def execute(
      document: NodalMlirDocument,
      request: NativeCompilerRequest
  ): NativeCompilerResult =
    var stagingDirectory: Option[Path] = None
    var result: Option[NativeCompilerResult] = None
    try
      val directory = Files.createTempDirectory(
        request.workingDirectory,
        "nodal-scala-mlir-"
      )
      stagingDirectory = Some(directory)
      val input = directory.resolve("input.mlir")
      val _ = Files.writeString(input, document.text, StandardCharsets.UTF_8)
      val command =
        request.executable.toString +: request.arguments :+ input.toString
      val builder = new ProcessBuilder(command.asJava)
      val _ = builder.directory(request.workingDirectory.toFile)
      val environment = builder.environment()
      request.environment.toVector.sortBy(_._1).foreach: (key, value) =>
        val _ = environment.put(key, value)
      val _ = environment.put("NODAL_BRIDGE_SCHEMA", document.schema)
      val _ = environment.put("NODAL_BRIDGE_VERSION", document.version.toString)
      val _ = environment.put("NODAL_PROCESS_PROTOCOL", ProtocolVersion)

      try
        val process = builder.start()
        process.getOutputStream.close()
        val stdout = capture(process.getInputStream)
        val stderr = capture(process.getErrorStream)
        val completed = process.waitFor(
          request.timeout.toMillis,
          TimeUnit.MILLISECONDS
        )
        if completed then
          stdout.thread.join()
          stderr.thread.join()
          val standardOutput = normalize(stdout.text)
          val standardError = normalize(stderr.text)
          val exitCode = process.exitValue()
          result = Some(
            if exitCode == 0 then
              NativeCompilerSuccess(command, standardOutput, standardError)
            else
              NativeCompilerFailure(
                BridgeDiagnostic(
                  "NODAL-BRIDGE-PROCESS-007",
                  s"native compiler exited with status $exitCode"
                ),
                command,
                standardOutput,
                standardError,
                Some(exitCode)
              )
          )
        else
          process.destroyForcibly()
          val _ = process.waitFor(5, TimeUnit.SECONDS)
          stdout.thread.join(1000)
          stderr.thread.join(1000)
          result = Some(
            NativeCompilerFailure(
              BridgeDiagnostic(
                "NODAL-BRIDGE-PROCESS-006",
                s"native compiler timed out after ${request.timeout.toMillis} ms"
              ),
              command,
              normalize(stdout.text),
              normalize(stderr.text),
              None
            )
          )
      catch
        case exception: java.io.IOException =>
          result = Some(
            failure(
              "NODAL-BRIDGE-PROCESS-005",
              s"native compiler launch failed: ${exception.getMessage}",
              command
            )
          )
    catch
      case exception: java.io.IOException =>
        result = Some(
          failure(
            "NODAL-BRIDGE-PROCESS-008",
            s"input staging failed: ${exception.getMessage}",
            request.executable.toString +: request.arguments
          )
        )
    finally
      stagingDirectory.foreach: directory =>
        try deleteRecursively(directory)
        catch
          case exception: java.io.IOException =>
            result = Some(
              NativeCompilerFailure(
                BridgeDiagnostic(
                  "NODAL-BRIDGE-PROCESS-009",
                  s"temporary input cleanup failed: ${exception.getMessage}"
                ),
                result.map(_.command).getOrElse(
                  request.executable.toString +: request.arguments
                ),
                result.map(_.standardOutput).getOrElse(""),
                result.map(_.standardError).getOrElse(""),
                None
              )
            )
    result.getOrElse(
      failure(
        "NODAL-BRIDGE-PROCESS-010",
        "native compiler transaction produced no result",
        request.executable.toString +: request.arguments
      )
    )

  private final case class Capture(
      thread: Thread,
      buffer: ByteArrayOutputStream
  ):
    def text: String = buffer.toString(StandardCharsets.UTF_8)

  private def capture(input: InputStream): Capture =
    val buffer = ByteArrayOutputStream()
    val thread = Thread.ofVirtual().start(
      new Runnable:
        override def run(): Unit =
          try
            val _ = input.transferTo(buffer)
          finally input.close()
    )
    Capture(thread, buffer)

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
