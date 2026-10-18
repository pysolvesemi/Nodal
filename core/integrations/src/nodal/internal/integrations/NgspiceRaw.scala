package nodal.internal.integrations

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import scala.collection.mutable
import scala.util.boundary
import scala.util.Try

private[nodal] enum NgspiceAnalysisKind:
  case Dc, Ac, Transient

private[nodal] final case class NgspiceRawVariable(index: Int, name: String, kind: String)
private[nodal] final case class NgspiceComplex(real: Double, imaginary: Double)
private[nodal] final case class NgspiceRawSchema(
    title: String,
    plotName: String,
    isComplex: Boolean,
    variables: Vector[NgspiceRawVariable],
    pointCount: Int
)
private[nodal] final case class NgspiceRawPlot(
    title: String,
    plotName: String,
    isComplex: Boolean,
    variables: Vector[NgspiceRawVariable],
    rows: Vector[Vector[NgspiceComplex]]
)

/** Finite ngspice 47 ASCII raw protocol, not a general waveform or physical-units API.
  *
  * Supports one DC, AC or transient plot from rawfile.c/raw_write or outitf.c. The two writers
  * differ in blank point separators and optional AC grid metadata. Binary, unpadded, dimensioned
  * and extended variable formats are intentionally unsupported. Successful parsing establishes
  * structure and finite numbers only; numerical correctness belongs to the analysis consumer.
  */
