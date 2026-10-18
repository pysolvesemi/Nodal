package nodal.internal.cli

import java.io.PrintStream
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import nodal.internal.integrations.*

/** Production file-to-compiler entry point; source elaboration remains a separate owner. */
object OpenVafCheck:
  private val Usage =
    """OpenVAF compile validation
      |--model FILE --executable FILE --evidence-dir DIR --profile ID
      |[--map-authored-source] [--timeout-seconds N] [--version-timeout-seconds N]
      |[--source-revision SHA] [--normalized-mlir FILE]
      |--list-profiles
      |--help
      |No model rewriting, hierarchy flattening, cache or numerical execution.
      |""".stripMargin
  private val Required = Set("--model", "--executable", "--evidence-dir", "--profile")
  private val Values = Required ++
    Set("--timeout-seconds", "--version-timeout-seconds", "--source-revision", "--normalized-mlir")
  private val MaxModelBytes = 16L * 1024 * 1024

  private def boundedBytes(path: Path): Vector[Byte] =
    val stream = Files.newInputStream(path)
    val bytes = try stream.readNBytes(MaxModelBytes.toInt + 1).toVector
    finally stream.close()
    require(bytes.size <= MaxModelBytes, "input exceeds the 16 MiB CLI input bound")
    bytes

  def main(arguments: Array[String]): Unit =
    val status = run(arguments, System.out, System.err)
    if status != 0 then sys.exit(status)

  private[nodal] def run(arguments: Array[String], out: PrintStream, err: PrintStream): Int =
    if arguments.toVector == Vector("--help") then
      out.print(Usage)
      0
    else if arguments.toVector == Vector("--list-profiles") then
      OpenVafProfiles.all.sortBy(_.id).foreach: profile =>
        out.println(s"${profile.id}\t${profile.version}\t${profile.executableSha256}")
      0
    else
      try
        var options = Map.empty[String, String]
        var mapSource = false
        var index = 0
        while index < arguments.length do
          val key = arguments(index)
          if key == "--map-authored-source" then
            require(!mapSource, "duplicate --map-authored-source")
            mapSource = true
            index += 1
          else
            require(Values.contains(key), s"unknown option: $key")
            require(!options.contains(key), s"duplicate option: $key")
            require(index + 1 < arguments.length, s"missing value for $key")
            options += key -> arguments(index + 1)
            index += 2
        require(Required.subsetOf(options.keySet), "missing required options")
        def seconds(key: String, default: Long, maximum: Long): Duration =
          val value = options.get(key).map(_.toLong).getOrElse(default)
          require(value > 0 && value <= maximum, s"$key must be between 1 and $maximum")
          Duration.ofSeconds(value)
        val model = Paths.get(options("--model")).toAbsolutePath.normalize()
        require(Files.isRegularFile(model), "model must be a regular file")
        require(Files.size(model) <= MaxModelBytes, "model exceeds the 16 MiB CLI input bound")
        val bytes = boundedBytes(model)
        require(bytes.size <= MaxModelBytes, "model changed beyond the 16 MiB CLI input bound")
        val request = OpenVafCompileRequest(
          modelBytes = bytes,
          logicalFilename = model.getFileName.toString,
          executable = Paths.get(options("--executable")).toAbsolutePath.normalize(),
          evidenceDirectory = Paths.get(options("--evidence-dir")).toAbsolutePath.normalize(),
          profileId = options("--profile"),
          versionTimeout = seconds("--version-timeout-seconds", 5, 60),
          compileTimeout = seconds("--timeout-seconds", 30, 3600),
          sourceMap = Option.when(mapSource)(
            OpenVafSourceMapBinding(OpenVafDiagnosticMapper.sha256(bytes))
          ),
          provenance = OpenVafProvenance(
            sourceRevision = options.get("--source-revision"),
            normalizedMlirSha256 = options.get("--normalized-mlir").map: filename =>
              val path = Paths.get(filename).toAbsolutePath.normalize()
              require(Files.isRegularFile(path), "normalized MLIR must be a regular file")
              require(Files.size(path) <= MaxModelBytes, "normalized MLIR exceeds 16 MiB")
              OpenVafDiagnosticMapper.sha256(boundedBytes(path))
          )
        )
        val result = OpenVafCompiler.compile(request)
        val stream = if result.outcome == OpenVafOutcome.Compiled then out else err
        stream.println(s"${result.diagnosticCode}: ${result.outcome}: ${result.message}")
        result.evidenceDirectory.foreach: directory =>
          val receipt = directory.resolve("receipt.json")
          if Files.isRegularFile(receipt) then stream.println(s"receipt: $receipt")
          else stream.println(s"evidence directory: $directory")
        if result.outcome == OpenVafOutcome.Compiled then 0 else 1
      catch
        case exception: IllegalArgumentException =>
          err.println(s"NODAL-OPENVAF-CLI-001: ${exception.getMessage}")
          err.print(Usage)
          2
        case exception: java.io.IOException =>
          err.println(s"NODAL-OPENVAF-CLI-002: ${exception.getMessage}")
          1
