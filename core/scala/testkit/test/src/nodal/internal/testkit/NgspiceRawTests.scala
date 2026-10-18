package nodal.internal.testkit

import java.nio.charset.StandardCharsets

import nodal.internal.integrations.*
import utest.*

object NgspiceRawTests extends TestSuite:
  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector

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
