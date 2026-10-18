package nodal.internal.integrations

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

import scala.util.control.NonFatal

private[nodal] final case class OpenVafGeneratedPosition(path: String, line: Int, column: Int)

/** Statement-level authored context, never a claim about an expression subposition. */
private[nodal] final case class OpenVafAuthoredSpan(
    semanticIdentity: String,
    path: String,
    startLine: Long,
    startColumn: Long,
    endLine: Long,
    endColumn: Long
)

private[nodal] final case class OpenVafStatementMapping(
    generatedLine: Int,
    firstColumn: Int,
    lastColumn: Int,
    authored: OpenVafAuthoredSpan
)

private[nodal] enum OpenVafSourceMapping:
  case NotRequested
  case Valid(modelSha256: String, statements: Vector[OpenVafStatementMapping])
  case Invalid(modelSha256: String, errors: Vector[String])

private[nodal] final case class OpenVafDiagnostic(
    severity: String,
    message: String,
    generated: Option[OpenVafGeneratedPosition],
    authored: Option[OpenVafAuthoredSpan]
)

private[nodal] object OpenVafDiagnosticMapper:
  private val MarkerPrefix = "// nodal-source-"
  private val V1Prefix = "// nodal-source-v1: "
  private val Location = "^\\s*--> (.*):([0-9]+):([0-9]+)\\s*$".r
  private val Diagnostic = "^(error|warning|note)(?:\\[[^]]+\\])?: (.*)$".r
  private val Ansi = "\\u001b\\[[0-9;]*[A-Za-z]".r

  def sha256(bytes: Vector[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes.toArray).map(b => f"${b & 0xff}%02x").mkString

  def decodeUtf8(bytes: Vector[Byte]): Either[String, String] =
    try
      Right(StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toArray)).toString)
    catch case NonFatal(_) => Left("model is not valid UTF-8")

  def parse(bytes: Vector[Byte], binding: OpenVafSourceMapBinding): OpenVafSourceMapping =
    val digest = sha256(bytes)
    if digest != binding.modelSha256 then
      OpenVafSourceMapping.Invalid(
        digest,
        Vector("source-map model SHA-256 does not match exact bytes")
      )
    else
      decodeUtf8(bytes) match
        case Left(message) => OpenVafSourceMapping.Invalid(digest, Vector(message))
        case Right(model) => parseLines(model, digest)

  private def parseLines(model: String, digest: String): OpenVafSourceMapping =
    val lines = model.split("\\r?\\n", -1).toVector
    val errors = Vector.newBuilder[String]
    val statements = Vector.newBuilder[OpenVafStatementMapping]
    val seen = scala.collection.mutable.HashMap.empty[String, OpenVafAuthoredSpan]
    lines.zipWithIndex.foreach: (line, index) =>
      val marker = line.trim
      if marker.startsWith(MarkerPrefix) then
        val parsed = if marker.startsWith(V1Prefix) then parseFields(marker.drop(V1Prefix.length))
        else Left("unknown source marker version or malformed header")
        parsed match
          case Left(message) => errors += s"line ${index + 1}: $message"
          case Right(span) =>
            if seen.get(span.semanticIdentity).exists(_ != span) then
              errors += s"line ${index + 1}: conflicting source semantic identity"
            else if index + 1 >= lines.size || !singleStatement(lines(index + 1)) then
              errors += s"line ${index + 1}: source marker must precede one physical statement line"
            else
              seen.put(span.semanticIdentity, span)
              val statement = lines(index + 1)
              statements += OpenVafStatementMapping(
                index + 2,
                statement.takeWhile(_.isWhitespace).length + 1,
                statement.length + 1,
                span
              )
    val failures = errors.result()
    if failures.nonEmpty then OpenVafSourceMapping.Invalid(digest, failures)
    else OpenVafSourceMapping.Valid(digest, statements.result())

  private def parseFields(value: String): Either[String, OpenVafAuthoredSpan] =
    val fields = value.split("\\|", -1).toVector
    if fields.size != 6 then Left("source marker requires exactly six fields")
    else
      for
        semantic <- decodeField(fields(0))
        path <- decodeField(fields(1))
        positions <-
          val parsed = fields.drop(2).map(_.toLongOption.filter(_ > 0))
          if parsed.forall(_.nonEmpty) then Right(parsed.flatten)
          else Left("source marker positions must be positive 64-bit integers")
        span <-
          val Vector(startLine, startColumn, endLine, endColumn) = positions: @unchecked
          if endLine < startLine || (endLine == startLine && endColumn < startColumn) then
            Left("source marker span is reversed")
          else
            Right(OpenVafAuthoredSpan(semantic, path, startLine, startColumn, endLine, endColumn))
      yield span

  private def decodeField(value: String): Either[String, String] =
    try
      val bytes = Base64.getDecoder.decode(value)
      if Base64.getEncoder.encodeToString(bytes) != value then
        Left("noncanonical source marker Base64")
      else
        decodeUtf8(bytes.toVector).flatMap: decoded =>
          if decoded.isEmpty then
            Left("empty source marker identity/path")
          else Right(decoded)
    catch case NonFatal(_) => Left("invalid source marker Base64")

  private def singleStatement(line: String): Boolean =
    val text = line.trim
    var quoted = false
    var escaped = false
    var semicolons = 0
    var invalid = text.isEmpty || !text.endsWith(";")
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      if quoted then
        if escaped then escaped = false
        else if char == '\\' then escaped = true
        else if char == '"' then quoted = false
      else if char == '"' then quoted = true
      else if char == ';' then semicolons += 1
      else if char == '`' ||
        (char == '/' && index + 1 < text.length && "/*".contains(text.charAt(index + 1)))
      then invalid = true
      index += 1
    !invalid && !quoted && semicolons == 1

  /** Only a compiler-reported position in the exact staged model can acquire authored context. */
  def diagnostics(
      output: String,
      modelPath: Path,
      logicalFilename: String,
      sourceMapping: OpenVafSourceMapping
  ): Vector[OpenVafDiagnostic] =
    val statements = sourceMapping match
      case OpenVafSourceMapping.Valid(_, entries) => entries.map(e => e.generatedLine -> e).toMap
      case _ => Map.empty[Int, OpenVafStatementMapping]
    val result = Vector.newBuilder[OpenVafDiagnostic]
    var current: Option[OpenVafDiagnostic] = None
    Ansi.replaceAllIn(output, "").linesIterator.foreach:
      case Diagnostic(severity, message) =>
        current.foreach(result += _)
        current = Some(OpenVafDiagnostic(severity, message, None, None))
      case Location(path, line, column) =>
        for
          previous <- current
          row <- line.toIntOption.filter(_ > 0)
          col <- column.toIntOption.filter(_ > 0)
        do
          if previous.generated.nonEmpty then result += previous
          val authored = if path == modelPath.toString || path == logicalFilename then
            statements.get(
              row
            ).filter(e => col >= e.firstColumn && col <= e.lastColumn).map(_.authored)
          else None
          current = Some(previous.copy(
            generated = Some(OpenVafGeneratedPosition(path, row, col)),
            authored = authored
          ))
      case _ => ()
    current.foreach(result += _)
    result.result()
