package nodal.increment42fixture

import nodal.*

/** Public-only hierarchy child shared by the predecessor-combination witnesses. */
final class HierarchyCombinationLeaf(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)

/** Hierarchy plus a parent-owned declarative analog equation. */
final class HierarchyEquationTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val resistance: Param[Real] = param(1.0.kOhm)
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  vin <> child.vin
  child.vout <> vout

  equations:
    equation(V(vin, vout), resistance * I(vin, vout))

/** Hierarchy plus parent-owned event control and a continuously evaluated contribution. */
final class HierarchyEventTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  val held: Variable[Real] = variable(Real, 0.0.V)
  vin <> child.vin
  child.vout <> vout

  analogProcedure:
    on(initialStep):
      held := 1.0.V

  analog:
    V(vin, vout) <+ transition(held, 0.0.ns, 1.0.ns)

/** Hierarchy plus a parent-owned module-local pure analog function. */
final class HierarchyFunctionTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  val scale = AnalogFunction("scaleSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val factor = f.input("factor", Real)
    signal * factor
  vin <> child.vin
  child.vout <> vout

  analog:
    V(vin, vout) <+ scale(1.0.V, rootGain)

/** Repeated compatible children with distinct symbolic and literal actuals. */
final class HierarchyRepeatedTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin0: Node[Electrical.type] = in(Electrical)
  val vout0: Node[Electrical.type] = out(Electrical)
  val vin1: Node[Electrical.type] = in(Electrical)
  val vout1: Node[Electrical.type] = out(Electrical)
  val vin2: Node[Electrical.type] = in(Electrical)
  val vout2: Node[Electrical.type] = out(Electrical)
  val vin3: Node[Electrical.type] = in(Electrical)
  val vout3: Node[Electrical.type] = out(Electrical)
  val first: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  val second: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = 3.0)
  val third: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = 5.0)
  val fourth: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  vin0 <> first.vin
  first.vout <> vout0
  vin1 <> second.vin
  second.vout <> vout1
  vin2 <> third.vin
  third.vout <> vout2
  vin3 <> fourth.vin
  fourth.vout <> vout3

/** A fixed nested hierarchy level used to exercise bounded emitted depth. */
final class HierarchyNestedBranch(branchGain: Param[Real] = 2.0) extends Module:
  val parameter: Param[Real] = branchGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val leaf: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = branchGain)
  vin <> leaf.vin
  leaf.vout <> vout

/** Three emitted module levels with repeated compatible branches and leaves. */
final class HierarchyNestedTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin0: Node[Electrical.type] = in(Electrical)
  val vout0: Node[Electrical.type] = out(Electrical)
  val vin1: Node[Electrical.type] = in(Electrical)
  val vout1: Node[Electrical.type] = out(Electrical)
  val firstBranch: HierarchyNestedBranch = new HierarchyNestedBranch(branchGain = rootGain)
  val secondBranch: HierarchyNestedBranch = new HierarchyNestedBranch(branchGain = 6.0)
  vin0 <> firstBranch.vin
  firstBranch.vout <> vout0
  vin1 <> secondBranch.vin
  secondBranch.vout <> vout1
