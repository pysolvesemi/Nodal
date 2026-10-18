package nodal.internal.testkit

import java.io.InputStream
import java.nio.charset.StandardCharsets

import nodal.internal.integrations.*
import utest.*

object NgspiceRawTests extends TestSuite:
  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector

  private final class ChunkedInput(
      chunks: Iterator[Array[Byte]],
      chunkSize: Int = 7,
      failAtEnd: Boolean = false
  ) extends InputStream:
    private var current = Array.emptyByteArray
    private var position = 0
    var bytesRead = 0L
    var largestRead = 0
    var reachedEnd = false
    var closed = false

    private def availableChunk(): Boolean =
      while position == current.length && chunks.hasNext do
        current = chunks.next()
        position = 0
      if position < current.length then true
      else
        reachedEnd = true
        if failAtEnd then
          val failed = InputStream.nullInputStream()
          failed.close()
          failed.read() != -1
        else false

    override def read(): Int =
      if !availableChunk() then -1
      else
        val value = current(position) & 0xff
        position += 1
        bytesRead += 1
        value

    override def read(target: Array[Byte], offset: Int, length: Int): Int =
      largestRead = math.max(largestRead, length)
      if length == 0 then 0
      else if !availableChunk() then -1
      else
        val count = math.min(math.min(length, chunkSize), current.length - position)
        System.arraycopy(current, position, target, offset, count)
        position += count
        bytesRead += count
        count

    override def close(): Unit = closed = true

  private def stream(value: String, chunkSize: Int = 7): ChunkedInput =
    new ChunkedInput(Iterator(value.getBytes(StandardCharsets.UTF_8)), chunkSize)

  private def sum(input: InputStream, asciiOnly: Boolean = false): Either[String, Double] =
    NgspiceRaw.fold[Double](input, NgspiceAnalysisKind.Dc, asciiOnly = asciiOnly)(_ => Right(0.0))(
      (total, _, row) => Right(total + row.head.real)
    )

  private def raw(
      plotName: String = "DC transfer characteristic",
      flags: String = "real",
      variables: Vector[String] = Vector("v(v-sweep) voltage", "v(p) voltage", "i(vdrive) current"),
      rows: Vector[Vector[String]] = Vector(
        Vector("0.000000000000000e+00", "0.000000000000000e+00", "0.000000000000000e+00"),
        Vector("1.000000000000000e+00", "1.000000000000000e+00", "-1.000000000000000e-03")
      ),
      separator: String = ""
  ): String =
    val declarations =
      variables.zipWithIndex.map((variable, index) => s"\t$index\t$variable\n").mkString
    val values = rows.zipWithIndex.map: (row, index) =>
      s"$index\t\t${row.head}\n" + row.tail.map(value => s"\t$value\n").mkString + separator
    s"Title: generated rc\nDate: Sun Oct 18 22:06:00 2026\nCommand: ngspice-47\nPlotname: $plotName\nFlags: $flags\nNo. Variables: ${variables.size}\nNo. Points: ${rows.size}       \nVariables:\n${declarations}Values:\n${values.mkString}"

  private def parse(
      value: String,
      kind: NgspiceAnalysisKind = NgspiceAnalysisKind.Dc
  ): Either[String, NgspiceRawPlot] =
    NgspiceRaw.parse(bytes(value), kind)

  private def rejected(
      value: String,
      expectedMessage: String,
      kind: NgspiceAnalysisKind = NgspiceAnalysisKind.Dc
  ): Unit =
    val result = parse(value, kind)
    assert(result.isLeft, result.left.toOption.exists(_.contains(expectedMessage)))

  val tests: Tests = Tests:
    test("real DC retains declared names kinds and signed numeric values"):
      val result = parse(raw()).toOption.get
      assert(
        result.title == "generated rc",
        result.plotName == "DC transfer characteristic",
        !result.isComplex
      )
      assert(result.variables == Vector(
        NgspiceRawVariable(0, "v(v-sweep)", "voltage"),
        NgspiceRawVariable(1, "v(p)", "voltage"),
        NgspiceRawVariable(2, "i(vdrive)", "current")
      ))
      assert(result.rows(1) ==
        Vector(NgspiceComplex(1, 0), NgspiceComplex(1, 0), NgspiceComplex(-0.001, 0)))

    test("transient accepts write-command blank separators and CRLF"):
      val input = raw(
        plotName = "Transient Analysis",
        variables = Vector("time time", "v(out) voltage"),
        rows = Vector(Vector("0", "-0.0"), Vector("1e-9", "2.5")),
        separator = "\n"
      ).replace("\n", "\r\n")
      val result = parse(input, NgspiceAnalysisKind.Transient).toOption.get
      assert(result.rows(1) == Vector(NgspiceComplex(1e-9, 0), NgspiceComplex(2.5, 0)))
      assert(java.lang.Double.doubleToLongBits(result.rows.head(1).real) ==
        java.lang.Double.doubleToLongBits(-0.0))

    test("complex AC retains real and imaginary components with grid metadata"):
      val input = raw(
        plotName = "AC Analysis",
        flags = "complex",
        variables = Vector("frequency frequency grid=3", "i(vdrive) current"),
        rows = Vector(Vector("1e3,0.0", "-1e-3,-6.283185307179586e-9"))
      )
      val result = parse(input, NgspiceAnalysisKind.Ac).toOption.get
      assert(
        result.isComplex,
        result.variables.head == NgspiceRawVariable(0, "frequency", "frequency")
      )
      assert(result.rows.head(1) == NgspiceComplex(-1e-3, -6.283185307179586e-9))

    test("plain text metadata and names preserve UTF-8 without inferring units"):
      val input = raw(variables = Vector("v(节点) raw-kind"), rows = Vector(Vector("+.5E+0")))
        .replace("generated rc", "RC café")
        .replace("Date: Sun Oct 18 22:06:00 2026\n", "")
        .replace("Command: ngspice-47\n", "")
      val result = parse(input).toOption.get
      assert(
        result.title == "RC café",
        result.variables.head.name == "v(节点)",
        result.variables.head.kind == "raw-kind"
      )
      assert(result.rows.head.head.real == 0.5)

    test("single point and configured exact count limits"):
      val input = bytes(raw(variables = Vector("sweep raw"), rows = Vector(Vector("0"))))
      assert(NgspiceRaw.parse(
        input,
        NgspiceAnalysisKind.Dc,
        maxPoints = 1,
        maxVariables = 1
      ).isRight)
      assert(NgspiceRaw.parse(bytes(raw()), NgspiceAnalysisKind.Dc, maxPoints = 1).isLeft)
      assert(NgspiceRaw.parse(bytes(raw()), NgspiceAnalysisKind.Dc, maxVariables = 2).isLeft)

    test("hard point boundary supports bounded long output"):
      val values = Vector.tabulate(100000)(index => Vector(index.toString))
      val result = parse(raw(variables = Vector("sweep raw"), rows = values)).toOption.get
      assert(result.rows.size == 100000, result.rows.last.head.real == 99999)
      rejected(raw().replace("No. Points: 2", "No. Points: 100001"), "point count")

    test("hard variable boundary"):
      val variables = Vector.tabulate(64)(index => s"v($index) voltage")
      val input = raw(variables = variables, rows = Vector(Vector.fill(64)("0")))
      assert(parse(input).toOption.get.variables.size == 64)
      rejected(input.replace("No. Variables: 64", "No. Variables: 65"), "variable count")

    test("invalid or relaxed caller limits are rejected"):
      for limit <- Vector(-1, 0, 100001, Int.MaxValue) do
        assert(NgspiceRaw.parse(bytes(raw()), NgspiceAnalysisKind.Dc, maxPoints = limit).isLeft)
      for limit <- Vector(-1, 0, 65, Int.MaxValue) do
        assert(NgspiceRaw.parse(bytes(raw()), NgspiceAnalysisKind.Dc, maxVariables = limit).isLeft)

    test("missing duplicate malformed and unsupported headers"):
      rejected(raw().replace("Title: generated rc\n", ""), "missing raw header: Title")
      rejected(raw().replace("Flags: real", "Flags: real\nFlags: real"), "duplicate raw header")
      rejected(raw().replace("Title: generated rc", "Title"), "malformed raw header")
      rejected(raw().replace("Title: generated rc", "Title: "), "empty raw header")
      rejected(
        raw().replace("Variables:\n", "Dimensions: 2,1\nVariables:\n"),
        "unsupported raw header"
      )
      rejected(
        raw().replace("Variables:\n", "Option: anything\nVariables:\n"),
        "unsupported raw header"
      )

    test("expected analysis and exact flags must agree"):
      rejected(raw(), "unexpected plot name", NgspiceAnalysisKind.Ac)
      for flags <- Vector("complex", "real unpadded", "real complex", "real padded", "REAL") do
        rejected(raw(flags = flags), "unsupported flags")
      rejected(
        raw(plotName = "AC Analysis", flags = "real"),
        "unsupported flags",
        NgspiceAnalysisKind.Ac
      )
      rejected(raw(plotName = "Operating Point"), "unexpected plot name")

    test("counts reject zero negative invalid and overflow before allocating rows"):
      for value <- Vector("0", "-1", "+2", "1.0", "two", "2147483648", "9" * 1000) do
        rejected(raw().replace("No. Points: 2", s"No. Points: $value"), "point count")
        rejected(raw().replace("No. Variables: 3", s"No. Variables: $value"), "variable count")
      rejected(
        raw().replace(
          "No. Points: 2",
          "No. Points: 100000"
        ).replace("No. Variables: 3", "No. Variables: 64"),
        "declared values exceed line limit"
      )

    test("variable indices declarations and counts are exact"):
      rejected(raw().replace("\t1\tv(p)", "\t0\tv(p)"), "expected variable index 1")
      rejected(raw().replace("\t0\tv(v-sweep)", "\t1\tv(v-sweep)"), "expected variable index 0")
      rejected(raw().replace("\t0\tv(v-sweep)", "\t00\tv(v-sweep)"), "expected variable index 0")
      rejected(raw().replace("v(p) voltage", "v(p)"), "unsupported variable declaration")
      rejected(
        raw().replace("v(p) voltage", "v(p) voltage dims=2"),
        "unsupported variable declaration"
      )
      rejected(
        raw().replace("No. Variables: 3", "No. Variables: 2"),
        "expected ASCII Values section"
      )
      rejected(
        raw().replace("No. Variables: 3", "No. Variables: 4"),
        "unsupported variable declaration"
      )

    test("point indices counts and value columns are exact"):
      rejected(raw().replace("1\t\t1.000", "0\t\t1.000"), "expected point index 1")
      rejected(raw().replace("1\t\t1.000", "2\t\t1.000"), "expected point index 1")
      rejected(raw().replace("0\t\t0.000", "00\t\t0.000"), "expected point index 0")
      rejected(raw().replace("0\t\t0.000", "0\t9\t0.000"), "malformed point")
      rejected(raw().replace("\n\t0.000", "\n\t0 0.000"), "malformed point")
      rejected(raw().replace("No. Points: 2", "No. Points: 1"), "trailing data")
      rejected(raw().replace("No. Points: 2", "No. Points: 3"), "missing point 2")

    test("finite decimal syntax rejects NaN infinity overflow hex and suffixes"):
      for value <- Vector(
          "NaN",
          "nan",
          "Inf",
          "Infinity",
          "-inf",
          "1e309",
          "0x1.0p0",
          "1f",
          "1d",
          "1e",
          "--1",
          "."
        )
      do
        rejected(raw(variables = Vector("sweep raw"), rows = Vector(Vector(value))), "number")

    test("real and complex arities cannot silently coerce"):
      rejected(raw(variables = Vector("sweep raw"), rows = Vector(Vector("1,0"))), "invalid real")
      for value <- Vector("1", "1,", ",1", "1,2,3", "1,NaN", "1,1e309") do
        assert(parse(
          raw(
            plotName = "AC Analysis",
            flags = "complex",
            variables = Vector("frequency frequency"),
            rows = Vector(Vector(value))
          ),
          NgspiceAnalysisKind.Ac
        ).isLeft)

    test("binary unrecognized sections and additional plots are rejected"):
      rejected(raw().replace("Values:", "Binary:"), "expected ASCII Values section")
      rejected(raw() + raw(), "trailing data or multiple plots")
      rejected(raw() + "unexpected\n", "trailing data")
      assert(parse(raw() + "\n \t\n").isRight)

    test("truncated headers variables and values fail"):
      val input = raw()
      rejected("Title: generated rc\n", "missing Variables section")
      rejected(input.take(input.indexOf("\t1\tv(p)")), "missing variable 1")
      rejected(input.take(input.indexOf("Values:")), "missing Values section")
      rejected(input.take(input.lastIndexOf("\t-1.000")), "missing point 1 variable 2")
      rejected(input.replace("\n\t-1.000", "\n\n\t-1.000"), "invalid decimal number")

    test("malformed UTF-8 control characters and bare CR cannot become valid text"):
      val badUtf8 = bytes("Title: ") ++ Vector(0xc3.toByte, 0x28.toByte) ++ bytes("\n")
      assert(NgspiceRaw.parse(
        badUtf8,
        NgspiceAnalysisKind.Dc
      ).left.toOption.contains("raw output is not valid UTF-8 text"))
      assert(NgspiceRaw.parse(
        Vector.empty,
        NgspiceAnalysisKind.Dc
      ).left.toOption.contains("empty raw output"))
      for control <- Vector('\u0000', '\u0001', '\u000b', '\u007f') do
        rejected(raw().replace("generated rc", s"bad${control}title"), "control character")
      rejected(raw().replace("generated rc", "bad\rtitle"), "bare carriage return")

    test("byte line length and line count ceilings reject before parsing"):
      val oversized = Vector.fill[Byte](16 * 1024 * 1024 + 1)('x'.toByte)
      assert(NgspiceRaw.parse(
        oversized,
        NgspiceAnalysisKind.Dc
      ).left.toOption.exists(_.contains("exceeds 16777216 bytes")))
      rejected("x" * (16 * 1024 + 1), "raw line exceeds 16384 bytes")
      val longTitle = "x" * (16 * 1024 - "Title: ".length)
      assert(parse(raw().replace("generated rc", longTitle)).isRight)
      val atLineLimit = raw() + "\n" * (1000000 - raw().count(_ == '\n'))
      assert(parse(atLineLimit).isRight)
      rejected(atLineLimit + "\n", "raw output exceeds 1000000 lines")

    test("streaming schema rows and indices agree with the collecting adapter"):
      val examples = Vector(
        NgspiceAnalysisKind.Dc -> raw().stripSuffix("\n"),
        NgspiceAnalysisKind.Transient -> raw(
          plotName = "Transient Analysis",
          variables = Vector("time time", "v(节点) voltage"),
          rows = Vector(Vector("0", "-0.0"), Vector("1e-9", "2.5")),
          separator = "\n"
        ).replace("generated rc", "RC café").replace("\n", "\r\n"),
        NgspiceAnalysisKind.Ac -> raw(
          plotName = "AC Analysis",
          flags = "complex",
          variables = Vector("frequency frequency grid=3", "i(vdrive) current"),
          rows = Vector(Vector("1e3,0", "-1e-3,-6e-9"))
        )
      )
      for (kind, text) <- examples do
        val input = stream(text, chunkSize = 1)
        val expected = parse(text, kind).toOption.get
        val result = NgspiceRaw.fold[(NgspiceRawSchema, Vector[Vector[NgspiceComplex]])](
          input,
          kind
        )(schema => Right(schema -> Vector.empty))((state, index, row) =>
          assert(index == state._2.size)
          Right(state._1 -> (state._2 :+ row))
        ).toOption.get
        assert(
          result._1 == NgspiceRawSchema(
            expected.title,
            expected.plotName,
            expected.isComplex,
            expected.variables,
            expected.rows.size
          ),
          result._2 == expected.rows,
          input.reachedEnd,
          !input.closed
        )

    test("streaming ASCII policy is explicit and never changes UTF-8 compatibility"):
      val unicode = raw().replace("generated rc", "RC café")
      assert(sum(stream(unicode)).isRight)
      assert(sum(stream(unicode), asciiOnly = true).left.toOption.contains(
        "raw output must use the pinned ASCII protocol"
      ))
      assert(sum(stream(raw()), asciiOnly = true).isRight)
      val incomplete = new ChunkedInput(
        Iterator(
          "Title: ".getBytes(StandardCharsets.UTF_8),
          Array(0xe2.toByte, 0x82.toByte)
        ),
        chunkSize = 1
      )
      assert(sum(incomplete).left.toOption.contains("raw output is not valid UTF-8 text"))

    test("streaming failures discard results after already consumed rows"):
      val failures = Vector(
        raw() + "unexpected\n" -> "trailing data",
        raw() + "\u0000" -> "control character",
        raw() + "\r" -> "bare carriage return",
        raw().replace("-1.000000000000000e-03", "NaN") -> "invalid decimal number",
        raw().replace("No. Points: 2", "No. Points: 3") -> "missing point 2"
      )
      for (text, message) <- failures do
        val input = stream(text, chunkSize = 3)
        var observed = 0
        val result =
          NgspiceRaw.fold[Int](input, NgspiceAnalysisKind.Dc)(_ => Right(0))((count, _, _) =>
            observed += 1
            Right(count + 1)
          )
        assert(result.left.toOption.exists(_.contains(message)), observed >= 1, !input.closed)
      val invalidUtf8 = new ChunkedInput(Iterator(
        raw().getBytes(StandardCharsets.UTF_8),
        Array(0xc3.toByte)
      ))
      assert(sum(invalidUtf8).left.toOption.contains("raw output is not valid UTF-8 text"))

    test("stream ownership remains with caller on success read error and consumer rejection"):
      val input = new ChunkedInput(
        Iterator(raw().getBytes(StandardCharsets.UTF_8)),
        failAtEnd = true
      )
      var observed = 0
      val result =
        NgspiceRaw.fold[Int](input, NgspiceAnalysisKind.Dc)(_ => Right(0))((count, _, _) =>
          observed += 1
          Right(count + 1)
        )
      assert(
        result.left.toOption.exists(_.startsWith("unable to read raw output:")),
        observed == 2,
        !input.closed
      )
      input.close()
      assert(input.closed)
      val rejectedSchema = stream(raw())
      assert(NgspiceRaw.fold[Int](rejectedSchema, NgspiceAnalysisKind.Dc)(_ =>
        Left("schema refused")
      )((count, _, _) => Right(count + 1)).left.toOption.contains("schema refused"))
      assert(!rejectedSchema.closed)
      val rejectedRow = stream(raw())
      assert(NgspiceRaw.fold[Int](rejectedRow, NgspiceAnalysisKind.Dc)(_ => Right(0))(
        (_, index, _) => Left(s"row $index refused")
      ).left.toOption.contains("row 0 refused"))
      assert(!rejectedRow.closed)

    test("streaming hard point bound consumes generated rows before EOF without collecting"):
      val single = raw(variables = Vector("sweep raw"), rows = Vector(Vector("0")))
      val header = single.take(single.indexOf("Values:") + "Values:\n".length)
        .replace("No. Points: 1", "No. Points: 100000")
      val chunks = Iterator(header.getBytes(StandardCharsets.UTF_8)) ++
        Iterator.tabulate(100000)(index => s"$index\t$index\n".getBytes(StandardCharsets.UTF_8))
      val input = new ChunkedInput(chunks, chunkSize = Int.MaxValue)
      var firstRowBytes = 0L
      val result = NgspiceRaw.fold[Double](input, NgspiceAnalysisKind.Dc)(schema =>
        assert(schema.pointCount == 100000, schema.variables.size == 1)
        Right(0.0)
      )((total, index, row) =>
        assert(row.size == 1, row.head.real == index.toDouble, !input.reachedEnd)
        if index == 0 then firstRowBytes = input.bytesRead
        Right(total + row.head.real)
      )
      assert(
        result == Right(4999950000.0),
        input.reachedEnd,
        !input.closed,
        firstRowBytes <= header.length + 8192,
        firstRowBytes < input.bytesRead,
        input.largestRead <= 8192
      )

    test("streaming exact byte ceiling and first excess byte use bounded reads"):
      val prefix = raw().getBytes(StandardCharsets.UTF_8)
      val maximum = 16 * 1024 * 1024
      val padding = (" " * (16 * 1024 - 1) + "\n").getBytes(StandardCharsets.UTF_8)
      def boundedInput(extra: Int): ChunkedInput =
        val remaining = maximum - prefix.length + extra
        val chunks = Iterator(prefix) ++ Iterator.fill(remaining / padding.length)(padding) ++
          Iterator((" " * (remaining % padding.length)).getBytes(StandardCharsets.UTF_8))
        new ChunkedInput(chunks, chunkSize = Int.MaxValue)
      val exact = boundedInput(0)
      assert(sum(exact) == Right(1.0), exact.bytesRead == maximum, exact.reachedEnd)
      val excess = boundedInput(1)
      assert(
        sum(excess).left.toOption.contains("raw output exceeds 16777216 bytes"),
        excess.bytesRead == maximum.toLong + 1,
        excess.largestRead <= 8192,
        !excess.closed
      )

    test("zero-length bulk reads cannot stall incremental parsing"):
      val source = stream(raw())
      val input = new InputStream:
        override def read(): Int = source.read()
        override def read(target: Array[Byte], offset: Int, length: Int): Int = 0
      assert(sum(input) == Right(1.0), source.reachedEnd, !source.closed)
