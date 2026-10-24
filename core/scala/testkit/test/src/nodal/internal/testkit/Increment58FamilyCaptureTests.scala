package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import utest.*
object Increment58FamilyCaptureTests extends TestSuite:
  private def capture(make: () => Module): ConstructionSnapshot =
    val snapshot = ConstructionKernel.inspect(make())
    val arguments = snapshot.structuralIfs.flatMap(value => Vector(value.trueArm, value.falseArm))
      .flatMap(_.arguments).filter(_.role == "family_capture")
    assert(arguments.nonEmpty, arguments.forall(_.dataType == "GeneratedFamily"))
    val members = snapshot.digitalExpressions.filter(_.generatedMember.nonEmpty)
    assert(members.nonEmpty, members.forall(_.generatedMember.get.familyInput.nonEmpty))
    assert(members.forall(value =>
      arguments.exists(argument =>
        value.generatedMember.get.familyInput.contains(argument.path)
      )
    ))
    assert(snapshot.generatedRegions.head.lexicalParent.isEmpty)
    val count = snapshot.modules.head.declarations.find(_.name == "count").get
    assert(count.attributes.contains("integer_range_lower" -> "0"))
    assert(count.attributes.contains("default" -> "0"))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    assert(text.contains("!nodal.generated_family"), text.contains("nodal.generated_member"))
    snapshot
  private def rejected(snapshot: ConstructionSnapshot, message: String): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.isInstanceOf[BridgeException], failure.getMessage.contains(message))
  val tests = Tests:
    test("module family retains real non-Data capture and guard without narrowing global bounds"):
      val snapshot = capture(() => new Increment58FamilyCaptureSource)
      val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(member.region.contains(snapshot.structuralIfs.head.trueArm.path))
      assert(snapshot.behavioralIfs.exists(_.trueArm.captures.contains(member.path)))
    test("false arms and nested actual capture edges preserve one root"):
      val falsy = capture(() => new Increment58FamilyCaptureFalse)
      assert(falsy.structuralIfs.head.falseArm.arguments.exists(_.role == "family_capture"))
      val nested = capture(() => new Increment58FamilyCaptureNested)
      val arms = nested.structuralIfs.flatMap(value => Vector(value.trueArm, value.falseArm))
      val familyArgs = arms.flatMap(_.arguments).filter(_.role == "family_capture")
      assert(familyArgs.size == 2)
      assert(arms.exists(arm =>
        familyArgs.exists(argument => arm.captures.contains(argument.path))
      ))
    test("ordinary Data-result conditional never yields the family capability"):
      val snapshot = capture(() => new Increment58FamilyCaptureValue)
      assert(snapshot.structuralIfs.head.dataType.contains("UInt(8)"))
      assert(snapshot.structuralIfs.head.trueArm.yieldValue.exists(path =>
        snapshot.digitalExpressions.exists(value =>
          value.path == path && value.generatedMember.nonEmpty
        )
      ))
    test("saved selected nested family retains original prefix and root capture"):
      val snapshot = capture(() => new Increment58FamilyCaptureSelected)
      val member = snapshot.digitalExpressions.flatMap(_.generatedMember).head
      assert(member.selectionPrefix.size == 1, member.selectionPrefix.head.ordinal == "0")
      assert(member.selectionPrefix.head.member == "inner")
      assert(member.selectionPrefix.head.family == snapshot.generatedRegions.head.path)
    test("two actual family roots cannot borrow each other's capture"):
      val snapshot = capture(() => new Increment58FamilyCaptureTwo)
      val members = snapshot.digitalExpressions.filter(_.generatedMember.nonEmpty)
      val first = members.head
      val wrong = members.last.generatedMember.get.familyInput
      assert(wrong != first.generatedMember.get.familyInput)
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == first.path then
              value.copy(generatedMember =
                value.generatedMember.map(_.copy(familyInput = wrong))
              )
            else value
          )
        ),
        "exact root producer"
      )
    test("missing raw and mismatched capture facts reject independently"):
      val snapshot = capture(() => new Increment58FamilyCaptureSource)
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            value.copy(generatedMember = value.generatedMember.map(_.copy(familyInput = None)))
          )
        ),
        "handle-definition scope"
      )
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            value.copy(generatedMember =
              value.generatedMember.map(_.copy(
                familyInput = Some(snapshot.root + ".enable")
              ))
            )
          )
        ),
        "actual non-Data structural capture"
      )
      rejected(
        snapshot.copy(structuralIfs =
          snapshot.structuralIfs.map(value =>
            value.copy(trueArm =
              value.trueArm.copy(arguments =
                value.trueArm.arguments.map(argument =>
                  if argument.role == "family_capture" then argument.copy(role = "capture")
                  else argument
                )
              )
            )
          )
        ),
        "exact capture role"
      )
    test("inclusive-zero predicate does not manufacture existence"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new Increment58FamilyCaptureBadGuard)).failed.get
      assert(
        failure.isInstanceOf[ConstructionException],
        failure.getMessage.contains("NODAL-AREA-058-INDEX")
      )
    test("family capability cannot pass pure or procedural isolation boundaries"):
      for make <- Vector[() => Module](
          () => new Increment58FamilyCapturePure,
          () => new Increment58FamilyCaptureProcedural
        )
      do
        val failure = scala.util.Try(ConstructionKernel.inspect(make())).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("dominating structural existence scope"))
