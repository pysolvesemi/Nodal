package nodal.internal.testkit

import nodal.*
import nodal.sim.*

/** Behavior-bearing reusable leaf; its gain remains an occurrence-specific parameter. */
final class Increment52HierarchyLeaf(gain: Param[Real] = 2.0) extends Module:
  val parameter: Param[Real] = gain
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  val scale = AnalogFunction("scaleVoltage", Real, PhysicalDimension.Voltage): f =>
    val voltage = f.input("voltage", Real, PhysicalDimension.Voltage)
    val factor = f.input("factor", Real)
    voltage * factor
  analog:
    I(positive, referenceNode) <+ scale(V(positive, referenceNode), gain) / 1.0.kOhm

/** A genuine nested occurrence, not a pre-flattened numerical fixture. */
final class Increment52HierarchyBranch(branchGain: Param[Real] = 2.0) extends Module:
  val parameter: Param[Real] = branchGain
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  val leaf = new Increment52HierarchyLeaf(branchGain)
  positive <> leaf.positive
  referenceNode <> leaf.referenceNode

/** Separate observable currents expose repeated/nested symbolic and literal binding mistakes. */
final class Increment52HierarchyTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val p0 = inout(Electrical)
  val p1 = inout(Electrical)
  val p2 = inout(Electrical)
  val p3 = inout(Electrical)
  val referenceNode = inout(Electrical)
  val first = new Increment52HierarchyBranch(rootGain)
  val second = new Increment52HierarchyLeaf(3.0)
  val third = new Increment52HierarchyBranch(5.0)
  val fourth = new Increment52HierarchyLeaf(rootGain)
  p0 <> first.positive
  p1 <> second.positive
  p2 <> third.positive
  p3 <> fourth.positive
  referenceNode <> first.referenceNode
  referenceNode <> second.referenceNode
  referenceNode <> third.referenceNode
  referenceNode <> fourth.referenceNode

object Increment52HierarchySource:
  def capture(): Either[SimulationError, SimulationSource] =
    SimulationSource.capture(new Increment52HierarchyTop)(model =>
      Vector(
        PortSelection("p0", model.p0),
        PortSelection("p1", model.p1),
        PortSelection("p2", model.p2),
        PortSelection("p3", model.p3),
        PortSelection("ground", model.referenceNode)
      )
    )

/** Separate event/state interaction; the previously qualified four-leaf graph stays unchanged. */
final class Increment52HierarchyStateTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  val child = new Increment52HierarchyLeaf(rootGain)
  val held = variable(Real, 0.0.V)
  positive <> child.positive
  referenceNode <> child.referenceNode
  analogProcedure:
    on(initialStep):
      held := 1.0.V
  analog:
    I(positive, referenceNode) <+ held / 1.0.kOhm
