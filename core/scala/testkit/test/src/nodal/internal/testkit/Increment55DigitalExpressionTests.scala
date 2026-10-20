package nodal.internal.testkit

import nodal.*
import nodal_external.Increment55PublicOperators
import utest.*

final class DigitalExpressionWidthsSource extends Module:
  val width = param(4.integer, range = 2 to 9)
  val otherWidth = param(4.integer, range = 3 to 7)
  val left = in(UInt(width))
  val right = in(UInt(otherWidth))
  val sum = left + right
  val difference = left - right
  val product = left * right
  val negative = -left
  val shift = left << 2
  val compare = left < right
  val condition = compare && !false.B
  val selected = select(condition, left, left)
  val extracted = left.extract(0, 2)
  val joined = left.concat(right)

final class DigitalExpressionWideSymbolicSource extends Module:
  val width = param(Int.MaxValue.integer, range = (Int.MaxValue - 1) to Int.MaxValue)
  val input = in(UInt(width))
  val result = input + input

final class DigitalExpressionWidthOverflowSource extends Module:
  val factor = param(1.integer, range = 1 to 2)
  val width = factor * Int.MaxValue.integer * Int.MaxValue.integer
  val input = in(UInt(width))
  val result = input * input

final class DigitalExpressionLiteralProofSource extends Module:
  val width = param(8.integer, range = 3 to 8)
  val literal = 3.U(width)
  val result = (literal + literal).resizeChecked(3)

final class DigitalExpressionUnknownProofSource extends Module:
  val value = param(1.U(8))
  val result = (value + 1.U(8)).resizeChecked(2)

final class DigitalExpressionFreshWidthSource extends Module:
  val width = param(4.integer, range = 2 to 8)
  val first = in(UInt(width))
  val second = in(UInt(width))
  val selected = select(true.B, first + second, second + first)
  val bitwise = (first + second) & (second + first)
  val predecessor = select(true.B, first.toSigned, second.toSigned)
  val explicitWidth = in(UInt(width + 1.integer))
  val explicitSelected = select(true.B, first + second, explicitWidth)

final class DigitalExpressionFreshMismatchSource extends Module:
  val width = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val first = in(UInt(width))
  val different = in(UInt(otherWidth))
  val result = select(true.B, first + first, different + different)

final class DigitalExpressionBitwiseIdentitySource extends Module:
  val width = param(8.integer, range = 2 to 8)
  val otherWidth = param(8.integer, range = 2 to 8)
  val left = in(UInt(width))
  val right = in(UInt(otherWidth))
  val result = left & right

final class DigitalExpressionExtractionBoundsSource extends Module:
  val width = param(8.integer, range = 2 to 8)
  val left = in(UInt(width))
  val result = left.extract(0, 3)

final class DigitalExpressionPublicSource extends Module:
  val unsignedLeft = in(UInt(4))
  val unsignedRight = in(UInt(4))
  val signedLeft = in(SInt(4))
  val signedRight = in(SInt(4))
  val bitsLeft = in(Bits(4))
  val bitsRight = in(Bits(4))
  val unsigned = Increment55PublicOperators.unsigned(unsignedLeft, unsignedRight)
  val signed = Increment55PublicOperators.signed(signedLeft, signedRight)
  val bits = Increment55PublicOperators.bits(bitsLeft, bitsRight)

final class DigitalExpressionAnalogIsolationSource extends Module:
  val left = in(Real)
  val right = in(Real)
  val predicate = (left < right) && !false.B

final class DigitalExpressionAggregateSelectSource extends Module:
  val condition = in(Bool)
  val left = in(Vec(SInt(4), 2, 3))
  val right = in(Vec(SInt(4), 2, 3))
  val result = select(condition, left, right)

