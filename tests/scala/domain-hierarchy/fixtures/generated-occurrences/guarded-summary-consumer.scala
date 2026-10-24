package nodal.guarded.summary
import nodal.*
final class GuardedSummaryConsumer extends Module:
  val flag = param(1.integer, range = 0 to 1)
  val input = in(UInt(8))
  val output = out(UInt(8))
  val rows =
    for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = new GuardedSummaryLibrary(flag, input)
  output := rows(0).child.output
