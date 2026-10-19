package nodal.internal.testkit

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import nodal.internal.integrations.*
import utest.*

object VacaskRawTests extends TestSuite:
  // Exact official 0.3.4 ASCII outputs from unchanged F52 OSDI smoke runs.
  private val Dc = """Title: // F53 unchanged diode OSDI witness
Date: Mon Oct 19 06:04:24 2026
Plotname: Operating Point
Flags: real
No. Variables: 3
No. Points: 13
Variables:
	0	axis	notype
	1	v0:flow(br)	notype
	2	n0	notype
Values:
 0	-1.000000000000000e-01
	9.816843611112659e-13
	-1.000000000000000e-01
 1	-5.000000000000000e-02
	8.646647167633873e-13
	-5.000000000000000e-02
 2	0.000000000000000e+00
	0.000000000000000e+00
	0.000000000000000e+00
 3	5.000000000000002e-02
	-6.389056098930653e-12
	5.000000000000002e-02
 4	1.000000000000000e-01
	-5.359815003314424e-11
	1.000000000000000e-01
 5	1.500000000000000e-01
	-4.024287934927348e-10
	1.500000000000000e-01
 6	2.000000000000000e-01
	-2.979957987041734e-09
	2.000000000000000e-01
 7	2.500000000000000e-01
	-2.202546579480672e-08
	2.500000000000000e-01
 8	3.000000000000000e-01
	-1.627537914190042e-07
	3.000000000000000e-01
 9	3.500000000000000e-01
	-1.202603284164775e-06
	3.500000000000000e-01
 10	4.000000000000000e-01
	-8.886109520507873e-06
	4.000000000000000e-01
 11	4.500000000000001e-01
	-6.565996813733051e-05
	4.500000000000001e-01
 12	5.000000000000001e-01
	-4.851651944097919e-04
	5.000000000000001e-01

""".replace("No. Points: 13\n", "No. Points: 13              \n")
  private val Ac = """Title: // F53 unchanged OSDI witness
Date: Mon Oct 19 06:04:24 2026
Plotname: AC Small Signal Analysis
Flags: complex
No. Variables: 3
No. Points: 3
Variables:
	0	frequency	notype
	1	n1	notype
	2	v0:flow(br)	notype
Values:
 0	1.000000000000000e+01,0.000000000000000e+00
	9.999999984413930e-01,-6.283433357392641e-03
	-3.947997614973942e-07,-6.283185297386569e-05
 1	1.000000000000000e+02,0.000000000000000e+00
	9.999843530174889e-01,-6.307989940400091e-02
	-3.963426971135935e-05,-6.283086994288971e-04
 2	1.000000000000000e+03,0.000000000000000e+00
	7.952166424057484e-01,-8.255721885996519e-01
	-5.187223045425427e-03,-4.996493523588480e-03

""".replace("No. Points: 3\n", "No. Points: 3               \n")
  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector
  private def parse(value: String, kind: AnalogAnalysisKind = AnalogAnalysisKind.Dc) =
    VacaskRaw.parse(bytes(value), kind)

  val tests: Tests = Tests:
    test("native DC AC schema values and complex signs"):
      val dc = parse(Dc).toOption.get
      val ac = parse(Ac, AnalogAnalysisKind.Ac).toOption.get
      assert(dc.rows.size == 13, dc.variables.map(_.kind).distinct == Vector("notype"))
      assert(dc.variables.map(_.name) == Vector("axis", "v0:flow(br)", "n0"))
      assert(ac.rows.size == 3, ac.isComplex, ac.rows.head(1).imaginary < 0)
      assert(NgspiceRaw.parse(bytes(Dc), AnalogAnalysisKind.Dc).isLeft)
      assert(NgspiceRaw.parse(bytes(Ac), AnalogAnalysisKind.Ac).isLeft)

    test("closed dialect flags plot names types and headers"):
      assert(parse(Dc.replace("Operating Point", "DC transfer characteristic")).isLeft)
      assert(parse(Dc.replace("Flags: real", "Flags: real unpadded")).isLeft)
      assert(parse(Dc.replace("notype", "voltage")).isLeft)
      assert(parse(Dc.replace("notype", "notype grid=3")).isLeft)
      assert(parse(Dc.replace("Variables:", "Command: unknown\nVariables:")).isLeft)
      assert(parse(Dc.replace("Variables:", "Dimensions: 13\nVariables:")).isLeft)
      assert(parse(Dc.replace("Values:", "Binary:")).isLeft)
      assert(parse(Dc.replace("Flags: real", "Flags: complex")).isLeft)

    test("bounds truncation duplicate indices trailing and nonfinite"):
      assert(VacaskRaw.parse(bytes(Dc), AnalogAnalysisKind.Dc, maxPoints = 12).isLeft)
      assert(parse(Dc.take(Dc.lastIndexOf("\n", Dc.length - 3))).isLeft)
      assert(parse(Dc + Dc).isLeft)
      assert(parse(Dc.replace(" 1\t", " 0\t")).isLeft)
      assert(parse(Dc.replace("-1.000000000000000e-01", "NaN")).isLeft)
      assert(parse(Dc.replace("-1.000000000000000e-01", "1e999")).isLeft)
      assert(parse(Dc.replace("Flags: real", "Flags: real\nFlags: real")).isLeft)

    test("stream fold retains parser EOF and callback failure behavior"):
      val folded =
        VacaskRaw.fold(new ByteArrayInputStream(bytes(Dc).toArray), AnalogAnalysisKind.Dc)(schema =>
          Right(schema.pointCount -> 0)
        )((state, _, _) => Right(state._1 -> (state._2 + 1)))
      assert(folded == Right(13 -> 13))
      val failed =
        VacaskRaw.fold(new ByteArrayInputStream(bytes(Dc).toArray), AnalogAnalysisKind.Dc)(_ =>
          Right(0)
        )((_, _, _) => Left("callback rejected"))
      assert(failed == Left("callback rejected"))
