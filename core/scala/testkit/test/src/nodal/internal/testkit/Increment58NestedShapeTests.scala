package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import utest.*
object Increment58NestedShapeTests extends TestSuite:
  private def plan(value: ConstructionSnapshot): DigitalValueMlir.Plan =
    val material = new DigitalMaterializationMlir.Plan(value)
    val enums = new EnumMlir.Plan(value.enumDefinitions, value.root, material.metadata)
    val physical = new PhysicalControlMlir.Resolver(value)
    val clocks = new ClockMlir.Resolver(value, physical)
    new DigitalValueMlir.Plan(
      value,
      enums,
      clocks,
      new HierarchyMlir.Plan(value),
      new ClockRelationshipMlir.Plan(value, clocks)
    )
  val tests = Tests:
    test("full outer indexing preserves an independently shaped Vec element"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedShapeSource)
      assert(snapshot.shapeIndices.size == 2)
      assert(snapshot.shapeIndices.forall(_.resultDimensions.isEmpty))
      assert(snapshot.shapeIndices(1).input == snapshot.shapeIndices.head.path)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("nodal.shape_index"))
    test("nested element does not flatten rank or weaken bounds ownership and typed provenance"):
      for make <- Vector[() => Module](
          () => new Increment58NestedShapeInnerBound,
          () => new Increment58NestedShapeOuterRank,
          () => new Increment43ForgedShapedExpression(false),
          () => new Increment43ForgedShapedExpression(true),
          () => new Increment43ForgedViewResult,
          () => new Increment43ForeignShapeCarrier,
          () => new Increment43InvalidRowRank,
          () => new Increment43NegativeShapeIndex,
          () => new Increment43UnprovedSymbolicIndex
        )
      do
        val failure = scala.util.Try(ConstructionKernel.inspect(make())).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("NODAL-SHAPE-043"))
    test("actual generated induction remains static through behavioral captures"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedIndexRoleSource)
      val owner = plan(snapshot)
      val induction = snapshot.generatedRegions.head.induction
      val captured = snapshot.behavioralIfs.head.trueArm.arguments.find(_.dataType == "Integer").get
      assert(captured.role == "static_capture", owner.isGeneratedIndex(induction))
      assert(owner.isGeneratedIndex(captured.path))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("procedural induction cannot forge generated static capture authority"):
      val snapshot = ConstructionKernel.inspect(new Increment58ProceduralIndexRoleSource)
      val loop = snapshot.digitalLoops.head
      assert(!plan(snapshot).isGeneratedIndex(loop.arguments.head.path))
      val path = snapshot.root + ".forged_when"
      val condition = path + ".condition"
      val predicate = KernelDigitalExpressionSnapshot(
        condition,
        snapshot.root,
        "boolean",
        Vector.empty,
        "Bool",
        Some("true"),
        None,
        Vector.empty,
        region = Some(loop.path)
      )
      val whenTrue = KernelStructuralArmSnapshot(
        path + ".then",
        Vector(loop.arguments.head.path),
        Vector(KernelDigitalRegionArgument(path + ".then.arg0", "Integer", "static_capture")),
        None
      )
      val whenFalse = KernelStructuralArmSnapshot(path + ".else", Vector.empty, Vector.empty, None)
      val forged = snapshot.copy(
        digitalExpressions = snapshot.digitalExpressions :+ predicate,
        behavioralIfs = Vector(KernelBehavioralIfSnapshot(
          path,
          snapshot.root,
          Some(loop.path),
          condition,
          whenTrue,
          whenFalse,
          10,
          path
        ))
      )
      val failure = scala.util.Try(plan(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])
      assert(failure.getMessage.contains(
        "behavioral ancestry requires actual sequential, structural or family regions"
      ))
