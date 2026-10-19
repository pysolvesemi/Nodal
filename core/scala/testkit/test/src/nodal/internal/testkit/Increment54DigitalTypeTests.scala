package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalTypeCarrier(dataType: DataType[? <: Data]) extends Module:
  val item = wire(dataType)

final class DigitalNestedTypeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(3.integer, range = 1 to 8)
  val rows = param(2.integer, range = 1 to 4)
  val record = Struct(
    "Payload",
    StructField("signed", SInt(width + 1.integer)),
    StructField("samples", Vec(UInt(width), 2, rows)),
    StructField("nested", Struct("Tag", StructField("valid", Bool), StructField("code", UInt(3))))
  )
  val input = in(Vec(record, rows, 2))

final class DigitalUnprovedWidthSource(lower: Int) extends Module:
  val width = param(3.integer, range = lower to 8)
  val value = wire(UInt(width))

object Increment54DigitalTypeTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("recursive records retain ordered shape and exact symbolic leaf widths"):
      val first = ScalaToMlirBridge.lower(new DigitalNestedTypeSource)
      assert(first == ScalaToMlirBridge.lower(new DigitalNestedTypeSource))
      assert(first.text.contains("!nodal.struct<\"Payload\""))
      assert(first.text.contains("!nodal.struct<\"Tag\""))
      assert(first.text.contains("!nodal.digital<\"uint\", \"width\">"))
      assert(first.text.contains("!nodal.digital<\"sint\""))
      assert(first.text.contains("\"nodal.const_expr\""))
      assert(first.text.contains("!nodal.shaped<\"rows,2\""))

    test("digital declarations require positive proven widths"):
      assert(failure(ConstructionKernel.inspect(new DigitalTypeCarrier(UInt(0)))) ==
        "NODAL-DIGITAL-054-001")
      assert(failure(ConstructionKernel.inspect(new DigitalUnprovedWidthSource(0))) ==
        "NODAL-DIGITAL-054-001")

    test("records require nonempty unique portable storable fields"):
      for dataType <- Vector[DataType[? <: Data]](
          Struct("Empty"),
          Struct("Duplicate", StructField("a", UInt(1)), StructField("a", SInt(2))),
          Struct("BadName", StructField("a:b", UInt(1))),
          Struct("ClockField", StructField("clock", Clock))
        )
      do
        assert(failure(ConstructionKernel.inspect(new DigitalTypeCarrier(dataType))) ==
          "NODAL-DIGITAL-054-004")

    test("nested digital footprint fails before expansion"):
      val dataType = Vec(UInt(Int.MaxValue), Int.MaxValue, Int.MaxValue)
      assert(failure(ConstructionKernel.inspect(new DigitalTypeCarrier(dataType))) ==
        "NODAL-DIGITAL-054-004")

    test("forged native type strings do not bypass independent bridge validation"):
      val snapshot = ConstructionKernel.inspect(new DigitalNestedTypeSource)
      val module = snapshot.modules.head
      val bad = module.declarations.map: declaration =>
        if declaration.name == "input" then declaration.copy(dataType = Some("UInt(missing)"))
        else declaration
      val forged = snapshot.copy(modules = Vector(module.copy(declarations = bad)))
      val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(error.isInstanceOf[BridgeException])
