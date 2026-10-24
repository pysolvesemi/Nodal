import nodal.*

final class AreaCapabilityCast extends Module:
  val enabled = in(Bool)
  val group = new Area:
    val source: Expr[Bool] = enabled
  when(enabled):
    group.source.asInstanceOf[Signal[Bool]] := true.B

final class AreaDatatypeCast extends Module:
  val enabled = in(Bool)
  val group = new Area:
    val target = wire(Bits(8))
  when(enabled):
    group.target.asInstanceOf[Signal[Bool]] := true.B
