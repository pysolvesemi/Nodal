package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalArithmeticSource extends Module, DigitalCollectionClock:
  val width = param(4.integer, range = 2 to 9)
  val otherWidth = param(4.integer, range = 2 to 9)
  val unsigned = in(UInt(width))
  val signed = in(SInt(width))
  val other = in(SInt(otherWidth))
  val unsignedQuotient = unsigned / 2.U(2)
  val unsignedRemainder = unsigned % 2.U(2)
  val computedDivisor = 1.S(2) + 1.S(2)
  val signedQuotient = signed / computedDivisor
  val signedRemainder = signed % (-3).S(3)
  val minimum = signed.min(other)
  val maximum = signed.max(other)
  val overflowQuotient = ((-8).S(4) / (-1).S(1)).toUnsigned.resizeChecked(4)
  val negativeRemainder = ((-7).S(4) % 3.S(3)).resizeChecked(1)
  val negativeQuotient = ((-7).S(4) / 3.S(3)).resizeChecked(2)
  val positiveQuotient = ((-7).S(4) / (-3).S(3)).toUnsigned.resizeChecked(2)
  val positiveRemainder = (7.S(4) % (-3).S(3)).toUnsigned.resizeChecked(1)
  val negativeDivisorQuotient = (7.S(4) / (-3).S(3)).resizeChecked(2)
  val checkedMinimum = (-8).S(4).min(7.S(8)).resizeChecked(4)
  val checkedMaximum = (-8).S(4).max(7.S(8)).toUnsigned.resizeChecked(3)
  val source = in(Vec(UInt(8), 4))
  val divided = source.map(value => (value / 2.U(2)).min(7.U(3)))
  val remainders = source.map(value => (value % (1.U(1) + 1.U(1))).max(1.U(1)))
  val outerDivisor = 1.U(1) + 1.U(1)
  val runtimeDivisor = in(UInt(2))
  val capturedDivided = source.map(value => value / outerDivisor)
  val signedSource = in(Vec(SInt(4), 3))
  val signedDivided = signedSource.map(value => value / (-1).S(1))

final class DigitalArithmeticUnknownDivisorSource(parameter: Boolean) extends Module:
  val input = in(UInt(8))
  val divisor = if parameter then param(2.U(2)) else in(UInt(2))
  val result = input / divisor

final class DigitalArithmeticSymbolicOverflowSource extends Module:
  val factor = param(1.integer, range = 1 to 2)
  val width = factor * Int.MaxValue.integer * Int.MaxValue.integer +
    Int.MaxValue.integer * 4.integer + 1.integer
  val input = in(SInt(width))
  val result = input / 1.S(2)

