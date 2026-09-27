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
