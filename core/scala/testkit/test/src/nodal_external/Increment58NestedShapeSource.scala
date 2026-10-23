package nodal_external
import nodal.*
trait Increment58NestedShapeClock:
  this: Module =>
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
final class Increment58NestedShapeSource extends Module, Increment58NestedShapeClock:
  val rows = param(2.integer, range = 2 to 4)
  val cols = param(3.integer, range = 2 to 4)
  val input = in(Vec(Vec(UInt(8), cols), rows))
  val output = out(UInt(8))
  output := input.at(0).at(1)
final class Increment58NestedShapeInnerBound extends Module, Increment58NestedShapeClock:
  val input = in(Vec(Vec(UInt(8), 3), 2))
  val output = out(UInt(8))
  output := input.at(0).at(3)
final class Increment58NestedShapeOuterRank extends Module, Increment58NestedShapeClock:
  val input = in(Vec(Vec(UInt(8), 3), 2))
  val output = out(Vec(UInt(8), 3))
  output := input.at(0, 1)
final class Increment58GeneratedIndexRoleSource extends Module, Increment58NestedShapeClock:
  val count = param(2.integer, range = 1 to 4)
  val input = in(Vec(UInt(8), count))
  val enables = in(Vec(Bool, count))
  val output = out(Vec(UInt(8), count))
  clock:
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := input.at(i)
        output.writable.at(i) := stored
    val _ = lanes
final class Increment58ProceduralIndexRoleSource extends Module, Increment58NestedShapeClock:
  val input = in(Vec(Bool, 2))
  val output = out(Vec(Bool, 2))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value)
