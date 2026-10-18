package nodal.internal.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import nodal.internal.bridge.*
import nodal.internal.integrations.*

import utest.*

object ToolProcessRunnerTests extends TestSuite:
  private def withDirectory(body: Path => Unit): Unit =
    val root = Files.createDirectories(Path.of(".work", "tool-process-tests")).toAbsolutePath
    val directory = Files.createTempDirectory(root, "case-")
    try body(directory)
    finally delete(directory)

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def runnable(pid: Long): Boolean =
    val handle = ProcessHandle.of(pid)
    if !handle.isPresent || !handle.get().isAlive then false
    else
      // Linux may retain a killed orphan as a zombie until PID 1 reaps it. It cannot execute or
      // retain a pipe writer; a live/runnable survivor is the cleanup failure this check detects.
      try
        val stat = Files.readString(Path.of(s"/proc/$pid/stat"))
        val state = stat.substring(stat.lastIndexOf(')') + 2).takeWhile(_ != ' ')
        state != "Z" && state != "X"
      catch case _: java.nio.file.NoSuchFileException => false

  private def request(directory: Path, script: String): ToolProcessRequest =
    ToolProcessRequest(
      executable = Path.of("/bin/sh"),
      arguments = Vector("-c", script, "tool-process-test"),
      workingDirectory = directory,
      timeout = Duration.ofSeconds(5)
    )

  val tests: Tests = Tests:
    test("missing executable and invalid requests fail before launch"):
      withDirectory: directory =>
        val base = request(directory, "touch launched")
        val missing = ToolProcessRunner.run(base.copy(executable = directory.resolve("missing")))
        val relative = ToolProcessRunner.run(base.copy(executable = Path.of("sh")))
        val noDirectory =
          ToolProcessRunner.run(base.copy(workingDirectory = directory.resolve("missing")))
        val zero = ToolProcessRunner.run(base.copy(timeout = Duration.ZERO))
        val negative = ToolProcessRunner.run(base.copy(timeout = Duration.ofMillis(-1)))
        val noBudget = ToolProcessRunner.run(base.copy(maxOutputBytes = 0))
        assert(missing.failure.contains(ToolProcessFailure.ToolUnavailable))
        Seq(relative, noDirectory, zero, negative, noBudget).foreach: result =>
          assert(result.failure.contains(ToolProcessFailure.InvalidRequest))
          assert(result.exitCode.isEmpty)
        assert(!Files.exists(directory.resolve("launched")))

    test("launch failures remain distinct from executable discovery"):
      withDirectory: directory =>
        val executable = directory.resolve("bad-interpreter")
        val _ = Files.writeString(executable, "#!/nodal-no-such-interpreter\n")
        assert(executable.toFile.setExecutable(true))
        val result = ToolProcessRunner.run(request(
          directory,
          ""
        ).copy(executable = executable, containment = ToolProcessContainment.Descendants))
        assert(result.failure.contains(ToolProcessFailure.LaunchFailed))
        assert(result.exitCode.isEmpty)

    test("native bridge retains protocol environment and normalization"):
      withDirectory: directory =>
        val document = ScalaToMlirBridge.lower(new BridgeTop)
        val result = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            executable = Path.of("/bin/sh"),
            arguments = Vector(
              "-c",
              "printf '%s|%s|%s\\r\\n  ' \"$NODAL_BRIDGE_SCHEMA\" \"$NODAL_BRIDGE_VERSION\" \"$NODAL_PROCESS_PROTOCOL\"; printf 'warning\\r' >&2"
            ),
            workingDirectory = directory,
            environment = Map("NODAL_PROCESS_PROTOCOL" -> "wrong"),
            timeout = Duration.ofSeconds(5)
          )
        ).asInstanceOf[NativeCompilerSuccess]
        assert(result.normalizedMlir == s"${document.schema}|${document.version}|1\n")
        assert(result.standardError == "warning\n")
        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()

    test("argument vectors and raw output survive success and error"):
      withDirectory: directory =>
        val literal = "spaces ; $(touch injected) 'quoted' α\nlast"
        val base = request(directory, "printf '%s' \"$1\"; printf 'error\\r\\n  ' >&2")
          .copy(arguments =
            Vector(
              "-c",
              "printf '%s' \"$1\"; printf 'error\\r\\n  ' >&2",
              "tool-process-test",
              literal
            )
          )
        val success = ToolProcessRunner.run(base)
        assert(success.failure.isEmpty)
        assert(success.exitCode.contains(0))
        assert(success.command == base.executable.toString +: base.arguments)
        assert(success.standardOutput == literal)
        assert(success.standardError == "error\r\n  ")
        assert(!Files.exists(directory.resolve("injected")))
        val failure = ToolProcessRunner.run(request(
          directory,
          "printf 'partial\\r'; printf 'failed' >&2; exit 7"
        ))
        assert(failure.failure.isEmpty)
        assert(failure.exitCode.contains(7))
        assert(failure.standardOutput == "partial\r")
        assert(failure.standardError == "failed")

    test("explicit environment replacement does not leak inherited variables"):
      withDirectory: directory =>
        val result = ToolProcessRunner.run(
          request(directory, "printf '%s|%s' \"$NODAL_TEST_VALUE\" \"${HOME-unset}\"")
            .copy(
              environment = Map("NODAL_TEST_VALUE" -> "literal value"),
              inheritEnvironment = false
            )
        )
        assert(result.failure.isEmpty)
        assert(result.standardOutput == "literal value|unset")

    test("concurrent stderr capture drains beyond a pipe buffer"):
      withDirectory: directory =>
        val result = ToolProcessRunner.run(
          request(
            directory,
            "i=0; while [ \"$i\" -lt 8192 ]; do printf '0123456789abcdef' >&2; i=$((i+1)); done; printf done"
          )
        )
        assert(result.failure.isEmpty)
        assert(result.exitCode.contains(0))
        assert(result.standardOutput == "done")
        assert(result.standardError == "0123456789abcdef" * 8192)

    test("output limits fail closed and exact limit can succeed"):
      withDirectory: directory =>
        val exact =
          ToolProcessRunner.run(request(directory, "printf 12345678").copy(maxOutputBytes = 8))
        assert(exact.failure.isEmpty)
        assert(exact.standardOutput == "12345678")
        val overflow = ToolProcessRunner.run(
          request(directory, "while :; do printf 1234567890abcdef >&2; done").copy(maxOutputBytes =
            1024
          )
        )
        assert(overflow.failure.contains(ToolProcessFailure.OutputLimit))
        assert(overflow.exitCode.isEmpty)
        assert(overflow.standardOutput.length + overflow.standardError.length <= 1024)
        assert(overflow.elapsedMillis < 2000)

    test("fast successful exits cannot hide a final-byte output overflow"):
      withDirectory: directory =>
        (0 until 40).foreach: _ =>
          val result =
            ToolProcessRunner.run(request(directory, "printf 123456789").copy(maxOutputBytes = 8))
          assert(result.failure.contains(ToolProcessFailure.OutputLimit))
          assert(result.exitCode.isEmpty)
          assert(IArray.genericWrapArray(result.standardOutputBytes).toSeq ==
            "12345678".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq)

    test("raw capture preserves invalid UTF8 NUL and a split multibyte boundary"):
      withDirectory: directory =>
        val raw = ToolProcessRunner.run(request(
          directory,
          "printf '\\377\\000\\303\\251'; printf '\\200\\015' >&2"
        ))
        assert(raw.failure.isEmpty)
        assert(IArray.genericWrapArray(raw.standardOutputBytes).toSeq ==
          Seq(255, 0, 195, 169).map(_.toByte))
        assert(IArray.genericWrapArray(raw.standardErrorBytes).toSeq == Seq(128, 13).map(_.toByte))
        val truncated =
          ToolProcessRunner.run(request(directory, "printf '\\303\\251'").copy(maxOutputBytes = 1))
        assert(truncated.failure.contains(ToolProcessFailure.OutputLimit))
        assert(IArray.genericWrapArray(truncated.standardOutputBytes).toSeq == Seq(195.toByte))

    test("expired startup deadlines cannot acknowledge a late controller"):
      withDirectory: directory =>
        (0 until 5).foreach: _ =>
          val result = ToolProcessRunner.run(
            request(directory, "printf started > started")
              .copy(
                timeout = Duration.ofNanos(1),
                containment = ToolProcessContainment.RequireProcessGroup
              )
          )
          assert(result.failure.contains(ToolProcessFailure.Timeout))
          assert(result.elapsedMillis < 2000)
        Thread.sleep(300)
        assert(!Files.exists(directory.resolve("started")))
        val entries = Files.list(directory)
        try assert(!entries.iterator().hasNext)
        finally entries.close()

    test("process groups contain a descendant after immediate launcher exit"):
      withDirectory: directory =>
        try
          val result = ToolProcessRunner.run(
            request(directory, "sleep 20 & printf '%s' \"$!\" > child.pid; exit 0")
              .copy(
                timeout = Duration.ofMillis(200),
                containment = ToolProcessContainment.RequireProcessGroup
              )
          )
          assert(result.failure.contains(ToolProcessFailure.Timeout))
          assert(result.elapsedMillis < 2000)
          assert(result.executedCommand.head == "/usr/bin/setsid")
          assert(result.command.head == "/bin/sh")
          val child = Files.readString(directory.resolve("child.pid")).toLong
          assert(!runnable(child))
          assert(!result.failureMessage.exists(_.contains("readers did not settle")))
        finally
          if Files.exists(directory.resolve("child.pid")) then
            val child = ProcessHandle.of(Files.readString(directory.resolve("child.pid")).toLong)
            if child.isPresent && child.get().isAlive then
              val _ = child.get().destroyForcibly()

    test("timeout terminates child processes and retains partial output"):
      withDirectory: directory =>
        val result = ToolProcessRunner.run(
          request(directory, "printf started; sleep 5").copy(timeout = Duration.ofMillis(150))
        )
        assert(result.failure.contains(ToolProcessFailure.Timeout))
        assert(result.exitCode.isEmpty)
        assert(result.standardOutput == "started")
        assert(result.elapsedMillis < 2000)

    test("deadline includes pipes inherited by a descendant after launcher exit"):
      withDirectory: directory =>
        val result = ToolProcessRunner.run(
          request(directory, "sleep 5 & printf '%s' \"$!\" > child.pid; sleep 0.15; exit 0")
            .copy(timeout = Duration.ofMillis(300))
        )
        try
          assert(result.failure.contains(ToolProcessFailure.Timeout))
          assert(result.exitCode.isEmpty)
          assert(result.elapsedMillis < 2000)
        finally
          if Files.exists(directory.resolve("child.pid")) then
            val child = ProcessHandle.of(Files.readString(directory.resolve("child.pid")).toLong)
            if child.isPresent && child.get().isAlive then
              val _ = child.get().destroyForcibly()

    test("interruption is typed and preserves the calling thread flag"):
      withDirectory: directory =>
        val result = new AtomicReference[ToolProcessResult]()
        val retainedInterrupt = new AtomicBoolean(false)
        val started = new CountDownLatch(1)
        val thread = Thread.ofVirtual().start(
          new Runnable:
            override def run(): Unit =
              started.countDown()
              result.set(ToolProcessRunner.run(request(directory, "printf ready; sleep 5")))
              retainedInterrupt.set(Thread.currentThread().isInterrupted)
        )
        assert(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        thread.interrupt()
        thread.join(2000)
        assert(!thread.isAlive)
        assert(result.get().failure.contains(ToolProcessFailure.Interrupted))
        assert(retainedInterrupt.get())

    test("preexisting interruption prevents launch without clearing the flag"):
      withDirectory: directory =>
        val result = new AtomicReference[ToolProcessResult]()
        val retainedInterrupt = new AtomicBoolean(false)
        val thread = Thread.ofVirtual().start(
          new Runnable:
            override def run(): Unit =
              Thread.currentThread().interrupt()
              result.set(ToolProcessRunner.run(request(directory, "touch launched")))
              retainedInterrupt.set(Thread.currentThread().isInterrupted)
        )
        thread.join(2000)
        assert(!thread.isAlive)
        assert(result.get().failure.contains(ToolProcessFailure.Interrupted))
        assert(retainedInterrupt.get())
        assert(!Files.exists(directory.resolve("launched")))
