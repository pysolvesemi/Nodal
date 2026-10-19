package nodal.internal.integrations

import java.io.InputStream
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest

import scala.util.control.NonFatal

/** Shared bounded byte-integrity transaction; native parsers and grid policies stay separate. */
private[integrations] object AnalogRawEvidence:
  val MaxRawBytes = 16 * 1024 * 1024
  enum Failure:
    case InvalidRaw, ConsumerFailure
  final case class Artifact(path: Path, sha256: String, size: Long)
  final case class Read[A](
      artifact: Option[Artifact],
      result: Either[(Failure, String), (AnalogRawSchema, A)]
  )
  type Parser = (
      InputStream,
      AnalogRawSchema => Either[String, Unit],
      (Unit, Int, Vector[AnalogComplex]) => Either[String, Unit]
  ) => Either[String, Unit]

  def hashFile(path: Path, maximum: Long = Long.MaxValue): String =
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

  def read[A](
      path: Path,
      parse: Parser,
      schemaCheck: AnalogRawSchema => Option[String],
      rowCheck: (Int, Vector[AnalogComplex]) => Option[String],
      finishCheck: () => Option[String],
      initialize: AnalogRawSchema => Either[String, A],
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): Read[A] =
    def invalid(message: String): Read[A] = Read(None, Left(Failure.InvalidRaw -> message))
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0 then
      invalid("successful exit did not produce a fresh nonempty regular raw output")
    else if Files.size(path) > MaxRawBytes then invalid(s"raw output exceeds $MaxRawBytes bytes")
    else
      val input = new RawInput(Files.newInputStream(path))
      var schema = Option.empty[AnalogRawSchema]
      var aggregate = Option.empty[A]
      var validationError = Option.empty[String]
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
        val result = parse(
          input,
          value =>
            schema = Some(value)
            validationError = validationError.orElse(schemaCheck(value))
            if validationError.isEmpty then accept(initialize(value))
            Right(())
          ,
          (_, index, row) =>
            validationError = validationError.orElse(rowCheck(index, row))
            if validationError.isEmpty && consumerError.isEmpty then
              aggregate.foreach(value => accept(consume(value, index, row)))
            Right(())
        )
        input.drain()
        result
      finally input.close()
      if input.count == 0 || input.count > MaxRawBytes || !input.ended then
        invalid("raw output is empty or exceeds its byte bound")
      else if !input.ascii then invalid("raw output must use the pinned ASCII protocol")
      else
        val artifact = Artifact(path, input.sha256, input.count)
        val result =
          for
            _ <- parsed.left.map(Failure.InvalidRaw -> _)
            _ <- validationError.orElse(finishCheck()).toLeft(()).left.map(Failure.InvalidRaw -> _)
            _ <- Either.cond(
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(path) == artifact.size && hashFile(path, MaxRawBytes) == artifact.sha256,
              (),
              Failure.InvalidRaw -> "raw output changed while it was being parsed"
            )
            _ <- consumerError.toLeft(()).left.map(Failure.ConsumerFailure -> _)
            _ <- Either.cond(
              schema.nonEmpty && aggregate.nonEmpty,
              (),
              Failure.InvalidRaw -> "raw parser did not produce a completed schema and aggregate"
            )
          yield (schema.get, aggregate.get)
        Read(Some(artifact), result)
