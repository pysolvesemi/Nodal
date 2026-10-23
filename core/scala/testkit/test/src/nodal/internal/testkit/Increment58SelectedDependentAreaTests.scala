package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58SelectedDependentAreaTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, detail: String): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.getMessage.contains(detail))

  val tests = Tests:
    test("direct and saved selected domains do not change the original template cardinality"):
      Vector(false, true).foreach: nonunit =>
        val snapshot = ConstructionKernel.inspect(new Increment58SelectedDependentArea(
          1,
          if nonunit then 4 else 1,
          nonunit
        ))
        assert(snapshot.generatedRegions.map(_.maximumTripCount) ==
          (if nonunit then Vector(3L, 7L) else Vector(3L, 3L)))
        val members = snapshot.digitalExpressions.flatMap(_.generatedMember)
        assert(members.size == 2, members.map(_.selectionPrefix).distinct.size == 1)
        assert(members.forall(_.selectionPrefix.head.ordinal == "1"))
        assert(members.forall(_.ordinal == (if nonunit then "4" else "1")))
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("symbolic ordinal lower and positive step retain exact parameter identities"):
      Vector[() => Module](
        () => new Increment58SymbolicSelectedDependentArea,
        () => new Increment58SymbolicStepSelectedDependentArea
      ).foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        assert(snapshot.generatedRegions.size == 2)
        assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.selectionPrefix.size ==
          1)
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("active outer induction stays in its real scope after prior selected substitution"):
      val snapshot = ConstructionKernel.inspect(new Increment58ActiveSelectedDependentArea)
      val outer = snapshot.generatedRegions.head
      val middle = snapshot.generatedRegions(1)
      assert(snapshot.generatedRegions.size == 3)
      assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.ordinal == "2")
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      val captured = middle.digitalBody.get.arguments.find(argument =>
        argument.role == "static_capture" && argument.dataType == "Integer"
      ).get
      val forged = snapshot.copy(parameterExpressions =
        snapshot.parameterExpressions.map(value =>
          if value.owner == snapshot.root && value.region.contains(middle.path) then
            value.copy(operands =
              value.operands.map(path => if path == captured.path then outer.induction else path)
            )
          else value
        )
      )
      rejected(forged, "exact lexical scope")
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58ActiveSelectedDependentArea(3))
      ).failed.get
      assert(failure.getMessage.contains("NODAL-AREA-058-INDEX"))
    test("actual guarded selected existence cannot hoist away predicate authority"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedSelectedDependentArea(true))
      val selected = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(selected.region.contains(snapshot.structuralIfs.head.trueArm.path))
      assert(selected.generatedMember.get.familyInput.nonEmpty)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58GuardedSelectedDependentArea(false))
      ).failed.get
      assert(failure.getMessage.contains("NODAL-AREA-058-INDEX"))
    test("bridge rejects missing duplicate swapped foreign and invalid ordered prefixes"):
      val snapshot = ConstructionKernel.inspect(new Increment58SelectedDependentArea(1, 1))
      val example = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      val member = example.generatedMember.get
      Vector(
        member.copy(selectionPrefix = Vector.empty) -> "handle-definition scope",
        member.copy(selectionPrefix = member.selectionPrefix ++ member.selectionPrefix) ->
          "chain is discontinuous",
        member.copy(selectionPrefix = member.selectionPrefix.map(_.copy(family = member.family))) ->
          "handle-definition scope",
        member.copy(selectionPrefix =
          member.selectionPrefix.map(_.copy(family = snapshot.root + ".foreign"))
        ) -> "root is absent",
        member.copy(selectionPrefix = member.selectionPrefix.map(_.copy(ordinal = "3"))) ->
          "every legal parameter setting",
        member.copy(ordinal = "2") -> "every legal parameter setting"
      ).foreach: (changed, detail) =>
        rejected(
          snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(value =>
              if value.path == example.path then value.copy(generatedMember = Some(changed))
              else value
            )
          ),
          detail
        )
      val pair = ConstructionKernel.inspect(new Increment58ForeignSelectedDependentArea)
      val examples = pair.digitalExpressions.filter(_.generatedMember.nonEmpty)
      assert(examples.size == 2)
      val original = examples(1)
      val forged = pair.copy(digitalExpressions =
        pair.digitalExpressions.map(value =>
          if value.path == original.path then
            value.copy(generatedMember =
              value.generatedMember.map(_.copy(
                selectionPrefix = examples.head.generatedMember.get.selectionPrefix
              ))
            )
          else value
        )
      )
      rejected(forged, "exact export type and selected family chain")
    test("each source ordinal requires all legal selected existence"):
      Vector((0, 1, false), (1, 2, false), (3, 0, false), (1, 5, true), (-1, 0, false)).foreach:
        (outer, inner, nonunit) =>
          val failure =
            scala.util.Try(ConstructionKernel.inspect(new Increment58SelectedDependentArea(
              outer,
              inner,
              nonunit
            ))).failed.get
          assert(failure.getMessage.contains("NODAL-AREA-058-INDEX"))
    test("nonunit dependent registers retain one template and distinct ordinal observations"):
      val snapshot = ConstructionKernel.inspect(new Increment58NonunitDependentRegisters)
      assert(snapshot.generatedRegions.map(_.maximumTripCount) == Vector(3L, 7L))
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
      assert(snapshot.behavioralIfs.size == 1)
      val members = snapshot.digitalExpressions.flatMap(_.generatedMember)
      assert(members.map(value => value.selectionPrefix.head.ordinal -> value.ordinal).toSet ==
        Set("0" -> "0", "1" -> "4"))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test(
      "actual structural Generate permits positive symbolic step without widening procedural loops"
    ):
      Vector[() => Module](
        () => new Increment58SymbolicDomainRegisters,
        () => new Increment58SymbolicBoundsFixedStepRegisters
      ).foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        assert(snapshot.generatedRegions.size == 1, snapshot.digitalLoops.isEmpty)
        assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      val failure = scala.util.Try(ConstructionKernel.inspect(new ParameterStepLoop)).failed.get
      assert(failure.getMessage.contains("parameter-independent fixed DAG"))
