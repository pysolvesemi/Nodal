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

  private def bridgeRejected(snapshot: ConstructionSnapshot, message: String): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.getMessage.contains(message))

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
    test("bridge emits exact family storage export and static member operations"):
      val text = ScalaToMlirBridge.lower(new Increment58GeneratedAreaSource).text
      assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.digital_storage\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.generated_export\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.generated_member\"(")) == 1)
      assert(text.contains("!nodal.generated_family"))
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
      val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(member.region.isEmpty)
      assert(snapshot.behavioralIfs.head.trueArm.captures.contains(member.path))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val family = snapshot.generatedRegions.head
      family.exports.foreach(exported =>
        assert(text.contains(family.path + ".export." + exported.member))
      )
    test("ordinary module and nested Area structural arms retain behavioral updates"):
      val snapshot = ConstructionKernel.inspect(new Increment58OrdinaryAreaStructuralWhen)
      assert(snapshot.generatedRegions.isEmpty)
      assert(snapshot.structuralIfs.size == 2 && snapshot.behavioralIfs.size == 4)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)
      assert(snapshot.digitalAssignments.size == 4)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("guarded nested callbacks capture values at the exact legal enclosing scope"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedGuardedSelection)
      val members = snapshot.digitalExpressions.filter(_.generatedMember.nonEmpty)
      assert(members.size == 2 && members.forall(_.region.isEmpty))
      assert(snapshot.structuralIfs.size == 1 && snapshot.behavioralIfs.size == 3)
      assert(snapshot.structuralIfs.head.trueArm.captures.contains(members.head.path))
      assert(snapshot.structuralIfs.head.falseArm.captures.contains(members.last.path))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      // Guard-dependent existence is a separate required profile, never a default-based proof.
      rejected("NODAL-AREA-058-INDEX")(new Increment58GeneratedGuardOnlyExistence)
    test("bridge rejects forged exports capabilities type ordinal and raw storage escape"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedAreaSource)
      val family = snapshot.generatedRegions.head
      val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      def familyChanged(value: KernelGeneratedRegionSnapshot): ConstructionSnapshot =
        snapshot.copy(generatedRegions = Vector(value))
      def memberChanged(value: KernelDigitalExpressionSnapshot): ConstructionSnapshot =
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(original =>
            if original.path == member.path then value else original
          )
        )
      bridgeRejected(
        familyChanged(family.copy(exports = family.exports ++ family.exports)),
        "inventories must be unique"
      )
      bridgeRejected(
        familyChanged(family.copy(exports =
          Vector(
            family.exports.head.copy(target = snapshot.root + ".input")
          )
        )),
        "exact direct storage"
      )
      bridgeRejected(
        familyChanged(family.copy(exports =
          Vector(
            family.exports.head.copy(access = "read_only")
          )
        )),
        "cannot expand export access"
      )
      bridgeRejected(memberChanged(member.copy(dataType = "UInt(9)")), "exact export type")
      bridgeRejected(
        memberChanged(member.copy(generatedMember =
          member.generatedMember.map(
            _.copy(ordinal = "100")
          )
        )),
        "ordinal does not exist for every legal parameter setting"
      )
      bridgeRejected(familyChanged(family.copy(digitalBody = None)), "NODAL-BRIDGE-058")
      bridgeRejected(
        memberChanged(member.copy(generatedMember = None)),
        "only generated member operations"
      )
      val raw = snapshot.digitalAssignments.last.copy(value = family.exports.head.target)
      bridgeRejected(
        snapshot.copy(digitalAssignments = snapshot.digitalAssignments.dropRight(1) :+ raw),
        "exact lexical scope"
      )

    test("required lane witness retains exact Bits payload per-lane enable reset and alias"):
      Vector[() => Module](
        () => new Increment58GeneratedLaneRegisters,
        () => new Increment58GeneratedConcreteLaneRegisters
      ).foreach: create =>
        val snapshot = ConstructionKernel.inspect(create())
        val family = snapshot.generatedRegions.head
        val declarations = snapshot.modules.flatMap(_.declarations)
        val stored = declarations.filter(_.kind == "register")
        val domain = snapshot.modules.flatMap(_.domains).find(_.name == "clock").get
        assert(snapshot.generatedRegions.size == 1 && stored.size == 1)
        assert(stored.head.dataType.contains("Bits(8)"))
        assert(stored.head.generatedScope.exists(_.owner == family.path))
        assert(stored.head.domain.contains(domain.path))
        assert(domain.edge.contains("rising") && domain.resetPolicy.contains("sync"))
        assert(domain.attributes.toMap.apply("reset_polarity") == "active_high")
        assert(declarations.find(_.name == "enable").get.dataType.exists(_.startsWith("Vec(Bool;")))
        assert(family.exports.map(_.member) == Vector("stored"))
        assert(family.exports.head.target == stored.head.path)
        assert(snapshot.structuralIfs.size == 1 && snapshot.behavioralIfs.size == 2)
        assert(snapshot.digitalExpressions.count(_.operation == "digital_not") == 1)
        assert(snapshot.digitalAssignments.size == 4)
        assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.ordinal == "0")
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 1)
        assert(text.contains("!nodal.bits<8>"))