object Increment55DigitalExpressionTests extends TestSuite:
  private def kernel(value: Expr[?]): KernelExpr[?] = value.asInstanceOf[KernelExpr[?]]
  private def kind(value: Expr[?]): String = kernel(value).resultDescriptor.get.kind
  private def width(value: Expr[?]): Any = kernel(value).resultDescriptor.get.arguments.head
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("lossless arithmetic computes exact widths signs and source operand order"):
      val left = 15.U(4)
      val right = 3.U(2)
      val sum = left + right
      val difference: Expr[SInt] = left - right
      val product = left * right
      val negative: Expr[SInt] = -left
      assert(kind(sum) == "UInt", width(sum) == 5)
      assert(kind(difference) == "SInt", width(difference) == 5)
      assert(kind(product) == "UInt", width(product) == 6)
      assert(kind(negative) == "SInt", width(negative) == 5)
      assert(kernel(sum).operands == Vector(left, right))
      assert(kernel(sum).operation.contains("digital_add"))
      val signed = (-8).S(4)
      assert(kind(signed + 3.S(3)) == "SInt", width(signed + 3.S(3)) == 5)
      assert(width(signed - 3.S(3)) == 5, width(signed * 3.S(3)) == 7)
      assert(width(-signed) == 5)
      assert(failure(1.U(Int.MaxValue) + 1.U(1)) == "NODAL-DIGITAL-055-002")
      assert(failure(1.U(Int.MaxValue) * 1.U(1)) == "NODAL-DIGITAL-055-002")
      assert(failure(-(1.U(Int.MaxValue))) == "NODAL-DIGITAL-055-002")

    test("symbolic widths retain signed64 bounds beyond the concrete Int carrier"):
      val captured = ConstructionKernel.capture(new DigitalExpressionWideSymbolicSource): module =>
        val bounds = DigitalScalarContract.widthBounds(kernel(module.result).resultDescriptor.get)
        assert(bounds == (Int.MaxValue.toLong, Int.MaxValue.toLong + 1))
        Vector(module.result)
      assert(captured.snapshot.parameterExpressions.exists(_.operation == "static_integer_max"))
      assert(failure(ConstructionKernel.inspect(new DigitalExpressionWidthOverflowSource)) ==
        "NODAL-DIGITAL-055-002")

    test("constant arithmetic and predicates prove checked conversions without folding the DAG"):
      val sum = (3.U(2) + 4.U(3)).resizeChecked(3)
      assert(kernel(sum).operation.contains("digital_resize_checked"))
      assert(width((15.U(4) - 14.U(4)).toUnsigned.resizeChecked(1)) == 1)
      assert(failure((1.U(1) - 2.U(2)).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(width(((-8).S(4) * (-8).S(4)).toUnsigned.resizeChecked(7)) == 7)
      assert(width((-(-8).S(4)).toUnsigned.resizeChecked(4)) == 4)
      assert(failure((3.U(2) + 4.U(3)).resizeChecked(2)) == "NODAL-DIGITAL-054-008")
      val condition = (1.U(1) < 2.U(2)) && !(3.S(3) =/= 3.S(3))
      assert(width(select(condition, 1.U(8), 255.U(8)).resizeChecked(1)) == 1)
      assert(width(select(3.U(3) >= 2.U(3), 1.U(8), 255.U(8)).resizeChecked(1)) == 1)
      assert(width(select(3.U(3) > 2.U(3), 1.U(8), 255.U(8)).resizeChecked(1)) == 1)
      assert(width(select(2.U(3) <= 2.U(3), 1.U(8), 255.U(8)).resizeChecked(1)) == 1)
      assert(width(select(2.U(3) === 2.U(3), 1.U(8), 255.U(8)).resizeChecked(1)) == 1)
      assert(ConstructionKernel.inspect(new DigitalExpressionLiteralProofSource).modules.nonEmpty)
      assert(failure(ConstructionKernel.inspect(new DigitalExpressionUnknownProofSource)) ==
        "NODAL-DIGITAL-054-008")

    test("shared constant proof DAGs are memoized and bounded without arithmetic expansion"):
      var shared = 0.U(1)
      (0 until 64).foreach: _ =>
        shared = shared + shared
      assert(width(shared.resizeChecked(1)) == 1)
      (0 until 80).foreach: _ =>
        shared = shared + shared
      assert(failure(shared.resizeChecked(1)) == "NODAL-DIGITAL-054-008")

    test("numeric and bitwise contracts reject mixed kinds and accidental Bits arithmetic"):
      val unsigned = 1.U(3)
      val signed = 1.S(3)
      val bits = unsigned.extract(0, 3)
      assert(failure(DigitalCombinationalContract.arithmetic[UInt](
        "digital_add",
        unsigned,
        signed
      )) ==
        "NODAL-DIGITAL-055-001")
      assert(failure(DigitalCombinationalContract.arithmetic[Bits]("digital_add", bits, bits)) ==
        "NODAL-DIGITAL-055-001")
      assert(failure(DigitalCombinationalContract.compare("digital_lt", unsigned, signed)) ==
        "NODAL-DIGITAL-055-001")
      assert(failure(DigitalCombinationalContract.compare("digital_lt", bits, bits)) ==
        "NODAL-DIGITAL-055-001")
      assert(failure(unsigned & 1.U(4)) == "NODAL-DIGITAL-055-003")
      assert(failure(bits === unsigned.extract(0, 2)) == "NODAL-DIGITAL-055-001")
      assert(failure(ConstructionKernel.inspect(new DigitalExpressionBitwiseIdentitySource)) ==
        "NODAL-DIGITAL-055-003")
      assert(failure(DigitalCombinationalContract.complement(1.real)) == "NODAL-DIGITAL-055-001")

    test("bitwise and extraction preserve exact encoded values including signed operands"):
      assert(width((5.U(4) & 3.U(4)).resizeChecked(1)) == 1)
      assert(width((5.U(4) | 2.U(4)).resizeChecked(3)) == 3)
      assert(width((5.U(4) ^ 4.U(4)).resizeChecked(1)) == 1)
      assert(width((~14.U(4)).resizeChecked(1)) == 1)
      assert(width((~(-2).S(4)).toUnsigned.resizeChecked(1)) == 1)
      val joined = (-1).S(2).concat(1.U(2))
      assert(kind(joined) == "Bits", width(joined) == 4)
      assert(failure(joined.resizeChecked(3)) == "NODAL-DIGITAL-054-008")
      assert(width(joined.extract(0, 2).resizeChecked(1)) == 1)
      assert(width(joined.extract(2, 4)) == 2)
      assert(failure(joined.extract(0, 0)) == "NODAL-DIGITAL-055-004")
      assert(failure(joined.extract(-1, 1)) == "NODAL-DIGITAL-055-004")
      assert(failure(joined.extract(0, 5)) == "NODAL-DIGITAL-055-004")
      assert(failure(ConstructionKernel.inspect(new DigitalExpressionExtractionBoundsSource)) ==
        "NODAL-DIGITAL-055-004")
      val extraction = kernel(joined.extract(1, 4))
      assert(extraction.operands.tail.forall(_.isInstanceOf[KernelExpr[?]]))
      assert(extraction.operands.tail.map(_.asInstanceOf[KernelExpr[?]].literal.get.value) ==
        Vector("1", "4"))

    test("static shifts distinguish signed arithmetic and logical right with lossless left width"):
      val negative = (-8).S(4)
      assert(width(negative << 3) == 7)
      assert(width(negative >> 1) == 4)
      assert(failure((negative >> 1).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(width(negative.logicalShiftRight(1).toUnsigned.resizeChecked(3)) == 3)
      assert(failure(negative.logicalShiftRight(0).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(width((negative >> Int.MaxValue).resizeChecked(1)) == 1)
      assert(width(negative.logicalShiftRight(Int.MaxValue).toUnsigned.resizeChecked(1)) == 1)
      assert(width(3.U(2) << 0) == 2, width(3.U(2) << 2) == 4)
      assert(failure(negative << -1) == "NODAL-DIGITAL-055-004")
      assert(failure(negative >> -1) == "NODAL-DIGITAL-055-004")
      assert(failure(negative << Int.MaxValue) == "NODAL-DIGITAL-055-002")
      assert(kernel(negative << 2).operands(1).asInstanceOf[KernelExpr[?]].literal.get.value == "2")
      assert(width((3.U(2).extract(0, 2) >> 1).resizeChecked(1)) == 1)

    test("conditional exact type excludes analog scalars and clock reset capabilities"):
      assert(failure(select(true.B, 1.U(1), 1.U(2))) == "NODAL-DIGITAL-055-003")
      assert(failure(select(true.B, 1.V, 1.A)) == "NODAL-DIGITAL-055-001")
      assert(failure(select(true.B, 1.integer, 1.integer)) == "NODAL-DIGITAL-055-001")
      assert(failure(select(
        true.B,
        CandidateRuntime.expr[Clock](),
        CandidateRuntime.expr[Clock]()
      )) ==
        "NODAL-DIGITAL-055-001")
      val captured = ConstructionKernel.inspect(new DigitalExpressionAggregateSelectSource)
      assert(captured.digitalExpressions.exists(value =>
        value.operation == "digital_select" &&
          value.dataType == "Vec(SInt(4);2x3)"
      ))

    test("symbolic widths retain max add identities with no default specialization"):
      ConstructionKernel.capture(new DigitalExpressionWidthsSource): module =>
        val sumWidth = width(module.sum).asInstanceOf[KernelExpr[Integer]]
        val maximum = sumWidth.operands.head.asInstanceOf[KernelExpr[Integer]]
        assert(sumWidth.operation.contains("analog_add"))
        assert(maximum.operation.contains("static_integer_max"))
        assert(maximum.operands == Vector(module.width, module.otherWidth))
        val productWidth = width(module.product).asInstanceOf[KernelExpr[Integer]]
        assert(productWidth.operation.contains("analog_add"))
        assert(productWidth.operands == Vector(module.width, module.otherWidth))
        assert(width(module.selected) == module.width)
        assert(width(module.extracted) == 2)
        Vector(module.sum, module.product, module.selected)

    test("fresh equivalent static width DAGs preserve complete types without equating parameters"):
      val captured = ConstructionKernel.capture(new DigitalExpressionFreshWidthSource): module =>
        Vector(module.selected, module.bitwise, module.predecessor, module.explicitSelected)
      assert(captured.snapshot.digitalExpressions.count(_.operation == "digital_select") == 3)
      assert(captured.snapshot.digitalExpressions.exists(_.operation == "digital_and"))
      assert(failure(ConstructionKernel.inspect(new DigitalExpressionFreshMismatchSource)) ==
        "NODAL-DIGITAL-055-003")

    test("public companion extensions resolve across API compilation and capture every operation"):
      val snapshot = ConstructionKernel.inspect(new DigitalExpressionPublicSource)
      assert(DigitalCombinationalContract.Operations.subsetOf(
        snapshot.digitalExpressions.map(_.operation).toSet
      ))
      assert(snapshot.digitalExpressions.forall(_.operands.forall(!_.contains("default"))))
      assert(kernel(Increment55PublicOperators.analog(
        1.real,
        2.real
      )).operation.contains("analog_add"))

    test("unrelated analog Bool expressions are never seeded into the digital graph"):
      val snapshot = ConstructionKernel.inspect(new DigitalExpressionAnalogIsolationSource)
      assert(snapshot.digitalExpressions.isEmpty)
      val digital = ConstructionKernel.inspect(new DigitalExpressionWidthsSource)
      assert(digital.digitalExpressions.exists(_.operation == "bool_and"))
      assert(digital.digitalExpressions.exists(_.operation == "bool_not"))
