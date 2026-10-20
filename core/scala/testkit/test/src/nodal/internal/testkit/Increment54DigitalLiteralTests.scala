package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class DigitalSignedParameterSource extends Module:
  val minimum = param((-128).S(8))
  val maximum = param(127.S(8))
  val unsigned = param(255.U(8))

object Increment54DigitalLiteralTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("signed extrema retain exact typed literal values"):
      for (value, width) <- Vector(
          (-1, 1),
          (0, 1),
          (-128, 8),
          (127, 8),
          (Int.MinValue, 32),
          (Int.MaxValue, 32)
        )
      do
        val expression = value.S(width).asInstanceOf[KernelExpr[SInt]]
        assert(expression.resultDescriptor.contains(KernelTypeDescriptor("SInt", Vector(width))))
        assert(expression.literal.exists(_.value == value.toString))
        assert(expression.operation.contains("integer"))

    test("unsigned extrema preserve nonnegative magnitude"):
      for (value, width) <- Vector((0, 1), (1, 1), (255, 8), (Int.MaxValue, 31)) do
        val expression = value.U(width).asInstanceOf[KernelExpr[UInt]]
        assert(expression.literal.exists(_.value == value.toString))
        assert(expression.resultDescriptor.contains(KernelTypeDescriptor("UInt", Vector(width))))

    test("invalid widths and overflowing signed or unsigned values reject"):
      assert(failure(0.S(0)) == "NODAL-DIGITAL-054-001")
      assert(failure(0.U(-1)) == "NODAL-DIGITAL-054-001")
      for expression <- Vector[() => Any](
          () => 1.S(1),
          () => (-2).S(1),
          () => 128.S(8),
          () => (-129).S(8),
          () => (-1).U(8),
          () => 256.U(8)
        )
      do assert(failure(expression()) == "NODAL-DIGITAL-054-002")

    test("large declared width does not allocate its magnitude"):
      assert(1.S(Int.MaxValue).asInstanceOf[KernelExpr[SInt]].literal.exists(_.value == "1"))
      assert(1.U(Int.MaxValue).asInstanceOf[KernelExpr[UInt]].literal.exists(_.value == "1"))

    test("signed parameter defaults reach native typed declarations"):
      val snapshot = ConstructionKernel.inspect(new DigitalSignedParameterSource)
      assert(snapshot.modules.head.declarations.find(_.name == "minimum").get.dataType
        .contains("SInt(8)"))
      val lowered = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(lowered.text.contains("!nodal.sint<8>"))
      assert(lowered.text.contains("-128"))
      assert(lowered == ScalaToMlirBridge.lower(new DigitalSignedParameterSource))
