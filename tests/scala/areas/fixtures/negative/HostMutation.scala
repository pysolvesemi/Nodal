import nodal.*

final class AreaHostCell(var value: Int)

final class AreaHostMutation extends Module:
  val enabled = in(Bool)
  val group = new Area:
    val cell = new AreaHostCell(0)
  when(enabled):
    group.cell.value += 1
