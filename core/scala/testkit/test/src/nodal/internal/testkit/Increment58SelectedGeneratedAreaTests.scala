package nodal.internal.testkit
import nodal.*
import nodal_external.*
final class Increment58SelectedNestedForged(action: Int) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield
        val _ = i
        new Increment58SelectedNamedArea
    val selected = outer(0).inner
    if action == 0 then
      val original = selected.generatedRegion.familyHandle.get
      val _ = original(0)
    else if action == 1 then
      val forged = new GeneratedAreas[Increment58SelectedNestedLeaf](
        outer.generatedRegion,
        selected.selectionPrefix
      )
      val _ = forged(0)
    else if action == 2 then
      val forged = new GeneratedAreas[Increment58SelectedNestedLeaf](
        selected.generatedRegion,
        selected.selectionPrefix.map(_.copy(ordinal = 2))
      )
      val _ = forged(0)
    else if action == 3 then
      val _ = GeneratedAreaRuntime.family[Increment58SelectedNestedLeaf](outer(0), "hidden")
    else if action == 4 then
      val _ = GeneratedAreaRuntime.member[Register[UInt]](selected(0), "readonly", "register")
    else
      val runtimeOrdinal = Reg(0.integer)
      val _ = selected(runtimeOrdinal)

import nodal.internal.bridge.*
import utest.*

object Increment58SelectedGeneratedAreaTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot): Unit =
    val outcome = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot))
    assert(outcome.isFailure)

  val tests = Tests:
    test("natural and saved family selections retain actual storage and independent access"):
      val snapshot = ConstructionKernel.inspect(new Increment58SelectedNestedAreaSource)
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val inner = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(outer.exports.size == 1)
      assert(outer.exports.head.kind == "family" && outer.exports.head.member == "inner")
      assert(outer.exports.head.target == inner.path && outer.exports.head.access == "read_only")
      val projected = snapshot.digitalExpressions.flatMap(_.generatedMember)
      assert(projected.size == 2 && projected.map(_.access).toSet == Set("read_only", "read_write"))
      assert(projected.forall(value => value.family == inner.path && value.ordinal == "1"))
      assert(projected.forall(_.selectionPrefix ==
        Vector(KernelGeneratedFamilySelection(outer.path, "0", "inner"))))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("kind = \"family\"")) == 3)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
    test("symbolic ordinals hoist only typed selected storage into behavioral capture"):
      val snapshot = ConstructionKernel.inspect(new Increment58SelectedNestedSymbolicSource)
      val selected = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      val member = selected.generatedMember.get
      assert(selected.region.isEmpty)
      assert(member.ordinal == snapshot.root + ".innerOrdinal")
      assert(member.selectionPrefix.head.ordinal == snapshot.root + ".outerOrdinal")
      assert(snapshot.behavioralIfs.head.trueArm.captures.contains(selected.path))
      assert(!snapshot.behavioralIfs.head.trueArm.captures.exists(path =>
        snapshot.generatedRegions.exists(_.path == path)
      ))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("named helper and grouped keys preserve public aliases without hidden inventory"):
      Vector[() => Module](
        () => new Increment58SelectedNamedFamilySource,
        () => new Increment58SelectedGroupedFamilySource
      ).foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        assert(snapshot.generatedRegions.size == 2)
        assert(!snapshot.generatedRegions.flatMap(_.exports).exists(_.member == "hidden"))
        val member = snapshot.digitalExpressions.flatMap(_.generatedMember).head
        assert(member.selectionPrefix.size == 1 && member.access == "read_only")
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("three levels retain ordered actual family views without extra templates"):
      val snapshot = ConstructionKernel.inspect(new Increment58SelectedTripleFamilySource)
      assert(snapshot.generatedRegions.size == 3)
      val member = snapshot.digitalExpressions.flatMap(_.generatedMember).head
      assert(member.selectionPrefix.map(_.ordinal) == Vector("1", "2"))
      assert(member.selectionPrefix.map(_.member) == Vector("inner", "last"))
      assert(member.ordinal == "3")
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 3)
      assert(text.linesIterator.count(_.contains("\"nodal.generated_member\"(")) == 3)
    test("each selected ordinal has independent all-legal existence proof"):
      Vector((-1, 0), (2, 0), (0, -1), (0, 3)).foreach: (outer, inner) =>
        val failure = scala.util.Try(ConstructionKernel.inspect(
          new Increment58SelectedNestedAreaSource(outer, inner)
        )).failed.get
        assert(failure.getMessage.contains("NODAL-AREA-058-INDEX"))
    test(
      "raw handles discontinuous views hidden keys runtime ordinals and capability upgrades reject"
    ):
      Vector(
        0 -> "OWNER",
        1 -> "OWNER",
        2 -> "INDEX",
        3 -> "EXPORT",
        4 -> "CAPABILITY",
        5 -> "INDEX"
      )
        .foreach: (action, code) =>
          val failure = scala.util.Try(
            ConstructionKernel.inspect(new Increment58SelectedNestedForged(action))
          ).failed.get
          assert(failure.getMessage.contains("NODAL-AREA-058-" + code))
    test("bridge verifies every family kind access target and ordinal frame"):
      Vector[() => Module](
        () => new Increment58SelectedNestedAreaSource,
        () => new Increment58SelectedNestedSymbolicSource
      ).foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
        val example = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
        val member = example.generatedMember.get
        def changedMember(value: KernelGeneratedMemberSnapshot): ConstructionSnapshot =
          snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(original =>
              if original.path == example.path then original.copy(generatedMember = Some(value))
              else original
            )
          )
        rejected(changedMember(member.copy(selectionPrefix = Vector.empty)))
        rejected(changedMember(member.copy(selectionPrefix =
          member.selectionPrefix ++ member.selectionPrefix
        )))
        rejected(changedMember(member.copy(selectionPrefix =
          member.selectionPrefix.map(_.copy(ordinal = "4"))
        )))
        def changedExport(value: KernelGeneratedAreaExport): ConstructionSnapshot =
          snapshot.copy(generatedRegions =
            snapshot.generatedRegions.map(original =>
              if original.path == outer.path then original.copy(exports = Vector(value))
              else original
            )
          )
        rejected(changedExport(outer.exports.head.copy(access = "read_write")))
        rejected(changedExport(outer.exports.head.copy(kind = "storage")))
        rejected(changedExport(outer.exports.head.copy(target = outer.path)))
