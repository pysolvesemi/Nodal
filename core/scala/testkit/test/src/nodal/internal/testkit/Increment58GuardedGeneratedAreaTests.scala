package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import utest.*
object Increment58GuardedGeneratedAreaTests extends TestSuite:
  private def guarded(snapshot: ConstructionSnapshot): KernelGeneratedRegionSnapshot =
    snapshot.generatedRegions.find(_.digitalBody.exists(_.parentRegion.exists(path =>
      snapshot.structuralIfs.exists(value => value.trueArm.path == path)
    ))).get
  private def bridgeReject(snapshot: ConstructionSnapshot, message: String): Unit =
    val outcome = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot))
    assert(outcome.isFailure)
    assert(outcome.failed.get.getMessage.contains(message))
  val tests = Tests:
    test("guarded family retains actual arm and independently captured upper bound"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedFamilySource)
      val family = guarded(snapshot)
      val arm = snapshot.structuralIfs.find(_.trueArm.path == family.lexicalParent.get).get.trueArm
      assert(family.parent.isEmpty && family.lexicalParent.nonEmpty)
      assert(family.path.startsWith(arm.path + "."))
      assert(family.upperExclusive == arm.arguments.find(_.role == "static_capture").get.path)
      val projection = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(projection.region == family.lexicalParent)
      assert(snapshot.behavioralIfs.exists(_.trueArm.captures.contains(projection.path)))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.generated_member"))
    test("nested generated ancestry remains separate from intervening structural arm"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedNestedFamilySource)
      val inner = guarded(snapshot)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      assert(inner.parent.contains(outer.path) && inner.lexicalParent != inner.parent)
      assert(snapshot.structuralIfs.find(_.trueArm.path ==
        inner.lexicalParent.get).get.parentRegion.contains(outer.path))
      assert(snapshot.generatedRegions.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(
        snapshot
      ).text.linesIterator.count(_.contains("\"nodal.generate\"")) == 2)
    test("closed guarded helper keeps read-only export and one retained body"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedHelperSource)
      assert(snapshot.generatedRegions.size == 1)
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.access == "read_only")
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("access = \"read\""))
    test("independently maybe-empty family may exist without selected member"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedMaybeEmptySource(false))
      assert(snapshot.generatedRegions.size == 1)
      assert(snapshot.digitalExpressions.forall(_.generatedMember.isEmpty))
      assert(snapshot.generatedRegions.head.maximumTripCount == 8)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.generated_export"))
    test("guard-dependent lane zero uses exact lexical presence without narrowing global bounds"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedMaybeEmptySource(true))
      val paths = snapshot.areas.map(_.path).toSet
      assert(paths == Set(snapshot.root + ".generate_0.lanes", snapshot.root + ".generate_1.lanes"))
      assert(snapshot.generatedRegions.flatMap(_.yieldedArea).toSet == paths)
      assert(snapshot.areas.flatMap(_.declarations).distinct.size == 2)
      val projection = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      val family =
        snapshot.generatedRegions.find(_.path == projection.generatedMember.get.family).get
      assert(family.lexicalParent.nonEmpty && projection.region == family.lexicalParent)
      val count = snapshot.modules.head.declarations.find(_.name == "count").get
      assert(count.attributes.contains("integer_range_lower" -> "0"))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.generated_member"))
    test("forged lexical ancestry raw bounds and escaping projections reject"):
      for make <- Vector[() => Module](
          () => new Increment58GuardedFamilySource,
          () => new Increment58GuardedNestedFamilySource
        )
      do
        val snapshot = ConstructionKernel.inspect(make())
        val family = guarded(snapshot)
        val conditional =
          snapshot.structuralIfs.find(_.trueArm.path == family.lexicalParent.get).get
        def changed(value: KernelGeneratedRegionSnapshot): ConstructionSnapshot =
          snapshot.copy(generatedRegions =
            snapshot.generatedRegions.map(original =>
              if original.path == family.path then value else original
            )
          )
        bridgeReject(
          changed(family.copy(digitalBody = family.digitalBody.map(_.copy(parentRegion = None)))),
          "handle-definition scope"
        )
        bridgeReject(
          changed(family.copy(digitalBody =
            family.digitalBody.map(_.copy(parentRegion = Some(conditional.falseArm.path)))
          )),
          "handle-definition scope"
        )
        bridgeReject(
          changed(family.copy(parent = Some(family.path))),
          "actual lexical parent chain"
        )
        bridgeReject(
          changed(family.copy(digitalBody =
            family.digitalBody.map(_.copy(parentRegion =
              Some(snapshot.behavioralIfs.head.trueArm.path)
            ))
          )),
          "behavioral body cannot create generated structure"
        )
        val count = snapshot.modules.head.declarations.find(_.name == "count").get.path
        bridgeReject(changed(family.copy(upperExclusive = count)), "explicit captures")
        val projection =
          snapshot.digitalExpressions.find(_.generatedMember.exists(_.family == family.path)).get
        bridgeReject(
          snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(original =>
              if original.path == projection.path then original.copy(region = family.parent)
              else original
            )
          ),
          "lexical scope"
        )
    test("guarded inner handle cannot be forged into a direct family export"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedNestedFamilySource)
      val inner = guarded(snapshot)
      val outer = snapshot.generatedRegions.find(_.path == inner.parent.get).get
      bridgeReject(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(original =>
            if original.path == outer.path then
              original.copy(exports =
                original.exports :+
                  KernelGeneratedAreaExport("forged", inner.path, "read_only", "family")
              )
            else original
          )
        ),
        "exact direct storage"
      )
