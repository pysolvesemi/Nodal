package nodal.internal.integrations

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

private[nodal] final case class ToolProcessRequest(
    executable: Path,
    arguments: Vector[String],
    workingDirectory: Path,
    environment: Map[String, String] = Map.empty,
    timeout: Duration = Duration.ofSeconds(30),
    inheritEnvironment: Boolean = true,
    maxOutputBytes: Int = 8 * 1024 * 1024,
    containment: ToolProcessContainment = ToolProcessContainment.PlatformDefault
)

/** PlatformDefault uses Linux process groups when available and retains descendant tracking on
  * other platforms. RequireProcessGroup fails closed when the Linux launcher is unavailable.
  * Descendants preserves direct launch behavior, but cannot contain a child reparented before it is
  * observed.
  */
private[nodal] enum ToolProcessContainment:
  case PlatformDefault, RequireProcessGroup, Descendants

private[nodal] enum ToolProcessFailure:
  case InvalidRequest, ToolUnavailable, LaunchFailed, Timeout, Interrupted, OutputLimit,
    CaptureFailed

private[nodal] final case class ToolProcessResult(
    command: Vector[String],
    standardOutput: String,
    standardError: String,
    exitCode: Option[Int],
    failure: Option[ToolProcessFailure],
    elapsedMillis: Long,
    failureMessage: Option[String] = None,
    standardOutputBytes: IArray[Byte] = IArray.empty[Byte],
    standardErrorBytes: IArray[Byte] = IArray.empty[Byte],
    executedCommand: Vector[String] = Vector.empty
)

private[nodal] trait ToolProcessExecutor:
  def run(request: ToolProcessRequest): ToolProcessResult

/** Tool-neutral execution with a shared stdout/stderr byte limit. The deadline includes output
  * drain; failure cleanup has an additional bounded grace period. Raw bytes are authoritative;
  * decoded UTF-8 views do not trim or normalize output. Linux containment uses a fixed control
  * script and separate positional arguments, never interpolating caller data into shell program
  * text. A startup controller waits at most 20 ten-millisecond sleeps for acknowledgement before it
  * exits without executing the tool. This contains normal fork/exit descendants, not programs
  * deliberately escaping the session or changing credentials.
  */
