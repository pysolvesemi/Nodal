package nodal.internal.testkit

import nodal.*
import utest.*

final class DigitalConversionWidthsSource extends Module:
  val width = param(8.integer, range = 3 to 8)
  val input = in(UInt(width))
  val signed = input.toSigned
  val unsigned = signed.toUnsigned
  val positive = 7.U(width)
  val negative = (-4).S(width)
  val checked = positive.resizeChecked(3)
  val extended = input.extend(8)
  val truncated = input.truncate(3)
  val saturated = input.saturate(3)
  val wrapped = input.wrap(3)
  val reinterpreted = input.reinterpretSigned
  val real = toReal(input)

final class DigitalSymbolicLiteralSource(signed: Boolean, lower: Int) extends Module:
  val width = param(8.integer, range = lower to 8)
  val value = if signed then 7.S(width) else 7.U(width)

final class DigitalDynamicWidthLiteralSource extends Module:
  val width = in(Integer)
  val value = 1.U(width)

final class DigitalCheckedSignalSource extends Module:
  val input = in(UInt(8))
  val value = input.resizeChecked(7)

final class DigitalUnsignedSignalSource extends Module:
  val input = in(SInt(8))
  val value = input.toUnsigned

final class DigitalRealSignalSource(width: Int) extends Module:
  val input = in(UInt(width))
  val value = toReal(input)

final class DigitalUnsignedParameterSource extends Module:
  val input = param(1.S(8))
  val value = input.toUnsigned

