package nodal.internal.testkit

import nodal.*

import utest.*

final class OverrideIntegrationChild extends Module:
  val gain: Param[Real] = param(1.0.V)
  val count: Param[UInt] = param(0.U(8))

final class ValidOverrideIntegrationTop extends Module:
  val parentGain: Param[Real] = param(2.0.V)
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.gain, parentGain + 1.0.V)
  child.param(_.count, 3.U(8))

final class FixedOverrideReplicationTop extends Module:
  val children: Vector[Instance[OverrideIntegrationChild]] =
    Vector.tabulate(3): index =>
      val child = instance(new OverrideIntegrationChild)
      child.param(_.count, (index + 1).U(8))
      child

final class DuplicateOverrideIntegrationTop extends Module:
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.gain, 2.0.V)
  child.param(_.gain, 3.0.V)

final class ForeignTargetOverrideIntegrationTop extends Module:
  val parentGain: Param[Real] = param(2.0.V)
  val left: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  val right: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  left.param(_ => right(_.gain), parentGain)

final class ChildOwnedValueOverrideIntegrationTop extends Module:
  val left: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  val right: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  left.param(_.gain, right(_.gain))

final class DynamicValueOverrideIntegrationTop extends Module:
  val dynamicGain: Signal[Real] = wire(Real)
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.gain, dynamicGain)

final class StatefulValueOverrideIntegrationTop extends Module:
  val parentGain: Param[Real] = param(2.0.V)
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)

  analog:
    val changed = transition(parentGain)
    child.param(_.gain, changed)

final class WidthMismatchOverrideIntegrationTop extends Module:
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.count, 3.U(4))

final class DimensionMismatchOverrideIntegrationTop extends Module:
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.gain, 2.0.A)

final class ParentArithmeticDefaultOverrideIntegrationTop(wrongUnits: Boolean) extends Module:
  val parentGain: Param[Real] =
    if wrongUnits then param(1.0.A + 1.0.A) else param(1.0.V + 1.0.V)
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  child.param(_.gain, parentGain)

final class ArithmeticDefaultOverrideIntegrationChild extends Module:
  val gain: Param[Real] = param(1.0.V + 1.0.V)

final class ChildArithmeticDefaultOverrideIntegrationTop(wrongUnits: Boolean) extends Module:
  val child: Instance[ArithmeticDefaultOverrideIntegrationChild] =
    instance(new ArithmeticDefaultOverrideIntegrationChild)
  child.param(_.gain, if wrongUnits then 3.0.A else 3.0.V)

final class ZeroSumOverrideIntegrationTop(form: Int) extends Module:
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  val sum: Expr[Real] = form match
    case 0 => 1.0.V + 0.0.V
    case 1 => 0.0.V + 1.0.V
    case _ => 0.0.real + 0.0.real
  child.param(_.gain, sum + 2.0.V)

final class NestedZeroMismatchOverrideIntegrationTop(zeroFirst: Boolean) extends Module:
  val child: Instance[OverrideIntegrationChild] = instance(new OverrideIntegrationChild)
  val sum: Expr[Real] =
    if zeroFirst then 0.0.A + 1.0.A else 1.0.A + 0.0.A
  child.param(_.gain, sum + 1.0.V)

final class BooleanOverrideIntegrationChild extends Module:
  val enabled: Param[Bool] = param(false.B)

final class BooleanDimensionOverrideIntegrationTop(form: Int) extends Module:
  val child: Instance[BooleanOverrideIntegrationChild] =
    instance(new BooleanOverrideIntegrationChild)
  val condition: Expr[Bool] = form match
    case 0 => (2.0.V > 1.0.V) && !(1.0.A < 0.0.A)
    case 1 => 1.0.V > 2.0.A
    case _ => (2.0.V > 1.0.V) && (1.0.V > 2.0.A)
  child.param(_.enabled, condition)

object AnalogHierarchyOverrideIntegrationTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("public param path accepts parent static expression and exact UInt literal"):
      val snapshot = ConstructionKernel.inspect(new ValidOverrideIntegrationTop)
      val top = snapshot.modules.find(_.path == "ValidOverrideIntegrationTop").get
      assert(top.instances.size == 1)
      assert(top.instances.head.parameterBindings.size == 2)

    test("fixed Scala replication remains scalar construction"):
      val snapshot = ConstructionKernel.inspect(new FixedOverrideReplicationTop)
      val top = snapshot.modules.find(_.path == "FixedOverrideReplicationTop").get
      assert(top.instances.size == 3)
      assert(top.instances.forall(_.parameterBindings.size == 1))

    test("duplicate target is rejected before recording"):
      assert(
        failure(new DuplicateOverrideIntegrationTop).diagnostic.code == "NODAL-HIERARCHY-030"
      )
      assert(ConstructionKernel.inspect(new ValidOverrideIntegrationTop).modules.nonEmpty)

    test("foreign child parameter target is rejected"):
      assert(
        failure(new ForeignTargetOverrideIntegrationTop).diagnostic.code ==
          "NODAL-PARAMETER-BINDING-017"
      )

    test("sibling-owned symbolic value is rejected"):
      assert(
        failure(new ChildOwnedValueOverrideIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-031"
      )

    test("dynamic signal dependency is rejected"):
      assert(
        failure(new DynamicValueOverrideIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-032"
      )

    test("stateful waveform operation is rejected even with parent-owned input"):
      assert(
        failure(new StatefulValueOverrideIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-037"
      )

    test("same Scala type with incompatible UInt width is rejected"):
      assert(
        failure(new WidthMismatchOverrideIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-034"
      )

    test("same Real type with incompatible physical dimension is rejected"):
      assert(
        failure(new DimensionMismatchOverrideIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-036"
      )

    test("parent arithmetic default retains Real type and symbolic override identity"):
      val snapshot =
        ConstructionKernel.inspect(new ParentArithmeticDefaultOverrideIntegrationTop(false))
      val top = snapshot.modules
        .find(_.path == "ParentArithmeticDefaultOverrideIntegrationTop").get
      val parameter = top.declarations.find(_.name == "parentGain").get
      assert(parameter.dataType.contains("Real"))
      assert(top.instances.head.parameterBindings == Vector("gain" -> parameter.path))

    test("child arithmetic default retains Real type independently of its override"):
      val snapshot =
        ConstructionKernel.inspect(new ChildArithmeticDefaultOverrideIntegrationTop(false))
      val top = snapshot.modules
        .find(_.path == "ChildArithmeticDefaultOverrideIntegrationTop").get
      val instance = top.instances.head
      val child = snapshot.modules.find(_.path == instance.childModule).get
      val parameter = child.declarations.find(_.name == "gain").get
      assert(parameter.dataType.contains("Real"))
      assert(instance.parameterBindings == Vector("gain" -> "3.0"))
      assert(parameter.attributes.toMap.get("default").exists(_ != "3.0"))

    test("arithmetic defaults retain physical dimension rejection"):
      assert(
        failure(new ParentArithmeticDefaultOverrideIntegrationTop(true)).diagnostic.code ==
          "NODAL-HIERARCHY-036"
      )
      assert(
        failure(new ChildArithmeticDefaultOverrideIntegrationTop(true)).diagnostic.code ==
          "NODAL-HIERARCHY-036"
      )

    test("zero addition preserves legal units in either order and through both-zero sums"):
      (0 to 2).foreach: form =>
        val snapshot = ConstructionKernel.inspect(new ZeroSumOverrideIntegrationTop(form))
        val top = snapshot.modules.find(_.path == "ZeroSumOverrideIntegrationTop").get
        assert(top.instances.head.parameterBindings.size == 1)

    test("a nonzero sum with zero cannot hide a nested physical dimension mismatch"):
      Vector(false, true).foreach: zeroFirst =>
        assert(
          failure(new NestedZeroMismatchOverrideIntegrationTop(zeroFirst)).diagnostic.code ==
            "NODAL-HIERARCHY-035"
        )

    test("Boolean overrides accept dimensionally valid comparisons and logical operations"):
      val snapshot = ConstructionKernel.inspect(new BooleanDimensionOverrideIntegrationTop(0))
      val top = snapshot.modules.find(_.path == "BooleanDimensionOverrideIntegrationTop").get
      assert(top.instances.head.parameterBindings.size == 1)

    test("Boolean overrides reject direct and nested comparison unit mismatches"):
      Vector(1, 2).foreach: form =>
        assert(
          failure(new BooleanDimensionOverrideIntegrationTop(form)).diagnostic.code ==
            "NODAL-HIERARCHY-035"
        )

    test("failed dimension checks leave the next construction transaction clean"):
      assert(
        failure(new NestedZeroMismatchOverrideIntegrationTop(false)).diagnostic.code ==
          "NODAL-HIERARCHY-035"
      )
      assert(
        failure(new BooleanDimensionOverrideIntegrationTop(2)).diagnostic.code ==
          "NODAL-HIERARCHY-035"
      )
      val recovered = ConstructionKernel.inspect(new BooleanDimensionOverrideIntegrationTop(0))
      assert(recovered.modules.nonEmpty)
      assert(ConstructionKernel.inspect(new ValidOverrideIntegrationTop).modules.nonEmpty)
