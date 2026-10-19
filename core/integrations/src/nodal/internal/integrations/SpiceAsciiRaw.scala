package nodal.internal.integrations

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import scala.collection.mutable
import scala.util.boundary
import scala.util.Try

/** Bounded lexical engine for two closed ASCII raw writer dialects.
  *
  * Supports one DC, AC or transient plot. The ngspice rawfile.c/raw_write and outitf.c writers
  * differ in blank point separators and optional AC grid metadata. Binary, unpadded, dimensioned
  * and extended variable formats are intentionally unsupported. Successful parsing establishes
  * structure and finite numbers only; numerical correctness belongs to the analysis consumer.
  */
private[integrations] enum SpiceRawDialect:
  case Ngspice47, Vacask034

private[integrations] object SpiceAsciiRaw:
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
      dialect: SpiceRawDialect,
      bytes: Vector[Byte],
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = MaxPoints,
      maxVariables: Int = MaxVariables
  ): Either[String, AnalogRawPlot] =
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
        fold(dialect, input, expectedKind, maxPoints, maxVariables)(schema =>
          Right(schema -> Vector.newBuilder[Vector[AnalogComplex]])
        )((state, _, row) =>
          state._2 += row
          Right(state)
        ).map: (schema, rows) =>
          AnalogRawPlot(
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
      dialect: SpiceRawDialect,
      input: InputStream,
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = MaxPoints,
      maxVariables: Int = MaxVariables,
      asciiOnly: Boolean = false
  )(initialize: AnalogRawSchema => Either[String, A])(
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): Either[String, A] =
    for
      _ <- validateLimits(maxPoints, maxVariables)
      cursor = new Cursor(input, asciiOnly)
      schema <- readSchema(cursor, dialect, expectedKind, maxPoints, maxVariables)
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
      dialect: SpiceRawDialect,
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int,
      maxVariables: Int
  ): Either[String, AnalogRawSchema] =
    for
      headers <- readHeaders(cursor, dialect)
      title <- required(headers, "Title")
      plotName <- required(headers, "Plotname")
      _ <- Either.cond(
        plotName == expectedPlotName(dialect, expectedKind),
        (),
        s"unexpected plot name: $plotName"
      )
      flags <- required(headers, "Flags")
      complex = expectedKind == AnalogAnalysisKind.Ac
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
      variables <- readMany(variableCount)(readVariable(cursor, _, dialect))
      section <- cursor.next().flatMap(_.toRight("missing Values section"))
      _ <- Either.cond(section == "Values:", (), s"expected ASCII Values section, found: $section")
    yield AnalogRawSchema(title, plotName, complex, variables, pointCount)

  private def foldRows[A](
      cursor: Cursor,
      schema: AnalogRawSchema,
      initial: A,
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
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

  private def readHeaders(
      cursor: Cursor,
      dialect: SpiceRawDialect
  ): Either[String, Map[String, String]] =
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
            if !HeaderNames.contains(name) ||
              (dialect == SpiceRawDialect.Vacask034 && name == "Command")
            then error = Some(s"unsupported raw header: $name")
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

  private def expectedPlotName(dialect: SpiceRawDialect, kind: AnalogAnalysisKind): String =
    (dialect, kind) match
      case (SpiceRawDialect.Ngspice47, AnalogAnalysisKind.Dc) => "DC transfer characteristic"
      case (SpiceRawDialect.Vacask034, AnalogAnalysisKind.Dc) => "Operating Point"
      case (SpiceRawDialect.Ngspice47, AnalogAnalysisKind.Ac) => "AC Analysis"
      case (SpiceRawDialect.Vacask034, AnalogAnalysisKind.Ac) => "AC Small Signal Analysis"
      case (_, AnalogAnalysisKind.Transient) => "Transient Analysis"

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

  private def readVariable(
      cursor: Cursor,
      index: Int,
      dialect: SpiceRawDialect
  ): Either[String, AnalogRawVariable] =
    for
      line <- cursor.next().flatMap(_.toRight(s"missing variable $index"))
      tokens = line.split("[ \\t]+", -1).toVector
      _ <- Either.cond(
        tokens.length == 3 ||
          (dialect == SpiceRawDialect.Ngspice47 && tokens.length == 4 && tokens(3) == "grid=3"),
        (),
        s"unsupported variable declaration: $line"
      )
      _ <- Either.cond(tokens.head == index.toString, (), s"expected variable index $index: $line")
      _ <- Either.cond(
        dialect != SpiceRawDialect.Vacask034 || tokens(2) == "notype",
        (),
        "VACASK variables must carry the native notype tag"
      )
    yield AnalogRawVariable(index, tokens(1), tokens(2))

  private def readRow(
      cursor: Cursor,
      point: Int,
      variables: Int,
      complex: Boolean
  ): Either[String, Vector[AnalogComplex]] =
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

  private def number(value: String, complex: Boolean): Either[String, AnalogComplex] =
    val parts = value.split(",", -1)
    for
      _ <- Either.cond(
        parts.length == (if complex then 2 else 1),
        (),
        s"invalid ${if complex then "complex" else "real"} value: $value"
      )
      real <- finite(parts(0))
      imaginary <- if complex then finite(parts(1)) else Right(0.0)
    yield AnalogComplex(real, imaginary)

  private def finite(value: String): Either[String, Double] =
    if !Decimal.matches(value) then Left(s"invalid decimal number: $value")
    else value.toDoubleOption.filter(_.isFinite).toRight(s"non-finite number: $value")