object Increment54DigitalConversionTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  private def kernel(value: Expr[?]): KernelExpr[?] = value.asInstanceOf[KernelExpr[?]]

  val tests: Tests = Tests:
    test("resizes have closed operations exact types and real operand edges"):
      val source = 5.U(8)
      val values = Vector(
        (source.extend(10), "digital_extend", 10),
        (source.truncate(3), "digital_truncate", 3),
        (source.wrap(3), "digital_wrap", 3),
        (source.saturate(3), "digital_saturate", 3),
        (source.resizeChecked(3), "digital_resize_checked", 3)
      )
      values.foreach: (value, operation, width) =>
        assert(kernel(value).operation.contains(operation))
        assert(kernel(value).resultDescriptor.contains(KernelTypeDescriptor("UInt", Vector(width))))
        assert(kernel(value).operands == Vector(source))
        assert(kernel(value).literal.isEmpty)
      assert(failure(source.extend(7)) == "NODAL-DIGITAL-054-007")
      assert(failure(source.truncate(9)) == "NODAL-DIGITAL-054-007")
      assert(failure(source.wrap(0)) == "NODAL-DIGITAL-054-001")
      assert(failure(source.resizeChecked(2)) == "NODAL-DIGITAL-054-008")

    test("numeric signedness conversion differs from equal-width reinterpretation"):
      val source = 255.U(8)
      val numeric = source.toSigned
      val reinterpreted = source.reinterpretSigned
      assert(kernel(numeric).resultDescriptor.contains(KernelTypeDescriptor("SInt", Vector(9))))
      assert(kernel(reinterpreted).resultDescriptor.contains(KernelTypeDescriptor(
        "SInt",
        Vector(8)
      )))
      assert(kernel(numeric).operation.contains("digital_to_signed"))
      assert(kernel(reinterpreted).operation.contains("digital_reinterpret_signed"))
      assert(kernel(numeric.toUnsigned).resultDescriptor.contains(KernelTypeDescriptor(
        "UInt",
        Vector(9)
      )))
      assert(kernel(127.S(8).toUnsigned).resultDescriptor.contains(KernelTypeDescriptor(
        "UInt",
        Vector(8)
      )))
      assert(failure(reinterpreted.toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(failure((-1).S(8).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(failure((-1).S(8).reinterpretUnsigned.resizeChecked(7)) == "NODAL-DIGITAL-054-008")

    test("constant proofs preserve distinct wrap truncate and saturation results"):
      assert(kernel(4.S(4).saturate(2).toUnsigned).operation.contains("digital_to_unsigned"))
      assert(kernel(4.S(4).wrap(2).toUnsigned).operation.contains("digital_to_unsigned"))
      assert(failure(4.S(4).truncate(3).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(failure((-4).S(4).saturate(2).toUnsigned) == "NODAL-DIGITAL-054-008")
      assert(kernel((-4).S(4).resizeChecked(3)).resultDescriptor
        .contains(KernelTypeDescriptor("SInt", Vector(3))))
      assert(failure((-4).S(4).resizeChecked(2)) == "NODAL-DIGITAL-054-008")
      assert(kernel(0.U(1).extend(Int.MaxValue)).resultDescriptor
        .contains(KernelTypeDescriptor("UInt", Vector(Int.MaxValue))))

    test("checked narrowing and sign changes never use runtime values or parameter defaults"):
      assert(failure(ConstructionKernel.inspect(new DigitalCheckedSignalSource)) ==
        "NODAL-DIGITAL-054-008")
      assert(failure(ConstructionKernel.inspect(new DigitalUnsignedSignalSource)) ==
        "NODAL-DIGITAL-054-008")
      assert(failure(ConstructionKernel.inspect(new DigitalUnsignedParameterSource)) ==
        "NODAL-DIGITAL-054-008")

    test("symbolic literals and conversions preserve the actual width expression"):
      val captured = ConstructionKernel.capture(new DigitalConversionWidthsSource): module =>
        assert(kernel(module.positive).resultDescriptor
          .contains(KernelTypeDescriptor("UInt", Vector(module.width))))
        assert(kernel(module.negative).resultDescriptor
          .contains(KernelTypeDescriptor("SInt", Vector(module.width))))
        val signedWidth = kernel(module.signed).resultDescriptor.get.arguments.head
          .asInstanceOf[KernelExpr[Integer]]
        assert(signedWidth.operation.contains("analog_add"))
        assert(signedWidth.operands.head == module.width)
        assert(kernel(module.reinterpreted).resultDescriptor
          .contains(KernelTypeDescriptor("SInt", Vector(module.width))))
        assert(kernel(module.checked).resultDescriptor
          .contains(KernelTypeDescriptor("UInt", Vector(3))))
        Vector(module.signed, module.positive, module.negative)
      assert(captured.snapshot.modules.nonEmpty)
      assert(failure(ConstructionKernel.inspect(new DigitalSymbolicLiteralSource(false, 2))) ==
        "NODAL-DIGITAL-054-002")
      assert(failure(ConstructionKernel.inspect(new DigitalSymbolicLiteralSource(true, 3))) ==
        "NODAL-DIGITAL-054-002")
      assert(failure(ConstructionKernel.inspect(new DigitalSymbolicLiteralSource(false, 0))) ==
        "NODAL-DIGITAL-054-001")
      assert(failure(ConstructionKernel.inspect(new DigitalDynamicWidthLiteralSource)) ==
        "NODAL-DIGITAL-054-001")

    test("conversion families reject unsupported source types instead of inert success"):
      assert(failure(1.0.real.extend(8)) == "NODAL-DIGITAL-054-005")
      assert(failure(true.B.resizeChecked(8)) == "NODAL-DIGITAL-054-005")
      assert(failure(CandidateRuntime.expr[UInt]("opaque").resizeChecked(8)) ==
        "NODAL-DIGITAL-054-005")
      assert(kernel(1.S(8.integer)).resultDescriptor.exists(_.kind == "SInt"))
      assert(kernel(1.U(8.integer)).resultDescriptor.exists(_.kind == "UInt"))

    test("explicit numeric Real conversion permits rounding but rejects unproved overflow"):
      val captured = ConstructionKernel.inspect(new DigitalRealSignalSource(54))
      assert(captured.modules.nonEmpty)
      assert(kernel(toReal(1.U(2048))).operation.contains("digital_to_real"))
      assert(failure(ConstructionKernel.inspect(new DigitalRealSignalSource(1024))) ==
        "NODAL-DIGITAL-054-008")
