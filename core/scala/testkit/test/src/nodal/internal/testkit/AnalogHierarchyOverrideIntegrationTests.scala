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

object AnalogHierarchyOverrideIntegrationTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("public param path accepts parent static expression and exact UInt literal"):
      val snapshot = ConstructionKernel.inspect(new ValidOverrideIntegrationTop)
      val top = snapshot.modules.find(_.path == "ValidOverrideIntegrationTop").get
      assert(top.instances.size == 1)
      assert(top.instances.head.parameters.size == 2)

    test("fixed Scala replication remains scalar construction"):
      val snapshot = ConstructionKernel.inspect(new FixedOverrideReplicationTop)
      val top = snapshot.modules.find(_.path == "FixedOverrideReplicationTop").get
      assert(top.instances.size == 3)
      assert(top.instances.forall(_.parameters.size == 1))

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
