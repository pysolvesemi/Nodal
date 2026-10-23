package nodal.guarded.summary
import nodal.*
final class GuardedSummaryLibrary(flag: Expr[Integer], data: Expr[UInt]) extends Area:
  val output = wire(UInt(8))
  if flag == 1 then
    val rows =
      for r <- hdlRange(0, 2) yield new Area:
        val _ = r
        val lanes =
          for i <- hdlRange(0, 2) yield new Area:
            val _ = i
            val stored = wire(UInt(8))
            stored := data
    output := rows(1).lanes(0).stored
  else output := data