private[nodal] object NgspiceRaw:
  private val MaxBytes = 16 * 1024 * 1024
  private val MaxLineBytes = 16 * 1024
  private val MaxLines = 1000000
  private val MaxPoints = 100000
  private val MaxVariables = 64
  private val HeaderNames =
    Set("Title", "Date", "Command", "Plotname", "Flags", "No. Variables", "No. Points")
  private val Decimal = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?".r

  /** Limits can tighten, but cannot relax, the finite adapter's resource ceilings. */
  def parse(
      bytes: Vector[Byte],
      expectedKind: NgspiceAnalysisKind,
      maxPoints: Int = MaxPoints,
      maxVariables: Int = MaxVariables
  ): Either[String, NgspiceRawPlot] =
    validateLimits(maxPoints, maxVariables).flatMap: _ =>
      if bytes.length > MaxBytes then Left(s"raw output exceeds $MaxBytes bytes")
      else
        val input = new InputStream:
          private var position = 0
          override def read(): Int =
            if position == bytes.size then -1
            else
              val value = bytes(position) & 0xff
              position += 1
              value
        fold(input, expectedKind, maxPoints, maxVariables)(schema =>
          Right(schema -> Vector.newBuilder[Vector[NgspiceComplex]])
        )((state, _, row) =>
          state._2 += row
          Right(state)
        ).map: (schema, rows) =>
          NgspiceRawPlot(
            schema.title,
            schema.plotName,
            schema.isComplex,
            schema.variables,
            rows.result()
          )

  /** Consume one bounded row at a time without retaining the raw bytes, text or preceding rows.
    *
    * The input belongs to the caller and is never closed here. Success is returned only after every
    * declared row and the complete trailing input have been validated through EOF. A failure may
    * leave unread input; callers needing an invalid artifact's full digest own any bounded drain.
    * Callback state must remain private until success: intermediate results are discarded on
    * failure, but external callback side effects cannot be rolled back.
    */
  def fold[A](
      input: InputStream,
      expectedKind: NgspiceAnalysisKind,
      maxPoints: Int = MaxPoints,
      maxVariables: Int = MaxVariables,
      asciiOnly: Boolean = false
  )(initialize: NgspiceRawSchema => Either[String, A])(
      consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A]
  ): Either[String, A] =
    for
      _ <- validateLimits(maxPoints, maxVariables)
      cursor = new Cursor(input, asciiOnly)
      schema <- readSchema(cursor, expectedKind, maxPoints, maxVariables)
      initial <- initialize(schema)
      result <- foldRows(cursor, schema, initial, consume)
      trailing <- cursor.nextNonEmpty()
      _ <- Either.cond(trailing.isEmpty, (), "trailing data or multiple plots are unsupported")
    yield result

  private def validateLimits(maxPoints: Int, maxVariables: Int): Either[String, Unit] =
    for
      _ <- Either.cond(
        maxPoints > 0 && maxPoints <= MaxPoints,
        (),
        s"point limit must be between 1 and $MaxPoints"
      )
      _ <- Either.cond(
        maxVariables > 0 && maxVariables <= MaxVariables,
        (),
        s"variable limit must be between 1 and $MaxVariables"
      )
    yield ()

  private def readSchema(
      cursor: Cursor,
      expectedKind: NgspiceAnalysisKind,
      maxPoints: Int,
      maxVariables: Int
  ): Either[String, NgspiceRawSchema] =
    for
      headers <- readHeaders(cursor)
      title <- required(headers, "Title")
      plotName <- required(headers, "Plotname")
      _ <- Either.cond(
        plotName == expectedPlotName(expectedKind),
        (),
        s"unexpected plot name: $plotName"
      )
      flags <- required(headers, "Flags")
      complex = expectedKind == NgspiceAnalysisKind.Ac
      _ <- Either.cond(
        flags == (if complex then "complex" else "real"),
        (),
        s"unsupported flags for $plotName: $flags"
      )
      variableCount <-
        required(headers, "No. Variables").flatMap(count(_, "variable", maxVariables))
      pointCount <- required(headers, "No. Points").flatMap(count(_, "point", maxPoints))
      _ <- Either.cond(
        pointCount.toLong * variableCount <= MaxLines,
        (),
        "declared values exceed line limit"
      )
      variables <- readMany(variableCount)(readVariable(cursor, _))
      section <- cursor.next().flatMap(_.toRight("missing Values section"))
      _ <- Either.cond(section == "Values:", (), s"expected ASCII Values section, found: $section")
    yield NgspiceRawSchema(title, plotName, complex, variables, pointCount)

  private def foldRows[A](
      cursor: Cursor,
      schema: NgspiceRawSchema,
      initial: A,
      consume: (A, Int, Vector[NgspiceComplex]) => Either[String, A]
  ): Either[String, A] =
    var state = initial
    var point = 0
    var error = Option.empty[String]
    while point < schema.pointCount && error.isEmpty do
      readRow(cursor, point, schema.variables.size, schema.isComplex)
        .flatMap(row => consume(state, point, row)) match
        case Left(message) => error = Some(message)
        case Right(next) => state = next
      point += 1
    error.toLeft(state)

  // All byte/line bounds and decoding live here, including bytes after the declared rows.
  // Refill is capped at the remaining byte budget plus one sentinel, without whole-file copies.
  private final class Cursor(input: InputStream, asciiOnly: Boolean):
    private val buffer = new Array[Byte](8192)
    private val line = new Array[Byte](MaxLineBytes)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var position = 0
    private var buffered = 0
    private var received = 0
    private var consumed = 0
    private var lines = 0
    private var exhausted = false

    private def readByte(): Int =
      if position == buffered && !exhausted then
        buffered = input.read(buffer, 0, math.min(buffer.length, MaxBytes - received + 1))
        position = 0
        // A nonconforming zero-length bulk read must not spin forever.
        if buffered == 0 then
          val value = input.read()
          if value == -1 then buffered = -1
          else
            buffer(0) = value.toByte
            buffered = 1
        if buffered == -1 then exhausted = true
        else received += buffered
      if exhausted then -1
      else
        val value = buffer(position) & 0xff
        position += 1
        consumed += 1
        value

    private def decode(length: Int): Either[String, Option[String]] =
      Try(decoder.reset().decode(ByteBuffer.wrap(line, 0, length)).toString.trim)
        .toEither.left.map(_ => "raw output is not valid UTF-8 text").map(Some(_))

    def next(): Either[String, Option[String]] =
      try
        boundary:
          var length = 0
          var first = true
          var carriageReturn = false
          while true do
            val value = readByte()
            if consumed > MaxBytes then boundary.break(Left(s"raw output exceeds $MaxBytes bytes"))
            if carriageReturn && value != 10 then
              boundary.break(Left("bare carriage return in raw output"))
            if value == -1 then
              if consumed == 0 then boundary.break(Left("empty raw output"))
              else if first then boundary.break(Right(None))
              else boundary.break(decode(length))
            if first then
              lines += 1
              first = false
              if lines > MaxLines then
                boundary.break(Left(s"raw output exceeds $MaxLines lines"))
            if value == 10 then boundary.break(decode(length))
            if length == MaxLineBytes then
              boundary.break(Left(s"raw line exceeds $MaxLineBytes bytes"))
            if asciiOnly && value > 127 then
              boundary.break(Left("raw output must use the pinned ASCII protocol"))
            if (value < 32 && value != 9 && value != 13) || value == 127 then
              boundary.break(Left("control character in raw output"))
            line(length) = value.toByte
            length += 1
            carriageReturn = value == 13
          Right(None)
      catch
        case error: IOException =>
          Left(
            s"unable to read raw output: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )

    def nextNonEmpty(): Either[String, Option[String]] =
      var line = next()
      while line == Right(Some("")) do line = next()
      line

  private def readHeaders(cursor: Cursor): Either[String, Map[String, String]] =
    val headers = mutable.Map.empty[String, String]
    var finished = false
    var error = Option.empty[String]
    while !finished && error.isEmpty do
      cursor.next() match
        case Left(message) => error = Some(message)
        case Right(None) => error = Some("missing Variables section")
        case Right(Some("Variables:")) => finished = true
        case Right(Some(line)) =>
          val separator = line.indexOf(':')
          if separator < 1 then error = Some(s"malformed raw header: $line")
          else
            val name = line.substring(0, separator)
            val value = line.substring(separator + 1).trim
            if !HeaderNames.contains(name) then error = Some(s"unsupported raw header: $name")
            else if headers.contains(name) then error = Some(s"duplicate raw header: $name")
            else if value.isEmpty then error = Some(s"empty raw header: $name")
            else headers(name) = value
    error.toLeft(headers.toMap)

  private def required(headers: Map[String, String], name: String): Either[String, String] =
    headers.get(name).toRight(s"missing raw header: $name")

  private def count(value: String, kind: String, maximum: Int): Either[String, Int] =
    val parsed = Option.when(value.nonEmpty && value.forall(c => c >= '0' && c <= '9'))(value)
      .flatMap(_.toIntOption)
      .filter(n => n > 0 && n <= maximum)
    parsed.toRight(s"$kind count must be between 1 and $maximum: $value")

  private def expectedPlotName(kind: NgspiceAnalysisKind): String = kind match
    case NgspiceAnalysisKind.Dc => "DC transfer characteristic"
    case NgspiceAnalysisKind.Ac => "AC Analysis"
    case NgspiceAnalysisKind.Transient => "Transient Analysis"

  private def readMany[A](count: Int)(read: Int => Either[String, A]): Either[String, Vector[A]] =
    val result = Vector.newBuilder[A]
    var index = 0
    var error = Option.empty[String]
    while index < count && error.isEmpty do
      read(index) match
        case Left(message) => error = Some(message)
        case Right(value) => result += value
      index += 1
    error.toLeft(result.result())

  private def readVariable(cursor: Cursor, index: Int): Either[String, NgspiceRawVariable] =
    for
      line <- cursor.next().flatMap(_.toRight(s"missing variable $index"))
      tokens = line.split("[ \\t]+", -1).toVector
      _ <- Either.cond(
        tokens.length == 3 || (tokens.length == 4 && tokens(3) == "grid=3"),
        (),
        s"unsupported variable declaration: $line"
      )
      _ <- Either.cond(tokens.head == index.toString, (), s"expected variable index $index: $line")
    yield NgspiceRawVariable(index, tokens(1), tokens(2))

  private def readRow(
      cursor: Cursor,
      point: Int,
      variables: Int,
      complex: Boolean
  ): Either[String, Vector[NgspiceComplex]] =
    readMany(variables): column =>
      for
        line <- (if column == 0 then cursor.nextNonEmpty() else cursor.next())
          .flatMap(_.toRight(s"missing point $point variable $column"))
        tokens = line.split("[ \\t]+", -1).toVector
        _ <- Either.cond(
          tokens.length == (if column == 0 then 2 else 1),
          (),
          s"malformed point $point variable $column: $line"
        )
        _ <- Either.cond(
          column != 0 || tokens.head == point.toString,
          (),
          s"expected point index $point: $line"
        )
        value <- number(tokens.last, complex).left.map(message =>
          s"point $point variable $column: $message"
        )
      yield value

  private def number(value: String, complex: Boolean): Either[String, NgspiceComplex] =
    val parts = value.split(",", -1)
    for
      _ <- Either.cond(
        parts.length == (if complex then 2 else 1),
        (),
        s"invalid ${if complex then "complex" else "real"} value: $value"
      )
      real <- finite(parts(0))
      imaginary <- if complex then finite(parts(1)) else Right(0.0)
    yield NgspiceComplex(real, imaginary)

  private def finite(value: String): Either[String, Double] =
    if !Decimal.matches(value) then Left(s"invalid decimal number: $value")
    else value.toDoubleOption.filter(_.isFinite).toRight(s"non-finite number: $value")