object Increment55DigitalArithmeticTests extends TestSuite:
  private def kernel(value: Expr[?]): KernelExpr[?] = value.asInstanceOf[KernelExpr[?]]
  private def width(value: Expr[?]): Any = kernel(value).resultType.get.arguments.head
  private def exact(value: Expr[?]): BigInt = DigitalScalarContract.knownValue(value).get
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code
  private lazy val snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalArithmeticSource)

  private def rejected(value: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])

  private def change(value: ConstructionSnapshot, path: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot = value.copy(digitalExpressions =
    value.digitalExpressions.map(expression =>
      if expression.path == path then update(expression) else expression
    )
  )

  val tests: Tests = Tests:
    test("signed quotient widens and remainder truncates toward zero for all sign combinations"):
      for numerator <- Vector(-7, 7); divisor <- Vector(-3, 3) do
        val a = numerator.S(4)
        val b = divisor.S(3)
        val quotient = a / b
        val remainder = a % b
        assert(width(quotient) == 5, width(remainder) == 4)
        assert(exact(quotient) == BigInt(numerator / divisor))
        assert(exact(remainder) == BigInt(numerator % divisor))
        assert(exact(quotient) * divisor + exact(remainder) == numerator)
        assert(exact(remainder).signum == (if numerator < 0 then -1 else 1))
        assert(kernel(quotient).operands == Vector(a, b))
      val minimum = (-8).S(4)
      assert(exact(minimum / (-1).S(1)) == 8)
      assert(exact(minimum % (-1).S(1)) == 0)
      assert(width(minimum / (-1).S(1)) == 5)
      assert(exact(Int.MinValue.S(32) / (-1).S(1)) == BigInt(Int.MaxValue) + 1)
      assert(exact(0.S(1) / (-1).S(1)) == 0)

    test("unsigned division and same-kind min max preserve mathematical values and exact widths"):
      val numerator = Int.MaxValue.U(31)
      assert(width(numerator / 3.U(2)) == 31, width(numerator % 3.U(2)) == 31)
      assert(exact(numerator / 3.U(2)) == BigInt(Int.MaxValue / 3))
      assert(exact(numerator % 3.U(2)) == BigInt(Int.MaxValue % 3))
      assert(exact(1.U(1) / 255.U(8)) == 0, exact(1.U(1) % 255.U(8)) == 1)
      assert(width(1.U(1).min(255.U(8))) == 8)
      assert(exact(1.U(1).min(255.U(8))) == 1, exact(1.U(1).max(255.U(8))) == 255)
      assert(width((-8).S(4).min(7.S(8))) == 8)
      assert(exact((-8).S(4).min(7.S(8))) == -8)
      assert(exact((-8).S(4).max(7.S(8))) == 7)
      assert(exact(1.U(Int.MaxValue) / 1.U(1)) == 1)
      assert(width(1.U(Int.MaxValue).max(1.U(1))) == Int.MaxValue)
      assert(failure(1.S(Int.MaxValue) / 1.S(2)) == "NODAL-DIGITAL-055-002")
      assert(failure(ConstructionKernel.inspect(new DigitalArithmeticSymbolicOverflowSource)) ==
        "NODAL-DIGITAL-055-002")

    test(
      "divisor proofs accept computed constants and reject zero runtime defaults and fake facts"
    ):
      assert(exact(7.U(3) / (1.U(1) + 1.U(1))) == 3)
      assert(exact(7.U(3) % (1.U(1) + 1.U(1))) == 1)
      assert(failure(1.U(1) / 0.U(1)) == "NODAL-DIGITAL-055-005")
      assert(failure(1.S(2) % (1.S(2) - 1.S(2))) == "NODAL-DIGITAL-055-005")
      Vector(false, true).foreach(parameter =>
        assert(failure(
          ConstructionKernel.inspect(new DigitalArithmeticUnknownDivisorSource(parameter))
        ) ==
          "NODAL-DIGITAL-055-005")
      )
      val forged = new KernelExpr[UInt](
        Vector.empty,
        resultType = Some(KernelTypeDescriptor("UInt", Vector(1))),
        literal = Some(KernelLiteral("integer", "2", KernelTypeDescriptor("UInt", Vector(1)))),
        operation = Some("integer")
      )
      assert(failure(1.U(1) / forged) == "NODAL-DIGITAL-055-005")
      val zeroGraph = new KernelExpr[SInt](
        Vector(1.S(2), 0.S(2)),
        resultType = Some(KernelTypeDescriptor("SInt", Vector(3))),
        operation = Some("digital_div")
      )
      assert(DigitalScalarContract.knownValue(zeroGraph).isEmpty)
      val nominal = enumLiteral(EnumType[DigitalSourceMode](), DigitalSourceMode.Run)
      for operation <- Vector("digital_div", "digital_rem", "digital_min", "digital_max") do
        assert(failure(DigitalCombinationalContract.arithmetic[UInt](operation, 1.U(2), 1.S(2))) ==
          "NODAL-DIGITAL-055-001")
        val bits = 1.U(2).extract(0, 2)
        assert(failure(DigitalCombinationalContract.arithmetic[Bits](operation, bits, bits)) ==
          "NODAL-DIGITAL-055-001")
        assert(failure(DigitalCombinationalContract.arithmetic[UInt](
          operation,
          nominal,
          nominal
        )) ==
          "NODAL-DIGITAL-055-001")

    test("bounded proof magnitude and depth fail closed without substituting a default"):
      val enormous = 1.U(1) << 4096
      assert(failure(1.U(1) / enormous) == "NODAL-DIGITAL-055-005")
      var divisor = 1.U(1)
      (0 until 129).foreach(_ => divisor = divisor + 0.U(1))
      assert(failure(1.U(1) % divisor) == "NODAL-DIGITAL-055-005")

    test("symbolic quotient and min max keep canonical expressions and actual collection proofs"):
      val captured = ConstructionKernel.capture(new DigitalArithmeticSource): module =>
        val quotientWidth = width(module.signedQuotient).asInstanceOf[KernelExpr[Integer]]
        assert(quotientWidth.operation.contains("analog_add"))
        assert(quotientWidth.operands.head == module.width)
        val maxWidth = width(module.minimum).asInstanceOf[KernelExpr[Integer]]
        assert(maxWidth.operation.contains("static_integer_max"))
        assert(maxWidth.operands == Vector(module.width, module.otherWidth))
        assert(width(module.unsignedQuotient) == module.width)
        Vector(module.signedQuotient, module.minimum, module.divided)
      val document = ScalaToMlirBridge.fromSnapshot(captured.snapshot)
      Vector("digital_div", "digital_rem", "digital_min", "digital_max").foreach(operation =>
        assert(document.text.contains(s"operator_name = \"$operation\""))
      )
      assert(document.text.contains("nodal.collection"))

    test("bridge independently rejects forged divisors result kinds widths and attached facts"):
      val captured = snapshot
      val signed = captured.digitalExpressions.find(value =>
        value.operation == "digital_div" && value.dataType.startsWith("SInt(") &&
          value.operands.head == s"${captured.root}.signed"
      ).get
      val minimum = captured.digitalExpressions.find(value =>
        value.operation == "digital_min" && value.dataType.startsWith("SInt(")
      ).get
      val zero = captured.digitalExpressions.find(value =>
        value.operation == "integer" && value.dataType.startsWith("SInt(") &&
          value.literal.contains("-1")
      ).get
      rejected(change(captured, signed.path)(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.signed"))
      ))
      rejected(change(captured, signed.path)(_.copy(literal = Some("2"))))
      rejected(change(captured, signed.path)(_.copy(dataType = "SInt(width)")))
      rejected(change(captured, signed.path)(value =>
        value.copy(dataType = value.dataType.replace("SInt(", "UInt("))
      ))
      rejected(change(captured, signed.path)(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.unsigned"))
      ))
      rejected(change(captured, zero.path)(_.copy(literal = Some("0"))))
      rejected(change(captured, minimum.path)(_.copy(dataType = "SInt(width)")))
      val quotientWidth = captured.parameterExpressions.find(value =>
        value.operation == "analog_add" && value.operands.contains(s"${captured.root}.width")
      ).get
      rejected(captured.copy(parameterExpressions =
        captured.parameterExpressions.map(value =>
          if value.path == quotientWidth.path then
            value.copy(operands =
              value.operands.map(operand =>
                if operand == s"${captured.root}.width" then s"${captured.root}.otherWidth"
                else operand
              )
            )
          else value
        )
      ))
      val huge = KernelDigitalExpressionSnapshot(
        captured.root + ".hugeDivisor",
        captured.root,
        "integer",
        Vector.empty,
        "SInt(4098)",
        Some((BigInt(1) << 4096).toString),
        None,
        Vector.empty
      )
      val hugeDivisor = change(captured, signed.path)(value =>
        value.copy(operands = value.operands.updated(1, huge.path))
      )
      rejected(hugeDivisor.copy(digitalExpressions = hugeDivisor.digitalExpressions :+ huge))

    test(
      "captured divisor proof follows the actual outer value and cannot trust formal annotations"
    ):
      val captured = snapshot
      val collection = captured.digitalCollections.find(_.captures.nonEmpty).get
      val replacement = collection.copy(captures = Vector(s"${captured.root}.runtimeDivisor"))
      rejected(captured.copy(
        digitalCollections = captured.digitalCollections.map(value =>
          if value.path == collection.path then replacement else value
        ),
        digitalExpressions = captured.digitalExpressions.map(value =>
          if value.path == collection.path then
            value.copy(operands = replacement.inputs ++ replacement.seed ++ replacement.captures)
          else value
        )
      ))

    test("actual source arithmetic and collection literals validate and round trip through native"):
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )
      val directory = Path.of(".work", "increment55-arithmetic").toAbsolutePath
      Files.createDirectories(directory)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      Files.writeString(directory.resolve("source.mlir"), document.text)
      def native(value: NodalMlirDocument): String = NativeCompilerClient.run(
        value,
        NativeCompilerRequest(
          Path.of(compiler).toAbsolutePath,
          Vector.empty,
          directory,
          timeout = Duration.ofSeconds(90)
        )
      ) match
        case success: NativeCompilerSuccess => success.normalizedMlir
        case failure: NativeCompilerFailure =>
          scala.util.Failure[Nothing](new java.lang.AssertionError(
            s"${failure.diagnostic}\n${failure.standardError}"
          )).get
      val normalized = native(document)
      Files.writeString(directory.resolve("normalized.mlir"), normalized)
      assert(native(document.copy(text = normalized)) == normalized)
