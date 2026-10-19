package nodal.internal.integrations

import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest

/** Verify and stage the exact compiled OSDI artifact at each consumer's trust boundary. */
private[nodal] object OsdiArtifact:
  private val MaxArtifactBytes = 64L * 1024 * 1024
  private def sha256(value: String): Boolean = value.matches("[0-9a-f]{64}")

  def stage(
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
            try
              val output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)
              try
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val buffer = new Array[Byte](65536)
                var read = input.read(buffer)
                while read != -1 && count <= artifact.size do
                  count += read
                  if count <= artifact.size then
                    hash.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                  if count <= artifact.size then read = input.read(buffer)
                if count != artifact.size ||
                  hash.digest().map(b => f"${b & 0xff}%02x").mkString != artifact.sha256
                then
                  Left("compiled artifact content does not match the compile result")
                else Right(())
              finally output.close()
            finally input.close()
