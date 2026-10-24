package nodal_external
import nodal.*

/** Source controls use the same authored child and Connect role as the positive witness. */
final class NestedConservativeShapeControl(
    outerLower: Int = 0,
    outerStep: Int = 1,
    innerLower: Int = 0,
    innerStep: Int = 1,
    symbolicOuter: Boolean = false,
    symbolicInner: Boolean = false,
    rowOrdinal: Int = 0,
    columnOrdinal: Int = 0
) extends Module:
  val extent = param(3.integer, range = 2 to 4)
  val outerUpper: Int | Expr[Integer] = if symbolicOuter then extent else 2
  val innerUpper: Int | Expr[Integer] = if symbolicInner then extent else 3
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val rows =
    for row <- hdlRange(outerLower, outerUpper, outerStep) yield new Area:
      val _ = row
      val columns =
        for column <- hdlRange(innerLower, innerUpper, innerStep) yield new Area:
          val _ = column
          val child = instance(new NestedConservativeLeaf).domain(core)
          private val body = child(_.endpoint)
          val _ = body.connectTerminal(NestedConservativeFields.p)
  private val chosen = rows(rowOrdinal).columns(columnOrdinal).child
  val selected = chosen(_.endpoint)

final class NestedConservativeParentActual(expression: Boolean) extends Module:
  val parentGain = param(5.0.real)
  val actual = if expression then parentGain + 1.0.real else parentGain
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val columns =
        for column <- hdlRange(0, 3) yield new Area:
          val _ = column
          val child = instance(new NestedConservativeLeaf).domain(core).param(_.gain, actual)
          private val body = child(_.endpoint)
          val _ = body.connectTerminal(NestedConservativeFields.p)
  private val chosen = rows(0).columns(0).child
  val selected = chosen(_.endpoint)

final class NestedConservativeLocalControl extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val columns =
        for column <- hdlRange(0, 3) yield new Area:
          val _ = column
          private val local = interfacePort(NestedConservativeFields.pins,
            NestedConservativeFields.connected, "local", core)
          val _ = local.connectTerminal(NestedConservativeFields.p)
