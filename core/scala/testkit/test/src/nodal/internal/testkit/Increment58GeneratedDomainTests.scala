package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*
object Increment58GeneratedDomainTests extends TestSuite:
  val tests = Tests:
    test("nonzero and nonunit bounds retain actual coordinates separately from ordinals"):
      Vector[() => Module](
        () => new Increment58GeneratedNonzeroBounds,
        () => new Increment58GeneratedNonunitBounds
      ).foreach: make =>
        val snapshot = ConstructionKernel.inspect(make())
        val family = snapshot.generatedRegions.head
        assert(
          snapshot.generatedRegions.size == 1,
          family.lower == "1",
          family.maximumTripCount == 3
        )
        assert(snapshot.digitalExpressions.flatMap(_.generatedMember).map(_.ordinal) ==
          Vector("0", "1", "2"))
        assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("concrete empty template is retained and never supplies a selected representative"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedEmptyBounds)
      assert(
        snapshot.generatedRegions.size == 1,
        snapshot.generatedRegions.head.maximumTripCount == 0
      )
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).isEmpty)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("maybe empty selection and guarded declaration retain their actual arm identity"):
      Vector[() => Module](
        () => new Increment58GeneratedMaybeEmptyBounds,
        () => new Increment58GeneratedGuardedBounds
      ).foreach: make =>
        val snapshot = ConstructionKernel.inspect(make())
        assert(snapshot.generatedRegions.size == 1, snapshot.structuralIfs.size == 1)
        val selected = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
        assert(selected.region.contains(snapshot.structuralIfs.head.trueArm.path))
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("active ancestor bounds use one template with bounded original cardinality"):
      Vector[() => Module](
        () => new Increment58GeneratedCapturedBounds,
        () => new Increment58GeneratedCapturedNonunitBounds,
        () => new Increment58GeneratedCapturedSelectedBounds
      ).foreach: make =>
        val snapshot = ConstructionKernel.inspect(make())
        assert(snapshot.generatedRegions.size == 2)
        assert(snapshot.generatedRegions(1).parent.contains(snapshot.generatedRegions.head.path))
        assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
        assert(
          snapshot.digitalExpressions.flatMap(_.generatedMember).forall(_.selectionPrefix.size == 1)
        )
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("direct and nested generated scalar operators retain their enclosing typed context"):
      Vector[() => Module](
        () => new Increment58GeneratedGuardedScalarExpression,
        () => new Increment58GeneratedGuardedSymbolicExpression,
        () => new Increment58GeneratedGuardedNestedExpression
      ).foreach: make =>
        val snapshot = ConstructionKernel.inspect(make())
        assert(snapshot.digitalExpressions.exists(_.operation == "digital_xor"))
        assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("independent nested guards retain both actual arms and complete output drivers"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGuardedRegisters)
      assert(snapshot.structuralIfs.size == 2, snapshot.generatedRegions.size == 1)
      val outer = snapshot.structuralIfs.find(_.parentRegion.isEmpty).get
      val inner = snapshot.structuralIfs.find(_.parentRegion.nonEmpty).get
      assert(inner.parentRegion.contains(outer.trueArm.path))
      assert(
        snapshot.generatedRegions.head.digitalBody.get.parentRegion.contains(inner.trueArm.path)
      )
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).size == 1)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
