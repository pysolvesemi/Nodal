import nodal.*

final class PureCollectionStructuralWhen extends Module:
  val mode = param(0.integer, range = 0 to 1)
  val enable = in(Bool)
  val input = in(Vec(UInt(8), 3))
  val stored = Reg(0.U(8))
  val output = input.map: element =>
    if mode == 0 then
      when(enable):
        stored := element
    element

final class ProceduralLoopStructuralWhen extends Module:
  val mode = param(0.integer, range = 0 to 1)
  val enable = in(Bool)
  val stored = Reg(false.B)
  loop(LoopBound.Static(3)): index =>
    val _ = index
    if mode == 0 then
      when(enable):
        stored := true.B