private[nodal] object ToolProcessRunner extends ToolProcessExecutor:
  private val PollNanos = TimeUnit.MILLISECONDS.toNanos(10)
  private val CleanupNanos = TimeUnit.MILLISECONDS.toNanos(250)
  private val SetSid = Path.of("/usr/bin/setsid")
  private val Kill = Path.of("/bin/kill")
  private val Shell = Path.of("/bin/sh")
  private val Sleep = Path.of("/bin/sleep")
  private val LaunchScript =
    "control=$1; ready=$2; shift 2; printf '%s' \"$$\" > \"$control\" || exit 125; " +
      "attempts=0; while [ \"$attempts\" -lt 20 ]; do " +
      "if [ -f \"$ready\" ]; then exec \"$@\"; fi; " +
      "/bin/sleep 0.01; attempts=$((attempts+1)); done; " +
      "printf expired > \"$control.expired\"; exit 125"

  override def run(request: ToolProcessRequest): ToolProcessResult =
    val started = System.nanoTime()
    val command = request.executable.toString +: request.arguments
    var executedCommand = command
    var controlDirectory: Option[Path] = None
    def failed(kind: ToolProcessFailure, message: String): ToolProcessResult =
      ToolProcessResult(
        command,
        "",
        "",
        None,
        Some(kind),
        elapsedMillis(started),
        Some(message),
        executedCommand = executedCommand
      )

    validate(request) match
      case Some((kind, message)) => failed(kind, message)
      case None if Thread.currentThread().isInterrupted =>
        failed(ToolProcessFailure.Interrupted, "tool process interrupted before launch")
      case None =>
        var result =
          try
            val grouped = request.containment != ToolProcessContainment.Descendants &&
              groupAvailable
            val groupFile = if grouped then
              val directory =
                Files.createTempDirectory(request.workingDirectory, "nodal-process-").toAbsolutePath
              controlDirectory = Some(directory)
              Some(directory.resolve("group.pid"))
            else None
            groupFile.foreach: file =>
              // --fork makes the control script the session leader regardless of the launcher PID's
              // existing process-group membership. Its PID handshake remains after immediate exit.
              executedCommand = Vector(
                SetSid.toString,
                "--fork",
                "--wait",
                "--",
                Shell.toString,
                "-c",
                LaunchScript,
                "nodal-process",
                file.toString,
                file.resolveSibling("ready").toString
              ) ++ command
            val builder = new ProcessBuilder(executedCommand*)
            builder.directory(request.workingDirectory.toFile)
            val environment = builder.environment()
            if !request.inheritEnvironment then environment.clear()
            request.environment.toVector.sortBy(_._1).foreach:
              case (key, value) => environment.put(key, value)
            execute(builder.start(), request, command, executedCommand, groupFile, started)
          catch
            case exception: IOException =>
              failed(
                ToolProcessFailure.LaunchFailed,
                s"tool process launch failed: ${exception.getMessage}"
              )
            case exception: IllegalArgumentException =>
              failed(
                ToolProcessFailure.InvalidRequest,
                s"invalid tool process request: ${exception.getMessage}"
              )
            case exception: SecurityException =>
              failed(
                ToolProcessFailure.LaunchFailed,
                s"tool process launch denied: ${exception.getMessage}"
              )
        controlDirectory.foreach: directory =>
          try
            val _ = Files.deleteIfExists(directory.resolve("group.pid"))
            val _ = Files.deleteIfExists(directory.resolve("ready"))
            val _ = Files.deleteIfExists(directory.resolve("group.pid.expired"))
            val _ = Files.deleteIfExists(directory)
          catch
            case exception: IOException =>
              result = result.copy(
                failure = Some(ToolProcessFailure.CaptureFailed),
                failureMessage = Some(s"tool control cleanup failed: ${exception.getMessage}")
              )
        result

  private def groupAvailable: Boolean =
    System.getProperty("os.name") == "Linux" &&
      Seq(SetSid, Kill, Shell, Sleep).forall(path =>
        Files.isRegularFile(path) && Files.isExecutable(path)
      )

  private def validate(request: ToolProcessRequest): Option[(ToolProcessFailure, String)] =
    if !request.executable.isAbsolute then
      Some((ToolProcessFailure.InvalidRequest, "tool executable must be an absolute path"))
    else if !Files.isRegularFile(request.executable) || !Files.isExecutable(request.executable) then
      Some((ToolProcessFailure.ToolUnavailable, "tool executable is missing or not executable"))
    else if !Files.isDirectory(request.workingDirectory) then
      Some((ToolProcessFailure.InvalidRequest, "tool working directory does not exist"))
    else if request.timeout.isZero || request.timeout.isNegative then
      Some((ToolProcessFailure.InvalidRequest, "tool timeout must be positive"))
    else if request.maxOutputBytes <= 0 then
      Some((ToolProcessFailure.InvalidRequest, "tool output byte limit must be positive"))
    else if request.containment == ToolProcessContainment.RequireProcessGroup && !groupAvailable
    then
      Some((
        ToolProcessFailure.ToolUnavailable,
        "Linux process-group containment tools are unavailable"
      ))
    else None

  private def execute(
      process: Process,
      request: ToolProcessRequest,
      command: Vector[String],
      executedCommand: Vector[String],
      groupFile: Option[Path],
      started: Long
  ): ToolProcessResult =
    val budget = new CaptureBudget(request.maxOutputBytes)
    val stdout = capture(process.getInputStream, budget)
    val stderr = capture(process.getErrorStream, budget)
    val descendants = mutable.LinkedHashMap.empty[Long, ProcessHandle]
    var group: Option[Long] = None
    var acknowledged = false
    var failure: Option[ToolProcessFailure] = None
    var message: Option[String] = None
    var interrupted = false
    var completed = false
    val timeoutNanos =
      try request.timeout.toNanos
      catch case _: ArithmeticException => Long.MaxValue
    def observeFailure(): Unit =
      if Thread.interrupted() then
        interrupted = true
        failure = Some(ToolProcessFailure.Interrupted)
        message = Some("tool process interrupted")
      else if budget.exceeded.get() then
        failure = Some(ToolProcessFailure.OutputLimit)
        message = Some(s"tool output exceeded ${request.maxOutputBytes} bytes")
      else if stdout.error.get().nonEmpty || stderr.error.get().nonEmpty then
        failure = Some(ToolProcessFailure.CaptureFailed)
        message = Some("tool output capture failed")
    try
      process.getOutputStream.close()
      while !completed && failure.isEmpty do
        rememberDescendants(process, descendants)
        if group.isEmpty then group = groupFile.flatMap(readGroup)
        observeFailure()
        if failure.isEmpty && System.nanoTime() - started >= timeoutNanos then
          failure = Some(ToolProcessFailure.Timeout)
          message = Some(s"tool process timed out after ${request.timeout}")
        if failure.isEmpty && !acknowledged && group.nonEmpty && process.isAlive then
          groupFile.foreach: file =>
            val _ =
              Files.writeString(file.resolveSibling("ready"), "run", StandardCharsets.US_ASCII)
          acknowledged = true
        if failure.isEmpty && !process.isAlive && !stdout.thread.isAlive && !stderr.thread.isAlive
        then
          // Terminated reader threads establish settled capture state. Re-read their flags afterwards:
          // a last-byte overflow or read failure must never race into an accepted success.
          observeFailure()
          if failure.isEmpty &&
            groupFile.exists(file => Files.exists(file.resolveSibling("group.pid.expired")))
          then
            failure = Some(ToolProcessFailure.LaunchFailed)
            message =
              Some("tool process-group startup acknowledgement exceeded its 200 ms allowance")
          if failure.isEmpty && groupFile.nonEmpty && group.isEmpty then
            failure = Some(ToolProcessFailure.CaptureFailed)
            message = Some("tool process-group handshake is absent or invalid")
          var groupDrained = true
          if failure.isEmpty then
            group.foreach: id =>
              signalGroup(id, "0", 50) match
                case Some(active) => groupDrained = !active
                case None =>
                  failure = Some(ToolProcessFailure.CaptureFailed)
                  message = Some("tool process-group status check timed out")
          if failure.isEmpty && groupDrained then
            observeFailure()
            if failure.isEmpty && System.nanoTime() - started >= timeoutNanos then
              failure = Some(ToolProcessFailure.Timeout)
              message = Some(s"tool process timed out after ${request.timeout}")
            completed = failure.isEmpty
        if !completed && failure.isEmpty then
          val remaining = timeoutNanos - (System.nanoTime() - started)
          if remaining <= 0 then
            failure = Some(ToolProcessFailure.Timeout)
            message = Some(s"tool process timed out after ${request.timeout}")
          else TimeUnit.NANOSECONDS.sleep(math.min(PollNanos, remaining))
    catch
      case _: InterruptedException =>
        interrupted = true
        failure = Some(ToolProcessFailure.Interrupted)
        message = Some("tool process interrupted")
      case exception: IOException =>
        failure = Some(ToolProcessFailure.CaptureFailed)
        message = Some(s"tool process control failed: ${exception.getMessage}")
    finally
      if failure.nonEmpty then
        if group.isEmpty then
          try group = groupFile.flatMap(readGroup)
          catch case _: IOException => ()
        interrupted = terminate(process, descendants, group, groupFile, stdout, stderr) ||
          interrupted
        if stdout.thread.isAlive || stderr.thread.isAlive then
          message = Some(message.getOrElse("tool process failed") +
            "; output readers did not settle during cleanup")
      budget.freeze()
      if interrupted then Thread.currentThread().interrupt()

    val outputBytes = stdout.buffer.toByteArray
    val errorBytes = stderr.buffer.toByteArray
    ToolProcessResult(
      command,
      new String(outputBytes, StandardCharsets.UTF_8),
      new String(errorBytes, StandardCharsets.UTF_8),
      if completed then Some(process.exitValue()) else None,
      failure,
      elapsedMillis(started),
      message,
      IArray.unsafeFromArray(outputBytes),
      IArray.unsafeFromArray(errorBytes),
      executedCommand
    )

  private def readGroup(file: Path): Option[Long] =
    if !Files.exists(file) then None
    else
      val input = Files.newInputStream(file)
      val text = try new String(input.readNBytes(32), StandardCharsets.US_ASCII)
      finally input.close()
      if text.nonEmpty && text.length <= 20 &&
        text.forall(character => character >= '0' && character <= '9')
      then
        text.toLongOption.filter(_ > 1)
      else None

  private def signalGroup(group: Long, signal: String, timeoutMillis: Long): Option[Boolean] =
    val helper = new ProcessBuilder(Kill.toString, s"-$signal", "--", s"-$group")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try
      helper.getOutputStream.close()
      if helper.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) then Some(helper.exitValue() == 0)
      else
        val _ = helper.destroyForcibly()
        None
    finally
      if helper.isAlive then
        val _ = helper.destroyForcibly()

  private def rememberDescendants(
      process: Process,
      descendants: mutable.LinkedHashMap[Long, ProcessHandle]
  ): Unit =
    val stream = process.descendants()
    try stream.iterator().asScala.foreach(handle => descendants.update(handle.pid(), handle))
    finally stream.close()

  private def terminate(
      process: Process,
      descendants: mutable.LinkedHashMap[Long, ProcessHandle],
      group: Option[Long],
      groupFile: Option[Path],
      stdout: Capture,
      stderr: Capture
  ): Boolean =
    val started = System.nanoTime()
    var interrupted = Thread.interrupted()
    groupFile.foreach: file =>
      try
        val _ = Files.deleteIfExists(file.resolveSibling("ready"))
      catch case _: IOException => ()
    rememberDescendants(process, descendants)
    val handles = descendants.values.toVector.reverse :+ process.toHandle
    var knownGroup = group
    def signal(value: String): Unit =
      // A timeout can precede the handshake. Exec cannot precede its write, so re-reading after
      // stopping tracked launchers captures any group that could have spawned an orphan meanwhile.
      if knownGroup.isEmpty then
        try knownGroup = groupFile.flatMap(readGroup)
        catch case _: IOException => ()
      knownGroup.foreach: id =>
        try
          val _ = signalGroup(id, value, 50)
        catch
          case _: InterruptedException => interrupted = true
          case _: IOException => ()
    signal("TERM")
    handles.foreach: handle =>
      if handle.isAlive then
        val _ = handle.destroy()
    def pause(): Unit =
      try TimeUnit.NANOSECONDS.sleep(PollNanos)
      catch case _: InterruptedException => interrupted = true
    while handles.exists(_.isAlive) && System.nanoTime() - started < CleanupNanos / 3 do pause()
    signal("KILL")
    handles.foreach: handle =>
      if handle.isAlive then
        val _ = handle.destroyForcibly()
    stdout.closeAsync()
    stderr.closeAsync()
    while (stdout.thread.isAlive || stderr.thread.isAlive || handles.exists(_.isAlive)) &&
      System.nanoTime() - started < CleanupNanos
    do pause()
    interrupted

  private final class CaptureBudget(maximum: Int):
    val exceeded = new AtomicBoolean(false)
    private var retained = 0
    private var frozen = false
    def freeze(): Unit = synchronized:
      frozen = true
    def append(buffer: ByteArrayOutputStream, bytes: Array[Byte], count: Int): Unit = synchronized:
      if !frozen then
        val accepted = math.min(count, maximum - retained)
        if accepted > 0 then buffer.write(bytes, 0, accepted)
        retained += accepted
        if accepted < count then exceeded.set(true)

  private final case class Capture(
      thread: Thread,
      input: InputStream,
      buffer: ByteArrayOutputStream,
      closing: AtomicBoolean,
      error: AtomicReference[Option[IOException]]
  ):
    def closeAsync(): Unit =
      closing.set(true)
      val _ = Thread.ofVirtual().start(() => closeQuietly(input))

  private def capture(input: InputStream, budget: CaptureBudget): Capture =
    val buffer = new ByteArrayOutputStream()
    val closing = new AtomicBoolean(false)
    val error = new AtomicReference[Option[IOException]](None)
    val thread = Thread.ofVirtual().start(
      new Runnable:
        override def run(): Unit =
          try
            val bytes = new Array[Byte](8192)
            var count = input.read(bytes)
            while count != -1 && !closing.get() && !budget.exceeded.get() do
              budget.append(buffer, bytes, count)
              count = input.read(bytes)
          catch
            case exception: IOException =>
              if !closing.get() then error.set(Some(exception))
          finally closeQuietly(input)
    )
    Capture(thread, input, buffer, closing, error)

  private def closeQuietly(input: InputStream): Unit =
    try input.close()
    catch case _: IOException => ()

  private def elapsedMillis(started: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
