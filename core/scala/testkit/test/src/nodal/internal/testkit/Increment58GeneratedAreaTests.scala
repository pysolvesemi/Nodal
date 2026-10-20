package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class Increment58GeneratedForgedAccess extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield
        val _ = i
        new Increment58OrdinalArea
    val _ = GeneratedAreaRuntime.member[Register[UInt]](lanes(0), "readonly", "register")

final class Increment58GeneratedHiddenInventory(action: Int) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield
        val _ = i
        new Increment58GeneratedPrivateArea
    if action == 1 then
      val _ = GeneratedAreaRuntime.member[Register[Bool]](lanes(0), "secret", "register")
    else if action == 2 then
      val _ = GeneratedAreaRuntime.nested[Area](lanes(0), "nested")
    else if action == 3 then
      val forged = new GeneratedAreaRef[Increment58GeneratedPrivateArea](lanes, 9, Vector.empty)
      val _ = GeneratedAreaRuntime.member[Register[Bool]](forged, "visible", "register")

object Increment58GeneratedAreaTests extends TestSuite:
  private def rejected(code: String)(body: => Module): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(body)).failed.get
    assert(failure.getMessage.contains(code))

  val tests = Tests:
    test("required generated register structural-if and behavioral guards retain one template"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedAreaSource)
      assert(snapshot.generatedRegions.size == 1)
      assert(snapshot.structuralIfs.size == 1)
      assert(snapshot.behavioralIfs.size == 2)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
      assert(snapshot.digitalAssignments.size == 4)
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.ordinal == "0")
    test("million-lane envelope stores one family and nested storage profile"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedAreaScaleSource)
      val family = snapshot.generatedRegions.head
      assert(snapshot.generatedRegions.size == 1)
      assert(family.maximumTripCount == 1000000)
      assert(family.yieldedArea.nonEmpty)
      assert(family.exports.map(_.member).toSet == Set("stored", "readonly", "nested.other"))
      assert(family.exports.find(_.member == "readonly").exists(_.access == "read_only"))
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)
      assert(snapshot.digitalExpressions.count(_.generatedMember.nonEmpty) == 2)
    test("ordinal existence is independent of defaults and runtime storage"):
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedOrdinalSource(3, -1))
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedOrdinalSource(3, 3))
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedOrdinalSource(0, 0))
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedDefaultOnlySource)
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedRuntimeOrdinalSource)
    test("empty capture and nonzero lower-step retain ordinal semantics"):
      val empty = ConstructionKernel.inspect(new Increment58GeneratedEmptySource)
      assert(empty.generatedRegions.head.maximumTripCount == 0)
      val nonzero = ConstructionKernel.inspect(new Increment58GeneratedOrdinalSource(10, 0, 4, 2))
      val family = nonzero.generatedRegions.head
      assert(family.lower == "4" && family.step == "2" && family.maximumTripCount == 3)
      assert(nonzero.digitalExpressions.flatMap(_.generatedMember).head.ordinal == "0")
    test("export capability and source-domain provenance remain exact"):
      rejected("NODAL-AREA-058-CAPABILITY")(new Increment58GeneratedForgedAccess)
      rejected("NODAL-CDC-001")(new Increment58GeneratedForeignDomain)
    test("unrelated or independently spelled update roots remain rejected"):
      rejected("NODAL-STATE-001")(new Increment58GeneratedDuplicateRoots)
      rejected("NODAL-STATE-001")(new Increment58GeneratedIndependentRoots)
    test("legacy bridge cannot silently erase generated family metadata"):
      val failure = scala.util.Try(
        ScalaToMlirBridge.lower(new Increment58GeneratedAreaSource)
      ).failed.get
      assert(failure.getMessage.contains("NODAL-BRIDGE-058"))
    test("hidden and host keys cannot export capability but public aliases retain identity"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedHiddenInventory(0))
      val exports = snapshot.generatedRegions.head.exports
      assert(exports.map(_.member).toSet == Set("visible", "readonly"))
      assert(exports.map(_.target).distinct.size == 1)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)
      rejected("NODAL-AREA-058-EXPORT")(new Increment58GeneratedHiddenInventory(1))
      rejected("NODAL-AREA-058-MEMBER")(new Increment58GeneratedHiddenInventory(2))
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedHiddenInventory(3))
    test("legacy callback arities and generated yield arities remain unambiguous"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedRangeForms)
      assert(snapshot.generatedRegions.count(_.yieldedArea.nonEmpty) == 3)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 3)
    test("typed selected readonly member remains usable in a behavioral callback"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedSelectedWhen)
      assert(snapshot.generatedRegions.size == 1 && snapshot.behavioralIfs.size == 1)
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).exists(_.access == "read_only"))
    test("ordinary module and nested Area structural arms retain behavioral updates"):
      val snapshot = ConstructionKernel.inspect(new Increment58OrdinaryAreaStructuralWhen)
      assert(snapshot.generatedRegions.isEmpty)
      assert(snapshot.structuralIfs.size == 2 && snapshot.behavioralIfs.size == 4)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)
      assert(snapshot.digitalAssignments.size == 4)
