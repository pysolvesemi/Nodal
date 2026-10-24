package nodal_external
import nodal.*

sealed trait NestedConservativePins extends Interface
object NestedConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val pins = Interface[NestedConservativePins]("NestedConservativePins", p, n).withRoles(connected)

final class NestedConservativeLeaf extends Module:
  val gain = param(2.0.real)
  private val observed = node(Electrical)
  private val reference = node(Electrical)
  analog:
    V(observed, reference) <+ gain * 1.0.V
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(NestedConservativeFields.pins,
    NestedConservativeFields.connected, "pins", required)

// Boundary routing and private gain emission are independent witnesses.
final class NestedConservativeSource extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val columns =
        for column <- hdlRange(0, 3) yield new Area:
          val _ = column
          val first = instance(new NestedConservativeLeaf).domain(core).param(_.gain, 3.0.real)
          val second = instance(new NestedConservativeLeaf).domain(core).param(_.gain, 7.0.real)
          private val a = first(_.endpoint)
          private val b = second(_.endpoint)
          a.connectTerminal(NestedConservativeFields.p).connectTo(b.connectTerminal(NestedConservativeFields.p))
          a.connectTerminal(NestedConservativeFields.n).connectTo(b.connectTerminal(NestedConservativeFields.n))
  private val first00 = rows(0).columns(0).first
  private val first01 = rows(0).columns(1).first
  private val first10 = rows(1).columns(0).first
  private val second00 = rows(0).columns(0).second
  val selected00 = first00(_.endpoint)
  val selected01 = first01(_.endpoint)
  val selected10 = first10(_.endpoint)
  val selectedSecond00 = second00(_.endpoint)
